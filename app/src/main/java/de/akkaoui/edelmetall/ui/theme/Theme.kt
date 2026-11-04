package de.akkaoui.edelmetall.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Gain = Color(0xFF4CC38A)
val Loss = Color(0xFFE5675F)

private val Colors = darkColorScheme(
    primary = Color(0xFFD4AF37),
    onPrimary = Color(0xFF1C1500),
    primaryContainer = Color(0xFF3A3012),
    onPrimaryContainer = Color(0xFFF1D27A),
    secondary = Color(0xFFC9CCD3),
    onSecondary = Color(0xFF15171B),
    secondaryContainer = Color(0xFF2B2F37),
    onSecondaryContainer = Color(0xFFE3E5EA),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFECEDEF),
    surface = Color(0xFF15181D),
    onSurface = Color(0xFFECEDEF),
    surfaceVariant = Color(0xFF20242B),
    onSurfaceVariant = Color(0xFFA6ACB6),
    surfaceContainer = Color(0xFF181B21),
    surfaceContainerHigh = Color(0xFF1E2128),
    outline = Color(0xFF3A3F48),
    outlineVariant = Color(0xFF2A2E35),
)

@Composable
fun EdelmetallTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
