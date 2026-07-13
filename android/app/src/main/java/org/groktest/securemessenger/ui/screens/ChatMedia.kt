package org.groktest.securemessenger.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

/**
 * Голосовое сообщение: кнопка play/pause + дорожка + длительность.
 * Файл скачивается и расшифровывается по первому нажатию, затем кэшируется.
 */
@Composable
fun VoiceMessagePlayer(
    jsonText: String,
    durationMs: Long,
    tint: Color,
    onDownloadMedia: suspend (String) -> File?
) {
    val scope = rememberCoroutineScope()
    var isPlaying by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var progress by remember { mutableStateOf(0f) }

    // Стабильная «волна» из хэша сообщения — высоты столбиков 0.25..1.0.
    val bars = remember(jsonText) {
        val rnd = java.util.Random(jsonText.hashCode().toLong())
        FloatArray(36) { 0.25f + rnd.nextFloat() * 0.75f }
    }

    // Прогресс воспроизведения для заливки волны.
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            val p = player
            if (p != null) {
                val dur = p.duration.coerceAtLeast(1)
                progress = (p.currentPosition.toFloat() / dur).coerceIn(0f, 1f)
            }
            kotlinx.coroutines.delay(50)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try { player?.release() } catch (e: Exception) {}
            player = null
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.widthIn(min = 160.dp).padding(vertical = 2.dp)
    ) {
        IconButton(
            onClick = {
                val p = player
                when {
                    isPlaying && p != null -> { p.pause(); isPlaying = false }
                    p != null -> { p.start(); isPlaying = true }
                    else -> {
                        isLoading = true
                        scope.launch {
                            val file = withContext(Dispatchers.IO) { onDownloadMedia(jsonText) }
                            isLoading = false
                            if (file != null) {
                                try {
                                    val mp = withContext(Dispatchers.IO) {
                                        android.media.MediaPlayer().apply {
                                            setDataSource(file.absolutePath)
                                            prepare()
                                        }
                                    }
                                    mp.setOnCompletionListener { isPlaying = false; progress = 0f }
                                    mp.start()
                                    player = mp
                                    isPlaying = true
                                } catch (e: Exception) {
                                    isPlaying = false
                                }
                            }
                        }
                    }
                }
            }
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = tint, strokeWidth = 2.dp)
            } else {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "Воспроизвести",
                    tint = tint
                )
            }
        }
        Waveform(
            bars = bars,
            progress = progress,
            tint = tint,
            modifier = Modifier
                .height(26.dp)
                .width(130.dp)
        )
        Spacer(Modifier.width(8.dp))
        val secs = (durationMs / 1000L).toInt()
        Text(String.format("%d:%02d", secs / 60, secs % 60), color = tint, fontSize = 12.sp)
    }
}

/** Волна голосового: столбики с заливкой по прогрессу воспроизведения (как в Telegram). */
@Composable
private fun Waveform(bars: FloatArray, progress: Float, tint: Color, modifier: Modifier) {
    Canvas(modifier = modifier) {
        val n = bars.size
        val gap = 2.dp.toPx()
        val barW = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        val playedUpTo = progress * n
        for (i in 0 until n) {
            val h = (size.height * bars[i]).coerceAtLeast(barW)
            val x = i * (barW + gap)
            val y = (size.height - h) / 2f
            val played = i < playedUpTo
            drawRoundRect(
                color = if (played) tint else tint.copy(alpha = 0.3f),
                topLeft = Offset(x, y),
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
    var filePath by remember(jsonText) { mutableStateOf(cachedMediaFile(jsonText)?.absolutePath) }
    var loading by remember(jsonText) { mutableStateOf(filePath == null) }
    var mediaPlayerRef by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    // active = открыт со звуком и увеличен; playing = играет (vs пауза) в active-режиме.
    var active by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(true) }
    var progress by remember { mutableStateOf(0f) }

    val size by animateDpAsState(if (active) 280.dp else 200.dp, label = "noteSize")

    LaunchedEffect(jsonText) {
        if (filePath != null) return@LaunchedEffect
        loading = true
        filePath = withContext(Dispatchers.IO) { onDownloadMedia(jsonText) }?.absolutePath
        loading = false
    }

    // Применяем состояние к плееру: предпросмотр (беззвучно, играет) ↔ активный (звук, play/pause).
    LaunchedEffect(active, playing, mediaPlayerRef) {
        val mp = mediaPlayerRef ?: return@LaunchedEffect
        try {
            if (active) {
                mp.setVolume(1f, 1f)
                if (playing) { if (!mp.isPlaying) mp.start() } else if (mp.isPlaying) mp.pause()
            } else {
                mp.setVolume(0f, 0f)
                if (!mp.isPlaying) mp.start()
            }
        } catch (e: Exception) {}
    }

    // Кольцо прогресса, пока активный и играет.
    LaunchedEffect(active, playing, mediaPlayerRef) {
        while (active && playing) {
            val mp = mediaPlayerRef
            if (mp != null) {
                val dur = mp.duration.coerceAtLeast(1)
                progress = (mp.currentPosition.toFloat() / dur).coerceIn(0f, 1f)
            }
            kotlinx.coroutines.delay(60)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try { mediaPlayerRef?.release() } catch (e: Exception) {}
            mediaPlayerRef = null
        }
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black)
            .combinedClickable(
                onClick = {
                    if (!active) { active = true; playing = true } else { playing = !playing }
                },
                onLongClick = onLongClick
            )
            // Перемотка: тянешь по кругу (за кольцо) — угол → позиция (только в active).
            .pointerInput(active) {
                if (!active) return@pointerInput
                detectDragGestures { change, _ ->
                    change.consume()
                    val cx = this.size.width / 2f
                    val cy = this.size.height / 2f
                    var deg = Math.toDegrees(
                        kotlin.math.atan2((change.position.y - cy).toDouble(), (change.position.x - cx).toDouble())
                    ).toFloat() + 90f   // 0° = верх (12 часов), по часовой
                    if (deg < 0f) deg += 360f
                    val frac = (deg / 360f).coerceIn(0f, 1f)
                    mediaPlayerRef?.let { mp ->
                        val dur = mp.duration.coerceAtLeast(1)
                        try { mp.seekTo((frac * dur).toInt()) } catch (e: Exception) {}
                        progress = frac
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val path = filePath
        if (path != null) {
            // TextureView (а не VideoView/SurfaceView) — совместим с RenderEffect-стеклом.
            AndroidView(
                factory = { ctx ->
                    android.view.TextureView(ctx).apply {
                        surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                                try {
                                    val mp = android.media.MediaPlayer()
                                    mp.setDataSource(path)
                                    mp.setSurface(android.view.Surface(st))
                                    mp.isLooping = true
                                    mp.setVolume(0f, 0f)
                                    mp.setOnPreparedListener { it.start() }
                                    mp.prepareAsync()
                                    mediaPlayerRef = mp
                                } catch (e: Exception) {}
                            }
                            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                                try { mediaPlayerRef?.release() } catch (e: Exception) {}
                                mediaPlayerRef = null
                                return true
                            }
                            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Кольцо прогресса (только в активном режиме)
            if (active) {
                CircularProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxSize().padding(2.dp),
                    color = Color.White,
                    strokeWidth = 3.dp,
                    trackColor = Color.White.copy(alpha = 0.25f)
                )
            }

            // Иконка: пауза → большой ▶ по центру; предпросмотр → беззвучный значок в углу.
            if (active && !playing) {
                Box(
                    modifier = Modifier.size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Играть", tint = Color.White, modifier = Modifier.size(34.dp))
                }
            } else if (!active) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.VolumeOff, contentDescription = "Без звука", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        } else if (loading) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp))
        }
    }
}
