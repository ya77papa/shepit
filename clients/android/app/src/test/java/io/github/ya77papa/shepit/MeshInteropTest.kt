package io.github.ya77papa.shepit

import io.github.ya77papa.shepit.core.Core
import io.github.ya77papa.shepit.data.Api
import io.github.ya77papa.shepit.mesh.MeshEnvelope
import io.github.ya77papa.shepit.mesh.MeshLink
import io.github.ya77papa.shepit.mesh.MeshNode
import io.github.ya77papa.shepit.mesh.MeshTags
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread

/**
 * Справжні ядра + справжні теги + протокол обміну, без сервера під час розмови.
 * Сервер потрібен лише щоб один раз установити сесію (як у житті — коли інтернет ще був).
 * SHEPIT_TEST_OPEN_SERVER=http://…
 */
class MeshInteropTest {
    /** Те, що в застосунку робить Repo: черга, теги «мені», розшифрування. */
    class Phone(val core: Core, val contacts: List<String>) : MeshNode {
        val outbox = mutableListOf<MeshEnvelope>()
        val inbox = mutableListOf<String>()
        private val me = core.identity()
        fun queue(peer: String, text: String) {
            val now = System.currentTimeMillis()
            val tag = MeshTags.tag(core.meshSecret(peer), me, MeshTags.day(now))
            outbox += MeshEnvelope(tag, now + MeshTags.TTL_MS, core.meshEncrypt(peer, text))
        }
        private fun forMe(): Map<String, String> = contacts.flatMap { p ->
            val s = core.meshSecret(p)
            MeshTags.window(System.currentTimeMillis()).map { d -> MeshTags.tag(s, p, d).toList().toString() to p }
        }.toMap()
        override fun offers() = outbox.toList()
        override fun isForMe(tag: ByteArray) = tag.toList().toString() in forMe()
        override fun receive(env: MeshEnvelope): Boolean {
            val from = forMe().getValue(env.tag.toList().toString())
            inbox += core.meshDecrypt(from, env.body)
            return true
        }
        override fun delivered(ids: List<String>) { outbox.removeAll { it.id in ids } }
    }

    private fun meet(a: Phone, b: Phone) {
        val ab = PipedOutputStream(); val abIn = PipedInputStream(ab, 1 shl 16)
        val ba = PipedOutputStream(); val baIn = PipedInputStream(ba, 1 shl 16)
        var err: Throwable? = null
        val t = thread { runCatching { MeshLink(b).run(abIn, ba, false) }.onFailure { err = it } }
        MeshLink(a).run(baIn, ab, true)
        t.join(10_000)
        err?.let { throw it }
    }

    @Test
    fun conversationOverMesh() {
        val server = System.getenv("SHEPIT_TEST_OPEN_SERVER")
        assumeTrue(server != null)
        val api = Api(server!!)
        val root = api.get("/v0/trust-root").trim()
        fun register(): Core = Core.create(root).also { c ->
            val body = c.keyUploadJson()!!.toByteArray()
            api.send("PUT", "/v0/keys", body, c.authHeader("PUT /v0/keys", body))
        }
        val a = register(); val b = register(); val c = register()
        // Колись, коли інтернет був, Аліса написала Бобу — сесія є.
        a.startSession(api.get("/v0/keys/${b.identity()}"))
        // Далі — без сервера.
        val alice = Phone(a, listOf(b.identity(), c.identity()))
        val bob = Phone(b, listOf(a.identity()))
        val kira = Phone(c, listOf(a.identity()))
        alice.queue(b.identity(), "Ми в укритті на Шевченка, все добре")
        alice.queue(b.identity(), "Світла немає, зв'язку теж")

        // Кіра поруч, але листи не їй — вона їх не бере й не читає.
        meet(alice, kira)
        assertEquals(emptyList<String>(), kira.inbox)
        assertEquals(2, alice.outbox.size)

        meet(alice, bob)
        assertEquals(listOf("Ми в укритті на Шевченка, все добре", "Світла немає, зв'язку теж"), bob.inbox)
        assertEquals(0, alice.outbox.size) // доставлено — Аліса забула

        bob.queue(a.identity(), "Прийняв, тримайтеся")
        meet(bob, alice)
        assertEquals(listOf("Прийняв, тримайтеся"), alice.inbox)
    }
}
