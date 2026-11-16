package io.github.ya77papa.shepit.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.ya77papa.shepit.ShepitApp
import io.github.ya77papa.shepit.call.CallManager.State
import io.github.ya77papa.shepit.data.Content
import kotlinx.coroutines.delay

/** Екран дзвінка. Відкривається поверх блокування й будить екран. */
class CallActivity : ComponentActivity() {
    private val app get() = application as ShepitApp

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) app.calls.accept() else app.calls.reject()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (intent.getBooleanExtra(EXTRA_ACCEPT, false)) acceptWithMic()
        setContent { ShepitTheme { CallScreen() } }
    }

    private fun acceptWithMic() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) app.calls.accept()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    @Composable
    private fun CallScreen() {
        val calls = app.calls
        val state by calls.state.collectAsState()
        val peer by calls.peer.collectAsState()
        val muted by calls.muted.collectAsState()
        val speaker by calls.speaker.collectAsState()
        val startedAt by calls.startedAt.collectAsState()
        val relayed by calls.relayed.collectAsState()
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(500) } }
        LaunchedEffect(state) { if (state == State.IDLE) { delay(300); finish() } }
        val name = remember(peer) { if (peer.isEmpty()) "" else app.repo.nameOf(peer) }

        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp).systemBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Text("📞", fontSize = 64.sp)
            Spacer(Modifier.height(12.dp))
            Text(name, fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                when (state) {
                    State.OUTGOING -> "Дзвоню…"
                    State.INCOMING -> "Вхідний дзвінок"
                    State.CONNECTING -> "З'єднання…"
                    State.ACTIVE -> Content.fmtDuration(now - startedAt)
                    State.IDLE -> "Завершено"
                },
                color = Muted, fontSize = 18.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "🔒 Наскрізне шифрування · " + if (relayed) "через ретранслятор — твою IP-адресу не видно" else "пряме з'єднання",
                color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
            if (state == State.INCOMING) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Button(onClick = { calls.reject() }, colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Color.White), modifier = Modifier.height(64.dp)) {
                        Text("Відхилити", fontSize = 18.sp)
                    }
                    Button(onClick = { acceptWithMic() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E9E5B), contentColor = Color.White), modifier = Modifier.height(64.dp)) {
                        Text("Прийняти", fontSize = 18.sp)
                    }
                }
            } else {
                if (state == State.ACTIVE || state == State.CONNECTING) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalButton(onClick = { calls.toggleMute() }) { Text(if (muted) "🔇 Мікрофон вимк." else "🎙 Мікрофон") }
                        FilledTonalButton(onClick = { calls.toggleSpeaker() }) { Text(if (speaker) "🔊 Гучний" else "📱 До вуха") }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                Button(onClick = { calls.hangup() }, colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Color.White), modifier = Modifier.height(64.dp).fillMaxWidth(0.7f)) {
                    Text(if (state == State.OUTGOING) "Скасувати" else "Завершити", fontSize = 18.sp)
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    companion object {
        const val EXTRA_ACCEPT = "accept"
    }
}
