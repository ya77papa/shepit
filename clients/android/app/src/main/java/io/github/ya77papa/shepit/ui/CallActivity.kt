package io.github.ya77papa.shepit.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.ya77papa.shepit.ShepitApp
import io.github.ya77papa.shepit.call.CallManager.State
import io.github.ya77papa.shepit.data.Content
import kotlinx.coroutines.delay

private val Green = Color(0xFF1DB954)
private val Red = Color(0xFFE5484D)

/** Екран дзвінка у звичному для телефона вигляді. Відкривається поверх блокування й будить екран. */
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
        handle(intent)
        setContent { ShepitTheme { CallScreen() } }
    }

    // Екран уже відкритий, а користувач натиснув «Прийняти» у сповіщенні.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_ACCEPT, false) == true) {
            i.removeExtra(EXTRA_ACCEPT)
            acceptWithMic()
        }
    }

    private fun acceptWithMic() {
        app.calls.trace("натиснуто «Прийняти»")
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
        LaunchedEffect(state) { if (state == State.IDLE) { delay(600); finish() } }
        val name = remember(peer) { if (peer.isEmpty()) "" else app.repo.nameOf(peer) }

        Column(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF1B2A41), Color(0xFF0B111A))))
                .systemBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, null, tint = Muted, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Шепіт · наскрізне шифрування", color = Muted, fontSize = 13.sp)
            }
            Spacer(Modifier.height(48.dp))
            Avatar(name, ringing = state == State.INCOMING || state == State.OUTGOING)
            Spacer(Modifier.height(24.dp))
            Text(name, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Text(
                when (state) {
                    State.OUTGOING -> "Виклик…"
                    State.INCOMING -> "Вхідний голосовий дзвінок"
                    State.CONNECTING -> "З'єднання…"
                    State.ACTIVE -> Content.fmtDuration(now - startedAt)
                    State.IDLE -> "Дзвінок завершено"
                },
                color = if (state == State.ACTIVE) Color.White else Muted, fontSize = 18.sp,
            )
            if (state == State.ACTIVE && relayed) {
                Spacer(Modifier.height(4.dp))
                Text("через ретранслятор — твою IP-адресу не видно", color = Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))

            if (state == State.INCOMING) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundAction(Icons.Default.CallEnd, "Відхилити", Red, 76.dp) { calls.reject() }
                    RoundAction(Icons.Default.Call, "Прийняти", Green, 76.dp, pulse = true) { acceptWithMic() }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ToggleAction(if (muted) Icons.Default.MicOff else Icons.Default.Mic, if (muted) "Мікрофон вимк." else "Мікрофон", muted) { calls.toggleMute() }
                    ToggleAction(if (speaker) Icons.AutoMirrored.Filled.VolumeUp else Icons.Default.PhoneInTalk, if (speaker) "Гучний" else "До вуха", speaker) { calls.toggleSpeaker() }
                }
                Spacer(Modifier.height(36.dp))
                RoundAction(Icons.Default.CallEnd, if (state == State.OUTGOING) "Скасувати" else "Завершити", Red, 76.dp) { calls.hangup() }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    companion object {
        const val EXTRA_ACCEPT = "accept"
    }
}

private val avatarColors = listOf(0xFF5B8DEF, 0xFFE0884B, 0xFF4BB38A, 0xFFB46BD6, 0xFFD6577A, 0xFF4BA3C7).map { Color(it) }

@Composable
private fun Avatar(name: String, ringing: Boolean) {
    val initials = name.split(" ", "-").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }
    val color = avatarColors[(name.hashCode() and 0x7fffffff) % avatarColors.size]
    val t = rememberInfiniteTransition(label = "ring")
    val wave by t.animateFloat(1f, 1.35f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "wave")
    val waveAlpha by t.animateFloat(0.35f, 0f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "alpha")
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(180.dp)) {
        if (ringing) Box(Modifier.size(128.dp).scale(wave).clip(CircleShape).background(color.copy(alpha = waveAlpha)))
        Box(Modifier.size(128.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Text(initials, color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, color: Color, size: Dp, pulse: Boolean = false, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "pulse")
    val s by t.animateFloat(1f, if (pulse) 1.08f else 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "s")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size).scale(s).clip(CircleShape).background(color).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(size * 0.45f)) }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun ToggleAction(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(64.dp).clip(CircleShape)
                .background(if (on) Color.White else Color.White.copy(alpha = 0.12f))
                .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = if (on) Color(0xFF0B111A) else Color.White, modifier = Modifier.size(28.dp)) }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
    }
}
