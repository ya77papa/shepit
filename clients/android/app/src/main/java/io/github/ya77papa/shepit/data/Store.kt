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

/** avatar — JPEG у base64 (з профілю, який надіслав сам контакт). */
data class Contact(val identity: String, val name: String, val fingerprint: String, val avatar: String? = null)

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
    /** У групі (peer = "g:<id>"): хто надіслав. */
    val sender: String? = null,
)

/** Контакти, історія, дрібні налаштування — локальна SQLite у приватній теці застосунку. */
class Db(context: Context) : SQLiteOpenHelper(context, "shepit.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE contacts(identity TEXT PRIMARY KEY, name TEXT NOT NULL, fingerprint TEXT NOT NULL, avatar TEXT)")
        db.execSQL(
            "CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT, peer TEXT NOT NULL, out INTEGER NOT NULL, " +
                "text TEXT NOT NULL, at INTEGER NOT NULL, kind TEXT NOT NULL, voice_mime TEXT, voice_dur INTEGER, voice BLOB, mid TEXT, sender TEXT)",
        )
        db.execSQL("CREATE INDEX messages_peer ON messages(peer, id)")
        db.execSQL("CREATE TABLE kv(k TEXT PRIMARY KEY, v TEXT)")
        createV3(db)
    }

    /** Групи й канали зберігаються як JSON — той самий формат, що у веб-версії й резервній копії. */
    private fun createV3(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS groups(id TEXT PRIMARY KEY, json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS channels(id TEXT PRIMARY KEY, json TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE messages ADD COLUMN mid TEXT")
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE contacts ADD COLUMN avatar TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN sender TEXT")
            createV3(db)
        }
    }

    fun contacts(): List<Contact> = readableDatabase.rawQuery(
        "SELECT identity, name, fingerprint, avatar FROM contacts ORDER BY name COLLATE NOCASE", null,
    ).use { c -> generateSequence { if (c.moveToNext()) Contact(c.getString(0), c.getString(1), c.getString(2), c.getString(3)) else null }.toList() }

    fun putContact(c: Contact) {
        writableDatabase.insertWithOnConflict("contacts", null, ContentValues().apply {
            put("identity", c.identity); put("name", c.name); put("fingerprint", c.fingerprint); put("avatar", c.avatar)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun messages(peer: String): List<Message> = readableDatabase.rawQuery(
        "SELECT id, peer, out, text, at, kind, voice_mime, voice_dur, voice, mid, sender FROM messages WHERE peer = ? ORDER BY id", arrayOf(peer),
    ).use { c -> generateSequence { if (c.moveToNext()) row(c) else null }.toList() }

    fun allMessages(): List<Message> = readableDatabase.rawQuery(
        "SELECT id, peer, out, text, at, kind, voice_mime, voice_dur, voice, mid, sender FROM messages ORDER BY id", null,
    ).use { c -> generateSequence { if (c.moveToNext()) row(c) else null }.toList() }

    private fun row(c: android.database.Cursor) = Message(
        c.getLong(0), c.getString(1), c.getInt(2) == 1, c.getString(3), c.getLong(4), c.getString(5),
        c.getString(6), c.getLong(7), if (c.isNull(8)) null else c.getBlob(8), c.getString(9), c.getString(10),
    )

    fun addMessage(m: Message): Long = writableDatabase.insert("messages", null, ContentValues().apply {
        put("peer", m.peer); put("out", if (m.out) 1 else 0); put("text", m.text); put("at", m.at); put("kind", m.kind)
        put("voice_mime", m.voiceMime); put("voice_dur", m.voiceDur); put("voice", m.voice); put("mid", m.mid); put("sender", m.sender)
    })

    fun deleteMessage(id: Long) {
        writableDatabase.delete("messages", "id = ?", arrayOf(id.toString()))
    }

    /** Видалити повідомлення, які peer надіслав мені, з цими id (у групі — лише від sender). */
    fun deleteIncoming(peer: String, mids: List<String>, sender: String? = null): Int {
        if (mids.isEmpty()) return 0
        val marks = mids.joinToString(",") { "?" }
        return if (sender == null) writableDatabase.delete("messages", "peer = ? AND out = 0 AND mid IN ($marks)", arrayOf(peer, *mids.toTypedArray()))
        else writableDatabase.delete("messages", "peer = ? AND out = 0 AND sender = ? AND mid IN ($marks)", arrayOf(peer, sender, *mids.toTypedArray()))
    }

    // ---------- групи й канали (JSON) ----------

    private fun jsonRows(table: String): List<String> =
        readableDatabase.rawQuery("SELECT json FROM $table", null).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }

    private fun jsonRow(table: String, id: String): String? =
        readableDatabase.rawQuery("SELECT json FROM $table WHERE id = ?", arrayOf(id)).use { if (it.moveToNext()) it.getString(0) else null }

    private fun putJson(table: String, id: String, json: String) {
        writableDatabase.insertWithOnConflict(table, null, ContentValues().apply { put("id", id); put("json", json) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun groups(): List<Group> = jsonRows("groups").mapNotNull { Group.fromJson(it) }.sortedBy { it.name.lowercase() }
    fun group(id: String): Group? = jsonRow("groups", id)?.let { Group.fromJson(it) }
    fun putGroup(g: Group) = putJson("groups", g.id, g.toJson().toString())
    fun deleteGroup(id: String) {
        writableDatabase.delete("groups", "id = ?", arrayOf(id))
        writableDatabase.delete("messages", "peer = ?", arrayOf("g:$id"))
    }

    fun channels(): List<ChannelRec> = jsonRows("channels").mapNotNull { ChannelRec.fromJson(it) }.sortedBy { it.name.lowercase() }
    fun channel(id: String): ChannelRec? = jsonRow("channels", id)?.let { ChannelRec.fromJson(it) }
    fun putChannel(c: ChannelRec) = putJson("channels", c.id, c.toJson().toString())
    fun deleteChannel(id: String) {
        writableDatabase.delete("channels", "id = ?", arrayOf(id))
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
        writableDatabase.execSQL("DELETE FROM groups")
        writableDatabase.execSQL("DELETE FROM channels")
    }
}
