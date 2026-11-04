package com.openneno.neno.sync

import android.util.Log
import com.openneno.neno.data.DirtyEntry
import com.openneno.neno.data.JsJson
import com.openneno.neno.data.Note
import com.openneno.neno.data.NoteRepository
import com.openneno.neno.data.Settings
import com.openneno.neno.data.Times
import com.openneno.neno.data.stringOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

data class SyncStatus(
    val syncing: Boolean = false,
    val error: String = "",
    val authError: Boolean = false,
    val lastSyncAt: Long = 0,
    val progressAll: Int = 0,
    val progressDone: Int = 0,
)

/**
 * 笔记仓库的文件结构, 与网页端及其他扩展工具保持一致:
 *   YYYY-MM-DD/<笔记 _id>.json   一条笔记
 *   picData/<图片 _id>.<后缀>     一张图片
 *   pinTags/pinTags.json          标签设置
 */
object RepoLayout {
    private val NOTE_PATH = Regex("^(\\d{4}-\\d{2}-\\d{2})/([0-9a-zA-Z]+)\\.json$")
    private val PIC_PATH = Regex("^picData/([^/.]+)\\.([0-9a-zA-Z]+)$")
    const val PIN_TAGS_PATH = "pinTags/pinTags.json"

    data class PathInfo(val kind: String, val id: String, val suffix: String = "")

    fun classify(path: String): PathInfo? {
        NOTE_PATH.find(path)?.let { return PathInfo("note", it.groupValues[2]) }
        PIC_PATH.find(path)?.let { return PathInfo("pic", it.groupValues[1], it.groupValues[2]) }
        if (path == PIN_TAGS_PATH) return PathInfo("pinTags", "pinTags")
        return null
    }

    fun notePath(note: Note) = "${note.createdAt.take(10)}/${note.id}.json"
    fun picPath(id: String, suffix: String) = "picData/$id.$suffix"
}

/**
 * GitHub 同步引擎, 移植自网页端 syncEngine.js, 两端可以同时使用同一个仓库.
 *
 * 一次同步的流程:
 *  1. 读取默认分支的最新提交(空闲时只有这一个请求, 并且使用 ETag, 没有变化时不计入配额)
 *  2. 远端有变化时一次取得完整的文件树, 与上次同步时记录的文件 sha 对比, 只下载变化的文件,
 *     并按笔记合并: 两端都修改时保留修改时间较新的一份
 *  3. 把待推送的本地改动合并成一次提交, 通过 GraphQL createCommitOnBranch 推送,
 *     expectedHeadOid 保证远端在此期间被其他设备修改时提交失败, 重新拉取合并后再推送
 */
class SyncEngine(private val repo: NoteRepository, private val settings: Settings) {

    private class ConflictException(message: String) : Exception(message)

    private data class State(
        val repo: String,
        var branch: String?,
        var headOid: String?,
        var initialized: Boolean,
        var baseShas: MutableMap<String, String>,
    )

    private val db get() = repo.db
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val config get() = settings.github.value
    val isConfigured get() = config.configured
    private val repoKey get() = "${config.owner}/${config.repo}"

    private suspend fun api(method: String, suffix: String, body: JsonObject? = null, query: Map<String, Any?> = emptyMap(), etag: String? = null) =
        GithubClient.rest(config.token, method, "/repos/${config.owner}/${config.repo}$suffix", body, query, etag)

    // ---------- 同步状态 ----------

    private fun loadState(): State {
        val text = db.getKv(KEY_STATE)
        if (text != null) {
            val o = runCatching { JsJson.parseObject(text) }.getOrNull()
            if (o != null && o.stringOrNull("repo") == repoKey) {
                return State(
                    repoKey, o.stringOrNull("branch"), o.stringOrNull("headOid"),
                    (o["initialized"] as? JsonPrimitive)?.boolean ?: false, db.loadBaseShas()
                )
            }
        }
        // 切换了仓库, 重新开始
        return State(repoKey, null, null, false, HashMap())
    }

    private fun saveState(state: State) {
        db.putKv(KEY_STATE, JsJson.stringify(buildJsonObject {
            put("repo", JsonPrimitive(state.repo))
            state.branch?.let { put("branch", JsonPrimitive(it)) }
            state.headOid?.let { put("headOid", JsonPrimitive(it)) }
            put("initialized", JsonPrimitive(state.initialized))
        }))
        db.saveBaseShas(state.baseShas)
    }

    /** 切换或退出账号时清除同步记录 */
    fun reset() {
        db.deleteKv(KEY_STATE)
        db.saveBaseShas(emptyMap())
        refCache = RefCache("", null, null)
        _status.value = SyncStatus()
    }

    // ---------- 远端读取 ----------

    private data class RefCache(val key: String, val etag: String?, val sha: String?)

    private var refCache = RefCache("", null, null)

    private suspend fun resolveHead(state: State, retried: Boolean = false): String {
        if (state.branch == null) {
            state.branch = api("GET", "").data!!.jsonObject.stringOrNull("default_branch")
        }
        val branch = state.branch!!
        val key = "$repoKey#$branch"
        try {
            val ref = api("GET", "/git/ref/heads/$branch", etag = if (refCache.key == key) refCache.etag else null)
            if (ref.notModified && refCache.sha != null) return refCache.sha!!
            val sha = ref.data!!.jsonObject["object"]!!.jsonObject.stringOrNull("sha")!!
            refCache = RefCache(key, ref.etag, sha)
            return sha
        } catch (e: GithubException) {
            if (retried) throw e
            if (e.status == 409) {
                // 空仓库没有分支, 先创建一个提交
                api("PUT", "/contents/README.md", body = buildJsonObject {
                    put("message", JsonPrimitive("init neno"))
                    put("content", JsonPrimitive(GitBlob.encode("# NENO\n\nNENO 笔记数据仓库\n".toByteArray())))
                })
                state.branch = null
                return resolveHead(state, true)
            }
            if (e.status == 404) {
                // 默认分支可能被改名
                state.branch = null
                return resolveHead(state, true)
            }
            throw e
        }
    }

    private fun blobsOf(entries: JsonArray, prefix: String = ""): MutableMap<String, String> {
        val map = HashMap<String, String>()
        for (e in entries) {
            val o = e.jsonObject
            if (o.stringOrNull("type") == "blob") map[prefix + o.stringOrNull("path")] = o.stringOrNull("sha")!!
        }
        return map
    }

    private suspend fun walkTree(sha: String, prefix: String): MutableMap<String, String> {
        val tree = api("GET", "/git/trees/$sha").data!!.jsonObject["tree"] as JsonArray
        val map = blobsOf(tree, prefix)
        for (e in tree) {
            val o = e.jsonObject
            if (o.stringOrNull("type") == "tree") map += walkTree(o.stringOrNull("sha")!!, "$prefix${o.stringOrNull("path")}/")
        }
        return map
    }

    /** 路径 -> blob sha */
    private suspend fun fetchTree(commitSha: String): MutableMap<String, String> {
        val data = api("GET", "/git/trees/$commitSha", query = mapOf("recursive" to 1)).data!!.jsonObject
        // 文件非常多时 recursive 结果会被截断, 改为逐个目录读取
        if ((data["truncated"] as? JsonPrimitive)?.boolean == true) return walkTree(commitSha, "")
        return blobsOf(data["tree"] as JsonArray)
    }

    private suspend fun fetchBlob(sha: String): ByteArray =
        GitBlob.decode(api("GET", "/git/blobs/$sha").data!!.jsonObject.stringOrNull("content")!!)

    private fun startProgress(total: Int) = _status.update { it.copy(progressAll = total, progressDone = 0) }
    private fun tick() = _status.update { it.copy(progressDone = it.progressDone + 1) }
    private fun endProgress() = _status.update { it.copy(progressAll = 0, progressDone = 0) }

    // ---------- 拉取与合并 ----------

    /** 返回本地数据是否变化 */
    private suspend fun pull(state: State, head: String, dirtyMap: MutableMap<String, DirtyEntry>): Boolean {
        val remote = fetchTree(head)
        val base = state.baseShas
        var changed = false

        fun dropDirty(dirty: DirtyEntry) {
            db.clearDirty(dirty); dirtyMap.remove(dirty.key)
        }

        val jobs = remote.keys.filter { remote[it] != base[it] }.mapNotNull { p -> RepoLayout.classify(p)?.let { p to it } }
        startProgress(jobs.size)
        val semaphore = Semaphore(FETCH_CONCURRENCY)
        val lock = Mutex()
        try {
            coroutineScopeAll(jobs.map { (path, info) ->
                suspend {
                    semaphore.withPermit {
                        val sha = remote[path]!!
                        val dirty = dirtyMap["${info.kind}:${info.id}"]
                        when (info.kind) {
                            "note" -> {
                                val local = db.getNote(info.id)
                                // 没有同步记录时(首次同步)先比较内容, 一致就不用下载
                                if (local != null && dirty == null && base[path] == null &&
                                    GitBlob.sha(local.serialize().toByteArray()) == sha
                                ) return@withPermit
                                val remoteNote = Note.parse(String(fetchBlob(sha), Charsets.UTF_8))
                                lock.withLock {
                                    if (dirty != null && dirty.deleted) {
                                        // 本地删除后远端又修改了, 以较新的操作为准
                                        if (Times.noteTime(remoteNote) > dirty.at) {
                                            db.putNote(remoteNote); dropDirty(dirty); changed = true
                                        }
                                        return@withLock
                                    }
                                    // 本地的修改更新, 保留本地, 稍后推送
                                    if (dirty != null && local != null && Times.noteTime(local) >= Times.noteTime(remoteNote)) return@withLock
                                    db.putNote(remoteNote)
                                    if (dirty != null) dropDirty(dirty)
                                    changed = true
                                }
                            }

                            "pic" -> {
                                // 本地新增或删除了这张图片, 以本地为准
                                if (dirty != null) return@withPermit
                                val file = repo.picFile(info.id, db.picSuffix(info.id) ?: info.suffix)
                                if (file.exists() && GitBlob.sha(file.readBytes()) == sha) return@withPermit
                                val bytes = fetchBlob(sha)
                                repo.picFile(info.id, info.suffix).writeBytes(bytes)
                                db.putPic(info.id, info.suffix)
                                changed = true
                            }

                            "pinTags" -> {
                                if (dirty == null && base[path] == null &&
                                    GitBlob.sha(pinTagsBytes()) == sha
                                ) return@withPermit
                                val remoteSettings = NoteRepository.parseTagSettings(
                                    JsJson.parseObject(String(fetchBlob(sha), Charsets.UTF_8))
                                )
                                lock.withLock {
                                    if (dirty != null) {
                                        // 两端都修改了: 置顶标签取并集, 同一个标签的图标以本地为准
                                        val local = repo.tagSettingsSync()
                                        repo.setTagSettingsSync(
                                            com.openneno.neno.data.TagSettings(
                                                (local.tags + remoteSettings.tags).distinct(),
                                                remoteSettings.icons + local.icons
                                            )
                                        )
                                    } else repo.setTagSettingsSync(remoteSettings)
                                    changed = true
                                }
                            }
                        }
                    }
                    tick()
                }
            })
        } finally {
            endProgress()
        }

        // 远端删除的文件
        for (path in base.keys.toList()) {
            if (path in remote) continue
            val info = RepoLayout.classify(path) ?: continue
            if (info.kind == "pinTags") continue
            val dirty = dirtyMap["${info.kind}:${info.id}"]
            // 本地有新的修改时保留, 稍后重新推送
            if (dirty != null && !dirty.deleted) continue
            if (dirty != null) dropDirty(dirty)
            if (info.kind == "note") {
                db.deleteNote(info.id)
            } else {
                repo.picFile(info.id, info.suffix).delete()
                db.deletePic(info.id)
            }
            changed = true
        }

        // 第一次与这个仓库同步: 把只存在于本地的笔记也上传, 避免数据只留在这台设备上
        if (!state.initialized) {
            for (note in db.allNotes()) {
                if (RepoLayout.notePath(note) !in remote && !dirtyMap.containsKey("note:${note.id}")) {
                    db.markDirty("note", note.id)
                }
                for (image in note.images) {
                    val suffix = db.picSuffix(image.key) ?: continue
                    if (RepoLayout.picPath(image.key, suffix) !in remote && !dirtyMap.containsKey("pic:${image.key}")) {
                        db.markDirty("pic", image.key, suffix = suffix)
                    }
                }
            }
            val s = repo.tagSettingsSync()
            if (RepoLayout.PIN_TAGS_PATH !in remote && (s.tags.isNotEmpty() || s.icons.isNotEmpty())) {
                db.markDirty("pinTags", "pinTags")
            }
        }

        state.baseShas = remote
        state.headOid = head
        state.initialized = true
        return changed
    }

    private suspend fun coroutineScopeAll(tasks: List<suspend () -> Unit>) {
        kotlinx.coroutines.coroutineScope { tasks.map { async { it() } }.awaitAll() }
    }

    private fun pinTagsBytes() = JsJson.stringify(NoteRepository.tagSettingsJson(repo.tagSettingsSync()), "\t").toByteArray()

    // ---------- 推送 ----------

    private class Change(
        val entry: DirtyEntry,
        val path: String,
        val deletion: Boolean = false,
        val bytes: ByteArray? = null,
        val sha: String? = null,
        val note: Note? = null,
        val isNew: Boolean = false,
    )

    private suspend fun createCommit(branch: String, expectedHeadOid: String, headline: String, additions: JsonArray, deletions: JsonArray): String {
        try {
            val data = GithubClient.graphql(config.token, COMMIT_MUTATION, buildJsonObject {
                put("input", buildJsonObject {
                    put("branch", buildJsonObject {
                        put("repositoryNameWithOwner", JsonPrimitive(repoKey))
                        put("branchName", JsonPrimitive(branch))
                    })
                    put("message", buildJsonObject { put("headline", JsonPrimitive(headline)) })
                    put("expectedHeadOid", JsonPrimitive(expectedHeadOid))
                    put("fileChanges", buildJsonObject {
                        put("additions", additions)
                        put("deletions", deletions)
                    })
                })
            })
            return data["createCommitOnBranch"]!!.jsonObject["commit"]!!.jsonObject.stringOrNull("oid")!!
        } catch (e: GithubException) {
            // 判断是否是远端已经有了新的提交
            if (e.status == 200) {
                val current = api("GET", "/git/ref/heads/$branch").data!!.jsonObject["object"]!!.jsonObject.stringOrNull("sha")
                if (current != expectedHeadOid) throw ConflictException(e.message ?: "conflict")
            }
            throw e
        }
    }

    /** 保持旧版本的提交信息格式, notion 同步的 action 依赖 "[ADD]" 判断是否有新笔记 */
    private fun commitMessage(changes: List<Change>): String {
        val notes = changes.filter { it.entry.kind == "note" }
        val added = notes.filter { !it.deletion && it.isNew }
        val modified = notes.filter { !it.deletion && !it.isNew }
        val deleted = notes.filter { it.deletion }
        if (notes.size == 1) {
            if (added.size == 1) return "[ADD] ${added[0].note!!.summary()}"
            if (modified.size == 1) return "[MODIFY] ${modified[0].note!!.summary()}"
            return "[DELETE] 删除 1 条笔记"
        }
        if (notes.isEmpty()) return if (changes.any { it.entry.kind == "pinTags" }) "update pinTags" else "update pictures"
        val parts = buildList {
            if (added.isNotEmpty()) add("新增 ${added.size} 条")
            if (modified.isNotEmpty()) add("修改 ${modified.size} 条")
            if (deleted.isNotEmpty()) add("删除 ${deleted.size} 条")
        }
        val tag = if (added.isNotEmpty()) "[ADD]" else if (modified.isNotEmpty()) "[MODIFY]" else "[DELETE]"
        return "$tag ${parts.joinToString(", ")}笔记"
    }

    /** 把 dirty 记录转换成文件改动, 内容与远端一致的直接清除 */
    private fun collectChanges(state: State): List<Change> {
        val changes = mutableListOf<Change>()
        val base = state.baseShas
        for (entry in db.dirtyList()) {
            var change: Change? = null
            when (entry.kind) {
                "note" -> if (entry.deleted) {
                    val path = entry.createdAt?.let { "${it.take(10)}/${entry.id}.json" }
                    if (path != null && base[path] != null) change = Change(entry, path, deletion = true)
                } else {
                    val note = db.getNote(entry.id)
                    if (note != null) {
                        val path = RepoLayout.notePath(note)
                        val bytes = note.serialize().toByteArray()
                        val sha = GitBlob.sha(bytes)
                        if (base[path] != sha) change = Change(entry, path, bytes = bytes, sha = sha, note = note, isNew = base[path] == null)
                    }
                }

                "pic" -> {
                    val suffix = entry.suffix ?: db.picSuffix(entry.id) ?: "png"
                    val path = RepoLayout.picPath(entry.id, suffix)
                    if (entry.deleted) {
                        if (base[path] != null) change = Change(entry, path, deletion = true)
                    } else {
                        val file = repo.picFile(entry.id, suffix)
                        if (file.exists()) {
                            val bytes = file.readBytes()
                            val sha = GitBlob.sha(bytes)
                            if (base[path] != sha) change = Change(entry, path, bytes = bytes, sha = sha)
                        }
                    }
                }

                "pinTags" -> {
                    val bytes = pinTagsBytes()
                    val sha = GitBlob.sha(bytes)
                    if (base[RepoLayout.PIN_TAGS_PATH] != sha) change = Change(entry, RepoLayout.PIN_TAGS_PATH, bytes = bytes, sha = sha)
                }
            }
            if (change != null) changes += change else db.clearDirty(entry)
        }
        return changes
    }

    private fun chunkChanges(changes: List<Change>): List<List<Change>> {
        val chunks = mutableListOf<List<Change>>()
        var current = mutableListOf<Change>()
        var size = 0L
        for (change in changes) {
            val changeSize = (change.bytes?.size ?: 0) * 4L / 3
            if (current.isNotEmpty() && (current.size >= MAX_COMMIT_FILES || size + changeSize > MAX_COMMIT_BYTES)) {
                chunks += current; current = mutableListOf(); size = 0
            }
            current += change
            size += changeSize
        }
        if (current.isNotEmpty()) chunks += current
        return chunks
    }

    private suspend fun push(state: State, startHead: String): String {
        var head = startHead
        val changes = collectChanges(state)
        if (changes.isEmpty()) return head
        startProgress(changes.size)
        try {
            for (chunk in chunkChanges(changes)) {
                val additions = buildJsonArray {
                    chunk.filter { !it.deletion }.forEach { c ->
                        add(buildJsonObject {
                            put("path", JsonPrimitive(c.path))
                            put("contents", JsonPrimitive(GitBlob.encode(c.bytes!!)))
                        })
                    }
                }
                val deletions = buildJsonArray {
                    chunk.filter { it.deletion }.forEach { c -> add(buildJsonObject { put("path", JsonPrimitive(c.path)) }) }
                }
                head = createCommit(state.branch!!, head, commitMessage(chunk), additions, deletions)
                for (c in chunk) {
                    if (c.deletion) state.baseShas.remove(c.path) else state.baseShas[c.path] = c.sha!!
                    db.clearDirty(c.entry)
                    tick()
                }
                state.headOid = head
                saveState(state)
            }
        } finally {
            endProgress()
        }
        return head
    }

    // ---------- 同步流程 ----------

    private suspend fun syncOnce() {
        val state = loadState()
        var head = resolveHead(state)
        val dirtyList = db.dirtyList()

        if (head != state.headOid || !state.initialized) {
            val changed = pull(state, head, ConcurrentHashMap(dirtyList.associateBy { it.key }))
            saveState(state)
            if (changed) withContext(Dispatchers.Main) { repo.changed(local = false) }
        } else if (dirtyList.isEmpty()) {
            return
        }

        head = push(state, head)
        state.headOid = head
        saveState(state)
        if (config.lastCommitSha != head) settings.setGithub(config.copy(lastCommitSha = head))
    }

    private suspend fun syncWithRetry() {
        var attempt = 1
        while (true) {
            try {
                syncOnce(); return
            } catch (e: ConflictException) {
                if (attempt >= MAX_ATTEMPTS) throw e
                attempt++
            }
        }
    }

    private val mutex = Mutex()
    @Volatile private var rerun = false

    /** 立即同步一次. 正在同步时只记录, 结束后再同步一次 */
    suspend fun syncNow() {
        if (!isConfigured || _status.value.authError) return
        if (mutex.isLocked) {
            rerun = true; return
        }
        mutex.withLock {
            _status.update { it.copy(syncing = true) }
            var error = ""
            try {
                do {
                    rerun = false
                    syncWithRetry()
                } while (rerun)
            } catch (e: Exception) {
                Log.w(TAG, "同步失败", e)
                if (e is GithubException && e.status == 401) _status.update { it.copy(authError = true) }
                error = (e as? GithubException)?.let { AccountApi.describeError(it) } ?: (e.message ?: "同步失败")
            } finally {
                _status.update {
                    it.copy(syncing = false, error = error, lastSyncAt = if (error.isEmpty()) System.currentTimeMillis() else it.lastSyncAt)
                }
            }
        }
    }

    fun clearAuthError() = _status.update { it.copy(authError = false, error = "") }

    fun hasPendingChanges() = db.dirtyCount() > 0

    private var pushJob: Job? = null

    /** 本地修改后稍等片刻再推送, 把连续的修改合并成一次提交 */
    fun scheduleSync(delayMs: Long = PUSH_DELAY) {
        markActivity()
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(delayMs)
            syncNow()
        }
    }

    // ---------- 前台轮询 ----------

    private var pollJob: Job? = null
    @Volatile private var lastActivity = System.currentTimeMillis()

    private val isIdle get() = System.currentTimeMillis() - lastActivity > IDLE_AFTER

    fun markActivity() {
        val wasIdle = isIdle
        lastActivity = System.currentTimeMillis()
        // 从空闲中回来时尽快检查一次
        if (wasIdle && pollJob != null) startPolling(300)
    }

    /** 应用在前台时检查远端: 最近有操作时几秒一次, 空闲时 30 秒一次 */
    fun startPolling(firstDelay: Long = 300) {
        pollJob?.cancel()
        pollJob = scope.launch {
            delay(firstDelay)
            while (isActive) {
                syncNow()
                delay(if (isIdle) IDLE_POLL_INTERVAL else ACTIVE_POLL_INTERVAL)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun launch(block: suspend () -> Unit) = scope.launch { block() }

    /** 直接提交若干文本文件(用于写入 notion 同步的 workflow) */
    suspend fun commitTextFiles(files: List<Pair<String, String>>, headline: String): String = mutex.withLock {
        val state = loadState()
        val head = resolveHead(state)
        val additions = buildJsonArray {
            files.forEach { (path, text) ->
                add(buildJsonObject {
                    put("path", JsonPrimitive(path))
                    put("contents", JsonPrimitive(GitBlob.encode(text.toByteArray())))
                })
            }
        }
        createCommit(state.branch!!, head, headline, additions, JsonArray(emptyList()))
    }

    companion object {
        private const val TAG = "NenoSync"
        private const val KEY_STATE = "syncState"
        private const val MAX_COMMIT_BYTES = 8L * 1024 * 1024
        private const val MAX_COMMIT_FILES = 200
        private const val FETCH_CONCURRENCY = 6
        private const val MAX_ATTEMPTS = 3
        const val PUSH_DELAY = 1000L
        private const val ACTIVE_POLL_INTERVAL = 5000L
        private const val IDLE_POLL_INTERVAL = 30_000L
        private const val IDLE_AFTER = 2 * 60 * 1000L

        private const val COMMIT_MUTATION = """mutation(${'$'}input: CreateCommitOnBranchInput!) {
    createCommitOnBranch(input: ${'$'}input) { commit { oid } }
}"""
    }
}

@Suppress("unused")
private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.jsonPrimitive?.boolean
