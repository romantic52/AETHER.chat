import Foundation
import WebRTC
import AVFoundation

// Обёртка над RTCPeerConnection: аудио/видео 1:1. ICE/TURN — те же, что у web/Android.
// Сигналинг наружу через колбэки (offer/answer/candidate шлёт CallManager по WS ядра).
protocol WebRTCClientDelegate: AnyObject {
    func webRTC(_ client: WebRTCClient, didGenerate candidate: RTCIceCandidate)
    func webRTC(_ client: WebRTCClient, didChange state: RTCIceConnectionState)
    func webRTC(_ client: WebRTCClient, didAddRemoteTrack track: RTCVideoTrack)
}

final class WebRTCClient: NSObject {
    weak var delegate: WebRTCClientDelegate?

    private static let factory: RTCPeerConnectionFactory = {
        RTCInitializeSSL()
        let encoder = RTCDefaultVideoEncoderFactory()
        let decoder = RTCDefaultVideoDecoderFactory()
        return RTCPeerConnectionFactory(encoderFactory: encoder, decoderFactory: decoder)
    }()

    private let peerConnection: RTCPeerConnection
    private var videoCapturer: RTCCameraVideoCapturer?
    private(set) var localVideoTrack: RTCVideoTrack?
    private(set) var remoteVideoTrack: RTCVideoTrack?
    private var localAudioTrack: RTCAudioTrack?
    private var usingFrontCamera = true
    private var localRenderers: [ObjectIdentifier: RTCVideoRenderer] = [:]
    private var remoteRenderers: [ObjectIdentifier: RTCVideoRenderer] = [:]
    private let rendererLock = NSLock()

    private let streamId = "AETHER_stream"

    static let iceServers: [RTCIceServer] = [
        RTCIceServer(urlStrings: ["stun:stun.l.google.com:19302"]),
        RTCIceServer(urlStrings: ["stun:YOUR_SERVER_IP:3478"]),
        RTCIceServer(urlStrings: ["turn:YOUR_SERVER_IP:3478?transport=udp",
                                  "turn:YOUR_SERVER_IP:3478?transport=tcp"],
                     username: "YOUR_TURN_USERNAME", credential: "YOUR_TURN_SECRET"),
    ]

    init(isVideo: Bool) {
        let config = RTCConfiguration()
        config.iceServers = Self.iceServers
        config.sdpSemantics = .unifiedPlan
        config.continualGatheringPolicy = .gatherContinually
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil,
                                              optionalConstraints: ["DtlsSrtpKeyAgreement": "true"])
        guard let pc = Self.factory.peerConnection(with: config, constraints: constraints, delegate: nil) else {
            fatalError("Не удалось создать RTCPeerConnection")
        }
        self.peerConnection = pc
        super.init()
        pc.delegate = self
        configureAudio()
        createMediaSenders(isVideo: isVideo)
    }

    private func configureAudio() {
        let session = RTCAudioSession.sharedInstance()
        session.lockForConfiguration()
        try? session.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetoothHFP, .defaultToSpeaker])
        try? session.setActive(true)
        session.unlockForConfiguration()
    }

    private func createMediaSenders(isVideo: Bool) {
        let audioConstraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        let audioSource = Self.factory.audioSource(with: audioConstraints)
        let audioTrack = Self.factory.audioTrack(with: audioSource, trackId: "AETHER_audio")
        peerConnection.add(audioTrack, streamIds: [streamId])
        localAudioTrack = audioTrack

        if isVideo {
            let videoSource = Self.factory.videoSource()
            videoCapturer = RTCCameraVideoCapturer(delegate: videoSource)
            let videoTrack = Self.factory.videoTrack(with: videoSource, trackId: "AETHER_video")
            peerConnection.add(videoTrack, streamIds: [streamId])
            localVideoTrack = videoTrack
        }
    }

    func startCaptureLocalVideo(front: Bool = true) {
        guard let capturer = videoCapturer,
              let device = RTCCameraVideoCapturer.captureDevices()
                .first(where: { $0.position == (front ? .front : .back) }) else { return }
        usingFrontCamera = front
        let formats = RTCCameraVideoCapturer.supportedFormats(for: device)
        let hdFormats = formats.filter {
            let size = CMVideoFormatDescriptionGetDimensions($0.formatDescription)
            return size.width <= 960 && size.height <= 720
        }
        let preferredFormats = hdFormats.isEmpty ? formats : hdFormats
        guard let format = preferredFormats.max(by: { a, b in
            let da = CMVideoFormatDescriptionGetDimensions(a.formatDescription)
            let db = CMVideoFormatDescriptionGetDimensions(b.formatDescription)
            return da.width * da.height < db.width * db.height
        }) ?? formats.first else { return }
        let fps = (format.videoSupportedFrameRateRanges.first?.maxFrameRate) ?? 30
        capturer.startCapture(with: device, format: format, fps: Int(min(fps, 24)))
    }

    func switchCamera() {
        guard let capturer = videoCapturer else { return }
        let front = !usingFrontCamera
        capturer.stopCapture { [weak self] in
            self?.startCaptureLocalVideo(front: front)
        }
    }

    // MARK: - SDP

    func offer(completion: @escaping (Result<RTCSessionDescription, Error>) -> Void) {
        let c = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        peerConnection.offer(for: c) { [weak self] sdp, error in
            guard let self else { return }
            guard let sdp else {
                completion(.failure(error ?? Self.error("WebRTC не создал offer")))
                return
            }
            self.peerConnection.setLocalDescription(sdp) { error in
                if let error { completion(.failure(error)) }
                else { completion(.success(sdp)) }
            }
        }
    }

    func answer(completion: @escaping (Result<RTCSessionDescription, Error>) -> Void) {
        let c = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        peerConnection.answer(for: c) { [weak self] sdp, error in
            guard let self else { return }
            guard let sdp else {
                completion(.failure(error ?? Self.error("WebRTC не создал answer")))
                return
            }
            self.peerConnection.setLocalDescription(sdp) { error in
                if let error { completion(.failure(error)) }
                else { completion(.success(sdp)) }
            }
        }
    }

    func set(remoteSdp: RTCSessionDescription, completion: @escaping (Error?) -> Void) {
        peerConnection.setRemoteDescription(remoteSdp, completionHandler: completion)
    }

    func add(remoteCandidate: RTCIceCandidate) {
        peerConnection.add(remoteCandidate) { _ in }
    }

    // MARK: - Управление

    func setAudio(enabled: Bool) { localAudioTrack?.isEnabled = enabled }
    func setVideo(enabled: Bool) { localVideoTrack?.isEnabled = enabled }

    func setSpeaker(_ on: Bool) {
        let session = RTCAudioSession.sharedInstance()
        session.lockForConfiguration()
        try? session.overrideOutputAudioPort(on ? .speaker : .none)
        session.unlockForConfiguration()
    }

    func renderLocal(to view: RTCVideoRenderer) {
        let id = ObjectIdentifier(view)
        rendererLock.lock()
        let isNew = localRenderers[id] == nil
        localRenderers[id] = view
        let track = localVideoTrack
        rendererLock.unlock()
        if isNew { track?.add(view) }
    }

    func renderRemote(to view: RTCVideoRenderer) {
        let id = ObjectIdentifier(view)
        rendererLock.lock()
        let isNew = remoteRenderers[id] == nil
        remoteRenderers[id] = view
        let track = remoteVideoTrack
        rendererLock.unlock()
        if isNew { track?.add(view) }
    }

    func removeLocalRenderer(_ view: RTCVideoRenderer) {
        rendererLock.lock()
        localRenderers.removeValue(forKey: ObjectIdentifier(view))
        let track = localVideoTrack
        rendererLock.unlock()
        track?.remove(view)
    }

    func removeRemoteRenderer(_ view: RTCVideoRenderer) {
        rendererLock.lock()
        remoteRenderers.removeValue(forKey: ObjectIdentifier(view))
        let track = remoteVideoTrack
        rendererLock.unlock()
        track?.remove(view)
    }

    func close() {
        videoCapturer?.stopCapture()
        rendererLock.lock()
        let local = Array(localRenderers.values)
        let remote = Array(remoteRenderers.values)
        localRenderers.removeAll()
        remoteRenderers.removeAll()
        rendererLock.unlock()
        for renderer in local { localVideoTrack?.remove(renderer) }
        for renderer in remote { remoteVideoTrack?.remove(renderer) }
        peerConnection.close()
        let session = RTCAudioSession.sharedInstance()
        session.lockForConfiguration()
        try? session.setActive(false)
        session.unlockForConfiguration()
    }

    private static func error(_ message: String) -> Error {
        NSError(domain: "io.aether.webrtc", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
    }
}

extension WebRTCClient: RTCPeerConnectionDelegate {
    func peerConnection(_ pc: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        delegate?.webRTC(self, didGenerate: candidate)
    }
    func peerConnection(_ pc: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        delegate?.webRTC(self, didChange: newState)
    }
    func peerConnection(_ pc: RTCPeerConnection, didAdd rtpReceiver: RTCRtpReceiver, streams: [RTCMediaStream]) {
        if let track = rtpReceiver.track as? RTCVideoTrack {
            let oldTrack = remoteVideoTrack
            remoteVideoTrack = track
            rendererLock.lock()
            let renderers = Array(remoteRenderers.values)
            rendererLock.unlock()
            for renderer in renderers {
                oldTrack?.remove(renderer)
                track.add(renderer)
            }
            delegate?.webRTC(self, didAddRemoteTrack: track)
        }
    }
    func peerConnection(_ pc: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    func peerConnection(_ pc: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    func peerConnection(_ pc: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    func peerConnectionShouldNegotiate(_ pc: RTCPeerConnection) {}
    func peerConnection(_ pc: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}
    func peerConnection(_ pc: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    func peerConnection(_ pc: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
}
