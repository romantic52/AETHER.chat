package aether.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Телеграмная палитра: light по умолчанию, dark по системной теме. */
private val LightColors = lightColorScheme(
    primary = Color(0xFF3390EC),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7F3FF),
    onPrimaryContainer = Color(0xFF0F3E63),
    secondary = Color(0xFF707579),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFF4F4F5),
    onSurfaceVariant = Color(0xFF707579),
    outline = Color(0xFFDADCE0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6AB2F2),
    onPrimary = Color(0xFF0E1621),
    primaryContainer = Color(0xFF2B5278),
    onPrimaryContainer = Color(0xFFD6E7F7),
    secondary = Color(0xFFAAAAAA),
    background = Color(0xFF17212B),
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF17212B),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF232E3C),
    onSurfaceVariant = Color(0xFF8B98A5),
    outline = Color(0xFF101921),
)

@Composable
fun AetherTheme(
    mode: aether.desktop.data.UiSettings.ThemeMode = aether.desktop.data.UiSettings.ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        aether.desktop.data.UiSettings.ThemeMode.SYSTEM -> isSystemInDarkTheme()
        aether.desktop.data.UiSettings.ThemeMode.LIGHT -> false
        aether.desktop.data.UiSettings.ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
