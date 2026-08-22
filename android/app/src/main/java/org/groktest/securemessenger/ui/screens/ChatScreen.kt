package org.groktest.securemessenger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.groktest.securemessenger.data.MessageEntity
import org.groktest.securemessenger.AetherService
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.material.icons.filled.Lock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.key
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import org.groktest.securemessenger.ui.theme.AetherStyle
import org.groktest.securemessenger.ui.theme.AetherEdge
import org.groktest.securemessenger.ui.theme.AetherEdgeDim
import org.groktest.securemessenger.ui.theme.LocalThemeSettings
import org.groktest.securemessenger.ui.theme.aetherControl
import org.groktest.securemessenger.ui.theme.aetherControlContent
import org.groktest.securemessenger.ui.theme.aetherBubbleShape
import org.groktest.securemessenger.ui.theme.aetherBubbleVisual
import org.groktest.securemessenger.ui.theme.aetherField
import org.groktest.securemessenger.ui.theme.aetherIsland
import org.groktest.securemessenger.ui.theme.aetherSurface
import org.groktest.securemessenger.ui.theme.aetherTextFieldColors
import org.groktest.securemessenger.ui.components.GlassBackground
import org.groktest.securemessenger.ui.components.AetherPrimaryButton
import org.groktest.securemessenger.ui.glass.glassSource
import org.groktest.securemessenger.ui.glass.glassSurface

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@android.annotation.SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun ChatScreen(
    peerId: String,
    peerDisplayName: String = peerId,
    chatType: Int = 0,
    messagesFlow: kotlinx.coroutines.flow.StateFlow<List<MessageEntity>>,
    onBack: () -> Unit,
    onAudioCall: () -> Unit,
    onVideoCall: () -> Unit,
    onSendMessage: suspend (String, String?, String?) -> Exception?,
    onSendMedia: suspend (List<android.net.Uri>, String?) -> Exception?,
    // Отправка как документ (файл) — без сжатия, с именем/размером
    onSendFiles: suspend (List<android.net.Uri>, String?) -> Exception? = { _, _ -> null },
    // Последний параметр — waveform: реальные амплитуды ГС (0..31, <=63 бакетов) или null
    onSendRecording: suspend (java.io.File, String, String, Long, List<Int>?) -> Exception? = { _, _, _, _, _ -> null },
    onDeleteMessage: suspend (String, Boolean) -> Exception? = { _, _ -> null },
    onReact: (String, String) -> Unit = { _, _ -> },
    // (#A2) Повторная отправка сообщения со статусом «ошибка» (-1)
    onRetryMessage: (String) -> Unit = {},
    onSeen: () -> Unit = {},
    myId: String = "",
    onDownloadMedia: suspend (String) -> java.io.File?,
    cachedMediaFile: (String) -> java.io.File? = { null },
    onEditMessage: suspend (String, String) -> Exception? = { _, _ -> null },
    onForwardMessage: suspend (String, MessageEntity) -> Exception? = { _, _ -> null },
    onScheduleMessage: (String, Long) -> Unit = { _, _ -> },
    // (#A3) true — чат НЕ защищён E2E (легаси-канал): показываем плашку
    checkNotE2e: suspend () -> Boolean = { false },
    // (#A6) false — в канале публикуют только админы: вместо поля ввода плашка
    checkCanPost: suspend () -> Boolean = { true },
    // (#A6) Открыть группу обсуждений канала (null — обсуждений нет)
    onOpenDiscussion: ((MessageEntity) -> Unit)? = null,
    forwardChatsFlow: kotlinx.coroutines.flow.StateFlow<List<org.groktest.securemessenger.data.ChatEntity>> = kotlinx.coroutines.flow.MutableStateFlow(emptyList()),
    // P6: открыть экран «Цифры безопасности» (null — кнопка скрыта, например для каналов)
    onOpenSafety: (() -> Unit)? = null,
    // last_active + эмодзи-статус одним запросом профиля.
    fetchPeerPresence: suspend () -> Pair<String?, String?>? = { null },
    // Кол-во участников группы/канала для подзаголовка шапки (null — недоступно)
    fetchMemberCount: suspend () -> Int? = { null },
    // Открыть профиль собеседника (тап по шапке)
    onOpenProfile: () -> Unit = {}
) {
    val messages by messagesFlow.collectAsState()
    var inputText by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var sendPulseTrigger by remember { mutableIntStateOf(0) }
    var composerHeightPx by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = messages.lastIndex.coerceAtLeast(0))
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val appearance = LocalThemeSettings.current

    var replyingTo by remember { mutableStateOf<MessageEntity?>(null) }
    var editingMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var forwardingMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var showScheduleDialog by remember { mutableStateOf(false) }
    val forwardChats by forwardChatsFlow.collectAsState()
    var typingFromPeer by remember { mutableStateOf(false) }
    var lastTypingSent by remember { mutableStateOf(0L) }
    var headerMenuOpen by remember { mutableStateOf(false) }
    val isChannelChat = chatType == 2
    val isGroupChat = chatType == 1
    val isSavedChat = chatType == 3
    val isPersonalChat = chatType == 0

    // Статус «был(а) в сети» — опрашиваем профиль собеседника (только личные чаты)
    var peerStatus by remember(peerId) { mutableStateOf("") }
    var peerStatusEmoji by remember(peerId) { mutableStateOf<String?>(null) }
    LaunchedEffect(peerId, chatType) {
        if (!isPersonalChat) return@LaunchedEffect
        while (true) {
            val presence = try { fetchPeerPresence() } catch (_: Exception) { null }
            peerStatus = formatLastSeen(presence?.first)
            peerStatusEmoji = presence?.second
            kotlinx.coroutines.delay(30_000)
        }
    }

    fun submitText() {
        val textToSend = inputText.trim()
        if (textToSend.isEmpty() || isSending) return
        val inputBeforeSend = inputText
        val editing = editingMessage
        val reply = replyingTo
        val rId = reply?.msgId
        val rText = reply?.let { if (it.text.startsWith("{")) "Вложение" else it.text.take(80) }

        // Outbox уже оптимистичный: освобождаем composer в момент тапа, а не после IO.
        inputText = ""
        replyingTo = null
        editingMessage = null
        sendPulseTrigger++
        coroutineScope.launch {
            val error = withContext(Dispatchers.IO) {
                if (editing != null) onEditMessage(editing.msgId, textToSend)
                else onSendMessage(textToSend, rId, rText)
            }
            if (error != null) {
                if (inputText.isEmpty() && replyingTo == null && editingMessage == null) {
                    inputText = inputBeforeSend
                    replyingTo = reply
                    editingMessage = editing
                }
                snackbarHostState.showSnackbar(
                    message = "Ошибка отправки: ${error.message ?: "Неизвестная ошибка"}",
                    duration = SnackbarDuration.Long
                )
            }
        }
    }

    var didInitialScroll by remember(peerId) { mutableStateOf(false) }
    var followLatest by remember(peerId) { mutableStateOf(true) }
    var knownLastMessageId by remember(peerId) { mutableStateOf<String?>(null) }
    val currentLastMessageId = messages.lastOrNull()?.msgId
    val arrivingMessageId = currentLastMessageId?.takeIf {
        knownLastMessageId != null && it != knownLastMessageId
    }
    val showJump by remember(messages) {
        derivedStateOf {
            if (!listState.canScrollForward) return@derivedStateOf false
            val info = listState.layoutInfo
            val readableEnd = info.viewportEndOffset - info.afterContentPadding
            val lastVisible = info.visibleItemsInfo.lastOrNull { it.offset < readableEnd }?.index
                ?: messages.lastIndex
            var hiddenWeight = 0
            for (index in (lastVisible + 1)..messages.lastIndex) {
                hiddenWeight += jumpHistoryWeight(messages[index].text)
                if (hiddenWeight >= 10) return@derivedStateOf true
            }
            false
        }
    }
    val userDragging by listState.interactionSource.collectIsDraggedAsState()
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val imeVisible by remember(density, imeInsets) {
        derivedStateOf { imeInsets.getBottom(density) > 0 }
    }

    suspend fun snapToLatest() {
        if (messages.isEmpty()) return
        listState.scrollToItem(messages.lastIndex)
        listState.scrollBy(Float.MAX_VALUE)
    }

    suspend fun glideToLatest() {
        if (messages.isEmpty()) return
        if (!appearance.animationsEnabled()) {
            snapToLatest()
            return
        }

        // Второй кадр нужен AnimatedVisibility, чтобы новый пузырь получил окончательный размер.
        withFrameNanos { }
        withFrameNanos { }
        if (!listState.canScrollForward) return
        val target = messages.lastIndex
        val info = listState.layoutInfo
        val targetItem = info.visibleItemsInfo.firstOrNull { it.index == target }
        if (targetItem != null) {
            val remaining = (
                targetItem.offset + targetItem.size + info.afterContentPadding - info.viewportEndOffset
            ).coerceAtLeast(0)
            if (remaining > 0) {
                listState.animateScrollBy(
                    remaining.toFloat(),
                    tween(appearance.motionDuration(420), easing = FastOutSlowInEasing)
                )
            }
        } else {
            // Для далёкой истории не рисуем сотни промежуточных сообщений.
            listState.animateScrollToItem(target)
        }
        withFrameNanos { }
        if (listState.canScrollForward) {
            val settledInfo = listState.layoutInfo
            val settledItem = settledInfo.visibleItemsInfo.firstOrNull { it.index == target }
            val remaining = settledItem?.let {
                (it.offset + it.size + settledInfo.afterContentPadding - settledInfo.viewportEndOffset)
                    .coerceAtLeast(0)
            } ?: 0
            if (remaining > 1) {
                listState.animateScrollBy(
                    remaining.toFloat(),
                    tween(appearance.motionDuration(220), easing = FastOutSlowInEasing)
                )
            }
        }
    }

    LaunchedEffect(userDragging, listState.isScrollInProgress) {
        if (didInitialScroll) {
            if (userDragging) followLatest = false
            else if (!listState.isScrollInProgress && !listState.canScrollForward) followLatest = true
        }
    }

    LaunchedEffect(currentLastMessageId) {
        if (currentLastMessageId != null) {
            if (!didInitialScroll) {
                withFrameNanos { }
                snapToLatest()
                followLatest = true
                didInitialScroll = true
            } else if (followLatest || messages.last().isOut) {
                glideToLatest()
            }
        }
        knownLastMessageId = currentLastMessageId
    }

    LaunchedEffect(imeVisible) {
        if (imeVisible && didInitialScroll && followLatest && messages.isNotEmpty()) {
            glideToLatest()
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
            if (!isSavedChat && from.equals(peerId, ignoreCase = true)) typingFromPeer = true
        }
        onDispose { AetherService.onTyping = prev }
    }
    LaunchedEffect(typingFromPeer) {
        if (typingFromPeer) {
            kotlinx.coroutines.delay(3500)
            typingFromPeer = false
        }
    }

    val context = LocalContext.current

    // ---- Telegram-like шторка вложений ----
    var showAttachSheet by remember { mutableStateOf(false) }
    val selectedMedia = remember { mutableStateListOf<android.net.Uri>() }
    var attachCaption by remember { mutableStateOf("") }
    var attachAsDocument by remember { mutableStateOf(false) }
    var recentMedia by remember { mutableStateOf<List<Pair<android.net.Uri, Boolean>>>(emptyList()) }

    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 100)
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedMedia.clear()
            selectedMedia.addAll(uris)
            val picked = uris.map { uri ->
                uri to (context.contentResolver.getType(uri)?.startsWith("video/") == true)
            }
            recentMedia = (picked + recentMedia).distinctBy { it.first }.take(100)
            attachAsDocument = false
            showAttachSheet = true
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
    var voiceSegmentStartedAt by remember { mutableLongStateOf(0L) }
    // Превью записанного голосового (review перед отправкой) + обрезка [0..1].
    var voicePreviewFile by remember { mutableStateOf<java.io.File?>(null) }
    var voicePreviewMs by remember { mutableStateOf(0L) }
    var trimStart by remember { mutableStateOf(0f) }
    var trimEnd by remember { mutableStateOf(1f) }
    // РЕАЛЬНАЯ ВОЛНА: амплитуды рекордера, лог-нормированные в 0..31 (~каждые 100мс).
    // Переживает сегменты («дописать»), чистится в clearVoice.
    val voiceWaveform = remember { mutableStateListOf<Int>() }
    // Смещение пальца по X в жесте записи — для подсказки slide-to-cancel.
    var recordDragX by remember { mutableStateOf(0f) }
    // Идёт отправка голосового: onDispose не должен удалять файлы у неё из-под ног.
    val voiceSendInFlight = remember { mutableStateOf(false) }

    LaunchedEffect(isRecordingVoice, voiceBaseMs) {
        if (isRecordingVoice) {
            while (isRecordingVoice) {
                val currentMs = if (voiceSegmentStartedAt > 0L) {
                    android.os.SystemClock.elapsedRealtime() - voiceSegmentStartedAt
                } else 0L
                recordSeconds = ((voiceBaseMs + currentMs) / 1000L).toInt()
                kotlinx.coroutines.delay(200)
            }
        }
    }

    // Снимаем огибающую записи: maxAmplitude каждые ~100мс → бакеты 0..31;
    // при отправке даунсемплятся до <=63 и уезжают в wire-поле "waveform".
    LaunchedEffect(isRecordingVoice) {
        while (isRecordingVoice) {
            val amp = try { voiceRecorder.value?.maxAmplitude ?: 0 } catch (_: Exception) { 0 }
            voiceWaveform.add(
                (31.0 * kotlin.math.ln(1.0 + amp) / kotlin.math.ln(1.0 + 32767.0))
                    .toInt().coerceIn(0, 31)
            )
            kotlinx.coroutines.delay(100)
        }
    }

    fun startVoiceRecording() {
        // Guard от двойного старта: повторный вход поверх активного рекордера
        // утекал бы MediaRecorder'ом и держал микрофон до ухода с экрана.
        if (isRecordingVoice || voiceRecorder.value != null) return
        // Стопим воспроизведение медиа в ленте — динамик не должен орать в микрофон.
        ChatPlaybackCoordinator.stopAll()
        var rec: android.media.MediaRecorder? = null
        var file: java.io.File? = null
        try {
            val f = java.io.File(context.cacheDir, "voice_seg_${System.currentTimeMillis()}.m4a")
            file = f
            rec = if (android.os.Build.VERSION.SDK_INT >= 31) android.media.MediaRecorder(context)
                  else @Suppress("DEPRECATION") android.media.MediaRecorder()
            rec.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            // Явные параметры вместо девайсных дефолтов (часто 8 кГц, «телефонное» звучание)
            rec.setAudioChannels(1)
            rec.setAudioSamplingRate(48000)
            rec.setAudioEncodingBitRate(64000)
            rec.setOutputFile(f.absolutePath)
            rec.prepare()
            rec.start()
            voiceRecorder.value = rec
            voiceFile.value = f
            voiceSegments.add(f)
            voiceSegmentStartedAt = android.os.SystemClock.elapsedRealtime()
            isRecordingVoice = true
        } catch (_: Exception) {
            try { rec?.release() } catch (_: Exception) {}
            try { file?.delete() } catch (_: Exception) {}
            voiceRecorder.value = null
            voiceFile.value = null
            voiceSegmentStartedAt = 0L
            isRecordingVoice = false
        }
    }

    // Останавливает текущий сегмент рекордера; копит время. true — есть что отправлять.
    fun finishCurrentSegment(): Boolean {
        val rec = voiceRecorder.value
        val currentFile = voiceFile.value
        val wasRecording = isRecordingVoice
        isRecordingVoice = false
        var stoppedCleanly = rec == null
        try {
            rec?.stop()
            stoppedCleanly = true
        } catch (_: Exception) {
            voiceSegments.remove(currentFile)
            try { currentFile?.delete() } catch (_: Exception) {}
        }
        try { rec?.release() } catch (_: Exception) {}
        voiceRecorder.value = null
        if (wasRecording && stoppedCleanly && voiceSegmentStartedAt > 0L) {
            voiceBaseMs += (android.os.SystemClock.elapsedRealtime() - voiceSegmentStartedAt)
                .coerceAtLeast(0L)
        }
        voiceSegmentStartedAt = 0L
        recordSeconds = (voiceBaseMs / 1000L).toInt()
        voiceFile.value = null
        return voiceSegments.any { it.exists() && it.length() > 0 }
    }

    fun clearVoice() {
        voiceSegments.forEach { try { it.delete() } catch (e: Exception) {} }
        voiceSegments.clear()
        voicePreviewFile?.let { try { it.delete() } catch (e: Exception) {} }
        voicePreviewFile = null
        voiceWaveform.clear()
        voiceBaseMs = 0L
        voiceSegmentStartedAt = 0L
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
            voiceSendInFlight.value = true
            val segs = voiceSegments.toList()
            val durMs = voiceBaseMs
            val wave = downsampleWaveform(voiceWaveform.toList())
            coroutineScope.launch {
                val recording = withContext(Dispatchers.IO) {
                    val valid = segs.filter { it.exists() && it.length() > 0L }
                    when (valid.size) {
                        0 -> null
                        1 -> valid.single()
                        else -> {
                            val out = java.io.File(context.cacheDir, "voice_send_${System.currentTimeMillis()}.m4a")
                            if (VoiceUtils.concat(valid, out)) out else { out.delete(); null }
                        }
                    }
                }
                val err = if (recording != null) withContext(Dispatchers.IO) {
                    try { onSendRecording(recording, "audio/mp4", "voice", durMs, wave) }
                    finally { recording.delete() }
                } else IllegalStateException("Не удалось подготовить голосовое сообщение")
                isSending = false
                clearVoice()
                voiceSendInFlight.value = false
                if (err != null) snackbarHostState.showSnackbar("Ошибка отправки: ${err.message}", duration = SnackbarDuration.Long)
            }
        } else {
            clearVoice()
            // Слишком короткое зажатие при попытке отправить — подсказываем, а не молчим.
            if (send) coroutineScope.launch {
                snackbarHostState.showSnackbar("Зажмите кнопку для записи")
            }
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
                if (VoiceUtils.concat(segs, o)) o else { o.delete(); null }
            }
            if (out == null) {
                // Склейка не удалась — не оставляем сегменты «прилипать» к следующей записи.
                clearVoice()
                snackbarHostState.showSnackbar("Не удалось сохранить запись")
                return@launch
            }
            voicePreviewFile = out
            voicePreviewMs = withContext(Dispatchers.IO) { VoiceUtils.durationMs(out) }.takeIf { it > 0 } ?: baseMs
            trimStart = 0f; trimEnd = 1f
        }
    }

    // Дописать из превью (зажал) — новый сегмент к уже записанным.
    // Продолжаем в залоченном режиме (hands-free): говорим, стоп по квадрату → снова превью.
    fun resumeVoiceRecording() {
        startVoiceRecording()
        // Превью удаляем только ПОСЛЕ успешного старта нового сегмента:
        // если мик занят, запись не пропадает — превью остаётся на месте.
        if (isRecordingVoice) {
            voicePreviewFile?.let { try { it.delete() } catch (e: Exception) {} }
            voicePreviewFile = null
        }
        recordLocked = isRecordingVoice
    }

    // Отправить из превью с учётом обрезки [trimStart, trimEnd].
    fun sendPreview() {
        val prev = voicePreviewFile ?: return
        val total = voicePreviewMs
        val sMs = (trimStart * total).toLong()
        val eMs = (trimEnd * total).toLong().coerceAtMost(total)
        isSending = true
        voiceSendInFlight.value = true
        val waveFull = voiceWaveform.toList()
        val tS = trimStart
        val tE = trimEnd
        coroutineScope.launch {
            var trimFailed = false
            val err = withContext(Dispatchers.IO) {
                val out = java.io.File(context.cacheDir, "voice_trim_${System.currentTimeMillis()}.m4a")
                try {
                    val trimmed = VoiceUtils.trim(prev, out, sMs, eMs)
                    trimFailed = !trimmed
                    // Обрезка не удалась → шлём ПОЛНЫЙ файл; длительность — всегда
                    // от фактически отправляемого файла, чтобы таймер совпадал со звуком.
                    val recording = if (trimmed) out else prev
                    val dur = if (trimmed) (eMs - sMs).coerceAtLeast(1L)
                              else VoiceUtils.durationMs(prev).takeIf { it > 0 } ?: total
                    val wave = downsampleWaveform(
                        if (trimmed && waveFull.isNotEmpty()) {
                            val from = (tS * waveFull.size).toInt().coerceIn(0, waveFull.size - 1)
                            val to = (tE * waveFull.size).toInt().coerceIn(from + 1, waveFull.size)
                            waveFull.subList(from, to)
                        } else waveFull
                    )
                    onSendRecording(recording, "audio/mp4", "voice", dur, wave)
                } finally {
                    out.delete()
                }
            }
            isSending = false
            clearVoice()
            voiceSendInFlight.value = false
            if (trimFailed) snackbarHostState.showSnackbar("Обрезка не применилась")
            if (err != null) snackbarHostState.showSnackbar("Ошибка отправки: ${err.message}", duration = SnackbarDuration.Long)
        }
    }

    val audioPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        // Запись НЕ стартуем: палец давно отпущен, жест завершён — иначе получалась бы
        // «бесхозная» запись без release-to-send. Пользователь просто зажмёт кнопку снова.
    }

    // ---- Видео-кружок: запись внутри приложения (как в Telegram) ----
    var showVideoNoteRecorder by remember { mutableStateOf(false) }
    fun sendVideoNoteFile(f: java.io.File) {
        isSending = true
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val duration = VideoUtils.durationMs(f)
                    onSendRecording(f, "video/mp4", "video_note", duration, null)
                } catch (e: Exception) {
                    e
                } finally {
                    f.delete()
                }
            }
            isSending = false
            if (result != null) {
                snackbarHostState.showSnackbar(
                    "Ошибка отправки: ${result.message}",
                    duration = SnackbarDuration.Long
                )
            }
        }
    }
    fun launchVideoNote() {
        // Перед рекордером кружка глушим воспроизведение медиа в ленте (контракт с ChatMedia)
        ChatPlaybackCoordinator.stopAll()
        showVideoNoteRecorder = true
    }
    val cameraPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        if (perms[android.Manifest.permission.CAMERA] == true && perms[android.Manifest.permission.RECORD_AUDIO] == true) {
            launchVideoNote()
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Для кружка нужен доступ к камере и микрофону")
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try { voiceRecorder.value?.stop() } catch (_: Exception) {}
            try { voiceRecorder.value?.release() } catch (_: Exception) {}
            voiceRecorder.value = null
            // Файлы не трогаем, если их прямо сейчас читает фоновая отправка —
            // уход с экрана сразу после отпускания кнопки не должен срывать send.
            if (!voiceSendInFlight.value) {
                voiceSegments.forEach { try { it.delete() } catch (_: Exception) {} }
                voicePreviewFile?.let { try { it.delete() } catch (_: Exception) {} }
            }
        }
    }

    // Сворачивание приложения во время записи (в т.ч. незалоченной): фоновому
    // приложению система глушит микрофон — сохраняем записанное в превью, не теряем.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP && isRecordingVoice) {
                stopVoiceToPreview()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(appearance.edgeDimLength.value.dp)
                        .zIndex(2f)
                ) {
                    AetherEdgeDim(AetherEdge.Top, Modifier.matchParentSize())
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .size(AetherStyle.ControlSize)
                                .aetherControl(fillAlpha = AetherStyle.ControlFillAlpha, strokeAlpha = AetherStyle.ControlStrokeAlpha)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = aetherControlContent())
                        }
                        Spacer(Modifier.width(8.dp))
                        Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(AetherStyle.ControlSize)
                                .glassSurface(RoundedCornerShape(AetherStyle.PillRadius))
                                .aetherIsland(
                                    shape = RoundedCornerShape(AetherStyle.PillRadius),
                                    fillAlpha = AetherStyle.ControlFillAlpha,
                                    strokeAlpha = AetherStyle.ControlStrokeAlpha
                                )
                                    .padding(start = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(if (isSavedChat) savedChatColor else peerColor(peerId))
                                    .clickable(enabled = !isSavedChat) { onOpenProfile() },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSavedChat) {
                                    Icon(
                                        Icons.Default.Star,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                } else {
                                    Text(
                                        peerDisplayName.trim().firstOrNull()?.uppercase() ?: "?",
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(enabled = !isSavedChat) { onOpenProfile() }
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        peerDisplayName,
                                        modifier = Modifier.weight(1f, fill = false),
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    if (isPersonalChat && !peerStatusEmoji.isNullOrBlank()) {
                                        Spacer(Modifier.width(4.dp))
                                        Text(peerStatusEmoji!!, fontSize = 14.sp, maxLines = 1)
                                    }
                                }
                                val isTyping = typingFromPeer && !isSavedChat && !isChannelChat
                                if (isTyping) {
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
                                    val memberCount by produceState<Int?>(initialValue = null, peerId) {
                                        if (isChannelChat || isGroupChat) {
                                            value = try { withContext(Dispatchers.IO) { fetchMemberCount() } } catch (e: Exception) { null }
                                        }
                                    }
                                    val (subtitle, subColor) = when {
                                        isSavedChat -> "Личные сохраненные сообщения" to MaterialTheme.colorScheme.onSurfaceVariant
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
                                IconButton(onClick = onAudioCall, modifier = Modifier.size(40.dp)) {
                                    Icon(Icons.Default.Phone, contentDescription = "Аудиозвонок", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                }
                                IconButton(onClick = onVideoCall, modifier = Modifier.size(40.dp)) {
                                    Icon(Icons.Default.Videocam, contentDescription = "Видеозвонок", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                }
                            }
                            if (!isSavedChat) Box {
                                IconButton(onClick = { headerMenuOpen = true }, modifier = Modifier.size(40.dp)) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "Меню чата", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                // Канон меню: surface-контейнер, скругление 20.dp (в M3 1.2 у
                                // DropdownMenu нет shape/containerColor — задаём через тему).
                                MaterialTheme(
                                    shapes = MaterialTheme.shapes.copy(extraSmall = CircleShape),
                                    colorScheme = MaterialTheme.colorScheme.copy(surfaceTint = Color.Transparent)
                                ) {
                                DropdownMenu(expanded = headerMenuOpen, onDismissRequest = { headerMenuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text(if (isPersonalChat) "Профиль" else "Информация") },
                                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                                        onClick = { headerMenuOpen = false; onOpenProfile() }
                                    )
                                    if (isPersonalChat && onOpenSafety != null) {
                                        DropdownMenuItem(
                                            text = { Text("Цифры безопасности") },
                                            leadingIcon = { Icon(Icons.Default.Security, contentDescription = null) },
                                            onClick = { headerMenuOpen = false; onOpenSafety() }
                                        )
                                    }
                                }
                                } // MaterialTheme (канон меню)
                            }
                        }
                    }
                }
            },
            bottomBar = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = appearance.edgeDimLength.value.dp)
                        .graphicsLayer {
                            translationY = -imeInsets.getBottom(density).toFloat()
                        }
                        .zIndex(2f),
                    contentAlignment = Alignment.BottomCenter
                ) {
                  AetherEdgeDim(AetherEdge.Bottom, Modifier.matchParentSize())
                  // (#A6) Канал для не-админа — read-only: плашка вместо поля ввода
                  val canPost by produceState(initialValue = true, peerId) {
                      value = try { withContext(Dispatchers.IO) { checkCanPost() } } catch (e: Exception) { true }
                  }
                  if (!canPost) {
                      Box(
                          modifier = Modifier
                              .fillMaxWidth()
                              .padding(vertical = 14.dp)
                              .then(if (imeVisible) Modifier else Modifier.navigationBarsPadding())
                              .onSizeChanged { composerHeightPx = it.height },
                          contentAlignment = Alignment.Center
                      ) {
                          Text(
                              "Публиковать могут только администраторы",
                              color = MaterialTheme.colorScheme.onSurfaceVariant,
                              fontSize = 14.sp
                          )
                      }
                  } else {
                  Column(Modifier.onSizeChanged { composerHeightPx = it.height }) {
                    val composerContextMessage = editingMessage ?: replyingTo
                    val composerContextIsEditing = editingMessage != null
                    AnimatedVisibility(
                        visible = composerContextMessage != null,
                        enter = fadeIn(tween(appearance.motionDuration(150))) + expandVertically(
                            animationSpec = tween(appearance.motionDuration(190), easing = FastOutSlowInEasing),
                            expandFrom = Alignment.Bottom
                        ),
                        exit = fadeOut(tween(appearance.motionDuration(110))) + shrinkVertically(
                            animationSpec = tween(appearance.motionDuration(160), easing = FastOutSlowInEasing),
                            shrinkTowards = Alignment.Bottom
                        )
                    ) {
                        val accent = if (composerContextIsEditing) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.width(3.dp).height(36.dp).background(accent))
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (composerContextIsEditing) "Редактирование" else "Ответ",
                                    color = accent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    composerContextMessage?.text?.let {
                                        if (!composerContextIsEditing && it.startsWith("{")) "Вложение" else it
                                    }.orEmpty(),
                                    maxLines = 1,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = {
                                if (composerContextIsEditing) {
                                    editingMessage = null
                                    inputText = ""
                                } else {
                                    replyingTo = null
                                }
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Отмена", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .then(if (imeVisible) Modifier else Modifier.navigationBarsPadding()),
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
                                // Реальная огибающая записи (null → прежний hash-фейк)
                                waveform = remember(previewFile) { downsampleWaveform(voiceWaveform.toList()) },
                                modifier = Modifier.weight(1f).padding(end = 8.dp).height(56.dp)
                            )
                        } else {
                        val attachEnabled = !isSending && !isRecordingVoice
                        Box(
                            modifier = Modifier
                                .size(AetherStyle.ControlSize)
                                .aetherControl(fillAlpha = AetherStyle.ControlFillAlpha, strokeAlpha = AetherStyle.ControlStrokeAlpha)
                                .clickable(enabled = attachEnabled) {
                                    val perms = if (android.os.Build.VERSION.SDK_INT >= 33)
                                        arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
                                    else
                                        arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                                    val granted = perms.any {
                                        androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                    }
                                    coroutineScope.launch {
                                        if (granted && recentMedia.isEmpty()) {
                                                recentMedia = withContext(Dispatchers.IO) { queryRecentMedia(context) }
                                        }
                                        showAttachSheet = true
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Вложение",
                                tint = if (attachEnabled) aetherControlContent() else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                modifier = Modifier.size(26.dp)
                            )
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
                                Box(modifier = Modifier.size(10.dp).graphicsLayer { alpha = blink }.clip(CircleShape).background(MaterialTheme.colorScheme.error))
                                Spacer(Modifier.width(8.dp))
                                val s2 = recordSeconds
                                Text(String.format("%d:%02d", s2 / 60, s2 % 60), color = MaterialTheme.colorScheme.onBackground)
                                if (!recordLocked) {
                                    Spacer(Modifier.width(12.dp))
                                    // Slide-to-cancel как в Telegram: подсказка едет за пальцем (dx/2)
                                    Text(
                                        "‹ Влево — отмена",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        modifier = Modifier.offset {
                                            androidx.compose.ui.unit.IntOffset((recordDragX / 2f).toInt(), 0)
                                        }
                                    )
                                }
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
                            BasicTextField(
                                value = inputText,
                                onValueChange = {
                                    inputText = it
                                    if (!isSavedChat && it.isNotEmpty()) {
                                        val now = System.currentTimeMillis()
                                        if (now - lastTypingSent > 2500) {
                                            lastTypingSent = now
                                            AetherService.sendTyping(peerId)
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp)
                                    .heightIn(min = AetherStyle.ControlSize, max = 120.dp)
                                    .aetherField(
                                        shape = RoundedCornerShape(AetherStyle.FieldRadius),
                                        fillAlpha = AetherStyle.ControlFillAlpha,
                                        strokeAlpha = AetherStyle.ControlStrokeAlpha
                                    ),
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = MaterialTheme.colorScheme.onBackground,
                                    fontSize = 16.sp,
                                    lineHeight = 22.sp
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                maxLines = 4,
                                enabled = !isSending,
                                decorationBox = { innerTextField ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 14.dp)
                                    ) {
                                        if (inputText.isEmpty()) {
                                            Text(
                                                "Написать сообщение...",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 16.sp,
                                                lineHeight = 22.sp
                                            )
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                        }
                        } // else (нет превью)

                        val sendActive = (inputText.isNotBlank() || isRecordingVoice || voicePreviewFile != null) && !isSending
                        val sendShape = CircleShape
                        val inactiveSendContent = aetherControlContent()
                        // Лёгкая пульсация кнопки во время незалоченной записи (живее, как в TG).
                        val recordingScale = if (isRecordingVoice && !recordLocked) {
                            val micPulse by rememberInfiniteTransition(label = "micPulse").animateFloat(
                                initialValue = 1f,
                                targetValue = 1.12f,
                                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                                    tween(700),
                                    androidx.compose.animation.core.RepeatMode.Reverse
                                ),
                                label = "micPulseV"
                            )
                            micPulse
                        } else {
                            1f
                        }
                        val sendTapScale = remember { Animatable(1f) }
                        LaunchedEffect(sendPulseTrigger, appearance.experimentalAnimations.value, appearance.animationSpeed.value) {
                            if (sendPulseTrigger == 0 || !appearance.animationsEnabled()) {
                                sendTapScale.snapTo(1f)
                            } else {
                                sendTapScale.animateTo(0.88f, tween(appearance.motionDuration(110)))
                                sendTapScale.animateTo(
                                    1f,
                                    spring(dampingRatio = 0.82f, stiffness = 320f * appearance.animationSpeed.value)
                                )
                            }
                        }
                        val btnScale = recordingScale * sendTapScale.value
                        val sendSurface = if (sendActive) {
                            Modifier
                                .clip(sendShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .border(
                                    AetherStyle.Stroke,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                    sendShape
                                )
                        } else {
                            Modifier.aetherControl(
                                fillAlpha = AetherStyle.ControlFillAlpha,
                                strokeAlpha = AetherStyle.ControlStrokeAlpha
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(AetherStyle.ControlSize)
                                .graphicsLayer { scaleX = btnScale; scaleY = btnScale }
                                .then(sendSurface),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSending) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = inactiveSendContent,
                                    strokeWidth = 2.dp
                                )
                            } else if (inputText.isNotBlank() && !isRecordingVoice) {
                                // Тап — отправить; долгий тап — отправить позже
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .clip(sendShape)
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
                                        .clip(sendShape)
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
                                        .clip(sendShape)
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
                                        .clip(sendShape)
                                        .clearAndSetSemantics {
                                            role = Role.Button
                                            contentDescription = if (recordVideoMode) "Видео-кружок" else "Голосовое сообщение"
                                            onClick(
                                                label = if (recordVideoMode) "Переключить на голосовое" else "Переключить на видеокружок"
                                            ) {
                                                recordVideoMode = !recordVideoMode
                                                true
                                            }
                                            if (recordVideoMode) {
                                                onLongClick(label = "Записать видеокружок") {
                                                    val camOk = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                                    val micOk = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                                    if (camOk && micOk) launchVideoNote()
                                                    else cameraPermLauncher.launch(arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO))
                                                    true
                                                }
                                            }
                                        }
                                        .pointerInput(recordVideoMode) {
                                            val lockPx = 80.dp.toPx()
                                            val cancelPx = 96.dp.toPx()
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
                                                    // Сначала ждём отпускания: иначе палец отпускается уже поверх
                                                    // рекордера и мгновенно нажимает его кнопку «Стоп».
                                                    if (waitForUpOrCancellation() != null) {
                                                        if (camOk && micOk) launchVideoNote()
                                                        else cameraPermLauncher.launch(arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO))
                                                    }
                                                    return@awaitEachGesture
                                                }

                                                // Голос: нет разрешения — запрашиваем и выходим (запишет со 2-го раза).
                                                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                                    audioPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                                    waitForUpOrCancellation()
                                                    return@awaitEachGesture
                                                }

                                                startVoiceRecording()
                                                // Мик занят/ошибка старта — жест дальше не ведём.
                                                if (!isRecordingVoice) return@awaitEachGesture
                                                var locked = false
                                                var cancelled = false
                                                recordDragX = 0f
                                                while (true) {
                                                    val ev = awaitPointerEvent()
                                                    val ch = ev.changes.firstOrNull() ?: break
                                                    // Свайп вверх → лок (можно отпустить палец)
                                                    if (!locked && (ch.position.y - startY) < -lockPx) {
                                                        locked = true
                                                        recordLocked = true
                                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                    }
                                                    // Свайп ВЛЕВО → отмена (slide-to-cancel, как в Telegram)
                                                    val dx = ch.position.x - down.position.x
                                                    if (!locked) recordDragX = dx.coerceAtMost(0f)
                                                    if (!locked && dx < -cancelPx) {
                                                        cancelled = true
                                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                        ch.consume()
                                                        break
                                                    }
                                                    if (!ch.pressed) {
                                                        // ACTION_CANCEL (шторка/звонок/перехват родителем) приходит
                                                        // с pressed=false и isConsumed — это НЕ «отпустил → отправить».
                                                        // После лока обрыв жеста не важен: запись уже hands-free.
                                                        cancelled = ch.isConsumed && !locked
                                                        ch.consume()
                                                        break
                                                    }
                                                }
                                                recordDragX = 0f
                                                // Отпустил без лока → отправить; отмена/обрыв жеста → выбросить.
                                                // locked → запись продолжается (стоп по кнопке-квадрату → превью).
                                                if (cancelled) stopVoiceRecording(send = false)
                                                else if (!locked) stopVoiceRecording(send = true)
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        if (isRecordingVoice) Icons.Filled.Stop
                                        else if (recordVideoMode) Icons.Filled.Videocam
                                        else Icons.Filled.Mic,
                                        contentDescription = if (recordVideoMode) "Видео-кружок" else "Записать голос",
                                        tint = if (isRecordingVoice) MaterialTheme.colorScheme.onPrimary else inactiveSendContent
                                    )
                                }
                            }
                        }
                    }
                  }
                  } // else canPost (#A6)

                  AnimatedVisibility(
                      visible = showJump,
                      enter = fadeIn(tween(appearance.motionDuration(220))),
                      exit = fadeOut(tween(appearance.motionDuration(180))),
                      modifier = Modifier
                          .align(Alignment.BottomEnd)
                          .navigationBarsPadding()
                          .padding(end = 12.dp, bottom = AetherStyle.ControlSize + 16.dp)
                  ) {
                      Box(
                          modifier = Modifier
                              .size(AetherStyle.SmallControlSize)
                              .aetherControl(fillAlpha = AetherStyle.ControlFillAlpha, strokeAlpha = AetherStyle.ControlStrokeAlpha)
                              .clickable {
                                  coroutineScope.launch {
                                      glideToLatest()
                                  }
                              },
                          contentAlignment = Alignment.Center
                      ) {
                          Icon(
                              Icons.Filled.KeyboardArrowDown,
                              contentDescription = "Вниз",
                              tint = aetherControlContent()
                          )
                      }
                  }
                }
            },
            containerColor = Color.Transparent
        ) { _ ->
            // (#A3) Честная индикация: легаси-канал без сквозного шифрования
            val notE2e by produceState(initialValue = false, peerId) {
                value = try { withContext(Dispatchers.IO) { checkNotE2e() } } catch (e: Exception) { false }
            }
            // Телеграм-обои: тонкий узор из точек в тон темы (субтильно, под Aether).
            Column(modifier = Modifier
                .fillMaxSize()
            ) {
                if (notE2e) {
                    Spacer(Modifier.height(AetherStyle.EdgeBarHeight + AetherStyle.ScreenVertical))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(AetherStyle.RowRadius),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = AetherStyle.ScreenHorizontal)
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
                val measuredComposerHeight = with(density) { composerHeightPx.toDp() }
                val composerPadding = if (composerHeightPx > 0) {
                    measuredComposerHeight
                } else {
                    AetherStyle.ControlSize + 16.dp +
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                }
                LazyColumn(
                    state = listState,
                    // Лента — источник размытия для стеклянных панелей (пилюля шапки и т.п.)
                    modifier = Modifier.fillMaxSize().glassSource(),
                    verticalArrangement = Arrangement.Bottom,
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        top = if (notE2e) 8.dp else AetherStyle.EdgeBarHeight + AetherStyle.ScreenVertical,
                        end = 12.dp,
                        bottom = composerPadding + 12.dp +
                            imeInsets.asPaddingValues().calculateBottomPadding()
                    )
                ) {
                    itemsIndexed(
                        items = messages,
                        key = { _, m -> m.msgId },
                        contentType = { _, m -> if (m.text.startsWith("{")) "media" else "text" }
                    ) { index, msg ->
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

                        Column {
                            if (showDate) DateSeparator(msg.timestamp)
                            Spacer(Modifier.height(if (groupedWithPrev) 2.dp else 4.dp))
                            val bubble: @Composable () -> Unit = {
                                MessageBubble(
                                    msg = msg,
                                    showTail = showTail,
                                    isChannelPost = isChannelChat,
                                    onDownloadMedia = onDownloadMedia,
                                    cachedMediaFile = cachedMediaFile,
                                    onDeleteMessage = { mid, deleteEverywhere ->
                                        coroutineScope.launch {
                                            val err = withContext(Dispatchers.IO) {
                                                onDeleteMessage(mid, deleteEverywhere)
                                            }
                                            if (err != null) {
                                                snackbarHostState.showSnackbar(
                                                    "Ошибка удаления: ${err.message}",
                                                    duration = SnackbarDuration.Long
                                                )
                                            }
                                        }
                                    },
                                    myId = myId,
                                    onReact = { messageId, emoji ->
                                        onReact(messageId, emoji)
                                        if (followLatest && messageId == currentLastMessageId) {
                                            coroutineScope.launch { glideToLatest() }
                                        }
                                    },
                                    onRetry = onRetryMessage,
                                    onReply = {
                                        editingMessage = null
                                        replyingTo = it
                                    },
                                    onEdit = {
                                        replyingTo = null
                                        editingMessage = it
                                        inputText = it.text
                                    },
                                    onForward = { forwardingMessage = it },
                                    onOpenDiscussion = onOpenDiscussion
                                )
                            }
                            val animateArrival = remember(msg.msgId) {
                                msg.msgId == arrivingMessageId && appearance.animationsEnabled()
                            }
                            if (animateArrival) {
                                var visible by remember(msg.msgId) { mutableStateOf(false) }
                                LaunchedEffect(msg.msgId) {
                                    withFrameNanos { }
                                    visible = true
                                }
                                val duration = appearance.motionDuration(if (isChannelChat) 520 else if (msg.isOut) 420 else 460)
                                val origin = androidx.compose.ui.graphics.TransformOrigin(
                                    pivotFractionX = if (msg.isOut && !isChannelChat) 1f else 0f,
                                    pivotFractionY = 1f
                                )
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = visible,
                                    enter = androidx.compose.animation.fadeIn(tween(appearance.motionDuration(300))) +
                                        androidx.compose.animation.scaleIn(
                                            animationSpec = tween(duration, easing = FastOutSlowInEasing),
                                            initialScale = 0.92f,
                                            transformOrigin = origin
                                        ) +
                                        androidx.compose.animation.slideInVertically(
                                            animationSpec = tween(duration, easing = FastOutSlowInEasing),
                                            initialOffsetY = {
                                                (it * 0.16f * appearance.motionIntensity.value).toInt().coerceAtMost(56)
                                            }
                                        ),
                                    exit = androidx.compose.animation.fadeOut(tween(appearance.motionDuration(160)))
                                ) {
                                    bubble()
                                }
                            } else {
                                bubble()
                            }
                        }
                    }
                }
            }
            } // Box (список)
            } // Column (#A3)
        }

        // --- Диалог выбора чата для пересылки ---
        forwardingMessage?.let { fmsg ->
            AlertDialog(
                onDismissRequest = { forwardingMessage = null },
                shape = RoundedCornerShape(AetherStyle.IslandRadius),
                containerColor = MaterialTheme.colorScheme.surface,
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
                    dismissButton = { TextButton(onClick = { showScheduleDialog = false }) { Text("Отмена") } },
                    shape = RoundedCornerShape(AetherStyle.IslandRadius),
                    colors = DatePickerDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    DatePicker(state = dateState, title = { Text("  Дата отправки", fontSize = 18.sp) })
                }
            } else {
                AlertDialog(
                    onDismissRequest = { showScheduleDialog = false },
                    shape = RoundedCornerShape(AetherStyle.IslandRadius),
                    containerColor = MaterialTheme.colorScheme.surface,
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
            val gridImageLoader = remember {
                coil.ImageLoader.Builder(context)
                    .components { add(coil.decode.VideoFrameDecoder.Factory()) }
                    .build()
            }
            DisposableEffect(gridImageLoader) {
                onDispose { gridImageLoader.shutdown() }
            }
            // Сразу полностью раскрыта — шторка «выпрыгивает» высоко, помещается 3 ряда фото
            val attachSheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = {
                    showAttachSheet = false
                    if (!isSending) {
                        selectedMedia.clear()
                        attachCaption = ""
                        attachAsDocument = false
                    }
                },
                sheetState = attachSheetState,
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                scrimColor = Color.Black.copy(alpha = 0.32f),
                dragHandle = null
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .aetherIsland(
                            shape = RoundedCornerShape(AetherStyle.IslandRadius),
                            fillAlpha = AetherStyle.DockFillAlpha,
                            strokeAlpha = AetherStyle.ControlStrokeAlpha
                        )
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                ) {
                    if (recentMedia.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(100.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Выберите фото через «Галерея»", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                            Text("${selIndex + 1}", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                        val actionShape = CircleShape
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(10.dp)
                        ) {
                            Box(
                                modifier = Modifier.size(46.dp).clip(actionShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
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
                            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = AetherStyle.DividerAlpha))
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
                        actionItem(Icons.AutoMirrored.Filled.InsertDriveFile, "Файл") {
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = !attachAsDocument,
                                onClick = { attachAsDocument = false },
                                label = { Text("Медиа") },
                                leadingIcon = { Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = aetherSurface(AetherStyle.SoftIslandFillAlpha),
                                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = AetherStyle.SelectedFillAlpha)
                                )
                            )
                            FilterChip(
                                selected = attachAsDocument,
                                onClick = { attachAsDocument = true },
                                label = { Text("Файл") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = aetherSurface(AetherStyle.SoftIslandFillAlpha),
                                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = AetherStyle.SelectedFillAlpha)
                                )
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        ) {
                            TextField(
                                value = attachCaption,
                                onValueChange = { attachCaption = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp)
                                    .aetherField(shape = RoundedCornerShape(AetherStyle.FieldRadius)),
                                placeholder = { Text("Подпись...", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                maxLines = 3,
                                shape = RoundedCornerShape(AetherStyle.FieldRadius),
                                colors = aetherTextFieldColors(containerAlpha = 0f)
                            )
                            val attachmentControlContent = aetherControlContent()
                            Box(
                                modifier = Modifier
                                    .size(50.dp)
                                    .aetherControl()
                                    .clickable(enabled = !isSending) {
                                        val uris = selectedMedia.toList()
                                        val cap = attachCaption.trim().ifBlank { null }
                                        val sendAsDocument = attachAsDocument
                                        isSending = true
                                        coroutineScope.launch {
                                            val error = withContext(Dispatchers.IO) {
                                                if (sendAsDocument) onSendFiles(uris, cap) else onSendMedia(uris, cap)
                                            }
                                            isSending = false
                                            if (error == null) {
                                                showAttachSheet = false
                                                selectedMedia.clear()
                                                attachCaption = ""
                                                attachAsDocument = false
                                            } else {
                                                snackbarHostState.showSnackbar("Ошибка отправки медиа: ${error.message}", duration = SnackbarDuration.Long)
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSending) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        color = attachmentControlContent,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("${selectedMedia.size}", color = attachmentControlContent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Spacer(Modifier.width(2.dp))
                                        Icon(
                                            Icons.AutoMirrored.Filled.Send,
                                            contentDescription = "Отправить",
                                            tint = attachmentControlContent,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    msg: MessageEntity,
    showTail: Boolean = true,
    isChannelPost: Boolean = false,
    onDownloadMedia: suspend (String) -> java.io.File? = { null },
    cachedMediaFile: (String) -> java.io.File? = { null },
    onDeleteMessage: (String, Boolean) -> Unit = { _, _ -> },
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
    val appearance = org.groktest.securemessenger.ui.theme.LocalThemeSettings.current
    val quickReaction = appearance.quickReaction.value
    val storedReactions = remember(msg.reactions) {
        try {
            if (msg.reactions.isBlank()) emptyMap()
            else {
                val json = org.json.JSONObject(msg.reactions)
                json.keys().asSequence().associateWith { json.getString(it) }
            }
        } catch (_: Exception) { emptyMap() }
    }
    val storedMyReaction = storedReactions[myId]
    var reactionPending by remember(msg.msgId) { mutableStateOf(false) }
    var optimisticReaction by remember(msg.msgId) { mutableStateOf<String?>(null) }
    val myReaction = if (reactionPending) optimisticReaction else storedMyReaction
    val reactionsMap = remember(storedReactions, reactionPending, optimisticReaction, myId) {
        if (!reactionPending || myId.isBlank()) storedReactions
        else storedReactions.toMutableMap().apply {
            if (optimisticReaction == null) remove(myId) else put(myId, optimisticReaction!!)
        }
    }
    LaunchedEffect(storedMyReaction, reactionPending, optimisticReaction) {
        if (!reactionPending) return@LaunchedEffect
        val expected = optimisticReaction
        if (storedMyReaction == expected) {
            reactionPending = false
        } else {
            kotlinx.coroutines.delay(2500)
            if (reactionPending && optimisticReaction == expected) reactionPending = false
        }
    }
    // Стекло на пузырях ломает рендер (видео-кружки = SurfaceView + RenderEffect → чёрный/глитч),
    // плюс блюр сплошной ленты делает текст нечитаемым. Стекло применяем только к
    // барам/панелям (см. isLiquidGlass в шапке/нижней панели), пузыри — сплошные.
    val mediaJson = remember(msg.text) { parseMediaPayloadForDisplay(msg.text) }
    val isMedia = mediaJson != null
    val kind = mediaJson?.let { mediaKindForDisplay(it) } ?: ""
    val mediaText = mediaJson?.toString() ?: msg.text
    var mediaFile by remember(msg.msgId, mediaText) {
        mutableStateOf(if (isMedia) cachedMediaFile(mediaText) else null)
    }
    var isDownloading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val bubbleInteraction = remember(msg.msgId) { MutableInteractionSource() }
    var reactionBurstTrigger by remember(msg.msgId) { mutableIntStateOf(0) }
    var reactionBurstEmoji by remember(msg.msgId) { mutableStateOf(quickReaction) }
    val reactionBurst = remember(msg.msgId) { Animatable(1f) }

    LaunchedEffect(reactionBurstTrigger, appearance.experimentalAnimations.value, appearance.animationSpeed.value, appearance.reactionEffects.value) {
        if (reactionBurstTrigger == 0 || !appearance.reactionEffects.value || !appearance.animationsEnabled()) {
            reactionBurst.snapTo(1f)
            return@LaunchedEffect
        }
        reactionBurst.snapTo(0f)
        reactionBurst.animateTo(
            1f,
            tween(appearance.motionDuration(560), easing = FastOutSlowInEasing)
        )
    }

    fun react(emoji: String, remove: Boolean) {
        optimisticReaction = if (remove) null else emoji
        reactionPending = true
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
        if (!remove && appearance.reactionEffects.value && appearance.animationsEnabled()) {
            reactionBurstEmoji = emoji
            reactionBurstTrigger++
        }
        onReact(msg.msgId, if (remove) "" else emoji)
    }

    // (#A5) remember: JSON парсится один раз, а не при каждой рекомпозиции пузыря
    val mediaCaption = mediaJson?.optString("caption", "") ?: ""
    val hasStyledMediaCaption = mediaCaption.isNotBlank() && (kind == "image" || kind == "video")
    val isDetachedMedia = (kind == "image" || kind == "video" || kind == "video_note") && !hasStyledMediaCaption
    val bubbleVisual = aetherBubbleVisual(alignEnd)
    val detachedLabelFill = Color.Black.copy(alpha = 0.58f)
    val detachedLabelStroke = Color.White.copy(alpha = 0.18f)
    val bubbleColor by animateColorAsState(
        targetValue = if (isDetachedMedia) {
            Color.Transparent
        } else {
            bubbleVisual.fill
        },
        animationSpec = tween(appearance.motionDuration(220)),
        label = "messageBubbleColor"
    )
    val bubbleBorderColor by animateColorAsState(
        targetValue = if (isDetachedMedia) Color.Transparent else bubbleVisual.stroke,
        animationSpec = tween(appearance.motionDuration(220)),
        label = "messageBubbleBorder"
    )
    val textColor = if (isDetachedMedia) Color.White else bubbleVisual.content
    val metadataColor = if (isDetachedMedia) Color.White else bubbleVisual.metadata
    val inlineMetadata = !isMedia && reactionsMap.isEmpty() && !isChannelPost
    val metadataContent: @Composable () -> Unit = {
        Row(
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (msg.isEdited && !isMedia) {
                Text(
                    "изм.",
                    fontSize = 11.sp,
                    color = metadataColor,
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
            Text(
                formatMsgTime(msg.timestamp),
                fontSize = 11.sp,
                color = metadataColor
            )
            if (isOut) {
                Spacer(Modifier.width(3.dp))
                Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                    Crossfade(
                        targetState = msg.status,
                        animationSpec = tween(appearance.motionDuration(200)),
                        label = "messageStatus"
                    ) { status ->
                        when {
                            status == -1 -> Icon(
                                Icons.Filled.ErrorOutline,
                                contentDescription = "Ошибка отправки",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.fillMaxSize()
                            )
                            status == 0 -> Icon(
                                Icons.Filled.Schedule,
                                contentDescription = "Отправляется",
                                tint = textColor.copy(alpha = 0.6f),
                                modifier = Modifier.fillMaxSize()
                            )
                            else -> Icon(
                                if (status >= 2) Icons.Filled.DoneAll else Icons.Filled.Done,
                                contentDescription = null,
                                tint = when {
                                    isDetachedMedia -> metadataColor
                                    status >= 3 -> MaterialTheme.colorScheme.primary
                                    else -> textColor.copy(alpha = 0.6f)
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
    val reactionChips: @Composable () -> Unit = {
        val counts = reactionsMap.values.toSet()
        val chipBg = if (isDetachedMedia) {
            detachedLabelFill
        } else {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            counts.forEach { emoji ->
                key(emoji) {
                    val mineChip = myReaction == emoji
                    val count = reactionsMap.values.count { it == emoji }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (mineChip) {
                                    if (isDetachedMedia) detachedLabelFill
                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                } else {
                                    chipBg
                                }
                            )
                            .then(
                                if (mineChip && isDetachedMedia) {
                                    Modifier.border(
                                        1.dp,
                                        Color.White.copy(alpha = 0.78f),
                                        RoundedCornerShape(12.dp)
                                    )
                                } else {
                                    Modifier
                                }
                            )
                            .clickable { react(emoji, mineChip) }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(emoji, fontSize = 13.sp, color = textColor)
                            if (count > 1) {
                                Text(
                                    count.toString(),
                                    fontSize = 11.sp,
                                    color = if (isDetachedMedia) Color.White else textColor.copy(alpha = 0.75f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(msg.msgId, mediaText, kind) {
        if (kind == "image" && mediaFile == null && !isDownloading && errorMessage == null) {
            isDownloading = true
            val file = withContext(Dispatchers.IO) { onDownloadMedia(mediaText) }
            if (file != null) {
                mediaFile = file
            } else {
                errorMessage = "Не удалось открыть изображение"
            }
            isDownloading = false
        }
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
                            coroutineScope.launch {
                                swipeOffset.animateTo(
                                    0f,
                                    if (!appearance.animationsEnabled()) snap() else spring(
                                        dampingRatio = 0.82f,
                                        stiffness = 420f * appearance.animationSpeed.value
                                    )
                                )
                            }
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
        val bubbleShape = aetherBubbleShape(alignEnd, showTail)
        Surface(
            color = bubbleColor,
            shape = bubbleShape,
            modifier = Modifier
                .widthIn(max = if (kind == "video_note") 308.dp else if (isDetachedMedia) 320.dp else 340.dp)
                .then(
                    if (isDetachedMedia) Modifier
                    else Modifier.border(AetherStyle.Stroke, bubbleBorderColor, bubbleShape)
                )
                .combinedClickable(
                    interactionSource = bubbleInteraction,
                    indication = null,
                    onClick = {},
                    // Двойной тап — быстрая реакция (по умолчанию ❤️, настраивается);
                    // повторный двойной тап снимает её.
                    onDoubleClick = {
                        react(quickReaction, myReaction == quickReaction)
                    },
                    onLongClick = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        menuOpen = true
                    }
                )
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = if (isDetachedMedia) 0.dp else 12.dp,
                    vertical = if (isDetachedMedia) 0.dp else 8.dp
                )
            ) {
                if (msg.forwardedFrom != null) {
                    val forwardedShape = RoundedCornerShape(10.dp)
                    Text(
                        "↪ Переслано от ${msg.forwardedFrom}",
                        color = if (isDetachedMedia) Color.White else textColor.copy(alpha = 0.75f),
                        fontSize = 12.sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        modifier = Modifier
                            .padding(bottom = 4.dp)
                            .then(
                                if (isDetachedMedia) {
                                    Modifier
                                        .clip(forwardedShape)
                                        .background(detachedLabelFill)
                                        .border(AetherStyle.Stroke, detachedLabelStroke, forwardedShape)
                                        .padding(horizontal = 7.dp, vertical = 3.dp)
                                } else {
                                    Modifier
                                }
                            )
                    )
                }
                if (msg.replyToText != null) {
                    val replyShape = RoundedCornerShape(10.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .then(
                                if (isDetachedMedia) {
                                    Modifier
                                        .clip(replyShape)
                                        .background(detachedLabelFill)
                                        .border(AetherStyle.Stroke, detachedLabelStroke, replyShape)
                                        .padding(horizontal = 7.dp, vertical = 4.dp)
                                } else {
                                    Modifier
                                }
                            )
                    ) {
                        Box(modifier = Modifier.width(3.dp).height(34.dp).background(textColor.copy(alpha = 0.5f)))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            msg.replyToText,
                            color = if (isDetachedMedia) Color.White else textColor.copy(alpha = 0.85f),
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
                if (!isMedia) {
                    if (reactionsMap.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = msg.text,
                                color = textColor,
                                fontSize = appearance.messageTextSize.value.sp,
                                lineHeight = (appearance.messageTextSize.value + 6).sp,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(Modifier.width(8.dp))
                            reactionChips()
                            Spacer(Modifier.width(8.dp))
                            metadataContent()
                        }
                    } else if (inlineMetadata) {
                        Box {
                            Text(
                                text = msg.text + "\u00A0".repeat(if (isOut) 13 else 9),
                                color = textColor,
                                fontSize = appearance.messageTextSize.value.sp,
                                lineHeight = (appearance.messageTextSize.value + 6).sp
                            )
                            Box(Modifier.align(Alignment.BottomEnd)) {
                                metadataContent()
                            }
                        }
                    } else {
                        Text(
                            text = msg.text,
                            color = textColor,
                            fontSize = appearance.messageTextSize.value.sp,
                            lineHeight = (appearance.messageTextSize.value + 6).sp
                        )
                    }
                } else {
                    val json = mediaJson
                    val mimeType = json?.optString("mime_type", "") ?: ""

                    if (kind == "video_note") {
                        VideoNoteMessage(
                            jsonText = mediaText,
                            cachedMediaFile = cachedMediaFile,
                            onDownloadMedia = onDownloadMedia,
                            onLongClick = { menuOpen = true }
                        )
                    } else if (kind == "video") {
                        VideoMessage(
                            jsonText = mediaText,
                            cachedMediaFile = cachedMediaFile,
                            onDownloadMedia = onDownloadMedia
                        )
                    } else if (kind == "voice") {
                        val wireDuration = json?.optDouble("duration", 0.0) ?: 0.0
                        val durationMs = if (wireDuration in 0.001..600.0) {
                            (wireDuration * 1000).toLong()
                        } else {
                            wireDuration.toLong()
                        }
                        // Реальная волна из wire-поля "waveform" (инты 0..31); нет — фейк в плеере
                        val waveform = remember(mediaText) {
                            json?.optJSONArray("waveform")?.let { arr ->
                                List(arr.length()) { i -> arr.optInt(i) }
                            }?.takeIf { it.isNotEmpty() }
                        }
                        VoiceMessagePlayer(
                            jsonText = mediaText,
                            durationMs = durationMs,
                            tint = textColor,
                            onDownloadMedia = onDownloadMedia,
                            waveform = waveform
                        )
                    } else if (kind == "file") {
                        FileMessageBubble(
                            jsonText = mediaText,
                            fileName = json?.optString("file_name", "") ?: "",
                            fileSize = json?.optLong("file_size", 0L) ?: 0L,
                            tint = textColor,
                            onDownloadMedia = onDownloadMedia
                        )
                    } else if (kind == "image") {
                        var showFullscreen by remember { mutableStateOf(false) }
                        val imageAspect = remember(mediaText) {
                            val width = json?.optDouble("width", 0.0) ?: 0.0
                            val height = json?.optDouble("height", 0.0) ?: 0.0
                            if (width > 0.0 && height > 0.0) {
                                (width / height).toFloat().coerceIn(0.84f, 1.8f)
                            } else {
                                4f / 3f
                            }
                        }
                        Box(
                            modifier = Modifier
                                .width(300.dp)
                                .aspectRatio(imageAspect)
                                .clip(RoundedCornerShape(AetherStyle.MediaRadius))
                                .background(textColor.copy(alpha = 0.08f))
                                .clickable(enabled = mediaFile != null) { showFullscreen = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Crossfade(
                                targetState = mediaFile,
                                animationSpec = tween(appearance.motionDuration(180)),
                                label = "messageImage"
                            ) { imageFile ->
                                when {
                                    imageFile != null -> coil.compose.AsyncImage(
                                        model = imageFile,
                                        contentDescription = "Вложение",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                    )
                                    errorMessage != null -> Text(
                                        errorMessage!!,
                                        color = MaterialTheme.colorScheme.error,
                                        fontSize = 12.sp
                                    )
                                    else -> CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = textColor
                                    )
                                }
                            }
                        }
                        val fullscreenFile = mediaFile
                        if (showFullscreen && fullscreenFile != null) {
                            FullscreenImageViewer(
                                imageModel = fullscreenFile,
                                onClose = { showFullscreen = false }
                            )
                        }
                    } else if (mediaFile != null) {
                        if (mimeType.startsWith("image/")) {
                            coil.compose.AsyncImage(
                                model = mediaFile,
                                contentDescription = "Вложение",
                                modifier = Modifier
                                    .width(300.dp)
                                    .aspectRatio(4f / 3f)
                                    .clip(RoundedCornerShape(AetherStyle.MediaRadius)),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                        } else {
                            Text("Файл загружен", color = textColor, fontSize = 14.sp)
                        }
                    } else {
                        if (isDownloading) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onBackground)
                        } else {
                            AetherPrimaryButton(
                                text = if (mimeType.startsWith("image/")) "Показать фото" else "Скачать файл",
                                onClick = {
                                    isDownloading = true
                                    coroutineScope.launch {
                                        val file = withContext(Dispatchers.IO) { onDownloadMedia(mediaText) }
                                        if (file != null) {
                                            mediaFile = file
                                        } else {
                                            errorMessage = "Ошибка скачивания"
                                        }
                                        isDownloading = false
                                    }
                                },
                                fillWidth = false,
                            )
                            if (errorMessage != null) {
                                Text(errorMessage!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                            }
                        }
                    }
                    if (mediaCaption.isNotBlank() && kind != "voice") {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            mediaCaption,
                            color = textColor,
                            fontSize = 15.sp,
                            lineHeight = 20.sp,
                            modifier = if (isDetachedMedia) {
                                Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(detachedLabelFill)
                                    .border(AetherStyle.Stroke, detachedLabelStroke, RoundedCornerShape(12.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            } else {
                                Modifier
                            }
                        )
                    }
                }
                
                // (#A6) Обсуждение поста — открывает подвязанную группу канала
                if (isChannelPost && onOpenDiscussion != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = AetherStyle.DividerAlpha))
                    TextButton(
                        onClick = { onOpenDiscussion(msg) },
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("💬 Обсуждение", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                }

                if (isMedia && reactionsMap.isNotEmpty()) {
                    Column {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            reactionChips()
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = if (isDetachedMedia) {
                                    Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(detachedLabelFill)
                                        .border(AetherStyle.Stroke, detachedLabelStroke, RoundedCornerShape(12.dp))
                                        .padding(horizontal = 7.dp, vertical = 3.dp)
                                } else {
                                    Modifier
                                }
                            ) {
                                metadataContent()
                            }
                        }
                    }
                }
                if (!inlineMetadata && reactionsMap.isEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .align(Alignment.End)
                            .then(
                                if (isDetachedMedia) {
                                    Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(detachedLabelFill)
                                        .border(AetherStyle.Stroke, detachedLabelStroke, RoundedCornerShape(12.dp))
                                        .padding(horizontal = 7.dp, vertical = 3.dp)
                                } else {
                                    Modifier
                                }
                            )
                    ) {
                        metadataContent()
                    }
                }
            }
        }
        if (reactionBurst.value < 0.999f) {
            val progress = reactionBurst.value
            val mainScale = if (progress < 0.28f) {
                0.45f + (progress / 0.28f) * 1.05f
            } else {
                1.5f - ((progress - 0.28f) / 0.72f) * 0.5f
            }
            val fade = if (progress < 0.64f) 1f else (1f - (progress - 0.64f) / 0.36f).coerceIn(0f, 1f)
            val radius = with(androidx.compose.ui.platform.LocalDensity.current) {
                appearance.motionDistance(34f).dp.toPx()
            }
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(112.dp),
                contentAlignment = Alignment.Center
            ) {
                repeat(4) { index ->
                    val angle = index * 1.5708f - 1.5708f
                    Text(
                        reactionBurstEmoji,
                        fontSize = 12.sp,
                        modifier = Modifier.graphicsLayer {
                            translationX = (kotlin.math.cos(angle.toDouble()) * radius * progress).toFloat()
                            translationY = (kotlin.math.sin(angle.toDouble()) * radius * progress).toFloat()
                            alpha = fade * (1f - progress * 0.45f)
                            val particleScale = 0.45f + (1f - progress) * 0.45f
                            scaleX = particleScale
                            scaleY = particleScale
                            rotationZ = index * 24f * progress
                        }
                    )
                }
                Text(
                    reactionBurstEmoji,
                    fontSize = 34.sp,
                    modifier = Modifier.graphicsLayer {
                        scaleX = mainScale
                        scaleY = mainScale
                        alpha = fade
                        translationY = -radius * 0.7f * progress
                        rotationZ = -7f + 14f * progress
                    }
                )
            }
        }
        // Канон меню: surface-контейнер, скругление 20.dp (в M3 1.2 у
        // DropdownMenu нет shape/containerColor — задаём через тему).
        MaterialTheme(
            shapes = MaterialTheme.shapes.copy(extraSmall = CircleShape),
            colorScheme = MaterialTheme.colorScheme.copy(surfaceTint = Color.Transparent)
        ) {
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                listOf("❤️", "👍", "🔥", "😂", "😮", "😢").forEach { e ->
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    val sc by animateFloatAsState(
                        targetValue = if (pressed) 1.4f else 1f,
                        animationSpec = if (!appearance.animationsEnabled()) snap() else spring(
                            dampingRatio = 0.72f,
                            stiffness = 340f * appearance.animationSpeed.value
                        ),
                        label = "emojiScale"
                    )
                    val mine = myReaction == e
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .graphicsLayer { scaleX = sc; scaleY = sc }
                            .clip(CircleShape)
                            // Подсветка моей выбранной реакции — повторный тап её снимает
                            .background(if (mine) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.Transparent)
                            .clickable(interactionSource = interaction, indication = null) {
                                menuOpen = false
                                coroutineScope.launch {
                                    kotlinx.coroutines.delay(100)
                                    react(e, mine)
                                }
                            }
                            .padding(4.dp),
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
                    showDeleteDialog = true
                    menuOpen = false
                }
            )
        }
        } // MaterialTheme (канон меню)
        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                shape = RoundedCornerShape(AetherStyle.IslandRadius),
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text("Удалить сообщение?") },
                text = { Text("Можно убрать его только у себя или отправить удаление всем участникам чата.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteDialog = false
                            onDeleteMessage(msg.msgId, true)
                        }
                    ) {
                        Text("У всех", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                showDeleteDialog = false
                                onDeleteMessage(msg.msgId, false)
                            }
                        ) {
                            Text("Только у себя")
                        }
                        TextButton(onClick = { showDeleteDialog = false }) {
                            Text("Отмена")
                        }
                    }
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
fun FullscreenImageViewer(imageModel: Any, onClose: () -> Unit) {
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
                model = imageModel,
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

private fun jumpHistoryWeight(raw: String): Int {
    if (!raw.trimStart().startsWith("{")) return 1
    return when (parseMediaPayloadForDisplay(raw)?.optString("kind")) {
        "image", "video", "video_note", "video_msg", "circle" -> 5
        "voice", "file", "document" -> 2
        else -> 1
    }
}

private fun parseMediaPayloadForDisplay(raw: String): org.json.JSONObject? {
    val obj = try { org.json.JSONObject(raw) } catch (e: Exception) { return null }
    val type = obj.optString("type")
    if (type == "media") return normalizeMediaPayloadForDisplay(obj, fallbackKind = null)
    if (type !in setOf("image", "video", "voice", "video_msg", "file")) return null

    val media = obj.optJSONObject("media")
    val out = org.json.JSONObject().put("type", "media")
    putFirstForDisplay(out, "file_id", obj, media, "file_id", "fileId", "id")
        ?: obj.optString("content").takeIf { it.isNotBlank() && !it.startsWith("data:") }?.let { out.put("file_id", it) }
    putFirstForDisplay(out, "sym_key", obj, media, "sym_key", "symKey", "key", "key_b64")
    putFirstForDisplay(out, "nonce", obj, media, "nonce", "nonce_b64", "iv")
    putFirstForDisplay(out, "mime_type", obj, media, "mime_type", "mimeType", "mime")
    putFirstForDisplay(out, "file_name", obj, media, "file_name", "fileName", "filename", "name")
    putFirstForDisplay(out, "caption", obj, media, "caption")
        ?: obj.optString("text").takeIf { it.isNotBlank() }?.let { out.put("caption", it) }
    if (obj.has("file_size")) out.put("file_size", obj.optLong("file_size"))
    if (obj.has("fileSize")) out.put("file_size", obj.optLong("fileSize"))
    if (media?.has("file_size") == true) out.put("file_size", media.optLong("file_size"))
    if (media?.has("fileSize") == true) out.put("file_size", media.optLong("fileSize"))
    // Реальная волна голосового (массив интов) — переносим как есть
    (obj.optJSONArray("waveform") ?: media?.optJSONArray("waveform"))?.let { out.put("waveform", it) }
    return normalizeMediaPayloadForDisplay(out, fallbackKind = when (type) {
        "image" -> "image"
        "video" -> "video"
        "voice" -> "voice"
        "video_msg" -> "video_msg"
        else -> "file"
    })
}

private fun normalizeMediaPayloadForDisplay(obj: org.json.JSONObject, fallbackKind: String?): org.json.JSONObject {
    if (!obj.has("kind") || obj.optString("kind").isBlank()) {
        obj.put("kind", fallbackKind ?: mediaKindForDisplay(obj))
    }
    if (obj.optString("kind") in setOf("video_msg", "circle")) {
        obj.put("kind", "video_note")
    }
    return obj
}

private fun mediaKindForDisplay(obj: org.json.JSONObject): String {
    val kind = obj.optString("kind", "")
    if (kind.isNotBlank()) return kind
    val mime = firstStringForDisplay(obj, "mime_type", "mimeType", "mime") ?: ""
    return when {
        mime.startsWith("image/") -> "image"
        mime.startsWith("video/") -> "video"
        mime.startsWith("audio/") -> "voice"
        else -> "file"
    }
}

private fun putFirstForDisplay(
    out: org.json.JSONObject,
    target: String,
    primary: org.json.JSONObject,
    nested: org.json.JSONObject?,
    vararg keys: String
): String? {
    val value = firstStringForDisplay(primary, *keys) ?: nested?.let { firstStringForDisplay(it, *keys) }
    if (!value.isNullOrBlank()) out.put(target, value)
    return value
}

private fun firstStringForDisplay(obj: org.json.JSONObject, vararg keys: String): String? {
    for (key in keys) {
        val value = obj.optString(key, "")
        if (value.isNotBlank()) return value
    }
    return null
}

/**
 * Даунсемпл огибающей ГС до <=63 бакетов усреднением — формат wire-поля "waveform"
 * (инты 0..31, как у Telegram). Пустой вход → null (плеер нарисует фолбэк).
 */
private fun downsampleWaveform(src: List<Int>, maxBuckets: Int = 63): List<Int>? {
    if (src.isEmpty()) return null
    if (src.size <= maxBuckets) return src.toList()
    val out = ArrayList<Int>(maxBuckets)
    for (i in 0 until maxBuckets) {
        val from = i * src.size / maxBuckets
        val to = ((i + 1) * src.size / maxBuckets).coerceAtLeast(from + 1)
        var sum = 0
        for (j in from until to) sum += src[j]
        out.add(sum / (to - from))
    }
    return out
}

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

/** Янтарный аватар «Сохранённых сообщений» (звезда) — часть аватар-палитры. */
internal val savedChatColor = Color(0xFFFFB400)

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
        isSameDay(ts, now) -> "был(а) сегодня в " + formatMsgTime(ts)
        else -> {
            val yesterday = java.util.Calendar.getInstance()
                .apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }.timeInMillis
            if (isSameDay(ts, yesterday)) "был(а) вчера в " + formatMsgTime(ts)
            else "был(а) " + java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale("ru")).format(java.util.Date(ts))
        }
    }
}

/** Плашка-разделитель дат по центру (как в Telegram). */
@Composable
private fun DateSeparator(ts: Long) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = formatDateSep(ts),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .aetherIsland(
                    shape = RoundedCornerShape(12.dp),
                    fillAlpha = AetherStyle.IslandFillAlpha,
                    strokeAlpha = AetherStyle.ControlStrokeAlpha
                )
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
