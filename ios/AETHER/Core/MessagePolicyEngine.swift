import Foundation

// Что разрешено конкретному сообщению.
//
// Доставка и хранение — РАЗНЫЕ вещи, и это не педантизм. Возможны все четыре
// сочетания: доставить напрямую и не хранить нигде; доставить напрямую и
// оставить шифрованную копию; доставить через сервер, но не хранить; доставить
// через сервер и синхронизировать. Смешаешь их в один флаг — и половина
// сценариев станет невыразимой.
//
// docs/TRANSPORT_LAYER_DESIGN.md, разделы 3.2, 7 и 44.

/// Что серверу позволено сделать с конвертом.
enum ServerStorage: String {
    /// Не отдавать серверу вообще.
    case never = "NEVER"
    /// Передать и удалить сразу после подтверждения получателем.
    case relayOnly = "RELAY_ONLY"
    /// Хранить шифрованную копию для синхронизации между устройствами.
    case encryptedBackup = "ENCRYPTED_BACKUP"
    /// Спрашивать каждый раз.
    case ask = "ASK"

    /// Чем больше, тем строже. Нужно для каскада: побеждает строгое.
    var restrictiveness: Int {
        switch self {
        case .never: return 3
        case .relayOnly: return 2
        case .ask: return 1
        case .encryptedBackup: return 0
        }
    }
}

extension DeliveryMode {
    /// Чем больше, тем меньше сервера. Побеждает строгое — см. правило каскада.
    var restrictiveness: Int {
        switch self {
        case .directOnly: return 3
        case .directPlusBackup: return 2
        case .auto: return 1
        case .server: return 0
        }
    }
}

struct EffectivePolicy {
    var deliveryMode: DeliveryMode
    var serverStorage: ServerStorage
    /// Порядок транспортов, заданный пользователем.
    var transportOrder: [String]
    /// Почему режим оказался строже запрошенного — для честного текста в UI.
    var restrictedBy: String?

    /// Разрешено ли этому сообщению уходить через сервер.
    var allowsServer: Bool { deliveryMode.allowsServer && serverStorage != .never }
}

@MainActor
final class MessagePolicyEngine {
    private let core: CoreClient

    init(core: CoreClient) {
        self.core = core
    }

    /// Итоговая политика для сообщения.
    ///
    /// Каскад: настройки чата → запрет категории на сервере → разовый выбор
    /// пользователя. На каждом шаге **побеждает более строгое**: разовый выбор
    /// может только ужесточить, но не ослабить.
    func policy(peer: String, contentKind: String,
                override: DeliveryMode? = nil) async -> EffectivePolicy {
        let stored = await core.chatPolicy(peer)
        var mode = DeliveryMode(rawValue: stored.deliveryMode) ?? .auto
        var storage = ServerStorage(rawValue: stored.serverStorage) ?? .encryptedBackup
        var reason: String?

        // Категория, запрещённая к отправке на этот сервер, обрезает маршрут
        // до локального — независимо от того, что выбрано в чате.
        let serverId = ServerContext.serverId
        if await !core.serverAllows(serverId: serverId, contentKind: contentKind) {
            if mode.restrictiveness < DeliveryMode.directOnly.restrictiveness {
                mode = .directOnly
                reason = "этот тип содержимого запрещено передавать серверу"
            }
            if storage.restrictiveness < ServerStorage.never.restrictiveness {
                storage = .never
            }
        }

        // Разовый выбор — только в сторону строгости.
        if let override, override.restrictiveness > mode.restrictiveness {
            mode = override
            reason = "выбрано для этого сообщения"
        }

        let order: [String]
        if let raw = stored.transportOrder, let data = raw.data(using: .utf8),
           let parsed = try? JSONDecoder().decode([String].self, from: data) {
            order = parsed
        } else {
            order = []
        }

        return EffectivePolicy(deliveryMode: mode, serverStorage: storage,
                               transportOrder: order, restrictedBy: reason)
    }

    func setChatPolicy(peer: String, mode: DeliveryMode, storage: ServerStorage,
                       transportOrder: [String] = []) async {
        let orderJson: String?
        if transportOrder.isEmpty {
            orderJson = nil
        } else {
            orderJson = (try? JSONEncoder().encode(transportOrder))
                .flatMap { String(data: $0, encoding: .utf8) }
        }
        await core.setChatPolicy(ChatDeliveryPolicy(
            peerId: peer.lowercased(),
            deliveryMode: mode.rawValue,
            transportOrder: orderJson,
            serverStorage: storage.rawValue,
            updatedTs: Int64(Date().timeIntervalSince1970 * 1000)))
    }

    /// Категория содержимого по payload — для проверки серверной политики.
    static func contentKind(_ payloadJson: String) -> String {
        guard let p = Wire.parse(payloadJson) else { return "text" }
        guard p.type == "media" else { return "text" }
        switch p.mediaKind {
        case .image: return "image"
        case .video, .videoNote: return "video"
        case .voice, .audio: return "voice"
        case .file: return "file"
        }
    }
}
