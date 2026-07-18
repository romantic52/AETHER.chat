package org.groktest.securemessenger.utils

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import org.groktest.securemessenger.ui.theme.LocalThemeSettings
import kotlin.math.roundToInt

/**
 * Свайп от левого края вправо для навигации «назад».
 * Контент следует за пальцем БЕЗ задержки (синхронное состояние, без корутин
 * на каждый кадр), а при отпускании — быстрая анимация без «резины»/пружины.
 */
@Composable
fun SwipeToBackWrapper(
    onBack: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    if (!enabled) {
        content()
        return
    }

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val appearance = LocalThemeSettings.current
    val screenWidthPx: Float = with(density) { configuration.screenWidthDp.dp.toPx() }

    // Назад — свайп с левой половины экрана. Свайп-ответ на сообщении имеет
    // приоритет: его жест поглощает событие (change.consume), поэтому на пузыре
    // срабатывает ответ, а на пустом месте слева — выход назад.
    val edgeZonePx: Float = screenWidthPx * 0.45f
    // Порог срабатывания — протянуть ~28% ширины экрана
    val triggerThreshold: Float = screenWidthPx * 0.28f

    // Синхронное смещение: контент двигается мгновенно вслед за пальцем
    var offsetX by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var dragStartedInEdge by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val p = (offsetX / screenWidthPx).coerceIn(0f, 1f)
                if (p > 0.005f) {
                    drawRect(color = Color.Black, alpha = 0.35f * (1f - p))
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .pointerInput(appearance.experimentalAnimations.value, appearance.animationSpeed.value) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            settleJob?.cancel()
                            dragStartedInEdge = offset.x <= edgeZonePx
                        },
                        onDragEnd = {
                            if (dragStartedInEdge) {
                                if (offsetX > triggerThreshold) {
                                    // Оставшийся путь рисует единственный pop-переход NavHost.
                                    onBack()
                                } else {
                                    settleJob = scope.launch {
                                        if (appearance.animationsEnabled()) {
                                            animate(
                                                initialValue = offsetX,
                                                targetValue = 0f,
                                                animationSpec = tween(appearance.motionDuration(170), easing = FastOutSlowInEasing)
                                            ) { v, _ -> offsetX = v }
                                        } else {
                                            offsetX = 0f
                                        }
                                    }
                                }
                            }
                            dragStartedInEdge = false
                        },
                        onDragCancel = {
                            dragStartedInEdge = false
                            settleJob?.cancel()
                            settleJob = scope.launch {
                                if (appearance.animationsEnabled()) {
                                    animate(
                                        initialValue = offsetX,
                                        targetValue = 0f,
                                        animationSpec = tween(appearance.motionDuration(150), easing = FastOutSlowInEasing)
                                    ) { v, _ -> offsetX = v }
                                } else {
                                    offsetX = 0f
                                }
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            if (dragStartedInEdge) {
                                change.consume()
                                offsetX = (offsetX + dragAmount).coerceIn(0f, screenWidthPx)
                            }
                        }
                    )
                }
        ) {
            content()
        }
    }
}
