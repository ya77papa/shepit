package io.github.ya77papa.shepit.ui

import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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

@Composable
fun ShepitTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, content = content)

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
