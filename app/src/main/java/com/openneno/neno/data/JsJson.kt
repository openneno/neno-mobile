package com.openneno.neno.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 与 JavaScript 的 JSON.stringify 输出一致的序列化.
 *
 * 仓库中的文件是由网页端用 JSON.stringify(data, null, "\t") 写入的, 同步时通过 git blob sha 判断
 * 文件是否变化, 所以这里必须逐字节保持一致: 键的顺序, 缩进, 空数组/空对象, 字符串转义.
 */
object JsJson {
    val parser = Json { isLenient = false; ignoreUnknownKeys = true }

    fun parseObject(text: String): JsonObject = parser.parseToJsonElement(text) as JsonObject

    fun stringify(element: JsonElement, indent: String = ""): String {
        val sb = StringBuilder()
        write(sb, element, indent, "")
        return sb.toString()
    }

    private fun write(sb: StringBuilder, element: JsonElement, indent: String, current: String) {
        when (element) {
            is JsonNull -> sb.append("null")
            is JsonPrimitive -> {
                if (element.isString) quote(sb, element.content) else sb.append(element.content)
            }

            is JsonArray -> {
                if (element.isEmpty()) {
                    sb.append("[]"); return
                }
                val inner = current + indent
                sb.append('[')
                element.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    if (indent.isNotEmpty()) sb.append('\n').append(inner)
                    write(sb, item, indent, inner)
                }
                if (indent.isNotEmpty()) sb.append('\n').append(current)
                sb.append(']')
            }

            is JsonObject -> {
                if (element.isEmpty()) {
                    sb.append("{}"); return
                }
                val inner = current + indent
                sb.append('{')
                var first = true
                for ((key, value) in element) {
                    if (!first) sb.append(',')
                    first = false
                    if (indent.isNotEmpty()) sb.append('\n').append(inner)
                    quote(sb, key)
                    sb.append(':')
                    if (indent.isNotEmpty()) sb.append(' ')
                    write(sb, value, indent, inner)
                }
                if (indent.isNotEmpty()) sb.append('\n').append(current)
                sb.append('}')
            }
        }
    }

    // JSON.stringify 只转义引号, 反斜杠, 控制字符和落单的代理项
    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append(String.format("%04x", c.code))
                Character.isHighSurrogate(c) -> {
                    if (i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                        sb.append(c).append(s[i + 1]); i++
                    } else sb.append("\\u").append(String.format("%04x", c.code))
                }

                Character.isLowSurrogate(c) -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
            i++
        }
        sb.append('"')
    }
}
