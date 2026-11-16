package io.github.ya77papa.shepit.data

import org.json.JSONArray
import org.json.JSONObject

/** Учасник групи: ім'я — як його знає той, хто додав. */
data class Member(val id: String, val name: String)

/**
 * Група: існує лише на пристроях учасників, сервер про неї не знає.
 * Змінюють склад і назву адміністратори; v — версія стану (більша перемагає).
 * JSON — той самий формат, що у веб-версії (резервні копії сумісні).
 */
data class Group(
    val id: String,
    val name: String,
    val avatar: String? = null,
    val members: List<Member>,
    val admins: List<String>,
    val v: Int,
    val left: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); avatar?.let { put("avatar", it) }
        put("members", JSONArray().apply { members.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
        put("admins", JSONArray(admins)); put("v", v)
        if (left) put("left", true)
    }

    companion object {
        fun fromJson(s: String): Group? = runCatching { fromJson(JSONObject(s)) }.getOrNull()

        fun fromJson(o: JSONObject): Group {
            val m = o.getJSONArray("members")
            val a = o.getJSONArray("admins")
            return Group(
                id = o.getString("id"), name = o.getString("name"), avatar = o.optString("avatar").ifEmpty { null },
                members = (0 until m.length()).map { m.getJSONObject(it).let { x -> Member(x.getString("id"), x.optString("name")) } },
                admins = (0 until a.length()).map { a.getString(it) },
                v = o.optInt("v"), left = o.optBoolean("left"),
            )
        }
    }
}

/**
 * Канал, на який я підписаний (або власний). key — ключ шифрування (зашифрований канал),
 * priv — приватний ключ підпису (лише в автора), base64url скаляра d.
 */
data class ChannelRec(
    val id: String,
    val public: Boolean,
    val key: String? = null,
    val pub: String,
    val priv: String? = null,
    val name: String,
    val about: String = "",
    val avatar: String? = null,
    val lastSeq: Long = 0,
    val readSeq: Long = 0,
    val joined: Long = System.currentTimeMillis(),
) {
    val mine get() = priv != null

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("public", public); key?.let { put("key", it) }; put("pub", pub); priv?.let { put("priv", it) }
        put("name", name); put("about", about); avatar?.let { put("avatar", it) }
        put("lastSeq", lastSeq); put("readSeq", readSeq); put("joined", joined)
    }

    companion object {
        fun fromJson(s: String): ChannelRec? = runCatching { fromJson(JSONObject(s)) }.getOrNull()

        fun fromJson(o: JSONObject) = ChannelRec(
            id = o.getString("id"), public = o.optBoolean("public"), key = o.optString("key").ifEmpty { null },
            pub = o.getString("pub"), priv = o.optString("priv").ifEmpty { null },
            name = o.optString("name"), about = o.optString("about"), avatar = o.optString("avatar").ifEmpty { null },
            lastSeq = o.optLong("lastSeq"), readSeq = o.optLong("readSeq"), joined = o.optLong("joined"),
        )
    }
}
