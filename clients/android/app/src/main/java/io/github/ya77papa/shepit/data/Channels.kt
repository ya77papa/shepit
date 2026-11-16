package io.github.ya77papa.shepit.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Канали: один пише — багато читають. Формат той самий, що у веб-версії (src/lib/channels.ts)
 * і на сервері (server/src/channels.rs): ключ підпису ECDSA P-256 (точка «raw» 65 байтів,
 * підпис r||s 64 байти), шифрування AES-256-GCM (iv||шифротекст, base64url).
 */
object Channels {
    data class Meta(val name: String, val about: String, val avatar: String? = null)
    data class Post(val seq: Long, val at: Long, val text: String?)
    data class Keys(val id: String, val pub: String, val priv: String, val key: String?)

    private val rnd = SecureRandom()
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    fun b64u(b: ByteArray): String = enc.encodeToString(b)
    fun unb64u(s: String): ByteArray = dec.decode(s)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    fun channelId(pubRaw: ByteArray) = hex(sha256(pubRaw)).take(32)

    private val P256: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
    }

    private fun fixed32(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    fun newKeys(public: Boolean): Keys {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), rnd) }.generateKeyPair()
        val p = kp.public as ECPublicKey
        val raw = byteArrayOf(4) + fixed32(p.w.affineX) + fixed32(p.w.affineY)
        val d = fixed32((kp.private as ECPrivateKey).s)
        return Keys(channelId(raw), b64u(raw), b64u(d), if (public) null else b64u(ByteArray(32).also { rnd.nextBytes(it) }))
    }

    /** DER (Java) → r||s (WebCrypto / сервер). */
    private fun derToRaw(der: ByteArray): ByteArray {
        var i = 2
        if (der[1].toInt() and 0x80 != 0) i += der[1].toInt() and 0x7f
        fun int(): BigInteger {
            require(der[i].toInt() == 0x02); val len = der[i + 1].toInt(); val v = BigInteger(1, der.copyOfRange(i + 2, i + 2 + len)); i += 2 + len; return v
        }
        return fixed32(int()) + fixed32(int())
    }

    /** Заголовок підпису ключем каналу: «час.nonce.підпис». */
    fun auth(c: ChannelRec, method: String, path: String, body: ByteArray, now: Long = System.currentTimeMillis()): String {
        val nonce = b64u(ByteArray(12).also { rnd.nextBytes(it) })
        val msg = "$method $path\n$now\n$nonce\n${hex(sha256(body))}"
        val key = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, unb64u(c.priv!!)), P256))
        val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(msg.toByteArray()) }.sign()
        return "$now.$nonce.${b64u(derToRaw(sig))}"
    }

    fun seal(key: String?, plain: String): String {
        if (key == null) return plain
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(unb64u(key), "AES"), GCMParameterSpec(128, iv)) }
        return b64u(iv + c.doFinal(plain.toByteArray()))
    }

    fun open(key: String?, data: String): String? {
        if (key == null) return data
        return runCatching {
            val raw = unb64u(data)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(unb64u(key), "AES"), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            }
            String(c.doFinal(raw.copyOfRange(12, raw.size)))
        }.getOrNull()
    }

    fun parseMeta(json: String?): Meta? = runCatching {
        val o = JSONObject(json!!)
        Meta(o.getString("name").take(80), o.optString("about").take(500), Content.avatar(o.opt("avatar")))
    }.getOrNull()

    fun metaJson(m: Meta) = JSONObject().apply { put("name", m.name); put("about", m.about); m.avatar?.let { put("avatar", it) } }.toString()

    private fun parsePost(json: String?): String? = runCatching {
        val o = JSONObject(json!!)
        if (o.optInt("v") == 1 && o.optString("t") == "text") o.getString("text") else null
    }.getOrNull()

    /** Посилання на канал: для зашифрованого — з ключем після «#». */
    fun link(base: String, c: ChannelRec) = "$base/#c=${c.id}" + (c.key?.let { "&k=$it" } ?: "")

    fun parseLink(text: String): Pair<String, String?>? {
        val p = text.trim().substringAfter('#', "").split('&').mapNotNull { it.split('=', limit = 2).takeIf { x -> x.size == 2 }?.let { x -> x[0] to x[1] } }.toMap()
        val id = p["c"]?.takeIf { Regex("^[0-9a-f]{32}$").matches(it) } ?: return null
        val key = p["k"]
        if (key != null && runCatching { unb64u(key).size }.getOrNull() != 32) return null
        return id to key
    }

    // ---------- сервер ----------

    class Client(private val base: String, private val http: OkHttpClient) {
        private val json = "application/json".toMediaType()

        private fun call(req: Request): String = http.newCall(req).execute().use { r ->
            if (r.code == 404) throw HttpException(404, "Канал не знайдено — можливо, його видалено")
            if (r.code == 403) throw HttpException(403, "Створювати канали можуть лише зареєстровані користувачі")
            if (r.code == 413) throw HttpException(413, "Допис завеликий")
            if (!r.isSuccessful) throw HttpException(r.code, "канал: ${r.code}")
            r.body.string()
        }

        /** Дані каналу з сервера: (запис, метадані) або null, якщо ключ не той. */
        fun fetch(id: String, key: String?): Pair<ChannelRec, Meta>? {
            val o = JSONObject(call(Request.Builder().url("$base/v0/channels/$id").build()))
            val pub = o.getString("pub")
            if (channelId(unb64u(pub)) != id) return null
            val public = o.optBoolean("public")
            if (!public && key == null) return null
            val k = if (public) null else key
            val meta = parseMeta(open(k, o.getString("meta"))) ?: return null
            val last = o.optLong("last_seq")
            return ChannelRec(id, public, k, pub, null, meta.name, meta.about, meta.avatar, last, last) to meta
        }

        fun lastSeq(id: String): Long? = runCatching { JSONObject(call(Request.Builder().url("$base/v0/channels/$id").build())).optLong("last_seq") }.getOrNull()

        /** Опублікувати опис. Для нового каналу потрібен ще підпис акаунта. */
        fun putMeta(c: ChannelRec, m: Meta, accountAuth: ((String, ByteArray) -> String)? = null) {
            val path = "/v0/channels/${c.id}"
            val body = JSONObject().put("pub", c.pub).put("public", c.public).put("meta", seal(c.key, metaJson(m))).toString().toByteArray()
            val req = Request.Builder().url(base + path).put(body.toRequestBody(json)).header("x-shepit-channel", auth(c, "PUT", path, body))
            if (accountAuth != null) req.header(Api.AUTH, accountAuth("PUT $path", body))
            call(req.build())
        }

        fun publish(c: ChannelRec, text: String): Long {
            val path = "/v0/channels/${c.id}/posts"
            val post = JSONObject().put("v", 1).put("t", "text").put("text", text).toString()
            val body = JSONObject().put("data", seal(c.key, post)).toString().toByteArray()
            return JSONObject(call(Request.Builder().url(base + path).post(body.toRequestBody(json)).header("x-shepit-channel", auth(c, "POST", path, body)).build())).getLong("seq")
        }

        fun deletePost(c: ChannelRec, seq: Long) {
            val path = "/v0/channels/${c.id}/posts/$seq"
            runCatching { call(Request.Builder().url(base + path).delete().header("x-shepit-channel", auth(c, "DELETE", path, ByteArray(0))).build()) }
                .onFailure { if ((it as? HttpException)?.code != 404) throw it }
        }

        fun delete(c: ChannelRec) {
            val path = "/v0/channels/${c.id}"
            runCatching { call(Request.Builder().url(base + path).delete().header("x-shepit-channel", auth(c, "DELETE", path, ByteArray(0))).build()) }
                .onFailure { if ((it as? HttpException)?.code != 404) throw it }
        }

        /** Дописи (за зростанням): після [after] або останні перед [before]. */
        fun posts(c: ChannelRec, after: Long? = null, before: Long? = null, limit: Int = 50): List<Post> {
            val q = buildString {
                append("limit=$limit")
                after?.let { append("&after=$it") }
                before?.let { append("&before=$it") }
            }
            val a = JSONArray(call(Request.Builder().url("$base/v0/channels/${c.id}/posts?$q").build()))
            return (0 until a.length()).map { a.getJSONObject(it) }.map { Post(it.getLong("seq"), it.getLong("at"), parsePost(open(c.key, it.getString("data")))) }
        }

        data class Found(val id: String, val meta: Meta, val posts: Long)

        fun search(q: String): List<Found> {
            val a = JSONArray(call(Request.Builder().url("$base/v0/channels?q=${java.net.URLEncoder.encode(q, "UTF-8")}").build()))
            return (0 until a.length()).map { a.getJSONObject(it) }.mapNotNull { o -> parseMeta(o.getString("meta"))?.let { Found(o.getString("id"), it, o.optLong("posts")) } }
        }
    }
}
