package org.groktest.securemessenger.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import dev.chrisbanes.haze.HazeState
import org.groktest.securemessenger.ui.glass.LocalHazeState
import org.groktest.securemessenger.ui.glass.supportsRealGlass
import org.groktest.securemessenger.ui.theme.LocalThemeSettings

/**
 * Корневой фон всех экранов + источник «жидкого стекла».
 *
 * Когда включено стекло (и API 32+), весь контент помечается источником
 * размытия [haze]; стеклянные панели поверх него (бары/пузыри) размывают
 * именно то, что под ними — через `glassSurface`/`hazeChild`. Тинт = surface
 * темы, сила — из ползунка прозрачности. Стекло выкл → сплошной фон темы.
 */
@Composable
fun GlassBackground(content: @Composable () -> Unit) {
    val themeSettings = LocalThemeSettings.current
    val glassOn = themeSettings.liquidGlassEnabled.value
    val bgUri = themeSettings.backgroundImageUri.value
    val realGlass = glassOn && supportsRealGlass && themeSettings.glassBlurRadius.value > 0f

    val hazeState = if (realGlass) remember { HazeState() } else null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Пользовательские обои (если заданы) — фон, который стекло будет размывать.
        if (bgUri != null) {
            AsyncImage(
                model = Uri.parse(bgUri),
                contentDescription = "Wallpaper",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.3f))
            )
        }

        // Источник включается только внутри чата, где есть реальная стеклянная шапка.
        // Если пометить здесь всё дерево, Haze будет держать тяжёлые off-screen вкладки.
        Box(modifier = Modifier.fillMaxSize()) {
            CompositionLocalProvider(
                // Дефолтный цвет текста = onBackground (следует теме).
                LocalContentColor provides MaterialTheme.colorScheme.onBackground,
                // Источник размытия для стеклянных панелей (null — стекло выкл / API<32).
                LocalHazeState provides hazeState,
            ) {
                content()
            }
        }
    }
}
