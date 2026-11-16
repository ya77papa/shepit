package io.github.ya77papa.shepit.data

import org.json.JSONObject

/**
 * Вміст зашифрованого повідомлення. Формат той самий, що у веб-клієнта:
 * `{"v":1,"t":"text"|"hello"|"voice"|"call", ...}`; старі повідомлення — звичайний текст.
 */
sealed interface Body {
    data class Text(val text: String) : Body
    data class Hello(val name: String) : Body
    data class Voice(val mime: String, val dur: Long, val data: String) : Body
    data class Call(val op: String, val id: String, val at: Long, val sdp: String? = null) : Body
}

object Content {
    private const val LEGACY_HELLO = "\u0001hello "

    fun encode(b: Body): String = JSONObject().apply {
        put("v", 1)
        when (b) {
            is Body.Text -> { put("t", "text"); put("text", b.text) }
            is Body.Hello -> { put("t", "hello"); put("name", b.name) }
            is Body.Voice -> { put("t", "voice"); put("mime", b.mime); put("dur", b.dur); put("data", b.data) }
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
                    "text" -> return Body.Text(o.getString("text"))
                    "hello" -> return Body.Hello(o.optString("name"))
                    "voice" -> return Body.Voice(o.getString("mime"), o.optLong("dur"), o.getString("data"))
                    "call" -> return Body.Call(
                        o.getString("op"), o.getString("id"), o.optLong("at"),
                        if (o.has("sdp")) o.getString("sdp") else null,
                    )
                }
            }
        }
        if (raw.startsWith(LEGACY_HELLO)) return Body.Hello(raw.removePrefix(LEGACY_HELLO).trim())
        return Body.Text(raw)
    }

    fun fmtDuration(ms: Long): String {
        val s = (ms + 500) / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
