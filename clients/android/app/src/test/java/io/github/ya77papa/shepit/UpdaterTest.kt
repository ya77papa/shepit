package io.github.ya77papa.shepit

import io.github.ya77papa.shepit.update.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Розбір справжньої відповіді GitHub Releases (знімок у test/resources). */
class UpdaterTest {
    private val json = javaClass.classLoader!!.getResource("releases.json")!!.readText()

    @Test
    fun picksNewest() {
        val r = Updater.newest(json, 1)!!
        assertEquals("0.1.6", r.versionName)
        assertEquals(6, r.versionCode)
        assertEquals(64, r.sha256.length)
        assert(r.url.endsWith(".apk"))
    }

    /** GitHub сортує теги як текст: 0.1.9 стоїть перед 0.1.10 — брати треба найбільший номер збірки. */
    @Test
    fun numericNotLexicographic() {
        fun rel(v: String) = """{"tag_name":"android-v$v","draft":false,"prerelease":false,"body":"","html_url":"","assets":[{"name":"s.apk","size":1,"digest":"sha256:${"a".repeat(64)}","browser_download_url":"https://x/s.apk"}]}"""
        assertEquals("0.1.10", Updater.newest("[${rel("0.1.9")},${rel("0.1.10")}]", 9)!!.versionName)
        assertEquals("0.2.11", Updater.newest(rel("0.2.11"), 10)!!.versionName)
        assertNull(Updater.newest(rel("0.2.11"), 11))
    }

    @Test
    fun nothingWhenCurrent() {
        assertNull(Updater.newest(json, 6))
        assertNull(Updater.newest(json, 99))
        assertNull(Updater.newest("[]", 1))
    }
}
