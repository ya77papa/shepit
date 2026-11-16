package io.github.ya77papa.shepit

import io.github.ya77papa.shepit.core.Core
import io.github.ya77papa.shepit.core.isValidIdentity
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI

/**
 * Перевірка UniFFI-шару на хост-збірці ядра проти справжнього сервера:
 * два акаунти, публікація ключів, сертифікат, шифрування й розшифрування.
 * Запуск: SHEPIT_TEST_SERVER=https://… ./gradlew testDebugUnitTest
 */
class CoreInteropTest {
    private val server = System.getenv("SHEPIT_TEST_SERVER")

    private fun http(method: String, path: String, body: ByteArray? = null, auth: String? = null): String {
        val c = URI(server + path).toURL().openConnection() as HttpURLConnection
        c.requestMethod = method
        auth?.let { c.setRequestProperty("x-shepit-auth", it) }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("content-type", "application/json")
            c.outputStream.use { it.write(body) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() } ?: ""
        check(code < 300) { "$method $path → $code $text" }
        return text
    }

    private fun account(root: String): Core {
        val c = Core.create(root)
        val keys = c.keyUploadJson()!!.toByteArray()
        http("PUT", "/v0/keys", keys, c.authHeader("PUT /v0/keys", keys))
        c.setCertificate(http("GET", "/v0/certificate", auth = c.authHeader("GET /v0/certificate", ByteArray(0))))
        assertTrue(!c.needsCertificate())
        return c
    }

    @Test
    fun roundTripThroughServer() {
        assumeTrue("SHEPIT_TEST_SERVER не задано", server != null)
        val root = http("GET", "/v0/trust-root").trim()
        val a = account(root)
        val b = account(root)
        assertTrue(isValidIdentity(a.identity()))

        a.startSession(http("GET", "/v0/keys/${b.identity()}"))
        val env = a.sealEnvelope(b.identity(), """{"v":1,"t":"text","text":"Привіт з Android"}""", null)
        http("POST", "/v0/send", env.toByteArray())

        val mb = b.mailbox()
        val list = http("GET", "/v0/mailbox/$mb", auth = b.authHeader("GET /v0/mailbox/$mb", ByteArray(0)))
        val envelope = org.json.JSONArray(list).getJSONObject(0).getJSONObject("envelope").toString()
        val d = b.openEnvelope(envelope)
        assertEquals(a.identity(), d.from)
        assertTrue(d.text.contains("Привіт з Android"))
        assertEquals(a.safetyNumber(b.identity()), b.safetyNumber(a.identity()))

        // відповідь у зворотний бік — сесія вже є у B
        val back = b.sealEnvelope(a.identity(), """{"v":1,"t":"text","text":"Відповідь"}""", "call")
        http("POST", "/v0/send", back.toByteArray())
        val mbA = a.mailbox()
        val l2 = http("GET", "/v0/mailbox/$mbA", auth = a.authHeader("GET /v0/mailbox/$mbA", ByteArray(0)))
        val d2 = a.openEnvelope(org.json.JSONArray(l2).getJSONObject(0).getJSONObject("envelope").toString())
        assertEquals("Відповідь", org.json.JSONObject(d2.text).getString("text"))

        // стан переживає експорт/завантаження
        val a2 = Core.load(a.export())
        assertEquals(a.identity(), a2.identity())
    }

    /** Потік подій так, як його відкриває застосунок (OkHttp), + облікові дані TURN. */
    @Test
    fun eventsStreamAndTurn() {
        assumeTrue("SHEPIT_TEST_SERVER не задано", server != null)
        val api = io.github.ya77papa.shepit.data.Api(server!!)
        val root = api.get("/v0/trust-root").trim()
        val a = account(root)
        val b = account(root)
        val turn = api.get("/v0/turn", b.authHeader("GET /v0/turn", ByteArray(0)))
        println("TURN: " + turn.take(200))
        val got = java.util.concurrent.CompletableFuture<String>()
        val t = Thread {
            runCatching {
                val req = okhttp3.Request.Builder().url(api.url("/v0/events"))
                    .header(io.github.ya77papa.shepit.data.Api.AUTH, b.authHeader("GET /v0/events", ByteArray(0))).build()
                api.streaming.newCall(req).execute().use { r ->
                    println("events: HTTP ${r.code} ${r.protocol}")
                    val src = r.body.source()
                    while (true) {
                        val line = src.readUtf8Line() ?: break
                        println("events line: [$line]")
                        if (line.startsWith("data")) { got.complete(line); break }
                    }
                }
            }.onFailure { got.completeExceptionally(it) }
        }
        t.isDaemon = true; t.start()
        Thread.sleep(1500)
        a.startSession(http("GET", "/v0/keys/${b.identity()}"))
        http("POST", "/v0/send", a.sealEnvelope(b.identity(), """{"v":1,"t":"call","op":"offer","id":"x","at":1}""", "call").toByteArray())
        assertEquals("data: mail", got.get(15, java.util.concurrent.TimeUnit.SECONDS))
    }
}
