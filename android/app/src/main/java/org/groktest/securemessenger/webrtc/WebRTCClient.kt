package org.groktest.securemessenger.webrtc

import android.content.Context
import org.webrtc.*

class WebRTCClient(
    private val context: Context,
    private val eglBase: EglBase,
    private val isVideoCall: Boolean,
    private val observer: PeerConnection.Observer
) {

    private val peerConnectionFactory: PeerConnectionFactory
    var peerConnection: PeerConnection? = null
    
    private val audioSource: AudioSource
    private val audioTrack: AudioTrack
    
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    var localVideoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val defaultVideoDecoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(defaultVideoEncoderFactory)
            .setVideoDecoderFactory(defaultVideoDecoderFactory)
            .createPeerConnectionFactory()

        audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        audioTrack = peerConnectionFactory.createAudioTrack("101", audioSource)
        
        if (isVideoCall) {
            initVideo()
        }
    }
    
    private fun initVideo() {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames
        val frontCamera = deviceNames.firstOrNull { enumerator.isFrontFacing(it) } ?: deviceNames.firstOrNull()
        
        if (frontCamera != null) {
            videoCapturer = enumerator.createCapturer(frontCamera, null)
            videoSource = peerConnectionFactory.createVideoSource(videoCapturer!!.isScreencast)
            
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
            videoCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
            videoCapturer?.startCapture(1280, 720, 30)

            localVideoTrack = peerConnectionFactory.createVideoTrack("102", videoSource)
        }
    }

    fun createPeerConnection() {
        // STUN + собственный TURN: без TURN звонки не проходят через
        // мобильные сети и симметричный NAT.
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:YOUR_SERVER_IP:3478").createIceServer(),
            PeerConnection.IceServer.builder(
                listOf(
                    "turn:YOUR_SERVER_IP:3478?transport=udp",
                    "turn:YOUR_SERVER_IP:3478?transport=tcp"
                )
            )
                .setUsername("YOUR_TURN_USERNAME")
                .setPassword("YOUR_TURN_SECRET")
                .createIceServer()
        )

        peerConnection = peerConnectionFactory.createPeerConnection(iceServers, observer)
        
        // Add local tracks
        peerConnection?.addTrack(audioTrack, listOf("stream1"))
        if (isVideoCall && localVideoTrack != null) {
            peerConnection?.addTrack(localVideoTrack, listOf("stream1"))
        }
    }

    fun createOffer(sdpObserver: SdpObserver) {
        val constraints = MediaConstraints()
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideoCall) "true" else "false"))
        peerConnection?.createOffer(sdpObserver, constraints)
    }

    fun createAnswer(sdpObserver: SdpObserver) {
        val constraints = MediaConstraints()
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideoCall) "true" else "false"))
        peerConnection?.createAnswer(sdpObserver, constraints)
    }

    // Кандидаты, пришедшие до setRemoteDescription, нельзя добавлять сразу —
    // WebRTC их молча отбрасывает. Складываем в очередь и добавляем после.
    private val pendingCandidates = mutableListOf<IceCandidate>()
    @Volatile private var remoteDescSet = false

    fun setRemoteDescription(sdp: SessionDescription, onSet: (() -> Unit)? = null) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                remoteDescSet = true
                synchronized(pendingCandidates) {
                    pendingCandidates.forEach { peerConnection?.addIceCandidate(it) }
                    pendingCandidates.clear()
                }
                onSet?.invoke()
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, sdp)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        if (!remoteDescSet) {
            synchronized(pendingCandidates) {
                if (!remoteDescSet) { pendingCandidates.add(candidate); return }
            }
        }
        peerConnection?.addIceCandidate(candidate)
    }
    
    fun setAudioEnabled(enabled: Boolean) {
        audioTrack.setEnabled(enabled)
    }
    
    fun setVideoEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    /** Переключение фронтальной/тыловой камеры (как в Telegram). */
    fun switchCamera() {
        (videoCapturer as? CameraVideoCapturer)?.switchCamera(null)
    }

    fun dispose() {
        peerConnection?.close()
        peerConnection = null
        
        audioSource.dispose()
        
        try {
            videoCapturer?.stopCapture()
        } catch (e: Exception) {}
        videoCapturer?.dispose()
        videoSource?.dispose()
        surfaceTextureHelper?.dispose()
        
        peerConnectionFactory.dispose()
    }
}
