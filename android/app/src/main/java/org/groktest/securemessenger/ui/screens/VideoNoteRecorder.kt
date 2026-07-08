package org.groktest.securemessenger.ui.screens

import androidx.camera.core.CameraSelector
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material3.Icon
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_NOTE_SECONDS = 60

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

    // "rec" — идёт запись; "preview" — обрезка/отправка записанного.
    var phase by remember { mutableStateOf("rec") }
    var isRecording by remember { mutableStateOf(false) }
    var seconds by remember { mutableStateOf(0) }
    var useFrontCamera by remember { mutableStateOf(true) }
    var hasFront by remember { mutableStateOf(true) }
    // Что сделать после Finalize записи: показать превью или отменить.
    var pendingAction by remember { mutableStateOf("") }

    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var outputFile by remember { mutableStateOf(File(context.cacheDir, "note_rec_${System.currentTimeMillis()}.mp4")) }

    // Превью/обрезка
    var previewFile by remember { mutableStateOf<File?>(null) }
    var durMs by remember { mutableStateOf(0L) }
    var trimStart by remember { mutableStateOf(0f) }
    var trimEnd by remember { mutableStateOf(1f) }
    var sending by remember { mutableStateOf(false) }
    val videoSegments = remember { mutableStateListOf<File>() }
    var elapsedBeforeSwitchMs by remember { mutableStateOf(0L) }

    fun unbindCamera() {
        try { provider?.unbindAll() } catch (e: Exception) {}
    }

    fun startRecording() {
        val capture = videoCapture ?: return
        try {
            @Suppress("MissingPermission")
            recording = capture.output
                .prepareRecording(context, FileOutputOptions.Builder(outputFile).build())
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        val ok = !event.hasError() && outputFile.exists() && outputFile.length() > 0
                        when (pendingAction) {
                            "switch" -> {
                                if (ok) {
                                    videoSegments.add(outputFile)
                                    elapsedBeforeSwitchMs += VideoUtils.durationMs(outputFile).takeIf { it > 0 } ?: (seconds * 1000L)
                                } else {
                                    try { outputFile.delete() } catch (e: Exception) {}
                                }
                                recording = null
                                outputFile = File(context.cacheDir, "note_rec_${System.currentTimeMillis()}.mp4")
                                useFrontCamera = !useFrontCamera
                            }
                            "preview" -> {
                                unbindCamera() // камера/микрофон выключаются — запись больше НЕ идёт
                                if (ok) {
                                    val parts = (videoSegments + outputFile).filter { it.exists() && it.length() > 0 }
                                    val finalFile = if (parts.size > 1) {
                                        val combined = File(context.cacheDir, "note_join_${System.currentTimeMillis()}.mp4")
                                        if (VideoUtils.concat(parts, combined)) combined else outputFile
                                    } else outputFile
                                    previewFile = finalFile
                                    durMs = VideoUtils.durationMs(finalFile).takeIf { it > 0 } ?: (seconds * 1000L)
                                    trimStart = 0f; trimEnd = 1f
                                    phase = "preview"
                                } else onResult(null)
                            }
                            "cancel" -> { unbindCamera(); try { outputFile.delete() } catch (e: Exception) {}; onResult(null) }
                        }
                        pendingAction = ""
                    }
                }
            isRecording = true
        } catch (e: Exception) {
            android.util.Log.e("VideoNote", "start error: ${e.message}")
            onResult(null)
        }
    }

    // Привязка камеры и автостарт записи в фазе "rec". На эмуляторах камера часто без
    // lensFacing — DEFAULT_*_CAMERA её не находят, поэтому перебираем все доступные.
    LaunchedEffect(phase, useFrontCamera) {
        if (phase != "rec") return@LaunchedEffect
        try {
            val p = ProcessCameraProvider.getInstance(context).get()
            provider = p
            hasFront = try { p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) } catch (e: Exception) { false }
            previewView.scaleX = if (useFrontCamera) -1f else 1f
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.fromOrderedList(
                        listOf(Quality.LOWEST, Quality.SD, Quality.HD, Quality.HIGHEST),
                        FallbackStrategy.higherQualityOrLowerThan(Quality.LOWEST)
                    )
                )
                .build()
            val capture = VideoCapture.withOutput(recorder)
            // Квадратный кадр (кружок): ViewPort 1:1 кропит вывод и превью.
            val rotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
            val viewPort = ViewPort.Builder(android.util.Rational(1, 1), rotation).build()
            val preferred = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val fallback = if (useFrontCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            val candidates = (listOf(preferred, fallback) + p.availableCameraInfos.map { it.cameraSelector }).distinct()
            var bound = false
            for (sel in candidates) {
                try {
                    p.unbindAll()
                    val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(capture).setViewPort(viewPort).build()
                    p.bindToLifecycle(lifecycleOwner, sel, group)
                    videoCapture = capture
                    bound = true
                    break
                } catch (e: Exception) {
                    android.util.Log.w("VideoNote", "bind failed for $sel: ${e.message}")
                }
            }
            if (!bound) { onResult(null); return@LaunchedEffect }
            // Автостарт записи (кружок начинает писаться сразу при открытии).
            if (!isRecording) startRecording()
        } catch (e: Exception) {
            android.util.Log.e("VideoNote", "camera init error: ${e.message}")
            onResult(null)
        }
    }

    // Таймер и автостоп на 60 секундах
    LaunchedEffect(isRecording, elapsedBeforeSwitchMs) {
        if (isRecording) {
            val startedAt = System.currentTimeMillis()
            while (isRecording) {
                seconds = ((elapsedBeforeSwitchMs + (System.currentTimeMillis() - startedAt)) / 1000L).toInt()
                if (seconds >= MAX_NOTE_SECONDS) {
                    pendingAction = "preview"
                    isRecording = false
                    try { recording?.stop() } catch (e: Exception) {}
                    break
                }
                delay(250)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try { recording?.stop() } catch (e: Exception) {}
            unbindCamera()
        }
    }

    fun stopToPreview() {
        if (!isRecording) return
        pendingAction = "preview"
        isRecording = false
        try { recording?.stop() } catch (e: Exception) { onResult(null) }
    }

    fun cancelAll() {
        if (isRecording) {
            pendingAction = "cancel"
            isRecording = false
            try { recording?.stop() } catch (e: Exception) { unbindCamera(); onResult(null) }
        } else {
            unbindCamera()
            previewFile?.let { try { it.delete() } catch (e: Exception) {} }
            videoSegments.forEach { try { it.delete() } catch (e: Exception) {} }
            videoSegments.clear()
            onResult(null)
        }
    }

    fun retake() {
        previewFile?.let { try { it.delete() } catch (e: Exception) {} }
        videoSegments.forEach { try { it.delete() } catch (e: Exception) {} }
        videoSegments.clear()
        previewFile = null
        outputFile = File(context.cacheDir, "note_rec_${System.currentTimeMillis()}.mp4")
        seconds = 0
        elapsedBeforeSwitchMs = 0L
        trimStart = 0f; trimEnd = 1f
        phase = "rec" // перезапустит LaunchedEffect → биндинг + автозапись
    }

    fun switchCamera() {
        if (phase != "rec") return
        if (isRecording) {
            pendingAction = "switch"
            isRecording = false
            try { recording?.stop() } catch (e: Exception) {}
        } else {
            useFrontCamera = !useFrontCamera
        }
    }

    fun sendTrimmed() {
        val src = previewFile ?: return
        sending = true
        scope.launch {
            val sMs = (trimStart * durMs).toLong()
            val eMs = (trimEnd * durMs).toLong().coerceAtMost(durMs)
            val outF = withContext(Dispatchers.IO) {
                if (sMs <= 0L && (eMs <= 0L || eMs >= durMs - 250L)) {
                    src
                } else {
                    val o = File(context.cacheDir, "note_trim_${System.currentTimeMillis()}.mp4")
                    if (VideoUtils.trim(src, o, sMs, eMs)) o else src
                }
            }
            onResult(outF)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f)),
        contentAlignment = Alignment.Center
    ) {
        // Круглое видео: в фазе записи — превью камеры, в превью — записанный файл.
        BoxWithConstraints(contentAlignment = Alignment.Center, modifier = Modifier.align(Alignment.Center)) {
            val circle = if (maxWidth < maxHeight) maxWidth * 0.82f else maxHeight * 0.6f
            Box(
                modifier = Modifier
                    .size(circle)
                    .clip(CircleShape)
                    .background(Color.DarkGray),
                contentAlignment = Alignment.Center
            ) {
                if (phase == "rec") {
                    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                } else {
                    val path = previewFile?.absolutePath
                    if (path != null) {
                        AndroidView(
                            factory = { ctx ->
                                android.widget.VideoView(ctx).apply {
                                    setVideoPath(path)
                                    setOnPreparedListener { mp -> mp.isLooping = true; start() }
                                }
                            },
                            modifier = Modifier.fillMaxSize()
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
                Icon(Icons.Filled.FiberManualRecord, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(String.format("0:%02d", seconds), color = Color.White, fontSize = 16.sp)
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 18.dp, end = 18.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f))
                    .clickable { switchCamera() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Cameraswitch,
                    contentDescription = "Переключить камеру",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
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
                    onValueChange = { r -> trimStart = r.start; trimEnd = r.endInclusive },
                    valueRange = 0f..1f
                )
                val cut = (((trimEnd - trimStart).coerceAtLeast(0f)) * durMs / 1000L).toInt()
                Text(String.format("0:%02d", cut), color = Color.White, fontSize = 14.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(12.dp))
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Слева: отмена (×) или переснять
                Text(
                    if (phase == "preview") "Переснять" else "Отмена",
                    color = Color(0xFF60A5FA),
                    fontSize = 16.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { if (phase == "preview") retake() else cancelAll() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )

                Spacer(Modifier.weight(1f))

                // Главная кнопка: запись → стоп(квадрат); превью → отправить
                Box(
                    modifier = Modifier
                        .size(74.dp)
                        .clip(CircleShape)
                        .background(if (phase == "preview") Color(0xFF3B82F6) else Color(0xFFEF4444))
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
