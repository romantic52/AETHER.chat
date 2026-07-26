package org.groktest.securemessenger.ui.screens

import androidx.camera.core.CameraSelector
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import org.groktest.securemessenger.ui.theme.AetherStyle
import org.groktest.securemessenger.ui.theme.LocalThemeSettings

private const val MAX_NOTE_SECONDS = 60
/** Миллисекунд в секунде — для фолбэка длительности из счётчика секунд. */
private const val MS_PER_SECOND = 1000L
/** Записи короче — случайный тап: файл удаляем и продолжаем запись, превью не открываем. */
private const val MIN_RECORD_MS = 700L
/** Минимальное окно обрезки на слайдере. */
private const val MIN_TRIM_WINDOW_MS = 1000L
/** Слак у правого края: обрезка ближе к концу ролика считается «без обрезки». */
private const val TRIM_END_SLACK_MS = 250L
/** Период опроса позиции плеера превью для лупа выбранного окна. */
private const val PREVIEW_POLL_MS = 100L

/** Удаление временного файла вне отменяемого scope: rememberCoroutineScope к моменту dismiss уже отменён. */
private fun deleteQuietly(file: File?) {
    if (file == null) return
    CoroutineScope(NonCancellable + Dispatchers.IO).launch {
        try { file.delete() } catch (_: Exception) {}
    }
}

/**
 * Запись видео-кружка (как в Telegram). Открывается и сразу пишет квадратное видео
 * (ViewPort 1:1). Стоп → превью: круглое зацикленное видео + таймлайн обрезки →
 * «Отправить»/«Переснять». onResult(file) — готовый mp4; onResult(null) — отмена.
 */
@Composable
fun VideoNoteRecorder(
    onResult: (File?) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val appearance = LocalThemeSettings.current

    // "rec" — идёт запись; "preview" — обрезка/отправка записанного.
    var phase by remember { mutableStateOf("rec") }
    var isRecording by remember { mutableStateOf(false) }
    var seconds by remember { mutableStateOf(0) }
    var useFrontCamera by remember { mutableStateOf(true) }
    var canSwitchCamera by remember { mutableStateOf(false) }
    var isSwitchingCamera by remember { mutableStateOf(false) }
    var cameraTurns by remember { mutableIntStateOf(0) }
    var pendingAction by remember { mutableStateOf("") }
    var recordingStartedAt by remember { mutableLongStateOf(0L) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    // Стоп уже запрошен, но Finalize ещё не пришёл: в этом окне запись заново не стартуем.
    var stopInProgress by remember { mutableStateOf(false) }
    // Файл отдан наружу через onResult — onDispose его не удаляет.
    var fileHandedOut by remember { mutableStateOf(false) }

    val previewView = remember {
        PreviewView(context).apply {
            // TextureView вместо SurfaceView: SurfaceView игнорирует Modifier.clip(CircleShape).
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val recorder = remember {
        Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    Quality.SD,
                    FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
                )
            )
            .build()
    }
    val videoCapture = remember {
        VideoCapture.Builder(recorder)
            .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
            .build()
    }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var outputFile by remember {
        mutableStateOf(File(context.cacheDir, "note_rec_${System.currentTimeMillis()}.mp4"))
    }

    var previewFile by remember { mutableStateOf<File?>(null) }
    var durMs by remember { mutableStateOf(0L) }
    var trimStart by remember { mutableStateOf(0f) }
    var trimEnd by remember { mutableStateOf(1f) }
    var sending by remember { mutableStateOf(false) }
    // Плеер записанного кружка: TextureView + MediaPlayer (VideoView на SurfaceView кругом не клипуется).
    val previewPlayer = remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var previewReady by remember { mutableStateOf(false) }

    fun unbindCamera() {
        try { provider?.unbindAll() } catch (e: Exception) {}
    }

    fun startRecording() {
        val target = outputFile
        try {
            @Suppress("MissingPermission")
            recording = videoCapture.output
                .prepareRecording(context, FileOutputOptions.Builder(target).build())
                .asPersistentRecording()
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    if (event !is VideoRecordEvent.Finalize) return@start
                    recording = null
                    isRecording = false
                    stopInProgress = false
                    val action = pendingAction
                    pendingAction = ""
                    val ok = !event.hasError() && target.exists() && target.length() > 0L
                    val recordedMs = (android.os.SystemClock.elapsedRealtime() - recordingStartedAt)
                        .coerceAtLeast(0L)

                    if (action == "cancel") {
                        unbindCamera()
                        deleteQuietly(target)
                        onResult(null)
                    } else if (ok && recordedMs < MIN_RECORD_MS) {
                        // Слишком короткая запись — случайный тап по стопу: файл удаляем и
                        // пишем заново (камера ещё привязана, превью не открываем).
                        deleteQuietly(target)
                        outputFile = File(context.cacheDir, "note_rec_${System.currentTimeMillis()}.mp4")
                        seconds = 0
                        elapsedMs = 0L
                        startRecording()
                    } else if (ok) {
                        unbindCamera()
                        scope.launch {
                            val measured = withContext(Dispatchers.IO) { VideoUtils.durationMs(target) }
                            previewFile = target
                            durMs = measured.takeIf { it > 0L } ?: (seconds * MS_PER_SECOND)
                            trimStart = 0f
                            trimEnd = 1f
                            phase = "preview"
                        }
                    } else {
                        unbindCamera()
                        android.util.Log.w("VideoNote", "record finalize error=${event.error}")
                        onResult(null)
                    }
                }
            recordingStartedAt = android.os.SystemClock.elapsedRealtime()
            isRecording = true
        } catch (e: Exception) {
            android.util.Log.e("VideoNote", "start error: ${e.message}")
            onResult(null)
        }
    }

    // Одна и та же persistent-запись переживает unbind/rebind при смене камеры.
    // CameraX использует Camera2, а MirrorMode зеркалит итог фронтальной камеры.
    LaunchedEffect(phase, useFrontCamera) {
        if (phase != "rec") return@LaunchedEffect
        isSwitchingCamera = recording != null
        try {
            val p = provider ?: withContext(Dispatchers.IO) {
                ProcessCameraProvider.getInstance(context).get()
            }.also { provider = it }
            val hasFront = try { p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) } catch (_: Exception) { false }
            val hasBack = try { p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) } catch (_: Exception) { false }
            canSwitchCamera = hasFront && hasBack

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val rotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
            val viewPort = ViewPort.Builder(android.util.Rational(1, 1), rotation).build()
            val preferred = if (useFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            val fallback = if (useFrontCamera) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }
            val candidates = (listOf(preferred, fallback) +
                p.availableCameraInfos.map { it.cameraSelector }).distinct()
            var bound = false
            for (selector in candidates) {
                try {
                    p.unbindAll()
                    val group = UseCaseGroup.Builder()
                        .addUseCase(preview)
                        .addUseCase(videoCapture)
                        .setViewPort(viewPort)
                        .build()
                    p.bindToLifecycle(lifecycleOwner, selector, group)
                    bound = true
                    break
                } catch (e: Exception) {
                    android.util.Log.w("VideoNote", "bind failed: ${e.message}")
                }
            }
            if (!bound) {
                onResult(null)
                return@LaunchedEffect
            }
            // Во время остановки (Finalize ещё не пришёл) запись заново не стартуем.
            if (recording == null && !stopInProgress && phase == "rec") startRecording()
        } catch (e: Exception) {
            android.util.Log.e("VideoNote", "camera init error: ${e.message}")
            onResult(null)
        } finally {
            isSwitchingCamera = false
        }
    }
    // Таймер на монотонных часах не прыгает при смене камеры или времени устройства.
    LaunchedEffect(isRecording, recordingStartedAt) {
        while (isRecording) {
            elapsedMs = (android.os.SystemClock.elapsedRealtime() - recordingStartedAt)
                .coerceAtLeast(0L)
            seconds = (elapsedMs / MS_PER_SECOND).toInt()
            if (seconds >= MAX_NOTE_SECONDS) {
                // Автостоп по лимиту: помечаем остановку, чтобы switchCamera/rebind не перезапустили запись.
                pendingAction = "preview"
                stopInProgress = true
                try { recording?.stop() } catch (_: Exception) {}
                break
            }
            delay(200)
        }
    }
    // Не даём экрану погаснуть во время записи (таймаут дисплея обычно короче лимита 60с).
    LaunchedEffect(phase) { previewView.keepScreenOn = phase == "rec" }
    // Луп выбранного окна обрезки: вышли за правую ручку (или плеер зациклился на начало
    // файла раньше левой) — возвращаемся к началу окна.
    LaunchedEffect(phase, previewReady, trimStart, trimEnd, durMs) {
        if (phase != "preview" || !previewReady) return@LaunchedEffect
        var lastPos = -1
        while (true) {
            val mp = previewPlayer.value ?: break
            val sMs = (trimStart * durMs).toInt()
            val eMs = (trimEnd * durMs).toInt().coerceAtLeast(sMs + 1)
            try {
                val pos = mp.currentPosition
                // Скачок назад больше секунды до левой ручки — сработал isLooping на конце файла.
                if (pos > eMs || (lastPos >= 0 && pos < lastPos - MS_PER_SECOND.toInt() && pos < sMs)) {
                    mp.seekTo(sMs)
                    lastPos = -1
                } else {
                    lastPos = pos
                }
            } catch (_: Exception) {
                break
            }
            delay(PREVIEW_POLL_MS)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (recording != null) pendingAction = "cancel"
            try { recording?.stop() } catch (_: Exception) {}
            unbindCamera()
            previewPlayer.value?.let { try { it.release() } catch (_: Exception) {} }
            previewPlayer.value = null
            // Подчищаем временные файлы, если они не отданы наружу через onResult
            // (back в фазе превью минует cancelAll, а scope здесь уже отменён).
            if (!fileHandedOut) {
                deleteQuietly(previewFile)
                if (outputFile != previewFile) deleteQuietly(outputFile)
            }
        }
    }

    fun stopToPreview() {
        if (!isRecording || stopInProgress) return
        // Помечаем остановку до stop(): switchCamera/rebind в этом окне запись не перезапустят.
        stopInProgress = true
        pendingAction = "preview"
        isRecording = false
        try { recording?.stop() } catch (e: Exception) { stopInProgress = false; onResult(null) }
    }

    fun cancelAll() {
        if (isRecording) {
            stopInProgress = true
            pendingAction = "cancel"
            isRecording = false
            try { recording?.stop() } catch (e: Exception) { unbindCamera(); onResult(null) }
        } else {
            unbindCamera()
            deleteQuietly(previewFile)
            onResult(null)
        }
    }

    fun retake() {
        previewPlayer.value?.let { try { it.release() } catch (_: Exception) {} }
        previewPlayer.value = null
        previewReady = false
        deleteQuietly(previewFile)
        previewFile = null
        outputFile = File(context.cacheDir, "note_rec_${System.currentTimeMillis()}.mp4")
        seconds = 0
        elapsedMs = 0L
        trimStart = 0f
        trimEnd = 1f
        phase = "rec"
    }

    fun switchCamera() {
        if (phase != "rec" || stopInProgress || !canSwitchCamera || isSwitchingCamera) return
        isSwitchingCamera = true
        cameraTurns++
        useFrontCamera = !useFrontCamera
    }

    fun sendTrimmed() {
        val src = previewFile ?: return
        sending = true
        scope.launch {
            val sMs = (trimStart * durMs).toLong()
            val eMs = (trimEnd * durMs).toLong().coerceAtMost(durMs)
            var trimFailed = false
            val outF = withContext(Dispatchers.IO) {
                if (sMs <= 0L && (eMs <= 0L || eMs >= durMs - TRIM_END_SLACK_MS)) {
                    src
                } else {
                    val o = File(context.cacheDir, "note_trim_${System.currentTimeMillis()}.mp4")
                    if (VideoUtils.trim(src, o, sMs, eMs)) o else {
                        // Обрезка не удалась: огрызок подчищаем, шлём исходник целиком.
                        try { o.delete() } catch (_: Exception) {}
                        trimFailed = true
                        src
                    }
                }
            }
            if (trimFailed) {
                android.widget.Toast.makeText(
                    context,
                    "Не удалось обрезать — отправлено целиком",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            if (outF.absolutePath != src.absolutePath) {
                withContext(Dispatchers.IO) { src.delete() }
            }
            fileHandedOut = true
            onResult(outF)
        }
    }

    val cameraRotation by animateFloatAsState(
        targetValue = cameraTurns * 180f,
        animationSpec = tween(appearance.motionDuration(320), easing = FastOutSlowInEasing),
        label = "videoNoteCameraRotation"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f)),
        contentAlignment = Alignment.Center
    ) {
        // Круглое видео: в фазе записи — превью камеры, в превью — записанный файл.
        BoxWithConstraints(contentAlignment = Alignment.Center, modifier = Modifier.align(Alignment.Center)) {
            val circle = if (maxWidth < maxHeight) maxWidth * 0.82f else maxHeight * 0.6f
            val ringColor = MaterialTheme.colorScheme.error
            Box(modifier = Modifier.size(circle + 14.dp), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(circle)
                        .clip(CircleShape)
                        .background(Color.DarkGray),
                    contentAlignment = Alignment.Center
                ) {
                    Crossfade(
                        targetState = phase,
                        animationSpec = tween(appearance.motionDuration(220)),
                        label = "videoNotePhase"
                    ) { currentPhase ->
                        if (currentPhase == "rec") {
                            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                        } else {
                            val path = previewFile?.absolutePath
                            if (path != null) {
                                // TextureView + MediaPlayer: SurfaceView-ный VideoView не клипуется кругом.
                                AndroidView(
                                    factory = { ctx ->
                                        android.view.TextureView(ctx).apply {
                                            surfaceTextureListener = object :
                                                android.view.TextureView.SurfaceTextureListener {
                                                override fun onSurfaceTextureAvailable(
                                                    st: android.graphics.SurfaceTexture,
                                                    width: Int,
                                                    height: Int
                                                ) {
                                                    val mp = android.media.MediaPlayer()
                                                    try {
                                                        mp.setDataSource(path)
                                                        mp.setSurface(android.view.Surface(st))
                                                        mp.isLooping = true
                                                        mp.setOnPreparedListener { p ->
                                                            // Старт с начала выбранного окна обрезки.
                                                            try {
                                                                p.seekTo((trimStart * durMs).toInt())
                                                                p.start()
                                                            } catch (_: Exception) {}
                                                            previewReady = true
                                                        }
                                                        mp.prepareAsync()
                                                        previewPlayer.value = mp
                                                    } catch (e: Exception) {
                                                        try { mp.release() } catch (_: Exception) {}
                                                    }
                                                }
                                                override fun onSurfaceTextureSizeChanged(
                                                    st: android.graphics.SurfaceTexture,
                                                    width: Int,
                                                    height: Int
                                                ) {}
                                                override fun onSurfaceTextureDestroyed(
                                                    st: android.graphics.SurfaceTexture
                                                ): Boolean {
                                                    previewReady = false
                                                    previewPlayer.value?.let {
                                                        try { it.release() } catch (_: Exception) {}
                                                    }
                                                    previewPlayer.value = null
                                                    return true
                                                }
                                                override fun onSurfaceTextureUpdated(
                                                    st: android.graphics.SurfaceTexture
                                                ) {}
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
                // Кольцо лимита 60с вокруг круга записи.
                if (phase == "rec") {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val stroke = 4.dp.toPx()
                        drawArc(
                            color = ringColor,
                            startAngle = -90f,
                            sweepAngle = (elapsedMs / (MAX_NOTE_SECONDS * MS_PER_SECOND).toFloat())
                                .coerceIn(0f, 1f) * 360f,
                            useCenter = false,
                            topLeft = Offset(stroke / 2f, stroke / 2f),
                            size = Size(size.width - stroke, size.height - stroke),
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                    }
                }
            }
        }

        // Верх: таймер записи
        if (phase == "rec") {
            Row(
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.FiberManualRecord, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    String.format("%d:%02d", seconds / 60, seconds % 60),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 18.dp, end = 18.dp)
                    .size(AetherStyle.ControlSize)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (canSwitchCamera) 0.16f else 0.08f))
                    .clickable(enabled = canSwitchCamera && !isSwitchingCamera) { switchCamera() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Cameraswitch,
                    contentDescription = "Переключить камеру",
                    tint = Color.White.copy(alpha = if (canSwitchCamera) 1f else 0.45f),
                    modifier = Modifier
                        .size(26.dp)
                        .graphicsLayer {
                            rotationZ = cameraRotation
                        }
                )
            }
        }

        // Низ: панель управления
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 24.dp).fillMaxWidth()
        ) {
            if (phase == "preview") {
                // Таймлайн обрезки
                RangeSlider(
                    value = trimStart..trimEnd,
                    onValueChange = { r ->
                        // Минимальное окно: ручки не сводятся ближе MIN_TRIM_WINDOW_MS.
                        val minFrac = if (durMs > 0L) {
                            (MIN_TRIM_WINDOW_MS.toFloat() / durMs).coerceAtMost(1f)
                        } else 0f
                        var s = r.start.coerceIn(0f, 1f)
                        var e = r.endInclusive.coerceIn(0f, 1f)
                        if (e - s < minFrac) {
                            if (s != trimStart) { // тянули левую ручку
                                s = (e - minFrac).coerceAtLeast(0f)
                                e = (s + minFrac).coerceAtMost(1f)
                            } else { // тянули правую
                                e = (s + minFrac).coerceAtMost(1f)
                                s = (e - minFrac).coerceAtLeast(0f)
                            }
                        }
                        trimStart = s
                        trimEnd = e
                        // Превью следует за обрезкой: перематываем к началу окна.
                        previewPlayer.value?.let { mp ->
                            try { mp.seekTo((s * durMs).toInt()) } catch (_: Exception) {}
                        }
                    },
                    valueRange = 0f..1f
                )
                val cut = (((trimEnd - trimStart).coerceAtLeast(0f)) * durMs / MS_PER_SECOND).toInt()
                Text(String.format("0:%02d", cut), color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(12.dp))
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Слева: отмена (×) или переснять
                Text(
                    if (phase == "preview") "Переснять" else "Отмена",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(AetherStyle.PillRadius))
                        // Во время отправки «Переснять»/«Отмена» заблокированы: retake удалил бы
                        // файл, который параллельно читает trim/отправка.
                        .clickable(enabled = !sending) { if (phase == "preview") retake() else cancelAll() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )

                Spacer(Modifier.weight(1f))

                // Главная кнопка: запись → стоп(квадрат); превью → отправить
                Box(
                    modifier = Modifier
                        .size(74.dp)
                        .clip(CircleShape)
                        .background(if (phase == "preview") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        .clickable(enabled = !sending) { if (phase == "preview") sendTrimmed() else stopToPreview() },
                    contentAlignment = Alignment.Center
                ) {
                    if (phase == "preview") {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить", tint = Color.White, modifier = Modifier.size(30.dp))
                    } else {
                        // Квадрат-стоп
                        Box(modifier = Modifier.size(24.dp).clip(RoundedCornerShape(5.dp)).background(Color.White))
                    }
                }
            }
        }
    }
}
