import Foundation

// Обнаружение серверов и доверие к ним.
//
// Разбор адреса, порядок запросов и проверка подписи живут в ядре
// (core/src/discovery.rs) — одинаково для iOS, Android и веба. Здесь только
// то, что специфично для клиента: TOFU-пин, тревога о смене идентификатора
// и запись в реестр.
//
// docs/MULTI_SERVER_DESIGN.md, раздел 16.

@MainActor
final class ServerDirectory: ObservableObject {
    static let shared = ServerDirectory()

    /// Что делать дальше после обнаружения.
    enum Outcome {
        /// Сервер новый — можно добавлять.
        case fresh(ServerInfo)
        /// Сервер уже добавлен и это он же: отпечаток сошёлся.
        case known(ServerInfo, ServerRecord)
        /// Отпечаток или server_id изменились. Молча принимать нельзя.
        case identityChanged(ServerInfo, ServerRecord, old: ServerPin)
    }

    private init() {}

    // MARK: - Обнаружение

    /// Опросить адрес. Блокирующий вызов ядра уводится с главного потока.
    func discover(input: String, allowCleartext: Bool) async throws -> ServerInfo {
        // nonce обязателен: без него подпись доказывает лишь то, что документ
        // когда-то был подписан, а не что это ответ на наш запрос.
        let nonce = Self.freshNonce()
        return try await Task.detached(priority: .userInitiated) {
            try discoverServer(input: input, nonce: nonce, allowCleartext: allowCleartext)
        }.value
    }

    /// Обнаружение + сверка с тем, что мы уже знаем об этом сервере.
    func inspect(input: String, allowCleartext: Bool) async throws -> Outcome {
        let info = try await discover(input: input, allowCleartext: allowCleartext)
        let registry = ServerRegistry.shared

        // Ищем по server_id, а не по адресу: сервер мог переехать на новый
        // домен, оставшись тем же сервером.
        if let existing = registry.server(info.serverId) {
            return matches(info, existing) ? .known(info, existing)
                                           : .identityChanged(info, existing, old: existing.pin!)
        }
        // По адресу знаем другой сервер — значит по этому адресу теперь кто-то
        // другой. Это ровно тот случай, ради которого экран тревоги и нужен.
        if let byOrigin = registry.server(matchingOrigin: info.origin), let pin = byOrigin.pin {
            return .identityChanged(info, byOrigin, old: pin)
        }
        return .fresh(info)
    }

    private func matches(_ info: ServerInfo, _ record: ServerRecord) -> Bool {
        guard let pin = record.pin else { return true }   // пина не было — принимаем как первый
        return pin.serverId == info.serverId && pin.fingerprintB64 == info.fingerprintB64
    }

    // MARK: - Добавление

    @discardableResult
    func add(_ info: ServerInfo, displayName: String? = nil) -> ServerRecord {
        let registry = ServerRegistry.shared
        let now = Date()
        var record = registry.server(info.serverId) ?? ServerRecord(
            id: info.serverId,
            kind: .custom,
            displayName: displayName ?? info.name,
            declaredName: info.name,
            origin: info.origin,
            apiURL: info.apiUrl,
            wsURL: info.websocketUrl
        )
        record.declaredName = info.name
        if let displayName, !displayName.isEmpty { record.displayName = displayName }
        apply(info, to: &record, at: now)
        // Пин ставится ОДИН раз, при первом знакомстве. Дальше он только
        // сверяется; переписывать его молча — значит отменить всю защиту.
        if record.pin == nil {
            record.pin = ServerPin(serverId: info.serverId,
                                   publicKeyB64: info.publicKeyB64,
                                   fingerprintB64: info.fingerprintB64,
                                   firstSeenAt: now,
                                   lastVerifiedAt: now)
        }
        registry.upsert(record)
        return record
    }

    private func apply(_ info: ServerInfo, to record: inout ServerRecord, at now: Date) {
        record.origin = info.origin
        record.apiURL = info.apiUrl
        record.wsURL = info.websocketUrl
        record.protocolVersion = Int(info.protocolVersion)
        record.registrationMode = RegistrationMode(wire: info.registrationMode)
        record.capabilities = info.capabilities
        record.transport = info.cleartext ? .lanCleartext : .tls
        record.lastConnectedAt = now
        record.pin?.lastVerifiedAt = now
    }

    /// Пользователь осознанно принял новый идентификатор сервера.
    ///
    /// Все разрешения на передачу данных при этом сбрасываются: новый ключ —
    /// это, возможно, другая сторона, и прежнее согласие к ней не относится.
    func acceptNewIdentity(for oldRecord: ServerRecord, info: ServerInfo) {
        let registry = ServerRegistry.shared
        let now = Date()
        var history = oldRecord.pin?.changes ?? []
        if let old = oldRecord.pin {
            history.append(ServerPin.PinChange(at: now,
                                               oldServerId: old.serverId,
                                               oldFingerprintB64: old.fingerprintB64,
                                               acceptedByUser: true))
        }
        var record = oldRecord
        record.pin = ServerPin(serverId: info.serverId,
                               publicKeyB64: info.publicKeyB64,
                               fingerprintB64: info.fingerprintB64,
                               firstSeenAt: now,
                               lastVerifiedAt: now,
                               changes: history)
        record.identityAlert = nil
        record.trusted = false
        apply(info, to: &record, at: now)

        if record.id != info.serverId {
            // Сервер переустановили: это другой server_id, а значит другое
            // пространство. Старые аккаунты к нему не относятся.
            registry.remove(serverId: record.id)
            record.id = info.serverId
            record.accounts = []
        }
        registry.upsert(record)
    }

    // MARK: - Периодическая сверка

    /// Тихая проверка личности сервера при старте и переключении пространства.
    /// Ничего не показывает сама: поднимает флаг, а решение принимает человек.
    func refresh(serverId: String) async {
        let registry = ServerRegistry.shared
        guard let record = registry.server(serverId) else { return }
        let allowCleartext = record.transport == .lanCleartext

        guard let info = try? await discover(input: record.origin,
                                             allowCleartext: allowCleartext) else { return }

        if let pin = record.pin,
           pin.serverId != info.serverId || pin.fingerprintB64 != info.fingerprintB64 {
            registry.update(serverId) {
                $0.identityAlert = ServerPin.PinChange(at: Date(),
                                                       oldServerId: pin.serverId,
                                                       oldFingerprintB64: pin.fingerprintB64,
                                                       acceptedByUser: false)
            }
            return
        }

        // Официальный сервер впервые назвал свой настоящий server_id.
        if record.kind == .official, record.id == ServerRegistry.officialPlaceholderId {
            ServerMigration.adoptOfficialServerId(info.serverId)
        }

        let now = Date()
        registry.update(registry.server(info.serverId)?.id ?? serverId) { rec in
            rec.declaredName = info.name
            rec.registrationMode = RegistrationMode(wire: info.registrationMode)
            rec.capabilities = info.capabilities
            rec.apiURL = info.apiUrl
            rec.wsURL = info.websocketUrl
            rec.protocolVersion = Int(info.protocolVersion)
            rec.lastConnectedAt = now
            if rec.pin == nil {
                rec.pin = ServerPin(serverId: info.serverId,
                                    publicKeyB64: info.publicKeyB64,
                                    fingerprintB64: info.fingerprintB64,
                                    firstSeenAt: now,
                                    lastVerifiedAt: now)
            } else {
                rec.pin?.lastVerifiedAt = now
            }
        }
        // Адреса активного пространства могли обновиться вместе с записью.
        if ServerRegistry.shared.activeSpace?.serverId == serverId,
           let updated = ServerRegistry.shared.server(info.serverId) {
            ServerContext.set(origin: updated.origin, apiBase: updated.apiURL,
                              wsEndpoint: updated.wsURL, serverId: updated.id,
                              dbFileName: ServerContext.dbFileName)
        }
    }

    private static func freshNonce() -> String {
        var bytes = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
