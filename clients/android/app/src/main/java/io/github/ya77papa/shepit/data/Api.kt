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

    private fun exec(req: Request): Response {
        val r = client.newCall(req).execute()
        noteDate(r)
        if (!r.isSuccessful) {
            val code = r.code
            r.close()
            throw HttpException(code, if (code == 401) "Сервер відхилив підпис — перевір час на телефоні" else "${req.method} ${req.url.encodedPath}: $code")
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

    fun send(method: String, path: String, body: ByteArray, auth: String? = null) {
        exec(
            Request.Builder().url(url(path)).method(method, body.toRequestBody(json))
                .apply { if (auth != null) header(AUTH, auth) }.build(),
        ).close()
    }

    companion object {
        const val AUTH = "x-shepit-auth"
    }
}
