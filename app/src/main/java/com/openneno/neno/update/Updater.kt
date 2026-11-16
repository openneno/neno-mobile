package com.openneno.neno.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import com.openneno.neno.BuildConfig
import com.openneno.neno.data.JsJson
import com.openneno.neno.data.Settings
import com.openneno.neno.data.stringOrNull
import com.openneno.neno.sync.GithubClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** GitHub Releases 上发布的一个版本(由 .github/workflows/release.yml 生成) */
data class AppRelease(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val apkSize: Long,
    val sha256Url: String?,
    val pageUrl: String,
)

/** 显示给用户的错误(不是 IOException, 避免被网络错误的处理吞掉) */
class UpdateException(message: String) : Exception(message)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val downloaded: Long, val total: Long) : UpdateState {
        val progress get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
    }
    data class Ready(val release: AppRelease, val file: File) : UpdateState
    data class Failed(val message: String, val release: AppRelease? = null) : UpdateState
}

/**
 * 检查更新: 读取 GitHub 上最新的 Release, 版本更高时下载 APK, 校验 SHA-256 后交给系统安装.
 * 不需要登录 GitHub(公开仓库的匿名接口每小时 60 次, 足够使用).
 */
object Updater {
    const val REPO = "openneno/neno-mobile"
    const val RELEASES_URL = "https://github.com/$REPO/releases"
    private const val LATEST_API = "${GithubClient.API}/repos/$REPO/releases/latest"
    private const val AUTO_CHECK_INTERVAL = 24L * 60 * 60 * 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** 需要弹窗提示的新版本(自动检查发现, 或用户手动检查) */
    private val _prompt = MutableStateFlow(false)
    val prompt: StateFlow<Boolean> = _prompt.asStateFlow()

    fun dismissPrompt() {
        _prompt.value = false
    }

    fun showPrompt() {
        _prompt.value = true
    }

    /** 启动时自动检查: 每天最多一次, 忽略过的版本不再提示; debug 版本不检查 */
    fun autoCheck(context: Context, settings: Settings) {
        if (BuildConfig.DEBUG || !settings.autoCheckUpdate.value) return
        val now = System.currentTimeMillis()
        if (now - settings.lastUpdateCheck in 0 until AUTO_CHECK_INTERVAL) return
        if (job?.isActive == true) return
        job = scope.launch {
            val release = runCatching { fetchLatest() }.getOrNull() ?: return@launch
            settings.lastUpdateCheck = now
            if (isNewer(release.version, BuildConfig.VERSION_NAME) && release.version != settings.ignoredUpdateVersion) {
                _state.value = cachedApk(context, release)?.let { UpdateState.Ready(release, it) } ?: UpdateState.Available(release)
                _prompt.value = true
            }
        }
    }

    /** 手动检查(设置页面) */
    fun check(context: Context, settings: Settings) {
        if (job?.isActive == true) return
        _state.value = UpdateState.Checking
        job = scope.launch {
            _state.value = try {
                val release = fetchLatest()
                settings.lastUpdateCheck = System.currentTimeMillis()
                if (isNewer(release.version, BuildConfig.VERSION_NAME)) {
                    _prompt.value = true
                    cachedApk(context, release)?.let { UpdateState.Ready(release, it) } ?: UpdateState.Available(release)
                } else UpdateState.UpToDate
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState.Failed(e.message ?: "检查更新失败")
            }
        }
    }

    fun download(context: Context, release: AppRelease) {
        if (job?.isActive == true && _state.value is UpdateState.Downloading) return
        job?.cancel()
        _state.value = UpdateState.Downloading(release, 0, release.apkSize)
        job = scope.launch {
            _state.value = try {
                UpdateState.Ready(release, downloadApk(context, release))
            } catch (e: CancellationException) {
                UpdateState.Available(release)
            } catch (e: Exception) {
                UpdateState.Failed(e.message ?: "下载失败", release)
            }
        }
    }

    fun cancelDownload() {
        val s = _state.value
        if (s is UpdateState.Downloading) {
            job?.cancel()
            _state.value = UpdateState.Available(s.release)
        }
    }

    /**
     * 打开系统安装界面. Android 8 起需要用户允许本应用"安装未知应用",
     * 没有允许时先打开对应的设置页面, 返回 false.
     */
    fun install(context: Context, file: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }

    fun openReleasePage(context: Context, release: AppRelease? = null) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(release?.pageUrl ?: RELEASES_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // ---------- 网络 ----------

    private suspend fun fetchLatest(): AppRelease = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(LATEST_API)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        val text = try {
            GithubClient.http.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> throw UpdateException("还没有发布任何版本")
                    response.code == 403 || response.code == 429 -> throw UpdateException("GitHub 请求太频繁, 请稍后再试")
                    !response.isSuccessful -> throw UpdateException("检查更新失败 (${response.code})")
                }
                response.body!!.string()
            }
        } catch (e: IOException) {
            throw UpdateException("网络错误, 请检查网络后重试")
        }
        parseRelease(JsJson.parseObject(text)) ?: throw UpdateException("最新版本中没有找到安装包")
    }

    /** 下载到 cache/update, 校验 SHA-256; 已经下载并校验过的直接使用 */
    private suspend fun downloadApk(context: Context, release: AppRelease): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        // 清理旧版本的安装包
        dir.listFiles()?.filter { it.name != release.apkName && it.name != "${release.apkName}.part" }?.forEach { it.delete() }
        val target = File(dir, release.apkName)
        val expected = release.sha256Url?.let { fetchSha256(it) }
        if (target.exists() && (expected == null || sha256(target) == expected)) return@withContext target

        val part = File(dir, "${release.apkName}.part")
        try {
            GithubClient.http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
                if (!response.isSuccessful) throw UpdateException("下载失败 (${response.code})")
                val body = response.body!!
                val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastReport = 0L
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            downloaded += n
                            if (downloaded - lastReport >= 32 * 1024 || downloaded == total) {
                                lastReport = downloaded
                                _state.value = UpdateState.Downloading(release, downloaded, total)
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            part.delete()
            throw UpdateException("网络中断, 请重试")
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
        if (expected != null && sha256(part) != expected) {
            part.delete()
            throw UpdateException("安装包校验失败, 请重试")
        }
        target.delete()
        if (!part.renameTo(target)) throw UpdateException("保存安装包失败")
        target
    }

    private fun fetchSha256(url: String): String? = runCatching {
        GithubClient.http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) null
            else parseSha256(response.body!!.string())
        }
    }.getOrNull()

    /** 已经下载好的安装包(上次下载后没有安装) */
    private suspend fun cachedApk(context: Context, release: AppRelease): File? = withContext(Dispatchers.IO) {
        val file = File(File(context.cacheDir, "update"), release.apkName)
        if (!file.exists()) return@withContext null
        val expected = release.sha256Url?.let { fetchSha256(it) } ?: return@withContext null
        file.takeIf { sha256(it) == expected }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ---------- 解析(纯函数, 有单元测试) ----------

    /** "v1.0.12" -> [1, 0, 12]; 比较时缺少的部分当作 0 */
    fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V").substringBefore('-')
            .split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(latest)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun parseRelease(json: JsonObject): AppRelease? {
        val tag = json.stringOrNull("tag_name") ?: return null
        val assets = (json["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val apk = assets.firstOrNull { it.stringOrNull("name")?.endsWith(".apk") == true } ?: return null
        val apkName = apk.stringOrNull("name")!!
        val sha = assets.firstOrNull { it.stringOrNull("name") == "$apkName.sha256" }
        return AppRelease(
            version = tag.removePrefix("v"),
            notes = releaseNotes(json.stringOrNull("body").orEmpty()),
            apkUrl = apk.stringOrNull("browser_download_url") ?: return null,
            apkName = apkName,
            apkSize = (apk["size"] as? JsonPrimitive)?.longOrNull ?: 0L,
            sha256Url = sha?.stringOrNull("browser_download_url"),
            pageUrl = json.stringOrNull("html_url") ?: RELEASES_URL,
        )
    }

    /** 只保留"更新内容"部分的列表, 去掉安装说明和 commit 前缀(feat: / fix: ...) */
    fun releaseNotes(body: String): String {
        val lines = body.replace("\r\n", "\n").lines()
        val start = lines.indexOfFirst { it.trim().startsWith("## 更新内容") }
        val section = if (start < 0) lines else lines.drop(start + 1).takeWhile { !it.trim().startsWith("## ") }
        return section.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n") { line ->
            if (line.startsWith("- ")) "• " + line.removePrefix("- ").replace(COMMIT_PREFIX, "") else line
        }
    }

    private val COMMIT_PREFIX = Regex("^(feat|fix|docs|chore|refactor|perf|style|test|build|ci)(\\([^)]*\\))?!?:\\s*")

    /** sha256sum 的输出: "<hash>  <文件名>" */
    fun parseSha256(text: String): String? =
        text.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
}
