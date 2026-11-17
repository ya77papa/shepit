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
    fun imagesFromWeb() {
        // Рівно так кодує веб-клієнт: {...b, v: 1}.
        val web = """{"t":"image","mime":"image/jpeg","w":1600,"h":1200,"data":"/9j/AAAA","caption":"з моря","id":"$id","g":"$id","v":1}"""
        assertEquals(Body.Image("image/jpeg", 1600, 1200, "/9j/AAAA", "з моря", id, id), Content.decode(web))
        assertEquals(Body.Image("image/jpeg", 10, 10, "AAAA"), Content.decode("""{"t":"image","mime":"image/jpeg","w":10,"h":10,"data":"AAAA","v":1}"""))
        // Не JPEG, дивні розміри, сміття в даних — пропускаємо.
        assertEquals(Body.Unknown, Content.decode("""{"t":"image","mime":"image/svg+xml","w":10,"h":10,"data":"AAAA","v":1}"""))
        assertEquals(Body.Unknown, Content.decode("""{"t":"image","mime":"image/jpeg","w":0,"h":10,"data":"AAAA","v":1}"""))
        assertEquals(Body.Unknown, Content.decode("""{"t":"image","mime":"image/jpeg","w":10,"h":99999,"data":"AAAA","v":1}"""))
        assertEquals(Body.Unknown, Content.decode("""{"t":"image","mime":"image/jpeg","w":10,"h":10,"data":"<script>","v":1}"""))
        val back = Content.encode(Body.Image("image/jpeg", 3, 4, "AAAA", null, id))
        assertEquals(Body.Image("image/jpeg", 3, 4, "AAAA", null, id), Content.decode(back))
    }

    @Test
    fun receiptsFromWeb() {
        assertEquals(Body.Receipt(listOf(id), "d"), Content.decode("""{"t":"receipt","ids":["$id","bad"],"s":"d","v":1}"""))
        assertEquals(Body.Receipt(listOf(id), "r", id), Content.decode("""{"t":"receipt","ids":["$id"],"s":"r","g":"$id","v":1}"""))
        assertEquals(Body.Unknown, Content.decode("""{"t":"receipt","ids":["$id"],"s":"x","v":1}"""))
        assertEquals(Body.Unknown, Content.decode("""{"t":"receipt","ids":["bad"],"s":"d","v":1}"""))
        assertEquals(Body.Receipt(listOf(id), "r", id), Content.decode(Content.encode(Body.Receipt(listOf(id), "r", id))))
    }

    @Test
    fun reactionsFromWeb() {
        assertEquals(Body.React(id, "👍"), Content.decode("""{"t":"react","id":"$id","e":"👍","v":1}"""))
        assertEquals(Body.React(id, "❤️", id), Content.decode("""{"t":"react","id":"$id","e":"❤️","g":"$id","v":1}"""))
        assertEquals(Body.React(id, ""), Content.decode("""{"t":"react","id":"$id","e":"","v":1}"""))
        assertEquals("латиниця/розмітка — не реакція", Body.Unknown, Content.decode("""{"t":"react","id":"$id","e":"<b>","v":1}"""))
        assertEquals(Body.Unknown, Content.decode("""{"t":"react","id":"x","e":"👍","v":1}"""))
        val r = Body.React(id, "🇺🇦", id)
        assertEquals(r, Content.decode(Content.encode(r)))
        assertTrue(Content.EMOJI.flatMap { it.second }.all { Content.validReaction(it) })
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
