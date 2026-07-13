package org.groktest.securemessenger.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp

object AetherStyle {
    val ScreenHorizontal = 18.dp
    val ScreenVertical = 10.dp
    val EdgeBarHeight = 104.dp

    val DockHorizontal = 18.dp
    val DockBottom = 12.dp
    val DockHeight = 68.dp
    val DockRadius = 34.dp
    val DockIndicatorInset = 8.dp
    val DockIndicatorHeight = 52.dp
    val DockIndicatorRadius = 26.dp

    val IslandRadius = 30.dp
    val RowRadius = 28.dp
    val FieldRadius = 28.dp
    val PillRadius = 24.dp
    val MediaRadius = 22.dp

    val ControlSize = 50.dp
    val SmallControlSize = 44.dp
    val AvatarLarge = 64.dp
    val AvatarMedium = 54.dp
    val Stroke = 1.dp

    const val DockFillAlpha = 0.68f
    const val DockStrokeAlpha = 0.72f
    const val SelectedFillAlpha = 0.18f
    const val SelectedStrokeAlpha = 0.62f
    const val IslandFillAlpha = 0.64f
    const val SoftIslandFillAlpha = 0.34f
    const val ControlFillAlpha = 0.68f
    const val SearchFillAlpha = 0.58f
    const val SoftStrokeAlpha = 0.18f
    const val ControlStrokeAlpha = 0.56f
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
fun aetherSurface(alpha: Float = AetherStyle.IslandFillAlpha): Color =
    MaterialTheme.colorScheme.surfaceVariant.copy(
        alpha = alpha * (1f - LocalThemeSettings.current.surfaceTransparency.value)
    )

@Composable
fun aetherStroke(alpha: Float = AetherStyle.SelectedStrokeAlpha): Color =
    MaterialTheme.colorScheme.primary.copy(alpha = alpha)

@Composable
fun Modifier.aetherIsland(
    shape: Shape = RoundedCornerShape(AetherStyle.IslandRadius),
    fillAlpha: Float = AetherStyle.IslandFillAlpha,
    strokeAlpha: Float = AetherStyle.SelectedStrokeAlpha
): Modifier = aetherShape(shape, fillAlpha, strokeAlpha)

@Composable
fun Modifier.aetherCircle(
    fillAlpha: Float = AetherStyle.ControlFillAlpha,
    strokeAlpha: Float = AetherStyle.ControlStrokeAlpha
): Modifier = aetherShape(CircleShape, fillAlpha, strokeAlpha)

@Composable
private fun Modifier.aetherShape(
    shape: Shape,
    fillAlpha: Float,
    strokeAlpha: Float,
): Modifier {
    val settings = LocalThemeSettings.current
    val fill = aetherSurface(fillAlpha)
    val primary = MaterialTheme.colorScheme.primary
    val glassAlpha = 0.08f + (1f - settings.glassTransparency.value) * 0.06f
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
                primary.copy(alpha = strokeAlpha),
                primary.copy(alpha = strokeAlpha * 0.38f),
                primary.copy(alpha = strokeAlpha * 0.76f),
            )
        )
    } else {
        SolidColor(primary.copy(alpha = strokeAlpha))
    }
    return this
        .clip(shape)
        .background(fillBrush, shape)
        .border(AetherStyle.Stroke, strokeBrush, shape)
}

@Composable
fun aetherTextFieldColors(containerAlpha: Float = AetherStyle.ControlFillAlpha) =
    TextFieldDefaults.colors(
        focusedContainerColor = aetherSurface(containerAlpha),
        unfocusedContainerColor = aetherSurface(containerAlpha),
        disabledContainerColor = aetherSurface(containerAlpha),
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
