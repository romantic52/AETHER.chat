package aether.desktop.ui

import aether.desktop.media.VideoPlayback
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Плеер сообщения и результат открытия файла — вместе, чтобы обе вьюхи
 * (кружок и прямоугольное видео) делили один жизненный цикл.
 */
private class VideoHandle(val playback: VideoPlayback) {
    var metadata by mutableStateOf<VideoPlayback.Metadata?>(null)
    var failed by mutableStateOf(false)
}

@Composable
private fun rememberVideoHandle(file: File, msgId: String): VideoHandle {
    val handle = remember(msgId) { VideoHandle(VideoPlayback()) }
    LaunchedEffect(msgId, file) {
        val meta = withContext(Dispatchers.IO) { handle.playback.open(file) }
        if (meta == null) handle.failed = true else handle.metadata = meta
    }
    // Декодер держит поток и нативные ресурсы ffmpeg — при уходе сообщения
    // с экрана освобождаем немедленно, не дожидаясь GC.
    DisposableEffect(msgId) {
        onDispose { handle.playback.close() }
    }
    return handle
}

/**
 * Кружок-видеосообщение: играет прямо в ленте, как в Telegram, — круглый
 * кадр, прогресс по окружности и остаток секунд внизу. Клик — пуск/пауза.
 */
@Composable
fun VideoNoteView(file: File, msgId: String, modifier: Modifier = Modifier) {
    val handle = rememberVideoHandle(file, msgId)
    val frame by handle.playback.frame.collectAsState()
    val state by handle.playback.state.collectAsState()
    val opened = handle.metadata != null

    Box(
        modifier = modifier
            .size(220.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .clickable(enabled = opened) {
                if (state.playing) handle.playback.pause() else handle.playback.play()
            },
        contentAlignment = Alignment.Center,
    ) {
        frame?.let {
            Image(
                bitmap = it,
                contentDescription = "Видеосообщение",
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        when {
            handle.failed -> Text(
                "Видео не открылось",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            !opened -> CircularProgressIndicator(modifier = Modifier.size(32.dp))
            !state.playing -> PlayBadge()
        }
        if (opened && state.positionMs > 0) {
            // Прогресс — дугой по самой окружности кружка.
            Canvas(modifier = Modifier.matchParentSize().padding(2.dp)) {
                drawArc(
                    color = Color.White.copy(alpha = 0.85f),
                    startAngle = -90f,
                    sweepAngle = 360f * state.progress,
                    useCenter = false,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
        if (opened && state.durationMs > 0) {
            TimePill(
                text = formatClock(state.durationMs - state.positionMs),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
            )
        }
    }
}

/**
 * Обычное видео: прямоугольный inline-плеер. Клик по кадру — пуск/пауза,
 * полоса снизу показывает прогресс и мотает по клику.
 */
@Composable
fun VideoFileView(file: File, msgId: String, widthLimit: Dp = 360.dp, modifier: Modifier = Modifier) {
    val handle = rememberVideoHandle(file, msgId)
    val frame by handle.playback.frame.collectAsState()
    val state by handle.playback.state.collectAsState()
    val meta = handle.metadata
    val aspect = meta?.takeIf { it.width > 0 && it.height > 0 }
        ?.let { it.width.toFloat() / it.height } ?: (16f / 9f)

    Box(
        modifier = modifier
            .widthIn(max = widthLimit)
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .clickable(enabled = meta != null) {
                if (state.playing) handle.playback.pause() else handle.playback.play()
            },
        contentAlignment = Alignment.Center,
    ) {
        frame?.let {
            Image(
                bitmap = it,
                contentDescription = "Видео",
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize(),
            )
        }
        when {
            handle.failed -> Text(
                "Видео не открылось",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            meta == null -> CircularProgressIndicator(modifier = Modifier.size(32.dp))
            !state.playing -> PlayBadge()
        }
        if (meta != null && state.durationMs > 0) {
            TimePill(
                text = formatClock(
                    if (state.playing || state.positionMs > 0) state.durationMs - state.positionMs
                    else state.durationMs,
                ),
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            )
        }
        if (meta != null) {
            // Зона клика выше самой полосы, чтобы в неё было легко попасть.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(16.dp)
                    .pointerInput(msgId) {
                        detectTapGestures { offset ->
                            val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                            handle.playback.seek((state.durationMs * fraction).toLong())
                            if (!state.playing) handle.playback.play()
                        }
                    },
            ) {
                Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)) {
                    val radius = CornerRadius(size.height / 2f)
                    drawRoundRect(color = Color.White.copy(alpha = 0.35f), cornerRadius = radius)
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset.Zero,
                        size = Size(size.width * state.progress, size.height),
                        cornerRadius = radius,
                    )
                }
            }
        }
    }
}

/** Полупрозрачная кнопка пуска поверх кадра. */
@Composable
private fun PlayBadge() {
    Box(
        modifier = Modifier.size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = "Воспроизвести",
            tint = Color.White,
            modifier = Modifier.size(36.dp),
        )
    }
}

/** Плашка со временем поверх видео — читается на любом кадре. */
@Composable
private fun TimePill(text: String, modifier: Modifier = Modifier) {
    Surface(color = Color.Black.copy(alpha = 0.4f), shape = RoundedCornerShape(10.dp), modifier = modifier) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

private fun formatClock(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}
