import Foundation
import SwiftUI

// Исчезающие сообщения и «просмотр один раз».
//
// Отсчёт ведётся НА КАЖДОМ УСТРОЙСТВЕ независимо, по спецификации из конверта.
// Спрашивать разрешения у сервера нельзя: сообщение могло вообще не проходить
// через сервер, а у режима «только напрямую» это прямо запрещено.
//
// Что здесь честно, а что нет: приложение перестаёт показывать содержимое и
// стирает его из своей базы. Оно НЕ мешает человеку переписать текст рукой или
// снять экран другим телефоном, и интерфейс такого не обещает.
//
// docs/TRANSPORT_LAYER_DESIGN.md, раздел 10.

@MainActor
final class EphemeralManager: ObservableObject {
    private let core: CoreClient
    /// Тикает, когда что-то истекло — чтобы чат перерисовался.
    let changed = PassthroughSubjectLike()

    private var sweepTask: Task<Void, Never>?

    init(core: CoreClient) {
        self.core = core
    }

    deinit { sweepTask?.cancel() }

    // MARK: - Постановка на учёт

    /// Взять сообщение под наблюдение сразу после появления в базе.
    ///
    /// Триггеры SENT, DELIVERED и ABSOLUTE запускают отсчёт немедленно —
    /// открытие получателем для них роли не играет. FIRST_OPEN и CLOSE ждут
    /// действия человека, поэтому срок у них пока не определён.
    func track(messageId: String, payloadJson: String, sentTs: Int64) {
        guard let spec = ephemeralFromPayload(payloadJson: payloadJson) else { return }
        let expires: Int64?
        switch spec.trigger {
        case .sent:
            expires = sentTs + spec.ttlSeconds * 1000
        case .delivered:
            // Момент доставки известен из маршрута; пока его нет, считаем от
            // отправки — иначе сообщение зависло бы без срока навсегда.
            expires = sentTs + spec.ttlSeconds * 1000
        case .absolute:
            expires = spec.absoluteMs
        case .firstOpen, .close:
            expires = nil
        }
        Task {
            await core.ephemeralSet(EphemeralState(
                messageId: messageId,
                state: expires == nil ? "UNOPENED" : "COUNTDOWN",
                openedTs: nil, expiresTs: expires, views: 0))
        }
    }

    /// Человек открыл сообщение: запускаем отсчёт и считаем просмотр.
    ///
    /// Возвращает true, если содержимое можно показывать. False означает, что
    /// лимит просмотров исчерпан — «просмотр один раз» уже использован.
    @discardableResult
    func open(messageId: String, payloadJson: String) async -> Bool {
        guard let spec = ephemeralFromPayload(payloadJson: payloadJson) else { return true }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let current = await core.ephemeralGet(messageId)

        if let limit = spec.viewLimit, let current, current.views >= limit {
            return false
        }
        if current?.state == "PURGED" { return false }

        // Для «просмотр один раз» без явного срока содержимое живёт ровно до
        // закрытия просмотра, поэтому даём короткий запас, а не бесконечность.
        let ttl = spec.ttlSeconds > 0 ? spec.ttlSeconds : (spec.viewLimit != nil ? 0 : 0)
        let expires: Int64?
        switch spec.trigger {
        case .firstOpen:
            expires = current?.expiresTs ?? (ttl > 0 ? now + ttl * 1000 : nil)
        case .close:
            expires = current?.expiresTs        // срок поставит closeView
        default:
            expires = current?.expiresTs
        }

        await core.ephemeralSet(EphemeralState(
            messageId: messageId, state: expires == nil ? "OPENED" : "COUNTDOWN",
            openedTs: current?.openedTs ?? now, expiresTs: expires,
            views: (current?.views ?? 0) + 1))
        return true
    }

    /// Просмотр закрыт: для триггера CLOSE и для «одного раза» это конец.
    func closeView(messageId: String, payloadJson: String) async {
        guard let spec = ephemeralFromPayload(payloadJson: payloadJson) else { return }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let current = await core.ephemeralGet(messageId)
        guard current?.state != "PURGED" else { return }

        var expires = current?.expiresTs
        if spec.trigger == .close {
            expires = now + spec.ttlSeconds * 1000
        }
        // «Просмотр один раз» после закрытия недоступен независимо от срока.
        if let limit = spec.viewLimit, (current?.views ?? 0) >= limit {
            await purge(messageId)
            return
        }
        if let expires {
            await core.ephemeralSet(EphemeralState(
                messageId: messageId, state: "COUNTDOWN",
                openedTs: current?.openedTs ?? now, expiresTs: expires,
                views: current?.views ?? 0))
        }
    }

    // MARK: - Вычистка

    /// Периодический проход. Запускается вместе с чатами и работает, пока
    /// приложение активно; при возврате из фона первый проход догоняет всё,
    /// что истекло, пока нас не было.
    func startSweeping() {
        sweepTask?.cancel()
        sweepTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.sweepOnce()
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
        }
    }

    func stopSweeping() {
        sweepTask?.cancel()
        sweepTask = nil
    }

    func sweepOnce() async {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let due = await core.ephemeralDue(now)
        guard !due.isEmpty else { return }
        for id in due { await purge(id) }
        changed.fire()
    }

    /// Стереть содержимое: и в базе, и в кэше медиа на устройстве.
    private func purge(_ messageId: String) async {
        // Медиа стираем ДО базы: после вычистки payload ключей уже не будет,
        // и файл остался бы лежать в кэше навсегда.
        if let message = await core.getMessage(messageId),
           let payload = Wire.parse(message.payloadJson),
           payload.type == "media", let fileId = payload.fileId {
            await MediaStore.shared.remove(fileId: fileId)
        }
        await core.ephemeralPurge(messageId)
        changed.fire()
    }

    /// Сколько осталось до исчезновения, в секундах. nil — отсчёт не идёт.
    func remaining(_ state: EphemeralState?) -> Int? {
        guard let state, let expires = state.expiresTs, state.state != "PURGED" else { return nil }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return max(0, Int((expires - now) / 1000))
    }
}

/// Простейший «сигнал изменения» без Combine: у проекта уже есть такой приём
/// (inboxTick в Messaging), держимся его.
@MainActor
final class PassthroughSubjectLike: ObservableObject {
    @Published private(set) var tick: Int = 0
    func fire() { tick &+= 1 }
}
