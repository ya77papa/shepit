package io.github.ya77papa.shepit

import io.github.ya77papa.shepit.core.Core
import io.github.ya77papa.shepit.data.Api
import io.github.ya77papa.shepit.data.Body
import io.github.ya77papa.shepit.data.Content
import io.github.ya77papa.shepit.data.HttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Видалення акаунта власником. Відкритий сервер: SHEPIT_TEST_OPEN_SERVER=http://… */
class DeleteAccountTest {
    @Test
    fun formats() {
        assertEquals(Body.Gone, Content.decode(Content.encode(Body.Gone)))
        val g = "ab".repeat(16)
        assertEquals(Body.Dissolve(g), Content.decode("""{"t":"dissolve","g":"$g","v":1}"""))
        assertEquals(Body.Dissolve(Content.INVALID_GROUP), Content.decode("""{"t":"dissolve","g":"bad","v":1}""")) // не збігається з жодною групою
    }

    @Test
    fun deleteOwnAccount() {
        val server = System.getenv("SHEPIT_TEST_OPEN_SERVER")
        assumeTrue(server != null)
        val api = Api(server!!)
        val c = Core.create(api.get("/v0/trust-root").trim())
        val body = c.keyUploadJson()!!.toByteArray()
        api.send("PUT", "/v0/keys", body, c.authHeader("PUT /v0/keys", body))
        val id = c.identity()
        assertNotNull(api.getOrNull("/v0/keys/$id"))
        // Чужий підпис (інший шлях) — відмова.
        val bad = runCatching { api.delete("/v0/account", c.authHeader("DELETE /v0/other", ByteArray(0))) }.exceptionOrNull()
        assertEquals(401, (bad as HttpException).code)
        api.delete("/v0/account", c.authHeader("DELETE /v0/account", ByteArray(0)))
        assertEquals(null, api.getOrNull("/v0/keys/$id"))
        // Після видалення — уже не зареєстрований.
        val after = runCatching { api.post("/v0/presence", """{"online":true}""".toByteArray(), c.authHeader("POST /v0/presence", """{"online":true}""".toByteArray())) }.exceptionOrNull()
        assertNotNull(after)
    }
}
