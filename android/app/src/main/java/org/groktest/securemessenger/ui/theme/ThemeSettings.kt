package org.groktest.securemessenger.ui.theme

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

const val MAX_SURFACE_TRANSPARENCY = 0.6f
const val MAX_GLASS_BLUR_RADIUS = 24f

class ThemeSettings(context: Context) {
    private val prefs = context.getSharedPreferences("theme_settings", Context.MODE_PRIVATE)
    private fun floatPref(key: String, default: Float, range: ClosedFloatingPointRange<Float>): Float =
        prefs.getFloat(key, default).takeIf { it.isFinite() }?.coerceIn(range) ?: default

    private val initialGlassClarity = floatPref("glass_transparency", 0.5f, 0f..1f)
    private val initialAnimationSpeed = floatPref("animation_speed", 1f, 1f..2f).also {
        prefs.edit().putFloat("animation_speed", it).apply()
    }
    private val initialLiquidGlass = if (!prefs.getBoolean("render_performance_v1", false)) {
        prefs.edit()
            .putBoolean("liquid_glass", false)
            .putBoolean("render_performance_v1", true)
            .apply()
        false
    } else {
        prefs.getBoolean("liquid_glass", false)
    }

    init {
        if (!prefs.getBoolean("round_shapes_v1", false)) {
            prefs.edit()
                .remove("panel_radius")
                .remove("control_radius")
                .remove("field_radius")
                .remove("bubble_radius")
                .putBoolean("round_shapes_v1", true)
                .apply()
        }
    }

    // Ключ выбранной темы (см. ThemePalettes): graphite|rosepine|sakura|mono|pastel|daylight
    var themeKey = mutableStateOf(prefs.getString("theme_key", "graphite") ?: "graphite")
    var accentColorHex = mutableStateOf(prefs.getString("accent_color", "#CCFFFFFF") ?: "#CCFFFFFF")
    var customAccentEnabled = mutableStateOf(prefs.getBoolean("custom_accent_enabled", false))
    var backgroundImageUri = mutableStateOf(prefs.getString("bg_image_uri", null))
    var fontFamilyStyle = mutableStateOf(prefs.getString("font_family", "Default") ?: "Default")
    // Быстрая реакция по двойному тапу (по умолчанию сердечко)
    var quickReaction = mutableStateOf(prefs.getString("quick_reaction", "❤️") ?: "❤️")
    // Отдельная кнопка исчезающих в поле ввода. Выключена — режим остаётся
    // доступен долгим нажатием на отправку, функция не пропадает.
    var showEphemeralButton = mutableStateOf(prefs.getBoolean("show_ephemeral_button", true))
    // Настоящий backdrop-blur доступен вручную, но выключен по умолчанию ради плавности.
    var liquidGlassEnabled = mutableStateOf(initialLiquidGlass)
    var glassClarity = mutableStateOf(initialGlassClarity) // 0=матовое, 1=чистое
    /**
     * Сплошные панели: прозрачность выключена, размытие остаётся отдельной
     * настройкой. Нужно тем, кому текст поверх просвечивающего фона мешает
     * читать, и тем, у кого полупрозрачность заметно ест батарею.
     */
    var opaqueSurfaces = mutableStateOf(prefs.getBoolean("opaque_surfaces", false))
    var glassBlurRadius = mutableStateOf(
        floatPref("glass_blur_radius", 8f + 10f * initialGlassClarity, 0f..MAX_GLASS_BLUR_RADIUS)
    )
    var surfaceTransparency = mutableStateOf(
        floatPref("surface_transparency", 0.22f, 0f..MAX_SURFACE_TRANSPARENCY)
    )
    var strictRoundShapes = mutableStateOf(prefs.getBoolean("strict_round_shapes", false))
    // Единые визуальные токены: ими пользуются все aether-панели, кнопки и поля.
    var surfaceTintStrength = mutableStateOf(floatPref("surface_tint_strength", 0.08f, 0f..0.35f))
    var controlTintStrength = mutableStateOf(floatPref("control_tint_strength", 0.14f, 0f..0.35f))
    var fieldTintStrength = mutableStateOf(floatPref("field_tint_strength", 0.06f, 0f..0.35f))
    var panelOpacity = mutableStateOf(floatPref("panel_opacity", 1f, 0.6f..1f))
    var controlOpacity = mutableStateOf(floatPref("control_opacity", 1f, 0.6f..1f))
    var fieldOpacity = mutableStateOf(floatPref("field_opacity", 1f, 0.6f..1f))
    var strokeStrength = mutableStateOf(floatPref("stroke_strength", 0.58f, 0f..1f))
    var panelStrokeStrength = mutableStateOf(floatPref("panel_stroke_strength", 1f, 0f..1f))
    var controlStrokeStrength = mutableStateOf(floatPref("control_stroke_strength", 1f, 0f..1f))
    var fieldStrokeStrength = mutableStateOf(floatPref("field_stroke_strength", 1f, 0f..1f))
    var strokeWidth = mutableStateOf(floatPref("stroke_width", 1f, 0.5f..2f))
    var dockIndicatorEdgeToEdge = mutableStateOf(prefs.getBoolean("dock_indicator_edge_to_edge", false))
    var edgeDimEnabled = mutableStateOf(prefs.getBoolean("edge_dim_enabled", true))
    var edgeDimStrength = mutableStateOf(prefs.getFloat("edge_dim_strength", 1f))
    var edgeDimLength = mutableStateOf(prefs.getFloat("edge_dim_length", 144f).coerceIn(112f, 240f))
    var experimentalAnimations = mutableStateOf(prefs.getBoolean("experimental_animations", true))
    var animationSpeed = mutableStateOf(initialAnimationSpeed)
    var motionIntensity = mutableStateOf(prefs.getFloat("motion_intensity", 1f))
    var reactionEffects = mutableStateOf(prefs.getBoolean("reaction_effects", true))
    var bubbleTails = mutableStateOf(prefs.getBoolean("bubble_tails", true))
    var messageTextSize = mutableStateOf(floatPref("message_text_size", 16f, 14f..20f))
    var bubbleTransparency = mutableStateOf(floatPref("bubble_transparency", 0.08f, 0f..0.6f))
    var bubbleStrokeStrength = mutableStateOf(floatPref("bubble_stroke_strength", 0.55f, 0f..1f))
    var customBubbleColors = mutableStateOf(prefs.getBoolean("custom_bubble_colors", false))
    var incomingBubbleHue = mutableStateOf(floatPref("incoming_bubble_hue", 205f, 0f..360f))
    var outgoingBubbleHue = mutableStateOf(floatPref("outgoing_bubble_hue", 225f, 0f..360f))
    var bubbleTintStrength = mutableStateOf(floatPref("bubble_tint_strength", 0.42f, 0f..1f))
    // --- Уведомления (читаются и в AetherService напрямую из этих же prefs) ---
    var notifSound = mutableStateOf(prefs.getBoolean("notif_sound", true))
    var notifVibration = mutableStateOf(prefs.getBoolean("notif_vibration", true))
    var notifPreviews = mutableStateOf(prefs.getBoolean("notif_previews", true))

    /** Сменить тему. Светлая/тёмная схема определяется самой палитрой. */
    fun setThemeKey(key: String) {
        themeKey.value = key
        prefs.edit().putString("theme_key", key).apply()
    }

    fun setAccentColor(hex: String) {
        accentColorHex.value = hex
        prefs.edit().putString("accent_color", hex).apply()
    }

    fun setCustomAccentEnabled(enabled: Boolean) {
        customAccentEnabled.value = enabled
        prefs.edit().putBoolean("custom_accent_enabled", enabled).apply()
    }

    fun setBackgroundImageUri(uri: String?) {
        backgroundImageUri.value = uri
        prefs.edit().putString("bg_image_uri", uri).apply()
    }

    fun setFontFamilyStyle(style: String) {
        fontFamilyStyle.value = style
        prefs.edit().putString("font_family", style).apply()
    }

    fun setQuickReaction(emoji: String) {
        quickReaction.value = emoji
        prefs.edit().putString("quick_reaction", emoji).apply()
    }

    fun setShowEphemeralButton(on: Boolean) {
        showEphemeralButton.value = on
        prefs.edit().putBoolean("show_ephemeral_button", on).apply()
    }

    fun setOpaqueSurfaces(on: Boolean) {
        opaqueSurfaces.value = on
        prefs.edit().putBoolean("opaque_surfaces", on).apply()
    }

    fun setLiquidGlassEnabled(on: Boolean) {
        liquidGlassEnabled.value = on
        prefs.edit().putBoolean("liquid_glass", on).apply()
    }

    fun setGlassClarity(v: Float) {
        glassClarity.value = v.coerceIn(0f, 1f)
        // Старый ключ оставлен для бесшовной миграции существующих настроек.
        prefs.edit().putFloat("glass_transparency", glassClarity.value).apply()
    }

    fun setGlassBlurRadius(v: Float) {
        glassBlurRadius.value = v.coerceIn(0f, MAX_GLASS_BLUR_RADIUS)
        prefs.edit().putFloat("glass_blur_radius", glassBlurRadius.value).apply()
    }

    fun setSurfaceTransparency(v: Float) {
        surfaceTransparency.value = v.coerceIn(0f, MAX_SURFACE_TRANSPARENCY)
        prefs.edit().putFloat("surface_transparency", surfaceTransparency.value).apply()
    }

    fun setStrictRoundShapes(v: Boolean) { strictRoundShapes.value = v; prefs.edit().putBoolean("strict_round_shapes", v).apply() }
    fun setSurfaceTintStrength(v: Float) { surfaceTintStrength.value = v.coerceIn(0f, 0.35f); prefs.edit().putFloat("surface_tint_strength", surfaceTintStrength.value).apply() }
    fun setControlTintStrength(v: Float) { controlTintStrength.value = v.coerceIn(0f, 0.35f); prefs.edit().putFloat("control_tint_strength", controlTintStrength.value).apply() }
    fun setFieldTintStrength(v: Float) { fieldTintStrength.value = v.coerceIn(0f, 0.35f); prefs.edit().putFloat("field_tint_strength", fieldTintStrength.value).apply() }
    fun setPanelOpacity(v: Float) { panelOpacity.value = v.coerceIn(0.6f, 1f); prefs.edit().putFloat("panel_opacity", panelOpacity.value).apply() }
    fun setControlOpacity(v: Float) { controlOpacity.value = v.coerceIn(0.6f, 1f); prefs.edit().putFloat("control_opacity", controlOpacity.value).apply() }
    fun setFieldOpacity(v: Float) { fieldOpacity.value = v.coerceIn(0.6f, 1f); prefs.edit().putFloat("field_opacity", fieldOpacity.value).apply() }
    fun setStrokeStrength(v: Float) { strokeStrength.value = v.coerceIn(0f, 1f); prefs.edit().putFloat("stroke_strength", strokeStrength.value).apply() }
    fun setPanelStrokeStrength(v: Float) { panelStrokeStrength.value = v.coerceIn(0f, 1f); prefs.edit().putFloat("panel_stroke_strength", panelStrokeStrength.value).apply() }
    fun setControlStrokeStrength(v: Float) { controlStrokeStrength.value = v.coerceIn(0f, 1f); prefs.edit().putFloat("control_stroke_strength", controlStrokeStrength.value).apply() }
    fun setFieldStrokeStrength(v: Float) { fieldStrokeStrength.value = v.coerceIn(0f, 1f); prefs.edit().putFloat("field_stroke_strength", fieldStrokeStrength.value).apply() }
    fun setStrokeWidth(v: Float) { strokeWidth.value = v.coerceIn(0.5f, 2f); prefs.edit().putFloat("stroke_width", strokeWidth.value).apply() }
    fun setDockIndicatorEdgeToEdge(v: Boolean) { dockIndicatorEdgeToEdge.value = v; prefs.edit().putBoolean("dock_indicator_edge_to_edge", v).apply() }

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
        animationSpeed.value = v.coerceIn(1f, 2f)
        prefs.edit().putFloat("animation_speed", animationSpeed.value).apply()
    }

    fun setExperimentalAnimations(v: Boolean) {
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
        return if (!animationsEnabled()) 0 else (baseMillis / speed).roundToInt().coerceIn(1, 220)
    }

    fun animationsEnabled(): Boolean = experimentalAnimations.value

    fun motionDistance(base: Float): Float = if (animationsEnabled()) base * motionIntensity.value else 0f

    fun setBubbleTails(v: Boolean) {
        bubbleTails.value = v
        prefs.edit().putBoolean("bubble_tails", v).apply()
    }

    fun setMessageTextSize(v: Float) {
        messageTextSize.value = v.coerceIn(14f, 20f)
        prefs.edit().putFloat("message_text_size", messageTextSize.value).apply()
    }

    fun setBubbleTransparency(v: Float) { bubbleTransparency.value = v.coerceIn(0f, 0.6f); prefs.edit().putFloat("bubble_transparency", bubbleTransparency.value).apply() }
    fun setBubbleStrokeStrength(v: Float) { bubbleStrokeStrength.value = v.coerceIn(0f, 1f); prefs.edit().putFloat("bubble_stroke_strength", bubbleStrokeStrength.value).apply() }
    fun setCustomBubbleColors(v: Boolean) { customBubbleColors.value = v; prefs.edit().putBoolean("custom_bubble_colors", v).apply() }
    fun setIncomingBubbleHue(v: Float) { incomingBubbleHue.value = v.coerceIn(0f, 360f); prefs.edit().putFloat("incoming_bubble_hue", incomingBubbleHue.value).apply() }
    fun setOutgoingBubbleHue(v: Float) { outgoingBubbleHue.value = v.coerceIn(0f, 360f); prefs.edit().putFloat("outgoing_bubble_hue", outgoingBubbleHue.value).apply() }
    fun setBubbleTintStrength(v: Float) { bubbleTintStrength.value = v.coerceIn(0f, 1f); prefs.edit().putFloat("bubble_tint_strength", bubbleTintStrength.value).apply() }

    fun resetGeneralStyle() {
        setSurfaceTransparency(0.22f)
        setStrokeStrength(0.58f)
        setStrokeWidth(1f)
        setLiquidGlassEnabled(false)
        setGlassClarity(0.5f)
        setGlassBlurRadius(13f)
        setEdgeDimEnabled(true)
        setEdgeDimStrength(1f)
        setEdgeDimLength(144f)
    }

    fun resetPanelStyle() {
        setSurfaceTintStrength(0.08f)
        setPanelOpacity(1f)
        setPanelStrokeStrength(1f)
    }

    fun resetControlStyle() {
        setControlTintStrength(0.14f)
        setControlOpacity(1f)
        setControlStrokeStrength(1f)
    }

    fun resetFieldStyle() {
        setFieldTintStrength(0.06f)
        setFieldOpacity(1f)
        setFieldStrokeStrength(1f)
    }

    fun resetBubbleStyle() {
        setBubbleTails(true)
        setBubbleTransparency(0.08f)
        setBubbleStrokeStrength(0.55f)
        setCustomBubbleColors(false)
        setIncomingBubbleHue(205f)
        setOutgoingBubbleHue(225f)
        setBubbleTintStrength(0.42f)
    }

    fun setNotifSound(v: Boolean) { notifSound.value = v; prefs.edit().putBoolean("notif_sound", v).apply() }
    fun setNotifVibration(v: Boolean) { notifVibration.value = v; prefs.edit().putBoolean("notif_vibration", v).apply() }
    fun setNotifPreviews(v: Boolean) { notifPreviews.value = v; prefs.edit().putBoolean("notif_previews", v).apply() }

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
