import Foundation
import WebRTC
import UIKit

// Групповые звонки (аудио, mesh): каждый участник держит p2p-соединение с каждым.
// Сервер — только релей сигналинга (group_call_* broadcast по группе, SDP/ICE —
// направленные webrtc_* с пометкой group_call). Медиа через сервер не идёт.
//
// ponytail: mesh комфортен до ~5 участников; больше — нужен SFU (отдельная задача).
@MainActor
final class GroupCallManager: NSObject, ObservableObject {
    enum State: Equatable { case idle, active }

    struct Invite: Equatable, Identifiable {
        let groupId: String
        let callId: String
        let from: String
        var id: String { callId }
    }

    @Published private(set) var state: State = .idle
    @Published private(set) var groupId = ""
    @Published private(set) var callId = ""
    /// Подключённые собеседники (без себя): peerId → ICE-состояние «связь есть».
    @Published private(set) var peers: [String: Bool] = [:]
    @Published var micOn = true { didSet { clients.values.forEach { $0.setAudio(enabled: micOn) } } }
    @Published var speakerOn = true { didSet { clients.values.first?.setSpeaker(speakerOn) } }
    @Published private(set) var duration: TimeInterval = 0
    /// Входящее приглашение в групповой звонок (баннер «Присоединиться»).
    @Published var pendingInvite: Invite?

    var sendSignal: ((_ type: String, _ recipient: String, _ extra: [String: Any]) -> Bool)?
    /// Занятость 1:1-звонком — в это время приглашения не показываем.
    var isBusyElsewhere: (() -> Bool)?
    var myId: (() -> String)?

    private var clients: [String: WebRTCClient] = [:]
    private var boxes: [String: PeerBox] = [:]
    private var pendingIce: [String: [RTCIceCandidate]] = [:]
    private var remoteReady: Set<String> = []
    private var timer: Timer?
    private var startedAt: Date?

    var isActive: Bool { state == .active }

    // MARK: - Старт / вход / выход

    /// Начать звонок в группе: комната открывается, остальные получают приглашение.
    func start(groupId: String) {
        guard state == .idle, !(isBusyElsewhere?() ?? false) else { return }
        Task { [weak self] in
            guard let self else { return }
            guard await Self.requestMicAccess() else { return }
            self.groupId = groupId.lowercased()
            self.callId = UUID().uuidString
            self.enterRoom()
            _ = self.broadcast("group_call_start")
        }
    }

    /// Присоединиться по приглашению.
    func join(_ invite: Invite) {
        guard state == .idle else { return }
        pendingInvite = nil
        Task { [weak self] in
            guard let self else { return }
            guard await Self.requestMicAccess() else { return }
            self.groupId = invite.groupId
            self.callId = invite.callId
            self.enterRoom()
            // Существующие участники, услышав join, сами пришлют offer.
            _ = self.broadcast("group_call_join")
        }
    }

    func leave() {
        guard state == .active else { return }
        _ = broadcast("group_call_leave")
        teardown()
    }

    private func enterRoom() {
        state = .active
        startedAt = Date()
        duration = 0
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, let start = self.startedAt else { return }
                self.duration = Date().timeIntervalSince(start)
            }
        }
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
    }

    private func teardown() {
        for (_, client) in clients { client.close() }
        clients = [:]; boxes = [:]; pendingIce = [:]; remoteReady = []
        peers = [:]
        timer?.invalidate(); timer = nil
        startedAt = nil
        groupId = ""; callId = ""
        micOn = true; speakerOn = true
        state = .idle
    }

    // MARK: - Сигналинг

    func handleSignal(type: String, sender: String, payload: [String: Any]) {
        let from = sender.lowercased()
        let gid = ((payload["group_id"] as? String) ?? "").lowercased()
        let cid = (payload["call_id"] as? String) ?? ""
        guard !from.isEmpty, from != (myId?() ?? "").lowercased() else { return }

        switch type {
        case "group_call_start":
            guard state == .idle, !(isBusyElsewhere?() ?? false), !gid.isEmpty, !cid.isEmpty else { return }
            pendingInvite = Invite(groupId: gid, callId: cid, from: from)

        case "group_call_join":
            // Кто-то вошёл в НАШ звонок: создаём соединение и предлагаем offer.
            guard state == .active, gid == groupId, cid == callId else { return }
            offerTo(peer: from)

        case "group_call_leave":
            if state == .active, gid == groupId {
                dropPeer(from)
            }
            if pendingInvite?.groupId == gid, pendingInvite?.from == from {
                pendingInvite = nil   // инициатор ушёл до нашего входа
            }

        case "webrtc_offer":
            guard state == .active, cid == callId,
                  let sdp = payload["sdp"] as? String else { return }
            answerTo(peer: from, offerSdp: sdp)

        case "webrtc_answer":
            guard state == .active, cid == callId,
                  let sdp = payload["sdp"] as? String,
                  let client = clients[from] else { return }
            client.set(remoteSdp: RTCSessionDescription(type: .answer, sdp: sdp)) { [weak self] error in
                Task { @MainActor in
                    guard let self, error == nil else { return }
                    self.remoteReady.insert(from)
                    self.drainIce(for: from)
                }
            }

        case "webrtc_ice":
            guard state == .active, cid == callId,
                  let candidate = payload["candidate"] as? String, !candidate.isEmpty else { return }
            let ice = RTCIceCandidate(sdp: candidate,
                                      sdpMLineIndex: Int32((payload["sdpMLineIndex"] as? NSNumber)?.int32Value ?? 0),
                                      sdpMid: payload["sdpMid"] as? String)
            if remoteReady.contains(from) { clients[from]?.add(remoteCandidate: ice) }
            else { pendingIce[from, default: []].append(ice) }

        default: break
        }
    }

    // MARK: - Mesh-соединения

    private func makeClient(for peer: String) -> WebRTCClient {
        let client = WebRTCClient(isVideo: false)
        let box = PeerBox(peer: peer, manager: self)
        client.delegate = box
        boxes[peer] = box
        clients[peer] = client
        client.setSpeaker(speakerOn)
        client.setAudio(enabled: micOn)
        peers[peer] = false
        return client
    }

    private func offerTo(peer: String) {
        guard clients[peer] == nil else { return }
        let client = makeClient(for: peer)
        client.offer { [weak self] result in
            Task { @MainActor in
                guard let self, case .success(let sdp) = result else { return }
                _ = self.directed("webrtc_offer", to: peer, extra: ["sdp": sdp.sdp])
            }
        }
    }

    private func answerTo(peer: String, offerSdp: String) {
        let client = clients[peer] ?? makeClient(for: peer)
        client.set(remoteSdp: RTCSessionDescription(type: .offer, sdp: offerSdp)) { [weak self] error in
            Task { @MainActor in
                guard let self, error == nil else { return }
                self.remoteReady.insert(peer)
                self.drainIce(for: peer)
                client.answer { result in
                    Task { @MainActor in
                        guard case .success(let sdp) = result else { return }
                        _ = self.directed("webrtc_answer", to: peer, extra: ["sdp": sdp.sdp])
                    }
                }
            }
        }
    }

    private func dropPeer(_ peer: String) {
        clients[peer]?.close()
        clients[peer] = nil
        boxes[peer] = nil
        peers[peer] = nil
        pendingIce[peer] = nil
        remoteReady.remove(peer)
    }

    private func drainIce(for peer: String) {
        guard let client = clients[peer] else { return }
        for ice in pendingIce[peer] ?? [] { client.add(remoteCandidate: ice) }
        pendingIce[peer] = nil
    }

    fileprivate func peer(_ peer: String, generated candidate: RTCIceCandidate) {
        _ = directed("webrtc_ice", to: peer, extra: [
            "candidate": candidate.sdp,
            "sdpMLineIndex": Int(candidate.sdpMLineIndex),
            "sdpMid": candidate.sdpMid ?? "0",
        ])
    }

    fileprivate func peer(_ peer: String, iceState state: RTCIceConnectionState) {
        switch state {
        case .connected, .completed:
            peers[peer] = true
        case .failed, .closed, .disconnected:
            // Отвал mesh-линка: убираем участника (при возврате он заново join'ится).
            if state == .failed || state == .closed { dropPeer(peer) }
            else { peers[peer] = false }
        default: break
        }
    }

    // MARK: - Транспорт

    private func broadcast(_ type: String) -> Bool {
        sendSignal?(type, "", ["group_id": groupId, "call_id": callId]) ?? false
    }

    private func directed(_ type: String, to peer: String, extra: [String: Any]) -> Bool {
        var payload = extra
        payload["group_call"] = true
        payload["group_id"] = groupId
        payload["call_id"] = callId
        return sendSignal?(type, peer, payload) ?? false
    }

    private static func requestMicAccess() async -> Bool {
        await withCheckedContinuation { c in
            AVAudioApplication.requestRecordPermission { c.resume(returning: $0) }
        }
    }

    // Per-peer делегат (у WebRTCClientDelegate нет peerId).
    private final class PeerBox: NSObject, WebRTCClientDelegate {
        let peer: String
        weak var manager: GroupCallManager?
        init(peer: String, manager: GroupCallManager) {
            self.peer = peer
            self.manager = manager
        }
        func webRTC(_ client: WebRTCClient, didGenerate candidate: RTCIceCandidate) {
            Task { @MainActor in self.manager?.peer(self.peer, generated: candidate) }
        }
        func webRTC(_ client: WebRTCClient, didChange state: RTCIceConnectionState) {
            Task { @MainActor in self.manager?.peer(self.peer, iceState: state) }
        }
        func webRTC(_ client: WebRTCClient, didAddRemoteTrack track: RTCVideoTrack) {}
    }
}
