import Foundation
import SwiftUI

// UI-модель одного сообщения (проекция StoredMessage + распарсенный payload).
struct ChatMessage: Identifiable, Equatable {
    let id: String
    let peerId: String
    let outgoing: Bool
    let senderId: String
    var payloadJson: String
    var status: Int32
    let ts: Int64
    var reactions: [String: [String]]   // emoji → [user_id]
    var edited: Bool
    var deleted: Bool
    let payload: Wire.Payload?

    var date: Date { Date(timeIntervalSince1970: Double(ts) / 1000) }

    static func == (l: ChatMessage, r: ChatMessage) -> Bool {
        l.id == r.id && l.status == r.status && l.payloadJson == r.payloadJson
            && l.edited == r.edited && l.deleted == r.deleted && l.reactions == r.reactions
    }

    static func from(_ m: StoredMessage) -> ChatMessage {
        let reactions = (try? JSONSerialization.jsonObject(with: Data(m.reactionsJson.utf8))) as? [String: [String]] ?? [:]
        return ChatMessage(id: m.id, peerId: m.peerId, outgoing: m.outgoing, senderId: m.senderId,
                           payloadJson: m.payloadJson, status: m.status, ts: m.ts,
                           reactions: reactions, edited: m.edited, deleted: m.deleted,
                           payload: Wire.parse(m.payloadJson))
    }
}

// Реалтайм-движок: единая точка входящих/исходящих. Владеет поллингом inbox и WS,
// расшифровывает конверты через ядро, пишет в SQLite, рассылает квитанции.
// Всё I/O делегируется актору CoreClient (вне main thread); UI подписан на @Published.
@MainActor
final class Messaging: ObservableObject {
    private static let isoFractional: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()
    private static let isoBasic: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()
    @Published var chats: [Chat] = []
    @Published var totalUnread: Int64 = 0
    @Published var typingPeers: Set<String> = []
    @Published var profiles: [String: Profile] = [:]
    @Published private(set) var realtimeConnected = false

    private weak var session: Session?
    private var core: CoreClient { session!.core }
    private var myId: String { session?.myId ?? "" }

    private var ws: WsClient?
    private var wsListener: WsBridge?
    private var pollTask: Task<Void, Never>?
    private var startupTask: Task<Void, Never>?
    private var reconnectTask: Task<Void, Never>?
    private var typingTimers: [String: Task<Void, Never>] = [:]
    private var shouldRun = false
    private var wsGeneration = UUID()
    private var reconnectAttempt = 0
    private var pollInFlight = false
    private var pollAgain = false
    private var lastTypingSent: [String: Date] = [:]
    private var stopTypingTasks: [String: Task<Void, Never>] = [:]
    private var receiptTasks: [String: Task<Void, Never>] = [:]
    private var receiptNeedsRead: [String: Bool] = [:]

    /// Активно открытый чат — для read-квитанций и живого дозагруза.
    var activePeer: String?

    /// Сигнал вью чата, что данные обновились.
    let inboxTick = PassthroughStorage()

    /// Менеджер звонков (WebRTC). Сигналинг идёт через ws этого движка.
    let calls = CallManager()

    /// Менеджер E2E-групп и каналов.
    let groups = GroupsManager()

    init() {
        calls.sendSignal = { [weak self] type, recipient, extra in
            guard let self, let ws = self.ws, ws.isActive() else { return false }
            let json = (try? JSONSerialization.data(withJSONObject: extra))
                .flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
            do {
                try ws.sendWebrtcSignal(signalType: type, recipientId: recipient, extraJson: json)
                return true
            } catch {
                return false
            }
        }
        calls.signalingAvailable = { [weak self] in self?.ws?.isActive() == true }
    }

    /// Привязать к сессии (после логина/бутстрапа). Идемпотентно.
    func rebind(session: Session) {
        self.session = session
        groups.bind(session: session, messaging: self)
    }

    /// Признак группового чата (для UI).
    /// Признак группы: сначала смотрим локальный SQLite-флаг чата (синхронно доступен
    /// сразу после refreshChats()), затем — асинхронно догружаемый GroupsManager.groups
    /// (может ещё не успеть заполниться на холодном старте из-за сетевого round-trip).
    func isGroup(_ peerId: String) -> Bool {
        let id = peerId.lowercased()
        if let flag = chats.first(where: { $0.peerId == id })?.isGroup { return flag }
        return groups.info(id) != nil
    }

    // MARK: - Жизненный цикл

    func start() {
        guard !shouldRun else { return }
        shouldRun = true
        reconnectAttempt = 0
        startupTask = Task {
            await refreshChats()
            await groups.load()
            await flushOutgoing()
            await pollInbox()
        }
        startPolling()
        connectWs()
    }

    func stop() {
        shouldRun = false
        startupTask?.cancel()
        startupTask = nil
        pollTask?.cancel()
        pollTask = nil
        reconnectTask?.cancel()
        reconnectTask = nil
        for task in stopTypingTasks.values { task.cancel() }
        stopTypingTasks.removeAll()
        receiptTasks.values.forEach { $0.cancel() }
        receiptTasks.removeAll()
        receiptNeedsRead.removeAll()
        typingTimers.values.forEach { $0.cancel() }
        typingTimers.removeAll()
        typingPeers.removeAll()
        ws?.disconnect()
        ws = nil
        wsListener = nil
        realtimeConnected = false
    }

    private func startPolling() {
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            guard let self else { return }
            while !Task.isCancelled {
                let seconds: UInt64 = self.realtimeConnected ? 30 : 5
                try? await Task.sleep(nanoseconds: seconds * 1_000_000_000)
                guard !Task.isCancelled else { return }
                await self.pollInbox()
            }
        }
    }

    private func connectWs() {
        // Токен — из памяти сессии (Keychain ненадёжен на неподписанной сборке), Keychain как фолбэк.
        let token = { () -> String in
            let t = session?.authToken ?? ""
            return t.isEmpty ? (Keychain.string(for: Keychain.kToken) ?? "") : t
        }()
        guard shouldRun else { return }
        // Токена ещё нет (логин не завершился) — повторить чуть позже.
        guard !token.isEmpty else {
            reconnectTask?.cancel()
            reconnectTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: 500_000_000)
                guard let self, self.shouldRun else { return }
                self.connectWs()
            }
            return
        }
        reconnectTask?.cancel()
        let generation = UUID()
        wsGeneration = generation
        let url = "wss://YOUR-SERVER-HOST.nip.io/ws?token=\(token)"
        let client = WsClient()
        let bridge = WsBridge(
            onOpen: { [weak self] in
                Task { @MainActor in self?.handleWsOpen(generation: generation) }
            },
            onEvent: { [weak self] event in
                Task { @MainActor in
                    guard self?.wsGeneration == generation else { return }
                    self?.handleWsEvent(event)
                }
            },
            onClose: { [weak self] in
                Task { @MainActor in self?.handleWsClose(generation: generation) }
            }
        )
        self.ws = client
        self.wsListener = bridge
        do {
            try client.connect(url: url, listener: bridge)
        } catch {
            handleWsClose(generation: generation)
        }
    }

    private func handleWsOpen(generation: UUID) {
        guard generation == wsGeneration else { return }
        reconnectTask?.cancel()
        reconnectAttempt = 0
        realtimeConnected = true
        Task { await pollInbox() }
    }

    private func handleWsClose(generation: UUID) {
        guard generation == wsGeneration else { return }
        realtimeConnected = false
        guard shouldRun else { return }
        reconnectTask?.cancel()
        let delays: [UInt64] = [2, 4, 8, 15, 30]
        let delay = delays[min(reconnectAttempt, delays.count - 1)]
        reconnectAttempt = min(reconnectAttempt + 1, delays.count - 1)
        reconnectTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: delay * 1_000_000_000)
            guard !Task.isCancelled, let self, self.shouldRun else { return }
            self.connectWs()
        }
    }

    private func handleWsEvent(_ json: String) {
        guard let data = json.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = obj["type"] as? String else { return }
        let sender = (obj["sender_id"] as? String)?.lowercased() ?? ""
        switch type {
        case "typing": markTyping(sender)
        case "stop_typing":
            typingTimers[sender]?.cancel()
            typingTimers[sender] = nil
            typingPeers.remove(sender)
        case "new_message": Task { await pollInbox() }
        case "webrtc_offer", "webrtc_answer", "webrtc_ice", "webrtc_hangup", "webrtc_busy":
            calls.handleSignal(type: type, sender: sender, payload: obj)
        default: break
        }
    }

    private func markTyping(_ peer: String) {
        guard !peer.isEmpty else { return }
        typingPeers.insert(peer)
        typingTimers[peer]?.cancel()
        typingTimers[peer] = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            self?.typingPeers.remove(peer)
            self?.typingTimers[peer] = nil
        }
    }

    func updateTyping(to peer: String, isTyping: Bool) {
        let id = peer.lowercased()
        guard !id.isEmpty, id != myId.lowercased() else { return }   // в Избранное не «печатаем»
        stopTypingTasks[id]?.cancel()

        guard isTyping else {
            sendStopTyping(to: id)
            return
        }

        let now = Date()
        if now.timeIntervalSince(lastTypingSent[id] ?? .distantPast) >= 2 {
            try? ws?.sendTyping(recipientId: id)
            lastTypingSent[id] = now
        }
        stopTypingTasks[id] = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard !Task.isCancelled else { return }
            self?.sendStopTyping(to: id)
        }
    }

    private func sendStopTyping(to peer: String) {
        stopTypingTasks[peer]?.cancel()
        stopTypingTasks[peer] = nil
        lastTypingSent[peer] = nil
        let data = try? JSONSerialization.data(withJSONObject: ["type": "stop_typing", "recipient_id": peer])
        if let data, let json = String(data: data, encoding: .utf8) { try? ws?.sendRaw(json: json) }
    }

    // MARK: - Приём (inbox)

    func pollInbox() async {
        if pollInFlight {
            pollAgain = true
            return
        }
        pollInFlight = true
        repeat {
            pollAgain = false
            await performInboxPoll()
        } while pollAgain && shouldRun
        pollInFlight = false
    }

    private func performInboxPoll() async {
        let since = await core.metaGet("inbox_since")
        guard let items = try? await core.fetchInbox(since: since), !items.isEmpty else { return }

        var ackIds: [String] = []
        var cursor = since
        var changed = false
        // Курсор не двигаем дальше первого нерасшифрованного элемента (например,
        // групповое сообщение пришло раньше, чем локально сохранился ключ группы —
        // гонка между groups.load() и поллингом по WS-триггеру new_message).
        // Иначе следующий since=cursor пропустит его навсегда, а сообщение уже
        // не переспросить — оно будет тут же подтверждено (ack) и удалено с сервера.
        var stuck = false

        for item in items {
            switch await handleIncoming(item) {
            case .processed(let didChange):
                ackIds.append(item.id)
                if didChange { changed = true }
                if !stuck, let created = item.createdAt { cursor = created }
            case .duplicate:
                ackIds.append(item.id)
                if !stuck, let created = item.createdAt { cursor = created }
            case .undecryptable:
                stuck = true
            }
        }

        if let ts = cursor { await core.metaSet("inbox_since", ts) }
        if !ackIds.isEmpty { try? await core.ack(ackIds) }

        await refreshChats()
        if changed { inboxTick.fire() }
    }

    private enum IncomingResult {
        case processed(changed: Bool)
        case duplicate
        /// Конверт не вскрылся (например, ключ группы ещё не подгружен) — не ack,
        /// повторим на следующем поллинге.
        case undecryptable
    }

    private func handleIncoming(_ item: InboxItem) async -> IncomingResult {
        if await core.messageExists(item.id) { return .duplicate }
        guard let opened = try? await core.open(item: item),
              let payload = Wire.parse(opened.plaintext) else { return .undecryptable }

        let myId = myId.lowercased()
        let isGroup = opened.isGroup
        let peerId = isGroup ? item.recipientId.lowercased() : item.senderId.lowercased()
        let senderId = item.senderId.lowercased()
        let ts = parseTs(item.createdAt)

        switch payload.type {
        case "text", "media":
            let store = ChatMessage(id: item.id, peerId: peerId, outgoing: senderId == myId,
                                    senderId: senderId, payloadJson: opened.plaintext,
                                    status: 1, ts: ts, reactions: [:], edited: false, deleted: false,
                                    payload: payload)
            await save(store, incUnread: senderId != myId && activePeer != peerId, isGroup: isGroup)
            // Входящее медиа — в кеш устройства сразу (ГС/кружки всегда, тяжёлое по настройке).
            if payload.type == "media", let fid = payload.fileId,
               let key = payload.symKey, !key.isEmpty {
                let kind = payload.mediaKind
                let autoHeavy = UserDefaults.standard.bool(forKey: Self.autoDownloadKey)
                if kind == .voice || kind == .videoNote || ((kind == .image || kind == .video) && autoHeavy) {
                    let nonce = payload.nonce ?? ""
                    Task.detached(priority: .background) {
                        _ = await MediaStore.shared.data(fileId: fid, symKey: key, nonce: nonce)
                    }
                }
            }
            if !isGroup && senderId != myId {
                sendReceipts(to: senderId, read: activePeer == peerId)
            }
            // Баннер/островок — только для чужих сообщений вне открытого чата.
            if senderId != myId && activePeer != peerId {
                let chat = chats.first { $0.peerId == peerId }
                NotificationsManager.shared.incomingMessage(
                    peer: peerId,
                    chatTitle: chat?.title ?? "",
                    sender: senderId,
                    preview: Wire.preview(opened.plaintext),
                    muted: chat?.muted ?? false,
                    isGroup: isGroup
                )
            }
            return .processed(changed: true)

        case "edit":
            if let target = payload.target, let newText = payload.text {
                await core.updateText(target, Wire.text(newText))
            }
            return .processed(changed: true)

        case "reaction":
            if let target = payload.target {
                await applyReaction(target: target, emoji: payload.emoji ?? "", from: senderId)
            }
            return .processed(changed: true)

        case "delete":
            if let target = payload.target {
                await core.markDeleted(target)
            }
            return .processed(changed: true)

        case "delivered":
            await core.markOutgoingStatus(peer: senderId, status: 2)
            return .processed(changed: true)

        case "read":
            await core.markOutgoingStatus(peer: senderId, status: 3)
            return .processed(changed: true)

        default:
            return .processed(changed: false)   // неизвестные/web-native типы — игнор (канон), но ack безопасен
        }
    }

    private func applyReaction(target: String, emoji: String, from user: String) async {
        guard let msg = await core.getMessage(target) else { return }
        var reactions = (try? JSONSerialization.jsonObject(with: Data(msg.reactionsJson.utf8))) as? [String: [String]] ?? [:]
        for key in reactions.keys { reactions[key]?.removeAll { $0 == user } }
        if !emoji.isEmpty { reactions[emoji, default: []].append(user) }
        reactions = reactions.filter { !$0.value.isEmpty }
        let data = (try? JSONSerialization.data(withJSONObject: reactions, options: [.sortedKeys])) ?? Data("{}".utf8)
        await core.updateReactions(target, String(data: data, encoding: .utf8) ?? "{}")
    }

    private func save(_ m: ChatMessage, incUnread: Bool, isGroup: Bool) async {
        let stored = StoredMessage(id: m.id, peerId: m.peerId, outgoing: m.outgoing,
                                   senderId: m.senderId, payloadJson: m.payloadJson,
                                   status: m.status, ts: m.ts,
                                   reactionsJson: "{}", edited: m.edited, deleted: m.deleted)
        await core.insertMessage(stored)
        let title = isGroup ? (chats.first { $0.peerId == m.peerId }?.title ?? "") : m.senderId
        await core.touchChat(peer: m.peerId, isGroup: isGroup, title: title,
                             lastText: Wire.preview(m.payloadJson), lastTs: m.ts, incUnread: incUnread)
    }

    private func sendReceipts(to peer: String, read: Bool) {
        let id = peer.lowercased()
        receiptNeedsRead[id] = (receiptNeedsRead[id] ?? false) || read
        receiptTasks[id]?.cancel()
        receiptTasks[id] = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 350_000_000)
            guard !Task.isCancelled, let self else { return }
            let shouldRead = self.receiptNeedsRead[id] ?? false
            self.receiptNeedsRead[id] = nil
            self.receiptTasks[id] = nil
            let payload = shouldRead ? Wire.read() : Wire.delivered()
            _ = try? await self.core.sendDirect(to: id, wirePayload: payload)
        }
    }

    private func parseTs(_ iso: String?) -> Int64 {
        guard let iso else { return Int64(Date().timeIntervalSince1970 * 1000) }
        if let d = Self.isoFractional.date(from: iso) ?? Self.isoBasic.date(from: iso) {
            return Int64(d.timeIntervalSince1970 * 1000)
        }
        return Int64(Date().timeIntervalSince1970 * 1000)
    }

    // MARK: - Список чатов

    func refreshChats() async {
        let nextChats = (try? await core.chatList()) ?? []
        let nextUnread = await core.totalUnread()
        if chats != nextChats {
            withAnimation(.spring(response: 0.38, dampingFraction: 0.85)) { chats = nextChats }
        }
        if totalUnread != nextUnread { totalUnread = nextUnread }
    }

    func openChat(_ peerId: String, isGroup: Bool) async {
        activePeer = peerId
        prefetchMedia(for: peerId)
        await core.clearUnread(peerId)
        await refreshChats()
        if !isGroup {
            Task { [weak self] in
                guard let self else { return }
                _ = try? await self.core.sendDirect(to: peerId, wirePayload: Wire.read())
            }
        }
    }
    func closeChat() { activePeer = nil }

    // MARK: - Закрепы

    /// Максимум закреплённых чатов (как в Telegram — лимит на список).
    static let maxPins = 10

    /// Порядок закрепов: новый закреп всегда встаёт ПЕРВЫМ (выше предыдущих).
    /// Хранится локально — сервер про закрепы не знает.
    @Published private(set) var pinOrder: [String] =
        (UserDefaults.standard.array(forKey: "pinOrder") as? [String]) ?? []

    func setPinned(_ peer: String, _ v: Bool) async {
        let id = peer.lowercased()
        if v {
            guard pinOrder.count < Self.maxPins || pinOrder.contains(id) else { return }
            pinOrder.removeAll { $0 == id }
            pinOrder.insert(id, at: 0)
        } else {
            pinOrder.removeAll { $0 == id }
        }
        UserDefaults.standard.set(pinOrder, forKey: "pinOrder")
        await core.setPinnedFlag(id, v)
        await refreshChats()
    }

    /// Позиция чата в списке закрепов (для сортировки; незнакомые — в конец).
    func pinRank(_ peer: String) -> Int {
        pinOrder.firstIndex(of: peer.lowercased()) ?? Int.max
    }
    func setMuted(_ peer: String, _ v: Bool) async { await core.setMutedFlag(peer, v); await refreshChats() }
    func setArchived(_ peer: String, _ v: Bool) async { await core.setArchivedFlag(peer, v); await refreshChats() }
    func deleteChat(_ peer: String) async { await core.deleteChatData(peer); await refreshChats() }

    /// Избранное: очистка истории (сам «личный канал» не удаляется — id аккаунта).
    func clearSavedMessages() async {
        let me = myId.lowercased()
        try? await core.deleteHistory(peer: me)   // и на сервере
        await core.deleteChatData(me)
        await refreshChats()
        inboxTick.fire()
    }

    // MARK: - Отправка (optimistic)

    @discardableResult
    func sendText(to peer: String, text: String, replyToId: String? = nil, replyToText: String? = nil,
                  isGroup: Bool = false) -> String {
        optimisticSend(to: peer, payload: Wire.text(text, replyToId: replyToId, replyToText: replyToText), isGroup: isGroup)
    }

    @discardableResult
    func sendMediaPayload(to peer: String, payloadJson: String, isGroup: Bool = false) -> String {
        optimisticSend(to: peer, payload: payloadJson, isGroup: isGroup)
    }

    private func optimisticSend(to peer: String, payload: String, isGroup: Bool) -> String {
        let localId = "local_\(UUID().uuidString)"
        let ts = Int64(Date().timeIntervalSince1970 * 1000)
        let stored = StoredMessage(id: localId, peerId: peer.lowercased(), outgoing: true,
                                   senderId: myId.lowercased(), payloadJson: payload,
                                   status: 0, ts: ts, reactionsJson: "{}", edited: false, deleted: false)
        Task {
            await core.insertMessage(stored)
            await core.touchChat(peer: peer.lowercased(), isGroup: isGroup, title: peer,
                                 lastText: Wire.preview(payload), lastTs: ts, incUnread: false)
            await refreshChats()
            inboxTick.fire()
            await deliverPending(localId: localId, peer: peer, payload: payload, isGroup: isGroup)
        }
        return localId
    }

    private func deliverPending(localId: String, peer: String, payload: String, isGroup: Bool) async {
        do {
            let realId: String
            if isGroup {
                guard let key = await core.groupKey(peer.lowercased()) else {
                    await core.updateStatus(localId, -1); inboxTick.fire(); return
                }
                realId = try await core.sendGroup(groupId: peer, groupKey: key, wirePayload: payload)
            } else {
                realId = try await core.sendDirect(to: peer, wirePayload: payload)
            }
            await core.replaceMessageId(old: localId, new: realId, status: 1)
        } catch {
            await core.updateStatus(localId, -1)
        }
        inboxTick.fire()
    }

    func retryMessage(_ message: ChatMessage, isGroup: Bool) {
        guard message.status == -1, message.id.hasPrefix("local_") else { return }
        Task {
            await core.updateStatus(message.id, 0)
            inboxTick.fire()

            if let payload = message.payload, payload.type == "media", (payload.symKey ?? "").isEmpty {
                guard let data = await MediaStore.shared.data(fileId: message.id, symKey: "", nonce: "") else {
                    await core.updateStatus(message.id, -1)
                    inboxTick.fire()
                    return
                }
                do {
                    let upload = try await core.uploadMedia(data)
                    await MediaStore.shared.seed(fileId: upload.fileId, data: data)
                    let finalPayload = Wire.media(
                        fileId: upload.fileId, symKey: upload.symKey,
                        mimeType: payload.mimeType ?? "application/octet-stream", nonce: upload.nonce,
                        kind: payload.kind, duration: payload.duration, fileName: payload.fileName,
                        fileSize: payload.fileSize, caption: payload.caption, fwdFrom: payload.fwdFrom,
                        replyToId: payload.replyToId, replyToText: payload.replyToText
                    )
                    await core.updatePayload(message.id, finalPayload)
                    await deliverPending(localId: message.id, peer: message.peerId,
                                         payload: finalPayload, isGroup: isGroup)
                } catch {
                    await core.updateStatus(message.id, -1)
                    inboxTick.fire()
                }
            } else {
                await deliverPending(localId: message.id, peer: message.peerId,
                                     payload: message.payloadJson, isGroup: isGroup)
            }
        }
    }

    /// Отправить медиа (фото/файл/видео/голос/кружок). Показывает пузырь сразу (локальные байты
    /// в кэше), затем в фоне шифрует+грузит через ядро и досылает финальный payload.
    func sendMedia(to peer: String, data: Data, mime: String, kind: String,
                   fileName: String? = nil, caption: String? = nil,
                   duration: Double? = nil, isGroup: Bool = false,
                   replyToId: String? = nil, replyToText: String? = nil) {
        let localId = "local_\(UUID().uuidString)"
        let ts = Int64(Date().timeIntervalSince1970 * 1000)
        let size = Int64(data.count)
        // Оптимистичный payload: file_id = localId, ключей ещё нет — превью берётся из кэша по localId.
        let payload0 = Wire.media(fileId: localId, symKey: "", mimeType: mime, nonce: "",
                                  kind: kind, duration: duration, fileName: fileName,
                                  fileSize: size, caption: caption,
                                  replyToId: replyToId, replyToText: replyToText)
        let stored = StoredMessage(id: localId, peerId: peer.lowercased(), outgoing: true,
                                   senderId: myId.lowercased(), payloadJson: payload0,
                                   status: 0, ts: ts, reactionsJson: "{}", edited: false, deleted: false)
        Task {
            await MediaStore.shared.seed(fileId: localId, data: data)
            await core.insertMessage(stored)
            await core.touchChat(peer: peer.lowercased(), isGroup: isGroup, title: peer,
                                 lastText: Wire.preview(payload0), lastTs: ts, incUnread: false)
            await refreshChats(); inboxTick.fire()
            do {
                let up = try await core.uploadMedia(data)
                await MediaStore.shared.seed(fileId: up.fileId, data: data)
                let payload = Wire.media(fileId: up.fileId, symKey: up.symKey, mimeType: mime,
                                         nonce: up.nonce, kind: kind, duration: duration,
                                         fileName: fileName, fileSize: size, caption: caption,
                                         replyToId: replyToId, replyToText: replyToText)
                await core.updatePayload(localId, payload)   // финальный payload без пометки «изменено»
                let realId: String
                if isGroup {
                    guard let key = await core.groupKey(peer.lowercased()) else {
                        throw CoreError.BadInput(msg: "Нет ключа группы")
                    }
                    realId = try await core.sendGroup(groupId: peer, groupKey: key, wirePayload: payload)
                } else {
                    realId = try await core.sendDirect(to: peer, wirePayload: payload)
                }
                await core.replaceMessageId(old: localId, new: realId, status: 1)
            } catch {
                await core.updateStatus(localId, -1)
            }
            await refreshChats(); inboxTick.fire()
        }
    }

    func sendReaction(to peer: String, target: String, emoji: String, isGroup: Bool) async {
        await applyReaction(target: target, emoji: emoji, from: myId.lowercased())
        inboxTick.fire()
        let payload = Wire.reaction(target: target, emoji: emoji)
        if isGroup, let key = await core.groupKey(peer.lowercased()) {
            _ = try? await core.sendGroup(groupId: peer, groupKey: key, wirePayload: payload)
        } else {
            _ = try? await core.sendDirect(to: peer, wirePayload: payload)
        }
    }

    func editMessage(peer: String, target: String, newText: String, isGroup: Bool) async {
        await core.updateText(target, Wire.text(newText))
        inboxTick.fire()
        let payload = Wire.edit(target: target, text: newText)
        if isGroup, let key = await core.groupKey(peer.lowercased()) {
            _ = try? await core.sendGroup(groupId: peer, groupKey: key, wirePayload: payload)
        } else {
            _ = try? await core.sendDirect(to: peer, wirePayload: payload)
        }
    }

    func deleteMessage(peer: String, target: String, isGroup: Bool) async {
        await core.markDeleted(target)
        inboxTick.fire()
        // Шлём "delete" собеседнику/группе, чтобы сообщение исчезло не только у
        // меня — раньше удаление было чисто локальным (core.markDeleted и всё).
        let payload = Wire.delete(target: target)
        if isGroup, let key = await core.groupKey(peer.lowercased()) {
            _ = try? await core.sendGroup(groupId: peer, groupKey: key, wirePayload: payload)
        } else {
            _ = try? await core.sendDirect(to: peer, wirePayload: payload)
        }
    }

    func flushOutgoing() async {
        let pending = await core.pendingOutgoing()
        for m in pending where m.id.hasPrefix("local_") {
            if let payload = Wire.parse(m.payloadJson), payload.type == "media",
               (payload.symKey ?? "").isEmpty {
                await core.updateStatus(m.id, -1)
                continue
            }
            let isGroup = chats.first { $0.peerId == m.peerId }?.isGroup ?? false
            await deliverPending(localId: m.id, peer: m.peerId, payload: m.payloadJson, isGroup: isGroup)
        }
    }

    // MARK: - Автозагрузка медиа в кеш

    /// Настройка: авто-скачивание фото/видео (тяжёлое). Голосовые и кружки
    /// качаются всегда — они должны жить на устройстве независимо от сети.
    static let autoDownloadKey = "autoDownloadMedia"

    private var prefetchTasks: [String: Task<Void, Never>] = [:]

    /// Префетч медиа чата: последние ≤200 сообщений, поэтапно (по одному файлу),
    /// сначала голосовые/кружки, затем фото/видео (если включена автозагрузка).
    /// Кеш-хиты мгновенны — повторный вызов дёшев; после очистки кеша всё
    /// докачается заново этим же путём.
    func prefetchMedia(for peer: String) {
        let id = peer.lowercased()
        guard prefetchTasks[id] == nil else { return }
        let autoHeavy = UserDefaults.standard.bool(forKey: Self.autoDownloadKey)
        prefetchTasks[id] = Task { [weak self] in
            guard let self else { return }
            defer { Task { @MainActor in self.prefetchTasks[id] = nil } }
            let page = (try? await self.core.messages(peer: id, beforeTs: 0, limit: 200)) ?? []
            var voices: [(String, String, String)] = []   // fileId, key, nonce
            var heavy: [(String, String, String)] = []
            for m in page.reversed() {   // свежие в приоритете
                guard !m.deleted, let payload = Wire.parse(m.payloadJson),
                      payload.type == "media",
                      let fid = payload.fileId, let key = payload.symKey, !key.isEmpty else { continue }
                let item = (fid, key, payload.nonce ?? "")
                switch payload.mediaKind {
                case .voice, .videoNote: voices.append(item)
                case .image, .video: heavy.append(item)
                case .file: break   // документы — только вручную
                }
            }
            for (fid, key, nonce) in voices {
                guard !Task.isCancelled else { return }
                _ = await MediaStore.shared.data(fileId: fid, symKey: key, nonce: nonce)
            }
            guard autoHeavy else { return }
            for (fid, key, nonce) in heavy {
                guard !Task.isCancelled else { return }
                _ = await MediaStore.shared.data(fileId: fid, symKey: key, nonce: nonce)
            }
        }
    }

    // MARK: - Presence

    func loadProfile(_ peer: String) async {
        let id = peer.lowercased()
        if let profile = try? await core.getProfile(id), profiles[id] != profile {
            profiles[id] = profile
            // Персистим avatar_file_id: профили живут в памяти, и после
            // перезапуска аватарки «подтягивались» заново (инициалы → фото).
            let fid = profile.avatarFileId ?? ""
            if avatarIds[id] != fid {
                if fid.isEmpty { avatarIds.removeValue(forKey: id) } else { avatarIds[id] = fid }
                UserDefaults.standard.set(avatarIds, forKey: "avatarFileIds")
            }
        }
    }

    /// Карта peer → avatar_file_id, переживает перезапуск (UserDefaults).
    private var avatarIds: [String: String] =
        (UserDefaults.standard.dictionary(forKey: "avatarFileIds") as? [String: String]) ?? [:]

    /// URL аватара пира: из живого профиля или персистентной карты —
    /// после перезапуска URL известен сразу, картинка мгновенно из дискового кеша.
    func avatarURL(_ peer: String) -> URL? {
        let id = peer.lowercased()
        let fid = profiles[id]?.avatarFileId ?? avatarIds[id]
        guard let fid, !fid.isEmpty else { return nil }
        return URL(string: "\(CoreClient.baseURL)/avatars/\(fid)")
    }

    private var profileRequests: Set<String> = []

    /// Догрузить профиль для строки списка, если его ещё нет в кэше.
    /// Групповые id пропускаем — у групп нет пользовательского профиля.
    func ensureProfile(_ peer: String) {
        let id = peer.lowercased()
        guard !id.isEmpty,
              !id.hasPrefix("group_"), !id.hasPrefix("channel_"),
              !id.hasPrefix("grp_"), !id.hasPrefix("chn_") else { return }
        guard profiles[id] == nil, !profileRequests.contains(id) else { return }
        profileRequests.insert(id)
        Task { await loadProfile(id) }
    }

    func isOnline(_ peer: String) -> Bool {
        guard let iso = profiles[peer.lowercased()]?.lastActive else { return false }
        guard let d = Self.isoFractional.date(from: iso) ?? Self.isoBasic.date(from: iso) else { return false }
        return Date().timeIntervalSince(d) < 75
    }

    /// «был(а) N минут назад» или «в сети».
    func presenceText(_ peer: String) -> String {
        if isOnline(peer) { return "в сети" }
        guard let iso = profiles[peer.lowercased()]?.lastActive else { return "" }
        guard let d = Self.isoFractional.date(from: iso) ?? Self.isoBasic.date(from: iso) else { return "" }
        let mins = Int(Date().timeIntervalSince(d) / 60)
        if mins < 1 { return "был(а) только что" }
        if mins < 60 { return "был(а) \(mins) мин назад" }
        let hours = mins / 60
        if hours < 24 { return "был(а) \(hours) ч назад" }
        return "был(а) давно"
    }
}

// Мостик WsListener (генерённый протокол) → Swift-замыкание.
final class WsBridge: WsListener, @unchecked Sendable {
    private let onOpenCb: () -> Void
    private let onEventCb: (String) -> Void
    private let onCloseCb: () -> Void
    init(onOpen: @escaping () -> Void,
         onEvent: @escaping (String) -> Void,
         onClose: @escaping () -> Void) {
        self.onOpenCb = onOpen
        self.onEventCb = onEvent
        self.onCloseCb = onClose
    }
    func onOpen() { onOpenCb() }
    func onEvent(json: String) { onEventCb(json) }
    func onClose() { onCloseCb() }
}

// Лёгкий сигнал для вью чата (без данных).
final class PassthroughStorage: ObservableObject {
    @Published var tick = 0
    func fire() { tick &+= 1 }
}
