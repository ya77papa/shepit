package io.github.ya77papa.shepit

import io.github.ya77papa.shepit.data.Body
import io.github.ya77papa.shepit.data.Content
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Формат повідомлень — той самий, що у веб-клієнта (src/lib/content.ts). */
class ContentTest {
    private val id = "0123456789abcdef0123456789abcdef"

    @Test
    fun fromWeb() {
        assertEquals(Body.Text("привіт", id), Content.decode("""{"v":1,"t":"text","text":"привіт","id":"$id"}"""))
        assertEquals(Body.Text("старе"), Content.decode("""{"v":1,"t":"text","text":"старе"}"""))
        assertNull((Content.decode("""{"v":1,"t":"text","text":"x","id":"../../etc"}""") as Body.Text).id)
        assertEquals(Body.Delete(listOf(id)), Content.decode("""{"v":1,"t":"delete","ids":["$id","bad"]}"""))
        assertEquals(Body.Clear, Content.decode("""{"v":1,"t":"clear"}"""))
        assertEquals(Body.Unknown, Content.decode("""{"v":1,"t":"reaction","emoji":"👍"}"""))
        assertEquals(Body.Text("{не json"), Content.decode("{не json"))
    }

    @Test
    fun roundTrip() {
        val mid = Content.newMsgId()
        assertTrue(mid.matches(Regex("[0-9a-f]{32}")))
        for (b in listOf(Body.Text("a", mid), Body.Voice("audio/ogg", 1200, "AAAA", mid), Body.Delete(listOf(mid)), Body.Clear)) {
            assertEquals(b, Content.decode(Content.encode(b)))
        }
    }
}
