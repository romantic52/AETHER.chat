package org.groktest.securemessenger.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import org.groktest.securemessenger.ui.theme.AetherStyle
import org.groktest.securemessenger.ui.theme.LocalThemeSettings

/**
 * Голосовое сообщение: кнопка play/pause + дорожка + длительность.
 * Файл скачивается и расшифровывается по первому нажатию, затем кэшируется.
 */
internal object ChatPlaybackCoordinator {
    private var owner: String? = null
    private var stop: (() -> Unit)? = null

    fun claim(key: String, onStop: () -> Unit) {
        // Останавливаем прежнее воспроизведение и при том же owner: два пузыря
        // с одинаковым jsonText делят ключ, иначе второй стартует поверх первого.
        stop?.takeIf { it !== onStop }?.invoke()
        owner = key
        stop = onStop
    }

    fun release(key: String) {
        if (owner == key) {
            owner = null
            stop = null
        }
    }

    /** Останавливает текущее воспроизведение (старт записи ГС/кружка, звонок). */
    fun stopAll() {
        stop?.invoke()
        owner = null
        stop = null
    }
}

/**
 * Глобальная скорость воспроизведения (1x → 1.5x → 2x) для голосовых и кружков:
 * одна на весь файл, переживает переключение сообщений и чатов.
 * minSdk 24 ≥ API 23, поэтому PlaybackParams доступен без проверки версии.
 */
private val playbackSpeedState = mutableStateOf(1f)

/** Следующая скорость по циклу 1x → 1.5x → 2x → 1x. */
private fun nextPlaybackSpeed(current: Float): Float = when {
    current < 1.25f -> 1.5f
    current < 1.75f -> 2f
    else -> 1f
}

/** Подпись бейджа скорости. */
private fun playbackSpeedLabel(speed: Float): String = when {
    speed < 1.25f -> "1x"
    speed < 1.75f -> "1.5x"
    else -> "2x"
}

/**
 * Скорость выставляем только играющему плееру: setSpeed на паузе сам запускает
 * воспроизведение (особенность MediaPlayer), поэтому пауза остаётся паузой.
 */
private fun applyPlaybackSpeed(p: android.media.MediaPlayer, speed: Float) {
    try {
        if (p.isPlaying) {
            p.playbackParams = android.media.PlaybackParams().setSpeed(speed)
        }
    } catch (_: Exception) {}
}

/**
 * Голосовое сообщение: одновременно играет только один медиаплеер, а прогресс
 * обновляется достаточно часто для плавности без постоянной перерисовки списка.
 */
@Composable
fun VoiceMessagePlayer(
    jsonText: String,
    durationMs: Long,
    tint: Color,
    onDownloadMedia: suspend (String) -> File?,
    waveform: List<Int>? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appearance = LocalThemeSettings.current
    var isPlaying by remember(jsonText) { mutableStateOf(false) }
    var isLoading by remember(jsonText) { mutableStateOf(false) }
    var player by remember(jsonText) { mutableStateOf<android.media.MediaPlayer?>(null) }
    var progress by remember(jsonText) { mutableStateOf(0f) }
    // Веб не шлёт duration: после prepare подставляем реальную длительность файла
    var realDurationMs by remember(jsonText, durationMs) { mutableStateOf(durationMs) }
    // Композиция снята — созданному в фоне плееру некуда жить, сразу release
    val disposed = remember(jsonText) { mutableStateOf(false) }

    val bars = remember(jsonText, waveform) {
        if (!waveform.isNullOrEmpty()) {
            // Реальная огибающая записи: значения 0..31 -> высота бара 0..1
            FloatArray(waveform.size) { i -> (waveform[i].coerceIn(0, 31) / 31f).coerceAtLeast(0.08f) }
        } else {
            // Старые сообщения без waveform: стабильный псевдослучайный фейк
            val rnd = java.util.Random(jsonText.hashCode().toLong())
            FloatArray(36) { 0.25f + rnd.nextFloat() * 0.75f }
        }
    }

    fun play(p: android.media.MediaPlayer) {
        ChatPlaybackCoordinator.claim(jsonText) {
            try { if (p.isPlaying) p.pause() } catch (_: Exception) {}
            isPlaying = false
        }
        try {
            p.start()
            applyPlaybackSpeed(p, playbackSpeedState.value)
            isPlaying = true
        } catch (_: Exception) {
            isPlaying = false
            ChatPlaybackCoordinator.release(jsonText)
        }
    }

    // Смена скорости на лету применяется к играющему плееру
    LaunchedEffect(playbackSpeedState.value) {
        val p = player ?: return@LaunchedEffect
        if (isPlaying) applyPlaybackSpeed(p, playbackSpeedState.value)
    }

    LaunchedEffect(isPlaying, player) {
        while (isPlaying) {
            player?.let { p ->
                try {
                    progress = (p.currentPosition.toFloat() / p.duration.coerceAtLeast(1))
                        .coerceIn(0f, 1f)
                } catch (_: Exception) {}
            }
            kotlinx.coroutines.delay(80)
        }
    }

    DisposableEffect(jsonText) {
        onDispose {
            disposed.value = true
            ChatPlaybackCoordinator.release(jsonText)
            try { player?.release() } catch (_: Exception) {}
            player = null
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.widthIn(min = 160.dp).padding(vertical = 2.dp)
    ) {
        IconButton(
            enabled = !isLoading,
            onClick = {
                val p = player
                when {
                    isPlaying && p != null -> {
                        try { p.pause() } catch (_: Exception) {}
                        isPlaying = false
                        ChatPlaybackCoordinator.release(jsonText)
                    }
                    p != null -> play(p)
                    else -> {
                        isLoading = true
                        scope.launch {
                            try {
                                val file = withContext(Dispatchers.IO) { onDownloadMedia(jsonText) }
                                if (file == null) {
                                    android.widget.Toast.makeText(context, "Не удалось загрузить голосовое", android.widget.Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                // Создание и старт — в NonCancellable: prepare() блокирующий
                                // и неотменяемый, при отмене scope (скролл) готовый нативный
                                // плеер иначе утёк бы без release().
                                withContext(kotlinx.coroutines.NonCancellable) {
                                    val mp = withContext(Dispatchers.IO) {
                                        val p = android.media.MediaPlayer()
                                        try {
                                            p.setDataSource(file.absolutePath)
                                            p.prepare()
                                            p
                                        } catch (t: Throwable) {
                                            p.release()
                                            throw t
                                        }
                                    }
                                    if (disposed.value) {
                                        // Пузырь уже ушёл из композиции — освобождаем сразу
                                        try { mp.release() } catch (_: Exception) {}
                                    } else {
                                        mp.setOnCompletionListener {
                                            isPlaying = false
                                            progress = 0f
                                            ChatPlaybackCoordinator.release(jsonText)
                                        }
                                        if (realDurationMs <= 0L) realDurationMs = mp.duration.toLong()
                                        player = mp
                                        play(mp)
                                    }
                                }
                            } catch (c: kotlinx.coroutines.CancellationException) {
                                throw c
                            } catch (_: Exception) {
                                isPlaying = false
                                android.widget.Toast.makeText(context, "Не удалось загрузить голосовое", android.widget.Toast.LENGTH_SHORT).show()
                            } finally {
                                isLoading = false
                            }
                        }
                    }
                }
            }
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = tint,
                    strokeWidth = 2.dp
                )
            } else {
                AnimatedContent(
                    targetState = isPlaying,
                    transitionSpec = {
                        (fadeIn(tween(appearance.motionDuration(150))) +
                            scaleIn(tween(appearance.motionDuration(180)), initialScale = 0.72f))
                            .togetherWith(
                                fadeOut(tween(appearance.motionDuration(100))) +
                                    scaleOut(tween(appearance.motionDuration(120)), targetScale = 0.78f)
                            )
                    },
                    label = "voicePlayPause"
                ) { playing ->
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "Пауза" else "Воспроизвести",
                        tint = tint
                    )
                }
            }
        }
        Waveform(
            bars = bars,
            progress = progress,
            tint = tint,
            onSeek = { fraction ->
                player?.let { p ->
                    try {
                        p.seekTo((fraction * p.duration).toInt())
                        progress = fraction
                    } catch (_: Exception) {}
                }
            },
            modifier = Modifier.height(26.dp).width(130.dp)
        )
        Spacer(Modifier.width(8.dp))
        if (isPlaying) {
            // Играет: вместо счётчика — кликабельный бейдж скорости 1x/1.5x/2x
            Text(
                playbackSpeedLabel(playbackSpeedState.value),
                color = tint,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(tint.copy(alpha = 0.16f))
                    .clickable { playbackSpeedState.value = nextPlaybackSpeed(playbackSpeedState.value) }
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            )
        } else {
            val shownMs = if (progress > 0f) (realDurationMs * progress).toLong() else realDurationMs
            Text(formatClock(shownMs), color = tint, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Waveform(
    bars: FloatArray,
    progress: Float,
    tint: Color,
    onSeek: (Float) -> Unit,
    modifier: Modifier
) {
    Canvas(
        modifier = modifier
            .pointerInput(onSeek) {
                detectTapGestures { offset ->
                    onSeek((offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f))
                }
            }
            .pointerInput(onSeek) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    onSeek((change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f))
                }
            }
    ) {
        val gap = 2.dp.toPx()
        val barW = ((size.width - gap * (bars.size - 1)) / bars.size).coerceAtLeast(1f)
        val playedUpTo = progress * bars.size
        bars.forEachIndexed { index, value ->
            val h = (size.height * value).coerceAtLeast(barW)
            val x = index * (barW + gap)
            drawRoundRect(
                color = if (index < playedUpTo) tint else tint.copy(alpha = 0.3f),
                topLeft = Offset(x, (size.height - h) / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f)
            )
        }
    }
}
@Composable
fun VideoMessage(
    jsonText: String,
    cachedMediaFile: (String) -> File? = { null },
    onDownloadMedia: suspend (String) -> File?
) {
    val appearance = LocalThemeSettings.current
    var filePath by remember(jsonText) { mutableStateOf(cachedMediaFile(jsonText)?.absolutePath) }
    var loading by remember(jsonText) { mutableStateOf(filePath == null) }
    var videoViewRef by remember(jsonText) { mutableStateOf<android.widget.VideoView?>(null) }
    var playing by remember(jsonText) { mutableStateOf(false) }
    var positionMs by remember(jsonText) { mutableStateOf(0L) }
    var videoDurationMs by remember(jsonText) { mutableStateOf(0L) }

    LaunchedEffect(jsonText) {
        if (filePath != null) return@LaunchedEffect
        loading = true
        filePath = withContext(Dispatchers.IO) { onDownloadMedia(jsonText) }?.absolutePath
        loading = false
    }

    // Поллинг позиции для прогресс-бара, пока видео играет
    LaunchedEffect(playing) {
        while (playing) {
            videoViewRef?.let { vv ->
                try {
                    positionMs = vv.currentPosition.toLong().coerceAtLeast(0L)
                    if (videoDurationMs <= 0L) videoDurationMs = vv.duration.toLong().coerceAtLeast(0L)
                } catch (_: Exception) {}
            }
            kotlinx.coroutines.delay(250)
        }
    }

    DisposableEffect(jsonText) {
        onDispose { ChatPlaybackCoordinator.release(jsonText) }
    }

    // Перемотка по доле прогресс-бара (тап или драг)
    fun seekVideo(fraction: Float) {
        val vv = videoViewRef ?: return
        val dur = videoDurationMs
        if (dur <= 0L) return
        val target = (fraction.coerceIn(0f, 1f) * dur).toLong()
        try {
            vv.seekTo(target.toInt())
            positionMs = target
        } catch (_: Exception) {}
    }

    Box(
        modifier = Modifier
            .width(300.dp)
            .height(220.dp)
            .clip(RoundedCornerShape(AetherStyle.MediaRadius))
            .background(Color.Black)
            .clickable(enabled = filePath != null) {
                // Play/pause по тапу — без системного MediaController
                val vv = videoViewRef ?: return@clickable
                if (playing) {
                    try { vv.pause() } catch (_: Exception) {}
                    playing = false
                    ChatPlaybackCoordinator.release(jsonText)
                } else {
                    // Старт видео останавливает другое медиа (ГС/кружок/видео)
                    ChatPlaybackCoordinator.claim(jsonText) {
                        try { videoViewRef?.pause() } catch (_: Exception) {}
                        playing = false
                    }
                    try {
                        vv.start()
                        playing = true
                    } catch (_: Exception) {
                        ChatPlaybackCoordinator.release(jsonText)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val path = filePath
        if (path != null) {
            AndroidView(
                factory = { ctx ->
                    android.widget.VideoView(ctx).apply {
                        setVideoPath(path)
                        setOnPreparedListener { player ->
                            player.isLooping = false
                            videoDurationMs = player.duration.toLong().coerceAtLeast(0L)
                            seekTo(1)
                            pause()
                        }
                        setOnCompletionListener {
                            playing = false
                            positionMs = 0L
                            ChatPlaybackCoordinator.release(jsonText)
                        }
                        videoViewRef = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Оверлей как у кружка: скрим-круг + белая иконка play/pause
            Box(
                modifier = Modifier
                    .size(AetherStyle.ControlSize)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.48f)),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = playing,
                    transitionSpec = {
                        (fadeIn(tween(appearance.motionDuration(150))) +
                            scaleIn(tween(appearance.motionDuration(180)), initialScale = 0.72f))
                            .togetherWith(
                                fadeOut(tween(appearance.motionDuration(100))) +
                                    scaleOut(tween(appearance.motionDuration(120)), targetScale = 0.78f)
                            )
                    },
                    label = "videoPlayPause"
                ) { isPlaying ->
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Пауза" else "Воспроизвести",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Подпись времени и тонкий прогресс-бар снизу (тап/драг — перемотка)
            if (videoDurationMs > 0L) {
                Text(
                    "${formatClock(positionMs)} / ${formatClock(videoDurationMs)}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 10.dp, bottom = 14.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.4f))
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(12.dp)
                        .pointerInput(videoDurationMs) {
                            detectTapGestures { offset ->
                                seekVideo(offset.x / size.width.coerceAtLeast(1))
                            }
                        }
                        .pointerInput(videoDurationMs) {
                            detectHorizontalDragGestures { change, _ ->
                                change.consume()
                                seekVideo(change.position.x / size.width.coerceAtLeast(1))
                            }
                        }
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val barH = 3.dp.toPx()
                        val y = size.height - barH - 2.dp.toPx()
                        drawRoundRect(
                            Color.White.copy(alpha = 0.35f),
                            topLeft = Offset(0f, y),
                            size = Size(size.width, barH),
                            cornerRadius = CornerRadius(barH / 2f)
                        )
                        val frac = (positionMs.toFloat() / videoDurationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
                        drawRoundRect(
                            Color.White,
                            topLeft = Offset(0f, y),
                            size = Size(size.width * frac, barH),
                            cornerRadius = CornerRadius(barH / 2f)
                        )
                    }
                }
            }
        } else if (loading) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp))
        } else {
            Text("Не удалось открыть видео", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Сообщение-документ (файл): иконка + имя + размер. Тап — скачать, расшифровать
 * и открыть системным просмотрщиком (через FileProvider, без прав на хранилище).
 */
@Composable
fun FileMessageBubble(
    jsonText: String,
    fileName: String,
    fileSize: Long,
    tint: Color,
    onDownloadMedia: suspend (String) -> File?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(min = 210.dp, max = 280.dp)
            .clickable(enabled = !loading) {
                loading = true
                scope.launch {
                    val source = withContext(Dispatchers.IO) { onDownloadMedia(jsonText) }
                    loading = false
                    if (source != null) {
                        try {
                            val safe = fileName.ifBlank { "file" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                            val f = File(context.cacheDir, safe)
                            withContext(Dispatchers.IO) { source.copyTo(f, overwrite = true) }
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", f
                            )
                            val mime = context.contentResolver.getType(uri) ?: "*/*"
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, mime)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(android.content.Intent.createChooser(intent, "Открыть файл"))
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "Не удалось открыть файл", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        android.widget.Toast.makeText(context, "Ошибка скачивания", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .padding(vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier.size(42.dp).clip(CircleShape).background(tint.copy(alpha = AetherStyle.SelectedFillAlpha)),
            contentAlignment = Alignment.Center
        ) {
            if (loading) CircularProgressIndicator(modifier = Modifier.size(22.dp), color = tint, strokeWidth = 2.dp)
            else Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, tint = tint)
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(fileName.ifBlank { "Файл" }, color = tint, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(formatFileSize(fileSize), color = tint.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Подпись времени mm:ss для плееров. */
private fun formatClock(ms: Long): String {
    val s = (ms / 1000L).toInt().coerceAtLeast(0)
    return String.format("%d:%02d", s / 60, s % 60)
}

/** Человекочитаемый размер файла. */
fun formatFileSize(bytes: Long): String = when {
    bytes <= 0 -> "файл"
    bytes < 1024 -> "$bytes Б"
    bytes < 1024 * 1024 -> String.format("%.0f КБ", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format("%.1f МБ", bytes / (1024.0 * 1024))
    else -> String.format("%.2f ГБ", bytes / (1024.0 * 1024 * 1024))
}

/**
 * Превью записанного голосового перед отправкой (review-режим как в Telegram):
 * play/pause + волна с двумя ручками обрезки. Файл локальный — без скачивания.
 */
@Composable
fun VoicePreviewBar(
    file: File,
    durationMs: Long,
    trimStart: Float,
    trimEnd: Float,
    onTrimChange: (Float, Float) -> Unit,
    tint: Color,
    accent: Color,
    modifier: Modifier = Modifier,
    waveform: List<Int>? = null
) {
    var isPlaying by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var progress by remember { mutableStateOf(0f) } // 0..1 по всему файлу

    val bars = remember(file.path, waveform) {
        if (!waveform.isNullOrEmpty()) {
            // Реальная огибающая записи: значения 0..31 -> высота бара 0..1
            FloatArray(waveform.size) { i -> (waveform[i].coerceIn(0, 31) / 31f).coerceAtLeast(0.08f) }
        } else {
            val rnd = java.util.Random(file.path.hashCode().toLong())
            FloatArray(48) { 0.25f + rnd.nextFloat() * 0.75f }
        }
    }

    LaunchedEffect(isPlaying, trimEnd) {
        while (isPlaying) {
            val p = player
            if (p != null) {
                val dur = p.duration.coerceAtLeast(1)
                val pos = (p.currentPosition.toFloat() / dur).coerceIn(0f, 1f)
                progress = pos
                if (pos >= trimEnd) {
                    try { p.pause() } catch (e: Exception) {}
                    isPlaying = false; progress = trimStart
                    ChatPlaybackCoordinator.release(file.path)
                }
            }
            kotlinx.coroutines.delay(40)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            ChatPlaybackCoordinator.release(file.path)
            try { player?.release() } catch (e: Exception) {}
            player = null
        }
    }

    // Превью тоже участвует в координаторе: не играет одновременно с лентой
    fun claimPreview() {
        ChatPlaybackCoordinator.claim(file.path) {
            try { player?.pause() } catch (e: Exception) {}
            isPlaying = false
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        IconButton(onClick = {
            val p = player
            when {
                isPlaying && p != null -> {
                    try { p.pause() } catch (e: Exception) {}
                    isPlaying = false
                    ChatPlaybackCoordinator.release(file.path)
                }
                p != null -> {
                    try {
                        claimPreview()
                        p.seekTo((trimStart * p.duration).toInt()); p.start(); isPlaying = true
                    } catch (e: Exception) {}
                }
                else -> {
                    try {
                        val mp = android.media.MediaPlayer()
                        try {
                            mp.setDataSource(file.absolutePath)
                            mp.setOnCompletionListener {
                                isPlaying = false; progress = trimStart
                                ChatPlaybackCoordinator.release(file.path)
                            }
                            mp.prepare()
                        } catch (e: Exception) {
                            // Не даём нативному плееру утечь при битом файле
                            mp.release()
                            throw e
                        }
                        player = mp
                        claimPreview()
                        mp.seekTo((trimStart * mp.duration).toInt())
                        mp.start()
                        isPlaying = true
                    } catch (e: Exception) { isPlaying = false }
                }
            }
        }) {
            Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = "Прослушать", tint = accent)
        }
        TrimWaveform(
            bars = bars, progress = progress,
            trimStart = trimStart, trimEnd = trimEnd,
            onTrimChange = { s, e ->
                onTrimChange(s, e)
                // при перетаскивании ручек — остановим проигрывание, чтобы не «убегало»
                player?.let {
                    try {
                        if (isPlaying) {
                            it.pause(); isPlaying = false
                            ChatPlaybackCoordinator.release(file.path)
                        }
                    } catch (ex: Exception) {}
                }
            },
            tint = tint, accent = accent,
            modifier = Modifier.weight(1f).height(40.dp)
        )
        Spacer(Modifier.width(8.dp))
        val secs = (((trimEnd - trimStart).coerceAtLeast(0f)) * durationMs / 1000L).toInt()
        Text(String.format("%d:%02d", secs / 60, secs % 60), color = tint, style = MaterialTheme.typography.labelSmall)
    }
}

/** Волна с двумя ручками обрезки. Тащим — двигается ближняя ручка. */
@Composable
private fun TrimWaveform(
    bars: FloatArray,
    progress: Float,
    trimStart: Float,
    trimEnd: Float,
    onTrimChange: (Float, Float) -> Unit,
    tint: Color,
    accent: Color,
    modifier: Modifier
) {
    var widthPx by remember { mutableStateOf(1f) }
    Box(
        modifier = modifier
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { off ->
                        val x = (off.x / widthPx).coerceIn(0f, 1f)
                        val dStart = kotlin.math.abs(x - trimStart)
                        val dEnd = kotlin.math.abs(x - trimEnd)
                        if (dStart <= dEnd) onTrimChange(x.coerceIn(0f, trimEnd - 0.05f), trimEnd)
                        else onTrimChange(trimStart, x.coerceIn(trimStart + 0.05f, 1f))
                    },
                    onDrag = { change, _ ->
                        val x = (change.position.x / widthPx).coerceIn(0f, 1f)
                        val dStart = kotlin.math.abs(x - trimStart)
                        val dEnd = kotlin.math.abs(x - trimEnd)
                        if (dStart <= dEnd) onTrimChange(x.coerceIn(0f, trimEnd - 0.05f), trimEnd)
                        else onTrimChange(trimStart, x.coerceIn(trimStart + 0.05f, 1f))
                        change.consume()
                    }
                )
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val n = bars.size
            val gap = 2.dp.toPx()
            val barW = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
            val playedUpTo = progress * n
            for (i in 0 until n) {
                val frac = (i + 0.5f) / n
                val inTrim = frac in trimStart..trimEnd
                val h = (size.height * bars[i]).coerceAtLeast(barW)
                val x = i * (barW + gap)
                val y = (size.height - h) / 2f
                val played = i < playedUpTo && inTrim
                val c = when {
                    !inTrim -> tint.copy(alpha = 0.15f)
                    played -> accent
                    else -> tint.copy(alpha = 0.45f)
                }
                drawRoundRect(c, topLeft = Offset(x, y), size = Size(barW, h), cornerRadius = CornerRadius(barW / 2f, barW / 2f))
            }
            // Ручки обрезки
            val handleW = 3.dp.toPx()
            drawRoundRect(accent, topLeft = Offset((trimStart * size.width).coerceIn(0f, size.width - handleW), 0f), size = Size(handleW, size.height), cornerRadius = CornerRadius(handleW, handleW))
            drawRoundRect(accent, topLeft = Offset((trimEnd * size.width - handleW).coerceIn(0f, size.width - handleW), 0f), size = Size(handleW, size.height), cornerRadius = CornerRadius(handleW, handleW))
        }
    }
}

/**
 * Видео-кружок (как в Telegram): по умолчанию маленький беззвучный зацикленный
 * предпросмотр. Тап — играет СО ЗВУКОМ и увеличивается; ещё тап — пауза/продолжить.
 * Вокруг — кольцо прогресса воспроизведения.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoNoteMessage(
    jsonText: String,
    cachedMediaFile: (String) -> File? = { null },
    onDownloadMedia: suspend (String) -> File?,
    onLongClick: (() -> Unit)? = null
) {
    val appearance = LocalThemeSettings.current
    var filePath by remember(jsonText) { mutableStateOf(cachedMediaFile(jsonText)?.absolutePath) }
    var loading by remember(jsonText) { mutableStateOf(filePath == null) }
    var mediaPlayerRef by remember(jsonText) { mutableStateOf<android.media.MediaPlayer?>(null) }
    var prepared by remember(jsonText) { mutableStateOf(false) }
    var active by remember(jsonText) { mutableStateOf(false) }
    var playing by remember(jsonText) { mutableStateOf(false) }
    var progress by remember(jsonText) { mutableStateOf(0f) }
    // Позиция на момент паузы/пересоздания surface: продолжаем с неё, а не с нуля
    var savedPositionMs by remember(jsonText) { mutableStateOf(0) }
    val scale by animateFloatAsState(
        targetValue = if (active) 1f else 0.97f,
        animationSpec = tween(appearance.motionDuration(240), easing = FastOutSlowInEasing),
        label = "videoNoteScale"
    )

    LaunchedEffect(jsonText) {
        if (filePath != null) return@LaunchedEffect
        loading = true
        filePath = withContext(Dispatchers.IO) { onDownloadMedia(jsonText) }?.absolutePath
        loading = false
    }

    LaunchedEffect(active, playing, mediaPlayerRef, prepared, playbackSpeedState.value) {
        val mp = mediaPlayerRef ?: return@LaunchedEffect
        if (!prepared) return@LaunchedEffect
        try {
            if (active && playing) {
                mp.setVolume(1f, 1f)
                if (!mp.isPlaying) mp.start()
                // Глобальная скорость 1x/1.5x/2x действует и на кружок
                applyPlaybackSpeed(mp, playbackSpeedState.value)
            } else if (mp.isPlaying) {
                mp.pause()
            }
        } catch (_: Exception) {}
    }

    LaunchedEffect(active, playing, mediaPlayerRef, prepared) {
        while (active && playing && prepared) {
            mediaPlayerRef?.let { mp ->
                try {
                    progress = (mp.currentPosition.toFloat() / mp.duration.coerceAtLeast(1))
                        .coerceIn(0f, 1f)
                } catch (_: Exception) {}
            }
            kotlinx.coroutines.delay(80)
        }
    }

    DisposableEffect(jsonText) {
        onDispose {
            ChatPlaybackCoordinator.release(jsonText)
            try { mediaPlayerRef?.release() } catch (_: Exception) {}
            mediaPlayerRef = null
        }
    }

    Box(
        modifier = Modifier
            .size(200.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(Color.Black)
            .combinedClickable(
                onClick = {
                    if (!active) {
                        ChatPlaybackCoordinator.claim(jsonText) {
                            // Чужой claim — пауза, а не закрытие: кружок остаётся
                            // увеличенным и запоминает позицию для продолжения
                            try { mediaPlayerRef?.let { savedPositionMs = it.currentPosition } } catch (_: Exception) {}
                            playing = false
                        }
                        active = true
                        playing = true
                    } else if (playing) {
                        try { mediaPlayerRef?.let { savedPositionMs = it.currentPosition } } catch (_: Exception) {}
                        playing = false
                        ChatPlaybackCoordinator.release(jsonText)
                    } else {
                        ChatPlaybackCoordinator.claim(jsonText) {
                            try { mediaPlayerRef?.let { savedPositionMs = it.currentPosition } } catch (_: Exception) {}
                            playing = false
                        }
                        playing = true
                    }
                },
                onLongClick = onLongClick
            )
            .pointerInput(active, mediaPlayerRef) {
                if (!active) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val radius = kotlin.math.min(cx, cy)
                    val dist = kotlin.math.hypot(
                        (down.position.x - cx).toDouble(),
                        (down.position.y - cy).toDouble()
                    )
                    // Перемотка только с кольца (внешние ~22% радиуса): внутренние
                    // касания не перехватываем — они уходят тапу play/pause и скроллу
                    if (dist <= radius * 0.78) return@awaitEachGesture
                    var dragging = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        if (!dragging) {
                            val moved = (ch.position - down.position).getDistance()
                            if (moved > viewConfiguration.touchSlop) dragging = true
                        }
                        if (dragging) {
                            ch.consume()
                            var degrees = Math.toDegrees(
                                kotlin.math.atan2(
                                    (ch.position.y - cy).toDouble(),
                                    (ch.position.x - cx).toDouble()
                                )
                            ).toFloat() + 90f
                            if (degrees < 0f) degrees += 360f
                            val fraction = (degrees / 360f).coerceIn(0f, 1f)
                            mediaPlayerRef?.let { mp ->
                                try {
                                    mp.seekTo((fraction * mp.duration.coerceAtLeast(1)).toInt())
                                    progress = fraction
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val path = filePath
        if (path != null) {
            coil.compose.AsyncImage(
                model = File(path),
                contentDescription = "Видео-кружок",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            AnimatedVisibility(
                visible = active,
                enter = fadeIn(tween(appearance.motionDuration(180))),
                exit = fadeOut(tween(appearance.motionDuration(150)))
            ) {
                key(path) {
                    AndroidView(
                        factory = { ctx ->
                            android.view.TextureView(ctx).apply {
                                surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(
                                        texture: android.graphics.SurfaceTexture,
                                        width: Int,
                                        height: Int
                                    ) {
                                        val surface = android.view.Surface(texture)
                                        prepared = false
                                        val mp = android.media.MediaPlayer()
                                        try {
                                            mp.setDataSource(path)
                                            mp.setSurface(surface)
                                            surface.release()
                                            mp.isLooping = false
                                            mp.setOnPreparedListener {
                                                if (mediaPlayerRef === it) {
                                                    // Продолжаем с позиции, сохранённой при
                                                    // паузе координатором или скролле
                                                    val dur = try { it.duration } catch (_: Exception) { 0 }
                                                    if (savedPositionMs in 1 until dur) {
                                                        try { it.seekTo(savedPositionMs) } catch (_: Exception) {}
                                                    }
                                                    prepared = true
                                                }
                                            }
                                            mp.setOnCompletionListener {
                                                if (mediaPlayerRef === it) {
                                                    prepared = false
                                                    playing = false
                                                    active = false
                                                    progress = 0f
                                                    savedPositionMs = 0
                                                    ChatPlaybackCoordinator.release(jsonText)
                                                }
                                            }
                                            mp.setOnErrorListener { failed, _, _ ->
                                                if (mediaPlayerRef === failed) {
                                                    prepared = false
                                                    playing = false
                                                    active = false
                                                    ChatPlaybackCoordinator.release(jsonText)
                                                }
                                                true
                                            }
                                            mediaPlayerRef = mp
                                            mp.prepareAsync()
                                        } catch (_: Exception) {
                                            // Файл битый/удалён из кэша: не даём утечь ни
                                            // нативному плееру, ни surface
                                            try { mp.release() } catch (_: Exception) {}
                                            try { surface.release() } catch (_: Exception) {}
                                            if (mediaPlayerRef === mp) mediaPlayerRef = null
                                            active = false
                                            playing = false
                                        }
                                    }

                                    override fun onSurfaceTextureSizeChanged(
                                        texture: android.graphics.SurfaceTexture,
                                        width: Int,
                                        height: Int
                                    ) = Unit

                                    override fun onSurfaceTextureDestroyed(
                                        texture: android.graphics.SurfaceTexture
                                    ): Boolean {
                                        // Запоминаем позицию: после re-prepare продолжим с неё
                                        try { mediaPlayerRef?.let { savedPositionMs = it.currentPosition } } catch (_: Exception) {}
                                        try { mediaPlayerRef?.release() } catch (_: Exception) {}
                                        mediaPlayerRef = null
                                        prepared = false
                                        return true
                                    }

                                    override fun onSurfaceTextureUpdated(
                                        texture: android.graphics.SurfaceTexture
                                    ) = Unit
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            if (active && playing && !prepared) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            }

            if (active) {
                CircularProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxSize().padding(2.dp),
                    color = Color.White,
                    strokeWidth = 3.dp,
                    trackColor = Color.White.copy(alpha = 0.25f)
                )
            }

            AnimatedVisibility(
                visible = !active || !playing,
                enter = fadeIn(tween(appearance.motionDuration(150))) +
                    scaleIn(tween(appearance.motionDuration(180)), initialScale = 0.72f),
                exit = fadeOut(tween(appearance.motionDuration(100))) +
                    scaleOut(tween(appearance.motionDuration(120)), targetScale = 0.78f)
            ) {
                Box(
                    modifier = Modifier
                        .size(if (active) AetherStyle.ControlSize else AetherStyle.SmallControlSize)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.48f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Играть",
                        tint = Color.White,
                        modifier = Modifier.size(if (active) 32.dp else 28.dp)
                    )
                }
            }
        } else if (loading) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp))
        }
    }
}
