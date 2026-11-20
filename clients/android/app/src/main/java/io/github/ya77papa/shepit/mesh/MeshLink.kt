package io.github.ya77papa.shepit.mesh

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Робота без інтернету (docs/mesh-concept.md): конверти, теги й обмін між двома телефонами.
 * Тут лише логіка — без Android, щоб її можна було перевірити тестами. Транспорти (локальна
 * Wi-Fi мережа, Bluetooth) дають лише потік байтів і викликають [MeshLink.run].
 */

/**
 * Зашифрований конверт. Хто кому — видно лише з [tag], який можуть порахувати двоє.
 * [gw] — той самий конверт разом зі скринькою адресата, зашифровані ключем сервера:
 * будь-який телефон з інтернетом вивантажить його на сервер (шлюз), не знаючи ні адресата, ні вмісту.
 */
class MeshEnvelope(val tag: ByteArray, val expires: Long, val body: ByteArray, val gw: ByteArray? = null) {
    val size get() = body.size + (gw?.size ?: 0)

    /** Відбиток: щоб не приймати й не зберігати той самий конверт двічі. */
    val id: String by lazy {
        val d = MessageDigest.getInstance("SHA-256")
        d.update(tag); d.update(expires.toString().toByteArray()); d.update(body)
        d.digest().joinToString("") { "%02x".format(it) }.take(32)
    }
}

object MeshTags {
    const val DAY_MS = 86_400_000L
    /** Конверт живе тиждень; далі його ніхто не несе. */
    const val TTL_MS = 7 * DAY_MS

    fun day(now: Long) = Math.floorDiv(now, DAY_MS)

    /**
     * Тег «від [from] до іншого з пари» на добу [day]: HMAC спільного секрету пари.
     * Щодня новий і різний у кожному напрямку — сторонні не зв'яжуть конверти між собою.
     */
    fun tag(secret: ByteArray, from: String, day: Long): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal("shepit-mesh-tag|$from|$day".toByteArray()).copyOf(16)
    }

    /**
     * Дні, теги яких приймаємо: тиждень назад (конверт міг довго їхати в чужій кишені)
     * і завтра (годинники можуть розходитись).
     */
    fun window(now: Long) = (day(now) - 7)..(day(now) + 1)

    /** Ознака «я твій контакт» у привітанні: HMAC спільного секрету пари з nonce співрозмовника. */
    fun hello(secret: ByteArray, peerNonce: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        mac.update("shepit-mesh-hello|".toByteArray())
        return mac.doFinal(peerNonce).copyOf(8)
    }
}

/** Що обмінові потрібно від застосунку. */
interface MeshNode {
    /** Мої конверти, які ще треба доставити. */
    fun offers(): List<MeshEnvelope>
    /** Чи цей тег адресований мені (від когось із моїх контактів). */
    fun isForMe(tag: ByteArray): Boolean
    /** Отримано конверт для мене. true — обробили (можна підтвердити, щоб відправник забув). */
    fun receive(env: MeshEnvelope): Boolean
    /** Отримувач підтвердив ці конверти. */
    fun delivered(ids: List<String>)

    /** Мої ознаки для привітання (по одній на кожен контакт), див. [MeshTags.hello]. */
    fun helloTokens(peerNonce: ByteArray): List<ByteArray> = emptyList()
    /** Чи співрозмовник — мій контакт (одна з його ознак збігається з моєю для [myNonce]). */
    fun isContact(myNonce: ByteArray, tokens: List<ByteArray>): Boolean = false
    /** Чи брати чужі конверти від цього телефона (налаштування «Передавати чужі повідомлення»). */
    fun carries(fromContact: Boolean): Boolean = false
    /** Конверт з таким відбитком у мене вже є (свій або вже несу). */
    fun has(id: String): Boolean = false
    /** Чи є місце ще для [size] байтів чужих конвертів. */
    fun room(size: Int): Boolean = false
    /** Узяти чужий конверт, щоб нести далі. */
    fun carry(env: MeshEnvelope) {}
}

/**
 * Обмін між двома телефонами. Обидва боки роблять те саме:
 * HELLO (nonce) → OFFER (що в мене є + ознаки «я твій контакт») → WANT (що з твого для мене
 * або що понесу далі) → ENV… DONE (самі конверти) → ACK (лише за доставлені мені).
 * Конверти першим шле той, хто підключився, — щоб обидва не писали великі дані одночасно.
 */
class MeshLink(private val node: MeshNode) {
    class Stats(var sent: Int = 0, var received: Int = 0, var delivered: Int = 0, var carried: Int = 0)

    fun run(input: InputStream, output: OutputStream, initiator: Boolean): Stats {
        val inp = DataInputStream(input.buffered())
        val out = DataOutputStream(output.buffered())
        val stats = Stats()
        val mine = node.offers().filter { it.expires > System.currentTimeMillis() }.take(MAX_OFFERS).associateBy { it.id }
        val myNonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }

        // Спершу лише привітання з nonce: ознаки «я твій контакт» рахуються з nonce співрозмовника.
        write(out, JSONObject().put("t", "hello").put("v", 2).put("n", b64(myNonce)))
        out.flush()
        val hello = expect(read(inp), "hello")
        val peerNonce = hello.optString("n").takeIf { it.isNotEmpty() }?.let { runCatching { unb64(it) }.getOrNull() }
        val tokens = if (peerNonce != null) node.helloTokens(peerNonce).take(MAX_TOKENS) else emptyList()
        write(out, JSONObject().put("t", "offer").put("auth", JSONArray(tokens.map { b64(it) }))
            .put("items", JSONArray(mine.values.map { JSONObject().put("id", it.id).put("tag", b64(it.tag)).put("exp", it.expires).put("n", it.size) })))
        out.flush()

        val offerFrame = expect(read(inp), "offer")
        val theirTokens = offerFrame.optJSONArray("auth")?.let { a -> (0 until minOf(a.length(), MAX_TOKENS)).mapNotNull { runCatching { unb64(a.getString(it)) }.getOrNull() } }.orEmpty()
        val fromContact = node.isContact(myNonce, theirTokens)
        val carry = node.carries(fromContact)
        val offer = offerFrame.getJSONArray("items")
        val forMe = mutableListOf<String>()
        val toCarry = mutableListOf<String>()
        var reserved = 0
        for (it in (0 until minOf(offer.length(), MAX_OFFERS)).map { offer.getJSONObject(it) }) {
            val id = it.getString("id")
            when {
                node.isForMe(unb64(it.getString("tag"))) -> forMe += id
                // Чуже: беремо, якщо дозволено, ще не маємо, не прострочене й є місце.
                carry && id !in mine && !node.has(id) && it.optLong("exp", Long.MAX_VALUE) > System.currentTimeMillis() &&
                    node.room(reserved + it.optInt("n", MAX_FRAME)) -> { toCarry += id; reserved += it.optInt("n", MAX_FRAME) }
            }
        }
        val want = forMe + toCarry
        write(out, JSONObject().put("t", "want").put("ids", JSONArray(want)))
        out.flush()
        val theyWant = expect(read(inp), "want").getJSONArray("ids").let { a -> (0 until a.length()).map { a.getString(it) } }

        fun sendEnvelopes() {
            for (id in theyWant) {
                val e = mine[id] ?: continue
                val f = JSONObject().put("t", "env").put("tag", b64(e.tag)).put("exp", e.expires).put("body", b64(e.body))
                e.gw?.let { f.put("gw", b64(it)) }
                write(out, f)
                stats.sent++
            }
            write(out, JSONObject().put("t", "done"))
            out.flush()
        }
        val acks = mutableListOf<String>()
        fun receiveEnvelopes() {
            val mineWanted = forMe.toSet()
            val carryWanted = toCarry.toSet()
            while (true) {
                val f = read(inp)
                if (f.getString("t") == "done") break
                expect(f, "env")
                val gw = f.optString("gw").takeIf { it.isNotEmpty() }?.let { unb64(it) }
                val e = MeshEnvelope(unb64(f.getString("tag")), f.getLong("exp"), unb64(f.getString("body")), gw)
                // Лише те, що просили, і лише справжнє (відбиток збігається).
                when {
                    e.id in mineWanted && node.isForMe(e.tag) -> {
                        stats.received++
                        if (node.receive(e)) acks += e.id
                    }
                    e.id in carryWanted -> { node.carry(e); stats.carried++ }
                }
            }
        }
        if (initiator) { sendEnvelopes(); receiveEnvelopes() } else { receiveEnvelopes(); sendEnvelopes() }

        write(out, JSONObject().put("t", "ack").put("ids", JSONArray(acks)))
        out.flush()
        val delivered = expect(read(inp), "ack").getJSONArray("ids").let { a -> (0 until a.length()).map { a.getString(it) } }
            .filter { it in theyWant && it in mine }
        if (delivered.isNotEmpty()) node.delivered(delivered)
        stats.delivered = delivered.size
        return stats
    }

    private fun write(out: DataOutputStream, f: JSONObject) {
        val b = f.toString().toByteArray()
        out.writeInt(b.size)
        out.write(b)
    }

    private fun read(inp: DataInputStream): JSONObject {
        val n = inp.readInt()
        if (n !in 1..MAX_FRAME) throw MeshProtocolException("розмір кадру $n")
        val b = ByteArray(n)
        inp.readFully(b)
        return JSONObject(String(b))
    }

    private fun expect(f: JSONObject, t: String): JSONObject {
        if (f.optString("t") != t) throw MeshProtocolException("очікував $t, отримав ${f.optString("t")}")
        return f
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = Base64.getDecoder().decode(s)

    companion object {
        /** Найбільший кадр: фото (~600 КБ) двічі (сам конверт і пакет шлюзу) у base64 із запасом. */
        const val MAX_FRAME = 4 * 1024 * 1024
        const val MAX_OFFERS = 500
        /** Ознак у привітанні не більше, ніж контактів у розумної людини. */
        const val MAX_TOKENS = 1000
    }
}

class MeshProtocolException(msg: String) : java.io.IOException(msg)
