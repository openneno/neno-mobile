package com.openneno.neno.sync

import android.net.Uri
import android.util.Base64
import com.openneno.neno.data.GithubConfig
import com.openneno.neno.data.JsJson
import com.openneno.neno.data.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class GithubAccount(val login: String, val name: String, val avatar: String, val scopes: List<String>?)

data class GithubRepo(val owner: String, val repo: String, val fullName: String, val private: Boolean, val url: String)

/** 同步配置向导使用的接口: 校验 Token, 获取用户信息, 列出/创建仓库 */
object AccountApi {
    /** 经典 Token, 预先勾选权限: repo 读写仓库, workflow 用于同步到 notion 的 action */
    const val TOKEN_URL = "https://github.com/settings/tokens/new?scopes=repo,workflow&description=NENO"
    const val DEFAULT_REPO_NAME = "neno-data"
    const val WEB_URL = "https://neno.pages.dev/"

    fun repoUrl(fullName: String) = "https://github.com/$fullName"

    /** 把接口错误转换成用户能看懂的提示 */
    fun describeError(error: Throwable): String {
        val status = (error as? GithubException)?.status
        return when {
            status == 401 -> "Token 无效或已过期, 请重新生成"
            status == 403 -> "Token 权限不足, 请确认已勾选仓库读写权限"
            status == 404 -> "找不到对应的资源, 请检查 Token 的仓库访问范围"
            status == 422 -> "仓库名已存在或不合法, 请换一个名字"
            status == null || status == 0 || status >= 500 ->
                if (error is GithubException || status == null && error is java.io.IOException) "网络连接失败, 请检查网络后重试"
                else error.message ?: "未知错误"

            else -> error.message ?: "未知错误"
        }
    }

    suspend fun fetchAccount(token: String): GithubAccount {
        val re = GithubClient.rest(token, "GET", "/user")
        val data = re.data!!.jsonObject
        val scopes = re.headers["x-oauth-scopes"]
        val login = data.stringOrNull("login") ?: ""
        return GithubAccount(
            login = login,
            name = data.stringOrNull("name") ?: login,
            avatar = data.stringOrNull("avatar_url") ?: "",
            // 经典 Token 会返回授权范围, 细粒度 Token 没有这个响应头
            scopes = scopes?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() },
        )
    }

    private fun toRepo(raw: JsonObject): GithubRepo {
        val fullName = raw.stringOrNull("full_name")!!
        val (owner, repo) = fullName.split("/", limit = 2)
        return GithubRepo(
            owner, repo, fullName,
            (raw["private"] as? JsonPrimitive)?.boolean ?: false,
            raw.stringOrNull("html_url") ?: repoUrl(fullName),
        )
    }

    /** 列出 Token 可以写入的仓库, 按更新时间倒序 */
    suspend fun listRepos(token: String): List<GithubRepo> {
        val re = GithubClient.rest(token, "GET", "/user/repos", query = mapOf("sort" to "updated", "per_page" to 100))
        return (re.data as JsonArray).map { it.jsonObject }.filter { raw ->
            val p = raw["permissions"] as? JsonObject ?: return@filter true
            p["push"]?.jsonPrimitive?.boolean == true || p["admin"]?.jsonPrimitive?.boolean == true
        }.map { toRepo(it) }
    }

    /** 新建一个私有仓库, auto_init 保证仓库有默认分支和第一次提交 */
    suspend fun createRepo(token: String, name: String): GithubRepo {
        val re = GithubClient.rest(token, "POST", "/user/repos", body = buildJsonObject {
            put("name", JsonPrimitive(name))
            put("private", JsonPrimitive(true))
            put("auto_init", JsonPrimitive(true))
            put("description", JsonPrimitive("NENO 笔记数据"))
        })
        return toRepo(re.data!!.jsonObject)
    }

    /** 其他设备/插件使用的 neno token: 配置的 base64 编码, 保持原有格式 */
    fun encodeNenoToken(config: GithubConfig): String {
        val text = JsJson.stringify(buildJsonObject {
            put("access_token", JsonPrimitive(config.token))
            put("repoName", JsonPrimitive(config.repo))
            put("githubName", JsonPrimitive(config.owner))
            put("gitUrl", JsonPrimitive(GithubClient.API))
        })
        return Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    class UnsupportedConfig(message: String) : Exception(message)

    /** 解析 neno token 或同步链接 */
    fun decodeNenoToken(input: String): GithubConfig {
        var value = input.trim()
        val marker = "#neno-config="
        val index = value.indexOf(marker)
        if (index != -1) value = Uri.decode(value.substring(index + marker.length))
        val text = String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)
        val data = JsJson.parseObject(text)
        val gitUrl = data.stringOrNull("gitUrl")
        if (!gitUrl.isNullOrEmpty() && gitUrl != GithubClient.API) throw UnsupportedConfig("NENO 已不再支持 Gitee, 请改用 GitHub")
        val token = data.stringOrNull("access_token")
        val repo = data.stringOrNull("repoName")
        val owner = data.stringOrNull("githubName")
        if (token.isNullOrEmpty() || repo.isNullOrEmpty() || owner.isNullOrEmpty()) throw IllegalArgumentException("配置不完整")
        return GithubConfig(token = token, owner = owner, repo = repo)
    }

    /** 同步链接把配置放在 # 之后, 不会随请求发送到服务器 */
    fun buildSyncLink(config: GithubConfig) = "${WEB_URL}#neno-config=${Uri.encode(encodeNenoToken(config))}"
}
