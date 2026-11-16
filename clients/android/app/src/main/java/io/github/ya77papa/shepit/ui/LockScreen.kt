package io.github.ya77papa.shepit.ui

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ya77papa.shepit.R
import io.github.ya77papa.shepit.security.AppLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Цифрова клавіатура PIN-коду: крапки, цифри, «Стерти». Без значків. */
@Composable
fun PinPad(length: Int, enabled: Boolean = true, onComplete: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    val max = if (length > 0) length else 8
    LaunchedEffect(pin) {
        if (length > 0 && pin.length == length) { onComplete(pin); pin = "" }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.height(20.dp)) {
            repeat(if (length > 0) length else maxOf(pin.length, 4)) { i ->
                Box(Modifier.size(14.dp).clip(CircleShape).background(if (i < pin.length) Accent else Color(0xFF2A3A52)))
            }
        }
        Spacer(Modifier.height(28.dp))
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(if (length == 0) "OK" else "", "0", "Стерти"))
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { k ->
                    Box(
                        Modifier.size(72.dp).clip(CircleShape)
                            .background(if (k.length == 1) Color(0xFF1D2A3B) else Color.Transparent)
                            .clickable(enabled = enabled && k.isNotEmpty()) {
                                when (k) {
                                    "Стерти" -> pin = pin.dropLast(1)
                                    "OK" -> if (pin.length >= 4) { onComplete(pin); pin = "" }
                                    else -> if (pin.length < max) pin += k
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) { Text(k, fontSize = if (k.length == 1) 28.sp else 15.sp, color = if (k.length == 1) Color.White else Muted) }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

/** Екран розблокування поверх застосунку. */
@Composable
fun LockScreen(lock: AppLock, activity: Activity, onForgot: () -> Unit) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf("") }
    var wait by remember { mutableLongStateOf(lock.waitSeconds()) }
    var checking by remember { mutableStateOf(false) }
    var showPin by remember { mutableStateOf(!lock.bioEnabled || !lock.biometricAvailable()) }
    var forgot by remember { mutableStateOf(false) }

    fun bio() = lock.promptBiometric(activity, "Розблокувати Шепіт", onSuccess = { lock.unlock() }, onFallback = { showPin = lock.pinEnabled })
    LaunchedEffect(Unit) { if (!showPin) bio() }
    LaunchedEffect(wait) { while (wait > 0) { delay(1000); wait = lock.waitSeconds() } }

    Surface(color = Color(0xFF0B111A), modifier = Modifier.fillMaxSize().clickable(enabled = false) {}) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(32.dp))
            Image(painterResource(R.drawable.ic_launcher_fg), null, Modifier.size(84.dp))
            Text("Шепіт заблоковано", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    wait > 0 -> "Забагато спроб. Спробуй через ${if (wait >= 60) "${wait / 60} хв ${wait % 60} с" else "$wait с"}."
                    error.isNotEmpty() -> error
                    showPin -> "Введи PIN-код"
                    else -> "Приклади палець до сканера"
                },
                color = if (error.isNotEmpty() || wait > 0) Danger else Muted, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
            if (showPin && lock.pinEnabled) {
                PinPad(lock.pinLength, enabled = !checking && wait == 0L) { pin ->
                    checking = true
                    scope.launch {
                        val ok = withContext(Dispatchers.Default) { lock.checkPin(pin) }
                        checking = false
                        if (ok) lock.unlock() else { error = "Неправильний PIN-код"; wait = lock.waitSeconds() }
                    }
                }
            }
            if (lock.bioEnabled && lock.biometricAvailable()) {
                TextButton(onClick = { error = ""; bio() }) { Text("Розблокувати відбитком") }
            } else if (!lock.pinEnabled) {
                TextButton(onClick = { bio() }) { Text("Розблокувати") }
            }
            TextButton(onClick = { forgot = true }) { Text("Забув PIN-код?", color = Muted) }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (forgot) AlertDialog(
        onDismissRequest = { forgot = false },
        title = { Text("Забув PIN-код") },
        text = {
            Text("Скинути PIN неможливо — інакше блокування нічого не захищало б. Можна лише стерти акаунт з цього телефона й відновити його з резервної копії (файл і пароль до неї).")
        },
        confirmButton = { TextButton(onClick = { forgot = false; onForgot() }) { Text("Стерти акаунт з телефона", color = Danger) } },
        dismissButton = { TextButton(onClick = { forgot = false }) { Text("Скасувати") } },
    )
}
