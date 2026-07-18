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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import org.groktest.securemessenger.ui.theme.LocalThemeSettings

/**
 * Голосовое сообщение: кнопка play/pause + дорожка + длительность.
 * Файл скачивается и расшифровывается по первому нажатию, затем кэшируется.
 */
private object ChatPlaybackCoordinator {
    private var owner: String? = null
    private var stop: (() -> Unit)? = null

    fun claim(key: String, onStop: () -> Unit) {
        if (owner != key) stop?.invoke()
        owner = key
        stop = onStop
    }

    fun release(key: String) {
        if (owner == key) {
            owner = null
            stop = null
        }
    }
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
    onDownloadMedia: suspend (String) -> File?
) {
    val scope = rememberCoroutineScope()
    val appearance = LocalThemeSettings.current
    var isPlaying by remember(jsonText) { mutableStateOf(false) }
    var isLoading by remember(jsonText) { mutableStateOf(false) }
    var player by remember(jsonText) { mutableStateOf<android.media.MediaPlayer?>(null) }
    var progress by remember(jsonText) { mutableStateOf(0f) }

    val bars = remember(jsonText) {
        val rnd = java.util.Random(jsonText.hashCode().toLong())
        FloatArray(36) { 0.25f + rnd.nextFloat() * 0.75f }
    }

    fun play(p: android.media.MediaPlayer) {
        ChatPlaybackCoordinator.claim(jsonText) {
            try { if (p.isPlaying) p.pause() } catch (_: Exception) {}
            isPlaying = false
        }
        try {
            p.start()
            isPlaying = true
        } catch (_: Exception) {
            isPlaying = false
            ChatPlaybackCoordinator.release(jsonText)
        }
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
                            val file = withContext(Dispatchers.IO) { onDownloadMedia(jsonText) }
                            try {
                                if (file != null) {
                                    val mp = withContext(Dispatchers.IO) {
                                        android.media.MediaPlayer().apply {
                                            setDataSource(file.absolutePath)
                                            prepare()
                                        }
                                    }
                                    mp.setOnCompletionListener {
                                        isPlaying = false
                                        progress = 0f
                                        ChatPlaybackCoordinator.release(jsonText)
                                    }
                                    player = mp
                                    play(mp)
                                }
                            } catch (_: Exception) {
                                isPlaying = false
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
        val shownMs = if (progress > 0f) (durationMs * progress).toLong() else durationMs
        val secs = (shownMs / 1000L).toInt()
        Text(String.format("%d:%02d", secs / 60, secs % 60), color = tint, fontSize = 12.sp)
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
    var filePath by remember(jsonText) { mutableStateOf(cachedMediaFile(jsonText)?.absolutePath) }
    var loading by remember(jsonText) { mutableStateOf(filePath == null) }

    LaunchedEffect(jsonText) {
        if (filePath != null) return@LaunchedEffect
        loading = true
        filePath = withContext(Dispatchers.IO) { onDownloadMedia(jsonText) }?.absolutePath
        loading = false
    }

    Box(
        modifier = Modifier
            .width(300.dp)
            .height(220.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        val path = filePath
        if (path != null) {
            AndroidView(
                factory = { ctx ->
                    android.widget.VideoView(ctx).apply {
                        setMediaController(android.widget.MediaController(ctx).also { it.setAnchorView(this) })
                        setVideoPath(path)
                        setOnPreparedListener { player ->
                            player.isLooping = false
                            seekTo(1)
                            pause()
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else if (loading) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp))
        } else {
            Text("Не удалось открыть видео", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
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
            modifier = Modifier.size(42.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            if (loading) CircularProgressIndicator(modifier = Modifier.size(22.dp), color = tint, strokeWidth = 2.dp)
            else Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, tint = tint)
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(fileName.ifBlank { "Файл" }, color = tint, fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(formatFileSize(fileSize), color = tint.copy(alpha = 0.7f), fontSize = 12.sp)
        }
    }
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
    modifier: Modifier = Modifier
) {
    var isPlaying by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var progress by remember { mutableStateOf(0f) } // 0..1 по всему файлу

    val bars = remember(file.path) {
        val rnd = java.util.Random(file.path.hashCode().toLong())
        FloatArray(48) { 0.25f + rnd.nextFloat() * 0.75f }
    }

    LaunchedEffect(isPlaying, trimEnd) {
        while (isPlaying) {
            val p = player
            if (p != null) {
                val dur = p.duration.coerceAtLeast(1)
                val pos = (p.currentPosition.toFloat() / dur).coerceIn(0f, 1f)
                progress = pos
                if (pos >= trimEnd) { try { p.pause() } catch (e: Exception) {}; isPlaying = false; progress = trimStart }
            }
            kotlinx.coroutines.delay(40)
        }
    }
    DisposableEffect(Unit) { onDispose { try { player?.release() } catch (e: Exception) {}; player = null } }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        IconButton(onClick = {
            val p = player
            when {
                isPlaying && p != null -> { try { p.pause() } catch (e: Exception) {}; isPlaying = false }
                p != null -> { try { p.seekTo((trimStart * p.duration).toInt()); p.start(); isPlaying = true } catch (e: Exception) {} }
                else -> {
                    try {
                        val mp = android.media.MediaPlayer()
                        mp.setDataSource(file.absolutePath)
                        mp.setOnCompletionListener { isPlaying = false; progress = trimStart }
                        mp.prepare()
                        mp.seekTo((trimStart * mp.duration).toInt())
                        mp.start()
                        player = mp; isPlaying = true
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
                player?.let { try { if (isPlaying) { it.pause(); isPlaying = false } } catch (ex: Exception) {} }
            },
            tint = tint, accent = accent,
            modifier = Modifier.weight(1f).height(40.dp)
        )
        Spacer(Modifier.width(8.dp))
        val secs = (((trimEnd - trimStart).coerceAtLeast(0f)) * durationMs / 1000L).toInt()
        Text(String.format("%d:%02d", secs / 60, secs % 60), color = tint, fontSize = 12.sp)
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

    LaunchedEffect(active, playing, mediaPlayerRef, prepared) {
        val mp = mediaPlayerRef ?: return@LaunchedEffect
        if (!prepared) return@LaunchedEffect
        try {
            if (active && playing) {
                mp.setVolume(1f, 1f)
                if (!mp.isPlaying) mp.start()
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
                            playing = false
                            active = false
                        }
                        active = true
                        playing = true
                    } else if (playing) {
                        playing = false
                        ChatPlaybackCoordinator.release(jsonText)
                    } else {
                        ChatPlaybackCoordinator.claim(jsonText) {
                            playing = false
                            active = false
                        }
                        playing = true
                    }
                },
                onLongClick = onLongClick
            )
            .pointerInput(active, mediaPlayerRef) {
                if (!active) return@pointerInput
                detectDragGestures { change, _ ->
                    change.consume()
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    var degrees = Math.toDegrees(
                        kotlin.math.atan2(
                            (change.position.y - cy).toDouble(),
                            (change.position.x - cx).toDouble()
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
                                        try {
                                            val surface = android.view.Surface(texture)
                                            prepared = false
                                            val mp = android.media.MediaPlayer()
                                            mp.setDataSource(path)
                                            mp.setSurface(surface)
                                            surface.release()
                                            mp.isLooping = false
                                            mp.setOnPreparedListener {
                                                if (mediaPlayerRef === it) prepared = true
                                            }
                                            mp.setOnCompletionListener {
                                                if (mediaPlayerRef === it) {
                                                    prepared = false
                                                    playing = false
                                                    active = false
                                                    progress = 0f
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
                        .size(if (active) 54.dp else 46.dp)
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
