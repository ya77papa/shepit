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
import io.github.ya77papa.shepit.ShepitApp
import io.github.ya77papa.shepit.service.ShepitService
import kotlinx.coroutines.launch

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

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkSystemBars()
        handleIntent(intent)
        setContent {
            ShepitTheme {
                AppUi(app, this, pendingInvite, openRequest, inviteCode, showUpdate, channelLink)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        i?.data?.toString()?.let { url ->
            io.github.ya77papa.shepit.data.Channels.parseLink(url)?.let { channelLink.value = it }
            app.repo.inviteCodeFrom(url)?.let { inviteCode.value = it }
            app.repo.parseInvite(url)?.let { pendingInvite.value = it }
        }
        i?.getStringExtra(EXTRA_PEER)?.let { openRequest.value = it }
        if (i?.getBooleanExtra(EXTRA_UPDATE, false) == true) showUpdate.value = true
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
        app.repo.scope.launch { runCatching { app.updater.checkIfDue() } }
    }

    fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun ignoringBattery(): Boolean = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    @Suppress("BatteryLife")
    fun askBattery() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    fun askFullScreen() {
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
