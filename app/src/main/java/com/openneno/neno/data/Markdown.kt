package com.openneno.neno.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * 编辑器的 Markdown 模式: 笔记仍然以 html 保存, 切换模式和发送时在 Markdown 与 html 之间转换.
 * 规则与网页端 src/lib/markdown.js 相同: 每一行是一个段落, 空行就是一个空段落, 来回切换不会丢失换行.
 */
object Markdown {
    private fun escapeHtml(text: String) =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private const val PLACEHOLDER = '\u0000'
    private val ESCAPED = Regex("""\\([\\`*_~\[\]()#>+\-.!|<])""")
    private val CODE = Regex("`([^`]+)`")
    private val LINK = Regex("""\[([^\]]*)]\(\s*([^)\s]+)\s*\)""")
    private val UNDERLINE = Regex("<(/?)u>", RegexOption.IGNORE_CASE)
    private val BOLD = Regex("""\*\*(?=\S)([\s\S]*?\S)\*\*""")
    private val ITALIC = Regex("""\*(?=\S)([^*]*?\S)\*""")
    private val STRIKE = Regex("""~~(?=\S)([\s\S]*?\S)~~""")
    private val KEPT = Regex("$PLACEHOLDER(\\d+)$PLACEHOLDER")
    private val LIST_RE = Regex("""^(\s*)([-*+]|\d+[.)])\s+(.*)$""")
    private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
    private val QUOTE = Regex("""^>\s?(.*)$""")
    private val FENCE = Regex("""^\s*```""")
    private val FENCE_END = Regex("""^\s*```\s*$""")

    /** 行内语法: 转义, `代码`, [文字](网址), **加粗**, *斜体*, ~~删除线~~, <u>下划线</u> */
    private fun inline(text: String): String {
        val kept = mutableListOf<String>()
        fun keep(html: String): String {
            kept += html
            return "$PLACEHOLDER${kept.size - 1}$PLACEHOLDER"
        }
        var out = text
            .replace(ESCAPED) { keep(escapeHtml(it.groupValues[1])) }
            .replace(CODE) { keep("<code>${escapeHtml(it.groupValues[1])}</code>") }
            .replace(LINK) {
                var href = it.groupValues[2]
                if (!Regex("^(https?:|mailto:)", RegexOption.IGNORE_CASE).containsMatchIn(href)) href = "https://$href"
                keep("<a href=\"${escapeHtml(href)}\">") + it.groupValues[1] + keep("</a>")
            }
            .replace(UNDERLINE) { keep("<${it.groupValues[1]}u>") }
        out = escapeHtml(out)
            .replace(BOLD, "<strong>$1</strong>")
            .replace(ITALIC, "<em>$1</em>")
            .replace(STRIKE, "<s>$1</s>")
        return out.replace(KEPT) { kept[it.groupValues[1].toInt()] }
    }

    fun toHtml(markdown: String): String {
        val lines = markdown.replace(Regex("\r\n?"), "\n").trimEnd().split("\n")
        val html = StringBuilder()
        // 当前打开的列表: (tag, indent)
        val lists = ArrayDeque<Pair<String, Int>>()
        fun closeLists(depth: Int = 0) {
            while (lists.size > depth) html.append("</li></${lists.removeLast().first}>")
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (FENCE.containsMatchIn(line)) {
                closeLists()
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !FENCE_END.matches(lines[i])) code += lines[i++]
                html.append("<pre>${escapeHtml(code.joinToString("\n"))}</pre>")
                i++
                continue
            }

            val item = LIST_RE.matchEntire(line)
            if (item != null) {
                val (space, marker, content) = item.destructured
                val tag = if (marker[0].isDigit()) "ol" else "ul"
                val indent = space.replace("\t", "    ").length
                // 缩进更深的作为子列表, 每级至少 2 个空格
                while (lists.isNotEmpty() && indent < lists.last().second) closeLists(lists.size - 1)
                val top = lists.lastOrNull()
                when {
                    top != null && indent >= top.second + 2 -> {
                        html.append("<$tag><li>"); lists.addLast(tag to indent)
                    }
                    top != null && top.first == tag -> html.append("</li><li>")
                    else -> {
                        if (top != null) closeLists(lists.size - 1)
                        html.append("<$tag><li>"); lists.addLast(tag to indent)
                    }
                }
                html.append(inline(content))
                i++
                continue
            }
            closeLists()

            val heading = HEADING.matchEntire(line)
            val quote = QUOTE.matchEntire(line)
            when {
                heading != null -> {
                    val level = heading.groupValues[1].length
                    html.append("<h$level>${inline(heading.groupValues[2])}</h$level>")
                }
                quote != null -> html.append("<blockquote>${inline(quote.groupValues[1])}</blockquote>")
                line.isBlank() -> html.append("<p><br></p>")
                else -> html.append("<p>${inline(line)}</p>")
            }
            i++
        }
        closeLists()
        return html.toString()
    }

    /** 行首会被当成 Markdown 语法的文字需要转义 */
    private fun escapeLineStart(text: String) = text
        .replace(Regex("""^(\s*)(#{1,6}\s|>|[-+]\s)"""), "$1\\\\$2")
        .replace(Regex("""^(\s*\d+)([.)]\s)"""), "$1\\\\$2")

    private fun escapeInline(text: String) =
        text.replace(Regex("""([\\`*~\[\]])"""), "\\\\$1").replace(Regex("<(/?u>)", RegexOption.IGNORE_CASE), "\\\\<$1")

    private fun inlineToMarkdown(node: Node): String {
        val out = StringBuilder()
        for (child in node.childNodes()) {
            if (child is TextNode) {
                out.append(escapeInline(child.wholeText.replace(' ', ' ').replace('\n', ' ')))
                continue
            }
            if (child !is Element) continue
            val inner = inlineToMarkdown(child)
            fun wrap(mark: String, end: String = mark) = if (inner.isNotBlank()) "$mark$inner$end" else inner
            out.append(
                when (child.normalName()) {
                    "strong", "b" -> wrap("**")
                    "em", "i" -> wrap("*")
                    "s", "del", "strike" -> wrap("~~")
                    "u" -> wrap("<u>", "</u>")
                    "code" -> "`${child.text()}`"
                    "a" -> {
                        val href = child.attr("href")
                        if (href.isEmpty() || child.text() == href) inner else "[$inner]($href)"
                    }
                    "br", "ul", "ol" -> ""
                    else -> inner
                }
            )
        }
        return out.toString()
    }

    private fun listToMarkdown(list: Element, depth: Int, lines: MutableList<String>) {
        var number = 1
        for (li in list.children()) {
            if (li.normalName() != "li") continue
            // quill 1 的扁平列表用 data-list 表示类型
            val ordered = li.attr("data-list").ifEmpty { if (list.normalName() == "ol") "ordered" else "bullet" } == "ordered"
            val marker = if (ordered) "${number++}." else "-"
            lines += "${"   ".repeat(depth)}$marker ${inlineToMarkdown(li)}"
            li.children().filter { it.normalName() == "ul" || it.normalName() == "ol" }.forEach { listToMarkdown(it, depth + 1, lines) }
        }
    }

    fun fromHtml(html: String): String {
        val lines = mutableListOf<String>()
        for (node in Jsoup.parseBodyFragment(html).body().childNodes()) {
            if (node is TextNode) {
                if (node.text().isNotBlank()) lines += escapeLineStart(escapeInline(node.text()))
                continue
            }
            if (node !is Element) continue
            val tag = node.normalName()
            when {
                tag == "ul" || tag == "ol" -> listToMarkdown(node, 0, lines)
                tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6' -> lines += "${"#".repeat(tag[1].digitToInt())} ${inlineToMarkdown(node)}"
                tag == "blockquote" -> lines += "> ${inlineToMarkdown(node)}".trimEnd()
                tag == "pre" -> {
                    lines += "```"; lines += node.wholeText().trim('\n'); lines += "```"
                }
                else -> lines += escapeLineStart(inlineToMarkdown(node))
            }
        }
        return lines.joinToString("\n")
    }
}
