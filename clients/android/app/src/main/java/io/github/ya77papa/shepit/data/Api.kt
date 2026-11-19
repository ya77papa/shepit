package io.github.ya77papa.shepit.data

import io.github.ya77papa.shepit.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class HttpException(val code: Int, message: String) : IOException(message)

/** Сервер Шепоту. Тут лише транспорт; підписи й шифрування робить Rust-ядро. */
class Api(private val base: String = BuildConfig.SERVER) {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Для потоку подій: без тайм-ауту читання (сервер шле keep-alive кожні 20 с). */
    val streaming: OkHttpClient = client.newBuilder().readTimeout(90, TimeUnit.SECONDS).build()

    private val json = "application/json".toMediaType()

    /** Канали: окремий клієнт з підписом ключем каналу. */
    val channels by lazy { Channels.Client(base, client) }

    /**
     * Різниця між годинником сервера і телефона (мс). Позначки часу дзвінків рахуємо за
     * годинником сервера, щоб розбіжність годинників телефона й комп'ютера не ламала дзвінки.
     */
    @Volatile var clockOffset = 0L
        private set

    fun now() = System.currentTimeMillis() + clockOffset

    fun noteDate(r: Response) {
        val d = r.headers.getDate("Date") ?: return
        // Date має точність до секунди: беремо середину секунди.
        clockOffset = d.time + 500 - System.currentTimeMillis()
    }

    fun url(path: String) = base + path

    /** Сервер відмовив (403 з кодом причини, напр. not-registered). */
    @Volatile var onRefusal: ((String) -> Unit)? = null

    private fun exec(req: Request): Response {
        val r = client.newCall(req).execute()
        noteDate(r)
        if (!r.isSuccessful) {
            val code = r.code
            val why = if (code == 403) runCatching { r.body.string().trim() }.getOrDefault("") else ""
            r.close()
            if (why.isNotEmpty()) onRefusal?.invoke(why)
            throw HttpException(
                code,
                when {
                    code == 401 -> "Сервер відхилив підпис — перевір час на телефоні"
                    REFUSALS.containsKey(why) -> REFUSALS.getValue(why)
                    else -> "${req.method} ${req.url.encodedPath}: $code"
                },
            )
        }
        return r
    }

    fun get(path: String, auth: String? = null): String =
        exec(Request.Builder().url(url(path)).apply { if (auth != null) header(AUTH, auth) }.build()).use { it.body.string() }

    /** GET, що повертає null на 404. */
    fun getOrNull(path: String, auth: String? = null): String? = try {
        get(path, auth)
    } catch (e: HttpException) {
        if (e.code == 404) null else throw e
    }

    fun send(method: String, path: String, body: ByteArray, auth: String? = null, extra: Map<String, String> = emptyMap()) {
        exec(
            Request.Builder().url(url(path)).method(method, body.toRequestBody(json))
                .apply { if (auth != null) header(AUTH, auth); extra.forEach { (k, v) -> header(k, v) } }.build(),
        ).close()
    }

    /** POST з відповіддю. */
    fun delete(path: String, auth: String? = null) {
        exec(Request.Builder().url(url(path)).delete().apply { if (auth != null) header(AUTH, auth) }.build()).close()
    }

    fun post(path: String, body: ByteArray, auth: String? = null): String =
        exec(Request.Builder().url(url(path)).post(body.toRequestBody(json)).apply { if (auth != null) header(AUTH, auth) }.build())
            .use { it.body.string() }

    companion object {
        const val AUTH = "x-shepit-auth"
        const val INVITE = "x-shepit-invite"

        /** Пояснення відмов сервера при реєстрації. */
        val REFUSALS = mapOf(
            "registration-closed" to "Реєстрацію нових користувачів на цьому сервері зараз закрито.",
            "invite-required" to "На цьому сервері реєстрація лише за запрошенням. Попроси посилання в того, хто вже користується Шепотом, або введи код запрошення.",
            "invite-invalid" to "Код запрошення недійсний: можливо, його вже використано, відкликано або минув термін дії.",
            "account-blocked" to "Адміністратор сервера видалив цей акаунт. Надсилати повідомлення з нього більше не можна.",
            "not-registered" to "Акаунт не зареєстровано на сервері (можливо, його видалив адміністратор).",
        )

        private val CODE_ALPHABET = Regex("^[ABCDEFGHJKMNPQRSTUVWXYZ2-9]{12}$")

        /** Код запрошення у вигляді XXXX-XXXX-XXXX (або null). */
        fun normalizeInviteCode(input: String?): String? {
            val raw = input.orEmpty().uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }
            return if (CODE_ALPHABET.matches(raw)) "${raw.take(4)}-${raw.substring(4, 8)}-${raw.substring(8)}" else null
        }
    }
}
