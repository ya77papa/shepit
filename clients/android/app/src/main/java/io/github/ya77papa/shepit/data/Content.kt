package io.github.ya77papa.shepit.data

import org.json.JSONObject

/**
 * Вміст зашифрованого повідомлення. Формат той самий, що у веб-клієнта:
 * `{"v":1,"t":"text"|"hello"|"voice"|"call", ...}`; старі повідомлення — звичайний текст.
 */
sealed interface Body {
    /** [id] — спільний ідентифікатор повідомлення, за ним відправник видаляє його «у всіх». */
    data class Text(val text: String, val id: String? = null) : Body
    data class Hello(val name: String) : Body
    data class Voice(val mime: String, val dur: Long, val data: String, val id: String? = null) : Body
    data class Call(val op: String, val id: String, val at: Long, val sdp: String? = null) : Body
    /** Видалити мої повідомлення з цими id у співрозмовника. */
    data class Delete(val ids: List<String>) : Body
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
            is Body.Text -> { put("t", "text"); put("text", b.text); b.id?.let { put("id", it) } }
            is Body.Hello -> { put("t", "hello"); put("name", b.name) }
            is Body.Voice -> { put("t", "voice"); put("mime", b.mime); put("dur", b.dur); put("data", b.data); b.id?.let { put("id", it) } }
            is Body.Delete -> { put("t", "delete"); put("ids", org.json.JSONArray(b.ids)) }
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
                    "text" -> return Body.Text(o.getString("text"), msgId(o.opt("id")))
                    "hello" -> return Body.Hello(o.optString("name"))
                    "voice" -> return Body.Voice(o.getString("mime"), o.optLong("dur"), o.getString("data"), msgId(o.opt("id")))
                    "delete" -> {
                        val a = o.optJSONArray("ids") ?: org.json.JSONArray()
                        return Body.Delete((0 until minOf(a.length(), 500)).mapNotNull { msgId(a.opt(it)) })
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

    private val MSG_ID = Regex("^[0-9a-f]{32}$")

    private fun msgId(v: Any?): String? = (v as? String)?.takeIf { MSG_ID.matches(it) }

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
