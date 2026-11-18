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

    /** Моє фото (JPEG base64), порожньо — немає. */
    var myAvatar: String
        get() = db.get("myAvatar") ?: ""
        set(v) = db.set("myAvatar", v)

    /** Повідомляти, що я переглянув (як у Signal: вимкнеш — сам теж не бачиш чужих «переглянуто»). */
    var readReceipts: Boolean
        get() = db.get("readReceipts") != "0"
        set(v) = db.set("readReceipts", if (v) "1" else "0")

    private val _groups = MutableStateFlow<List<Group>>(emptyList())
    val groups = _groups.asStateFlow()
    private val _channels = MutableStateFlow<List<ChannelRec>>(emptyList())
    val channels = _channels.asStateFlow()

    fun refreshGroups() { _groups.value = db.groups() }
    fun refreshChannels() { _channels.value = db.channels() }

    fun hasAccount() = secure.exists()

    suspend fun load(): Boolean = withContext(Dispatchers.IO) {
        if (core != null) return@withContext true
        val st = secure.read() ?: return@withContext false
        core = Core.load(st)
        db.failStuck()
        refreshContacts()
        refreshGroups()
        refreshChannels()
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

    fun nameOf(identity: String): String {
        if (identity.startsWith("g:")) return db.group(identity.removePrefix("g:"))?.name ?: "Група"
        if (identity == core?.identity()) return myName.ifEmpty { "Я" }
        return db.contacts().firstOrNull { it.identity == identity }?.name ?: "Контакт ${short(fingerprintOf(identity))}"
    }

    /** Як показувати учасника групи: ім'я з моїх контактів, інакше як його назвав адмін. */
    fun memberName(id: String?, g: Group?): String {
        if (id == null) return ""
        if (id == core?.identity()) return myName.ifEmpty { "Я" }
        return db.contacts().firstOrNull { it.identity == id }?.name ?: g?.members?.firstOrNull { it.id == id }?.name ?: "Контакт ${short(fingerprintOf(id))}"
    }

    fun avatarOf(id: String): String? = if (id == core?.identity()) myAvatar.ifEmpty { null } else db.contacts().firstOrNull { it.identity == id }?.avatar

    // ---------- профіль ----------

    /** Надіслати ім'я й фото контактам (усім або вказаним). */
    suspend fun sendProfile(to: List<String>? = null) {
        val body = Body.Profile(myName, myAvatar.ifEmpty { null })
        for (id in to ?: db.contacts().map { it.identity }) runCatching { send(id, body) }
    }

    private suspend fun onProfile(from: String, b: Body.Profile) {
        val c = db.contacts().firstOrNull { it.identity == from } ?: return
        // Ім'я, яке дав сам користувач, не чіпаємо; автоматичне — замінюємо.
        val auto = Regex("^(Новий|Контакт) \\d").containsMatchIn(c.name)
        db.putContact(c.copy(avatar = b.avatar, name = if (auto && b.name.isNotBlank()) b.name else c.name))
        refreshContacts()
        _changed.emit(from)
    }

    // ---------- групи ----------

    /** Надіслати в групу: кожному учаснику окремо (сервер не знає, що це група). Повертає к-сть невдач. */
    suspend fun sendGroup(g: Group, body: Body, to: List<Member> = g.members): Int {
        val me = core?.identity()
        var failed = 0
        for (m in to) {
            if (m.id == me) continue
            runCatching { send(m.id, body) }.onFailure { failed++ }
        }
        return failed
    }

    private fun stateOf(g: Group) = Body.GroupState(g.id, g.name, g.avatar, g.members, g.admins, g.v)

    private suspend fun logGroup(g: Group, text: String) {
        db.addMessage(Message(peer = "g:${g.id}", out = false, text = text, at = System.currentTimeMillis(), kind = "call"))
        _changed.emit("g:${g.id}")
    }

    /** Адмін змінив групу: нова версія стану всім (і тим, кого видалили, — щоб знали). */
    private suspend fun updateGroup(next: Group, notify: List<Member>): Int {
        val g = next.copy(v = next.v + 1)
        withContext(Dispatchers.IO) { db.putGroup(g) }
        refreshGroups()
        val failed = sendGroup(g, stateOf(g), notify)
        _changed.emit("g:${g.id}")
        return failed
    }

    suspend fun createGroup(name: String, memberIds: List<String>): Group {
        val me = core?.identity() ?: error("Немає акаунта")
        val g = Group(
            id = Content.newMsgId(), name = name.trim().take(80), v = 0, admins = listOf(me),
            members = listOf(Member(me, myName.ifEmpty { "Я" })) + memberIds.map { Member(it, nameOf(it)) },
        )
        logGroup(g, "Групу «${g.name}» створено")
        updateGroup(g, g.members)
        return db.group(g.id)!!
    }

    /** Зміна групи адміном: [f] повертає новий стан і кого сповістити (за замовчуванням — старий склад). */
    suspend fun groupOp(id: String, f: suspend (Group) -> Pair<Group, List<Member>?>): Int {
        val g = db.group(id) ?: error("Групу не знайдено")
        val (next, notify) = f(g)
        return updateGroup(next, notify ?: g.members)
    }

    suspend fun renameGroup(id: String, name: String) = groupOp(id) { it.copy(name = name.trim().take(80)) to null }
    suspend fun setGroupAvatar(id: String, avatar: String) = groupOp(id) { it.copy(avatar = avatar) to null }

    suspend fun addToGroup(id: String, ids: List<String>) = groupOp(id) { g ->
        val added = ids.filter { a -> g.members.none { it.id == a } }.map { Member(it, nameOf(it)) }
        if (added.isNotEmpty()) logGroup(g, "Додано: ${added.joinToString { it.name }}")
        val next = g.copy(members = g.members + added)
        next to next.members
    }

    suspend fun removeFromGroup(id: String, member: String) = groupOp(id) { g ->
        logGroup(g, "${memberName(member, g)} видалено з групи")
        g.copy(members = g.members.filter { it.id != member }, admins = g.admins.filter { it != member }) to g.members
    }

    suspend fun toggleAdmin(id: String, member: String) = groupOp(id) { g ->
        val admins = if (member in g.admins) g.admins - member else g.admins + member
        g.copy(admins = admins.ifEmpty { listOf(core!!.identity()) }) to null
    }

    suspend fun leaveGroup(id: String) {
        val g = db.group(id) ?: return
        val me = core?.identity()
        sendGroup(g, Body.Leave(g.id))
        db.putGroup(g.copy(left = true, members = g.members.filter { it.id != me }))
        logGroup(g, "Ти вийшов(-ла) з групи")
        refreshGroups()
    }

    suspend fun deleteGroupLocal(id: String) {
        withContext(Dispatchers.IO) { db.deleteGroup(id) }
        refreshGroups()
        _changed.emit("g:$id")
    }

    /** Повідомлення, що стосується групи. */
    private suspend fun onGroupMessage(from: String, body: Body) {
        val me = core?.identity() ?: return
        val gid = when (body) {
            is Body.GroupState -> body.g
            is Body.Leave -> body.g
            is Body.Text -> body.g
            is Body.Voice -> body.g
            is Body.Image -> body.g
            is Body.Delete -> body.g
            is Body.React -> body.g
            is Body.Receipt -> body.g
            else -> null
        } ?: return
        if (gid == Content.INVALID_GROUP) return
        val key = "g:$gid"
        val local = db.group(gid)
        if (body is Body.GroupState) {
            val amMember = body.members.any { it.id == me }
            var next = Group(gid, body.name, body.avatar, body.members, body.admins, body.ver)
            if (local == null) {
                if (from !in body.admins || !amMember) return
                db.putGroup(next)
                logGroup(next, "${memberName(from, next)} додав(-ла) тебе до групи «${next.name}»")
            } else {
                if (from !in local.admins || body.ver <= local.v) return
                val added = next.members.filter { m -> local.members.none { it.id == m.id } && m.id != me }
                val removed = local.members.filter { m -> next.members.none { it.id == m.id } && m.id != me }
                if (!amMember) next = next.copy(left = true)
                db.putGroup(next)
                when {
                    !amMember && !local.left -> logGroup(next, "${memberName(from, local)} видалив(-ла) тебе з групи")
                    amMember && local.left -> logGroup(next, "${memberName(from, local)} повернув(-ла) тебе до групи")
                }
                if (amMember && added.isNotEmpty()) logGroup(next, "Додано: ${added.joinToString { memberName(it.id, next) }}")
                if (amMember && removed.isNotEmpty()) logGroup(next, "Видалено: ${removed.joinToString { memberName(it.id, local) }}")
                if (next.name != local.name) logGroup(next, "Нова назва: «${next.name}»")
            }
            refreshGroups()
            return
        }
        if (local == null || local.left || local.members.none { it.id == from }) return
        val now = System.currentTimeMillis()
        val m = when (body) {
            is Body.Leave -> {
                val name = memberName(from, local)
                val members = local.members.filter { it.id != from }
                var admins = local.admins - from
                // Без адміністраторів група не лишається: адміном стає учасник з найменшим ключем (у всіх однаково).
                if (admins.isEmpty() && members.isNotEmpty()) admins = listOf(members.map { it.id }.sorted().first())
                db.putGroup(local.copy(members = members, admins = admins))
                refreshGroups()
                logGroup(local, "$name виходить з групи")
                return
            }
            is Body.Delete -> {
                if (db.deleteIncoming(key, body.ids, from) > 0) _changed.emit(key)
                return
            }
            is Body.Receipt -> {
                if (db.receipt(key, body.ids, from, body.s == "r")) _changed.emit(key)
                return
            }
            is Body.React -> {
                if (db.react(key, body.id, from, body.e)) _changed.emit(key)
                return
            }
            is Body.Text -> Message(peer = key, out = false, text = body.text, at = now, mid = body.id, sender = from, re = body.re)
            is Body.Voice -> Message(
                peer = key, out = false, text = "🎤 Голосове", at = now, kind = "voice",
                voiceMime = body.mime, voiceDur = body.dur, voice = Base64.decode(body.data, Base64.DEFAULT), mid = body.id, sender = from, re = body.re,
            )
            is Body.Image -> imageMessage(key, body).copy(sender = from)
            else -> return
        }
        noteDelivered(from, m.mid, gid)
        val id = db.addMessage(m)
        _changed.emit(key)
        _incoming.emit(m.copy(id = id))
    }

    // ---------- канали ----------

    suspend fun saveChannel(c: ChannelRec) {
        withContext(Dispatchers.IO) { db.putChannel(c) }
        refreshChannels()
    }

    suspend fun createChannel(name: String, about: String, public: Boolean): ChannelRec = withContext(Dispatchers.IO) {
        val c = core ?: error("Немає акаунта")
        val k = Channels.newKeys(public)
        val rec = ChannelRec(k.id, public, k.key, k.pub, k.priv, name.trim().take(80), about.trim().take(500))
        api.channels.putMeta(rec, Channels.Meta(rec.name, rec.about)) { purpose, body -> c.authHeader(purpose, body) }
        db.putChannel(rec)
        refreshChannels()
        rec
    }

    fun channelLink(c: ChannelRec) = Channels.link(api.url(""), c)

    /** Нові дописи в каналах (для позначки «•»). */
    suspend fun checkChannels() = withContext(Dispatchers.IO) {
        for (c in db.channels()) {
            val last = api.channels.lastSeq(c.id) ?: continue
            if (last != c.lastSeq) db.putChannel(c.copy(lastSeq = last))
        }
        refreshChannels()
    }

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

    /** До контакту або в групу (peer = "g:<id>"). Повертає, скільком учасникам не доставлено. */
    private suspend fun sendTo(peer: String, body: (String?) -> Body): Int {
        if (!peer.startsWith("g:")) { send(peer, body(null)); return 0 }
        val g = db.group(peer.removePrefix("g:")) ?: error("Групу не знайдено")
        if (g.left) error("Ти не в цій групі")
        return sendGroup(g, body(g.id))
    }

    /**
     * Записати вихідне («надсилається»), передати, оновити стан. Повертає, скільком учасникам групи
     * не доставлено. Якщо не пішло нікому — стан «не надіслано» і виняток (для підказки в інтерфейсі).
     */
    private suspend fun sendOut(m: Message, body: (String?) -> Body): Int {
        val id = withContext(Dispatchers.IO) { db.addMessage(m.copy(status = "sending")) }
        _changed.emit(m.peer)
        return transmit(id, m.peer, body)
    }

    private suspend fun transmit(id: Long, peer: String, body: (String?) -> Body): Int {
        val me = core?.identity()
        val total = if (peer.startsWith("g:")) db.group(peer.removePrefix("g:"))?.members?.count { it.id != me } ?: 0 else 1
        try {
            val failed = sendTo(peer, body)
            val ok = total == 0 || failed < total
            db.setStatus(id, if (ok) "sent" else "failed")
            _changed.emit(peer)
            if (ok) _sent.tryEmit(peer)
            if (!ok) error("Не вдалося надіслати")
            return failed
        } catch (e: Exception) {
            db.setStatus(id, "failed")
            _changed.emit(peer)
            throw e
        }
    }

    /** Повторити невдале: тіло відновлюється з того, що збережено в бесіді. */
    suspend fun retry(m: Message): Int {
        val mid = m.mid
        val make: (String?) -> Body = when (m.kind) {
            "voice" -> { g -> Body.Voice(m.voiceMime ?: "audio/ogg", m.voiceDur, Base64.encodeToString(m.voice ?: ByteArray(0), Base64.NO_WRAP), mid, g, m.re) }
            "image" -> {
                val bytes = m.voice ?: error("Фото пошкоджене")
                val b = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
                val data = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val caption = m.text.takeIf { it != "Фото" }
                ({ g -> Body.Image("image/jpeg", b.outWidth, b.outHeight, data, caption, mid, g, m.re) })
            }
            "text" -> { g -> Body.Text(m.text, mid, g, m.re) }
            else -> return 0
        }
        db.setStatus(m.id, "sending")
        _changed.emit(m.peer)
        return transmit(m.id, m.peer, make)
    }

    suspend fun sendText(peer: String, text: String, re: Reply? = null): Int {
        val mid = Content.newMsgId()
        return sendOut(Message(peer = peer, out = true, text = text, at = System.currentTimeMillis(), mid = mid, re = re)) { g -> Body.Text(text, mid, g, re) }
    }

    /** Цитата для відповіді на [m] у бесіді [peer]. */
    fun replyTo(peer: String, m: Message): Reply? {
        val mid = m.mid ?: return null
        val by = if (m.out) core?.identity() else if (peer.startsWith("g:")) m.sender else peer
        val t = when (m.kind) {
            "voice" -> "Голосове повідомлення"
            "image" -> if (m.text != "Фото") "Фото: ${m.text}" else "Фото"
            else -> m.text
        }
        return Reply(mid, by ?: return null, t.take(200))
    }

    // ---------- доставлено / переглянуто ----------

    /** Кому ще підтвердити доставку: (кому, група) → id повідомлень. */
    private val pendingAcks = mutableMapOf<Pair<String, String?>, MutableSet<String>>()

    private fun noteDelivered(to: String, mid: String?, g: String?) {
        if (mid == null) return
        synchronized(pendingAcks) { pendingAcks.getOrPut(to to g) { mutableSetOf() } += mid }
    }

    private suspend fun sendReceipt(to: String, ids: List<String>, s: String, g: String?) {
        if (to == core?.identity()) return
        // «quiet» — сервер не будить отримувача сповіщенням заради службового.
        ids.chunked(200).forEach { part -> runCatching { send(to, Body.Receipt(part, s, g), wake = "quiet") } }
    }

    /** Бесіда на екрані — позначити вхідні переглянутими і (якщо дозволено) повідомити відправників. */
    suspend fun markRead(peer: String) {
        val fresh = withContext(Dispatchers.IO) { db.takeUnseen(peer) }
        if (fresh.isEmpty() || !readReceipts) return
        val gid = peer.removePrefix("g:").takeIf { peer.startsWith("g:") }
        val bySender = fresh.mapNotNull { (sender, mid) -> (if (gid != null) sender else peer)?.let { it to mid } }.groupBy({ it.first }, { it.second })
        synchronized(pendingAcks) { bySender.forEach { (to, mids) -> pendingAcks[to to gid]?.removeAll(mids.toSet()) } }
        bySender.forEach { (to, mids) -> sendReceipt(to, mids, "r", gid) }
    }

    /** Після отримання пошти: «доставлено» всім відправникам. */
    private suspend fun flushAcks() {
        val batch = synchronized(pendingAcks) { pendingAcks.toMap().also { pendingAcks.clear() } }
        batch.forEach { (k, mids) -> if (mids.isNotEmpty()) sendReceipt(k.first, mids.toList(), "d", k.second) }
    }

    // ---------- в мережі / востаннє ----------

    /** Показувати іншим, коли я в мережі (як у Telegram: вимкнеш — сам теж не бачиш чужого часу). */
    var showPresence: Boolean
        get() = db.get("showPresence") != "0"
        set(v) = db.set("showPresence", if (v) "1" else "0")

    private val _presence = MutableStateFlow<Map<String, PresenceInfo>>(emptyMap())
    val presence = _presence.asStateFlow()

    /** «Я тут» (застосунок на екрані) або «згорнув». */
    suspend fun presenceBeat(online: Boolean) = withContext(Dispatchers.IO) {
        val c = core ?: return@withContext
        val body = JSONObject().put("online", online).put("share", showPresence).toString().toByteArray()
        runCatching { api.post("/v0/presence", body, c.authHeader("POST /v0/presence", body)) }
    }

    /** Оновити статуси контактів (старий сервер — просто без статусів). */
    suspend fun refreshPresence() = withContext(Dispatchers.IO) {
        val c = core ?: return@withContext
        val ids = _contacts.value.map { it.identity }.take(200)
        if (ids.isEmpty()) return@withContext
        val body = JSONObject().put("ids", org.json.JSONArray(ids)).toString().toByteArray()
        runCatching {
            val o = JSONObject(api.post("/v0/presence/query", body, c.authHeader("POST /v0/presence/query", body)))
            val fresh = o.keys().asSequence().associateWith { k ->
                val p = o.getJSONObject(k)
                PresenceInfo(p.optBoolean("on"), if (p.has("at")) p.getLong("at") else null, p.optBoolean("hidden"))
            }
            _presence.value = rememberPresence(ids, fresh)
        }
    }

    /**
     * Сервер тримає статуси лише в пам'яті — після його перезапуску він якийсь час нічого не знає.
     * Тому останній відомий статус кожного контакту пам'ятаємо тут, на пристрої, і показуємо
     * «востаннє…» з нього, доки сервер знову не дізнається.
     */
    private fun rememberPresence(ids: List<String>, fresh: Map<String, PresenceInfo>): Map<String, PresenceInfo> {
        val now = System.currentTimeMillis()
        val cache = runCatching { JSONObject(db.get("presenceCache") ?: "{}") }.getOrDefault(JSONObject())
        val out = mutableMapOf<String, PresenceInfo>()
        for (id in ids) {
            val f = fresh[id]
            if (f != null) {
                out[id] = f
                // «В мережі» запам'ятовуємо як «востаннє зараз».
                cache.put(id, JSONObject().apply { if (f.hidden) put("hidden", true) else put("at", if (f.on) now else f.at ?: now) })
            } else cache.optJSONObject(id)?.let { cached ->
                out[id] = PresenceInfo(at = if (cached.has("at")) cached.getLong("at") else null, hidden = cached.optBoolean("hidden"))
            }
        }
        // Лише поточні контакти.
        cache.keys().asSequence().toList().filter { it !in ids }.forEach { cache.remove(it) }
        db.set("presenceCache", cache.toString())
        return out
    }

    /** Вихідне в бесіді peer пішло на сервер (для звуку «надіслано»). */
    private val _sent = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val sent = _sent.asSharedFlow()

    /** Звуки в бесіді. */
    var chatSounds: Boolean
        get() = db.get("chatSounds") != "0"
        set(v) = db.set("chatSounds", if (v) "1" else "0")

    /** Відкрита на екрані бесіда (null — жодної або застосунок не на екрані). */
    @Volatile var visibleChat: String? = null

    /** Фото зберігається лише в базі застосунку (поле voice — байти JPEG, voiceMime — його тип). */
    private fun imageMessage(peer: String, b: Body.Image) = Message(
        peer = peer, out = false, text = b.caption ?: "Фото", at = System.currentTimeMillis(), kind = "image",
        voiceMime = b.mime, voice = Base64.decode(b.data, Base64.DEFAULT), mid = b.id, re = b.re,
    )

    /** Надіслати фото: [jpeg] уже стиснений і без EXIF (див. Photo.prepare). */
    suspend fun sendImage(peer: String, jpeg: ByteArray, w: Int, h: Int, caption: String?, re: Reply? = null): Int {
        val mid = Content.newMsgId()
        val data = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        return sendOut(Message(peer = peer, out = true, text = caption ?: "Фото", at = System.currentTimeMillis(), kind = "image", voiceMime = "image/jpeg", voice = jpeg, mid = mid, re = re)) { g ->
            Body.Image("image/jpeg", w, h, data, caption, mid, g, re)
        }
    }

    suspend fun sendVoice(peer: String, mime: String, dur: Long, data: ByteArray, re: Reply? = null) {
        val mid = Content.newMsgId()
        val b64 = Base64.encodeToString(data, Base64.NO_WRAP)
        sendOut(Message(peer = peer, out = true, text = "🎤 Голосове", at = System.currentTimeMillis(), kind = "voice", voiceMime = mime, voiceDur = dur, voice = data, mid = mid, re = re)) { g ->
            Body.Voice(mime, dur, b64, mid, g, re)
        }
    }

    // ---------- реакції ----------

    /** Поставити реакцію (та сама ще раз — прибрати). Лише на повідомлення з id. */
    suspend fun react(m: Message, e: String) {
        val mid = m.mid ?: return
        val me = core?.identity() ?: return
        val next = if (m.reactions[me] == e) "" else e
        sendTo(m.peer) { g -> Body.React(mid, next, g) }
        withContext(Dispatchers.IO) { db.react(m.peer, mid, me, next) }
        _changed.emit(m.peer)
    }

    // ---------- видалення ----------

    /** Видалити повідомлення: лише в мене або в обох (лише свої, з id). */
    suspend fun deleteMessage(m: Message, everywhere: Boolean) {
        if (everywhere && m.out && m.mid != null) sendTo(m.peer) { g -> Body.Delete(listOf(m.mid), g) }
        withContext(Dispatchers.IO) { db.deleteMessage(m.id) }
        _changed.emit(m.peer)
    }

    suspend fun clearChat(peer: String, everywhere: Boolean) {
        if (everywhere && !peer.startsWith("g:")) send(peer, Body.Clear)
        withContext(Dispatchers.IO) { db.clearChat(peer) }
        _changed.emit(peer)
    }

    /** Видалити контакт і бесіду з ним (лише в мене). */
    suspend fun deleteContact(peer: String) {
        withContext(Dispatchers.IO) { db.deleteContact(peer) }
        refreshContacts()
        _changed.emit(peer)
    }

    suspend fun sendHello(peer: String) {
        send(peer, Body.Hello(myName))
        db.addMessage(Message(peer = peer, out = true, text = "👋 ${myName.ifEmpty { "Новий контакт" }} приєднується за запрошенням", at = System.currentTimeMillis()))
        _changed.emit(peer)
        if (myAvatar.isNotEmpty()) runCatching { send(peer, Body.Profile(myName, myAvatar)) }
    }

    /**
     * Забрати вхідні: розшифрувати, зберегти стан ключів і повідомлення, лише потім підтвердити.
     * Повертає кількість нових повідомлень.
     */
    suspend fun receive(): Int = withContext(Dispatchers.IO) {
        val n = receiveLocked()
        // Підтвердження — вже поза м'ютексом (send його бере).
        visibleChat?.let { markRead(it) }
        flushAcks()
        n
    }

    private suspend fun receiveLocked(): Int {
        return lock.withLock {
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
        // Групи: окремий шлях (і жодних нових контактів від учасників групи).
        val groupish = when (body) {
            is Body.GroupState, is Body.Leave -> true
            is Body.Text -> body.g != null
            is Body.Voice -> body.g != null
            is Body.Image -> body.g != null
            is Body.Delete -> body.g != null
            is Body.React -> body.g != null
            is Body.Receipt -> body.g != null
            else -> false
        }
        if (groupish) {
            onGroupMessage(from, body)
            return false
        }
        if (body is Body.Profile) {
            onProfile(from, body)
            return false
        }
        if (body is Body.React) {
            if (db.contacts().any { it.identity == from } && db.react(from, body.id, from, body.e)) _changed.emit(from)
            return false
        }
        if (body is Body.Receipt) {
            if (db.contacts().any { it.identity == from } && db.receipt(from, body.ids, from, body.s == "r")) _changed.emit(from)
            return false
        }
        // Службові: співрозмовник видалив свої повідомлення «у всіх» або очистив бесіду.
        when (body) {
            is Body.Delete -> {
                if (db.deleteIncoming(from, body.ids) > 0) _changed.emit(from)
                return false
            }
            Body.Clear -> {
                if (db.contacts().none { it.identity == from }) return false
                db.clearChat(from)
                db.addMessage(Message(peer = from, out = false, text = "🧹 ${nameOf(from)} очистив(-ла) бесіду", at = System.currentTimeMillis(), kind = "call"))
                _changed.emit(from)
                return false
            }
            Body.Unknown -> return false
            else -> {}
        }
        val known = db.contacts().any { it.identity == from }
        if (!known) {
            val fp = fingerprintOf(from)
            val name = (body as? Body.Hello)?.name?.trim()?.take(60)?.ifEmpty { null } ?: "Новий ${short(fp)}"
            db.putContact(Contact(from, name, fp))
            refreshContacts()
            // Новий контакт привітався — надіслати йому моє фото (після receive: тут тримається м'ютекс).
            if (body is Body.Hello && myAvatar.isNotEmpty()) scope.launch { sendProfile(listOf(from)) }
        }
        val now = System.currentTimeMillis()
        val m = when (body) {
            is Body.Call -> {
                _callSignals.emit(from to body)
                return false
            }
            is Body.Hello -> Message(peer = from, out = false, text = "👋 ${body.name.ifEmpty { "Новий контакт" }} приєднується за запрошенням", at = now)
            is Body.Text -> Message(peer = from, out = false, text = body.text, at = now, mid = body.id, re = body.re)
            is Body.Voice -> Message(
                peer = from, out = false, text = "🎤 Голосове", at = now, kind = "voice",
                voiceMime = body.mime, voiceDur = body.dur, voice = Base64.decode(body.data, Base64.DEFAULT), mid = body.id, re = body.re,
            )
            is Body.Image -> imageMessage(from, body)
            else -> return false
        }
        noteDelivered(from, m.mid, null)
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
            r.groups.forEach { db.putGroup(it) }
            r.channels.forEach { db.putChannel(it) }
            myName = r.myName
            myAvatar = r.myAvatar
            refreshContacts()
            refreshGroups()
            refreshChannels()
        }
        ensureKeys()
    }

    fun wipe() {
        core = null
        secure.wipe()
        db.wipe()
        refreshContacts()
        refreshGroups()
        refreshChannels()
    }
}
