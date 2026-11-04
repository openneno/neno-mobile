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
data class HtmlColors(val link: Color, val tag: Color, val tagBackground: Color, val quote: Color, val code: Color, val highlight: Color)

/**
 * 把笔记的 html 转换成 AnnotatedString: 标签可点击, 网址变成短链接, 搜索词高亮.
 * 与网页端 renderContent.js 的规则一致.
 */
object HtmlText {
    // 网址到空白, 引号, 尖括号或中文标点为止
    private val URL_RE = Regex("https?://[^\\s<>\"'`，。！？、；：（）《》「」『』【】]+", RegexOption.IGNORE_CASE)
    private val TRAILING = Regex("[.,;:!?'\")\\]}>]+$")
    private val BLOCKS = setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "pre", "ul", "ol")
    private val BLOCKED = setOf("script", "style", "iframe", "object", "embed", "link", "meta", "base", "form", "img")

    fun render(
        html: String,
        colors: HtmlColors,
        tags: Collection<String> = emptyList(),
        keyword: String = "",
        onTagClick: ((String) -> Unit)? = null,
        linkify: Boolean = true,
    ): AnnotatedString {
        val tagSet = tags.filter { it.isNotEmpty() }.toSet()
        val body = Jsoup.parseBodyFragment(html).body()
        val raw = buildAnnotatedString {
            val ctx = Ctx(colors, linkify)
            body.childNodes().forEach { walk(it, ctx) }
        }
        val trimmed = trimEnd(raw)
        return decorate(trimmed, colors, tagSet, keyword, onTagClick)
    }

    private class Ctx(val colors: HtmlColors, val linkify: Boolean) {
        val lists = ArrayDeque<IntArray>() // [是否有序(1/0), 当前序号]
        var inLink = 0
        var inCode = 0
        var inPre = 0
        var quote = 0
    }

    private fun AnnotatedString.Builder.ensureNewLine() {
        if (length > 0 && toString().last() != '\n') append('\n')
    }

    private fun AnnotatedString.Builder.walk(node: Node, ctx: Ctx) {
        when (node) {
            is TextNode -> {
                var text = node.wholeText
                if (ctx.inPre == 0) text = text.replace('\n', ' ').replace(' ', ' ')
                if (text.isEmpty()) return
                appendText(text, ctx)
            }

            is Element -> {
                val tag = node.normalName()
                if (tag in BLOCKED) return
                if (tag == "br") {
                    append('\n'); return
                }
                val block = tag in BLOCKS
                if (block && tag != "ul" && tag != "ol") ensureNewLine()
                when (tag) {
                    "ul", "ol" -> {
                        ctx.lists.addLast(intArrayOf(if (tag == "ol") 1 else 0, 0))
                        node.childNodes().forEach { walk(it, ctx) }
                        ctx.lists.removeLast()
                        return
                    }

                    "li" -> {
                        val list = ctx.lists.lastOrNull()
                        // quill 2 的 getSemanticHTML 之前的版本用 data-list 区分
                        val ordered = node.attr("data-list").let { if (it.isNotEmpty()) it == "ordered" else list?.get(0) == 1 }
                        val indent = "    ".repeat((ctx.lists.size - 1).coerceAtLeast(0))
                        val marker = if (ordered) {
                            list?.let { it[1]++ }
                            "${list?.get(1) ?: 1}. "
                        } else "• "
                        withStyle(SpanStyle(color = ctx.colors.quote)) { append(indent + marker) }
                    }

                    "blockquote" -> {
                        ctx.quote++
                        withStyle(SpanStyle(color = ctx.colors.tag)) { append("▍") }
                        withStyle(SpanStyle(color = ctx.colors.quote)) { node.childNodes().forEach { walk(it, ctx) } }
                        ctx.quote--
                        ensureNewLine()
                        return
                    }
                }
                val style: SpanStyle? = when (tag) {
                    "strong", "b" -> SpanStyle(fontWeight = FontWeight.SemiBold)
                    "em", "i" -> SpanStyle(fontStyle = FontStyle.Italic)
                    "u" -> SpanStyle(textDecoration = TextDecoration.Underline)
                    "s", "strike", "del" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                    "code" -> SpanStyle(fontFamily = FontFamily.Monospace, background = ctx.colors.code)
                    "pre" -> SpanStyle(fontFamily = FontFamily.Monospace, background = ctx.colors.code)
                    "h1", "h2", "h3", "h4", "h5", "h6" -> SpanStyle(fontWeight = FontWeight.Bold)
                    else -> spanFromCss(node.attr("style"))
                }
                if (tag == "code") ctx.inCode++
                if (tag == "pre") ctx.inPre++
                val href = if (tag == "a") node.attr("href") else ""
                val validLink = href.matches(Regex("^(https?:|mailto:).*", RegexOption.IGNORE_CASE))
                val content: AnnotatedString.Builder.() -> Unit = {
                    if (tag == "a" && validLink) {
                        ctx.inLink++
                        val text = node.text()
                        withLink(LinkAnnotation.Url(href, TextLinkStyles(SpanStyle(color = ctx.colors.link)))) {
                            if (looksLikeUrl(text) && node.children().isEmpty()) append(shortUrl(text.trim()))
                            else node.childNodes().forEach { walk(it, ctx) }
                        }
                        ctx.inLink--
                    } else node.childNodes().forEach { walk(it, ctx) }
                }
                if (style != null) withStyle(style) { content() } else content()
                if (tag == "code") ctx.inCode--
                if (tag == "pre") ctx.inPre--
                if (block) ensureNewLine()
            }
        }
    }

    private fun spanFromCss(style: String): SpanStyle? {
        if (style.isBlank()) return null
        var s = SpanStyle()
        var any = false
        if (Regex("font-weight:\\s*(bold|[6-9]00)").containsMatchIn(style)) { s = s.copy(fontWeight = FontWeight.SemiBold); any = true }
        if (style.contains("font-style: italic") || style.contains("font-style:italic")) { s = s.copy(fontStyle = FontStyle.Italic); any = true }
        if (style.contains("underline")) { s = s.copy(textDecoration = TextDecoration.Underline); any = true }
        if (style.contains("line-through")) { s = s.copy(textDecoration = TextDecoration.LineThrough); any = true }
        return if (any) s else null
    }

    private fun AnnotatedString.Builder.appendText(text: String, ctx: Ctx) {
        if (!ctx.linkify || ctx.inLink > 0 || ctx.inCode > 0 || ctx.inPre > 0) {
            append(text); return
        }
        var last = 0
        for (m in URL_RE.findAll(text)) {
            val url = trimUrl(m.value)
            if (url.length <= "https://".length) continue
            if (m.range.first > last) append(text.substring(last, m.range.first))
            withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = ctx.colors.link)))) { append(shortUrl(url)) }
            last = m.range.first + url.length
        }
        if (last < text.length) append(text.substring(last))
    }

    private fun trimEnd(s: AnnotatedString): AnnotatedString {
        var end = s.length
        while (end > 0 && s[end - 1].isWhitespace()) end--
        return s.subSequence(0, end)
    }

    /** 标记标签, 高亮搜索词 */
    private fun decorate(
        s: AnnotatedString,
        colors: HtmlColors,
        tags: Set<String>,
        keyword: String,
        onTagClick: ((String) -> Unit)?,
    ): AnnotatedString {
        val text = s.text
        val linkRanges = s.getLinkAnnotations(0, s.length).map { it.start until it.end }
        return buildAnnotatedString {
            append(s)
            if (tags.isNotEmpty()) {
                for (m in Regex("#\\S+").findAll(text)) {
                    val token = m.value
                    if (token !in tags || linkRanges.any { m.range.first in it }) continue
                    val style = SpanStyle(color = colors.tag, background = colors.tagBackground)
                    if (onTagClick != null) {
                        addLink(
                            LinkAnnotation.Clickable("tag", TextLinkStyles(style)) { onTagClick(token) },
                            m.range.first, m.range.last + 1
                        )
                    } else addStyle(style, m.range.first, m.range.last + 1)
                }
            }
            val k = keyword.trim()
            if (k.isNotEmpty()) {
                var i = text.indexOf(k, ignoreCase = true)
                while (i >= 0) {
                    addStyle(SpanStyle(background = colors.highlight), i, i + k.length)
                    i = text.indexOf(k, i + k.length, ignoreCase = true)
                }
            }
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
