package io.github.ya77papa.shepit.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Стан акаунта (секретні ключі й сесії) — у файлі, зашифрованому ключем з Android Keystore.
 * Сам ключ Keystore не можна витягти з телефона (апаратне сховище, де воно є).
 */
class SecureState(context: Context) {
    private val file = File(context.filesDir, "account.bin")
    private val alias = "shepit-account"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun exists() = file.exists()

    fun read(): ByteArray? {
        if (!file.exists()) return null
        val all = file.readBytes()
        val iv = all.copyOfRange(0, 12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return c.doFinal(all, 12, all.size - 12)
    }

    fun write(state: ByteArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val tmp = File(file.parentFile, "account.tmp")
        tmp.writeBytes(c.iv + c.doFinal(state))
        if (!tmp.renameTo(file)) {
            file.delete(); tmp.renameTo(file)
        }
    }

    fun wipe() {
        file.delete()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
    }
}

data class Contact(val identity: String, val name: String, val fingerprint: String)

data class Message(
    val id: Long = 0,
    val peer: String,
    val out: Boolean,
    val text: String,
    val at: Long,
    val kind: String = "text", // text | voice | call
    val voiceMime: String? = null,
    val voiceDur: Long = 0,
    val voice: ByteArray? = null,
    /** Спільний з співрозмовником id (для «видалити у всіх»); у старих повідомлень немає. */
    val mid: String? = null,
)

/** Контакти, історія, дрібні налаштування — локальна SQLite у приватній теці застосунку. */
class Db(context: Context) : SQLiteOpenHelper(context, "shepit.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE contacts(identity TEXT PRIMARY KEY, name TEXT NOT NULL, fingerprint TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT, peer TEXT NOT NULL, out INTEGER NOT NULL, " +
                "text TEXT NOT NULL, at INTEGER NOT NULL, kind TEXT NOT NULL, voice_mime TEXT, voice_dur INTEGER, voice BLOB, mid TEXT)",
        )
        db.execSQL("CREATE INDEX messages_peer ON messages(peer, id)")
        db.execSQL("CREATE TABLE kv(k TEXT PRIMARY KEY, v TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE messages ADD COLUMN mid TEXT")
    }

    fun contacts(): List<Contact> = readableDatabase.rawQuery(
        "SELECT identity, name, fingerprint FROM contacts ORDER BY name COLLATE NOCASE", null,
    ).use { c -> generateSequence { if (c.moveToNext()) Contact(c.getString(0), c.getString(1), c.getString(2)) else null }.toList() }

    fun putContact(c: Contact) {
        writableDatabase.insertWithOnConflict("contacts", null, ContentValues().apply {
            put("identity", c.identity); put("name", c.name); put("fingerprint", c.fingerprint)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun messages(peer: String): List<Message> = readableDatabase.rawQuery(
        "SELECT id, peer, out, text, at, kind, voice_mime, voice_dur, voice, mid FROM messages WHERE peer = ? ORDER BY id", arrayOf(peer),
    ).use { c -> generateSequence { if (c.moveToNext()) row(c) else null }.toList() }

    fun allMessages(): List<Message> = readableDatabase.rawQuery(
        "SELECT id, peer, out, text, at, kind, voice_mime, voice_dur, voice, mid FROM messages ORDER BY id", null,
    ).use { c -> generateSequence { if (c.moveToNext()) row(c) else null }.toList() }

    private fun row(c: android.database.Cursor) = Message(
        c.getLong(0), c.getString(1), c.getInt(2) == 1, c.getString(3), c.getLong(4), c.getString(5),
        c.getString(6), c.getLong(7), if (c.isNull(8)) null else c.getBlob(8), c.getString(9),
    )

    fun addMessage(m: Message): Long = writableDatabase.insert("messages", null, ContentValues().apply {
        put("peer", m.peer); put("out", if (m.out) 1 else 0); put("text", m.text); put("at", m.at); put("kind", m.kind)
        put("voice_mime", m.voiceMime); put("voice_dur", m.voiceDur); put("voice", m.voice); put("mid", m.mid)
    })

    fun deleteMessage(id: Long) {
        writableDatabase.delete("messages", "id = ?", arrayOf(id.toString()))
    }

    /** Видалити повідомлення, які peer надіслав мені, з цими id. Повертає кількість. */
    fun deleteIncoming(peer: String, mids: List<String>): Int {
        if (mids.isEmpty()) return 0
        val marks = mids.joinToString(",") { "?" }
        return writableDatabase.delete("messages", "peer = ? AND out = 0 AND mid IN ($marks)", arrayOf(peer, *mids.toTypedArray()))
    }

    fun clearChat(peer: String) {
        writableDatabase.delete("messages", "peer = ?", arrayOf(peer))
    }

    fun deleteContact(identity: String) {
        writableDatabase.delete("messages", "peer = ?", arrayOf(identity))
        writableDatabase.delete("contacts", "identity = ?", arrayOf(identity))
    }

    fun get(k: String): String? = readableDatabase.rawQuery("SELECT v FROM kv WHERE k = ?", arrayOf(k)).use {
        if (it.moveToNext()) it.getString(0) else null
    }

    fun set(k: String, v: String) {
        writableDatabase.insertWithOnConflict("kv", null, ContentValues().apply { put("k", k); put("v", v) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun wipe() {
        writableDatabase.execSQL("DELETE FROM contacts")
        writableDatabase.execSQL("DELETE FROM messages")
        writableDatabase.execSQL("DELETE FROM kv")
    }
}
