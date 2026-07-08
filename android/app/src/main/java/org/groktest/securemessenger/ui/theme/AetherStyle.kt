package org.groktest.securemessenger.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

object AetherStyle {
    val ScreenHorizontal = 18.dp
    val ScreenVertical = 10.dp

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

    const val DockFillAlpha = 0.94f
    const val DockStrokeAlpha = 0.72f
    const val SelectedFillAlpha = 0.18f
    const val SelectedStrokeAlpha = 0.62f
    const val IslandFillAlpha = 0.72f
    const val SoftIslandFillAlpha = 0.34f
    const val ControlFillAlpha = 0.78f
    const val SearchFillAlpha = 0.64f
    const val SoftStrokeAlpha = 0.18f
    const val ControlStrokeAlpha = 0.56f
    const val DividerAlpha = 0.12f
}

@Composable
fun aetherSurface(alpha: Float = AetherStyle.IslandFillAlpha): Color =
    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)

@Composable
fun aetherStroke(alpha: Float = AetherStyle.SelectedStrokeAlpha): Color =
    MaterialTheme.colorScheme.primary.copy(alpha = alpha)

@Composable
fun Modifier.aetherIsland(
    shape: Shape = RoundedCornerShape(AetherStyle.IslandRadius),
    fillAlpha: Float = AetherStyle.IslandFillAlpha,
    strokeAlpha: Float = AetherStyle.SelectedStrokeAlpha
): Modifier = this
    .clip(shape)
    .background(aetherSurface(fillAlpha), shape)
    .border(AetherStyle.Stroke, aetherStroke(strokeAlpha), shape)

@Composable
fun Modifier.aetherCircle(
    fillAlpha: Float = AetherStyle.ControlFillAlpha,
    strokeAlpha: Float = AetherStyle.ControlStrokeAlpha
): Modifier = this
    .clip(CircleShape)
    .background(aetherSurface(fillAlpha), CircleShape)
    .border(AetherStyle.Stroke, aetherStroke(strokeAlpha), CircleShape)

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
