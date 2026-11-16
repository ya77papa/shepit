package io.github.ya77papa.shepit

import io.github.ya77papa.shepit.core.Core
import io.github.ya77papa.shepit.data.Api
import io.github.ya77papa.shepit.data.ChannelRec
import io.github.ya77papa.shepit.data.Channels
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Канали: Android ↔ сервер ↔ WebCrypto (Node, ті самі виклики, що в браузері).
 * Потрібен локальний сервер у вільному режимі: SHEPIT_TEST_OPEN_SERVER=http://….
 */
class ChannelsInteropTest {
    @Test
    fun cryptoRoundTrip() {
        val k = Channels.newKeys(public = false)
        assertEquals(32, k.id.length)
        val c = Channels.seal(k.key, "таємниця")
        assertEquals("таємниця", Channels.open(k.key, c))
        assertNull(Channels.open(Channels.newKeys(false).key, c))
        assertEquals("відкрито", Channels.open(null, Channels.seal(null, "відкрито")))
        assertEquals(k.id to k.key, Channels.parseLink(Channels.link("https://x", ChannelRec(k.id, false, k.key, k.pub, name = "n"))))
    }

    @Test
    fun withServerAndWebCrypto() {
        val server = System.getenv("SHEPIT_TEST_OPEN_SERVER")
        assumeTrue(server != null)
        val api = Api(server!!)
        val acc = Core.create(api.get("/v0/trust-root").trim())
        val up = acc.keyUploadJson()!!.toByteArray()
        api.send("PUT", "/v0/keys", up, acc.authHeader("PUT /v0/keys", up))

        val k = Channels.newKeys(public = false)
        val rec = ChannelRec(k.id, false, k.key, k.pub, k.priv, "Тест", "опис")
        // Без підпису акаунта новий канал не створюється.
        val denied = runCatching { api.channels.putMeta(rec, Channels.Meta("Тест", "опис")) }.exceptionOrNull()
        assertTrue(denied != null)
        api.channels.putMeta(rec, Channels.Meta("Тест", "опис")) { p, b -> acc.authHeader(p, b) }
        api.channels.publish(rec, "привіт з Android")

        // Підписник без приватного ключа бачить опис і дописи.
        val (sub, meta) = api.channels.fetch(k.id, k.key)!!
        assertEquals("Тест", meta.name)
        assertNull(sub.priv)
        assertNull("з чужим ключем — не відкривається", api.channels.fetch(k.id, Channels.newKeys(false).key))

        // Браузерна сторона (WebCrypto) читає допис Android і публікує свій.
        val script = File(javaClass.classLoader!!.getResource("webcrypto-peer.mjs")!!.toURI())
        val p = ProcessBuilder("node", script.path, server, k.id, k.key, k.pub, k.priv).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        assertEquals(out, 0, p.waitFor())
        val res = JSONObject(out.lines().last())
        assertTrue(res.getBoolean("idOk"))
        assertEquals("привіт з Android", res.getJSONArray("read").getString(0))
        assertEquals("сервер прийняв підпис WebCrypto", 200, res.getInt("posted"))

        val posts = api.channels.posts(sub)
        assertEquals(listOf("привіт з Android", "привіт з браузера"), posts.map { it.text })

        // Публічний канал знаходиться пошуком (без урахування регістру).
        val pk = Channels.newKeys(public = true)
        val prec = ChannelRec(pk.id, true, null, pk.pub, pk.priv, "Відкриті Новини")
        api.channels.putMeta(prec, Channels.Meta("Відкриті Новини", "")) { pp, b -> acc.authHeader(pp, b) }
        assertTrue(api.channels.search("відкриті").any { it.id == pk.id })
        api.channels.deletePost(rec, posts.first().seq)
        assertEquals(1, api.channels.posts(sub).size)
        api.channels.delete(rec)
        assertTrue(runCatching { api.channels.fetch(k.id, k.key) }.isFailure)
    }
}
