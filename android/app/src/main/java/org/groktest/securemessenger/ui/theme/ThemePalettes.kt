package org.groktest.securemessenger.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Готовые темы (палитры) по референсам пользователя:
 *  graphite — графитовый монохром (дефолт), rosepine — сине-лиловый «лес»,
 *  sakura — тёмная с розовым акцентом, mono — чёрно-белая,
 *  pastel — светлая пастельно-розовая, daylight — светлая нейтральная.
 *  tertiary = исходящие пузыри, surfaceVariant = входящие пузыри/поле ввода.
 */
data class AppPalette(
    val key: String,
    val title: String,
    val isDark: Boolean,
    val scheme: ColorScheme
)

private fun graphite() = AppPalette(
    "graphite", "Графит", true,
    darkColorScheme(
        primary = Color(0xFFC8CDD3), onPrimary = Color(0xFF0C0D0F),
        secondary = Color(0xFF8A9099), onSecondary = Color(0xFF0C0D0F),
        tertiary = Color(0xFF33363C), onTertiary = Color(0xFFECECEE),
        background = Color(0xFF0C0D0F), onBackground = Color(0xFFECECEE),
        surface = Color(0xFF1A1B1E), onSurface = Color(0xFFECECEE),
        surfaceVariant = Color(0xFF26282C), onSurfaceVariant = Color(0xFF8A9099),
        outline = Color(0xFF2A2C30),
        error = Color(0xFFEF5B5B), onError = Color(0xFF190B0D)
    )
)

private fun rosepine() = AppPalette(
    "rosepine", "Лес", true,
    darkColorScheme(
        primary = Color(0xFFC4A7E7), onPrimary = Color(0xFF191724),
        secondary = Color(0xFF9CCFD8), onSecondary = Color(0xFF191724),
        tertiary = Color(0xFF2A283E), onTertiary = Color(0xFFE0DEF4),
        background = Color(0xFF191724), onBackground = Color(0xFFE0DEF4),
        surface = Color(0xFF1F1D2E), onSurface = Color(0xFFE0DEF4),
        surfaceVariant = Color(0xFF26233A), onSurfaceVariant = Color(0xFF908CAA),
        outline = Color(0xFF403D52),
        error = Color(0xFFEB6F92), onError = Color(0xFF191724)
    )
)

private fun sakura() = AppPalette(
    "sakura", "Сакура", true,
    darkColorScheme(
        primary = Color(0xFFEC6F9A), onPrimary = Color(0xFF1A0E14),
        secondary = Color(0xFFF3A6C4), onSecondary = Color(0xFF1A0E14),
        tertiary = Color(0xFF3A2233), onTertiary = Color(0xFFF8E6EE),
        background = Color(0xFF15101A), onBackground = Color(0xFFF3E9EF),
        surface = Color(0xFF1E1622), onSurface = Color(0xFFF3E9EF),
        surfaceVariant = Color(0xFF2A1F2C), onSurfaceVariant = Color(0xFFB79AAA),
        outline = Color(0xFF3A2C36),
        error = Color(0xFFEF5B5B), onError = Color(0xFF190B0D)
    )
)

private fun mono() = AppPalette(
    "mono", "Моно", true,
    darkColorScheme(
        primary = Color(0xFFFFFFFF), onPrimary = Color(0xFF000000),
        secondary = Color(0xFFBFBFBF), onSecondary = Color(0xFF000000),
        tertiary = Color(0xFF2B2B2B), onTertiary = Color(0xFFF2F2F2),
        background = Color(0xFF0A0A0A), onBackground = Color(0xFFF2F2F2),
        surface = Color(0xFF151515), onSurface = Color(0xFFF2F2F2),
        surfaceVariant = Color(0xFF1F1F1F), onSurfaceVariant = Color(0xFF9A9A9A),
        outline = Color(0xFF333333),
        error = Color(0xFFEF5B5B), onError = Color(0xFF190B0D)
    )
)

private fun pastel() = AppPalette(
    "pastel", "Пастель", false,
    lightColorScheme(
        primary = Color(0xFFE0729B), onPrimary = Color(0xFFFFFFFF),
        secondary = Color(0xFFC98BA8), onSecondary = Color(0xFFFFFFFF),
        tertiary = Color(0xFFF8CFDF), onTertiary = Color(0xFF3B2A32),
        background = Color(0xFFFDF2F6), onBackground = Color(0xFF3B2A32),
        surface = Color(0xFFFFFFFF), onSurface = Color(0xFF3B2A32),
        surfaceVariant = Color(0xFFF6E3EC), onSurfaceVariant = Color(0xFF765966),
        outline = Color(0xFFE8CFDA),
        error = Color(0xFFD64560), onError = Color(0xFFFFFFFF)
    )
)

private fun daylight() = AppPalette(
    "daylight", "День", false,
    lightColorScheme(
        primary = Color(0xFF3A6FF7), onPrimary = Color(0xFFFFFFFF),
        secondary = Color(0xFF5B6470), onSecondary = Color(0xFFFFFFFF),
        tertiary = Color(0xFFD7E5FF), onTertiary = Color(0xFF1A1C20),
        background = Color(0xFFF4F5F7), onBackground = Color(0xFF1A1C20),
        surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1A1C20),
        surfaceVariant = Color(0xFFEAECEF), onSurfaceVariant = Color(0xFF60656B),
        outline = Color(0xFFDADDE1),
        error = Color(0xFFD93B4C), onError = Color(0xFFFFFFFF)
    )
)

private fun telegramNight() = AppPalette(
    "telegram_night", "Telegram Night", true,
    darkColorScheme(
        primary = Color(0xFF64B5EF), onPrimary = Color(0xFF07131C),
        secondary = Color(0xFF7EB8DA), onSecondary = Color(0xFF07131C),
        tertiary = Color(0xFF2B5278), onTertiary = Color(0xFFFFFFFF),
        background = Color(0xFF0E1621), onBackground = Color(0xFFF2F5F7),
        surface = Color(0xFF17212B), onSurface = Color(0xFFF2F5F7),
        surfaceVariant = Color(0xFF182533), onSurfaceVariant = Color(0xFF8E9BA7),
        outline = Color(0xFF3A4B5C),
        error = Color(0xFFEF5B5B), onError = Color(0xFF190B0D)
    )
)

private fun telegramDay() = AppPalette(
    "telegram_day", "Telegram Day", false,
    lightColorScheme(
        primary = Color(0xFF3390EC), onPrimary = Color.White,
        secondary = Color(0xFF5D7991), onSecondary = Color.White,
        tertiary = Color(0xFFE5F3FF), onTertiary = Color(0xFF17212B),
        background = Color(0xFFFFFFFF), onBackground = Color(0xFF17212B),
        surface = Color(0xFFFFFFFF), onSurface = Color(0xFF17212B),
        surfaceVariant = Color(0xFFF1F4F6), onSurfaceVariant = Color(0xFF626E78),
        outline = Color(0xFFD5DDE3),
        error = Color(0xFFE53935), onError = Color.White
    )
)

object ThemePalettes {
    val all: List<AppPalette> = listOf(
        telegramNight(), telegramDay(), graphite(), mono(), sakura(), rosepine(), pastel(), daylight()
    )

    fun byKey(key: String): AppPalette = all.firstOrNull { it.key == key } ?: all.first()
}
