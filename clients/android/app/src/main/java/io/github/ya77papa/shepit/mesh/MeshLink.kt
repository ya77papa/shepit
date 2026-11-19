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

/** Зашифрований конверт. Хто кому — видно лише з [tag], який можуть порахувати двоє. */
class MeshEnvelope(val tag: ByteArray, val expires: Long, val body: ByteArray) {
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

    /** Дні, теги яких приймаємо: учора, сьогодні, завтра (годинники можуть розходитись). */
    fun window(now: Long) = (day(now) - 1)..(day(now) + 1)
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
}

/**
 * Обмін між двома телефонами. Обидва боки роблять те саме:
 * HELLO+OFFER (що в мене є) → WANT (що з твого для мене) → ENV… DONE (самі конверти) → ACK.
 * Конверти першим шле той, хто підключився, — щоб обидва не писали великі дані одночасно.
 */
class MeshLink(private val node: MeshNode) {
    class Stats(var sent: Int = 0, var received: Int = 0, var delivered: Int = 0)

    fun run(input: InputStream, output: OutputStream, initiator: Boolean): Stats {
        val inp = DataInputStream(input.buffered())
        val out = DataOutputStream(output.buffered())
        val stats = Stats()
        val mine = node.offers().filter { it.expires > System.currentTimeMillis() }.take(MAX_OFFERS).associateBy { it.id }

        write(out, JSONObject().put("t", "hello").put("v", 1))
        write(out, JSONObject().put("t", "offer").put("items", JSONArray(mine.values.map { JSONObject().put("id", it.id).put("tag", b64(it.tag)) })))
        out.flush()

        expect(read(inp), "hello")
        val offer = expect(read(inp), "offer").getJSONArray("items")
        val want = (0 until minOf(offer.length(), MAX_OFFERS)).map { offer.getJSONObject(it) }
            .filter { node.isForMe(unb64(it.getString("tag"))) }
            .map { it.getString("id") }
        write(out, JSONObject().put("t", "want").put("ids", JSONArray(want)))
        out.flush()
        val theyWant = expect(read(inp), "want").getJSONArray("ids").let { a -> (0 until a.length()).map { a.getString(it) } }

        fun sendEnvelopes() {
            for (id in theyWant) {
                val e = mine[id] ?: continue
                write(out, JSONObject().put("t", "env").put("tag", b64(e.tag)).put("exp", e.expires).put("body", b64(e.body)))
                stats.sent++
            }
            write(out, JSONObject().put("t", "done"))
            out.flush()
        }
        val acks = mutableListOf<String>()
        fun receiveEnvelopes() {
            val wanted = want.toSet()
            while (true) {
                val f = read(inp)
                if (f.getString("t") == "done") break
                expect(f, "env")
                val e = MeshEnvelope(unb64(f.getString("tag")), f.getLong("exp"), unb64(f.getString("body")))
                // Лише те, що просили, і лише справжнє (відбиток збігається).
                if (e.id !in wanted || !node.isForMe(e.tag)) continue
                stats.received++
                if (node.receive(e)) acks += e.id
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
        /** Найбільший кадр: фото (~600 КБ) у base64 із запасом. */
        const val MAX_FRAME = 2 * 1024 * 1024
        const val MAX_OFFERS = 500
    }
}

class MeshProtocolException(msg: String) : java.io.IOException(msg)
