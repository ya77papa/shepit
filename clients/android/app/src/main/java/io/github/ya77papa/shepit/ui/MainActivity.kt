package io.github.ya77papa.shepit.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import io.github.ya77papa.shepit.BuildConfig
import io.github.ya77papa.shepit.ShepitApp
import io.github.ya77papa.shepit.service.ShepitService
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle

class MainActivity : ComponentActivity() {
    private val app get() = application as ShepitApp

    /** Запрошення, з яким відкрили застосунок (посилання #add=…). */
    private val pendingInvite = mutableStateOf<Pair<String, String?>?>(null)
    private val openRequest = mutableStateOf<String?>(null)
    /** Код запрошення з посилання (#i=…) — для реєстрації на сервері «за запрошенням». */
    private val inviteCode = mutableStateOf<String?>(null)
    /** Показати вікно оновлення (з сповіщення «Доступна нова версія»). */
    private val showUpdate = mutableStateOf(false)
    /** Посилання на канал (#c=…&k=…). */
    private val channelLink = mutableStateOf<Pair<String, String?>?>(null)
    /** Текст, яким поділились з іншого застосунку (ACTION_SEND). */
    private val shared = mutableStateOf<String?>(null)

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkSystemBars()
        handleIntent(intent)
        applySecureFlag()
        // Поки застосунок на екрані й розблокований: «я тут» раз на хвилину, статуси контактів — раз на 30 с.
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                var n = 0
                while (true) {
                    if (!app.lock.locked.value && app.repo.hasAccount() && runCatching { app.repo.load() }.getOrDefault(false)) {
                        if (n++ % 2 == 0) app.repo.presenceBeat(online = true)
                        app.repo.refreshPresence()
                    }
                    kotlinx.coroutines.delay(30_000)
                }
            }
        }
        setContent {
            ShepitTheme {
                val locked by app.lock.locked.collectAsState()
                androidx.compose.foundation.layout.Box {
                    AppUi(app, this@MainActivity, pendingInvite, openRequest, inviteCode, showUpdate, channelLink, shared)
                    if (locked && app.repo.hasAccount()) LockScreen(app.lock, this@MainActivity) {
                        app.repo.wipe()
                        app.lock.clearPin(); app.lock.bioEnabled = false; app.lock.unlock()
                        stopService(Intent(this@MainActivity, ShepitService::class.java))
                        recreate()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND && i.type?.startsWith("text/") == true) {
            val subject = i.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
            val text = i.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            val all = listOf(subject, text).filter { it.isNotEmpty() }.distinct().joinToString("\n")
            if (all.isNotEmpty()) shared.value = all.take(4000)
            return
        }
        i?.data?.toString()?.let { url ->
            io.github.ya77papa.shepit.data.Channels.parseLink(url)?.let { channelLink.value = it }
            app.repo.inviteCodeFrom(url)?.let { inviteCode.value = it }
            app.repo.parseInvite(url)?.let { pendingInvite.value = it }
        }
        i?.getStringExtra(EXTRA_PEER)?.let { openRequest.value = it }
        if (i?.getBooleanExtra(EXTRA_UPDATE, false) == true) showUpdate.value = true
    }

    override fun onStart() {
        super.onStart()
        app.lock.onForeground()
        applySecureFlag()
        if (app.repo.hasAccount()) {
            promptUpdate()
            // Без фонової роботи служба живе лише поки застосунок на екрані.
            if (!app.repo.backgroundWork) ShepitService.start(this)
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            app.lock.onBackground()
            app.repo.scope.launch { app.repo.presenceBeat(online = false) } // «згорнув»
            // Фонову роботу вимкнено: зупинити службу, щойно застосунок пішов з екрана (не під час дзвінка).
            if (!app.repo.backgroundWork && app.calls.state.value == io.github.ya77papa.shepit.call.CallManager.State.IDLE) {
                stopService(android.content.Intent(this, ShepitService::class.java))
            }
        }
        super.onStop()
    }

    /** Приховувати вміст у списку застосунків і на скріншотах — якщо увімкнено в налаштуваннях. */
    fun applySecureFlag() {
        if (app.lock.secureScreen) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onResume() {
        super.onResume()
        visible = true
        if (app.repo.hasAccount()) onAccountReady()
    }

    override fun onPause() {
        visible = false
        super.onPause()
    }

    /** Акаунт є: запустити з'єднання, попросити дозволи. */
    fun onAccountReady() {
        ShepitService.start(this)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        app.repo.scope.launch { runCatching { app.repo.load(); app.repo.receive() } }
        promptUpdate()
    }

    /**
     * Є нова версія — показати той самий діалог, що й «Перевірити оновлення» в налаштуваннях.
     * Не частіше ніж раз на добу для тієї самої версії (щоб «Пізніше» не набридало); вимкнене
     * автооновлення в налаштуваннях вимикає й це.
     */
    private fun promptUpdate() {
        app.repo.scope.launch {
            val rel = runCatching { app.updater.checkIfDue() }.getOrNull() ?: return@launch
            if (rel.versionCode <= BuildConfig.VERSION_CODE) return@launch
            val db = app.repo.db
            val (code, at) = (db.get("update_prompted") ?: "0:0").split(':').let { (it.getOrNull(0)?.toIntOrNull() ?: 0) to (it.getOrNull(1)?.toLongOrNull() ?: 0L) }
            val now = System.currentTimeMillis()
            if (code == rel.versionCode && now - at < 24 * 3600_000L) return@launch
            runOnUiThread {
                // Заблоковано PIN-кодом чи вже згорнули — покажемо наступного разу.
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) && !app.lock.locked.value) {
                    db.set("update_prompted", "${rel.versionCode}:$now")
                    showUpdate.value = true
                }
            }
        }
    }

    fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun ignoringBattery(): Boolean = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    @Suppress("BatteryLife")
    fun askBattery() {
        app.lock.expectReturn()
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    fun askFullScreen() {
        app.lock.expectReturn()
        if (Build.VERSION.SDK_INT >= 34) runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
        }
    }

    companion object {
        const val EXTRA_PEER = "peer"
        const val EXTRA_UPDATE = "update"
        @Volatile var visible = false
        @Volatile var openPeer: String? = null
    }
}
