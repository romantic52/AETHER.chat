import Foundation

// Выбор маршрута доставки.
//
// Роутер не знает, что внутри сообщения, и не умеет шифровать. Он решает
// ровно одно: куда пробовать доставить и в каком порядке — и честно
// записывает каждую попытку, чтобы потом можно было ответить на вопрос
// «где сейчас моё сообщение».
//
// docs/TRANSPORT_LAYER_DESIGN.md, раздел 4.1.

/// Режим доставки чата. Пока задаётся только значением по умолчанию;
/// экран настроек и хранение приходят следующим этапом.
enum DeliveryMode: String {
    case auto = "AUTO"
    case directOnly = "DIRECT_ONLY"
    case directPlusBackup = "DIRECT_PLUS_BACKUP"
    case server = "SERVER"

    /// Разрешает ли режим маршруты, которым нужен сервер.
    var allowsServer: Bool { self != .directOnly }
    /// Требует ли режим начинать с локальных маршрутов.
    var prefersDirect: Bool { self == .directOnly || self == .directPlusBackup }
}

@MainActor
final class TransportRouter {
    /// Ни одного достижимого маршрута. Отдельный случай, а не просто ошибка:
    /// при DIRECT_ONLY сообщение должно ЖДАТЬ получателя рядом, а не молча
    /// уезжать через сервер.
    enum RoutingFailure: Error {
        case noRouteAvailable(triedButUnreachable: Bool)
        case allRoutesFailed(last: Error)
    }

    private(set) var adapters: [String: TransportAdapter] = [:]
    private let core: CoreClient

    init(core: CoreClient) {
        self.core = core
    }

    func register(_ adapter: TransportAdapter) {
        adapters[adapter.id] = adapter
    }

    func unregister(id: String) {
        adapters.removeValue(forKey: id)
    }

    /// Доставить сообщение, перебирая разрешённые маршруты по порядку.
    ///
    /// `preferredOrder` — заданный пользователем порядок транспортов; то, что
    /// в него не попало, идёт следом по оценке качества.
    func deliver(_ message: OutgoingMessage,
                 mode: DeliveryMode = .auto,
                 preferredOrder: [String] = []) async -> Result<DeliveryProof, RoutingFailure> {
        let allowed = candidates(mode: mode, preferredOrder: preferredOrder)
        guard !allowed.isEmpty else {
            return .failure(.noRouteAvailable(triedButUnreachable: false))
        }

        var lastError: Error?
        var sawUnreachable = false

        for adapter in allowed {
            // Недостижимый маршрут не тратит попытку: в журнале должны быть
            // настоящие попытки доставки, а не результаты опроса.
            if case .unreachable(let reason) = await adapter.canReach(message.recipient,
                                                                      isGroup: message.isGroup) {
                sawUnreachable = true
                lastError = TransportError.unreachable(reason)
                continue
            }

            let attempt = await core.beginAttempt(message.messageId, transport: adapter.id)
            do {
                let proof = try await adapter.send(message)
                await core.endAttempt(message.messageId, attempt: attempt, outcome: "ok")
                await core.setRoute(MessageRoute(
                    messageId: message.messageId,
                    transport: proof.transport,
                    physical: proof.physical,
                    serverId: proof.serverId,
                    serverStored: proof.serverStored,
                    deliveredTs: proof.deliveredTs,
                    readTs: nil))
                return .success(proof)
            } catch {
                let outcome = (error as? TransportError)?.outcome ?? "error"
                await core.endAttempt(message.messageId, attempt: attempt,
                                      outcome: outcome, detail: String(describing: error))
                lastError = error
                // Идём к следующему маршруту: id сообщения при этом НЕ меняется,
                // поэтому у получателя не появится второй копии.
            }
        }

        if let lastError, !sawUnreachable {
            return .failure(.allRoutesFailed(last: lastError))
        }
        return .failure(.noRouteAvailable(triedButUnreachable: sawUnreachable))
    }

    /// Доставить служебное сообщение: квитанцию о доставке или прочтении.
    ///
    /// Отличий от обычной доставки два, и оба принципиальные.
    ///
    /// Первое: журнал не ведётся. Квитанция не лежит в истории, привязывать
    /// к ней маршрут не к чему, а строки в message_delivery_attempts на
    /// несуществующее сообщение только замусорили бы Message Info.
    ///
    /// Второе: если разрешённых маршрутов нет, квитанция просто НЕ УХОДИТ.
    /// Она не встаёт в очередь и никуда не переносится: подтверждение
    /// «прочитано полчаса назад» бесполезно, а вот утечка самого факта
    /// чтения на сервер, которому этот чат запрещён, — вполне реальна.
    /// Свежую квитанцию отправит следующее открытие чата.
    @discardableResult
    func deliverControl(_ message: OutgoingMessage,
                        mode: DeliveryMode = .auto,
                        preferredOrder: [String] = []) async -> Bool {
        for adapter in candidates(mode: mode, preferredOrder: preferredOrder) {
            if case .unreachable = await adapter.canReach(message.recipient,
                                                          isGroup: message.isGroup) { continue }
            if (try? await adapter.send(message)) != nil { return true }
        }
        return false
    }

    /// Маршруты, разрешённые режимом, в порядке предпочтения.
    func candidates(mode: DeliveryMode, preferredOrder: [String] = []) -> [TransportAdapter] {
        // Отсечка по возможностям транспорта, а не по его имени: новый адаптер
        // сам попадёт в нужную половину.
        let allowed = adapters.values.filter { mode.allowsServer || !$0.capabilities.requiresServer }

        let explicit = preferredOrder.compactMap { wanted in
            allowed.first { adapter in adapter.id == wanted }
        }
        let rest = allowed
            .filter { a in !preferredOrder.contains(a.id) }
            .sorted { lhs, rhs in
                // При «сначала напрямую» локальные маршруты идут первыми
                // независимо от оценки качества — это выбор пользователя,
                // а не оптимизация.
                if mode.prefersDirect, lhs.capabilities.requiresServer != rhs.capabilities.requiresServer {
                    return !lhs.capabilities.requiresServer
                }
                return lhs.id < rhs.id
            }
        return explicit + rest
    }
}
