package org.groktest.securemessenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Liquid Glass: полупрозрачная «стеклянная» панель с глянцевым бликом по краю.
 * Имитация backdrop-blur через полупрозрачную градиентную заливку +
 * светящуюся рамку (белый блик сверху, затухание книзу).
 */
fun Modifier.liquidGlass(
    shape: Shape,
    tint: Color = Color.White,
    tintAlpha: Float = 0.12f
): Modifier = this
    .clip(shape)
    .background(
        Brush.verticalGradient(
            colors = listOf(
                tint.copy(alpha = (tintAlpha * 1.6f).coerceAtMost(1f)),
                tint.copy(alpha = tintAlpha)
            )
        ),
        shape
    )
    .border(
        width = 1.dp,
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.45f),
                Color.White.copy(alpha = 0.06f),
                Color.White.copy(alpha = 0.20f)
            )
        ),
        shape = shape
    )
