package com.openneno.neno

import com.openneno.neno.data.JsJson
import com.openneno.neno.update.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun compareVersions() {
        assertTrue(Updater.isNewer("1.0.5", "1.0.4"))
        assertTrue(Updater.isNewer("v1.0.10", "1.0.9"))
        assertTrue(Updater.isNewer("1.1", "1.0.99"))
        assertTrue(Updater.isNewer("2.0.0", "1.9.9"))
        assertFalse(Updater.isNewer("1.0.4", "1.0.4"))
        assertFalse(Updater.isNewer("1.0", "1.0.0"))
        assertFalse(Updater.isNewer("1.0.3", "1.0.4"))
        assertFalse(Updater.isNewer("1.0.4", "1.0.4-debug"))
    }

    @Test
    fun parseRelease() {
        val json = JsJson.parseObject(
            """
            {
              "tag_name": "v1.0.5",
              "html_url": "https://github.com/openneno/neno-mobile/releases/tag/v1.0.5",
              "body": "## 更新内容\r\n\r\n- feat: markdown editor mode\r\n- fix(sync): retry on 502\r\n\r\n## 安装\r\n\r\n下载下方的 `neno-v1.0.5.apk` 安装。\r\n",
              "assets": [
                {"name": "neno-v1.0.5.apk.sha256", "size": 82, "browser_download_url": "https://example.com/neno-v1.0.5.apk.sha256"},
                {"name": "neno-v1.0.5.apk", "size": 3156057, "browser_download_url": "https://example.com/neno-v1.0.5.apk"}
              ]
            }
            """.trimIndent()
        )
        val release = Updater.parseRelease(json)!!
        assertEquals("1.0.5", release.version)
        assertEquals("neno-v1.0.5.apk", release.apkName)
        assertEquals("https://example.com/neno-v1.0.5.apk", release.apkUrl)
        assertEquals("https://example.com/neno-v1.0.5.apk.sha256", release.sha256Url)
        assertEquals(3156057L, release.apkSize)
        assertEquals("• markdown editor mode\n• retry on 502", release.notes)
    }

    @Test
    fun releaseWithoutApk() {
        assertNull(Updater.parseRelease(JsJson.parseObject("""{"tag_name": "v1.0.5", "assets": []}""")))
    }

    @Test
    fun parseSha256() {
        val hash = "5286036f60b37af7c9dc4fe0a405892a8f7fe638b5e08aef3c16227776ad9cf5"
        assertEquals(hash, Updater.parseSha256("$hash  neno-v1.0.4.apk\n"))
        assertEquals(hash, Updater.parseSha256(hash.uppercase()))
        assertNull(Updater.parseSha256("Not Found"))
    }
}
