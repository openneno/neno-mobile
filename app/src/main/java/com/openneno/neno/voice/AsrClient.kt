package com.openneno.neno.voice

import com.openneno.neno.data.JsJson
import com.openneno.neno.data.stringOrNull
import com.openneno.neno.sync.GithubClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class AsrException(message: String, val status: Int = 0) : Exception(message)

/**
 * DeepSeek 语音识别: https://api-docs.deepseek.com/guides/asr/
 * POST multipart/form-data, 字段 file; 返回 {request_id, text, duration_ms}. 语言自动识别, 最长 120 秒, 最大 8 MiB.
 * 与网页端 lib/voice/asr.js 一致.
 */
object AsrClient {
    const val ENDPOINT = "https://api.deepseek.com/v1/tools/asr"
    const val MAX_BYTES = 8L * 1024 * 1024

    private val http by lazy {
        GithubClient.http.newBuilder().readTimeout(150, TimeUnit.SECONDS).build()
    }

    private val MESSAGES = mapOf(
        400 to "音频格式不支持",
        401 to "API Key 无效, 请在设置中检查",
        402 to "DeepSeek 账户余额不足",
        408 to "上传超时, 请重试",
        413 to "录音文件太大(最大 8 MB)",
        429 to "请求太多, 请稍后重试",
        503 to "识别服务繁忙, 请稍后重试",
        504 to "识别超时, 请重试",
    )

    /** 发送半秒静音, 检查 API Key 是否可用(按秒计费, 约 1 秒) */
    suspend fun check(apiKey: String, cacheDir: File) {
        val file = File(cacheDir, "asr-check.wav")
        withContext(Dispatchers.IO) {
            val samples = VoiceRecorder.SAMPLE_RATE / 2
            val data = samples * 2
            val header = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
                putInt(VoiceRecorder.SAMPLE_RATE); putInt(VoiceRecorder.SAMPLE_RATE * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(data)
            }.array()
            file.writeBytes(header)
        }
        try {
            transcribe(file, apiKey)
        } finally {
            file.delete()
        }
    }

    /** 返回识别出的文字(可能为空, 例如录音里没有说话) */
    suspend fun transcribe(file: File, apiKey: String): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw AsrException("请先在设置中填写 DeepSeek API Key")
        if (file.length() > MAX_BYTES) throw AsrException(MESSAGES.getValue(413), 413)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "speech.${file.extension.ifEmpty { "wav" }}", file.asRequestBody("audio/wav".toMediaType()))
            .build()
        val request = Request.Builder().url(ENDPOINT)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .post(body)
            .build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw AsrException("网络错误, 请重试")
        }
        response.use {
            val text = it.body?.string().orEmpty()
            val json = runCatching { JsJson.parseObject(text) }.getOrNull()
            if (!it.isSuccessful) {
                val detail = (json?.get("error") as? JsonObject)?.stringOrNull("message") ?: json?.stringOrNull("message")
                throw AsrException(MESSAGES[it.code] ?: detail ?: "识别失败(${it.code})", it.code)
            }
            json?.stringOrNull("text")?.trim().orEmpty()
        }
    }
}
