package io.github.ya77papa.shepit.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.ya77papa.shepit.ShepitApp
import io.github.ya77papa.shepit.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Тримає з'єднання з сервером, поки застосунок закритий: вхідні дзвінки й повідомлення
 * приходять одразу, без сервісів Google. Показує тихе постійне сповіщення «Шепіт на зв'язку».
 */
class ShepitService : Service() {
    private val jobs = mutableListOf<Job>()
    private var micOn = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.hasExtra(EXTRA_MIC) == true) micOn = intent.getBooleanExtra(EXTRA_MIC, false)
        // Під час розмови сервіс отримує ще й тип «мікрофон», щоб голос не зникав,
        // коли екран дзвінка згорнуто.
        val base = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0
        val withMic = if (micOn && Build.VERSION.SDK_INT >= 30) base or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else base
        runCatching { ServiceCompat.startForeground(this, Notifications.ID_SERVICE, Notifications.service(this), withMic) }
            .onFailure { ServiceCompat.startForeground(this, Notifications.ID_SERVICE, Notifications.service(this), base) }
        if (jobs.isEmpty()) {
            val app = application as ShepitApp
            val repo = app.repo
            jobs += repo.scope.launch {
                if (repo.load()) repo.listenForever()
            }
            jobs += repo.scope.launch {
                // Запасний варіант і поповнення ключів.
                while (true) {
                    runCatching { if (repo.load()) { repo.ensureKeys(); repo.receive() } }
                    checkUpdate()
                    delay(15 * 60 * 1000L)
                }
            }
            jobs += repo.scope.launch {
                repo.incoming.collect { m ->
                    if (!MainActivity.visible || MainActivity.openPeer != m.peer) {
                        Notifications.showMessage(this@ShepitService, repo.nameOf(m.peer), m.peer, m.text)
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        super.onDestroy()
    }

    /** Раз на 12 год: є нова версія — сповістити (один раз на версію), якщо застосунок не відкритий. */
    private suspend fun checkUpdate() {
        val app = application as ShepitApp
        val rel = runCatching { app.updater.checkIfDue() }.getOrNull() ?: return
        if (MainActivity.visible || app.repo.db.get("update_notified") == rel.versionName) return
        app.repo.db.set("update_notified", rel.versionName)
        Notifications.showUpdate(this, rel.versionName)
    }

    companion object {
        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, ShepitService::class.java)) }
        }

        /** Увімкнути/вимкнути тип «мікрофон» на час розмови. */
        fun mic(ctx: Context, on: Boolean) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, ShepitService::class.java).putExtra(EXTRA_MIC, on)) }
        }

        private const val EXTRA_MIC = "mic"
    }
}

/** Після перезавантаження телефона — знову на зв'язку. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if ((ctx.applicationContext as ShepitApp).repo.hasAccount()) ShepitService.start(ctx)
        }
    }
}

/** Кнопка «Відхилити» у сповіщенні про дзвінок. */
class CallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == DECLINE) (ctx.applicationContext as ShepitApp).calls.reject()
    }

    companion object {
        const val DECLINE = "io.github.ya77papa.shepit.DECLINE"
    }
}
