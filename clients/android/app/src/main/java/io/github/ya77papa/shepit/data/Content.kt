package io.github.ya77papa.shepit.data

import org.json.JSONObject

/**
 * Вміст зашифрованого повідомлення. Формат той самий, що у веб-клієнта:
 * `{"v":1,"t":"text"|"hello"|"voice"|"call", ...}`; старі повідомлення — звичайний текст.
 */
sealed interface Body {
    /** [id] — спільний ідентифікатор повідомлення, за ним відправник видаляє його «у всіх». */
    /** [g] — ідентифікатор групи: повідомлення адресоване групі (кожному учаснику окремо). */
    data class Text(val text: String, val id: String? = null, val g: String? = null) : Body
    data class Hello(val name: String) : Body
    data class Voice(val mime: String, val dur: Long, val data: String, val id: String? = null, val g: String? = null) : Body
    /** Фото (JPEG base64, без EXIF). Сервер тримає його лише до доставки (максимум 3 дні). */
    data class Image(val mime: String, val w: Int, val h: Int, val data: String, val caption: String? = null, val id: String? = null, val g: String? = null) : Body
    data class Call(val op: String, val id: String, val at: Long, val sdp: String? = null) : Body
    /** Видалити мої повідомлення з цими id у співрозмовника (або в групі g). */
    data class Delete(val ids: List<String>, val g: String? = null) : Body
    /** Мій профіль: ім'я і фото (JPEG base64) — для контактів. */
    data class Profile(val name: String, val avatar: String? = null) : Body
    /** Стан групи від адміністратора; ver — версія стану («v» — версія формату, завжди 1). */
    data class GroupState(val g: String, val name: String, val avatar: String?, val members: List<Member>, val admins: List<String>, val ver: Int) : Body
    /** Я виходжу з групи. */
    data class Leave(val g: String) : Body
    /** Реакція на повідомлення з id (порожня e — прибрати мою). У групі — з g. */
    data class React(val id: String, val e: String, val g: String? = null) : Body
    /** Очистити всю бесіду у співрозмовника. */
    data object Clear : Body
    /** Тип з новішої версії — пропустити. */
    data object Unknown : Body
}

object Content {
    private const val LEGACY_HELLO = "\u0001hello "

    fun encode(b: Body): String = JSONObject().apply {
        put("v", 1)
        when (b) {
            is Body.Text -> { put("t", "text"); put("text", b.text); b.id?.let { put("id", it) }; b.g?.let { put("g", it) } }
            is Body.Hello -> { put("t", "hello"); put("name", b.name) }
            is Body.Voice -> { put("t", "voice"); put("mime", b.mime); put("dur", b.dur); put("data", b.data); b.id?.let { put("id", it) }; b.g?.let { put("g", it) } }
            is Body.Image -> {
                put("t", "image"); put("mime", b.mime); put("w", b.w); put("h", b.h); put("data", b.data)
                b.caption?.let { put("caption", it) }; b.id?.let { put("id", it) }; b.g?.let { put("g", it) }
            }
            is Body.Delete -> { put("t", "delete"); put("ids", org.json.JSONArray(b.ids)); b.g?.let { put("g", it) } }
            is Body.Profile -> { put("t", "profile"); put("name", b.name); b.avatar?.let { put("avatar", it) } }
            is Body.GroupState -> {
                put("t", "group"); put("g", b.g); put("name", b.name); b.avatar?.let { put("avatar", it) }
                put("members", org.json.JSONArray().apply { b.members.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
                put("admins", org.json.JSONArray(b.admins)); put("ver", b.ver)
            }
            is Body.Leave -> { put("t", "leave"); put("g", b.g) }
            is Body.React -> { put("t", "react"); put("id", b.id); put("e", b.e); b.g?.let { put("g", it) } }
            Body.Clear -> put("t", "clear")
            Body.Unknown -> put("t", "unknown")
            is Body.Call -> {
                put("t", "call"); put("op", b.op); put("id", b.id); put("at", b.at)
                if (b.sdp != null) put("sdp", b.sdp)
            }
        }
    }.toString()

    fun decode(raw: String): Body {
        if (raw.startsWith("{")) {
            runCatching {
                val o = JSONObject(raw)
                if (o.optInt("v") == 1) when (o.optString("t")) {
                    "text" -> return Body.Text(o.getString("text"), msgId(o.opt("id")), groupId(o))
                    "hello" -> return Body.Hello(o.optString("name"))
                    "voice" -> return Body.Voice(o.getString("mime"), o.optLong("dur"), o.getString("data"), msgId(o.opt("id")), groupId(o))
                    "image" -> return image(o) ?: Body.Unknown
                    "delete" -> {
                        val a = o.optJSONArray("ids") ?: org.json.JSONArray()
                        return Body.Delete((0 until minOf(a.length(), 500)).mapNotNull { msgId(a.opt(it)) }, groupId(o))
                    }
                    "profile" -> return Body.Profile(o.optString("name").trim().take(60), avatar(o.opt("avatar")))
                    "group" -> return groupState(o) ?: Body.Unknown
                    "leave" -> return groupId(o)?.let { Body.Leave(it) } ?: Body.Unknown
                    "react" -> {
                        val id = msgId(o.opt("id")) ?: return Body.Unknown
                        val e = o.optString("e").takeIf { validReaction(it) } ?: return Body.Unknown
                        return Body.React(id, e, groupId(o))
                    }
                    "clear" -> return Body.Clear
                    "call" -> return Body.Call(
                        o.getString("op"), o.getString("id"), o.optLong("at"),
                        if (o.has("sdp")) o.getString("sdp") else null,
                    )
                    else -> if (o.has("t")) return Body.Unknown
                }
            }
        }
        if (raw.startsWith(LEGACY_HELLO)) return Body.Hello(raw.removePrefix(LEGACY_HELLO).trim())
        return Body.Text(raw)
    }

    /** Емодзі для поля введення (за групами) — ті самі, що у веб-версії. */
    val EMOJI: List<Pair<String, List<String>>> = listOf(
        "Смайли" to "😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😙 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🥵 🥶 🥴 😵 🤯 🤠 🥳 😎 🤓 🧐 😕 😟 🙁 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 💀 💩 🤡 👻 👽 🤖".split(" "),
        "Жести" to "👍 👎 👌 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ ✋ 🤚 🖐 🖖 👋 🤝 🙏 👏 🙌 👐 🤲 💪 ✍️ 🤳 💅 👀 🧠".split(" "),
        "Серця" to "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💯 💢 💥 💫 💦 💨 🔥 ✨ ⭐ 🌟".split(" "),
        "Природа" to "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🐔 🐧 🐦 🦆 🦉 🐴 🦄 🐝 🦋 🐌 🐞 🐢 🐍 🐙 🐬 🐳 🌸 🌻 🌹 🌷 🌱 🌲 🍀 🍁 🌈 ☀️ 🌙 ⛄ ❄️ ☔ ⚡".split(" "),
        "Їжа" to "🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🥑 🥦 🥕 🌽 🥔 🍞 🧀 🥚 🍳 🥓 🍔 🍟 🍕 🌭 🥪 🌮 🍝 🍜 🍣 🍰 🎂 🍫 🍬 🍩 🍪 ☕ 🍵 🍺 🍷 🥂 🍾".split(" "),
        "Різне" to "🎉 🎊 🎁 🎈 🏆 ⚽ 🏀 🎮 🎵 🎶 🎤 🎧 📷 📱 💻 ⌚ 💡 📌 📎 ✏️ 📚 💰 🚗 ✈️ 🚀 🏠 ⏰ ✅ ❌ ❗ ❓ ⚠️ 🇺🇦".split(" "),
    )

    val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏", "🔥", "👎")

    /** Реакція: 1–8 символів емодзі, без латиниці, цифр і розмітки (порожня — прибрати). */
    fun validReaction(e: String): Boolean = e.isEmpty() || (e.codePointCount(0, e.length) <= 8 && e.length <= 32 && e.none { it.code < 0x80 })

    private val B64 = Regex("^[A-Za-z0-9+/=]+$")

    /** Фото — ті самі перевірки, що у веб-версії (validImage). */
    private fun image(o: JSONObject): Body.Image? {
        val w = o.opt("w") as? Int ?: return null
        val h = o.opt("h") as? Int ?: return null
        val data = o.opt("data") as? String ?: return null
        val caption = if (o.has("caption")) (o.opt("caption") as? String ?: return null) else null
        if (o.optString("mime") != "image/jpeg" || w !in 1..4096 || h !in 1..4096) return null
        if (data.isEmpty() || data.length >= 900_000 || !B64.matches(data)) return null
        if (caption != null && caption.length > 2000) return null
        return Body.Image("image/jpeg", w, h, data, caption?.ifEmpty { null }, msgId(o.opt("id")), groupId(o))
    }

    private val MSG_ID = Regex("^[0-9a-f]{32}$")

    private fun msgId(v: Any?): String? = (v as? String)?.takeIf { MSG_ID.matches(it) }

    /** Група вказана, але з хибним id — повідомлення ігнорується (не потрапить в особисту бесіду). */
    private fun groupId(o: JSONObject): String? = if (!o.has("g")) null else (msgId(o.opt("g")) ?: INVALID_GROUP)

    const val INVALID_GROUP = "invalid"

    private val AVATAR = Regex("^[A-Za-z0-9+/=]+$")

    /** Фото профілю чи групи: base64 JPEG до ~64 КБ. */
    fun avatar(v: Any?): String? = (v as? String)?.takeIf { it.length < 90_000 && AVATAR.matches(it) }

    private val IDENTITY = Regex("^05[0-9a-f]{64}$")

    private fun groupState(o: JSONObject): Body.GroupState? {
        val g = msgId(o.opt("g")) ?: return null
        val name = o.optString("name").takeIf { it.length <= 80 } ?: return null
        val ver = o.optInt("ver", -1).takeIf { it > 0 } ?: return null
        val m = o.optJSONArray("members") ?: return null
        if (m.length() > 101) return null
        val members = (0 until m.length()).map { i ->
            val x = m.optJSONObject(i) ?: return null
            val id = x.optString("id").takeIf { IDENTITY.matches(it) } ?: return null
            Member(id, x.optString("name").take(60))
        }
        val a = o.optJSONArray("admins") ?: return null
        val admins = (0 until a.length()).map { a.optString(it) }
        if (admins.any { ad -> members.none { it.id == ad } }) return null
        val av = if (o.has("avatar")) avatar(o.opt("avatar")) ?: return null else null
        return Body.GroupState(g, name, av, members, admins, ver)
    }

    /** Новий ідентифікатор повідомлення (128 біт). */
    fun newMsgId(): String {
        val b = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        return b.joinToString("") { "%02x".format(it) }
    }

    fun fmtDuration(ms: Long): String {
        val s = (ms + 500) / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
