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
    /// Эмодзи-статусы (рядом с ником, как в Telegram). Ключ — user_id.
    @Published var statusEmojis: [String: String] = [:]
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
    /// Отметка времени последнего сообщения, разобранного быстрым путём по WS.
    /// Двигать по ней inbox_since сразу нельзя: если бы WS-событие по дороге
    /// потерялось, потерянное сообщение оказалось бы СТАРШЕ курсора, и мы бы
    /// его больше никогда не запросили. Поэтому отметка ждёт поллинга, который
    /// подтвердит, что за курсором действительно ничего не осталось.
    private var fastCursor: String?
    /// Конверты из WS-событий, ждущие разбора, и признак идущего разбора.
    private var fastQueue: [InboxItem] = []
    private var fastDraining = false
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

    /// Групповые звонки (mesh, аудио).
    let groupCalls = GroupCallManager()

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
        groupCalls.sendSignal = { [weak self] type, recipient, extra in
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
        groupCalls.isBusyElsewhere = { [weak self] in self?.calls.isBusy ?? false }
        groupCalls.myId = { [weak self] in self?.myId ?? "" }
    }

    /// Привязать к сессии (после логина/бутстрапа). Идемпотентно.
    func rebind(session: Session) {
        self.session = session
        groups.bind(session: session, messaging: self)
        rebuildRouter()
    }

    /// Пересобрать набор транспортов под активное пространство.
    ///
    /// Зовётся при каждой привязке: у другого сервера другой идентификатор,
    /// а значит и другой транспорт. Локальные транспорты (Bluetooth, Wi-Fi)
    /// добавятся сюда же и от пространства зависеть не будут.
    private func rebuildRouter() {
        let r = TransportRouter(core: core)
        r.register(ServerTransport(core: core, serverId: ServerContext.serverId))
        router = r
    }

    /// Маршрутизатор доставки. Создаётся вместе с привязкой к сессии.
    private var router: TransportRouter?

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
            // Порядок важен: ядро — актор, вызовы к нему выстраиваются в очередь,
            // и всё, что стоит перед разбором инбокса, откладывает появление новых
            // сообщений на экране. Поэтому сначала то, ради чего приложение
            // открыли, и лишь потом обслуживание ключей.
            await refreshChats()         // локальная база, без сети
            await groups.load()          // ключи групп нужны ДО разбора инбокса
            await pollInbox()
            await flushOutgoing()
            await core.ensureOlmKeys()   // опубликовать/пополнить prekeys для Double Ratchet
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
        let url = "\(ServerContext.wsEndpoint)?token=\(token)"
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
        case "new_message":
            // Конверт обычно приезжает прямо в событии — тогда сообщение видно
            // без похода за инбоксом. Не сложилось (старый сервер, чужой ключ) —
            // работает прежний путь.
            Task { [weak self] in
                guard let self else { return }
                if await self.fastIncoming(obj) { return }
                await self.pollInbox()
            }
        case "webrtc_offer", "webrtc_answer", "webrtc_ice", "webrtc_hangup", "webrtc_busy":
            // SDP/ICE группового звонка помечены group_call — в свой менеджер.
            if (obj["group_call"] as? Bool) == true || (obj["group_call"] as? Int) == 1 {
                groupCalls.handleSignal(type: type, sender: sender, payload: obj)
            } else {
                calls.handleSignal(type: type, sender: sender, payload: obj)
            }
        case "group_call_start", "group_call_join", "group_call_leave":
            groupCalls.handleSignal(type: type, sender: sender, payload: obj)
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
        guard let items = try? await core.fetchInbox(since: since) else { return }
        guard !items.isEmpty else {
            // За курсором пусто: всё разобрано, включая быстрый путь по WS.
            // Только теперь двигать курсор по его отметке безопасно — потерянное
            // событие лежало бы сейчас именно здесь.
            await advanceCursorToFast(after: since)
            return
        }
        // Сервер отдаёт не больше 200 за раз — если пришло ровно окно, догребаем
        // остаток сразу, а не по 200 за цикл поллинга.
        if items.count >= 200 { pollAgain = true }

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
                await clearRetries(item.id)
            case .duplicate:
                ackIds.append(item.id)
                if !stuck, let created = item.createdAt { cursor = created }
                await clearRetries(item.id)
            case .undecryptable:
                // Карантин: сообщение, которое не вскрывается ДОЛГО (пир выкинул
                // устройство, ключ сменился и не принят), иначе навсегда затыкает
                // инбокс — на вебе окно выдачи, на iOS курсор since.
                if await shouldQuarantine(item) {
                    ackIds.append(item.id)
                    if !stuck, let created = item.createdAt { cursor = created }
                    await clearRetries(item.id)
                    await saveUndecryptablePlaceholder(item)
                    changed = true
                } else {
                    stuck = true
                    // Баннер тревоги должен подняться СЕЙЧАС, а не после карантина:
                    // иначе кнопка «Принять новый ключ» появляется, когда принимать
                    // уже нечего.
                    changed = true
                }
            }
        }

        if let ts = cursor { await core.metaSet("inbox_since", ts) }
        if !ackIds.isEmpty { try? await core.ack(ackIds) }
        // Окно выбрано целиком (200) — остаток ещё не виден, курсор быстрого
        // пути подождёт следующего круга.
        if !stuck && items.count < 200 { await advanceCursorToFast(after: cursor) }

        await refreshChats()
        if changed {
            inboxTick.fire()
            // Догружаем свежие сообщения в резервную копию (если она включена).
            // Копия шифруется на устройстве, поэтому это дешёвая фоновая работа.
            Task.detached { [core] in await core.backupSyncUp() }
        }
    }

    private func advanceCursorToFast(after since: String?) async {
        guard let ts = fastCursor else { return }
        // Отметки времени сервер сравнивает как текст (created_at — TEXT),
        // поэтому и здесь строковое сравнение, а не разбор дат.
        if let since, ts <= since { return }
        await core.metaSet("inbox_since", ts)
    }

    /// Быстрый приём: конверт приехал прямо в WS-событии, поэтому сообщение
    /// вскрывается и показывается сразу — без отдельного захода за инбоксом.
    /// Этот заход и был основной задержкой ответа: целый сетевой круг поверх
    /// уже доставленного уведомления.
    ///
    /// true — разобрано и подтверждено; false — нужен обычный поллинг.
    private func fastIncoming(_ obj: [String: Any]) async -> Bool {
        guard let id = obj["message_id"] as? String, !id.isEmpty,
              let envelope = obj["envelope"] as? String, !envelope.isEmpty,
              let sender = obj["sender_id"] as? String,
              let recipient = obj["recipient_id"] as? String,
              let createdAt = obj["created_at"] as? String, !createdAt.isEmpty
        else { return false }   // сервер старой версии — полей в событии нет

        let myDevice = await core.currentDeviceId
        guard !myDevice.isEmpty else { return false }
        // Копия, адресованная другому устройству аккаунта: ключа от неё здесь
        // нет и в инбокс она не попадёт — поллинг звать незачем.
        if let target = obj["target_device_id"] as? String, !target.isEmpty,
           target != myDevice { return true }

        fastQueue.append(InboxItem(id: id, senderId: sender.lowercased(),
                                   recipientId: recipient.lowercased(),
                                   envelope: envelope, createdAt: createdAt))
        return await drainFast()
    }

    /// Разбор очереди конвертов, пришедших по WS, строго по одному: вскрывать
    /// два конверта от одного пира параллельно нельзя — Olm-сессия одна, и
    /// второй вызов сломал бы её состояние.
    private func drainFast() async -> Bool {
        // Разбор уже идёт — только что добавленное заберёт тот же цикл.
        guard !fastDraining else { return true }
        // Идёт обычный поллинг: он и так вычитает всё неподтверждённое, а
        // повторное вскрытие тех же конвертов только сломает сессии.
        guard !pollInFlight else {
            fastQueue.removeAll()
            pollAgain = true
            return true
        }

        fastDraining = true
        pollInFlight = true
        var ackIds: [String] = []
        var newest: String?
        var changed = false
        var needPoll = false

        while !fastQueue.isEmpty {
            let item = fastQueue.removeFirst()
            switch await handleIncoming(item) {
            case .processed(let didChange):
                if didChange { changed = true }
            case .duplicate:
                break
            case .undecryptable:
                // Ключ группы ещё не подъехал или сменился ключ пира — там
                // карантин и тревоги, это работа поллинга.
                needPoll = true
                continue
            }
            ackIds.append(item.id)
            await clearRetries(item.id)
            if let ts = item.createdAt, newest == nil || ts > newest! { newest = ts }
        }

        pollInFlight = false
        fastDraining = false

        // Подтверждаем всю пачку одним запросом — и уже после показа сообщений.
        if !ackIds.isEmpty { try? await core.ack(ackIds) }
        if let ts = newest, fastCursor == nil || ts > fastCursor! { fastCursor = ts }

        await refreshChats()
        if changed {
            inboxTick.fire()
            Task.detached { [core] in await core.backupSyncUp() }
        }
        // Пока разбирали, накопилось ещё — пусть поллинг доберёт остаток.
        return !(needPoll || pollAgain)
    }

    private enum IncomingResult {
        case processed(changed: Bool)
        case duplicate
        /// Конверт не вскрылся (например, ключ группы ещё не подгружен) — не ack,
        /// повторим на следующем поллинге.
        case undecryptable
    }

    /// Сколько ждём, прежде чем убрать конверт из очереди. Считаем ВРЕМЯ, а не
    /// попытки: поллинг событийный (WS-триггер на каждое сообщение), и счётчик
    /// попыток выгорал бы за секунды — быстрее, чем человек нажмёт «Принять ключ».
    private static let quarantineAfter: TimeInterval = 24 * 3600

    private func retriesKey(_ id: String) -> String { "undecryptable_\(id)" }

    /// Пора ли убирать конверт из очереди. НЕ карантиним, пока по этому чату висит
    /// непринятая тревога (иначе кнопка в баннере теряет смысл) и пока это групповое
    /// сообщение без ключа группы — там ключ просто ещё не подъехал.
    private func shouldQuarantine(_ item: InboxItem) async -> Bool {
        let myId = myId.lowercased()
        let isGroup = item.recipientId.lowercased() != myId
        if isGroup, await core.groupKey(item.recipientId.lowercased()) == nil {
            await groups.load()   // гонка загрузки ключей, а не «ядовитое» сообщение
            return false
        }
        if await core.pendingKeyChange(for: item.senderId.lowercased()) != nil { return false }

        let key = retriesKey(item.id)
        let now = Date().timeIntervalSince1970
        guard let firstRaw = await core.metaGet(key), let first = Double(firstRaw), first > 0 else {
            await core.metaSet(key, String(now))   // засекаем первую неудачу
            return false
        }
        return now - first >= Self.quarantineAfter
    }

    private func clearRetries(_ id: String) async {
        if await core.metaGet(retriesKey(id)) != nil {
            await core.metaSet(retriesKey(id), "0")
        }
    }

    /// Плашка вместо навсегда нерасшифрованного сообщения: пользователь видит, что
    /// сообщение было и почему не открылось, а очередь инбокса едет дальше.
    private func saveUndecryptablePlaceholder(_ item: InboxItem) async {
        // Групповой конверт адресован на group_id — иначе плашка уезжала бы
        // в фантомный личный чат с отправителем и плодила там непрочитанное.
        let myId = myId.lowercased()
        let isGroup = item.recipientId.lowercased() != myId
        let peerId = isGroup ? item.recipientId.lowercased() : item.senderId.lowercased()
        let text = "Сообщение не удалось расшифровать: не подтверждено устройство отправителя."
        let stored = ChatMessage(id: item.id, peerId: peerId, outgoing: false,
                                 senderId: item.senderId.lowercased(), payloadJson: Wire.text(text),
                                 status: 1, ts: parseTs(item.createdAt), reactions: [:],
                                 edited: false, deleted: false, payload: Wire.parse(Wire.text(text)))
        await save(stored, incUnread: activePeer != peerId, isGroup: isGroup)
    }

    private func handleIncoming(_ item: InboxItem) async -> IncomingResult {
        // Быстрая отсечка по серверному id — дёшево и не требует расшифровки.
        if await core.messageExists(item.id) { return .duplicate }
        // Заблокированный отправитель: сообщение не сохраняем, но считаем
        // обработанным — иначе оно не подтвердится серверу и будет приходить
        // при каждом опросе, застопорив очередь инбокса.
        if BlockStore.shared.isBlocked(item.senderId) { return .processed(changed: false) }
        guard let opened = try? await core.open(item: item),
              let payload = Wire.parse(opened.plaintext) else { return .undecryptable }

        // Настоящая дедупликация — по идентификатору ИЗ КОНВЕРТА. Серверный id
        // у каждой копии свой (одна на устройство получателя) и меняется при
        // смене транспорта, поэтому по нему одно и то же сообщение выглядело бы
        // разными. Клиенты старых версий поля не шлют — для них идентичностью
        // остаётся серверный id, и это правильно.
        let messageId = messageIdFromPayload(payloadJson: opened.plaintext) ?? item.id
        if messageId != item.id, await core.messageExists(messageId) { return .duplicate }

        let myId = myId.lowercased()
        let isGroup = opened.isGroup
        let peerId = isGroup ? item.recipientId.lowercased() : item.senderId.lowercased()
        let senderId = item.senderId.lowercased()
        let ts = parseTs(item.createdAt)

        switch payload.type {
        case "text", "media":
            let store = ChatMessage(id: messageId, peerId: peerId, outgoing: senderId == myId,
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
        if !isGroup { refreshStatusEmoji(peerId) }
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
    /// Порядок закрепов. НАМЕРЕННО не @Published: если публиковать и его, и
    /// chats, SwiftUI получает ДВА обновления подряд, и переезд строки
    /// распадается на два шага — она прыгает к началу списка и лишь потом едет
    /// на своё место. Читается только из pinRank во время отрисовки, а
    /// перерисовку вызывает присваивание chats.
    private(set) var pinOrder: [String] =
        (UserDefaults.standard.array(forKey: "pinOrder") as? [String]) ?? []

    func setPinned(_ peer: String, _ v: Bool) async {
        let id = peer.lowercased()
        if v { guard pinOrder.count < Self.maxPins || pinOrder.contains(id) else { return } }
        let idx = chats.firstIndex(where: { $0.peerId == id })

        pinOrder.removeAll { $0 == id }
        if v { pinOrder.insert(id, at: 0) }
        UserDefaults.standard.set(pinOrder, forKey: "pinOrder")

        // Единственное published-изменение: новый порядок и новый флаг видны
        // SwiftUI одновременно, поэтому строка едет на место одним движением.
        if let idx {
            var updated = chats
            updated[idx].pinned = v
            withAnimation(.spring(response: 0.55, dampingFraction: 0.9)) {
                chats = updated
            }
        }
        // Диск — в фоне, без повторной анимации (порядок в памяти уже верный).
        await core.setPinnedFlag(id, v)
    }

    /// Отметить чат прочитанным. Второе действие левого свайпа — как в Telegram,
    /// где слева стоят «Не прочитан» и «Закрепить».
    func markRead(_ peer: String) async {
        let id = peer.lowercased()
        await core.clearUnread(id)
        if let idx = chats.firstIndex(where: { $0.peerId == id }) {
            var updated = chats
            updated[idx].unread = 0
            chats = updated
        }
        totalUnread = await core.totalUnread()
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
        // Идентификатор создаём ЗДЕСЬ и больше не меняем никогда.
        //
        // Раньше клиент клал сообщение с локальным id и подменял его серверным.
        // Так нельзя: при смене маршрута (не подтвердился Bluetooth — ушло через
        // сервер) id сменился бы, и получатель не распознал бы дубликат. Сам id
        // едет внутри шифрованного payload, поэтому одинаков для всех копий и
        // всех транспортов, а сервер его не видит.
        let mid = newMessageId()
        let body = withMessageId(payload, mid)
        let ts = Int64(Date().timeIntervalSince1970 * 1000)
        let stored = StoredMessage(id: mid, peerId: peer.lowercased(), outgoing: true,
                                   senderId: myId.lowercased(), payloadJson: body,
                                   status: 0, ts: ts, reactionsJson: "{}", edited: false, deleted: false)
        Task {
            await core.insertMessage(stored)
            await core.touchChat(peer: peer.lowercased(), isGroup: isGroup, title: peer,
                                 lastText: Wire.preview(body), lastTs: ts, incUnread: false)
            await refreshChats()
            inboxTick.fire()
            await deliverPending(localId: mid, peer: peer, payload: body, isGroup: isGroup)
        }
        return mid
    }

    /// Вписать логический id в payload. Если payload вдруг не разобрался —
    /// отправляем как есть: потеря дедупликации хуже, чем неотправленное
    /// сообщение, но ронять отправку из-за этого нельзя.
    private func withMessageId(_ payload: String, _ mid: String) -> String {
        (try? payloadWithMessageId(payloadJson: payload, messageId: mid)) ?? payload
    }

    /// Отдать сообщение маршрутизатору. Журнал попыток и запись маршрута
    /// ведёт он же — здесь остаётся только статус в истории чата.
    private func deliverPending(localId: String, peer: String, payload: String, isGroup: Bool) async {
        if router == nil { rebuildRouter() }
        guard let router else { await core.updateStatus(localId, -1); inboxTick.fire(); return }

        let outgoing = OutgoingMessage(messageId: localId, recipient: peer,
                                       isGroup: isGroup, payloadJson: payload)
        switch await router.deliver(outgoing) {
        case .success:
            // Идентификатор не трогаем: он тот же, что был при создании,
            // независимо от того, какой маршрут сработал.
            await core.updateStatus(localId, 1)
        case .failure:
            await core.updateStatus(localId, -1)
        }
        inboxTick.fire()
    }

    func retryMessage(_ message: ChatMessage, isGroup: Bool) {
        // Признак неотправленного — статус. Раньше проверялся ещё и префикс
        // local_, но идентификаторы больше не переименовываются, и префикса
        // не существует.
        guard message.status == -1 else { return }
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
        // Тот же сквозной идентификатор, что и у текста: он же служит ключом
        // кэша медиа, пока файл не загружен и настоящего file_id ещё нет.
        let localId = newMessageId()
        let ts = Int64(Date().timeIntervalSince1970 * 1000)
        let size = Int64(data.count)
        // Оптимистичный payload: file_id = localId, ключей ещё нет — превью берётся из кэша по localId.
        let payload0 = withMessageId(Wire.media(fileId: localId, symKey: "", mimeType: mime, nonce: "",
                                                kind: kind, duration: duration, fileName: fileName,
                                                fileSize: size, caption: caption,
                                                replyToId: replyToId, replyToText: replyToText), localId)
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
                let payload = withMessageId(Wire.media(fileId: up.fileId, symKey: up.symKey, mimeType: mime,
                                                       nonce: up.nonce, kind: kind, duration: duration,
                                                       fileName: fileName, fileSize: size, caption: caption,
                                                       replyToId: replyToId, replyToText: replyToText), localId)
                await core.updatePayload(localId, payload)   // финальный payload без пометки «изменено»
                await deliverPending(localId: localId, peer: peer,
                                     payload: payload, isGroup: isGroup)
            } catch {
                // Сюда попадает только сбой загрузки файла: доставку
                // отрабатывает deliverPending и статус ставит сам.
                await core.updateStatus(localId, -1)
            }
            await refreshChats(); inboxTick.fire()
        }
    }

    // MARK: - TOFU olm-identity (SEC HIGH-2)

    /// Непринятая смена olm-ключа собеседника (peerKey) — баннер в чате.
    func pendingOlmKeyChange(for peerId: String) async -> String? {
        await core.pendingKeyChange(for: peerId)
    }

    /// Вид тревоги: у смены ключа аккаунта и неподписанного устройства
    /// разные последствия, поэтому и тексты в баннере разные.
    func pendingOlmAlertKind(for peerId: String) async -> CoreClient.KeyAlertKind? {
        await core.pendingKeyAlertKind(for: peerId)
    }

    /// Явное принятие нового ключа; отклонённые входящие вскроются следующим
    /// поллингом, упавшие исходящие пользователь ретраит с самого сообщения.
    @discardableResult
    func acceptNewOlmKey(peerKey: String) async -> Bool {
        do {
            try await core.acceptNewOlmKey(peerKey: peerKey)
            inboxTick.fire()
            return true
        } catch {
            return false
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
        for m in pending {
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
                case .audio, .file: break   // музыка и документы — только вручную
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
        let fid = profiles[id]?.avatarFileId ?? groups.info(id)?.avatarFileId ?? avatarIds[id]
        guard let fid, !fid.isEmpty else { return nil }
        return URL(string: "\(ServerContext.origin)/avatars/\(fid)")
    }

    func statusEmoji(_ peer: String) -> String? {
        let s = statusEmojis[peer.lowercased()]
        return (s?.isEmpty ?? true) ? nil : s
    }

    /// Отображаемое имя собеседника: display name из профиля, иначе title чата.
    func displayName(_ peer: String, fallback: String) -> String {
        let id = peer.lowercased()
        if let dn = profiles[id]?.displayName, !dn.isEmpty { return dn }
        return fallback.isEmpty ? id : fallback
    }

    /// Принудительно обновить статус (вход в чат/профиль — без ожидания кэша).
    func refreshStatusEmoji(_ peer: String) {
        let id = peer.lowercased()
        guard !id.isEmpty, !id.hasPrefix("grp_"), !id.hasPrefix("group_"),
              !id.hasPrefix("chn_"), !id.hasPrefix("channel_") else { return }
        Task { statusEmojis[id] = await ProfileHTTP.statusEmoji(id) ?? "" }
    }

    private var profileRequests: Set<String> = []
    private var statusRequests: Set<String> = []

    /// Догрузить профиль для строки списка, если его ещё нет в кэше.
    /// Групповые id пропускаем — у групп нет пользовательского профиля.
    func ensureProfile(_ peer: String) {
        let id = peer.lowercased()
        guard !id.isEmpty,
              !id.hasPrefix("group_"), !id.hasPrefix("channel_"),
              !id.hasPrefix("grp_"), !id.hasPrefix("chn_") else { return }
        // Статус тянем НЕЗАВИСИМО от кэша профиля: профиль мог загрузиться
        // раньше (для presence), а статус — новое поле.
        if statusEmojis[id] == nil, !statusRequests.contains(id) {
            statusRequests.insert(id)
            Task { statusEmojis[id] = await ProfileHTTP.statusEmoji(id) ?? "" }
        }
        guard profiles[id] == nil, !profileRequests.contains(id) else { return }
        profileRequests.insert(id)
        Task { await loadProfile(id) }
    }

    func isOnline(_ peer: String) -> Bool {
        guard let iso = profiles[peer.lowercased()]?.lastActive else { return false }
        guard let d = Self.isoFractional.date(from: iso) ?? Self.isoBasic.date(from: iso) else { return false }
        return Date().timeIntervalSince(d) < 75
    }

    private static let presenceClock: DateFormatter = {
        let f = DateFormatter(); f.locale = .current; f.dateFormat = "HH:mm"; return f
    }()
    private static let presenceDate: DateFormatter = {
        let f = DateFormatter(); f.locale = .current; f.dateFormat = "dd.MM.yyyy"; return f
    }()

    /// Telegram-стиль: «в сети», «только что», «N мин назад», дальше — точное
    /// время: «сегодня в 14:20», «вчера в 9:15», «был(а) 12.05.2026».
    func presenceText(_ peer: String) -> String {
        if isOnline(peer) { return String(localized: "в сети") }
        guard let iso = profiles[peer.lowercased()]?.lastActive,
              let d = Self.isoFractional.date(from: iso) ?? Self.isoBasic.date(from: iso) else { return "" }
        let mins = Int(Date().timeIntervalSince(d) / 60)
        if mins < 1 { return String(localized: "был(а) только что") }
        if mins < 60 {
            return String(format: String(localized: "был(а) %lld мин назад"), mins)
        }
        let cal = Calendar.current
        if cal.isDateInToday(d) {
            return String(format: String(localized: "был(а) сегодня в %@"), Self.presenceClock.string(from: d))
        }
        if cal.isDateInYesterday(d) {
            return String(format: String(localized: "был(а) вчера в %@"), Self.presenceClock.string(from: d))
        }
        return String(format: String(localized: "был(а) %@"), Self.presenceDate.string(from: d))
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
