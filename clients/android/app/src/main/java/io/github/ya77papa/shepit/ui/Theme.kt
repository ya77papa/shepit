package io.github.ya77papa.shepit.ui

import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val Accent = Color(0xFFF5C542)
val Muted = Color(0xFF8A9BB0)
val Warn = Color(0xFFE0B45A)
val Danger = Color(0xFFB4464B)
val OutBubble = Color(0xFF24456B)
val InBubble = Color(0xFF1D2A3B)

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF1F2A10),
    background = Color(0xFF0F1722),
    onBackground = Color(0xFFE6EDF5),
    surface = Color(0xFF16202E),
    onSurface = Color(0xFFE6EDF5),
    surfaceVariant = Color(0xFF243246),
    onSurfaceVariant = Color(0xFFB8C6D6),
    secondaryContainer = Color(0xFF243246),
    onSecondaryContainer = Color(0xFFE6EDF5),
    error = Color(0xFFFF7A7A),
)

/** Поля введення й меню — помітно заокруглені. */
private val shapes = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
)

/** Спокійна (не яскрава) кнопка для частих дій, напр. «Запросити друга». */
val CalmButton = Color(0xFF2A3A52)

@Composable
fun ShepitTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, shapes = shapes, content = content)

/**
 * Інтерфейс завжди темний, тож значки системних смуг (час, батарея) — завжди світлі.
 * Типовий enableEdgeToEdge() бере тему телефона: на світлій темі значки темні й зливаються з тлом.
 */
fun androidx.activity.ComponentActivity.darkSystemBars() {
    val transparent = android.graphics.Color.TRANSPARENT
    enableEdgeToEdge(
        statusBarStyle = androidx.activity.SystemBarStyle.dark(transparent),
        navigationBarStyle = androidx.activity.SystemBarStyle.dark(transparent),
    )
}

/** Зелена кулька «в мережі». */
val OnlineGreen = androidx.compose.ui.graphics.Color(0xFF3FB950)
