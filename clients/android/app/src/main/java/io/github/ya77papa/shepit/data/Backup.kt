package io.github.ya77papa.shepit.data

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Зашифрована резервна копія — той самий формат, що у веб-версії:
 * PBKDF2-SHA256 (600 000 ітерацій) → AES-256-GCM. Копію з браузера можна відновити тут і навпаки.
 */
object Backup {
    private const val ITER = 600_000
    const val MIN_PASSWORD = 10

    class Restored(
        val account: ByteArray, val contacts: List<Contact>, val messages: List<Message>, val myName: String,
        val groups: List<Group> = emptyList(), val channels: List<ChannelRec> = emptyList(), val myAvatar: String = "",
    )

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.DEFAULT)

    private fun key(password: String, salt: ByteArray, iter: Int): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val k = f.generateSecret(PBEKeySpec(password.toCharArray(), salt, iter, 256)).encoded
        return SecretKeySpec(k, "AES")
    }

    fun export(account: ByteArray, db: Db, myName: String, password: String): ByteArray {
        require(password.length >= MIN_PASSWORD) { "Пароль має бути щонайменше $MIN_PASSWORD символів" }
        val payload = JSONObject().apply {
            put("account", b64(account))
            put("contacts", JSONArray().apply {
                db.contacts().forEach { c ->
                    put(JSONObject().put("identity", c.identity).put("name", c.name).put("fingerprint", c.fingerprint).apply { c.avatar?.let { put("avatar", it) } })
                }
            })
            put("messages", JSONArray().apply {
                db.allMessages().forEach { m ->
                    put(JSONObject().apply {
                        put("peer", m.peer); put("out", m.out); put("text", m.text); put("at", m.at)
                        if (m.kind != "text") put("kind", m.kind)
                        if (m.mid != null) put("mid", m.mid)
                        if (m.sender != null) put("from", m.sender)
                        if (m.reactions.isNotEmpty()) put("reactions", JSONObject(m.reactions as Map<*, *>))
                        if (m.voice != null && m.kind == "image") {
                            // Той самий формат, що у веб-версії: image {data, mime, w, h}.
                            val b = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            android.graphics.BitmapFactory.decodeByteArray(m.voice, 0, m.voice.size, b)
                            put("image", JSONObject().put("data", b64(m.voice)).put("mime", m.voiceMime ?: "image/jpeg").put("w", b.outWidth).put("h", b.outHeight))
                        } else if (m.voice != null) put("voice", JSONObject().put("data", b64(m.voice)).put("mime", m.voiceMime).put("dur", m.voiceDur))
                    })
                }
            })
            put("myName", myName)
            put("groups", JSONArray().apply { db.groups().forEach { put(it.toJson()) } })
            // Містить ключі моїх каналів — без них писати в канал неможливо.
            put("channels", JSONArray().apply { db.channels().forEach { put(it.toJson()) } })
            put("myAvatar", db.get("myAvatar") ?: "")
            put("exportedAt", System.currentTimeMillis())
        }
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(password, salt, ITER), GCMParameterSpec(128, iv))
        val data = c.doFinal(payload.toString().toByteArray())
        return JSONObject().apply {
            put("shepit", "backup"); put("v", 1); put("kdf", "PBKDF2-SHA256"); put("iter", ITER)
            put("salt", b64(salt)); put("iv", b64(iv)); put("data", b64(data))
        }.toString().toByteArray()
    }

    fun import(file: ByteArray, password: String): Restored {
        val f = runCatching { JSONObject(String(file)) }.getOrElse { error("Це не файл резервної копії Шепоту") }
        if (f.optString("shepit") != "backup" || f.optInt("v") != 1) error("Невідомий формат резервної копії")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(password, unb64(f.getString("salt")), f.getInt("iter")), GCMParameterSpec(128, unb64(f.getString("iv"))))
        val plain = runCatching { c.doFinal(unb64(f.getString("data"))) }.getOrElse { error("Невірний пароль або пошкоджений файл") }
        val p = JSONObject(String(plain))
        val contacts = p.getJSONArray("contacts").let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { Contact(it.getString("identity"), it.getString("name"), it.getString("fingerprint"), Content.avatar(it.opt("avatar"))) }
        }
        val messages = p.getJSONArray("messages").let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                val img = o.optJSONObject("image")
                val v = o.optJSONObject("voice") ?: img
                Message(
                    peer = o.getString("peer"), out = o.getBoolean("out"), text = o.getString("text"), at = o.getLong("at"),
                    kind = o.optString("kind", if (img != null) "image" else if (v != null) "voice" else "text"),
                    voiceMime = v?.optString("mime"), voiceDur = if (img != null) 0 else v?.optLong("dur") ?: 0, voice = v?.let { unb64(it.getString("data")) },
                    mid = o.optString("mid").takeIf { it.length == 32 },
                    sender = o.optString("from").ifEmpty { null },
                    reactions = o.optJSONObject("reactions")?.let { r -> r.keys().asSequence().associateWith { r.getString(it) } } ?: emptyMap(),
                )
            }
        }
        val groups = p.optJSONArray("groups")?.let { a -> (0 until a.length()).mapNotNull { runCatching { Group.fromJson(a.getJSONObject(it)) }.getOrNull() } } ?: emptyList()
        val channels = p.optJSONArray("channels")?.let { a -> (0 until a.length()).mapNotNull { runCatching { ChannelRec.fromJson(a.getJSONObject(it)) }.getOrNull() } } ?: emptyList()
        return Restored(unb64(p.getString("account")), contacts, messages, p.optString("myName"), groups, channels, p.optString("myAvatar"))
    }
}
