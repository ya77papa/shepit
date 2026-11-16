package io.github.ya77papa.shepit.data

import android.content.Context
import android.util.Base64
import io.github.ya77papa.shepit.core.Core
import io.github.ya77papa.shepit.core.fingerprintOf
import io.github.ya77papa.shepit.core.isValidIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Усе про акаунт: ядро, сервер, локальна база. Операції з ядром — строго по черзі (м'ютекс),
 * бо кожне повідомлення змінює стан сесій.
 */
class Repo(context: Context) {
    val api = Api()
    val db = Db(context)
    private val secure = SecureState(context)
    private val lock = Mutex()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile var core: Core? = null
        private set

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts = _contacts.asStateFlow()

    /** Сповіщення UI: змінилась розмова з цим контактом. */
    private val _changed = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val changed = _changed.asSharedFlow()

    /** Службові повідомлення дзвінків для CallManager. */
    private val _callSignals = MutableSharedFlow<Pair<String, Body.Call>>(extraBufferCapacity = 16)
    val callSignals = _callSignals.asSharedFlow()

    /** Нові вхідні (для сповіщень, коли застосунок не на екрані). */
    private val _incoming = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    val incoming = _incoming.asSharedFlow()

    val connected = MutableStateFlow(false)

    var myName: String
        get() = db.get("myName") ?: ""
        set(v) = db.set("myName", v.trim().take(60))

    fun hasAccount() = secure.exists()

    suspend fun load(): Boolean = withContext(Dispatchers.IO) {
        if (core != null) return@withContext true
        val st = secure.read() ?: return@withContext false
        core = Core.load(st)
        refreshContacts()
        true
    }

    private fun save() = secure.write(core!!.export())

    fun refreshContacts() {
        _contacts.value = db.contacts()
    }

    fun short(fp: String) = fp.split(" ").take(2).joinToString(" ")

    // ---------- реєстрація / ключі ----------

    suspend fun register(name: String, invite: String? = null) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (secure.exists()) return@withLock
            val root = api.get("/v0/trust-root").trim()
            val c = Core.create(root)
            // Якщо сервер відмовить (закрито / немає запрошення) — нічого не збережеться.
            val body = c.keyUploadJson()!!.toByteArray()
            api.send("PUT", "/v0/keys", body, c.authHeader("PUT /v0/keys", body), invite?.let { mapOf(Api.INVITE to it) } ?: emptyMap())
            core = c
            ensureCertificate()
            save()
            myName = name
        }
    }

    /** Режим реєстрації на сервері: open / invite / closed. */
    suspend fun registrationMode(): String = withContext(Dispatchers.IO) {
        runCatching { JSONObject(api.get("/v0/registration")).optString("mode", "open") }.getOrDefault("open")
    }

    data class MyInvite(val code: String?, val mode: String, val left: Int)

    /** Мій код запрошення (сервер «за запрошенням»): той самий, поки ним не скористались. */
    suspend fun myInvite(): MyInvite? = withContext(Dispatchers.IO) {
        val c = core ?: return@withContext null
        runCatching {
            val j = JSONObject(api.post("/v0/invites/mine", ByteArray(0), c.authHeader("POST /v0/invites/mine", ByteArray(0))))
            MyInvite(j.optString("code").takeIf { !j.isNull("code") && it.isNotEmpty() }, j.optString("mode", "open"), j.optInt("left"))
        }.getOrNull()
    }

    private fun publishKeys(json: String) {
        val body = json.toByteArray()
        api.send("PUT", "/v0/keys", body, core!!.authHeader("PUT /v0/keys", body))
    }

    private fun ensureCertificate() {
        val c = core!!
        if (!c.needsCertificate()) return
        val cert = api.get("/v0/certificate", c.authHeader("GET /v0/certificate", ByteArray(0)))
        c.setCertificate(cert)
    }

    /** Сервер має мої ключі і їх вистачає; інакше — опублікувати нові. */
    suspend fun ensureKeys() = withContext(Dispatchers.IO) {
        lock.withLock {
            val c = core ?: return@withLock
            val st = api.getOrNull("/v0/keys/status", c.authHeader("GET /v0/keys/status", ByteArray(0)))
            if (st != null && JSONObject(st).optInt("one_time") >= 5) return@withLock
            val up = c.refreshKeysJson()
            save()
            publishKeys(up)
        }
    }

    // ---------- контакти ----------

    fun addContact(identity: String, name: String?): Contact? {
        val key = identity.trim().lowercase()
        if (!isValidIdentity(key) || key == core?.identity()) return null
        db.contacts().firstOrNull { it.identity == key }?.let { return it }
        val fp = fingerprintOf(key)
        val c = Contact(key, name?.trim()?.takeIf { it.isNotEmpty() } ?: "Контакт ${short(fp)}", fp)
        db.putContact(c)
        refreshContacts()
        return c
    }

    fun rename(identity: String, name: String) {
        val c = db.contacts().firstOrNull { it.identity == identity } ?: return
        db.putContact(c.copy(name = name.trim().take(60)))
        refreshContacts()
        _changed.tryEmit(identity)
    }

    fun nameOf(identity: String): String =
        db.contacts().firstOrNull { it.identity == identity }?.name ?: "Контакт ${short(fingerprintOf(identity))}"

    fun inviteUrl(code: String? = null): String {
        val id = core?.identity() ?: return ""
        val n = myName
        return api.url("/#add=$id") +
            (if (n.isNotEmpty()) "&name=" + java.net.URLEncoder.encode(n, "UTF-8").replace("+", "%20") else "") +
            (if (code != null) "&i=$code" else "")
    }

    private fun fragment(text: String): Map<String, String> =
        text.trim().substringAfter('#', "").split('&')
            .mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to java.net.URLDecoder.decode(it[1], "UTF-8") } }
            .toMap()

    /** Розібрати посилання-запрошення: (ключ, ім'я). */
    fun parseInvite(text: String): Pair<String, String?>? {
        val key = (fragment(text)["add"] ?: text.trim()).lowercase()
        return if (isValidIdentity(key)) key to fragment(text)["name"] else null
    }

    /** Код запрошення з посилання (#i=…), якщо є. */
    fun inviteCodeFrom(text: String): String? = Api.normalizeInviteCode(fragment(text)["i"])

    // ---------- повідомлення ----------

    fun log(peer: String, text: String) {
        db.addMessage(Message(peer = peer, out = false, text = text, at = System.currentTimeMillis(), kind = "call"))
        _changed.tryEmit(peer)
    }

    suspend fun send(peer: String, body: Body, wake: String? = null) = withContext(Dispatchers.IO) {
        lock.withLock {
            val c = core ?: error("Немає акаунта")
            ensureCertificate()
            if (!c.hasSession(peer)) {
                val bundle = api.getOrNull("/v0/keys/$peer") ?: error("Цей ключ не зареєстрований на сервері")
                c.startSession(bundle)
            }
            val env = c.sealEnvelope(peer, Content.encode(body), wake)
            save() // стан ратчета — до відправки
            api.send("POST", "/v0/send", env.toByteArray())
        }
    }

    suspend fun sendText(peer: String, text: String) {
        send(peer, Body.Text(text))
        db.addMessage(Message(peer = peer, out = true, text = text, at = System.currentTimeMillis()))
        _changed.emit(peer)
    }

    suspend fun sendVoice(peer: String, mime: String, dur: Long, data: ByteArray) {
        send(peer, Body.Voice(mime, dur, Base64.encodeToString(data, Base64.NO_WRAP)))
        db.addMessage(Message(peer = peer, out = true, text = "🎤 Голосове", at = System.currentTimeMillis(), kind = "voice", voiceMime = mime, voiceDur = dur, voice = data))
        _changed.emit(peer)
    }

    suspend fun sendHello(peer: String) {
        send(peer, Body.Hello(myName))
        db.addMessage(Message(peer = peer, out = true, text = "👋 ${myName.ifEmpty { "Новий контакт" }} приєднується за запрошенням", at = System.currentTimeMillis()))
        _changed.emit(peer)
    }

    /**
     * Забрати вхідні: розшифрувати, зберегти стан ключів і повідомлення, лише потім підтвердити.
     * Повертає кількість нових повідомлень.
     */
    suspend fun receive(): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            val c = core ?: return@withLock 0
            val mb = c.mailbox()
            val items = JSONArray(api.get("/v0/mailbox/$mb", c.authHeader("GET /v0/mailbox/$mb", ByteArray(0))))
            if (items.length() == 0) return@withLock 0
            val opened = mutableListOf<Pair<String, String>>()
            for (i in 0 until items.length()) {
                val env = items.getJSONObject(i).getJSONObject("envelope").toString()
                runCatching { c.openEnvelope(env) }.onSuccess { opened += it.from to it.text }
            }
            save()
            var n = 0
            for ((from, raw) in opened) if (deliver(from, raw)) n++
            val upto = items.getJSONObject(items.length() - 1).getLong("id")
            val body = """{"upto":$upto}""".toByteArray()
            api.send("POST", "/v0/mailbox/$mb/ack", body, c.authHeader("POST /v0/mailbox/$mb/ack", body))
            n
        }
    }

    private suspend fun deliver(from: String, raw: String): Boolean {
        val body = Content.decode(raw)
        val known = db.contacts().any { it.identity == from }
        if (!known) {
            val fp = fingerprintOf(from)
            val name = (body as? Body.Hello)?.name?.trim()?.take(60)?.ifEmpty { null } ?: "Новий ${short(fp)}"
            db.putContact(Contact(from, name, fp))
            refreshContacts()
        }
        val now = System.currentTimeMillis()
        val m = when (body) {
            is Body.Call -> {
                _callSignals.emit(from to body)
                return false
            }
            is Body.Hello -> Message(peer = from, out = false, text = "👋 ${body.name.ifEmpty { "Новий контакт" }} приєднується за запрошенням", at = now)
            is Body.Text -> Message(peer = from, out = false, text = body.text, at = now)
            is Body.Voice -> Message(
                peer = from, out = false, text = "🎤 Голосове", at = now, kind = "voice",
                voiceMime = body.mime, voiceDur = body.dur, voice = Base64.decode(body.data, Base64.DEFAULT),
            )
        }
        val id = db.addMessage(m)
        _changed.emit(from)
        _incoming.emit(m.copy(id = id))
        return true
    }

    // ---------- потік подій ----------

    /** Слухати сервер: щойно в скриньці пошта — забрати. Працює, доки живий scope. */
    suspend fun listenForever() {
        var delayMs = 1000L
        while (scope.isActive) {
            val c = core
            if (c == null) { delay(2000); continue }
            try {
                val req = Request.Builder().url(api.url("/v0/events"))
                    .header(Api.AUTH, c.authHeader("GET /v0/events", ByteArray(0))).build()
                api.streaming.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw HttpException(r.code, "events ${r.code}")
                    api.noteDate(r)
                    connected.value = true
                    delayMs = 1000
                    val src = r.body.source()
                    while (true) {
                        val line = src.readUtf8Line() ?: break
                        if (line == "data: mail" || line == "data:mail") scope.launch { runCatching { receive() } }
                    }
                }
            } catch (_: Exception) {
            }
            connected.value = false
            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(30_000)
        }
    }

    // ---------- дзвінки ----------

    data class Turn(val urls: List<String>, val username: String, val credential: String)

    fun turn(): Turn? {
        val c = core ?: return null
        val s = api.getOrNull("/v0/turn", c.authHeader("GET /v0/turn", ByteArray(0))) ?: return null
        val o = JSONObject(s)
        val urls = o.getJSONArray("urls").let { a -> (0 until a.length()).map { a.getString(it) } }
        return Turn(urls, o.getString("username"), o.getString("credential"))
    }

    fun safetyNumber(peer: String): String = core?.safetyNumber(peer) ?: ""

    // ---------- резервна копія / вихід ----------

    suspend fun exportBackup(password: String): ByteArray = withContext(Dispatchers.IO) {
        lock.withLock { Backup.export(core!!.export(), db, myName, password) }
    }

    suspend fun importBackup(data: ByteArray, password: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val r = Backup.import(data, password)
            val c = Core.load(r.account)
            secure.write(r.account)
            core = c
            db.wipe()
            r.contacts.forEach { db.putContact(it) }
            r.messages.forEach { db.addMessage(it) }
            myName = r.myName
            refreshContacts()
        }
        ensureKeys()
    }

    fun wipe() {
        core = null
        secure.wipe()
        db.wipe()
        refreshContacts()
    }
}
