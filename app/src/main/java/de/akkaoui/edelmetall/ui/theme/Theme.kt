package de.akkaoui.edelmetall.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import de.akkaoui.edelmetall.data.Metal
import de.akkaoui.edelmetall.data.ThemeMode

/** true, wenn gerade das dunkle Design aktiv ist. */
val LocalDarkTheme = staticCompositionLocalOf { true }

val Gain: Color
    @Composable @ReadOnlyComposable get() = if (LocalDarkTheme.current) Color(0xFF4CC38A) else Color(0xFF1E8A57)

val Loss: Color
    @Composable @ReadOnlyComposable get() = if (LocalDarkTheme.current) Color(0xFFE5675F) else Color(0xFFC0392B)

/** Metallfarbe, im hellen Design etwas dunkler, damit Linien und Punkte gut sichtbar bleiben. */
@Composable
@ReadOnlyComposable
fun metalColor(metal: Metal): Color {
    val base = Color(metal.color)
    return if (LocalDarkTheme.current) base else lerp(base, Color.Black, if (metal == Metal.GOLD) 0.18f else 0.42f)
}

private val DarkColors = darkColorScheme(
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
    surfaceContainerHighest = Color(0xFF262A31),
    outline = Color(0xFF3A3F48),
    outlineVariant = Color(0xFF2A2E35),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF8C6B12),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF6E7B8),
    onPrimaryContainer = Color(0xFF2E2200),
    secondary = Color(0xFF5B5F67),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4E5E9),
    onSecondaryContainer = Color(0xFF1B1D21),
    background = Color(0xFFF6F5F2),
    onBackground = Color(0xFF1A1B1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1B1E),
    surfaceVariant = Color(0xFFECEBE7),
    onSurfaceVariant = Color(0xFF5E6168),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF0EEE9),
    surfaceContainerHighest = Color(0xFFE8E6E0),
    outline = Color(0xFFC9C7C1),
    outlineVariant = Color(0xFFE2E0DA),
)

@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun EdelmetallTheme(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
    }
}
