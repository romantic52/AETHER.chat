package org.groktest.securemessenger.ui.components

import android.net.Uri
import android.os.Build
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.chrisbanes.haze.HazeState
import org.groktest.securemessenger.ui.glass.LocalHazeState
import org.groktest.securemessenger.ui.theme.LocalThemeSettings

/**
 * Корневой фон всех экранов + источник «жидкого стекла».
 *
 * Когда включено стекло (и API 31+), весь контент помечается источником
 * размытия [haze]; стеклянные панели поверх него (бары/пузыри) размывают
 * именно то, что под ними — через `glassSurface`/`hazeChild`. Тинт = surface
 * темы, сила — из ползунка прозрачности. Стекло выкл → сплошной фон темы.
 */
@Composable
fun GlassBackground(content: @Composable () -> Unit) {
    val themeSettings = LocalThemeSettings.current
    val glassOn = themeSettings.isLiquidGlass()
    val bgUri = themeSettings.backgroundImageUri.value
    val realGlass = glassOn && Build.VERSION.SDK_INT >= 31

    val hazeState = remember { HazeState() }

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
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (themeSettings.isGlassEnabled.value && !realGlass) Modifier.blur(24.dp) else Modifier)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.3f))
            )
        }

        // ВАЖНО: haze-ИСТОЧНИК вешается НЕ здесь (иначе бары размывают сами себя),
        // а на прокручиваемый контент каждого экрана через Modifier.glassSource().
        // Здесь только пробрасываем общее состояние размытия.
        Box(modifier = Modifier.fillMaxSize()) {
            CompositionLocalProvider(
                // Дефолтный цвет текста = onBackground (следует теме).
                LocalContentColor provides MaterialTheme.colorScheme.onBackground,
                // Источник размытия для стеклянных панелей (null — стекло выкл / API<31).
                LocalHazeState provides (if (realGlass) hazeState else null),
            ) {
                content()
            }
        }
    }
}
