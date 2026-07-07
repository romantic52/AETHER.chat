package org.groktest.securemessenger.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import org.groktest.securemessenger.AetherService
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
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var hasPermissions by remember { mutableStateOf(false) }
    var callStatus by remember { mutableStateOf(if (isIncoming) "Входящий вызов..." else "Вызов...") }
    var connected by remember { mutableStateOf(false) }
    var callSeconds by remember { mutableStateOf(0) }
    var minimized by remember { mutableStateOf(false) }
    var micEnabled by remember { mutableStateOf(true) }
    var cameraEnabled by remember { mutableStateOf(isVideoCall) }
    var speakerOn by remember { mutableStateOf(isVideoCall) }
    var accepted by remember { mutableStateOf(false) }

    var webRTCClient by remember { mutableStateOf<WebRTCClient?>(null) }
    var eglBase by remember { mutableStateOf<EglBase?>(null) }
    var remoteVideoTrack by remember { mutableStateOf<VideoTrack?>(null) }

    fun sendHangup() {
        try {
            AetherService.sendWebRtcSignal(JSONObject().apply {
                put("type", "webrtc_hangup")
                put("recipient_id", peerId)
            })
        } catch (e: Exception) {}
    }

    fun endCall(sendSignal: Boolean) {
        if (sendSignal) sendHangup()
        onClose()
    }

    // Разрешения: камера нужна только для видеозвонка
    val neededPerms = remember {
        if (isVideoCall) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        else arrayOf(Manifest.permission.RECORD_AUDIO)
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        hasPermissions = neededPerms.all { perms[it] == true }
        if (!hasPermissions) endCall(sendSignal = !isIncoming)
    }

    LaunchedEffect(Unit) {
        val allGranted = neededPerms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) hasPermissions = true else launcher.launch(neededPerms)
    }

    // Таймер разговора
    LaunchedEffect(connected) {
        if (connected) {
            callSeconds = 0
            while (true) {
                delay(1000)
                callSeconds++
                callStatus = "Разговор " + formatCallTime(callSeconds)
            }
        }
    }

    // Таймаут исходящего вызова: собеседник не ответил за 45 c — завершаем
    LaunchedEffect(Unit) {
        if (!isIncoming) {
            var waited = 0
            while (!connected && waited < 45) { delay(1000); waited++ }
            if (!connected) {
                callStatus = "Не отвечает"
                delay(1500)
                if (!connected) endCall(sendSignal = true)
            }
        }
    }

    // Аудиорежим и громкая связь
    DisposableEffect(Unit) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val prevMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        onDispose {
            try {
                am.isSpeakerphoneOn = false
                am.mode = prevMode
            } catch (e: Exception) {}
        }
    }
    LaunchedEffect(speakerOn) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.isSpeakerphoneOn = speakerOn
        } catch (e: Exception) {}
    }

    LaunchedEffect(hasPermissions) {
        if (!hasPermissions) return@LaunchedEffect

        eglBase = EglBase.create()

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED -> {
                        connected = true
                        callStatus = "Разговор " + formatCallTime(callSeconds)
                    }
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED -> {
                        callStatus = "Связь прервана"
                        endCall(sendSignal = false)
                    }
                    else -> {}
                }
            }
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate) {
                AetherService.sendWebRtcSignal(JSONObject().apply {
                    put("type", "webrtc_ice")
                    put("recipient_id", peerId)
                    put("sdpMid", candidate.sdpMid)
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                    put("candidate", candidate.sdp)
                    // legacy-поле для старых клиентов
                    put("sdp", candidate.sdp)
                })
            }
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {
                stream?.videoTracks?.firstOrNull()?.let { remoteVideoTrack = it }
            }
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = receiver?.track()
                if (track is VideoTrack) remoteVideoTrack = track
            }
        }

        webRTCClient = WebRTCClient(context, eglBase!!, isVideoCall, observer)
        webRTCClient?.createPeerConnection()

        AetherService.callListener = { signal ->
            val type = signal.optString("type")
            val sender = signal.optString("sender_id").lowercase()
            if (sender.isNotEmpty() && sender != peerId.lowercase()) {
                // Звонок от третьего пользователя во время разговора — занято
                if (type == "webrtc_offer") {
                    AetherService.sendWebRtcSignal(JSONObject().apply {
                        put("type", "webrtc_busy")
                        put("recipient_id", sender)
                    })
                }
            } else when (type) {
                "webrtc_answer" -> {
                    callStatus = "Соединение..."
                    val sdp = SessionDescription(SessionDescription.Type.ANSWER, signal.optString("sdp"))
                    webRTCClient?.setRemoteDescription(sdp)
                }
                "webrtc_ice" -> {
                    val cand = signal.optString("candidate").ifEmpty { signal.optString("sdp") }
                    webRTCClient?.addIceCandidate(
                        IceCandidate(signal.optString("sdpMid"), signal.optInt("sdpMLineIndex"), cand)
                    )
                }
                "webrtc_hangup" -> {
                    callStatus = "Звонок завершён"
                    endCall(sendSignal = false)
                }
                "webrtc_busy" -> {
                    callStatus = "Занято"
                    endCall(sendSignal = false)
                }
            }
        }

        if (!isIncoming) {
            webRTCClient?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) {
                    webRTCClient?.peerConnection?.setLocalDescription(this, desc)
                    AetherService.sendWebRtcSignal(JSONObject().apply {
                        put("type", "webrtc_offer")
                        put("recipient_id", peerId)
                        put("sdp", desc.description)
                        put("isVideoCall", isVideoCall)
                    })
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(p0: String?) {}
                override fun onSetFailure(p0: String?) {}
            })
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webRTCClient?.dispose()
            AetherService.callListener = null
            AetherService.lastOffer = null
        }
    }

    fun acceptCall() {
        accepted = true
        callStatus = "Соединение..."
        val offer = AetherService.lastOffer
        if (offer != null) {
            val sdp = SessionDescription(SessionDescription.Type.OFFER, offer.optString("sdp"))
            webRTCClient?.setRemoteDescription(sdp) {
                webRTCClient?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        webRTCClient?.peerConnection?.setLocalDescription(this, desc)
                        AetherService.sendWebRtcSignal(JSONObject().apply {
                            put("type", "webrtc_answer")
                            put("recipient_id", peerId)
                            put("sdp", desc.description)
                        })
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                })
            }
            AetherService.lastOffer = null
        }
    }

    if (minimized) {
        // ---- Мини-панель (как в Telegram): тап — развернуть ----
        Box(modifier = Modifier.fillMaxWidth().statusBarsPadding(), contentAlignment = Alignment.TopCenter) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xFF10B981))
                    .clickable { minimized = false }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Call, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(peerId, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (connected) formatCallTime(callSeconds) else callStatus,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    if (micEnabled) Icons.Filled.Mic else Icons.Filled.MicOff,
                    contentDescription = "Микрофон",
                    tint = Color.White,
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
                    tint = Color.White,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEF4444))
                        .clickable { endCall(sendSignal = true) }
                        .padding(4.dp)
                )
            }
        }
        return
    }

    // ---- Полноэкранный звонок (Telegram-стиль) ----
    val grad = remember(peerId) { avatarGradient(peerId) }
    val hasRemoteVideo = isVideoCall && remoteVideoTrack != null
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(grad))
    ) {
        // Видео собеседника на весь экран
        if (hasRemoteVideo) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).apply {
                        init(eglBase?.eglBaseContext, null)
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        setEnableHardwareScaler(true)
                        remoteVideoTrack?.addSink(this)
                    }
                },
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
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black)
                    .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (cameraEnabled && webRTCClient?.localVideoTrack != null) {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { ctx ->
                            SurfaceViewRenderer(ctx).apply {
                                init(eglBase?.eglBaseContext, null)
                                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                                setEnableHardwareScaler(true)
                                setMirror(true)
                                setZOrderMediaOverlay(true)
                                webRTCClient?.localVideoTrack?.addSink(this)
                            }
                        },
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
                onClick = { minimized = true },
                modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.3f))
            ) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Свернуть", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.25f))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = Color(0xFF4ADE80), modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(5.dp))
                Text("E2E", color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
                    Text(initialOf(peerId), color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(22.dp))
            }
            Text(peerId, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(callStatus, color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp)
        }

        // Нижние кнопки управления
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 52.dp, start = 12.dp, end = 12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom
        ) {
            if (isIncoming && !accepted) {
                CallControl(Icons.Filled.Call, "Принять", Color(0xFF22C55E), Color.White) { acceptCall() }
                CallControl(Icons.Filled.CallEnd, "Отклонить", Color(0xFFEF4444), Color.White) { endCall(sendSignal = true) }
            } else {
                CallControl(
                    if (micEnabled) Icons.Filled.Mic else Icons.Filled.MicOff,
                    if (micEnabled) "Звук" else "Без звука",
                    if (micEnabled) Color.White.copy(alpha = 0.18f) else Color.White,
                    if (micEnabled) Color.White else Color.Black
                ) {
                    micEnabled = !micEnabled
                    webRTCClient?.setAudioEnabled(micEnabled)
                }

                CallControl(
                    if (speakerOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                    if (speakerOn) "Динамик" else "Тихо",
                    if (speakerOn) Color.White else Color.White.copy(alpha = 0.18f),
                    if (speakerOn) Color.Black else Color.White
                ) { speakerOn = !speakerOn }

                if (isVideoCall) {
                    CallControl(
                        if (cameraEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                        if (cameraEnabled) "Камера" else "Выкл",
                        if (cameraEnabled) Color.White.copy(alpha = 0.18f) else Color.White,
                        if (cameraEnabled) Color.White else Color.Black
                    ) {
                        cameraEnabled = !cameraEnabled
                        webRTCClient?.setVideoEnabled(cameraEnabled)
                    }

                    CallControl(Icons.Filled.FlipCameraAndroid, "Камера", Color.White.copy(alpha = 0.18f), Color.White) {
                        webRTCClient?.switchCamera()
                    }
                }

                CallControl(Icons.Filled.CallEnd, "Завершить", Color(0xFFEF4444), Color.White) { endCall(sendSignal = true) }
            }
        }
    }
}

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
private fun CallControl(
    icon: ImageVector,
    label: String,
    background: Color,
    tint: Color,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(background)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(28.dp))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}
