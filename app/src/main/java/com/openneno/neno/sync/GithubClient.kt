package com.openneno.neno.sync

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class GithubException(message: String, val status: Int, val type: String? = null) : Exception(message)

data class RestResult(val data: JsonElement?, val headers: okhttp3.Headers, val etag: String?, val notModified: Boolean = false)

/** 基于 OkHttp 的 GitHub REST / GraphQL 客户端 */
object GithubClient {
    const val API = "https://api.github.com"

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val JSON_TYPE = "application/json".toMediaType()

    private fun Request.Builder.auth(token: String) = apply {
        header("Accept", "application/vnd.github+json")
        header("Authorization", "token ${token.trim()}")
        header("User-Agent", "neno-android")
    }

    /** REST 请求. 传入 etag 时发送条件请求, 内容没有变化时返回 notModified(不计入请求配额) */
    suspend fun rest(
        token: String,
        method: String,
        path: String,
        body: JsonElement? = null,
        query: Map<String, Any?> = emptyMap(),
        etag: String? = null,
    ): RestResult = withContext(Dispatchers.IO) {
        val url = (API + path).toHttpUrl().newBuilder().apply {
            query.forEach { (k, v) -> if (v != null) addQueryParameter(k, v.toString()) }
        }.build()
        val request = Request.Builder().url(url).auth(token)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .apply { if (etag != null) header("If-None-Match", etag) }
            .method(method, body?.let { it.toString().toRequestBody(JSON_TYPE) }
                ?: if (method == "GET" || method == "HEAD") null else ByteArray(0).toRequestBody(null))
            .build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw GithubException(e.message ?: "网络连接失败", 0)
        }
        response.use { r ->
            if (r.code == 304) return@withContext RestResult(null, r.headers, etag, notModified = true)
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw GithubException(errorMessage(text) ?: r.message, r.code)
            val data = if (r.code == 204 || text.isEmpty()) null else json.parseToJsonElement(text)
            RestResult(data, r.headers, r.header("etag"))
        }
    }

    suspend fun graphql(token: String, query: String, variables: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("query", JsonPrimitive(query))
            put("variables", variables)
        }
        val request = Request.Builder().url("$API/graphql").auth(token)
            .post(payload.toString().toRequestBody(JSON_TYPE)).build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw GithubException(e.message ?: "网络连接失败", 0)
        }
        response.use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw GithubException(errorMessage(text) ?: r.message, r.code)
            val result = json.parseToJsonElement(text).jsonObject
            val errors = result["errors"] as? JsonArray
            if (errors != null && errors.isNotEmpty()) {
                val first = errors[0].jsonObject
                throw GithubException(
                    (first["message"] as? JsonPrimitive)?.contentOrNull ?: "GraphQL error", 200,
                    (first["type"] as? JsonPrimitive)?.contentOrNull
                )
            }
            result["data"]!!.jsonObject
        }
    }

    private fun errorMessage(text: String): String? = runCatching {
        (json.parseToJsonElement(text).jsonObject["message"] as? JsonPrimitive)?.contentOrNull
    }.getOrNull()
}

/** git 对象相关的工具函数 */
object GitBlob {
    /** 与 git hash-object 相同: sha1("blob <字节数>\0" + 内容) */
    fun sha(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8))
        md.update(bytes)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    /** GitHub 返回的 base64 带有换行 */
    fun decode(text: String): ByteArray = Base64.decode(text.replace(Regex("\\s"), ""), Base64.DEFAULT)
}
