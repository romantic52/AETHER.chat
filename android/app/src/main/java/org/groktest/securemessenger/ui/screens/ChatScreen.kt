package org.groktest.securemessenger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.groktest.securemessenger.data.MessageEntity
import org.groktest.securemessenger.AetherService
import org.groktest.securemessenger.ui.components.GlassBackground
import org.groktest.securemessenger.ui.components.liquidGlass
import org.groktest.securemessenger.ui.glass.glassSurface
import org.groktest.securemessenger.ui.glass.glassSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.material.icons.filled.Lock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.key
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    peerId: String,
    peerDisplayName: String = peerId,
    messagesFlow: kotlinx.coroutines.flow.Flow<List<MessageEntity>>,
    onBack: () -> Unit,
    onAudioCall: () -> Unit,
    onVideoCall: () -> Unit,
    onSendMessage: suspend (String, String?, String?) -> Exception?,
    onSendMedia: suspend (List<android.net.Uri>, String?) -> Exception?,
    // Отправка как документ (файл) — без сжатия, с именем/размером
    onSendFiles: suspend (List<android.net.Uri>, String?) -> Exception? = { _, _ -> null },
    onSendRecording: suspend (ByteArray, String, String, Long) -> Exception? = { _, _, _, _ -> null },
    onDeleteMessage: (String) -> Unit = {},
    onReact: (String, String) -> Unit = { _, _ -> },
    // (#A2) Повторная отправка сообщения со статусом «ошибка» (-1)
    onRetryMessage: (String) -> Unit = {},
    onSeen: () -> Unit = {},
    myId: String = "",
    onDownloadMedia: suspend (String) -> ByteArray?,
    onEditMessage: suspend (String, String) -> Exception? = { _, _ -> null },
    onForwardMessage: suspend (String, MessageEntity) -> Exception? = { _, _ -> null },
    onScheduleMessage: (String, Long) -> Unit = { _, _ -> },
    // (#A3) true — чат НЕ защищён E2E (легаси-канал): показываем плашку
    checkNotE2e: suspend () -> Boolean = { false },
    // (#A6) false — в канале публикуют только админы: вместо поля ввода плашка
    checkCanPost: suspend () -> Boolean = { true },
    // (#A6) Открыть группу обсуждений канала (null — обсуждений нет)
    onOpenDiscussion: ((MessageEntity) -> Unit)? = null,
    forwardChatsFlow: kotlinx.coroutines.flow.Flow<List<org.groktest.securemessenger.data.ChatEntity>> = kotlinx.coroutines.flow.flowOf(emptyList()),
    // P6: открыть экран «Цифры безопасности» (null — кнопка скрыта, например для каналов)
    onOpenSafety: (() -> Unit)? = null,
    // Статус собеседника: ISO last_active (для «был(а) в сети») — null если недоступно
    fetchPeerLastActive: suspend () -> String? = { null },
    // Кол-во участников группы/канала для подзаголовка шапки (null — недоступно)
    fetchMemberCount: suspend () -> Int? = { null },
    // Открыть профиль собеседника (тап по шапке)
    onOpenProfile: () -> Unit = {}
) {
    val messages by messagesFlow.collectAsState(initial = emptyList())
    var inputText by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val isLiquidGlass = org.groktest.securemessenger.ui.theme.LocalThemeSettings.current.isLiquidGlass()

    var replyingTo by remember { mutableStateOf<MessageEntity?>(null) }
    var editingMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var forwardingMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var showScheduleDialog by remember { mutableStateOf(false) }
    val forwardChats by forwardChatsFlow.collectAsState(initial = emptyList())
    var typingFromPeer by remember { mutableStateOf(false) }
    var lastTypingSent by remember { mutableStateOf(0L) }

    // Статус «был(а) в сети» — опрашиваем профиль собеседника (только личные чаты)
    var peerStatus by remember { mutableStateOf("") }
    LaunchedEffect(peerId) {
        val personal = !peerId.startsWith("channel_", ignoreCase = true) &&
            !peerId.startsWith("group_", ignoreCase = true)
        if (!personal) return@LaunchedEffect
        while (true) {
            peerStatus = formatLastSeen(try { fetchPeerLastActive() } catch (e: Exception) { null })
            kotlinx.coroutines.delay(30_000)
        }
    }

    fun submitText() {
        val textToSend = inputText.trim()
        if (textToSend.isEmpty() || isSending) return
        val editing = editingMessage
        val rId = replyingTo?.msgId
        val rText = replyingTo?.let { if (it.text.startsWith("{")) "Вложение" else it.text.take(80) }
        isSending = true
        coroutineScope.launch {
            val error = withContext(Dispatchers.IO) {
                if (editing != null) onEditMessage(editing.msgId, textToSend)
                else onSendMessage(textToSend, rId, rText)
            }
            isSending = false
            if (error == null) {
                inputText = ""
                replyingTo = null
                editingMessage = null
            } else {
                snackbarHostState.showSnackbar(
                    message = "Ошибка отправки: ${error.message ?: "Неизвестная ошибка"}",
                    duration = SnackbarDuration.Long
                )
            }
        }
    }

    var didInitialScroll by remember { mutableStateOf(false) }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            val target = messages.size - 1
            if (!didInitialScroll) {
                listState.scrollToItem(target)        // мгновенно при открытии чата
                didInitialScroll = true
            } else {
                listState.animateScrollToItem(target) // плавно только для новых сообщений
            }
        }
    }

    // Отправляем «прочитано» только когда появилось НОВОЕ входящее —
    // раньше контрол улетал при каждом открытии чата и каждой своей отправке.
    var lastSeenIncomingMsgId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(messages.size) {
        val lastIncoming = messages.lastOrNull { !it.isOut }
        if (lastIncoming != null && lastIncoming.msgId != lastSeenIncomingMsgId) {
            lastSeenIncomingMsgId = lastIncoming.msgId
            onSeen()
        }
    }

    // Индикатор «печатает...» от собеседника (через WebSocket)
    DisposableEffect(peerId) {
        val prev = AetherService.onTyping
        AetherService.onTyping = { from ->
            if (from.equals(peerId, ignoreCase = true)) typingFromPeer = true
        }
        onDispose { AetherService.onTyping = prev }
    }
    LaunchedEffect(typingFromPeer) {
        if (typingFromPeer) {
            kotlinx.coroutines.delay(3500)
            typingFromPeer = false
        }
    }

    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 100)
    ) { uris ->
        if (uris.isNotEmpty()) {
            isSending = true
            coroutineScope.launch {
                val error = withContext(Dispatchers.IO) { onSendMedia(uris, null) }
                isSending = false
                if (error != null) {
                    snackbarHostState.showSnackbar("Ошибка отправки медиа: ${error.message}", duration = SnackbarDuration.Long)
                }
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            isSending = true
            coroutineScope.launch {
                // Документы (в т.ч. фото, выбранные «как файл») — без сжатия, с именем/размером
                val error = withContext(Dispatchers.IO) { onSendFiles(uris, null) }
                isSending = false
                if (error != null) {
                    snackbarHostState.showSnackbar("Ошибка отправки файла: ${error.message}", duration = SnackbarDuration.Long)
                }
            }
        }
    }

    val context = LocalContext.current

    // ---- Telegram-like шторка вложений ----
    var showAttachSheet by remember { mutableStateOf(false) }
    val selectedMedia = remember { mutableStateListOf<android.net.Uri>() }
    var attachCaption by remember { mutableStateOf("") }
    var recentMedia by remember { mutableStateOf<List<Pair<android.net.Uri, Boolean>>>(emptyList()) }
    val gridImageLoader = remember {
        coil.ImageLoader.Builder(context)
            .components { add(coil.decode.VideoFrameDecoder.Factory()) }
            .build()
    }
    val mediaPermsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) {
            coroutineScope.launch(Dispatchers.IO) { recentMedia = queryRecentMedia(context) }
        }
    }

    // (#A5) Прогрев галереи при входе в чат: шторка вложений открывается
    // мгновенно с готовыми миниатюрами, а не с пустой сеткой (телеграм-паттерн)
    LaunchedEffect(Unit) {
        val perms = if (android.os.Build.VERSION.SDK_INT >= 33)
            arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
        else
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        val granted = perms.any {
            androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (granted && recentMedia.isEmpty()) {
            withContext(Dispatchers.IO) { recentMedia = queryRecentMedia(context) }
        }
    }

    // ---- Голосовое сообщение (запись) ----
    var isRecordingVoice by remember { mutableStateOf(false) }
    var recordVideoMode by remember { mutableStateOf(false) } // false = голос, true = видео-кружок
    var recordSeconds by remember { mutableStateOf(0) }
    // Запись «залочена» свайпом вверх — палец можно отпустить, запись идёт дальше.
    var recordLocked by remember { mutableStateOf(false) }
    val voiceRecorder = remember { mutableStateOf<android.media.MediaRecorder?>(null) }
    val voiceFile = remember { mutableStateOf<java.io.File?>(null) }
    // Сегменты текущего голосового (для «дописать») + накопленное время прошлых сегментов.
    val voiceSegments = remember { mutableStateListOf<java.io.File>() }
    var voiceBaseMs by remember { mutableStateOf(0L) }
    // Превью записанного голосового (review перед отправкой) + обрезка [0..1].
    var voicePreviewFile by remember { mutableStateOf<java.io.File?>(null) }
    var voicePreviewMs by remember { mutableStateOf(0L) }
    var trimStart by remember { mutableStateOf(0f) }
    var trimEnd by remember { mutableStateOf(1f) }

    LaunchedEffect(isRecordingVoice) {
        if (isRecordingVoice) {
            recordSeconds = 0
            while (isRecordingVoice) {
                kotlinx.coroutines.delay(1000)
                recordSeconds++
            }
        }
    }

    fun startVoiceRecording() {
        try {
            val f = java.io.File(context.cacheDir, "voice_seg_${System.currentTimeMillis()}.m4a")
            val rec = if (android.os.Build.VERSION.SDK_INT >= 31) android.media.MediaRecorder(context)
                      else @Suppress("DEPRECATION") android.media.MediaRecorder()
            rec.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            rec.setOutputFile(f.absolutePath)
            rec.prepare()
            rec.start()
            voiceRecorder.value = rec
            voiceFile.value = f
            voiceSegments.add(f)
            isRecordingVoice = true
        } catch (e: Exception) {
            isRecordingVoice = false
        }
    }

    // Останавливает текущий сегмент рекордера; копит время. true — есть что отправлять.
    fun finishCurrentSegment(): Boolean {
        val rec = voiceRecorder.value
        try { rec?.stop() } catch (e: Exception) {}
        try { rec?.release() } catch (e: Exception) {}
        voiceRecorder.value = null
        if (isRecordingVoice) voiceBaseMs += recordSeconds * 1000L
        isRecordingVoice = false
        voiceFile.value = null
        return voiceSegments.any { it.exists() && it.length() > 0 }
    }

    fun clearVoice() {
        voiceSegments.forEach { try { it.delete() } catch (e: Exception) {} }
        voiceSegments.clear()
        voicePreviewFile?.let { try { it.delete() } catch (e: Exception) {} }
        voicePreviewFile = null
        voiceBaseMs = 0L
        recordSeconds = 0
        recordLocked = false
        trimStart = 0f; trimEnd = 1f
    }

    // Отпустил без лока (send=true) или отмена (send=false). Склеивает сегменты и шлёт.
    fun stopVoiceRecording(send: Boolean) {
        val ok = finishCurrentSegment()
        recordLocked = false
        if (send && ok && voiceBaseMs >= 1000L) {
            isSending = true
            val segs = voiceSegments.toList()
            val durMs = voiceBaseMs
            coroutineScope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    val out = java.io.File(context.cacheDir, "voice_send_${System.currentTimeMillis()}.m4a")
                    if (VoiceUtils.concat(segs, out)) out.readBytes() else null
                }
                val err = if (bytes != null) withContext(Dispatchers.IO) { onSendRecording(bytes, "audio/mp4", "voice", durMs) } else null
                isSending = false
                clearVoice()
                if (err != null) snackbarHostState.showSnackbar("Ошибка отправки: ${err.message}", duration = SnackbarDuration.Long)
            }
        } else {
            clearVoice()
        }
    }

    // Квадрат-стоп → превью: склеивает сегменты, показывает голосовое для прослушки/обрезки.
    fun stopVoiceToPreview() {
        val ok = finishCurrentSegment()
        recordLocked = false
        if (!ok) { clearVoice(); return }
        val segs = voiceSegments.toList()
        val baseMs = voiceBaseMs
        coroutineScope.launch {
            val out = withContext(Dispatchers.IO) {
                val o = java.io.File(context.cacheDir, "voice_prev_${System.currentTimeMillis()}.m4a")
                if (VoiceUtils.concat(segs, o)) o else null
            }
            voicePreviewFile = out
            voicePreviewMs = out?.let { withContext(Dispatchers.IO) { VoiceUtils.durationMs(it) } }?.takeIf { it > 0 } ?: baseMs
            trimStart = 0f; trimEnd = 1f
        }
    }

    // Дописать из превью (зажал) — новый сегмент к уже записанным.
    // Продолжаем в залоченном режиме (hands-free): говорим, стоп по квадрату → снова превью.
    fun resumeVoiceRecording() {
        voicePreviewFile?.let { try { it.delete() } catch (e: Exception) {} }
        voicePreviewFile = null
        startVoiceRecording()
        recordLocked = true
    }

    // Отправить из превью с учётом обрезки [trimStart, trimEnd].
    fun sendPreview() {
        val prev = voicePreviewFile ?: return
        val total = voicePreviewMs
        val sMs = (trimStart * total).toLong()
        val eMs = (trimEnd * total).toLong().coerceAtMost(total)
        isSending = true
        coroutineScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                val out = java.io.File(context.cacheDir, "voice_trim_${System.currentTimeMillis()}.m4a")
                if (VoiceUtils.trim(prev, out, sMs, eMs)) out.readBytes() else prev.readBytes()
            }
            val dur = (eMs - sMs).coerceAtLeast(1000L)
            val err = withContext(Dispatchers.IO) { onSendRecording(bytes, "audio/mp4", "voice", dur) }
            isSending = false
            clearVoice()
            if (err != null) snackbarHostState.showSnackbar("Ошибка отправки: ${err.message}", duration = SnackbarDuration.Long)
        }
    }

    val audioPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoiceRecording()
    }

    // ---- Видео-кружок: запись внутри приложения (как в Telegram) ----
    var showVideoNoteRecorder by remember { mutableStateOf(false) }
    fun sendVideoNoteFile(f: java.io.File) {
        isSending = true
        coroutineScope.launch {
            val bytes = withContext(Dispatchers.IO) { try { f.readBytes() } catch (e: Exception) { null } }
            val durMs = withContext(Dispatchers.IO) {
                try {
                    val r = android.media.MediaMetadataRetriever()
                    r.setDataSource(f.absolutePath)
                    val d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    r.release(); d
                } catch (e: Exception) { 0L }
            }
            val err = if (bytes != null) withContext(Dispatchers.IO) { onSendRecording(bytes, "video/mp4", "video_note", durMs) } else null
            isSending = false
            if (err != null) snackbarHostState.showSnackbar("Ошибка отправки: ${err.message}", duration = SnackbarDuration.Long)
        }
    }
    fun launchVideoNote() {
        showVideoNoteRecorder = true
    }
    val cameraPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        if (perms[android.Manifest.permission.CAMERA] == true && perms[android.Manifest.permission.RECORD_AUDIO] == true) launchVideoNote()
    }

    // ---- Фото с камеры ----
    val photoUriState = remember { mutableStateOf<android.net.Uri?>(null) }
    val takePictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = photoUriState.value
        if (ok && uri != null) {
            isSending = true
            coroutineScope.launch {
                val err = withContext(Dispatchers.IO) { onSendMedia(listOf(uri), null) }
                isSending = false
                if (err != null) snackbarHostState.showSnackbar("Ошибка отправки: ${err.message}", duration = SnackbarDuration.Long)
            }
        }
    }
    fun launchPhotoCamera() {
        try {
            val f = java.io.File(context.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
            photoUriState.value = uri
            takePictureLauncher.launch(uri)
        } catch (e: Exception) {}
    }
    val photoCamPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchPhotoCamera()
    }

    // ---- Редактор фото (uCrop) ----
    val cropResultLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK && res.data != null) {
            val out = com.yalantis.ucrop.UCrop.getOutput(res.data!!)
            if (out != null) {
                isSending = true
                coroutineScope.launch {
                    val err = withContext(Dispatchers.IO) { onSendMedia(listOf(out), null) }
                    isSending = false
                    if (err != null) snackbarHostState.showSnackbar("Ошибка отправки: ${err.message}", duration = SnackbarDuration.Long)
                }
            }
        }
    }
    val pickPhotoForEdit = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val dest = android.net.Uri.fromFile(java.io.File(context.cacheDir, "crop_${System.currentTimeMillis()}.jpg"))
            val options = com.yalantis.ucrop.UCrop.Options().apply {
                setFreeStyleCropEnabled(true)
                setCompressionFormat(android.graphics.Bitmap.CompressFormat.JPEG)
                setCompressionQuality(90)
            }
            val intent = com.yalantis.ucrop.UCrop.of(uri, dest).withOptions(options).getIntent(context)
            cropResultLauncher.launch(intent)
        }
    }

    GlassBackground {
        Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                // Своя плашка фиксированной высоты — всё честно по центру, ничего не «торчит».
                val isChannelChat = peerId.startsWith("channel_", ignoreCase = true)
                val isGroupChat = peerId.startsWith("group_", ignoreCase = true)
                val isPersonalChat = !isChannelChat && !isGroupChat
                Surface(
                    color = if (isLiquidGlass) Color.Transparent else MaterialTheme.colorScheme.surface,
                    modifier = if (isLiquidGlass) Modifier.glassSurface(androidx.compose.ui.graphics.RectangleShape) else Modifier
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .height(56.dp)
                            .padding(start = 4.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // Аватар-инициал (имени чата), цвет стабилен по peerId
                        Box(
                            modifier = Modifier.size(38.dp).clip(CircleShape).background(peerColor(peerId))
                                .clickable { onOpenProfile() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                peerDisplayName.trim().firstOrNull()?.uppercase() ?: "?",
                                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f).clickable { onOpenProfile() }) {
                            Text(
                                peerDisplayName,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            val isTyping = typingFromPeer && !isChannelChat
                            if (isTyping) {
                                // «печатает» с живыми точками, как в Telegram
                                var dots by remember { mutableStateOf(1) }
                                LaunchedEffect(Unit) {
                                    while (true) { kotlinx.coroutines.delay(400); dots = (dots % 3) + 1 }
                                }
                                Text(
                                    "печатает" + ".".repeat(dots),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1
                                )
                            } else {
                                // Кол-во участников/подписчиков для шапки группы/канала
                                val memberCount by produceState<Int?>(initialValue = null, peerId) {
                                    if (isChannelChat || isGroupChat) {
                                        value = try { withContext(Dispatchers.IO) { fetchMemberCount() } } catch (e: Exception) { null }
                                    }
                                }
                                val (subtitle, subColor) = when {
                                    isChannelChat -> (memberCount?.let { "$it подписчиков" } ?: "Канал") to MaterialTheme.colorScheme.onSurfaceVariant
                                    isGroupChat -> (memberCount?.let { "$it участников" } ?: "Группа") to MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> peerStatus.ifBlank { "был(а) недавно" } to
                                        (if (peerStatus == "в сети") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(
                                    subtitle,
                                    fontSize = 12.sp,
                                    color = subColor,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (isPersonalChat) {
                            IconButton(onClick = onAudioCall) {
                                Icon(Icons.Default.Phone, contentDescription = "Аудиозвонок", tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = onVideoCall) {
                                Icon(Icons.Default.Videocam, contentDescription = "Видеозвонок", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            bottomBar = {
                Surface(
                    color = if (isLiquidGlass) Color.Transparent else MaterialTheme.colorScheme.surface,
                    modifier = if (isLiquidGlass) Modifier.glassSurface(androidx.compose.ui.graphics.RectangleShape) else Modifier
                ) {
                  // (#A6) Канал для не-админа — read-only: плашка вместо поля ввода
                  val canPost by produceState(initialValue = true, peerId) {
                      value = try { withContext(Dispatchers.IO) { checkCanPost() } } catch (e: Exception) { true }
                  }
                  if (!canPost) {
                      Box(
                          modifier = Modifier
                              .fillMaxWidth()
                              .navigationBarsPadding()
                              .padding(vertical = 14.dp),
                          contentAlignment = Alignment.Center
                      ) {
                          Text(
                              "Публиковать могут только администраторы",
                              color = MaterialTheme.colorScheme.onSurfaceVariant,
                              fontSize = 14.sp
                          )
                      }
                  } else {
                  Column {
                    if (editingMessage != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.width(3.dp).height(36.dp).background(Color(0xFFF59E0B)))
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Редактирование", color = Color(0xFFF59E0B), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    editingMessage?.text ?: "",
                                    maxLines = 1,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { editingMessage = null; inputText = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Отмена", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (replyingTo != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.width(3.dp).height(36.dp).background(MaterialTheme.colorScheme.primary))
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Ответ", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    replyingTo?.let { if (it.text.startsWith("{")) "Вложение" else it.text } ?: "",
                                    maxLines = 1,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { replyingTo = null }) {
                                Icon(Icons.Default.Close, contentDescription = "Отмена", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .navigationBarsPadding(),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        val previewFile = voicePreviewFile
                        if (previewFile != null) {
                            // Режим превью голосового: слева — удалить запись.
                            IconButton(onClick = { clearVoice() }, enabled = !isSending) {
                                Icon(Icons.Default.Delete, contentDescription = "Удалить запись", tint = MaterialTheme.colorScheme.error)
                            }
                            VoicePreviewBar(
                                file = previewFile,
                                durationMs = voicePreviewMs,
                                trimStart = trimStart,
                                trimEnd = trimEnd,
                                onTrimChange = { s, e -> trimStart = s; trimEnd = e },
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                accent = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f).padding(end = 8.dp).height(56.dp)
                            )
                        } else {
                        IconButton(
                            onClick = {
                                val perms = if (android.os.Build.VERSION.SDK_INT >= 33)
                                    arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
                                else
                                    arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                                val granted = perms.any {
                                    androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                }
                                showAttachSheet = true
                                if (granted) {
                                    // Сетка уже прогрета при входе в чат — тихо обновляем
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val fresh = queryRecentMedia(context)
                                        if (fresh != recentMedia) recentMedia = fresh
                                    }
                                } else {
                                    mediaPermsLauncher.launch(perms)
                                }
                            },
                            enabled = !isSending && !isRecordingVoice
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Вложение", tint = MaterialTheme.colorScheme.primary)
                        }

                        if (isRecordingVoice) {
                            Row(
                                modifier = Modifier.weight(1f).padding(end = 8.dp).height(56.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Мигающая красная точка
                                val blink by rememberInfiniteTransition(label = "rec").animateFloat(
                                    initialValue = 0.3f, targetValue = 1f,
                                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                                        tween(600), androidx.compose.animation.core.RepeatMode.Reverse
                                    ), label = "blink"
                                )
                                Box(modifier = Modifier.size(10.dp).graphicsLayer { alpha = blink }.clip(CircleShape).background(Color(0xFFEF4444)))
                                Spacer(Modifier.width(8.dp))
                                val s2 = recordSeconds
                                Text(String.format("%d:%02d", s2 / 60, s2 % 60), color = MaterialTheme.colorScheme.onBackground)
                                Spacer(Modifier.weight(1f))
                                if (recordLocked) {
                                    Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                } else {
                                    Text("↑ вверх — заблокировать", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                    Spacer(Modifier.width(8.dp))
                                }
                                TextButton(onClick = { stopVoiceRecording(send = false) }) {
                                    Text("Отмена", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        } else {
                            TextField(
                                value = inputText,
                                onValueChange = {
                                    inputText = it
                                    if (it.isNotEmpty()) {
                                        val now = System.currentTimeMillis()
                                        if (now - lastTypingSent > 2500) {
                                            lastTypingSent = now
                                            AetherService.sendTyping(peerId)
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp),
                                placeholder = { Text("Написать сообщение...", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                maxLines = 4,
                                enabled = !isSending,
                                shape = RoundedCornerShape(24.dp),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent,
                                    focusedTextColor = MaterialTheme.colorScheme.onBackground,
                                    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                                    disabledTextColor = MaterialTheme.colorScheme.onBackground
                                )
                            )
                        }
                        } // else (нет превью)

                        val sendActive = (inputText.isNotBlank() || isRecordingVoice || voicePreviewFile != null) && !isSending
                        val sendBg by animateColorAsState(
                            targetValue = if (sendActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            animationSpec = tween(200),
                            label = "sendBg"
                        )
                        // Лёгкая пульсация кнопки во время незалоченной записи (живее, как в TG).
                        val micPulse by rememberInfiniteTransition(label = "micPulse").animateFloat(
                            initialValue = 1f, targetValue = 1.12f,
                            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                                tween(700), androidx.compose.animation.core.RepeatMode.Reverse
                            ), label = "micPulseV"
                        )
                        val btnScale = if (isRecordingVoice && !recordLocked) micPulse else 1f
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .graphicsLayer { scaleX = btnScale; scaleY = btnScale }
                                .clip(CircleShape)
                                .background(sendBg),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSending) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.onBackground,
                                    strokeWidth = 2.dp
                                )
                            } else if (inputText.isNotBlank() && !isRecordingVoice) {
                                // Тап — отправить; долгий тап — отправить позже
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .clip(CircleShape)
                                        .combinedClickable(
                                            onClick = { submitText() },
                                            onLongClick = { if (editingMessage == null) showScheduleDialog = true }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Send,
                                        contentDescription = "Отправить",
                                        tint = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            } else if (voicePreviewFile != null) {
                                // Превью: тап — отправить (с обрезкой); долгий тап — дописать (hands-free).
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .clip(CircleShape)
                                        .combinedClickable(
                                            onClick = { sendPreview() },
                                            onLongClick = { resumeVoiceRecording() }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Send,
                                        contentDescription = "Отправить голосовое",
                                        tint = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            } else if (isRecordingVoice && recordLocked) {
                                // Залочено: тап по квадрату — стоп и переход к превью (прослушать/обрезать).
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .clip(CircleShape)
                                        .combinedClickable(onClick = { stopVoiceToPreview() }),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(MaterialTheme.colorScheme.onPrimary)
                                    )
                                }
                            } else {
                                // Как в Telegram: тап — смена режима (голос/кружок); зажал — запись;
                                // СВАЙП ВВЕРХ — заблокировать (можно отпустить палец); отпустил — отправка.
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .pointerInput(recordVideoMode) {
                                            val lockPx = 80.dp.toPx()
                                            awaitEachGesture {
                                                val down = awaitFirstDown(requireUnconsumed = false)
                                                val startY = down.position.y

                                                // Различаем тап от удержания ПО ТАЙМЕРУ (не по move-событиям!):
                                                // если за 200мс не отпустили — это зажатие → старт записи.
                                                val isHold = try {
                                                    withTimeout(200L) { waitForUpOrCancellation() }
                                                    false // отпустили в пределах 200мс → быстрый тап
                                                } catch (e: androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException) {
                                                    true
                                                }

                                                if (!isHold) {
                                                    // быстрый тап → смена режима голос/кружок
                                                    recordVideoMode = !recordVideoMode
                                                    return@awaitEachGesture
                                                }

                                                if (recordVideoMode) {
                                                    val camOk = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                                    val micOk = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                                    if (camOk && micOk) launchVideoNote()
                                                    else cameraPermLauncher.launch(arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO))
                                                    waitForUpOrCancellation()
                                                    return@awaitEachGesture
                                                }

                                                // Голос: нет разрешения — запрашиваем и выходим (запишет со 2-го раза).
                                                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                                    audioPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                                    waitForUpOrCancellation()
                                                    return@awaitEachGesture
                                                }

                                                startVoiceRecording()
                                                var locked = false
                                                while (true) {
                                                    val ev = awaitPointerEvent()
                                                    val ch = ev.changes.firstOrNull() ?: break
                                                    // Свайп вверх → лок (можно отпустить палец)
                                                    if (!locked && (ch.position.y - startY) < -lockPx) {
                                                        locked = true
                                                        recordLocked = true
                                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                    }
                                                    if (!ch.pressed) { ch.consume(); break }
                                                }
                                                // Отпустил без лока → отправить голосовое.
                                                // locked → запись продолжается (стоп по кнопке-квадрату → превью).
                                                if (!locked) stopVoiceRecording(send = true)
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        if (isRecordingVoice) Icons.Filled.Stop
                                        else if (recordVideoMode) Icons.Filled.Videocam
                                        else Icons.Filled.Mic,
                                        contentDescription = if (recordVideoMode) "Видео-кружок" else "Записать голос",
                                        tint = if (isRecordingVoice) Color(0xFFEF4444) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                  }
                  } // else canPost (#A6)
                }
            },
            containerColor = Color.Transparent
        ) { padding ->
            // (#A3) Честная индикация: легаси-канал без сквозного шифрования
            val notE2e by produceState(initialValue = false, peerId) {
                value = try { withContext(Dispatchers.IO) { checkNotE2e() } } catch (e: Exception) { false }
            }
            // Телеграм-обои: тонкий узор из точек в тон темы (субтильно, под Aether).
            val patternColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.04f)
            Column(modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .drawBehind {
                    val step = 56.dp.toPx()
                    val r = 2.2.dp.toPx()
                    var y = 0f; var row = 0
                    while (y < size.height) {
                        var x = if (row % 2 == 0) 0f else step / 2f
                        while (x < size.width) {
                            drawCircle(patternColor, r, Offset(x, y))
                            x += step
                        }
                        y += step; row++
                    }
                }
            ) {
                if (notE2e) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "⚠️ Этот канал создан до включения E2E: сервер знает его ключ. Создайте канал заново для сквозного шифрования.",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().then(
                        if (isLiquidGlass) Modifier.glassSource() else Modifier
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)
                ) {
                    itemsIndexed(messages, key = { _, m -> m.msgId }) { index, msg ->
                        // Группировка как в Telegram: разделители дат, тесная группа
                        // сообщений одного автора, «хвост» пузыря только у последнего.
                        val prev = messages.getOrNull(index - 1)
                        val next = messages.getOrNull(index + 1)
                        val gap = 5 * 60_000L
                        val showDate = prev == null || !isSameDay(prev.timestamp, msg.timestamp)
                        val groupedWithPrev = !showDate && prev != null &&
                            prev.isOut == msg.isOut && (msg.timestamp - prev.timestamp) in 0 until gap
                        val showTail = next == null || next.isOut != msg.isOut ||
                            !isSameDay(next.timestamp, msg.timestamp) || (next.timestamp - msg.timestamp) >= gap

                        // animateItemPlacement — плавный сдвиг при вставке нового
                        // сообщения / изменении статуса (как лента Telegram).
                        Column(modifier = Modifier.animateItemPlacement(tween(220))) {
                            if (showDate) DateSeparator(msg.timestamp)
                            Spacer(Modifier.height(if (groupedWithPrev) 2.dp else 8.dp))
                            MessageBubble(
                                msg = msg,
                                showTail = showTail,
                                isChannelPost = peerId.startsWith("channel_", ignoreCase = true),
                                onDownloadMedia = onDownloadMedia,
                                onDeleteMessage = onDeleteMessage,
                                myId = myId,
                                onReact = onReact,
                                onRetry = onRetryMessage,
                                onReply = { replyingTo = it },
                                onEdit = {
                                    replyingTo = null
                                    editingMessage = it
                                    inputText = it.text
                                },
                                onForward = { forwardingMessage = it },
                                onOpenDiscussion = onOpenDiscussion
                            )
                        }
                    }
                }
            }
            // Кнопка «вниз» — появляется, когда список прокручен вверх (как в Telegram)
            val showJump by remember { androidx.compose.runtime.derivedStateOf { listState.canScrollForward } }
            androidx.compose.animation.AnimatedVisibility(
                visible = showJump,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 4.dp,
                    modifier = Modifier.size(46.dp).clickable {
                        coroutineScope.launch { listState.animateScrollToItem((messages.size - 1).coerceAtLeast(0)) }
                    }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Вниз", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            } // Box (список + кнопка вниз)
            } // Column (#A3)
        }

        // --- Диалог выбора чата для пересылки ---
        forwardingMessage?.let { fmsg ->
            AlertDialog(
                onDismissRequest = { forwardingMessage = null },
                title = { Text("Переслать в...") },
                text = {
                    if (forwardChats.isEmpty()) {
                        Text("Нет чатов")
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                            items(forwardChats, key = { it.peerId }) { chat ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            forwardingMessage = null
                                            coroutineScope.launch {
                                                val err = withContext(Dispatchers.IO) { onForwardMessage(chat.peerId, fmsg) }
                                                snackbarHostState.showSnackbar(
                                                    if (err == null) "Переслано: ${chat.name}"
                                                    else "Ошибка пересылки: ${err.message}"
                                                )
                                            }
                                        }
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    org.groktest.securemessenger.ui.components.Avatar(
                                        name = chat.name,
                                        avatarFileId = chat.avatarFileId,
                                        size = 36.dp,
                                        type = chat.type
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(chat.name, fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { forwardingMessage = null }) { Text("Отмена") }
                }
            )
        }

        // --- Диалог отложенной отправки (дата → время) ---
        if (showScheduleDialog) {
            var scheduleStep by remember { mutableStateOf(0) }
            val dateState = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
            val nowCal = remember { java.util.Calendar.getInstance() }
            val timeState = rememberTimePickerState(
                initialHour = nowCal.get(java.util.Calendar.HOUR_OF_DAY),
                initialMinute = nowCal.get(java.util.Calendar.MINUTE),
                is24Hour = true
            )
            if (scheduleStep == 0) {
                DatePickerDialog(
                    onDismissRequest = { showScheduleDialog = false },
                    confirmButton = { TextButton(onClick = { scheduleStep = 1 }) { Text("Далее") } },
                    dismissButton = { TextButton(onClick = { showScheduleDialog = false }) { Text("Отмена") } }
                ) {
                    DatePicker(state = dateState, title = { Text("  Дата отправки", fontSize = 18.sp) })
                }
            } else {
                AlertDialog(
                    onDismissRequest = { showScheduleDialog = false },
                    title = { Text("Время отправки") },
                    text = { TimePicker(state = timeState) },
                    confirmButton = {
                        TextButton(onClick = {
                            // DatePicker отдаёт полночь UTC выбранного дня
                            val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                                timeInMillis = dateState.selectedDateMillis ?: System.currentTimeMillis()
                            }
                            val local = java.util.Calendar.getInstance().apply {
                                set(
                                    utc.get(java.util.Calendar.YEAR),
                                    utc.get(java.util.Calendar.MONTH),
                                    utc.get(java.util.Calendar.DAY_OF_MONTH),
                                    timeState.hour,
                                    timeState.minute,
                                    0
                                )
                                set(java.util.Calendar.MILLISECOND, 0)
                            }
                            val sendAt = local.timeInMillis
                            showScheduleDialog = false
                            if (sendAt > System.currentTimeMillis() && inputText.isNotBlank()) {
                                onScheduleMessage(inputText.trim(), sendAt)
                                inputText = ""
                                replyingTo = null
                                coroutineScope.launch {
                                    val fmt = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
                                    snackbarHostState.showSnackbar("Отправка запланирована: ${fmt.format(java.util.Date(sendAt))}")
                                }
                            } else {
                                coroutineScope.launch { snackbarHostState.showSnackbar("Выбранное время уже прошло") }
                            }
                        }) { Text("Запланировать") }
                    },
                    dismissButton = { TextButton(onClick = { scheduleStep = 0 }) { Text("Назад") } }
                )
            }
        }

        // --- Запись видео-кружка (полноэкранный оверлей в отдельном окне) ---
        if (showVideoNoteRecorder) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { showVideoNoteRecorder = false },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
            ) {
                VideoNoteRecorder(onResult = { f ->
                    showVideoNoteRecorder = false
                    if (f != null) sendVideoNoteFile(f)
                })
            }
        }

        // --- Telegram-like шторка вложений ---
        if (showAttachSheet) {
            // Сразу полностью раскрыта — шторка «выпрыгивает» высоко, помещается 3 ряда фото
            val attachSheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = {
                    showAttachSheet = false
                    selectedMedia.clear()
                    attachCaption = ""
                },
                sheetState = attachSheetState,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp)) {
                    if (recentMedia.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(100.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Нет доступа к галерее", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
                            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(recentMedia.size) { i ->
                                val (uri, isVideo) = recentMedia[i]
                                val selIndex = selectedMedia.indexOf(uri)
                                Box(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            // (#A5) Лимит выбора — 100, как в Telegram
                                            if (selIndex >= 0) selectedMedia.remove(uri)
                                            else if (selectedMedia.size < 100) selectedMedia.add(uri)
                                        }
                                ) {
                                    // (#A5) Миниатюра: явный маленький размер + RGB_565 —
                                    // полноразмерные фото не декодируются ради ячейки 120dp
                                    coil.compose.AsyncImage(
                                        model = coil.request.ImageRequest.Builder(context)
                                            .data(uri)
                                            .size(256)
                                            .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                                            .crossfade(false)
                                            .build(),
                                        imageLoader = gridImageLoader,
                                        contentDescription = null,
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    if (isVideo) {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = "Видео",
                                            tint = Color.White,
                                            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(18.dp)
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(6.dp)
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .background(if (selIndex >= 0) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.35f))
                                            .border(1.5.dp, Color.White, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (selIndex >= 0) {
                                            Text("${selIndex + 1}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    if (selIndex >= 0) {
                                        Box(modifier = Modifier.fillMaxSize().border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)))
                                    }
                                }
                            }
                        }
                    }

                    val actionItem: @Composable (androidx.compose.ui.graphics.vector.ImageVector, String, () -> Unit) -> Unit = { icon, label, onClick ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(10.dp)
                        ) {
                            Box(
                                modifier = Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    // Доп. полоска-разделитель между сеткой и действиями
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 8.dp)
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        actionItem(Icons.Default.PhotoCamera, "Камера") {
                            showAttachSheet = false
                            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) launchPhotoCamera()
                            else photoCamPermLauncher.launch(android.Manifest.permission.CAMERA)
                        }
                        actionItem(Icons.Default.Image, "Галерея") {
                            showAttachSheet = false
                            mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        }
                        actionItem(Icons.Default.InsertDriveFile, "Файл") {
                            showAttachSheet = false
                            filePicker.launch(arrayOf("*/*"))
                        }
                        actionItem(Icons.Default.Edit, "Редактор") {
                            showAttachSheet = false
                            pickPhotoForEdit.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    }

                    if (selectedMedia.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        ) {
                            TextField(
                                value = attachCaption,
                                onValueChange = { attachCaption = it },
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                                placeholder = { Text("Подпись...", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                maxLines = 3,
                                shape = RoundedCornerShape(24.dp),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                )
                            )
                            Box(
                                modifier = Modifier
                                    .size(50.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .clickable {
                                        val uris = selectedMedia.toList()
                                        val cap = attachCaption.trim().ifBlank { null }
                                        showAttachSheet = false
                                        selectedMedia.clear()
                                        attachCaption = ""
                                        isSending = true
                                        coroutineScope.launch {
                                            val error = withContext(Dispatchers.IO) { onSendMedia(uris, cap) }
                                            isSending = false
                                            if (error != null) {
                                                snackbarHostState.showSnackbar("Ошибка отправки медиа: ${error.message}", duration = SnackbarDuration.Long)
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${selectedMedia.size}", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Spacer(Modifier.width(2.dp))
                                    Icon(
                                        Icons.AutoMirrored.Filled.Send,
                                        contentDescription = "Отправить",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                    // Поднимаем действия выше края/навбара (ModalBottomSheet съедает inset)
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    msg: MessageEntity,
    showTail: Boolean = true,
    isChannelPost: Boolean = false,
    onDownloadMedia: suspend (String) -> ByteArray? = { null },
    onDeleteMessage: (String) -> Unit = {},
    myId: String = "",
    onReact: (String, String) -> Unit = { _, _ -> },
    onRetry: (String) -> Unit = {},
    onReply: (MessageEntity) -> Unit = {},
    onEdit: (MessageEntity) -> Unit = {},
    onForward: (MessageEntity) -> Unit = {},
    // (#A6) Открыть обсуждение поста (null — у канала нет группы обсуждений)
    onOpenDiscussion: ((MessageEntity) -> Unit)? = null
) {
    val isOut = msg.isOut
    // В канале посты выровнены одинаково (слева) — как лента, без «моих справа».
    val alignEnd = isOut && !isChannelPost
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val quickReaction = org.groktest.securemessenger.ui.theme.LocalThemeSettings.current.quickReaction.value
    // Моя текущая реакция на это сообщение (для toggle «снять» и подсветки в пикере)
    val myReaction = remember(msg.reactions, myId) {
        try {
            if (msg.reactions.isBlank()) null
            else org.json.JSONObject(msg.reactions).optString(myId, "").ifBlank { null }
        } catch (e: Exception) { null }
    }
    // Стекло на пузырях ломает рендер (видео-кружки = SurfaceView + RenderEffect → чёрный/глитч),
    // плюс блюр сплошной ленты делает текст нечитаемым. Стекло применяем только к
    // барам/панелям (см. isLiquidGlass в шапке/нижней панели), пузыри — сплошные.
    val isLiquid = false
    val bubbleColor = if (isLiquid) Color.Transparent
        else if (alignEnd) MaterialTheme.colorScheme.tertiary
        else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isLiquid) MaterialTheme.colorScheme.onBackground
        else if (alignEnd) MaterialTheme.colorScheme.onTertiary
        else MaterialTheme.colorScheme.onBackground

    val isMedia = msg.text.startsWith("{\"type\":\"media\"")
    var mediaBytes by remember { mutableStateOf<ByteArray?>(null) }
    var isDownloading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    // (#A5) remember: JSON парсится один раз, а не при каждой рекомпозиции пузыря
    val mediaJson = remember(msg.text) {
        if (isMedia) try { org.json.JSONObject(msg.text) } catch (e: Exception) { null } else null
    }
    val kind = mediaJson?.optString("kind", "") ?: ""

    if (kind == "video_note") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start
        ) {
            VideoNoteMessage(jsonText = msg.text, onDownloadMedia = onDownloadMedia)
        }
        return
    }

    // Свайп вправо → ответ (как в Telegram). Жест ТОЛЬКО на самом пузыре,
    // а не на всей строке — свайп по пустому месту рядом ответ не вызывает.
    val swipeOffset = remember { androidx.compose.animation.core.Animatable(0f) }
    val swipeThresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.toPx() }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .offset { androidx.compose.ui.unit.IntOffset(swipeOffset.value.toInt(), 0) }
                .pointerInput(msg.msgId) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (swipeOffset.value > swipeThresholdPx) onReply(msg)
                            coroutineScope.launch { swipeOffset.animateTo(0f) }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            // Поглощаем жест, чтобы не сработал свайп-назад (SwipeToBackWrapper)
                            change.consume()
                            coroutineScope.launch {
                                swipeOffset.snapTo((swipeOffset.value + dragAmount).coerceIn(0f, swipeThresholdPx * 1.4f))
                            }
                        }
                    )
                }
        ) {
        // «Хвост» (срезанный угол) только у последнего сообщения в группе —
        // сгруппированные сообщения одного автора скруглены одинаково.
        val r = 18.dp
        val bubbleShape = if (!showTail) RoundedCornerShape(r) else RoundedCornerShape(
            topStart = r,
            topEnd = r,
            bottomStart = if (alignEnd) r else 4.dp,
            bottomEnd = if (alignEnd) 4.dp else r
        )
        Surface(
            color = bubbleColor,
            shape = bubbleShape,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .then(
                    if (isLiquid) Modifier.glassSurface(
                        shape = bubbleShape,
                        fallbackTint = if (isOut) MaterialTheme.colorScheme.primary else Color.White,
                        fallbackAlpha = if (isOut) 0.30f else 0.10f
                    ) else Modifier
                )
                .combinedClickable(
                    onClick = {},
                    // Двойной тап — быстрая реакция (по умолчанию ❤️, настраивается);
                    // повторный двойной тап снимает её.
                    onDoubleClick = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        onReact(msg.msgId, if (myReaction == quickReaction) "" else quickReaction)
                    },
                    onLongClick = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        menuOpen = true
                    }
                )
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (msg.forwardedFrom != null) {
                    Text(
                        "↪ Переслано от ${msg.forwardedFrom}",
                        color = textColor.copy(alpha = 0.75f),
                        fontSize = 12.sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                if (msg.replyToText != null) {
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                        Box(modifier = Modifier.width(3.dp).height(34.dp).background(textColor.copy(alpha = 0.5f)))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            msg.replyToText,
                            color = textColor.copy(alpha = 0.85f),
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
                if (!isMedia) {
                    Text(
                        text = msg.text,
                        color = textColor,
                        fontSize = 16.sp,
                        lineHeight = 22.sp
                    )
                } else {
                    val json = mediaJson
                    val mimeType = json?.optString("mime_type", "") ?: ""

                    if (kind == "voice") {
                        VoiceMessagePlayer(
                            jsonText = msg.text,
                            durationMs = json?.optLong("duration", 0L) ?: 0L,
                            tint = textColor,
                            onDownloadMedia = onDownloadMedia
                        )
                    } else if (kind == "file") {
                        FileMessageBubble(
                            jsonText = msg.text,
                            fileName = json?.optString("file_name", "") ?: "",
                            fileSize = json?.optLong("file_size", 0L) ?: 0L,
                            tint = textColor,
                            onDownloadMedia = onDownloadMedia
                        )
                    } else if (mediaBytes != null) {
                        if (mimeType.startsWith("image/")) {
                            var showFullscreen by remember { mutableStateOf(false) }
                            coil.compose.AsyncImage(
                                model = mediaBytes,
                                contentDescription = "Вложение",
                                modifier = Modifier
                                    .heightIn(max = 280.dp)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    // (#A5) Тап — полноэкранный просмотр с зумом
                                    .clickable { showFullscreen = true },
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                            if (showFullscreen) {
                                FullscreenImageViewer(
                                    imageBytes = mediaBytes!!,
                                    onClose = { showFullscreen = false }
                                )
                            }
                        } else {
                            Text("Файл загружен", color = textColor, fontSize = 14.sp)
                        }
                    } else {
                        if (isDownloading) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onBackground)
                        } else {
                            Button(
                                onClick = {
                                    isDownloading = true
                                    coroutineScope.launch {
                                        val bytes = withContext(Dispatchers.IO) { onDownloadMedia(msg.text) }
                                        if (bytes != null) {
                                            mediaBytes = bytes
                                        } else {
                                            errorMessage = "Ошибка скачивания"
                                        }
                                        isDownloading = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text(if (mimeType.startsWith("image/")) "Показать фото" else "Скачать файл")
                            }
                            if (errorMessage != null) {
                                Text(errorMessage!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                            }
                        }
                    }
                    val caption = mediaJson?.optString("caption", "") ?: ""
                    if (caption.isNotBlank() && kind != "voice") {
                        Spacer(Modifier.height(6.dp))
                        Text(caption, color = textColor, fontSize = 15.sp, lineHeight = 20.sp)
                    }
                }
                
                // (#A6) Обсуждение поста — открывает подвязанную группу канала
                if (isChannelPost && onOpenDiscussion != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    TextButton(
                        onClick = { onOpenDiscussion(msg) },
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("💬 Обсуждение", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                }

                val reactionsMap = remember(msg.reactions) {
                    try {
                        if (msg.reactions.isBlank()) emptyMap<String, String>()
                        else {
                            val o = org.json.JSONObject(msg.reactions)
                            o.keys().asSequence().associateWith { o.getString(it) }
                        }
                    } catch (e: Exception) { emptyMap<String, String>() }
                }
                if (reactionsMap.isNotEmpty()) {
                    val counts = reactionsMap.values.groupingBy { it }.eachCount()
                    val chipBg = if (msg.isOut) Color.White.copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        counts.forEach { (emoji, cnt) ->
                            key(emoji) {
                                // Pop-появление реакции (пружинка), как в Telegram
                                val scale = remember { Animatable(0.5f) }
                                LaunchedEffect(Unit) {
                                    scale.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 520f))
                                }
                                val mineChip = myReaction == emoji
                                Box(
                                    modifier = Modifier
                                        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                                        .clip(RoundedCornerShape(12.dp))
                                        // Моя реакция чуть ярче; тап по чипу — поставить/снять
                                        .background(if (mineChip) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else chipBg)
                                        .clickable {
                                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                            onReact(msg.msgId, if (mineChip) "" else emoji)
                                        }
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text("$emoji $cnt", fontSize = 13.sp, color = textColor)
                                }
                            }
                        }
                    }
                }
                run {
                    Spacer(Modifier.height(2.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (msg.isEdited) {
                            Text(
                                "изм.",
                                fontSize = 11.sp,
                                color = textColor.copy(alpha = 0.6f),
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        }
                        // Время — у всех сообщений (как в Telegram)
                        Text(
                            formatMsgTime(msg.timestamp),
                            fontSize = 11.sp,
                            color = textColor.copy(alpha = 0.6f)
                        )
                        if (isOut) {
                            Spacer(Modifier.width(3.dp))
                            // (#A2) -1 = ошибка отправки, 0 = в очереди (optimistic send)
                            when {
                                msg.status == -1 -> Icon(
                                    Icons.Filled.ErrorOutline,
                                    contentDescription = "Ошибка отправки",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(14.dp)
                                )
                                msg.status == 0 -> Icon(
                                    Icons.Filled.Schedule,
                                    contentDescription = "Отправляется",
                                    tint = textColor.copy(alpha = 0.6f),
                                    modifier = Modifier.size(14.dp)
                                )
                                // 1 = отправлено (✓), 2 = доставлено (✓✓ серая),
                                // 3 = прочитано (✓✓ зелёная) — как в Telegram.
                                else -> Icon(
                                    if (msg.status >= 2) Icons.Filled.DoneAll else Icons.Filled.Done,
                                    contentDescription = null,
                                    tint = if (msg.status >= 3) Color(0xFF4ADE80) else textColor.copy(alpha = 0.6f),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                listOf("❤️", "👍", "🔥", "😂", "😮", "😢").forEach { e ->
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    val sc by animateFloatAsState(if (pressed) 1.4f else 1f, spring(), label = "emojiScale")
                    val mine = myReaction == e
                    Box(
                        modifier = Modifier
                            .graphicsLayer { scaleX = sc; scaleY = sc }
                            .clip(CircleShape)
                            // Подсветка моей выбранной реакции — повторный тап её снимает
                            .background(if (mine) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.Transparent)
                            .clickable(interactionSource = interaction, indication = null) {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                onReact(msg.msgId, if (mine) "" else e); menuOpen = false
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(e, fontSize = 22.sp)
                    }
                }
            }
            if (isOut && msg.status == -1) {
                DropdownMenuItem(
                    text = { Text("Повторить отправку") },
                    onClick = { onRetry(msg.msgId); menuOpen = false }
                )
            }
            DropdownMenuItem(
                text = { Text("Ответить") },
                onClick = { onReply(msg); menuOpen = false }
            )
            DropdownMenuItem(
                text = { Text("Переслать") },
                onClick = { onForward(msg); menuOpen = false }
            )
            if (isOut && !isMedia) {
                DropdownMenuItem(
                    text = { Text("Изменить") },
                    onClick = { onEdit(msg); menuOpen = false }
                )
            }
            if (!isMedia) {
                DropdownMenuItem(
                    text = { Text("Копировать") },
                    onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(msg.text))
                        menuOpen = false
                    }
                )
            }
            DropdownMenuItem(
                text = { Text("Удалить") },
                onClick = {
                    onDeleteMessage(msg.msgId)
                    menuOpen = false
                }
            )
        }
        }
    }
}

/** Последние фото/видео из MediaStore для шторки вложений. Pair<Uri, isVideo>, новые сверху. */
private fun queryRecentMedia(context: android.content.Context, limit: Int = 120): List<Pair<android.net.Uri, Boolean>> {
    val out = mutableListOf<Triple<android.net.Uri, Boolean, Long>>()
    fun q(collection: android.net.Uri, isVideo: Boolean) {
        try {
            val proj = arrayOf(
                android.provider.MediaStore.MediaColumns._ID,
                android.provider.MediaStore.MediaColumns.DATE_ADDED
            )
            context.contentResolver.query(
                collection, proj, null, null,
                android.provider.MediaStore.MediaColumns.DATE_ADDED + " DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID)
                val dateCol = c.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.DATE_ADDED)
                var n = 0
                while (c.moveToNext() && n < limit) {
                    out.add(
                        Triple(
                            android.content.ContentUris.withAppendedId(collection, c.getLong(idCol)),
                            isVideo,
                            c.getLong(dateCol)
                        )
                    )
                    n++
                }
            }
        } catch (e: Exception) {}
    }
    q(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false)
    q(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
    return out.sortedByDescending { it.third }.take(limit).map { Pair(it.first, it.second) }
}

/**
 * (#A5) Полноэкранный просмотр фото (телеграм-паттерн):
 * pinch-zoom до 5x, пан при увеличении, двойной тап — зум/сброс, одиночный — закрыть.
 */
@Composable
fun FullscreenImageViewer(imageBytes: ByteArray, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

        // Плавный вход/выход: фон проявляется, картинка «вырастает» 0.9→1.
        val scope = rememberCoroutineScope()
        val appear = remember { Animatable(0f) }
        LaunchedEffect(Unit) { appear.animateTo(1f, tween(220)) }
        val close: () -> Unit = {
            scope.launch {
                appear.animateTo(0f, tween(170))
                onClose()
            }
        }
        val entrance = 0.9f + 0.1f * appear.value

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = appear.value))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offset = if (scale > 1f) offset + pan * scale else androidx.compose.ui.geometry.Offset.Zero
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { close() },
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = androidx.compose.ui.geometry.Offset.Zero
                            } else {
                                scale = 2.5f
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            coil.compose.AsyncImage(
                model = imageBytes,
                contentDescription = "Фото",
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale * entrance,
                        scaleY = scale * entrance,
                        alpha = appear.value,
                        translationX = offset.x,
                        translationY = offset.y
                    )
            )
            // Крестик на случай, если жесты не очевидны
            IconButton(
                onClick = close,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 36.dp, end = 12.dp)
                    .graphicsLayer { alpha = appear.value }
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Закрыть", tint = Color.White)
            }
        }
    }
}

// ---- Хелперы чата (как в Telegram) ----

private fun formatMsgTime(ts: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))

private fun isSameDay(a: Long, b: Long): Boolean {
    val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
    val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) &&
        ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
}

private fun formatDateSep(ts: Long): String {
    val now = java.util.Calendar.getInstance()
    if (isSameDay(ts, now.timeInMillis)) return "Сегодня"
    now.add(java.util.Calendar.DAY_OF_YEAR, -1)
    if (isSameDay(ts, now.timeInMillis)) return "Вчера"
    val sameYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) ==
        java.util.Calendar.getInstance().apply { timeInMillis = ts }.get(java.util.Calendar.YEAR)
    val pattern = if (sameYear) "d MMMM" else "d MMMM yyyy"
    return java.text.SimpleDateFormat(pattern, java.util.Locale("ru")).format(java.util.Date(ts))
}

/** Цвета аватаров как в Telegram — стабильный выбор по имени. */
internal val avatarColors = listOf(
    Color(0xFFE17076), Color(0xFFEDA86C), Color(0xFFA695E7),
    Color(0xFF7BC862), Color(0xFF6EC9CB), Color(0xFF65AADD), Color(0xFFEE7AAE)
)
internal fun peerColor(seed: String): Color =
    avatarColors[(seed.hashCode().let { if (it < 0) -it else it }) % avatarColors.size]

/** «был(а) в сети» по ISO last_active (как в Telegram). */
internal fun formatLastSeen(iso: String?): String {
    val ts = org.groktest.securemessenger.api.RelayApi.parseUtcIso(iso)
    if (ts <= 0L) return "был(а) недавно"
    val now = System.currentTimeMillis()
    val diff = now - ts
    return when {
        diff < 75_000L -> "в сети"
        diff < 3_600_000L -> "был(а) ${diff / 60_000L} мин назад"
        isSameDay(ts, now) -> "был(а) в " + formatMsgTime(ts)
        else -> {
            val yesterday = java.util.Calendar.getInstance()
                .apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }.timeInMillis
            if (isSameDay(ts, yesterday)) "был(а) вчера в " + formatMsgTime(ts)
            else "был(а) " + java.text.SimpleDateFormat("d MMM", java.util.Locale("ru")).format(java.util.Date(ts))
        }
    }
}

/** Плашка-разделитель дат по центру (как в Telegram). */
@Composable
private fun DateSeparator(ts: Long) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = formatDateSep(ts),
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.30f))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
