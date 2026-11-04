package com.openneno.neno.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import java.security.SecureRandom
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

data class NoteImage(val key: String, val suffixName: String)

/**
 * 一条笔记. 保留原始的 JSON 对象(包括其他工具写入的未知字段和键的顺序), 只读取界面需要的字段.
 */
class Note(val json: JsonObject) {
    val id: String get() = str("_id")
    val content: String get() = str("content")
    val pureContent: String get() = str("pureContent")
    val parentId: String get() = str("parentId")
    val createdAt: String get() = str("created_at")
    val updateAt: String get() = str("update_at")
    val source: String get() = str("source")

    val tags: List<String>
        get() = (json["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    val images: List<NoteImage>
        get() = (json["images"] as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val key = (o["key"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            if (key.isEmpty()) return@mapNotNull null
            NoteImage(key, (o["suffixName"] as? JsonPrimitive)?.contentOrNull ?: "png")
        } ?: emptyList()

    val date: String get() = createdAt.take(10)

    /** 纯文本, 旧数据可能没有 pureContent */
    val plainText: String by lazy {
        pureContent.ifEmpty { htmlToText(content) }
    }

    private fun str(key: String): String = (json[key] as? JsonPrimitive)?.contentOrNull ?: ""

    /** 写入仓库的内容: 与网页端 JSON.stringify(note, null, "\t") 一致 */
    fun serialize(): String = JsJson.stringify(json, "\t")

    fun summary(max: Int = 60): String {
        val text = plainText.replace(Regex("\\s+"), " ").trim()
        return if (text.length > max) text.substring(0, max) + "..." else text
    }

    override fun equals(other: Any?) = other is Note && other.json == json
    override fun hashCode() = json.hashCode()

    companion object {
        fun parse(text: String) = Note(JsJson.parseObject(text))

        fun htmlToText(html: String): String {
            if (html.isEmpty()) return ""
            val doc = Jsoup.parseBodyFragment(html)
            // 块级元素之间换行, 与浏览器 innerText 接近
            doc.select("br").append("\\n")
            doc.select("p,li,blockquote,pre,h1,h2,h3,h4,h5,h6,div").prepend("\\n")
            return doc.body().text().replace("\\n", "\n").trim()
        }
    }
}

/** 笔记以及它的批注对象(parent)和批注(children) */
data class NoteView(val note: Note, val parent: Note? = null, val children: List<Note> = emptyList())

/** 标签设置: 置顶标签和标签图标 */
data class TagSettings(val tags: List<String> = emptyList(), val icons: Map<String, String> = emptyMap())

fun JsonObject.with(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject {
    // 保持原有键的顺序, 新增的键追加在末尾
    val map = LinkedHashMap(this)
    for ((k, v) in pairs) map[k] = v
    return JsonObject(map)
}

fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    for ((k, v) in pairs) {
        when (v) {
            null -> {}
            is String -> put(k, JsonPrimitive(v))
            is Number -> put(k, JsonPrimitive(v))
            is Boolean -> put(k, JsonPrimitive(v))
            is kotlinx.serialization.json.JsonElement -> put(k, v)
            else -> put(k, JsonPrimitive(v.toString()))
        }
    }
}

/** 与 mongo ObjectId 相同的 24 位十六进制 id, 前 8 位是秒级时间戳, 所以按 id 排序就是按创建时间排序 */
object ObjectId {
    private val random = SecureRandom()
    private val machine = random.nextInt(0xFFFFFF + 1)
    private val pid = random.nextInt(0xFFFF + 1)
    private var increment = random.nextInt(0xFFFFFF + 1)

    @Synchronized
    fun next(): String {
        val ts = System.currentTimeMillis() / 1000
        val inc = increment
        increment = (increment + 1) and 0xFFFFFF
        return "%08x%06x%04x%06x".format(ts, machine, pid, inc)
    }
}

object Times {
    // dayjs().format() 的格式: 2026-10-26T11:39:10+08:00
    private val FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")

    fun now(): String = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).format(FORMAT)

    /** 尽量解析各种旧格式的时间, 失败时返回 null */
    fun parse(text: String): ZonedDateTime? {
        if (text.isBlank()) return null
        val zone = ZoneId.systemDefault()
        runCatching { return OffsetDateTime.parse(text).atZoneSameInstant(zone) }
        runCatching { return ZonedDateTime.parse(text).withZoneSameInstant(zone) }
        runCatching { return LocalDateTime.parse(text.replace(' ', 'T')).atZone(zone) }
        runCatching { return LocalDate.parse(text.take(10)).atStartOfDay(zone) }
        return null
    }

    fun millis(text: String): Long = parse(text)?.toInstant()?.toEpochMilli() ?: 0L

    /** 笔记的修改时间, 两端都修改时据此决定保留哪一份 */
    fun noteTime(note: Note?): Long {
        if (note == null) return 0
        return millis(note.updateAt.ifEmpty { note.createdAt })
    }

    fun display(text: String, pattern: String = "yyyy-MM-dd HH:mm"): String =
        parse(text)?.format(DateTimeFormatter.ofPattern(pattern)) ?: text

    fun today(): LocalDate = LocalDate.now()

    fun localDate(text: String): LocalDate? = parse(text)?.toLocalDate()
}

fun stringList(list: Collection<String>) = JsonArray(list.map { JsonPrimitive(it) })

fun JsonPrimitive?.str(): String = this?.jsonPrimitive?.contentOrNull ?: ""
