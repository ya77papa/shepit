package io.github.ya77papa.shepit

import io.github.ya77papa.shepit.data.Body
import io.github.ya77papa.shepit.data.Content
import io.github.ya77papa.shepit.data.Member
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
    fun groupsFromWeb() {
        val a = "05" + "a".repeat(64)
        val b = "05" + "b".repeat(64)
        // Рівно так кодує веб-клієнт (encode = {...b, v: 1}).
        val web = """{"t":"group","g":"$id","name":"Сім'я","members":[{"id":"$a","name":"Аліса"},{"id":"$b","name":"Боб"}],"admins":["$a"],"ver":2,"v":1}"""
        val st = Content.decode(web) as Body.GroupState
        assertEquals(2, st.ver)
        assertEquals(listOf(Member(a, "Аліса"), Member(b, "Боб")), st.members)
        assertEquals(st, Content.decode(Content.encode(st)))
        assertEquals(Body.Text("hi", id, id), Content.decode("""{"t":"text","text":"hi","id":"$id","g":"$id","v":1}"""))
        assertEquals(Content.INVALID_GROUP, (Content.decode("""{"t":"text","text":"hi","g":"x","v":1}""") as Body.Text).g)
        assertEquals(Body.Unknown, Content.decode("""{"t":"group","g":"$id","name":"x","members":[],"admins":["$a"],"ver":1,"v":1}"""))
        assertEquals(Body.Leave(id), Content.decode("""{"t":"leave","g":"$id","v":1}"""))
        assertEquals(Body.Profile("Аліса", "QUJD"), Content.decode("""{"t":"profile","name":"Аліса","avatar":"QUJD","v":1}"""))
        assertEquals(Body.Profile("Аліса", null), Content.decode("""{"t":"profile","name":"Аліса","avatar":"<script>","v":1}"""))
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
