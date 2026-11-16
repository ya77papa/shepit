package io.github.ya77papa.shepit

import io.github.ya77papa.shepit.core.Core
import io.github.ya77papa.shepit.data.Api
import io.github.ya77papa.shepit.data.HttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Реєстрація за запрошенням. Сервер у режимі invite: SHEPIT_TEST_INVITE_SERVER=http://… */
class InviteTest {
    @Test
    fun codes() {
        assertEquals("ABCD-EFGH-JKMN", Api.normalizeInviteCode(" abcd efgh-jkmn "))
        assertNull(Api.normalizeInviteCode("ABCD-EFGH-JKM0"))
        assertNull(Api.normalizeInviteCode(null))
    }

    @Test
    fun refusedWithoutInvite() {
        val server = System.getenv("SHEPIT_TEST_INVITE_SERVER")
        assumeTrue(server != null)
        val api = Api(server!!)
        val c = Core.create(api.get("/v0/trust-root").trim())
        val body = c.keyUploadJson()!!.toByteArray()
        val e1 = runCatching { api.send("PUT", "/v0/keys", body, c.authHeader("PUT /v0/keys", body)) }.exceptionOrNull() as HttpException
        assertEquals(Api.REFUSALS["invite-required"], e1.message)
        val e2 = runCatching {
            api.send("PUT", "/v0/keys", body, c.authHeader("PUT /v0/keys", body), mapOf(Api.INVITE to "ABCD-EFGH-JKMN"))
        }.exceptionOrNull() as HttpException
        assertEquals(Api.REFUSALS["invite-invalid"], e2.message)
    }
}
