package org.groktest.securemessenger.ui.theme

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

class ThemeSettings(context: Context) {
    private val prefs = context.getSharedPreferences("theme_settings", Context.MODE_PRIVATE)

    var isDarkTheme = mutableStateOf(prefs.getBoolean("is_dark", true))
    var isGlassEnabled = mutableStateOf(prefs.getBoolean("is_glass", true))
    // Ключ выбранной темы (см. ThemePalettes): graphite|rosepine|sakura|mono|pastel|daylight
    var themeKey = mutableStateOf(prefs.getString("theme_key", "graphite") ?: "graphite")
    var accentColorHex = mutableStateOf(prefs.getString("accent_color", "#CCFFFFFF") ?: "#CCFFFFFF")
    var backgroundImageUri = mutableStateOf(prefs.getString("bg_image_uri", null))
    var autoExtractColors = mutableStateOf(prefs.getBoolean("auto_extract_colors", true))
    var fontFamilyStyle = mutableStateOf(prefs.getString("font_family", "Default") ?: "Default")
    // "Classic" | "LiquidGlass"
    var designStyle = mutableStateOf(prefs.getString("design_style", "Classic") ?: "Classic")
    // Быстрая реакция по двойному тапу (по умолчанию сердечко)
    var quickReaction = mutableStateOf(prefs.getString("quick_reaction", "❤️") ?: "❤️")
    // --- Жидкое стекло (настоящий backdrop-blur) ---
    var liquidGlassEnabled = mutableStateOf(prefs.getBoolean("liquid_glass", false))
    var glassTransparency = mutableStateOf(prefs.getFloat("glass_transparency", 0.5f)) // 0=матовое, 1=прозрачное
    // --- Уведомления (читаются и в AetherService напрямую из этих же prefs) ---
    var notifSound = mutableStateOf(prefs.getBoolean("notif_sound", true))
    var notifVibration = mutableStateOf(prefs.getBoolean("notif_vibration", true))
    var notifPreviews = mutableStateOf(prefs.getBoolean("notif_previews", true))

    fun setDarkTheme(isDark: Boolean) {
        isDarkTheme.value = isDark
        prefs.edit().putBoolean("is_dark", isDark).apply()
    }

    fun setGlassEnabled(isGlass: Boolean) {
        isGlassEnabled.value = isGlass
        prefs.edit().putBoolean("is_glass", isGlass).apply()
    }

    /** Сменить тему: сохраняем ключ и синхронизируем флаг тёмной/светлой палитры. */
    fun setThemeKey(key: String) {
        themeKey.value = key
        val dark = ThemePalettes.byKey(key).isDark
        isDarkTheme.value = dark
        prefs.edit().putString("theme_key", key).putBoolean("is_dark", dark).apply()
    }

    fun setAccentColor(hex: String) {
        accentColorHex.value = hex
        prefs.edit().putString("accent_color", hex).apply()
    }

    fun setBackgroundImageUri(uri: String?) {
        backgroundImageUri.value = uri
        prefs.edit().putString("bg_image_uri", uri).apply()
    }

    fun setAutoExtractColors(auto: Boolean) {
        autoExtractColors.value = auto
        prefs.edit().putBoolean("auto_extract_colors", auto).apply()
    }

    fun setFontFamilyStyle(style: String) {
        fontFamilyStyle.value = style
        prefs.edit().putString("font_family", style).apply()
    }

    fun setDesignStyle(style: String) {
        designStyle.value = style
        prefs.edit().putString("design_style", style).apply()
    }

    fun setQuickReaction(emoji: String) {
        quickReaction.value = emoji
        prefs.edit().putString("quick_reaction", emoji).apply()
    }

    fun setLiquidGlassEnabled(on: Boolean) {
        liquidGlassEnabled.value = on
        prefs.edit().putBoolean("liquid_glass", on).apply()
    }

    fun setGlassTransparency(v: Float) {
        glassTransparency.value = v
        prefs.edit().putFloat("glass_transparency", v).apply()
    }

    fun setNotifSound(v: Boolean) { notifSound.value = v; prefs.edit().putBoolean("notif_sound", v).apply() }
    fun setNotifVibration(v: Boolean) { notifVibration.value = v; prefs.edit().putBoolean("notif_vibration", v).apply() }
    fun setNotifPreviews(v: Boolean) { notifPreviews.value = v; prefs.edit().putBoolean("notif_previews", v).apply() }

    /** Включён ли режим «жидкого стекла» (настоящий backdrop-blur поверх темы). */
    fun isLiquidGlass(): Boolean = liquidGlassEnabled.value

    fun getAccentColor(): Color {
        return try {
            Color(android.graphics.Color.parseColor(accentColorHex.value))
        } catch (e: Exception) {
            Color(0xCCFFFFFF)
        }
    }
}

val LocalThemeSettings = staticCompositionLocalOf<ThemeSettings> {
    error("No ThemeSettings provided")
}
