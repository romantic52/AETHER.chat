package org.groktest.securemessenger.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

object AetherStyle {
    val ScreenHorizontal = 18.dp
    val ScreenVertical = 10.dp
    val EdgeBarHeight = 104.dp

    val DockHorizontal = 12.dp
    val DockBottom = 12.dp
    val DockHeight = 64.dp
    val DockRadius = 32.dp
    val DockIndicatorInset = 2.dp
    val DockIndicatorHeight = 60.dp
    val DockIndicatorRadius = 30.dp

    val IslandRadius
        @Composable @ReadOnlyComposable get() = if (LocalThemeSettings.current.strictRoundShapes.value) 40.dp else 32.dp
    val RowRadius
        @Composable @ReadOnlyComposable get() = if (LocalThemeSettings.current.strictRoundShapes.value) 32.dp else 24.dp
    val FieldRadius
        @Composable @ReadOnlyComposable get() = if (LocalThemeSettings.current.strictRoundShapes.value) 28.dp else 22.dp
    val PillRadius = 1000.dp
    val MediaRadius
        @Composable @ReadOnlyComposable get() = if (LocalThemeSettings.current.strictRoundShapes.value) 30.dp else 22.dp

    val ControlSize = 50.dp
    val SmallControlSize = 44.dp
    val AvatarLarge = 64.dp
    val AvatarMedium = 54.dp
    val Stroke: Dp
        @Composable @ReadOnlyComposable get() = LocalThemeSettings.current.strokeWidth.value.dp

    const val DockFillAlpha = 0.62f
    const val DockStrokeAlpha = 0.56f
    const val SelectedFillAlpha = 0.18f
    const val SelectedStrokeAlpha = 0.62f
    const val IslandFillAlpha = 0.64f
    const val SoftIslandFillAlpha = 0.34f
    const val ControlFillAlpha = 0.68f
    const val SearchFillAlpha = 0.58f
    const val SearchStrokeAlpha = 0.42f
    const val SoftStrokeAlpha = 0.18f
    const val ControlStrokeAlpha = 0.56f
    const val AvatarFillAlpha = 0.84f
    const val AvatarStrokeAlpha = 0.46f
    const val DividerAlpha = 0.12f
}

enum class AetherEdge { Top, Bottom }

@Composable
fun AetherEdgeDim(
    edge: AetherEdge,
    modifier: Modifier = Modifier
) {
    val settings = LocalThemeSettings.current
    val strength = if (settings.edgeDimEnabled.value) {
        settings.edgeDimStrength.value.coerceIn(0f, 1f)
    } else {
        0f
    }
    val base = MaterialTheme.colorScheme.background
    val brush = if (edge == AetherEdge.Top) {
        Brush.verticalGradient(
            0f to base.copy(alpha = strength * 0.92f),
            0.18f to base.copy(alpha = strength * 0.78f),
            0.42f to base.copy(alpha = strength * 0.48f),
            0.68f to base.copy(alpha = strength * 0.20f),
            0.86f to base.copy(alpha = strength * 0.07f),
            1f to Color.Transparent
        )
    } else {
        Brush.verticalGradient(
            0f to Color.Transparent,
            0.14f to base.copy(alpha = strength * 0.07f),
            0.32f to base.copy(alpha = strength * 0.20f),
            0.58f to base.copy(alpha = strength * 0.48f),
            0.82f to base.copy(alpha = strength * 0.78f),
            1f to base.copy(alpha = strength * 0.92f)
        )
    }
    Box(modifier = modifier.background(brush))
}

@Composable
private fun aetherElementSurface(alpha: Float, tintStrength: Float, opacity: Float): Color {
    val settings = LocalThemeSettings.current
    val visibility = surfaceVisibility(settings)
    val base = lerp(
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.primary,
        tintStrength.coerceIn(0f, 1f)
    )
    return base.copy(alpha = (alpha * visibility * opacity).coerceIn(0f, 1f))
}

/** Компактные контролы скруглены, но не искажаются в круг при изменении размера. */
val AetherCompactShape: CornerBasedShape = RoundedCornerShape(20.dp)

/** Усиленный режим увеличивает радиус, не превращая карточки и диалоги в овалы. */
@Composable
fun aetherLargeShape(): CornerBasedShape = if (LocalThemeSettings.current.strictRoundShapes.value) {
    RoundedCornerShape(48.dp)
} else {
    RoundedCornerShape(32.dp)
}

@Composable
fun aetherSurface(alpha: Float = AetherStyle.IslandFillAlpha): Color =
    LocalThemeSettings.current.let { settings ->
        aetherElementSurface(alpha, settings.surfaceTintStrength.value, settings.panelOpacity.value)
    }

@Composable
private fun aetherControlSurface(alpha: Float): Color =
    LocalThemeSettings.current.let { settings ->
        aetherElementSurface(alpha, settings.controlTintStrength.value, settings.controlOpacity.value)
    }

@Composable
private fun aetherFieldSurface(alpha: Float): Color =
    LocalThemeSettings.current.let { settings ->
        aetherElementSurface(alpha, settings.fieldTintStrength.value, settings.fieldOpacity.value)
    }

@Composable
private fun aetherAvatarSurface(alpha: Float): Color =
    aetherElementSurface(alpha, 0f, 1f)

private fun surfaceVisibility(settings: ThemeSettings): Float {
    val fraction = (settings.surfaceTransparency.value / MAX_SURFACE_TRANSPARENCY).coerceIn(0f, 1f)
    return 1f - 0.38f * fraction
}

@Composable
fun aetherStroke(alpha: Float = AetherStyle.SelectedStrokeAlpha): Color =
    LocalThemeSettings.current.let { settings ->
        MaterialTheme.colorScheme.primary.copy(
            alpha = alpha * settings.strokeStrength.value * surfaceVisibility(settings)
        )
    }

@Composable
fun Modifier.aetherIsland(
    shape: Shape = AetherCompactShape,
    fillAlpha: Float = AetherStyle.IslandFillAlpha,
    strokeAlpha: Float = AetherStyle.SelectedStrokeAlpha
): Modifier = aetherShape(shape, fillAlpha, strokeAlpha, ElementSurface.Panel)

@Composable
fun Modifier.aetherCircle(
    fillAlpha: Float = AetherStyle.ControlFillAlpha,
    strokeAlpha: Float = AetherStyle.ControlStrokeAlpha
): Modifier = aetherShape(CircleShape, fillAlpha, strokeAlpha, ElementSurface.Avatar)

@Composable
fun Modifier.aetherControl(
    fillAlpha: Float = AetherStyle.ControlFillAlpha,
    strokeAlpha: Float = AetherStyle.ControlStrokeAlpha,
    shape: Shape = AetherCompactShape,
): Modifier = aetherShape(
    shape,
    fillAlpha,
    strokeAlpha,
    ElementSurface.Control,
)

@Composable
fun Modifier.aetherField(
    shape: Shape = AetherCompactShape,
    fillAlpha: Float = AetherStyle.ControlFillAlpha,
    strokeAlpha: Float = AetherStyle.SearchStrokeAlpha,
): Modifier = aetherShape(shape, fillAlpha, strokeAlpha, ElementSurface.Field)

private enum class ElementSurface { Panel, Control, Field, Avatar }

@Composable
private fun Modifier.aetherShape(
    shape: Shape,
    fillAlpha: Float,
    strokeAlpha: Float,
    role: ElementSurface,
): Modifier {
    val settings = LocalThemeSettings.current
    val fill = when (role) {
        ElementSurface.Panel -> aetherSurface(fillAlpha)
        ElementSurface.Control -> aetherControlSurface(fillAlpha)
        ElementSurface.Field -> aetherFieldSurface(fillAlpha)
        ElementSurface.Avatar -> aetherAvatarSurface(fillAlpha)
    }
    val primary = MaterialTheme.colorScheme.primary
    val visibility = surfaceVisibility(settings)
    val roleOpacity = when (role) {
        ElementSurface.Panel -> settings.panelOpacity.value
        ElementSurface.Control -> settings.controlOpacity.value
        ElementSurface.Field -> settings.fieldOpacity.value
        ElementSurface.Avatar -> 1f
    }
    val roleStroke = when (role) {
        ElementSurface.Panel -> settings.panelStrokeStrength.value
        ElementSurface.Control -> settings.controlStrokeStrength.value
        ElementSurface.Field -> settings.fieldStrokeStrength.value
        ElementSurface.Avatar -> 1f
    }
    val glassAlpha = (0.08f + (1f - settings.glassClarity.value) * 0.06f) * visibility * roleOpacity
    val resolvedStrokeAlpha = strokeAlpha * settings.strokeStrength.value * roleStroke * visibility
    val fillBrush = if (settings.liquidGlassEnabled.value) {
        val tint = MaterialTheme.colorScheme.onBackground
        Brush.verticalGradient(
            listOf(
                tint.copy(alpha = glassAlpha * 1.6f).compositeOver(fill),
                tint.copy(alpha = glassAlpha).compositeOver(fill),
            )
        )
    } else {
        SolidColor(fill)
    }
    val strokeBrush = if (settings.liquidGlassEnabled.value) {
        Brush.verticalGradient(
            listOf(
                primary.copy(alpha = resolvedStrokeAlpha),
                primary.copy(alpha = resolvedStrokeAlpha * 0.38f),
                primary.copy(alpha = resolvedStrokeAlpha * 0.76f),
            )
        )
    } else {
        SolidColor(primary.copy(alpha = resolvedStrokeAlpha))
    }
    return this
        .clip(shape)
        .background(fillBrush, shape)
        .border(AetherStyle.Stroke, strokeBrush, shape)
}

@Composable
fun aetherControlContent(): Color {
    val settings = LocalThemeSettings.current
    val scheme = MaterialTheme.colorScheme
    val fill = aetherControlSurface(AetherStyle.ControlFillAlpha)
    val samples = if (settings.liquidGlassEnabled.value) {
        val glassAlpha = (0.08f + (1f - settings.glassClarity.value) * 0.06f) *
            surfaceVisibility(settings) * settings.controlOpacity.value
        listOf(
            scheme.onBackground.copy(alpha = glassAlpha * 1.6f).compositeOver(fill).compositeOver(scheme.background),
            scheme.onBackground.copy(alpha = glassAlpha).compositeOver(fill).compositeOver(scheme.background),
        )
    } else {
        listOf(fill.compositeOver(scheme.background))
    }
    val primary = scheme.primary
    fun minimumContrast(color: Color): Float = samples.minOf { background ->
        (maxOf(color.luminance(), background.luminance()) + 0.05f) /
            (minOf(color.luminance(), background.luminance()) + 0.05f)
    }
    if (minimumContrast(primary) >= 4.5f) return primary
    return if (minimumContrast(Color.Black) >= minimumContrast(Color.White)) Color.Black else Color.White
}

@Composable
fun aetherTextFieldColors(containerAlpha: Float = AetherStyle.ControlFillAlpha) =
    TextFieldDefaults.colors(
        focusedContainerColor = aetherFieldSurface(containerAlpha),
        unfocusedContainerColor = aetherFieldSurface(containerAlpha),
        disabledContainerColor = aetherFieldSurface(containerAlpha),
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
        focusedTextColor = MaterialTheme.colorScheme.onBackground,
        unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
        disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
        disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    )

data class AetherBubbleVisual(
    val fill: Color,
    val content: Color,
    val metadata: Color,
    val stroke: Color,
)

@Composable
fun aetherBubbleVisual(outgoing: Boolean): AetherBubbleVisual {
    val settings = LocalThemeSettings.current
    val scheme = MaterialTheme.colorScheme
    val themeBase = if (outgoing) scheme.tertiary else scheme.surfaceVariant
    val tinted = if (settings.customBubbleColors.value) {
        val hue = if (outgoing) settings.outgoingBubbleHue.value else settings.incomingBubbleHue.value
        val dark = scheme.background.luminance() < 0.5f
        val target = Color(
            android.graphics.Color.HSVToColor(
                floatArrayOf(hue, if (dark) 0.58f else 0.42f, if (dark) 0.42f else 0.90f)
            )
        )
        lerp(themeBase, target, settings.bubbleTintStrength.value)
    } else {
        themeBase
    }
    val matteStrength = if (settings.liquidGlassEnabled.value) {
        (1f - settings.glassClarity.value) * 0.05f
    } else {
        0f
    }
    val matte = lerp(tinted, scheme.onBackground, matteStrength)
    val surfaceFraction = (settings.surfaceTransparency.value / MAX_SURFACE_TRANSPARENCY).coerceIn(0f, 1f)
    val bubbleFraction = (settings.bubbleTransparency.value / MAX_SURFACE_TRANSPARENCY).coerceIn(0f, 1f)
    val transparencyFraction = (surfaceFraction * 0.65f + bubbleFraction * 0.35f).coerceIn(0f, 1f)
    val globalBubbleAlpha = 1f - 0.28f * surfaceFraction
    val requestedAlpha = (globalBubbleAlpha + (0.62f - globalBubbleAlpha) * bubbleFraction)
        .coerceIn(0.62f, 1f)
    val possibleBackgrounds = if (settings.backgroundImageUri.value != null) {
        listOf(Color.Black, Color.White)
    } else {
        listOf(scheme.background)
    }
    fun contrast(first: Color, second: Color): Float =
        (maxOf(first.luminance(), second.luminance()) + 0.05f) /
            (minOf(first.luminance(), second.luminance()) + 0.05f)
    fun bestContent(alpha: Float): Pair<Color, Float> {
        val resolved = possibleBackgrounds.map { matte.copy(alpha = alpha).compositeOver(it) }
        val blackContrast = resolved.minOf { contrast(Color.Black, it) }
        val whiteContrast = resolved.minOf { contrast(Color.White, it) }
        return if (blackContrast >= whiteContrast) Color.Black to blackContrast else Color.White to whiteContrast
    }
    var fillAlpha = requestedAlpha
    if (possibleBackgrounds.size > 1 && bestContent(fillAlpha).second < 4.5f) {
        var low = fillAlpha
        var high = 1f
        repeat(12) {
            val middle = (low + high) / 2f
            if (bestContent(middle).second >= 4.5f) high = middle else low = middle
        }
        fillAlpha = high
    }
    val resolvedFill = matte.copy(alpha = fillAlpha)
    val content = bestContent(fillAlpha).first
    val strokeAlpha = (0.10f + 0.08f * transparencyFraction) *
        settings.bubbleStrokeStrength.value * settings.strokeStrength.value
    return AetherBubbleVisual(
        fill = resolvedFill,
        content = content,
        metadata = content,
        stroke = content.copy(alpha = strokeAlpha),
    )
}

@Composable
fun aetherBubbleShape(outgoing: Boolean, showTail: Boolean): Shape {
    val settings = LocalThemeSettings.current
    val radius = if (settings.strictRoundShapes.value) 24f else 18f
    val r = radius.dp
    if (!showTail || !settings.bubbleTails.value) return RoundedCornerShape(r)
    val tail = (radius * 0.35f).coerceIn(4f, 8f).dp
    return RoundedCornerShape(
        topStart = r,
        topEnd = r,
        bottomStart = if (outgoing) r else tail,
        bottomEnd = if (outgoing) tail else r,
    )
}
