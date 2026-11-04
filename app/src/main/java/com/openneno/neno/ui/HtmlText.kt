package com.openneno.neno.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.URI
import java.net.URLDecoder

/** 渲染笔记 html 时使用的颜色 */
data class HtmlColors(
    val link: Color,
    val tag: Color,
    val tagBackground: Color,
    val quote: Color,
    val code: Color,
    val highlight: Color,
)

/** 笔记内容的一个块. 与网页端 .neno-content 的排版一致: 段落没有额外间距, 空段落占一行 */
sealed interface NoteBlock {
    /** 段落; heading 为 1-6 时是标题 */
    data class Paragraph(val text: AnnotatedString, val heading: Int = 0) : NoteBlock

    /** 空段落(<p><br></p>), 占一行的高度 */
    data object Blank : NoteBlock

    /** 列表项: marker 是 "•" 或 "1." */
    data class ListItem(val text: AnnotatedString, val marker: String, val ordered: Boolean, val level: Int) : NoteBlock

    /** 引用(摘录). 编辑器每一行都是一个 blockquote, 连续的引用在界面上画成一组 */
    data class Quote(val text: AnnotatedString) : NoteBlock

    data class Code(val text: String) : NoteBlock
}

/**
 * 把笔记的 html 解析成块: 标签可点击, 网址变成短链接, 搜索词高亮.
 * 与网页端 renderContent.js 的规则一致.
 */
object HtmlText {
    // 网址到空白, 引号, 尖括号或中文标点为止
    private val URL_RE = Regex("https?://[^\\s<>\"'`，。！？、；：（）《》「」『』【】]+", RegexOption.IGNORE_CASE)
    private val TRAILING = Regex("[.,;:!?'\")\\]}>]+$")
    private val TAG_RE = Regex("#\\S+")
    private val LISTS = setOf("ul", "ol")
    private val BLOCK_TAGS = setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "blockquote", "pre", "section", "article")
    private val BLOCKED = setOf("script", "style", "iframe", "object", "embed", "link", "meta", "base", "form", "img", "video", "audio")

    fun parse(
        html: String,
        colors: HtmlColors,
        tags: Collection<String> = emptyList(),
        keyword: String = "",
        onTagClick: ((String) -> Unit)? = null,
    ): List<NoteBlock> {
        val ctx = Ctx(colors, tags.filter { it.isNotEmpty() }.toSet(), keyword.trim(), onTagClick)
        val blocks = mutableListOf<NoteBlock>()
        addParagraphs(blocks, Jsoup.parseBodyFragment(html).body().childNodes(), ctx, 0)
        // 去掉开头和结尾的空行, 中间的空行保留(与网页端一致)
        while (blocks.firstOrNull() == NoteBlock.Blank) blocks.removeAt(0)
        while (blocks.lastOrNull() == NoteBlock.Blank) blocks.removeAt(blocks.lastIndex)
        return blocks
    }

    private class Ctx(
        val colors: HtmlColors,
        val tags: Set<String>,
        val keyword: String,
        val onTagClick: ((String) -> Unit)?,
    )

    private fun block(el: Element, ctx: Ctx, out: MutableList<NoteBlock>, level: Int) {
        when (val tag = el.normalName()) {
            "ul", "ol" -> {
                var n = 0
                for (child in el.children()) {
                    if (child.normalName() != "li") {
                        block(child, ctx, out, level); continue
                    }
                    // quill 1 的扁平列表用 data-list / ql-indent-N 表示类型和层级
                    val dataList = child.attr("data-list")
                    val ordered = if (dataList.isNotEmpty()) dataList == "ordered" else tag == "ol"
                    val indent = Regex("ql-indent-(\\d)").find(child.className())?.groupValues?.get(1)?.toInt() ?: 0
                    n++
                    val inline = child.childNodes().filter { !(it is Element && it.normalName() in LISTS) }
                    out += NoteBlock.ListItem(inline(inline, ctx), if (ordered) "$n." else "•", ordered, level + indent)
                    child.children().filter { it.normalName() in LISTS }.forEach { block(it, ctx, out, level + indent + 1) }
                }
            }

            "blockquote" -> {
                val inner = mutableListOf<NoteBlock>()
                addParagraphs(inner, el.childNodes(), ctx, 0)
                if (inner.isEmpty()) out += NoteBlock.Quote(AnnotatedString(""))
                inner.forEach { b -> out += NoteBlock.Quote(if (b is NoteBlock.Paragraph) b.text else AnnotatedString("")) }
            }

            "pre" -> out += NoteBlock.Code(el.wholeText().trimEnd('\n'))
            "li" -> out += NoteBlock.ListItem(inline(el.childNodes(), ctx), "•", false, level)
            else -> {
                val heading = if (tag.length == 2 && tag[0] == 'h') tag[1].digitToIntOrNull() ?: 0 else 0
                addParagraphs(out, el.childNodes(), ctx, heading)
            }
        }
    }

    /** 一组节点: 其中的块级元素单独成块, 其余的文字成为段落 */
    private fun addParagraphs(out: MutableList<NoteBlock>, nodes: List<Node>, ctx: Ctx, heading: Int) {
        val onlyBreaks = nodes.all { (it is Element && it.normalName() == "br") || (it is TextNode && it.isBlank) }
        if (onlyBreaks) {
            if (nodes.isNotEmpty()) out += NoteBlock.Blank
            return
        }
        val pending = mutableListOf<Node>()
        fun flush() {
            if (pending.isEmpty()) return
            val text = inline(pending.toList(), ctx)
            pending.clear()
            if (text.isNotBlank()) out += NoteBlock.Paragraph(text, heading)
        }
        for (node in nodes) {
            if (node is Element && node.normalName() in BLOCK_TAGS) {
                flush(); block(node, ctx, out, 0)
            } else pending += node
        }
        flush()
    }

    private fun inline(nodes: List<Node>, ctx: Ctx): AnnotatedString {
        val raw = buildAnnotatedString {
            nodes.forEach { walk(it, ctx, inLink = false, inCode = false) }
        }
        // 去掉首尾的换行和空白(<p>文字<br></p>)
        var start = 0
        var end = raw.length
        while (end > 0 && raw[end - 1].isWhitespace()) end--
        while (start < end && raw[start] == '\n') start++
        return decorate(raw.subSequence(start, end), ctx)
    }

    private fun AnnotatedString.Builder.walk(node: Node, ctx: Ctx, inLink: Boolean, inCode: Boolean) {
        when (node) {
            is TextNode -> {
                var text = node.wholeText.replace('\n', ' ').replace(' ', ' ')
                if (text.isEmpty()) return
                // 搜索时保持原文, 保证搜索词能匹配
                if (!inLink && !inCode && ctx.keyword.isEmpty()) text = keepTagsTogether(text, ctx.tags)
                if (inLink || inCode) append(text) else appendLinkified(text, ctx)
            }

            is Element -> {
                val tag = node.normalName()
                if (tag in BLOCKED) return
                if (tag == "br") {
                    append('\n'); return
                }
                // quill 编辑时留下的光标占位
                if (node.hasClass("ql-cursor")) return
                val style: SpanStyle? = when (tag) {
                    "strong", "b" -> SpanStyle(fontWeight = FontWeight.SemiBold)
                    "em", "i" -> SpanStyle(fontStyle = FontStyle.Italic)
                    "u" -> SpanStyle(textDecoration = TextDecoration.Underline)
                    "s", "strike", "del" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                    "code" -> SpanStyle(fontFamily = FontFamily.Monospace, background = ctx.colors.code)
                    else -> spanFromCss(node.attr("style"))
                }
                val href = if (tag == "a") node.attr("href") else ""
                val validLink = Regex("^(https?:|mailto:).*", RegexOption.IGNORE_CASE).matches(href)
                val content: AnnotatedString.Builder.() -> Unit = {
                    if (validLink) {
                        val text = node.text()
                        withLink(LinkAnnotation.Url(href, linkStyles(ctx))) {
                            if (looksLikeUrl(text) && node.children().isEmpty()) append(shortUrl(text.trim()))
                            else node.childNodes().forEach { walk(it, ctx, true, inCode) }
                        }
                    } else node.childNodes().forEach { walk(it, ctx, inLink, inCode || tag == "code") }
                }
                if (style != null) withStyle(style) { content() } else content()
            }
        }
    }

    private fun linkStyles(ctx: Ctx) = TextLinkStyles(SpanStyle(color = ctx.colors.link, textDecoration = TextDecoration.Underline))

    /** 只保留粗体, 斜体, 下划线, 删除线; 忽略复制网页时带来的颜色和背景, 保证深色模式下可读 */
    private fun spanFromCss(style: String): SpanStyle? {
        if (style.isBlank()) return null
        var s = SpanStyle()
        var any = false
        if (Regex("font-weight:\\s*(bold|[6-9]00)").containsMatchIn(style)) { s = s.copy(fontWeight = FontWeight.SemiBold); any = true }
        if (Regex("font-style:\\s*italic").containsMatchIn(style)) { s = s.copy(fontStyle = FontStyle.Italic); any = true }
        if (style.contains("underline")) { s = s.copy(textDecoration = TextDecoration.Underline); any = true }
        if (style.contains("line-through")) { s = s.copy(textDecoration = TextDecoration.LineThrough); any = true }
        return if (any) s else null
    }

    private fun AnnotatedString.Builder.appendLinkified(text: String, ctx: Ctx) {
        var last = 0
        for (m in URL_RE.findAll(text)) {
            val url = trimUrl(m.value)
            if (url.length <= "https://".length) continue
            if (m.range.first > last) append(text.substring(last, m.range.first))
            withLink(LinkAnnotation.Url(url, linkStyles(ctx))) { append(shortUrl(url)) }
            last = m.range.first + url.length
        }
        if (last < text.length) append(text.substring(last))
    }

    /** 标记标签, 高亮搜索词 */
    private fun decorate(s: AnnotatedString, ctx: Ctx): AnnotatedString {
        if (ctx.tags.isEmpty() && ctx.keyword.isEmpty()) return s
        val text = s.text
        val linkRanges = s.getLinkAnnotations(0, s.length).map { it.start until it.end }
        return buildAnnotatedString {
            append(s)
            if (ctx.tags.isNotEmpty()) {
                for (m in TAG_RE.findAll(text)) {
                    val token = m.value.replace(JOINER, "")
                    if (token !in ctx.tags || linkRanges.any { m.range.first in it }) continue
                    val style = SpanStyle(color = ctx.colors.tag, background = ctx.colors.tagBackground, fontWeight = FontWeight.Medium)
                    val onClick = ctx.onTagClick
                    if (onClick != null) {
                        addLink(LinkAnnotation.Clickable("tag:$token", TextLinkStyles(style)) { onClick(token) }, m.range.first, m.range.last + 1)
                    } else addStyle(style, m.range.first, m.range.last + 1)
                }
            }
            if (ctx.keyword.isNotEmpty()) {
                var i = text.indexOf(ctx.keyword, ignoreCase = true)
                while (i >= 0) {
                    addStyle(SpanStyle(background = ctx.colors.highlight), i, i + ctx.keyword.length)
                    i = text.indexOf(ctx.keyword, i + ctx.keyword.length, ignoreCase = true)
                }
            }
        }
    }

    // 不可见的连字符, 禁止在它两侧换行
    private const val JOINER = "⁠"

    /** 标签整体换到下一行, 不在标签中间折行(与网页端 .neno-tag 的 inline-block 一致) */
    private fun keepTagsTogether(text: String, tags: Set<String>): String {
        if (tags.isEmpty() || '#' !in text) return text
        return TAG_RE.replace(text) { m ->
            if (m.value in tags && m.value.length <= 24) {
                // 按码位拆分, 不拆开 emoji
                m.value.codePoints().toArray().joinToString(JOINER) { String(Character.toChars(it)) }
            } else m.value
        }
    }

    private fun looksLikeUrl(text: String) = Regex("^(https?://|www\\.)\\S+$", RegexOption.IGNORE_CASE).matches(text.trim())

    private fun trimUrl(raw: String): String {
        var url = raw.replace(TRAILING, "")
        // 保留成对的右括号, 例如维基百科的链接
        val opens = url.count { it == '(' }
        val closes = url.count { it == ')' }
        if (opens > closes && raw.getOrNull(url.length) == ')') url += ")"
        return url
    }

    /** 网址太长时只显示 域名 + 路径, 超出部分省略 */
    fun shortUrl(url: String, max: Int = 40): String {
        var display = try {
            val u = URI(url)
            val path = if (u.rawPath.isNullOrEmpty() || u.rawPath == "/") "" else u.rawPath
            val extra = if (path.isEmpty()) (u.rawQuery?.let { "?$it" } ?: "") + (u.rawFragment?.let { "#$it" } ?: "") else ""
            (u.host ?: "").removePrefix("www.") + path + extra
        } catch (e: Exception) {
            url.replace(Regex("^https?://", RegexOption.IGNORE_CASE), "")
        }
        display = runCatching { URLDecoder.decode(display.replace("+", "%2B"), "UTF-8") }.getOrDefault(display)
        if (display.isEmpty()) display = url
        return if (display.length > max) display.substring(0, max - 1) + "…" else display
    }
}
