import Foundation
import WebRTC
import AVFoundation
import AudioToolbox
import Combine
#if canImport(ActivityKit)
import ActivityKit
#endif

// Конечный автомат звонка 1:1. WebRTC отвечает только за медиа, а плоские
// offer/answer/ICE-сообщения проходят через общий защищённый WS-транспорт Aether.
@MainActor
final class CallManager: NSObject, ObservableObject {
    enum State: Equatable { case idle, preparing, dialing, incoming, connecting, active, ended }

    struct Record: Codable, Identifiable, Equatable {
        enum Direction: String, Codable { case incoming, outgoing }
        enum Result: String, Codable { case completed, missed, declined, cancelled, busy, failed }

        let id: UUID
        let peerId: String
        let isVideo: Bool
        let direction: Direction
        let startedAt: Date
        let duration: TimeInterval
        let result: Result
    }

    @Published var state: State = .idle {
        didSet { if state != oldValue { syncLiveActivity(from: oldValue) } }
    }
    @Published var peerId = ""
    @Published var isVideo = false
    @Published var micOn = true
    @Published var cameraOn = true
    @Published var speakerOn = true
    @Published var duration: TimeInterval = 0
    @Published private(set) var remoteTrackReady = false
    @Published private(set) var history: [Record] = []
    @Published private(set) var endMessage: String?
    /// Чем закончился последний звонок — фон экрана красит по нему финальный кадр.
    @Published private(set) var lastResult: Record.Result?

    /// Маски и жесты для исходящего видео; живёт дольше одного звонка,
    /// чтобы выбранная маска не сбрасывалась между вызовами.
    let effects = VideoEffects()

    var client: WebRTCClient?
    private var pendingRemoteCandidates: [RTCIceCandidate] = []
    private var remoteReady = false
    private var pendingOfferSdp: String?
    private var callStartedAt: Date?
    private var callCreatedAt: Date?
    private var direction: Record.Direction = .outgoing
    private var timer: Timer?
    private var timeoutTask: Task<Void, Never>?
    private var disconnectTask: Task<Void, Never>?
    private var permissionTask: Task<Void, Never>?
    private var ringtoneTimer: Timer?
    private var didWriteHistory = false

    /// Возвращает true, если сигнал принят WS-транспортом.
    var sendSignal: ((_ type: String, _ recipient: String, _ extra: [String: Any]) -> Bool)?
    var signalingAvailable: (() -> Bool)?

    var isBusy: Bool { state != .idle && state != .ended }

    override init() {
        super.init()
        loadHistory()
    }

    // MARK: - Исходящий

    func startCall(peer: String, video: Bool) {
        guard !isBusy else { return }
        prepareNewCall(peer: peer, video: video, direction: .outgoing)
        state = .preparing

        guard signalingAvailable?() ?? false else {
            finish(result: .failed, message: "Нет соединения с сервером звонков", notifyPeer: false)
            return
        }
        permissionTask = Task { [weak self] in
            guard let self else { return }
            let allowed = await Self.requestMediaAccess(video: video)
            guard !Task.isCancelled, self.state == .preparing else { return }
            guard allowed else {
                self.finish(result: .failed,
                            message: video ? "Нужен доступ к микрофону и камере" : "Нужен доступ к микрофону",
                            notifyPeer: false)
                return
            }
            self.beginOutgoing()
        }
    }

    private func beginOutgoing() {
        state = .dialing
        let c = WebRTCClient(isVideo: isVideo, effects: effects)
        c.delegate = self
        client = c
        c.setSpeaker(speakerOn)
        c.setAudio(enabled: micOn)
        if isVideo { c.startCaptureLocalVideo() }
        scheduleTimeout()

        c.offer { [weak self] result in
            Task { @MainActor in
                guard let self, self.state == .dialing else { return }
                switch result {
                case .success(let sdp):
                    if !self.send("webrtc_offer", extra: ["sdp": sdp.sdp, "isVideoCall": self.isVideo]) {
                        self.finish(result: .failed, message: "Не удалось отправить вызов", notifyPeer: false)
                    }
                case .failure:
                    self.finish(result: .failed, message: "Не удалось создать соединение", notifyPeer: false)
                }
            }
        }
    }

    // MARK: - Входящий сигналинг

    func handleSignal(type: String, sender: String, payload: [String: Any]) {
        let from = sender.lowercased()
        guard !from.isEmpty else { return }

        switch type {
        case "webrtc_offer":
            guard let sdp = payload["sdp"] as? String, !sdp.isEmpty else { return }
            if isBusy {
                // Повторный offer того же вызова тоже не должен разрушать текущий peer connection.
                _ = sendRaw("webrtc_busy", recipient: from)
                return
            }
            prepareNewCall(peer: from,
                           video: Self.bool(payload["isVideoCall"]),
                           direction: .incoming)
            pendingOfferSdp = sdp
            state = .incoming
            scheduleTimeout()
            startIncomingAlert()

        case "webrtc_answer":
            guard direction == .outgoing,
                  state == .dialing || state == .connecting,
                  let sdp = payload["sdp"] as? String,
                  let c = client else { return }
            state = .connecting
            c.set(remoteSdp: RTCSessionDescription(type: .answer, sdp: sdp)) { [weak self] error in
                Task { @MainActor in
                    guard let self else { return }
                    if error == nil {
                        self.remoteReady = true
                        self.drainCandidates()
                    } else {
                        self.finish(result: .failed, message: "Ошибка согласования звонка", notifyPeer: true)
                    }
                }
            }

        case "webrtc_ice":
            guard from == peerId,
                  let candidate = payload["candidate"] as? String,
                  !candidate.isEmpty else { return }
            let ice = RTCIceCandidate(sdp: candidate,
                                      sdpMLineIndex: Self.int32(payload["sdpMLineIndex"]),
                                      sdpMid: payload["sdpMid"] as? String)
            if remoteReady { client?.add(remoteCandidate: ice) }
            else { pendingRemoteCandidates.append(ice) }

        case "webrtc_hangup":
            guard from == peerId else { return }
            let result: Record.Result = state == .active ? .completed : (direction == .incoming ? .missed : .cancelled)
            finish(result: result, message: "Звонок завершён", notifyPeer: false)

        case "webrtc_busy":
            guard from == peerId, direction == .outgoing else { return }
            finish(result: .busy, message: "Абонент занят", notifyPeer: false)

        default:
            break
        }
    }

    func accept() {
        guard state == .incoming, pendingOfferSdp != nil else { return }
        stopIncomingAlert()
        state = .preparing
        permissionTask = Task { [weak self] in
            guard let self else { return }
            let allowed = await Self.requestMediaAccess(video: self.isVideo)
            guard !Task.isCancelled, self.state == .preparing else { return }
            guard allowed else {
                self.finish(result: .declined,
                            message: self.isVideo ? "Нужен доступ к микрофону и камере" : "Нужен доступ к микрофону",
                            notifyPeer: true)
                return
            }
            self.beginAccepting()
        }
    }

    private func beginAccepting() {
        guard let offerSdp = pendingOfferSdp else { return }
        state = .connecting
        let c = WebRTCClient(isVideo: isVideo, effects: effects)
        c.delegate = self
        client = c
        c.setSpeaker(speakerOn)
        c.setAudio(enabled: micOn)
        if isVideo { c.startCaptureLocalVideo() }

        c.set(remoteSdp: RTCSessionDescription(type: .offer, sdp: offerSdp)) { [weak self] error in
            Task { @MainActor in
                guard let self else { return }
                guard error == nil else {
                    self.finish(result: .failed, message: "Не удалось принять звонок", notifyPeer: true)
                    return
                }
                self.remoteReady = true
                self.drainCandidates()
                c.answer { result in
                    Task { @MainActor in
                        switch result {
                        case .success(let sdp):
                            if !self.send("webrtc_answer", extra: ["sdp": sdp.sdp]) {
                                self.finish(result: .failed, message: "Не удалось ответить на звонок", notifyPeer: false)
                            }
                        case .failure:
                            self.finish(result: .failed, message: "Не удалось ответить на звонок", notifyPeer: true)
                        }
                    }
                }
            }
        }
    }

    func decline() { finish(result: .declined, message: "Вызов отклонён", notifyPeer: true) }
    func hangup() {
        let result: Record.Result = state == .active ? .completed : .cancelled
        finish(result: result, message: "Звонок завершён", notifyPeer: true)
    }

    // MARK: - Управление в звонке

    func toggleMic() { micOn.toggle(); client?.setAudio(enabled: micOn) }
    func toggleCamera() { cameraOn.toggle(); client?.setVideo(enabled: cameraOn) }
    func toggleSpeaker() { speakerOn.toggle(); client?.setSpeaker(speakerOn) }
    func switchCamera() { guard isVideo && cameraOn else { return }; client?.switchCamera() }

    // MARK: - Внутреннее

    private func prepareNewCall(peer: String, video: Bool, direction: Record.Direction) {
        timeoutTask?.cancel()
        disconnectTask?.cancel()
        permissionTask?.cancel()
        peerId = peer.lowercased()
        isVideo = video
        cameraOn = video
        micOn = true
        speakerOn = true
        duration = 0
        remoteTrackReady = false
        remoteReady = false
        pendingRemoteCandidates.removeAll()
        pendingOfferSdp = nil
        endMessage = nil
        lastResult = nil
        effects.resetTransient()
        callCreatedAt = Date()
        callStartedAt = nil
        self.direction = direction
        didWriteHistory = false
    }

    @discardableResult
    private func send(_ type: String, extra: [String: Any]) -> Bool {
        var fields = extra
        fields["sig_id"] = sigId()
        return sendSignal?(type, peerId, fields) ?? false
    }

    @discardableResult
    private func sendRaw(_ type: String, recipient: String) -> Bool {
        sendSignal?(type, recipient, ["sig_id": sigId()]) ?? false
    }

    private func sigId() -> String {
        "sig_\(Int(Date().timeIntervalSince1970 * 1000))_\(Int.random(in: 1000...9999))"
    }

    private func drainCandidates() {
        for candidate in pendingRemoteCandidates { client?.add(remoteCandidate: candidate) }
        pendingRemoteCandidates.removeAll()
    }

    private func finish(result: Record.Result, message: String, notifyPeer: Bool) {
        guard state != .idle && state != .ended else { return }
        if notifyPeer, !peerId.isEmpty { _ = sendRaw("webrtc_hangup", recipient: peerId) }
        timeoutTask?.cancel(); timeoutTask = nil
        disconnectTask?.cancel(); disconnectTask = nil
        permissionTask?.cancel(); permissionTask = nil
        stopIncomingAlert()
        timer?.invalidate(); timer = nil
        client?.close(); client = nil
        pendingRemoteCandidates.removeAll()
        remoteReady = false
        pendingOfferSdp = nil
        writeHistory(result: result)
        endMessage = message
        lastResult = result
        state = .ended

        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 1_100_000_000)
            guard let self, self.state == .ended else { return }
            self.reset()
        }
    }

    // MARK: - Live Activity (Dynamic Island)

    #if canImport(ActivityKit)
    private var liveActivity: Activity<CallActivityAttributes>?
    private var activeSince: Date?
    #endif

    /// Держит островок в согласии со стейтом звонка: появляется на наборе/входящем,
    /// при active начинает тикать таймер (островок сам, без обновлений из приложения),
    /// исчезает при idle. Ошибки ActivityKit молча игнорируем — звонок важнее островка.
    private func syncLiveActivity(from old: State) {
        #if canImport(ActivityKit)
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        switch state {
        case .dialing, .incoming, .connecting, .active:
            if state == .active, old != .active { activeSince = Date() }
            let phase: String = {
                switch state {
                case .dialing: return "Вызов…"
                case .incoming: return "Входящий…"
                case .connecting: return "Соединение…"
                default: return ""
                }
            }()
            let content = ActivityContent(
                state: CallActivityAttributes.ContentState(
                    phase: phase,
                    startedAt: activeSince ?? Date(),
                    active: state == .active
                ),
                staleDate: nil
            )
            if let activity = liveActivity {
                Task { await activity.update(content) }
            } else {
                let attrs = CallActivityAttributes(peerName: peerId, isVideo: isVideo)
                liveActivity = try? Activity.request(attributes: attrs, content: content)
            }
        case .idle, .ended:
            if state == .idle || state == .ended, let activity = liveActivity {
                liveActivity = nil
                activeSince = nil
                Task { await activity.end(nil, dismissalPolicy: .immediate) }
            }
        case .preparing:
            break
        }
        #endif
    }

    private func reset() {
        state = .idle
        peerId = ""
        isVideo = false
        micOn = true
        cameraOn = true
        speakerOn = true
        duration = 0
        remoteTrackReady = false
        callStartedAt = nil
        callCreatedAt = nil
        endMessage = nil
        effects.resetTransient()
    }

    private func scheduleTimeout() {
        timeoutTask?.cancel()
        timeoutTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 45 * 1_000_000_000)
            guard !Task.isCancelled, let self, self.state != .active else { return }
            let result: Record.Result = self.direction == .incoming ? .missed : .cancelled
            self.finish(result: result, message: "Нет ответа", notifyPeer: true)
        }
    }

    private func startIncomingAlert() {
        stopIncomingAlert()
        playIncomingAlert()
        ringtoneTimer = Timer.scheduledTimer(withTimeInterval: 2.2, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.playIncomingAlert() }
        }
    }

    private func playIncomingAlert() {
        AudioServicesPlaySystemSound(1005)
        AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
    }

    private func stopIncomingAlert() {
        ringtoneTimer?.invalidate()
        ringtoneTimer = nil
    }

    private func scheduleDisconnectGrace(for client: WebRTCClient) {
        disconnectTask?.cancel()
        disconnectTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 8 * 1_000_000_000)
            guard !Task.isCancelled, let self, self.client === client, self.state == .active else { return }
            self.finish(result: .failed, message: "Связь потеряна", notifyPeer: true)
        }
    }

    private func startTimer() {
        timeoutTask?.cancel(); timeoutTask = nil
        callStartedAt = Date()
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, let started = self.callStartedAt else { return }
                self.duration = Date().timeIntervalSince(started)
            }
        }
    }

    private func writeHistory(result: Record.Result) {
        guard !didWriteHistory, !peerId.isEmpty else { return }
        didWriteHistory = true
        let record = Record(id: UUID(), peerId: peerId, isVideo: isVideo, direction: direction,
                            startedAt: callCreatedAt ?? Date(), duration: duration, result: result)
        history.insert(record, at: 0)
        if history.count > 100 { history.removeLast(history.count - 100) }
        if let data = try? JSONEncoder().encode(history) {
            UserDefaults.standard.set(data, forKey: "aether.callHistory.v1")
        }
    }

    private func loadHistory() {
        guard let data = UserDefaults.standard.data(forKey: "aether.callHistory.v1"),
              let records = try? JSONDecoder().decode([Record].self, from: data) else { return }
        history = records
    }

    private static func requestMediaAccess(video: Bool) async -> Bool {
        let mic = await requestAccess(for: .audio)
        guard mic else { return false }
        if !video { return true }
        return await requestAccess(for: .video)
    }

    private static func requestAccess(for mediaType: AVMediaType) async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: mediaType) {
        case .authorized: return true
        case .denied, .restricted: return false
        case .notDetermined:
            return await withCheckedContinuation { continuation in
                AVCaptureDevice.requestAccess(for: mediaType) { continuation.resume(returning: $0) }
            }
        @unknown default: return false
        }
    }

    private static func bool(_ value: Any?) -> Bool {
        (value as? Bool) ?? (value as? NSNumber)?.boolValue ?? false
    }

    private static func int32(_ value: Any?) -> Int32 {
        if let number = value as? NSNumber { return number.int32Value }
        if let int = value as? Int { return Int32(clamping: int) }
        return 0
    }
}

extension CallManager: WebRTCClientDelegate {
    nonisolated func webRTC(_ client: WebRTCClient, didGenerate candidate: RTCIceCandidate) {
        Task { @MainActor in
            guard self.client === client else { return }
            _ = self.send("webrtc_ice", extra: [
                "candidate": candidate.sdp,
                "sdpMid": candidate.sdpMid ?? "",
                "sdpMLineIndex": candidate.sdpMLineIndex,
            ])
        }
    }

    nonisolated func webRTC(_ client: WebRTCClient, didChange state: RTCIceConnectionState) {
        Task { @MainActor in
            guard self.client === client else { return }
            switch state {
            case .connected, .completed:
                self.disconnectTask?.cancel()
                self.disconnectTask = nil
                if self.state != .active {
                    self.state = .active
                    self.startTimer()
                }
            case .failed:
                self.finish(result: .failed, message: "Соединение прервано", notifyPeer: true)
            case .disconnected:
                self.scheduleDisconnectGrace(for: client)
            case .closed:
                if self.isBusy { self.finish(result: .completed, message: "Звонок завершён", notifyPeer: false) }
            default: break
            }
        }
    }

    nonisolated func webRTC(_ client: WebRTCClient, didAddRemoteTrack track: RTCVideoTrack) {
        Task { @MainActor in
            guard self.client === client else { return }
            self.remoteTrackReady = true
        }
    }
}
