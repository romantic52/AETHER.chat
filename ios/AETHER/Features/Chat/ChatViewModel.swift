import SwiftUI

enum ChatTimelineKind {
    case date(String)
    case message(ChatMessage, tail: Bool, showSender: Bool)
}

struct ChatTimelineRow: Identifiable {
    let id: String
    let kind: ChatTimelineKind
}

// Вью-модель диалога. Подготавливает готовую timeline только при изменении данных:
// набор текста больше не перегруппировывает всю историю сообщений на каждый символ.
@MainActor
final class ChatViewModel: ObservableObject {
    private static let dayMonthFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = .current
        formatter.dateFormat = "d MMMM"
        return formatter
    }()
    private static let fullDateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = .current
        formatter.dateFormat = "d MMMM yyyy"
        return formatter
    }()
    @Published private(set) var messages: [ChatMessage] = []
    @Published private(set) var timeline: [ChatTimelineRow] = []
    @Published private(set) var canLoadMore = true
    @Published private(set) var loading = false

    private(set) var peerId = ""
    private(set) var isGroup = false
    private weak var session: Session?
    private weak var messaging: Messaging?
    private var reloadTask: Task<Void, Never>?
    private var reloadPending = false

    private let pageSize: UInt32 = 40

    static var placeholder: ChatViewModel { ChatViewModel() }
    private init() {}

    func bind(peerId: String, isGroup: Bool, session: Session, messaging: Messaging) {
        let id = peerId.lowercased()
        // Уже привязаны к этому же чату — ничего не делаем. Но если SwiftUI
        // переиспользовал вью (navigationDestination) для ДРУГОГО чата, обязаны
        // перепривязаться и сбросить состояние — иначе показываются сообщения
        // старого чата и экран «требует переоткрытия».
        if self.session != nil, self.peerId == id, self.isGroup == isGroup { return }
        reloadTask?.cancel()
        reloadTask = nil
        reloadPending = false
        self.peerId = id
        self.isGroup = isGroup
        self.session = session
        self.messaging = messaging
        messages = []
        timeline = []
        canLoadMore = true
        loading = false
    }

    var myId: String { session?.myId.lowercased() ?? "" }

    func loadInitial() async {
        let page = (try? await session?.core.messages(peer: peerId, beforeTs: 0, limit: pageSize)) ?? []
        apply(page.map(ChatMessage.from))
        canLoadMore = page.count >= Int(pageSize)
    }

    func loadMore() async {
        guard canLoadMore, !loading, let oldest = messages.first else { return }
        loading = true
        let page = (try? await session?.core.messages(peer: peerId, beforeTs: oldest.ts, limit: pageSize)) ?? []
        if page.isEmpty {
            canLoadMore = false
        } else {
            apply(page.map(ChatMessage.from) + messages)
            canLoadMore = page.count >= Int(pageSize)
        }
        loading = false
    }

    private func reloadOnce() async {
        let limit = UInt32(max(Int(pageSize), messages.count))
        let page = (try? await session?.core.messages(peer: peerId, beforeTs: 0, limit: limit)) ?? []
        apply(page.map(ChatMessage.from))
    }

    /// Несколько быстрых inboxTick объединяются максимум в одну дополнительную загрузку.
    func requestReload() {
        if reloadTask != nil {
            reloadPending = true
            return
        }
        reloadTask = Task { [weak self] in
            guard let self else { return }
            repeat {
                self.reloadPending = false
                await self.reloadOnce()
            } while self.reloadPending && !Task.isCancelled
            self.reloadTask = nil
        }
    }

    private func apply(_ next: [ChatMessage]) {
        guard messages != next else { return }

        let oldLast = messages.last?.id
        let oldCount = messages.count
        let newLast = next.last?.id

        let rebuild = {
            self.messages = next
            // Удалённые сообщения просто пропадают из ленты — без плашки
            // "Сообщение удалено" и без пустого места на их месте.
            self.timeline = Self.makeTimeline(next.filter { !$0.deleted }, isGroup: self.isGroup)
        }

        // Первая загрузка — мгновенно, без анимации.
        if oldCount == 0 {
            rebuild()
            return
        }

        // Действительно новое сообщение в конце — анимируем вылет.
        let hasNewAtEnd = next.count > oldCount && newLast != oldLast
        // Реакции меняют высоту пузыря — только этот случай «поднимаем» пружиной.
        // Прочие фоновые релоады (эхо отправки, статусы) — БЕЗ анимации, иначе
        // они накладываются второй анимацией поверх optimistic-вставки.
        let sameIds = next.count == oldCount
            && zip(next, messages).allSatisfy { $0.id == $1.id }
        let reactionsChanged = sameIds
            && zip(next, messages).contains { $0.reactions != $1.reactions }
        if hasNewAtEnd {
            withAnimation(AetherUI.sendAnimation) {
                rebuild()
            }
        } else if reactionsChanged {
            withAnimation(.spring(response: 0.32, dampingFraction: 0.82)) {
                rebuild()
            }
        } else {
            rebuild()
        }
    }

    func send(text: String, replyTo: ChatMessage?) {
        guard let messaging else { return }
        let replyToText = replyTo.flatMap { Wire.preview($0.payloadJson) }
        let localId = messaging.sendText(to: peerId, text: text,
                                         replyToId: replyTo?.id,
                                         replyToText: replyToText,
                                         isGroup: isGroup)
        // Пузырь появляется сразу, синхронно с тапом — не ждём Task→SQLite→
        // inboxTick→полный релоад (этот путь давал видимую паузу между очисткой
        // поля и появлением сообщения). Фоновый релоад затем подтвердит то же
        // состояние из БД (id совпадает), apply() не станет анимировать повторно.
        let payload = Wire.text(text, replyToId: replyTo?.id, replyToText: replyToText)
        let optimistic = ChatMessage(id: localId, peerId: peerId, outgoing: true,
                                     senderId: myId, payloadJson: payload,
                                     status: 0, ts: Int64(Date().timeIntervalSince1970 * 1000),
                                     reactions: [:], edited: false, deleted: false,
                                     payload: Wire.parse(payload))
        withAnimation(AetherUI.sendAnimation) {
            messages.append(optimistic)
            timeline = Self.makeTimeline(messages.filter { !$0.deleted }, isGroup: isGroup)
        }
    }

    func sendMedia(data: Data, mime: String, kind: String, fileName: String?, caption: String? = nil,
                   duration: Double? = nil, replyTo: ChatMessage? = nil) {
        messaging?.sendMedia(to: peerId, data: data, mime: mime, kind: kind,
                             fileName: fileName, caption: caption, duration: duration, isGroup: isGroup,
                             replyToId: replyTo?.id,
                             replyToText: replyTo.flatMap { Wire.preview($0.payloadJson) })
    }

    func react(to message: ChatMessage, emoji: String) {
        let mine = message.reactions.first { $0.value.contains(myId) }?.key
        let newEmoji = mine == emoji ? "" : emoji
        Task { await messaging?.sendReaction(to: peerId, target: message.id, emoji: newEmoji, isGroup: isGroup) }
    }

    func edit(_ message: ChatMessage, newText: String) {
        Task { await messaging?.editMessage(peer: peerId, target: message.id, newText: newText, isGroup: isGroup) }
    }

    func delete(_ message: ChatMessage) {
        Task { await messaging?.deleteMessage(peer: peerId, target: message.id, isGroup: isGroup) }
    }

    func retry(_ message: ChatMessage) {
        messaging?.retryMessage(message, isGroup: isGroup)
    }

    func onAppear() async {
        await loadInitial()
        await messaging?.openChat(peerId, isGroup: isGroup)
        Task { [weak self] in
            guard let self else { return }
            await self.messaging?.loadProfile(self.peerId)
        }
    }

    func onDisappear() {
        reloadTask?.cancel()
        reloadTask = nil
        messaging?.updateTyping(to: peerId, isTyping: false)
        messaging?.closeChat()
    }

    func typingChanged(isEmpty: Bool) {
        messaging?.updateTyping(to: peerId, isTyping: !isEmpty)
    }

    private static func makeTimeline(_ messages: [ChatMessage], isGroup: Bool) -> [ChatTimelineRow] {
        var rows: [ChatTimelineRow] = []
        rows.reserveCapacity(messages.count + max(1, messages.count / 20))
        var lastDay: String?

        for (index, message) in messages.enumerated() {
            let day = dayLabel(message.date)
            if day != lastDay {
                rows.append(ChatTimelineRow(id: "date_\(day)_\(message.id)", kind: .date(day)))
                lastDay = day
            }
            let next = index + 1 < messages.count ? messages[index + 1] : nil
            let sameAuthorNext = next?.senderId == message.senderId
                && next.map { abs($0.ts - message.ts) < 5 * 60_000 } ?? false
            let previous = index > 0 ? messages[index - 1] : nil
            let sameAuthorPrevious = previous?.senderId == message.senderId
                && previous.map { abs(message.ts - $0.ts) < 5 * 60_000 } ?? false
            rows.append(ChatTimelineRow(
                id: message.id,
                kind: .message(message, tail: !sameAuthorNext,
                               showSender: isGroup && !message.outgoing && !sameAuthorPrevious)
            ))
        }
        return rows
    }

    private static func dayLabel(_ date: Date) -> String {
        let calendar = Calendar.current
        if calendar.isDateInToday(date) { return String(localized: "Сегодня") }
        if calendar.isDateInYesterday(date) { return String(localized: "Вчера") }
        return calendar.isDate(date, equalTo: Date(), toGranularity: .year)
            ? dayMonthFormatter.string(from: date)
            : fullDateFormatter.string(from: date)
    }
}
