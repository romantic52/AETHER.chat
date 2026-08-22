package org.groktest.securemessenger.ui.glass

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp as lerpF
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import org.groktest.securemessenger.ui.theme.LocalThemeSettings

/**
 * Ядро движка «жидкого стекла» (Android, поверх Haze — настоящий backdrop-blur
 * через RenderEffect, API 32+; Haze 0.7.x использует scrim на API 31).
 *
 * ВАЖНО про архитектуру (иначе панели размывают сами себя):
 *  - ИСТОЧНИК размытия — только ПРОКРУЧИВАЕМЫЙ контент экрана (лента/список):
 *    повесить [glassSource] на LazyColumn/контент тела.
 *  - СТЕКЛЯННЫЕ ПАНЕЛИ поверх (бары, нижняя навигация) — [glassSurface]
 *    (`hazeChild`): они размывают то, что под ними, а собственное содержимое
 *    (имя/иконки/поле) рисуется ЧЁТКО поверх.
 */

/** Источник размытия (общий на приложение). null — стекло выключено / нет API. */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/** Поддерживается ли настоящий backdrop-blur на этом устройстве. */
val supportsRealGlass: Boolean get() = Build.VERSION.SDK_INT >= 32

/**
 * Помечает контент ИСТОЧНИКОМ размытия. Вешать на прокручиваемое тело экрана
 * (НЕ на бары). Сила/тинт — из темы и ползунка прозрачности. Блюр мягкий,
 * чтобы текст под панелями был «матовым», а не «зацензуренным».
 */
@Composable
fun Modifier.glassSource(): Modifier {
    val hazeState = LocalHazeState.current ?: return this
    if (!supportsRealGlass) return this
    val settings = LocalThemeSettings.current
    val tintAlpha = lerpF(0.5f, 0.14f, settings.glassClarity.value)
    return this.haze(
        state = hazeState,
        backgroundColor = MaterialTheme.colorScheme.background,
        tint = MaterialTheme.colorScheme.surface.copy(alpha = tintAlpha),
        blurRadius = settings.glassBlurRadius.value.dp,
    )
}

/**
 * Стеклянная панель: backdrop-blur (Haze) при API 32+.
 * Без реального blur визуальную имитацию уже рисует следующий aetherIsland.
 */
@Composable
fun Modifier.glassSurface(
    shape: Shape,
): Modifier {
    val settings = LocalThemeSettings.current
    if (!settings.liquidGlassEnabled.value) return this
    val hazeState = LocalHazeState.current
    return if (hazeState != null && supportsRealGlass) {
        this.hazeChild(state = hazeState, shape = shape)
    } else {
        this
    }
}
