package com.openneno.neno

import com.openneno.neno.data.JsJson
import com.openneno.neno.data.ObjectId
import com.openneno.neno.sync.GitBlob
import com.openneno.neno.sync.RepoLayout
import com.openneno.neno.ui.screens.SeededRandom
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 与网页端保持一致的关键逻辑. 期望值由网页端的 JavaScript 代码生成(见 resources/js_note.json).
 */
class CompatTest {
    private val fixture = JsJson.parseObject(javaClass.getResource("/js_note.json")!!.readText())
    private val jsText = fixture["text"]!!.jsonPrimitive.content
    private val jsSha = fixture["sha"]!!.jsonPrimitive.content

    @Test
    fun `stringify matches JSON stringify with tab indent`() {
        val parsed = JsJson.parseObject(jsText)
        assertEquals(jsText, JsJson.stringify(parsed, "\t"))
    }

    @Test
    fun `blob sha matches git hash-object`() {
        assertEquals(jsSha, GitBlob.sha(jsText.toByteArray(Charsets.UTF_8)))
        // git hash-object 空文件
        assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", GitBlob.sha(ByteArray(0)))
    }

    @Test
    fun `seeded random matches random js`() {
        val r = SeededRandom("2026-11-04#0")
        val expected = listOf(0.3101977633778006, 0.06005718349479139, 0.8955327840521932)
        expected.forEach { assertEquals(it, r.next(), 1e-15) }
    }

    @Test
    fun `classify repo paths`() {
        assertEquals("note", RepoLayout.classify("2026-10-26/6adecb5eebcd69e38944057d.json")!!.kind)
        val pic = RepoLayout.classify("picData/6adecb5eebcd69e38944057d.png")!!
        assertEquals("pic", pic.kind); assertEquals("png", pic.suffix)
        assertEquals("pinTags", RepoLayout.classify("pinTags/pinTags.json")!!.kind)
        assertNull(RepoLayout.classify("README.md"))
        assertNull(RepoLayout.classify("countDate/countDate.json"))
    }

    @Test
    fun `object id is 24 hex and increasing`() {
        val a = ObjectId.next()
        val b = ObjectId.next()
        assertTrue(Regex("^[0-9a-f]{24}$").matches(a))
        assertTrue(b > a)
    }

    @Test
    fun `compact stringify for neno token`() {
        val o = JsJson.parseObject("""{"access_token":"t","repoName":"r","githubName":"g","gitUrl":"https://api.github.com"}""")
        assertEquals("""{"access_token":"t","repoName":"r","githubName":"g","gitUrl":"https://api.github.com"}""", JsJson.stringify(o))
    }
}
