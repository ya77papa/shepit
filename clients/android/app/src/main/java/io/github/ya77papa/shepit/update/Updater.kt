package io.github.ya77papa.shepit.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.github.ya77papa.shepit.BuildConfig
import io.github.ya77papa.shepit.data.Db
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Оновлення застосунку з GitHub Releases.
 *
 * Безпека: файл звіряється з SHA-256, який публікує GitHub для релізу, а Android
 * встановить оновлення лише якщо воно підписане тим самим ключем, що й встановлена версія.
 * Перевірка звертається до api.github.com (GitHub бачить лише IP-адресу, без даних акаунта).
 */
class Updater(private val ctx: Context, private val db: Db) {
    data class Release(val versionName: String, val versionCode: Int, val url: String, val size: Long, val sha256: String, val page: String)

    val available = MutableStateFlow<Release?>(null)
    /** null — не завантажується; 0..1 — прогрес. */
    val progress = MutableStateFlow<Float?>(null)
    val error = MutableStateFlow("")

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    var auto: Boolean
        get() = db.get("update_auto") != "0"
        set(v) = db.set("update_auto", if (v) "1" else "0")

    /** Автоматична перевірка — не частіше ніж раз на 12 год. */
    suspend fun checkIfDue(): Release? {
        if (!auto) return null
        val last = db.get("update_checked")?.toLongOrNull() ?: 0
        if (System.currentTimeMillis() - last < 12 * 3600_000L) return available.value
        return runCatching { check() }.getOrNull()
    }

    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://api.github.com/repos/$REPO/releases?per_page=10")
            .header("Accept", "application/vnd.github+json").build()
        val json = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("GitHub: ${r.code}")
            r.body.string()
        }
        db.set("update_checked", System.currentTimeMillis().toString())
        available.value = newest(json, BuildConfig.VERSION_CODE)
        available.value
    }

    /** Чи дозволено Шепоту встановлювати застосунки (одноразовий дозвіл у налаштуваннях Android). */
    fun canInstall() = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(from: Context) {
        from.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Завантажити, перевірити контрольну суму і передати системі на встановлення. */
    suspend fun downloadAndInstall(rel: Release) = withContext(Dispatchers.IO) {
        error.value = ""
        progress.value = 0f
        try {
            val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "shepit-${rel.versionName}.apk")
            val md = MessageDigest.getInstance("SHA-256")
            http.newCall(Request.Builder().url(rel.url).build()).execute().use { r ->
                if (!r.isSuccessful) error("Завантаження: ${r.code}")
                val total = r.body.contentLength().takeIf { it > 0 } ?: rel.size
                r.body.byteStream().use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            md.update(buf, 0, n)
                            done += n
                            if (total > 0) progress.value = (done.toFloat() / total).coerceAtMost(1f)
                        }
                    }
                }
            }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (!got.equals(rel.sha256, ignoreCase = true)) {
                file.delete()
                error("Контрольна сума файлу не збігається — оновлення скасовано")
            }
            install(file)
        } catch (e: Exception) {
            error.value = e.message ?: "Не вдалося оновити"
            throw e
        } finally {
            progress.value = null
        }
    }

    private fun install(file: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("shepit.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val intent = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java), flags)
            s.commit(intent.intentSender)
        }
    }

    companion object {
        const val REPO = "ya77papa/shepit"

        /** Найновіший Android-реліз з відповіді GitHub, якщо він новіший за [current]. */
        fun newest(json: String, current: Int): Release? {
            val list = JSONArray(json)
            for (i in 0 until list.length()) {
                val o = list.getJSONObject(i)
                val tag = o.optString("tag_name")
                if (o.optBoolean("draft") || o.optBoolean("prerelease") || !tag.startsWith("android-v")) continue
                val name = tag.removePrefix("android-v")
                // Номер версії = номер збірки в CI = останнє число версії (0.1.N).
                val code = name.substringAfterLast('.').toIntOrNull() ?: continue
                val assets = o.getJSONArray("assets")
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk") } ?: continue
                val sha = apk.optString("digest").removePrefix("sha256:").takeIf { it.length == 64 }
                    ?: Regex("\\b[0-9a-f]{64}\\b").find(o.optString("body"))?.value
                    ?: continue
                val rel = Release(name, code, apk.getString("browser_download_url"), apk.optLong("size"), sha, o.optString("html_url"))
                return rel.takeIf { it.versionCode > current }
            }
            return null
        }
    }
}

/** Відповідь системного встановлювача: попросити підтвердження або показати помилку. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { ctx.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "код $status"
                (ctx.applicationContext as? io.github.ya77papa.shepit.ShepitApp)?.updater?.error?.value =
                    if (status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE)
                        "Оновлення не встановлено: підпис не збігається з встановленою версією ($msg)"
                    else "Оновлення не встановлено: $msg"
            }
        }
    }
}
