package com.openneno.neno.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class NoteStats(
    val noteCount: Int = 0,
    val tagCount: Int = 0,
    val dayCount: Int = 0,
    val countByDay: Map<String, Int> = emptyMap(),
)

/**
 * 笔记的读写. 所有修改都会标记 dirty(等待同步推送), 并增加 [version] 通知界面和桌面组件刷新.
 */
class NoteRepository(private val context: Context, val db: NenoDb) {

    private val _version = MutableStateFlow(0L)

    /** 本地数据(笔记, 标签设置)每次变化都会改变, 界面据此重新读取 */
    val version: StateFlow<Long> = _version.asStateFlow()

    /** 本地有新的改动需要推送时调用(由同步引擎设置) */
    var onLocalChange: (() -> Unit)? = null

    /** 数据变化后调用(用于刷新桌面组件) */
    var onDataChanged: (() -> Unit)? = null

    val picDir: File by lazy { File(context.filesDir, "pics").apply { mkdirs() } }

    fun picFile(id: String, suffix: String) = File(picDir, "$id.$suffix")

    fun picFile(image: NoteImage): File = picFile(image.key, db.picSuffix(image.key) ?: image.suffixName)

    /** 数据变化: 本地修改时 local=true, 同步拉取时 false */
    fun changed(local: Boolean) {
        _version.value = _version.value + 1
        onDataChanged?.invoke()
        if (local) onLocalChange?.invoke()
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    // ---------- 查询 ----------

    suspend fun notes(tag: String? = null, day: String? = null, keyword: String? = null, limit: Int = -1) =
        io { db.queryNotes(tag, day, keyword, limit) }

    suspend fun note(id: String): Note? = io { db.getNote(id) }

    suspend fun view(note: Note): NoteView = io { viewSync(note) }

    suspend fun views(notes: List<Note>): List<NoteView> = io { notes.map { viewSync(it) } }

    suspend fun view(id: String): NoteView? = io { db.getNote(id)?.let { viewSync(it) } }

    private fun viewSync(note: Note): NoteView {
        val parent = note.parentId.takeIf { it.isNotEmpty() }?.let { db.getNote(it) }
        return NoteView(note, parent, db.children(note.id))
    }

    suspend fun allTags(): List<String> = io { allTagsSync() }

    private fun allTagsSync(): List<String> {
        val set = LinkedHashSet<String>()
        for (column in db.allTagColumns()) column.split('\n').filter { it.isNotEmpty() }.forEach { set.add(it) }
        return set.sorted()
    }

    suspend fun stats(): NoteStats = io {
        val byDay = db.countByDay()
        val first = byDay.keys.firstOrNull()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        NoteStats(
            noteCount = db.noteCount(),
            tagCount = allTagsSync().size,
            dayCount = first?.let { ChronoUnit.DAYS.between(it, LocalDate.now()).toInt() } ?: 0,
            countByDay = byDay,
        )
    }

    // ---------- 新增 / 修改 / 删除 ----------

    /**
     * 保存笔记. id 为空时新建. 与网页端 addNeno 一致: 修改时保留 created_at, 写入 update_at.
     */
    suspend fun save(
        id: String,
        parentId: String,
        content: String,
        pureContent: String,
        tags: List<String>,
        images: List<NoteImage>,
    ): Note = io {
        val old = if (id.isNotEmpty()) db.getNote(id) else null
        val imagesJson = JsonArray(images.mapIndexed { index, image ->
            buildJsonObject {
                put("suffixName", JsonPrimitive(image.suffixName))
                put("key", JsonPrimitive(image.key))
                put("timeStamp", JsonPrimitive(System.currentTimeMillis() + index))
            }
        })
        val note = if (old != null) {
            Note(
                old.json.with(
                    "content" to JsonPrimitive(content),
                    "pureContent" to JsonPrimitive(pureContent),
                    "parentId" to JsonPrimitive(parentId.ifEmpty { old.parentId }),
                    "tags" to stringList(tags),
                    "images" to imagesJson,
                    "update_at" to JsonPrimitive(Times.now()),
                )
            )
        } else {
            // 键的顺序与网页端新建的笔记一致
            Note(buildJsonObject {
                put("content", JsonPrimitive(content))
                put("pureContent", JsonPrimitive(pureContent))
                put("_id", JsonPrimitive(id.ifEmpty { ObjectId.next() }))
                put("parentId", JsonPrimitive(parentId))
                put("source", JsonPrimitive("android"))
                put("tags", stringList(tags))
                put("images", imagesJson)
                put("created_at", JsonPrimitive(Times.now()))
            })
        }
        db.putNote(note)
        db.markDirty("note", note.id)
        // 编辑时移除的图片也从仓库删除
        if (old != null) {
            val keep = images.map { it.key }.toSet()
            for (image in old.images) if (image.key !in keep) deleteImageSync(image)
        }
        note
    }.also { changed(true) }

    suspend fun delete(id: String) {
        io {
            val note = db.getNote(id) ?: return@io
            for (image in note.images) deleteImageSync(image)
            db.deleteNote(id)
            // 记录删除前的创建时间, 推送时据此找到仓库中的文件
            db.markDirty("note", id, deleted = true, createdAt = note.createdAt)
        }
        changed(true)
    }

    private fun deleteImageSync(image: NoteImage) {
        val suffix = db.picSuffix(image.key) ?: image.suffixName
        picFile(image.key, suffix).delete()
        db.deletePic(image.key)
        db.markDirty("pic", image.key, deleted = true, suffix = suffix)
    }

    /** 把选择的图片复制到本地, 返回图片信息(保存笔记时才会关联到笔记) */
    suspend fun importImage(uri: Uri): NoteImage = io {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri)
        var suffix = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?: uri.lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.length in 2..5 }
            ?: "png"
        if (suffix == "jpeg") suffix = "jpg"
        val id = ObjectId.next()
        resolver.openInputStream(uri)!!.use { input ->
            picFile(id, suffix).outputStream().use { input.copyTo(it) }
        }
        db.putPic(id, suffix)
        db.markDirty("pic", id, suffix = suffix)
        NoteImage(id, suffix)
    }

    // ---------- 标签 ----------

    fun tagSettingsSync(): TagSettings {
        val text = db.getKv(KEY_TAG_SETTINGS) ?: return TagSettings()
        return runCatching { parseTagSettings(JsJson.parseObject(text)) }.getOrDefault(TagSettings())
    }

    suspend fun tagSettings(): TagSettings = io { tagSettingsSync() }

    fun setTagSettingsSync(settings: TagSettings) {
        db.putKv(KEY_TAG_SETTINGS, JsJson.stringify(tagSettingsJson(settings, alwaysIcons = true)))
    }

    suspend fun togglePin(tag: String) {
        io {
            val s = tagSettingsSync()
            val tags = if (tag in s.tags) s.tags - tag else s.tags + tag
            setTagSettingsSync(s.copy(tags = tags))
            db.markDirty("pinTags", "pinTags")
        }
        changed(true)
    }

    suspend fun setTagIcon(tag: String, icon: String) {
        io {
            val s = tagSettingsSync()
            val icons = s.icons.toMutableMap()
            if (icon.isNotBlank()) icons[tag] = icon.trim() else icons.remove(tag)
            setTagSettingsSync(s.copy(icons = icons))
            db.markDirty("pinTags", "pinTags")
        }
        changed(true)
    }

    /** 重命名标签: 修改所有包含这个标签的笔记, 图标和置顶跟着标签走 */
    suspend fun renameTag(oldTag: String, newTag: String) {
        if (oldTag == newTag || newTag.isBlank() || newTag == "#") return
        io {
            val pattern = Regex(Regex.escape(oldTag) + "(?=[\\s<&]|$)")
            db.inTransaction { tx ->
                for (note in db.queryNotes(tag = oldTag)) {
                    val tags = note.tags.map { if (it == oldTag) newTag else it }.distinct()
                    val updated = Note(
                        note.json.with(
                            "content" to JsonPrimitive(note.content.replace(pattern, Regex.escapeReplacement(newTag))),
                            "pureContent" to JsonPrimitive(note.pureContent.replace(pattern, Regex.escapeReplacement(newTag))),
                            "tags" to stringList(tags),
                            "update_at" to JsonPrimitive(Times.now()),
                        )
                    )
                    db.putNote(updated, tx)
                    db.markDirty("note", note.id)
                }
            }
            val s = tagSettingsSync()
            if (oldTag in s.icons || oldTag in s.tags) {
                val icons = s.icons.toMutableMap()
                icons.remove(oldTag)?.let { icons[newTag] = it }
                val pins = s.tags.map { if (it == oldTag) newTag else it }.distinct()
                setTagSettingsSync(TagSettings(pins, icons))
                db.markDirty("pinTags", "pinTags")
            }
        }
        changed(true)
    }

    // ---------- 备份 ----------

    /** 导出为与网页端相同结构的备份文件 */
    suspend fun exportBackup(): String = io {
        val notes = db.allNotes()
        val countDate = buildJsonObject {
            put("_id", JsonPrimitive("countDate"))
            put("created_at", JsonPrimitive("countDate"))
            db.countByDay().forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        }
        val settings = tagSettingsSync()
        val pins = buildJsonObject {
            put("_id", JsonPrimitive("pinTags"))
            put("tags", stringList(settings.tags))
            put("icons", JsonObject(settings.icons.mapValues { JsonPrimitive(it.value) }))
        }
        val pics = db.allPics().mapNotNull { (id, suffix) ->
            val file = picFile(id, suffix)
            if (!file.exists()) return@mapNotNull null
            val mime = imageMime(suffix)
            buildJsonObject {
                put("_id", JsonPrimitive(id))
                put("file", JsonPrimitive("data:$mime;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)))
            }
        }
        JsJson.stringify(buildJsonObject {
            put("body", buildJsonObject {
                put("allNeno", JsonArray(notes.map { it.json }))
                put("allNenoCount", JsonArray(listOf(countDate)))
                put("allNenoPinTags", JsonArray(listOf(pins)))
                put("allNenoPic", JsonArray(pics))
            })
        })
    }

    /** 从备份恢复, 导入的数据会同步到仓库(内容一致的文件不会产生提交) */
    suspend fun importBackup(text: String): Int {
        val count = io {
            val root = JsJson.parseObject(text)
            val body = (root["body"] as? JsonObject) ?: root
            val suffixes = HashMap<String, String>()
            var n = 0
            db.inTransaction { tx ->
                for (el in (body["allNeno"] as? JsonArray).orEmpty()) {
                    val note = Note(el as? JsonObject ?: continue)
                    if (note.id.isEmpty() || note.createdAt.isEmpty()) continue
                    db.putNote(note, tx)
                    db.markDirty("note", note.id)
                    note.images.forEach { suffixes[it.key] = it.suffixName }
                    n++
                }
            }
            val pins = LinkedHashSet<String>()
            val icons = LinkedHashMap<String, String>()
            for (el in (body["allNenoPinTags"] as? JsonArray).orEmpty()) {
                val s = parseTagSettings(el as? JsonObject ?: continue)
                pins += s.tags; icons += s.icons
            }
            if (pins.isNotEmpty() || icons.isNotEmpty()) {
                val current = tagSettingsSync()
                // 已有的图标优先, 备份里的只补充没有设置过的
                setTagSettingsSync(TagSettings((current.tags + pins).distinct(), icons + current.icons))
                db.markDirty("pinTags", "pinTags")
            }
            for (el in (body["allNenoPic"] as? JsonArray).orEmpty()) {
                val o = el as? JsonObject ?: continue
                val id = o.stringOrNull("_id") ?: continue
                val data = o.stringOrNull("file") ?: continue
                val comma = data.indexOf(',')
                if (comma < 0) continue
                val header = data.substring(0, comma)
                val suffix = suffixes[id] ?: header.substringAfter("image/", "png").substringBefore(';').let {
                    if (it == "jpeg") "jpg" else it
                }
                picFile(id, suffix).writeBytes(Base64.decode(data.substring(comma + 1), Base64.DEFAULT))
                db.putPic(id, suffix)
                if (suffixes.containsKey(id)) db.markDirty("pic", id, suffix = suffix)
            }
            n
        }
        changed(true)
        return count
    }

    companion object {
        const val KEY_TAG_SETTINGS = "tagSettings"

        fun parseTagSettings(o: JsonObject): TagSettings {
            val tags = (o["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
            return TagSettings(tags, normalizeIcons(o["icons"] as? JsonObject))
        }

        /** 只保留 "标签 -> 非空字符串" 的条目 */
        fun normalizeIcons(o: JsonObject?): Map<String, String> {
            val map = LinkedHashMap<String, String>()
            o?.forEach { (k, v) ->
                val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                if (!s.isNullOrEmpty()) map[k] = s
            }
            return map
        }

        /** 仓库中 pinTags/pinTags.json 的内容, 没有图标时不写 icons, 与旧版本完全一致 */
        fun tagSettingsJson(s: TagSettings, alwaysIcons: Boolean = false): JsonObject = buildJsonObject {
            put("_id", JsonPrimitive("pinTags"))
            put("created_at", JsonPrimitive("pinTags"))
            put("tags", stringList(s.tags))
            if (alwaysIcons || s.icons.isNotEmpty()) put("icons", JsonObject(s.icons.mapValues { JsonPrimitive(it.value) }))
        }

        fun imageMime(suffix: String): String = when (suffix.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            "apng" -> "image/apng"
            else -> "application/octet-stream"
        }

        /** 与网页端一致: # 开头直到空白 */
        val TAG_REGEX = Regex("#\\S+")

        fun extractTags(text: String): List<String> = TAG_REGEX.findAll(text).map { it.value }.distinct().toList()
    }
}
