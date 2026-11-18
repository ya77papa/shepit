package io.github.ya77papa.shepit

import io.github.ya77papa.shepit.data.PresenceInfo
import io.github.ya77papa.shepit.data.PresenceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class PresenceTest {
    private val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 18, 12, 0, 0) }.timeInMillis

    @Test
    fun texts() {
        assertNull(PresenceText.of(null, now))
        assertEquals("в мережі", PresenceText.of(PresenceInfo(on = true), now))
        assertEquals("востаннє нещодавно", PresenceText.of(PresenceInfo(hidden = true), now))
        assertEquals("востаннє щойно", PresenceText.of(PresenceInfo(at = now - 20_000), now))
        assertEquals("востаннє 15 хв тому", PresenceText.of(PresenceInfo(at = now - 15 * 60_000), now))
        assertEquals("востаннє сьогодні о 09:30", PresenceText.of(PresenceInfo(at = now - 150 * 60_000), now))
        assertEquals("востаннє вчора о 21:40", PresenceText.of(PresenceInfo(at = now - (14 * 60 + 20) * 60_000L), now))
        assertTrue(PresenceText.of(PresenceInfo(at = now - 5 * 86_400_000L), now)!!.startsWith("востаннє 13 "))
    }
}
