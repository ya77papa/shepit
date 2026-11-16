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

    @Test
    fun nothingWhenCurrent() {
        assertNull(Updater.newest(json, 6))
        assertNull(Updater.newest(json, 99))
        assertNull(Updater.newest("[]", 1))
    }
}
