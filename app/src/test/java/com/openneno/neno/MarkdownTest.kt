package com.openneno.neno

import com.openneno.neno.data.Markdown
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {
    private val sample = listOf(
        "# 标题",
        "普通 **粗** *斜* ~~删~~ `code` [链接](https://example.com) #标签",
        "",
        "- a",
        "- b",
        "   - c",
        "1. x",
        "2. y",
        "> 引用",
        "```",
        "fn()",
        "  x",
        "```",
        "1\\. 不是列表",
        "\\- 也不是",
    ).joinToString("\n")

    @Test
    fun markdownToHtml() {
        assertEquals(
            "<h1>标题</h1><p>普通 <strong>粗</strong> <em>斜</em> <s>删</s> <code>code</code> <a href=\"https://example.com\">链接</a> #标签</p>" +
                "<p><br></p><ul><li>a</li><li>b<ul><li>c</li></ul></li></ul><ol><li>x</li><li>y</li></ol>" +
                "<blockquote>引用</blockquote><pre>fn()\n  x</pre><p>1. 不是列表</p><p>- 也不是</p>",
            Markdown.toHtml(sample),
        )
    }

    @Test
    fun roundTrip() {
        assertEquals(sample, Markdown.fromHtml(Markdown.toHtml(sample)))
    }

    @Test
    fun webEditorHtml() {
        // 网页端编辑器保存的 html: 空行是 <p></p>, 列表是标准的 <ul>/<ol>
        val html = "<p>第一行 <strong>粗体</strong> #读书</p><p></p><ul><li>苹果</li><li>香蕉</li></ul><blockquote>一句引用</blockquote><p>结尾</p>"
        assertEquals("第一行 **粗体** #读书\n\n- 苹果\n- 香蕉\n> 一句引用\n结尾", Markdown.fromHtml(html))
    }

    @Test
    fun escapesLiteralSyntax() {
        val html = "<p>2*3*4 = 24</p><p># 不是标题</p>"
        val md = Markdown.fromHtml(html)
        assertEquals("2\\*3\\*4 = 24\n\\# 不是标题", md)
        assertEquals(html, Markdown.toHtml(md))
    }
}
