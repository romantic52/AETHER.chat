package org.groktest.securemessenger.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.groktest.securemessenger.AetherService
import org.groktest.securemessenger.api.ServerConfig
import org.groktest.securemessenger.data.ChatEntity
import org.groktest.securemessenger.ui.theme.AetherStyle
import org.groktest.securemessenger.ui.theme.aetherControl
import org.groktest.securemessenger.ui.theme.aetherControlContent
import org.groktest.securemessenger.ui.theme.aetherIsland
import org.groktest.securemessenger.ui.theme.aetherSurface
import org.groktest.securemessenger.webrtc.WebRTCClient
import org.json.JSONObject
import org.webrtc.*

/**
 * Оверлей звонка: рисуется ПОВЕРХ всего приложения (не отдельный экран навигации),
 * поэтому звонок можно свернуть в мини-панель и продолжать пользоваться приложением.
 */
@Composable
fun CallOverlay(
    peerId: String,
    isIncoming: Boolean,
    isVideoCall: Boolean,
    minimized: Boolean,
    onMinimizedChange: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val callbackActive = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    val neededPerms = remember(isVideoCall) {
        if (isVideoCall) {
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }
    }

    var hasPermissions by remember {
        mutableStateOf(
            neededPerms.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }
    var setupRequested by remember { mutableStateOf(!isIncoming) }
    var callStatus by remember {
        mutableStateOf(if (isIncoming) "Входящий вызов..." else "Вызов...")
    }
    var connected by remember { mutableStateOf(false) }
    var hasConnected by remember { mutableStateOf(false) }
    var callSeconds by remember { mutableStateOf(0) }
    BackHandler(enabled = !minimized) { onMinimizedChange(true) }
    var micEnabled by remember { mutableStateOf(true) }
    var cameraEnabled by remember { mutableStateOf(isVideoCall) }
    var speakerOn by remember { mutableStateOf(isVideoCall) }
    var accepted by remember { mutableStateOf(false) }
    var ending by remember { mutableStateOf(false) }
    var closingVisual by remember { mutableStateOf(false) }
    var disconnectGeneration by remember { mutableIntStateOf(0) }
    var localDescriptionSet by remember { mutableStateOf(false) }

    var webRTCClient by remember { mutableStateOf<WebRTCClient?>(null) }
    var eglBase by remember { mutableStateOf<EglBase?>(null) }
    var remoteVideoTrack by remember { mutableStateOf<VideoTrack?>(null) }
    val pendingSignals = remember { mutableStateListOf<JSONObject>() }

    // Имя и аватар собеседника из ядрового хранилища (peerId — фолбэк).
    var peerChat by remember(peerId) { mutableStateOf<ChatEntity?>(null) }
    LaunchedEffect(peerId) {
        peerChat = withContext(Dispatchers.IO) {
            runCatching { AetherService.chatLookup?.invoke(peerId) }.getOrNull()
        }
    }
    val peerName = peerChat?.name?.takeIf { it.isNotBlank() } ?: peerId
    val peerAvatarFileId = peerChat?.avatarFileId?.takeIf { it.isNotBlank() }

    fun sendHangup() {
        try {
            AetherService.sendWebRtcSignal(JSONObject().apply {
                put("type", "webrtc_hangup")
                put("recipient_id", peerId)
                put("sig_id", "android_${System.currentTimeMillis()}")
            })
        } catch (_: Exception) {}
    }

    fun finishCall(
        sendSignal: Boolean,
        message: String = "Звонок завершён",
        holdMessageMs: Long = 0L
    ) {
        if (ending) return
        ending = true
        callStatus = message
        if (sendSignal) sendHangup()
        scope.launch {
            if (holdMessageMs > 0L) delay(holdMessageMs)
            closingVisual = true
            delay(190)
            if (callbackActive.get()) onClose()
        }
    }

    fun endCall(sendSignal: Boolean) {
        finishCall(sendSignal = sendSignal)
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermissions = neededPerms.all { permissions[it] == true }
        if (!hasPermissions && setupRequested) {
            finishCall(
                sendSignal = isIncoming,
                message = if (isVideoCall) {
                    "Нужен доступ к микрофону и камере"
                } else {
                    "Нужен доступ к микрофону"
                },
                holdMessageMs = 700
            )
        }
    }

    LaunchedEffect(Unit) {
        if (!isIncoming && AetherService.wsClient?.isActive() != true) {
            finishCall(false, "Нет соединения", 700)
            return@LaunchedEffect
        }
        if (!isIncoming && !hasPermissions) launcher.launch(neededPerms)
    }

    LaunchedEffect(hasConnected) {
        if (!hasConnected) return@LaunchedEffect
        while (!ending) {
            delay(1000)
            callSeconds++
            if (connected) callStatus = "Разговор " + formatCallTime(callSeconds)
        }
    }

    LaunchedEffect(Unit) {
        var waited = 0
        while (!hasConnected && !ending && waited < 45) {
            delay(1000)
            waited++
        }
        if (!hasConnected && !ending) {
            finishCall(
                sendSignal = true,
                message = if (isIncoming && !accepted) "Пропущенный вызов" else "Не отвечает",
                holdMessageMs = 700
            )
        }
    }

    fun applyAudioRoute(speaker: Boolean) {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val types = if (speaker) {
                    listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                } else {
                    listOf(
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_BLE_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    )
                }
                val device = types.firstNotNullOfOrNull { type ->
                    manager.availableCommunicationDevices.firstOrNull { it.type == type }
                }
                if (device != null) manager.setCommunicationDevice(device)
            } else {
                @Suppress("DEPRECATION")
                manager.isSpeakerphoneOn = speaker
            }
        } catch (_: Exception) {}
    }

    DisposableEffect(Unit) {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val previousMode = manager.mode
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        onDispose {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    manager.clearCommunicationDevice()
                } else {
                    @Suppress("DEPRECATION")
                    run { manager.isSpeakerphoneOn = false }
                }
                manager.mode = previousMode
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(speakerOn) {
        applyAudioRoute(speakerOn)
    }

    // Рингтон + вибрация входящего: играют, пока вызов не принят и не завершён.
    // Уважают режим звонка системы (беззвучный/вибро).
    val ringing = isIncoming && !accepted && !ending
    DisposableEffect(ringing) {
        if (!ringing) return@DisposableEffect onDispose {}
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var player: MediaPlayer? = null
        var vibrator: Vibrator? = null
        try {
            if (audio.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                if (uri != null) {
                    player = MediaPlayer().apply {
                        setDataSource(context, uri)
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                        isLooping = true
                        prepare()
                        start()
                    }
                }
            }
            if (audio.ringerMode != AudioManager.RINGER_MODE_SILENT) {
                vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }
                val pattern = longArrayOf(0L, 900L, 800L)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, 0)
                }
            }
        } catch (_: Exception) {}
        onDispose {
            try { player?.stop() } catch (_: Exception) {}
            try { player?.release() } catch (_: Exception) {}
            try { vibrator?.cancel() } catch (_: Exception) {}
        }
    }

    // Гудки исходящего вызова, пока собеседник не ответил.
    val ringback = !isIncoming && !hasConnected && !ending
    DisposableEffect(ringback) {
        if (!ringback) return@DisposableEffect onDispose {}
        val tone = try {
            ToneGenerator(AudioManager.STREAM_VOICE_CALL, 65).also {
                it.startTone(ToneGenerator.TONE_SUP_RINGTONE)
            }
        } catch (_: Exception) { null }
        onDispose {
            try { tone?.stopTone() } catch (_: Exception) {}
            try { tone?.release() } catch (_: Exception) {}
        }
    }

    fun processSignal(signal: JSONObject) {
        val type = signal.optString("type")
        val client = webRTCClient
        when (type) {
            "webrtc_offer" -> {
                // Повторный offer в ЖИВОМ звонке = ICE-restart от собеседника:
                // принимаем и отвечаем. Первичный offer сюда не попадает
                // (его обрабатывает MainActivity через incomingOfferListener).
                if (client == null || !hasConnected) return
                val sdp = signal.optString("sdp")
                if (sdp.isBlank()) return
                callStatus = "Восстановление связи..."
                client.setRemoteDescription(
                    SessionDescription(SessionDescription.Type.OFFER, sdp),
                    onSet = {
                        client.createAnswer(object : SdpObserver {
                            override fun onCreateSuccess(description: SessionDescription?) {
                                val answer = description ?: return
                                client.setLocalDescription(
                                    answer,
                                    onSet = {
                                        AetherService.sendWebRtcSignal(JSONObject().apply {
                                            put("type", "webrtc_answer")
                                            put("recipient_id", peerId)
                                            put("sdp", answer.description)
                                        })
                                    },
                                    onError = {}
                                )
                            }

                            override fun onSetSuccess() = Unit
                            override fun onCreateFailure(error: String?) = Unit
                            override fun onSetFailure(error: String?) = Unit
                        })
                    },
                    onError = {}
                )
            }
            "webrtc_answer" -> {
                if (client == null || !localDescriptionSet) {
                    pendingSignals.add(JSONObject(signal.toString()))
                    return
                }
                callStatus = "Соединение..."
                val sdp = SessionDescription(
                    SessionDescription.Type.ANSWER,
                    signal.optString("sdp")
                )
                client.setRemoteDescription(
                    sdp,
                    onError = {
                        mainHandler.post {
                            finishCall(false, "Ошибка согласования звонка", 700)
                        }
                    }
                )
            }
            "webrtc_ice" -> {
                if (client == null) {
                    pendingSignals.add(JSONObject(signal.toString()))
                    return
                }
                val candidate = signal.optString("candidate")
                    .ifEmpty { signal.optString("sdp") }
                if (candidate.isNotBlank()) {
                    client.addIceCandidate(
                        IceCandidate(
                            signal.optString("sdpMid").takeIf { it.isNotBlank() },
                            signal.optInt("sdpMLineIndex"),
                            candidate
                        )
                    )
                }
            }
            "webrtc_hangup" -> {
                finishCall(false, "Звонок завершён", 450)
            }
            "webrtc_busy" -> {
                finishCall(false, "Абонент занят", 650)
            }
        }
    }

    DisposableEffect(peerId) {
        val listener: (JSONObject) -> Unit = { raw ->
            val copy = JSONObject(raw.toString())
            mainHandler.post {
                if (!callbackActive.get() || ending) return@post
                val sender = copy.optString("sender_id").lowercase()
                if (sender.isNotEmpty() && sender != peerId.lowercase()) {
                    if (copy.optString("type") == "webrtc_offer") {
                        AetherService.sendWebRtcSignal(JSONObject().apply {
                            put("type", "webrtc_busy")
                            put("recipient_id", sender)
                        })
                    }
                } else {
                    processSignal(copy)
                }
            }
        }
        AetherService.callListener = listener
        onDispose {
            callbackActive.set(false)
            if (AetherService.callListener === listener) AetherService.callListener = null
            AetherService.lastOffer = null
            webRTCClient?.dispose()
            webRTCClient = null
            try { eglBase?.release() } catch (_: Exception) {}
            eglBase = null
        }
    }

    LaunchedEffect(hasPermissions, setupRequested) {
        if (!hasPermissions || !setupRequested || webRTCClient != null || ending) {
            return@LaunchedEffect
        }

        val egl = EglBase.create()
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                mainHandler.post {
                    if (!callbackActive.get() || ending) return@post
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            connected = true
                            hasConnected = true
                            disconnectGeneration++
                            callStatus = "Разговор " + formatCallTime(callSeconds)
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            connected = false
                            callStatus = "Восстановление связи..."
                            val generation = ++disconnectGeneration
                            // Инициатор звонка пробует ICE-restart (новый offer с
                            // IceRestart) — отвечающая сторона просто ответит на него.
                            if (!isIncoming) {
                                scope.launch {
                                    delay(2_500)
                                    if (!connected && generation == disconnectGeneration && !ending && hasConnected) {
                                        val client = webRTCClient ?: return@launch
                                        client.createOffer(object : SdpObserver {
                                            override fun onCreateSuccess(description: SessionDescription?) {
                                                val offer = description ?: return
                                                client.setLocalDescription(
                                                    offer,
                                                    onSet = {
                                                        AetherService.sendWebRtcSignal(JSONObject().apply {
                                                            put("type", "webrtc_offer")
                                                            put("recipient_id", peerId)
                                                            put("sdp", offer.description)
                                                            put("isVideoCall", isVideoCall)
                                                        })
                                                    },
                                                    onError = {}
                                                )
                                            }

                                            override fun onSetSuccess() = Unit
                                            override fun onCreateFailure(error: String?) = Unit
                                            override fun onSetFailure(error: String?) = Unit
                                        }, iceRestart = true)
                                    }
                                }
                            }
                            scope.launch {
                                delay(10_000)
                                if (!connected && generation == disconnectGeneration && !ending) {
                                    finishCall(false, "Связь прервана", 650)
                                }
                            }
                        }
                        PeerConnection.IceConnectionState.FAILED,
                        PeerConnection.IceConnectionState.CLOSED -> {
                            connected = false
                            finishCall(false, "Связь прервана", 650)
                        }
                        else -> Unit
                    }
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit

            override fun onIceCandidate(candidate: IceCandidate) {
                AetherService.sendWebRtcSignal(JSONObject().apply {
                    put("type", "webrtc_ice")
                    put("recipient_id", peerId)
                    put("sdpMid", candidate.sdpMid)
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                    put("candidate", candidate.sdp)
                    put("sdp", candidate.sdp)
                })
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit

            override fun onAddStream(stream: MediaStream?) {
                stream?.videoTracks?.firstOrNull()?.let { track ->
                    mainHandler.post { remoteVideoTrack = track }
                }
            }

            override fun onRemoveStream(stream: MediaStream?) = Unit
            override fun onDataChannel(channel: DataChannel?) = Unit
            override fun onRenegotiationNeeded() = Unit

            override fun onAddTrack(
                receiver: RtpReceiver?,
                streams: Array<out MediaStream>?
            ) {
                (receiver?.track() as? VideoTrack)?.let { track ->
                    mainHandler.post { remoteVideoTrack = track }
                }
            }
        }

        val client = WebRTCClient(context, egl, isVideoCall, observer)
        if (!client.createPeerConnection()) {
            client.dispose()
            egl.release()
            finishCall(false, "Не удалось создать соединение", 700)
            return@LaunchedEffect
        }
        eglBase = egl
        webRTCClient = client
        client.setAudioEnabled(micEnabled)
        client.setVideoEnabled(cameraEnabled)

        fun fail(message: String) {
            mainHandler.post {
                if (!ending) finishCall(true, message, 700)
            }
        }

        fun markLocalReadyAndDrain() {
            mainHandler.post {
                localDescriptionSet = true
                val queued = pendingSignals.toList()
                pendingSignals.clear()
                queued.forEach(::processSignal)
            }
        }

        if (isIncoming) {
            val offer = AetherService.lastOffer
            if (offer == null || offer.optString("sdp").isBlank()) {
                finishCall(false, "Вызов уже завершён", 600)
                return@LaunchedEffect
            }
            AetherService.lastOffer = null
            client.setRemoteDescription(
                SessionDescription(
                    SessionDescription.Type.OFFER,
                    offer.optString("sdp")
                ),
                onSet = {
                    client.createAnswer(object : SdpObserver {
                        override fun onCreateSuccess(description: SessionDescription?) {
                            val answer = description ?: return fail("Не удалось ответить")
                            client.setLocalDescription(
                                answer,
                                onSet = {
                                    markLocalReadyAndDrain()
                                    AetherService.sendWebRtcSignal(JSONObject().apply {
                                        put("type", "webrtc_answer")
                                        put("recipient_id", peerId)
                                        put("sdp", answer.description)
                                    })
                                },
                                onError = { fail("Ошибка согласования звонка") }
                            )
                        }

                        override fun onSetSuccess() = Unit
                        override fun onCreateFailure(error: String?) {
                            fail("Не удалось ответить")
                        }
                        override fun onSetFailure(error: String?) = Unit
                    })
                },
                onError = { fail("Не удалось принять звонок") }
            )
        } else {
            client.createOffer(object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription?) {
                    val offer = description ?: return fail("Не удалось создать вызов")
                    client.setLocalDescription(
                        offer,
                        onSet = {
                            markLocalReadyAndDrain()
                            val sent = AetherService.sendWebRtcSignal(JSONObject().apply {
                                put("type", "webrtc_offer")
                                put("recipient_id", peerId)
                                put("sdp", offer.description)
                                put("isVideoCall", isVideoCall)
                            })
                            if (!sent) mainHandler.post {
                                finishCall(false, "Нет соединения", 700)
                            }
                        },
                        onError = { fail("Ошибка согласования звонка") }
                    )
                }

                override fun onSetSuccess() = Unit
                override fun onCreateFailure(error: String?) {
                    fail("Не удалось создать вызов")
                }
                override fun onSetFailure(error: String?) = Unit
            })
        }

        val queued = pendingSignals.toList()
        pendingSignals.clear()
        queued.forEach(::processSignal)
    }

    fun acceptCall() {
        if (accepted || ending) return
        accepted = true
        setupRequested = true
        callStatus = "Соединение..."
        if (!hasPermissions) launcher.launch(neededPerms)
    }

    val overlayAlpha by animateFloatAsState(
        targetValue = if (closingVisual) 0f else 1f,
        animationSpec = tween(190, easing = FastOutSlowInEasing),
        label = "callOverlayAlpha"
    )
    val overlayScale by animateFloatAsState(
        targetValue = if (closingVisual) 0.985f else 1f,
        animationSpec = tween(190, easing = FastOutSlowInEasing),
        label = "callOverlayScale"
    )
    AnimatedContent(
        targetState = minimized,
        transitionSpec = {
            if (targetState) {
                (fadeIn(tween(240, easing = FastOutSlowInEasing)) +
                    scaleIn(tween(280, easing = FastOutSlowInEasing), initialScale = 0.9f))
                    .togetherWith(
                        fadeOut(tween(170)) +
                            scaleOut(tween(220), targetScale = 0.985f)
                    )
            } else {
                (fadeIn(tween(260, easing = FastOutSlowInEasing)) +
                    scaleIn(tween(300, easing = FastOutSlowInEasing), initialScale = 0.985f))
                    .togetherWith(
                        fadeOut(tween(160)) +
                            scaleOut(tween(210), targetScale = 0.9f)
                    )
            }
        },
        modifier = Modifier.fillMaxSize(),
        label = "callMinimize"
    ) { compact ->
    if (compact) {
        // ---- Мини-панель (как в Telegram): тап — развернуть ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .graphicsLayer { alpha = overlayAlpha; scaleX = overlayScale; scaleY = overlayScale },
            contentAlignment = Alignment.TopCenter
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .fillMaxWidth()
                    .aetherIsland(
                        RoundedCornerShape(AetherStyle.PillRadius),
                        fillAlpha = AetherStyle.DockFillAlpha,
                        strokeAlpha = AetherStyle.DockStrokeAlpha
                    )
                    .clickable { onMinimizedChange(false) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Call, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(peerName, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (connected) formatCallTime(callSeconds) else callStatus,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    if (micEnabled) Icons.Filled.Mic else Icons.Filled.MicOff,
                    contentDescription = "Микрофон",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable {
                            micEnabled = !micEnabled
                            webRTCClient?.setAudioEnabled(micEnabled)
                        }
                        .padding(4.dp)
                )
                Spacer(Modifier.width(10.dp))
                Icon(
                    Icons.Filled.CallEnd,
                    contentDescription = "Завершить",
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                        .clickable { endCall(sendSignal = true) }
                        .padding(4.dp)
                )
            }
        }
    } else {

    // ---- Полноэкранный звонок (Telegram-стиль) ----
    val grad = remember(peerId) { avatarGradient(peerId) }
    val hasRemoteVideo = isVideoCall && remoteVideoTrack != null
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .graphicsLayer {
                alpha = overlayAlpha
                scaleX = overlayScale
                scaleY = overlayScale
            }
            .background(Brush.verticalGradient(grad))
    ) {
        // Видео собеседника на весь экран
        val eglContext = eglBase?.eglBaseContext
        val remoteTrack = remoteVideoTrack
        if (hasRemoteVideo && remoteTrack != null && eglContext != null) {
            WebRtcVideoRenderer(
                track = remoteTrack,
                eglContext = eglContext,
                mirror = false,
                mediaOverlay = false,
                modifier = Modifier.fillMaxSize()
            )
            // Затемнение сверху/снизу — для читаемости имени и кнопок
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.40f), Color.Transparent, Color.Black.copy(alpha = 0.55f))
                    )
                )
            )
        }

        // Локальное видео — PiP сверху справа
        if (isVideoCall) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 52.dp, end = 16.dp)
                    .width(104.dp)
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(AetherStyle.MediaRadius))
                    .background(Color.Black)
                    .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(AetherStyle.MediaRadius)),
                contentAlignment = Alignment.Center
            ) {
                val localTrack = webRTCClient?.localVideoTrack
                if (cameraEnabled && localTrack != null && eglContext != null) {
                    WebRtcVideoRenderer(
                        track = localTrack,
                        eglContext = eglContext,
                        mirror = true,
                        mediaOverlay = true,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Filled.VideocamOff, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(26.dp))
                }
            }
        }

        // Верхняя панель: свернуть + индикатор E2E
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { onMinimizedChange(true) },
                modifier = Modifier.size(AetherStyle.SmallControlSize).aetherControl()
            ) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Свернуть", tint = aetherControlContent())
            }
            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(AetherStyle.PillRadius))
                    .background(aetherSurface(AetherStyle.SoftIslandFillAlpha))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(5.dp))
                Text("E2E", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // Центр: аватар + имя + статус (голосовой и до появления видео)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = if (hasRemoteVideo) 90.dp else 0.dp),
            verticalArrangement = if (hasRemoteVideo) Arrangement.Top else Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!hasRemoteVideo) {
                Box(
                    modifier = Modifier
                        .size(128.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.08f))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (peerAvatarFileId != null) {
                        AsyncImage(
                            model = ServerConfig.avatarUrl(peerAvatarFileId),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text(initialOf(peerName), color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(22.dp))
            }
            Text(peerName, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(callStatus, color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp)
        }

        // Нижние кнопки управления
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 52.dp, start = 12.dp, end = 12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom
        ) {
            if (isIncoming && !accepted) {
                CallControl(Icons.Filled.Call, "Принять", AcceptGreen, Color.White) { acceptCall() }
                CallControl(Icons.Filled.CallEnd, "Отклонить", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError) { endCall(sendSignal = true) }
            } else {
                CallControl(
                    if (micEnabled) Icons.Filled.Mic else Icons.Filled.MicOff,
                    if (micEnabled) "Микрофон" else "Микрофон выкл.",
                    if (micEnabled) Color.White.copy(alpha = 0.18f) else Color.White,
                    if (micEnabled) Color.White else Color.Black
                ) {
                    micEnabled = !micEnabled
                    webRTCClient?.setAudioEnabled(micEnabled)
                }

                CallControl(
                    if (speakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    if (speakerOn) "Динамик" else "Динамик выкл.",
                    if (speakerOn) Color.White else Color.White.copy(alpha = 0.18f),
                    if (speakerOn) Color.Black else Color.White
                ) { speakerOn = !speakerOn }

                if (isVideoCall) {
                    CallControl(
                        if (cameraEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                        if (cameraEnabled) "Камера" else "Камера выкл.",
                        if (cameraEnabled) Color.White.copy(alpha = 0.18f) else Color.White,
                        if (cameraEnabled) Color.White else Color.Black
                    ) {
                        cameraEnabled = !cameraEnabled
                        webRTCClient?.setVideoEnabled(cameraEnabled)
                    }

                    CallControl(Icons.Filled.FlipCameraAndroid, "Сменить", Color.White.copy(alpha = 0.18f), Color.White) {
                        webRTCClient?.switchCamera()
                    }
                }

                CallControl(Icons.Filled.CallEnd, "Завершить", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError) { endCall(sendSignal = true) }
            }
        }
    }
    }
    }
}

/** Зелёный кнопки «Принять»: осознанный хардкод — универсальная семантика «ответить на звонок» поверх тёмного медиа-фона, от палитры темы не зависит. */
private val AcceptGreen = Color(0xFF22C55E)

/** Круг кнопки управления звонком: осознанно крупнее ControlSize=50 (кандидат на токен CallControlSize в AetherStyle). */
private val CallControlSize = 64.dp

/** Первая буква имени для аватара-заглушки (как в Telegram). */
private fun initialOf(name: String): String =
    name.trim().firstOrNull()?.uppercase() ?: "?"

/** Стабильный градиент фона звонка по имени собеседника (как у Telegram-аватаров). */
private fun avatarGradient(seed: String): List<Color> {
    val palettes = listOf(
        listOf(Color(0xFF16222A), Color(0xFF3A6073)),
        listOf(Color(0xFF42275A), Color(0xFF734B6D)),
        listOf(Color(0xFF0F2027), Color(0xFF2C5364)),
        listOf(Color(0xFF42344F), Color(0xFF614385)),
        listOf(Color(0xFF232526), Color(0xFF414345)),
        listOf(Color(0xFF1F1C2C), Color(0xFF534F6B)),
        listOf(Color(0xFF1A2980), Color(0xFF26467A))
    )
    val idx = (seed.hashCode().let { if (it < 0) -it else it }) % palettes.size
    return palettes[idx]
}

private fun formatCallTime(totalSeconds: Int): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return String.format("%d:%02d", m, s)
}

@Composable
private fun WebRtcVideoRenderer(
    track: VideoTrack,
    eglContext: EglBase.Context,
    mirror: Boolean,
    mediaOverlay: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val renderer = remember(track, eglContext) {
        SurfaceViewRenderer(context).apply {
            init(eglContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setEnableHardwareScaler(true)
            setMirror(mirror)
            setZOrderMediaOverlay(mediaOverlay)
        }
    }

    DisposableEffect(track, renderer) {
        track.addSink(renderer)
        onDispose {
            try { track.removeSink(renderer) } catch (_: Exception) {}
            try { renderer.release() } catch (_: Exception) {}
        }
    }

    androidx.compose.ui.viewinterop.AndroidView(
        factory = { renderer },
        modifier = modifier
    )
}

@Composable
private fun CallControl(
    icon: ImageVector,
    label: String,
    background: Color,
    tint: Color,
    onClick: () -> Unit
) {
    val animatedBackground by animateColorAsState(
        targetValue = background,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "callControlBackground"
    )
    val animatedTint by animateColorAsState(
        targetValue = tint,
        animationSpec = tween(180),
        label = "callControlTint"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(CallControlSize)
                .clip(CircleShape)
                .background(animatedBackground)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = icon,
                transitionSpec = {
                    (fadeIn(tween(160)) + scaleIn(tween(180), initialScale = 0.72f))
                        .togetherWith(
                            fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.78f)
                        )
                },
                label = "callControlIcon"
            ) { currentIcon ->
                Icon(
                    currentIcon,
                    contentDescription = label,
                    tint = animatedTint,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}
