import Foundation

// Общий интерфейс транспорта.
//
// Смысл слоя: сообщение не принадлежит транспорту. Одно и то же сообщение с
// одним и тем же идентификатором может уйти по Bluetooth, не дождаться
// подтверждения и уехать через сервер — оставшись тем же сообщением.
// Поэтому движок сообщений не знает ни про CoreBluetooth, ни про HTTP.
//
// docs/TRANSPORT_LAYER_DESIGN.md, разделы 2 и 4.

/// Сообщение, отданное на доставку.
///
/// Payload здесь ещё не зашифрован — и это осознанно, вопреки первому наброску
/// проекта. Шифрование Olm идёт ПОД КАЖДОЕ устройство получателя, а набор
/// достижимых устройств у каждого транспорта свой: по Bluetooth рядом лежит
/// один телефон, через сервер доступны все. Значит запечатывать конверт может
/// только сам транспорт, зная свой набор адресатов.
///
/// Роутер при этом в содержимое не заглядывает: для него это непрозрачная
/// строка, которую он передаёт дальше.
struct OutgoingMessage {
    let messageId: String
    let recipient: String
    let isGroup: Bool
    let payloadJson: String
}

/// Чем и как сообщение действительно ушло.
struct DeliveryProof {
    let transport: String
    /// Физический канал, если он отличается от транспорта: bluetooth, wifi_aware, lan.
    let physical: String?
    /// Какой сервер участвовал. nil — сервер не использовался вообще.
    let serverId: String?
    /// Оставил ли сервер копию у себя, а не просто передал и забыл.
    let serverStored: Bool
    let deliveredTs: Int64?
}

struct TransportCapabilities {
    let maxPayloadBytes: Int
    let supportsLargeMedia: Bool
    let supportsReceipts: Bool
    /// Нужен ли для работы сервер.
    ///
    /// Именно по этому флагу отсекаются маршруты в режиме «только напрямую» —
    /// не по списку имён. Добавили новый транспорт: он сам попадает в нужную
    /// половину, и о нём не надо вспоминать в другом месте.
    let requiresServer: Bool
    /// Видит ли сервер факт переписки (кто, кому, когда), даже не видя текста.
    let leaksMetadataToServer: Bool
    let isMetered: Bool
}

enum Reachability {
    case reachable(quality: RouteQuality)
    /// Достижим, но нужно сначала поискать: Bluetooth-сканирование, установка сессии.
    case reachableAfterDiscovery(estimate: TimeInterval)
    case unreachable(reason: String)
}

/// Оценка маршрута для выбора. Меньше — лучше по задержке, больше — лучше по ширине.
struct RouteQuality {
    let latency: TimeInterval
    let bandwidthHint: Int
    /// Чем больше, тем охотнее маршрут выбирается при прочих равных.
    let preference: Int
}

enum TransportError: Error {
    case unreachable(String)
    case rejected(String)
    case timeout
    case failed(String)

    /// Как назвать исход в журнале попыток.
    var outcome: String {
        switch self {
        case .unreachable: return "unreachable"
        case .rejected: return "rejected"
        case .timeout: return "timeout"
        case .failed: return "error"
        }
    }
}

protocol TransportAdapter {
    /// Устойчивый идентификатор: "server.<server_id>", "nearby.ble", "nearby.wifi".
    var id: String { get }
    var capabilities: TransportCapabilities { get }

    /// Достижим ли получатель прямо сейчас. Должно быть дёшево и без побочных
    /// эффектов: роутер зовёт это перед каждой отправкой.
    func canReach(_ recipient: String, isGroup: Bool) async -> Reachability

    /// Доставить. Успех обязан означать подтверждение, а не «отправили в сеть».
    func send(_ message: OutgoingMessage) async throws -> DeliveryProof

    func cancel(messageId: String) async
}

extension TransportAdapter {
    func cancel(messageId: String) async {}
}
