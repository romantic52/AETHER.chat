package org.groktest.securemessenger.webrtc

import android.content.Context
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

class WebRTCClient(
    context: Context,
    private val eglBase: EglBase,
    private val isVideoCall: Boolean,
    private val observer: PeerConnection.Observer
) {
    companion object {
        @Volatile
        private var initialized = false

        private val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                .createIceServer(),
            PeerConnection.IceServer.builder("stun:144.31.181.10:3478")
                .createIceServer(),
            PeerConnection.IceServer.builder(
                listOf(
                    "turn:144.31.181.10:3478?transport=udp",
                    "turn:144.31.181.10:3478?transport=tcp"
                )
            )
                .setUsername("smturn")
                .setPassword("719b6a0efc869ea2b32c2ecb4ee8d0a7")
                .createIceServer()
        )

        private fun initializeOnce(context: Context) {
            if (initialized) return
            synchronized(this) {
                if (initialized) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
                initialized = true
            }
        }
    }

    private val appContext = context.applicationContext
    private val peerConnectionFactory: PeerConnectionFactory
    var peerConnection: PeerConnection? = null
        private set

    private val audioSource: AudioSource
    private val audioTrack: AudioTrack
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    var localVideoTrack: VideoTrack? = null
        private set
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private val pendingCandidates = ArrayDeque<IceCandidate>()
    @Volatile
    private var remoteDescriptionSet = false
    @Volatile
    private var disposed = false

    init {
        initializeOnce(appContext)
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            )
            .setVideoDecoderFactory(
                DefaultVideoDecoderFactory(eglBase.eglBaseContext)
            )
            .createPeerConnectionFactory()

        audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        audioTrack = peerConnectionFactory.createAudioTrack("AETHER_audio", audioSource)
        if (isVideoCall) initVideo()
    }

    private fun initVideo() {
        try {
            val enumerator = Camera2Enumerator(appContext)
            val device = enumerator.deviceNames.firstOrNull(enumerator::isFrontFacing)
                ?: enumerator.deviceNames.firstOrNull()
                ?: return
            val capturer = enumerator.createCapturer(device, null) ?: return
            val source = peerConnectionFactory.createVideoSource(capturer.isScreencast)
            val helper = SurfaceTextureHelper.create(
                "AetherCaptureThread",
                eglBase.eglBaseContext
            )
            capturer.initialize(helper, appContext, source.capturerObserver)
            capturer.startCapture(960, 540, 24)

            videoCapturer = capturer
            videoSource = source
            surfaceTextureHelper = helper
            localVideoTrack = peerConnectionFactory.createVideoTrack("AETHER_video", source)
        } catch (e: Exception) {
            android.util.Log.e("AetherCall", "Camera init failed", e)
            releaseVideoCapture()
        }
    }

    fun createPeerConnection(): Boolean {
        if (disposed) return false
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy =
                PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            iceCandidatePoolSize = 2
        }
        peerConnection = peerConnectionFactory.createPeerConnection(config, observer)
        val connection = peerConnection ?: return false
        connection.addTrack(audioTrack, listOf("AETHER_stream"))
        if (isVideoCall) {
            localVideoTrack?.let { connection.addTrack(it, listOf("AETHER_stream")) }
        }
        return true
    }

    fun createOffer(sdpObserver: SdpObserver, iceRestart: Boolean = false) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(
                MediaConstraints.KeyValuePair(
                    "OfferToReceiveVideo",
                    isVideoCall.toString()
                )
            )
            // ICE-restart: новый offer с новыми кандидатами для восстановления связи
            if (iceRestart) {
                mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
            }
        }
        peerConnection?.createOffer(sdpObserver, constraints)
    }

    fun createAnswer(sdpObserver: SdpObserver) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(
                MediaConstraints.KeyValuePair(
                    "OfferToReceiveVideo",
                    isVideoCall.toString()
                )
            )
        }
        peerConnection?.createAnswer(sdpObserver, constraints)
    }

    fun setLocalDescription(
        sdp: SessionDescription,
        onSet: () -> Unit,
        onError: (String) -> Unit
    ) {
        peerConnection?.setLocalDescription(object : EmptySdpObserver() {
            override fun onSetSuccess() = onSet()
            override fun onSetFailure(error: String?) = onError(error.orEmpty())
        }, sdp) ?: onError("PeerConnection не создан")
    }

    fun setRemoteDescription(
        sdp: SessionDescription,
        onSet: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        peerConnection?.setRemoteDescription(object : EmptySdpObserver() {
            override fun onSetSuccess() {
                val queued = synchronized(pendingCandidates) {
                    remoteDescriptionSet = true
                    pendingCandidates.toList().also { pendingCandidates.clear() }
                }
                queued.forEach { peerConnection?.addIceCandidate(it) }
                onSet?.invoke()
            }

            override fun onSetFailure(error: String?) {
                onError?.invoke(error.orEmpty())
            }
        }, sdp) ?: onError?.invoke("PeerConnection не создан")
    }

    fun addIceCandidate(candidate: IceCandidate) {
        if (!remoteDescriptionSet) {
            synchronized(pendingCandidates) {
                if (!remoteDescriptionSet) {
                    pendingCandidates.addLast(candidate)
                    return
                }
            }
        }
        peerConnection?.addIceCandidate(candidate)
    }

    fun setAudioEnabled(enabled: Boolean) {
        if (!disposed) audioTrack.setEnabled(enabled)
    }

    fun setVideoEnabled(enabled: Boolean) {
        if (!disposed) localVideoTrack?.setEnabled(enabled)
    }

    fun switchCamera() {
        if (!disposed) (videoCapturer as? CameraVideoCapturer)?.switchCamera(null)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        synchronized(pendingCandidates) { pendingCandidates.clear() }
        try { peerConnection?.close() } catch (_: Exception) {}
        try { peerConnection?.dispose() } catch (_: Exception) {}
        peerConnection = null

        releaseVideoCapture()
        try { audioTrack.dispose() } catch (_: Exception) {}
        try { audioSource.dispose() } catch (_: Exception) {}
        try { peerConnectionFactory.dispose() } catch (_: Exception) {}
    }

    private fun releaseVideoCapture() {
        try { videoCapturer?.stopCapture() } catch (_: Exception) {}
        try { videoCapturer?.dispose() } catch (_: Exception) {}
        videoCapturer = null
        try { localVideoTrack?.dispose() } catch (_: Exception) {}
        localVideoTrack = null
        try { videoSource?.dispose() } catch (_: Exception) {}
        videoSource = null
        try { surfaceTextureHelper?.dispose() } catch (_: Exception) {}
        surfaceTextureHelper = null
    }

    private open class EmptySdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }
}