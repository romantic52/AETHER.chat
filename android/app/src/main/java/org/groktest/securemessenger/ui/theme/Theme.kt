package org.groktest.securemessenger.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
fun SecureMessengerTheme(
    themeSettings: ThemeSettings,
    content: @Composable () -> Unit
) {
    // Тема выбирается пользователем (см. ThemePalettes / экран Кастомизации).
    val palette = ThemePalettes.byKey(themeSettings.themeKey.value)
    val isDark = palette.isDark
    val baseScheme = palette.scheme
    val colorScheme = if (themeSettings.customAccentEnabled.value) {
        val accent = ensureContrast(
            color = themeSettings.getAccentColor().copy(alpha = 1f),
            background = baseScheme.background,
            toward = baseScheme.onBackground,
        )
        val outgoing = lerp(baseScheme.tertiary, accent, 0.42f)
        baseScheme.copy(
            primary = accent,
            onPrimary = if (accent.luminance() > 0.179f) Color.Black else Color.White,
            tertiary = outgoing,
            onTertiary = if (outgoing.luminance() > 0.179f) Color.Black else Color.White,
        )
    } else {
        baseScheme
    }

    val fontFamilyStr = themeSettings.fontFamilyStyle.value
    val fontFamily = when (fontFamilyStr) {
        "Serif" -> androidx.compose.ui.text.font.FontFamily.Serif
        "SansSerif" -> androidx.compose.ui.text.font.FontFamily.SansSerif
        "Monospace" -> androidx.compose.ui.text.font.FontFamily.Monospace
        "Cursive" -> androidx.compose.ui.text.font.FontFamily.Cursive
        else -> androidx.compose.ui.text.font.FontFamily.Default
    }
    
    val typography = androidx.compose.runtime.remember(fontFamilyStr) {
        val base = androidx.compose.material3.Typography()
        androidx.compose.material3.Typography(
            displayLarge = base.displayLarge.copy(fontFamily = fontFamily),
            displayMedium = base.displayMedium.copy(fontFamily = fontFamily),
            displaySmall = base.displaySmall.copy(fontFamily = fontFamily),
            headlineLarge = base.headlineLarge.copy(fontFamily = fontFamily),
            headlineMedium = base.headlineMedium.copy(fontFamily = fontFamily),
            headlineSmall = base.headlineSmall.copy(fontFamily = fontFamily),
            titleLarge = base.titleLarge.copy(fontFamily = fontFamily),
            titleMedium = base.titleMedium.copy(fontFamily = fontFamily),
            titleSmall = base.titleSmall.copy(fontFamily = fontFamily),
            bodyLarge = base.bodyLarge.copy(fontFamily = fontFamily, lineHeight = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp)),
            bodyMedium = base.bodyMedium.copy(fontFamily = fontFamily, lineHeight = androidx.compose.ui.unit.TextUnit(20f, androidx.compose.ui.unit.TextUnitType.Sp)),
            bodySmall = base.bodySmall.copy(fontFamily = fontFamily, lineHeight = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp)),
            labelLarge = base.labelLarge.copy(fontFamily = fontFamily),
            labelMedium = base.labelMedium.copy(fontFamily = fontFamily),
            labelSmall = base.labelSmall.copy(fontFamily = fontFamily)
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = Shapes(
            extraSmall = AetherCompactShape,
            small = AetherCompactShape,
            medium = AetherCompactShape,
            large = aetherLargeShape(),
            extraLarge = aetherLargeShape(),
        ),
        typography = typography,
        content = content
    )
}

private fun ensureContrast(
    color: Color,
    background: Color,
    toward: Color,
    target: Float = 4.5f,
): Color {
    fun ratio(first: Color, second: Color): Float =
        (maxOf(first.luminance(), second.luminance()) + 0.05f) /
            (minOf(first.luminance(), second.luminance()) + 0.05f)

    if (ratio(color, background) >= target) return color
    var low = 0f
    var high = 1f
    repeat(12) {
        val middle = (low + high) / 2f
        if (ratio(lerp(color, toward, middle), background) >= target) {
            high = middle
        } else {
            low = middle
        }
    }
    return lerp(color, toward, high)
}
