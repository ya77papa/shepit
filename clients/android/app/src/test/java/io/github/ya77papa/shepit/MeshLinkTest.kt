package io.github.ya77papa.shepit

import io.github.ya77papa.shepit.mesh.MeshEnvelope
import io.github.ya77papa.shepit.mesh.MeshLink
import io.github.ya77papa.shepit.mesh.MeshNode
import io.github.ya77papa.shepit.mesh.MeshTags
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread

class MeshLinkTest {
    /** Телефон: його вихідні конверти й теги, які він упізнає. */
    class Fake(val out: MutableList<MeshEnvelope>, val mine: Set<String>) : MeshNode {
        val got = mutableListOf<MeshEnvelope>()
        val done = mutableListOf<String>()
        override fun offers() = out.toList()
        override fun isForMe(tag: ByteArray) = tag.decodeToString() in mine
        override fun receive(env: MeshEnvelope): Boolean { got += env; return true }
        override fun delivered(ids: List<String>) { done += ids; out.removeAll { it.id in ids } }
    }

    private fun env(tag: String, body: String, exp: Long = System.currentTimeMillis() + 60_000) = MeshEnvelope(tag.toByteArray(), exp, body.toByteArray())

    private fun exchange(a: Fake, b: Fake): Pair<MeshLink.Stats, MeshLink.Stats> {
        val ab = PipedOutputStream(); val abIn = PipedInputStream(ab, 1 shl 16)
        val ba = PipedOutputStream(); val baIn = PipedInputStream(ba, 1 shl 16)
        var sb: MeshLink.Stats? = null
        var err: Throwable? = null
        val t = thread { runCatching { sb = MeshLink(b).run(abIn, ba, initiator = false) }.onFailure { err = it } }
        val sa = MeshLink(a).run(baIn, ab, initiator = true)
        t.join(10_000)
        err?.let { throw it }
        return sa to sb!!
    }

    @Test
    fun twoPhonesSwap() {
        // Аліса має лист Бобу (тег "toBob") і лист Кірі; Боб — лист Алісі.
        val big = "ф".repeat(300_000) // більше за буфер каналу: перевірка, що немає взаємоблокування
        val alice = Fake(mutableListOf(env("toBob", "привіт Бобе"), env("toKira", "привіт Кіро"), env("toBob", big)), setOf("toAlice"))
        val bob = Fake(mutableListOf(env("toAlice", "привіт Алісо")), setOf("toBob"))
        val (sa, sb) = exchange(alice, bob)
        assertEquals(listOf("привіт Бобе", big), bob.got.map { it.body.decodeToString() })
        assertEquals(listOf("привіт Алісо"), alice.got.map { it.body.decodeToString() })
        // Доставлене відправник забуває; лист Кірі лишається чекати.
        assertEquals(listOf("toKira"), alice.out.map { it.tag.decodeToString() })
        assertTrue(bob.out.isEmpty())
        assertEquals(2, sa.sent); assertEquals(2, sa.delivered); assertEquals(1, sb.delivered)
        // Повторна зустріч: нового нічого.
        val (sa2, _) = exchange(alice, bob)
        assertEquals(0, sa2.sent)
    }

    @Test
    fun expiredNotOffered() {
        val alice = Fake(mutableListOf(env("toBob", "старе", exp = 1)), emptySet())
        val bob = Fake(mutableListOf(), setOf("toBob"))
        exchange(alice, bob)
        assertTrue(bob.got.isEmpty())
    }

    @Test
    fun tags() {
        val s = ByteArray(32) { it.toByte() }
        val day = MeshTags.day(1_763_500_000_000)
        val t1 = MeshTags.tag(s, "alice", day)
        assertEquals(16, t1.size)
        assertArrayEquals(t1, MeshTags.tag(s, "alice", day))
        assertFalse(t1.contentEquals(MeshTags.tag(s, "bob", day)))       // інший напрям
        assertFalse(t1.contentEquals(MeshTags.tag(s, "alice", day + 1))) // інший день
        assertFalse(t1.contentEquals(MeshTags.tag(ByteArray(32), "alice", day))) // інша пара
    }
}
