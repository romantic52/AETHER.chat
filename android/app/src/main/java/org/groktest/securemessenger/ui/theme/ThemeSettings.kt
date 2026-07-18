package org.groktest.securemessenger.ui.theme

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

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
    var surfaceTransparency = mutableStateOf(prefs.getFloat("surface_transparency", 0.22f))
    var edgeDimEnabled = mutableStateOf(prefs.getBoolean("edge_dim_enabled", true))
    var edgeDimStrength = mutableStateOf(prefs.getFloat("edge_dim_strength", 1f))
    var edgeDimLength = mutableStateOf(prefs.getFloat("edge_dim_length", 144f).coerceIn(112f, 240f))
    var experimentalAnimations = mutableStateOf(prefs.getBoolean("experimental_animations", true))
    var animationSpeed = mutableStateOf(prefs.getFloat("animation_speed", 1f))
    var motionIntensity = mutableStateOf(prefs.getFloat("motion_intensity", 1f))
    var reactionEffects = mutableStateOf(prefs.getBoolean("reaction_effects", true))
    var bubbleRadius = mutableStateOf(prefs.getFloat("bubble_radius", 22f))
    var bubbleTails = mutableStateOf(prefs.getBoolean("bubble_tails", true))
    var messageTextSize = mutableStateOf(prefs.getFloat("message_text_size", 16f))
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

    fun setSurfaceTransparency(v: Float) {
        surfaceTransparency.value = v.coerceIn(0f, 1f)
        prefs.edit().putFloat("surface_transparency", surfaceTransparency.value).apply()
    }

    fun setEdgeDimEnabled(v: Boolean) {
        edgeDimEnabled.value = v
        prefs.edit().putBoolean("edge_dim_enabled", v).apply()
    }

    fun setEdgeDimStrength(v: Float) {
        edgeDimStrength.value = v.coerceIn(0f, 1f)
        prefs.edit().putFloat("edge_dim_strength", edgeDimStrength.value).apply()
    }

    fun setEdgeDimLength(v: Float) {
        edgeDimLength.value = v.coerceIn(112f, 240f)
        prefs.edit().putFloat("edge_dim_length", edgeDimLength.value).apply()
    }

    fun setAnimationSpeed(v: Float) {
        animationSpeed.value = v.coerceIn(0f, 2f)
        prefs.edit().putFloat("animation_speed", animationSpeed.value).apply()
    }

    fun setExperimentalAnimations(v: Boolean) {
        if (v && animationSpeed.value <= 0.01f) setAnimationSpeed(1f)
        experimentalAnimations.value = v
        prefs.edit().putBoolean("experimental_animations", v).apply()
    }

    fun setMotionIntensity(v: Float) {
        motionIntensity.value = v.coerceIn(0f, 1.5f)
        prefs.edit().putFloat("motion_intensity", motionIntensity.value).apply()
    }

    fun setReactionEffects(v: Boolean) {
        reactionEffects.value = v
        prefs.edit().putBoolean("reaction_effects", v).apply()
    }

    fun motionDuration(baseMillis: Int): Int {
        val speed = animationSpeed.value
        return if (!animationsEnabled()) 0 else (baseMillis / speed).roundToInt().coerceAtLeast(1)
    }

    fun animationsEnabled(): Boolean = experimentalAnimations.value && animationSpeed.value > 0.01f

    fun motionDistance(base: Float): Float = if (animationsEnabled()) base * motionIntensity.value else 0f

    fun setBubbleRadius(v: Float) {
        bubbleRadius.value = v.coerceIn(8f, 30f)
        prefs.edit().putFloat("bubble_radius", bubbleRadius.value).apply()
    }

    fun setBubbleTails(v: Boolean) {
        bubbleTails.value = v
        prefs.edit().putBoolean("bubble_tails", v).apply()
    }

    fun setMessageTextSize(v: Float) {
        messageTextSize.value = v.coerceIn(14f, 20f)
        prefs.edit().putFloat("message_text_size", messageTextSize.value).apply()
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
