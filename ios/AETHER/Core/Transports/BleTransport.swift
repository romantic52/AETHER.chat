import Foundation

// Доставка по Bluetooth как один из транспортов.
//
// Сервера не требует и метаданных ему не отдаёт — ради этого всё и затевалось:
// там, где нет сети, сообщение уходит напрямую, и никакой посредник не узнаёт
// ни кто кому пишет, ни когда.
//
// Достижимость — видим ли мы собеседника прямо сейчас. Пока ключами обнаружения
// не обменялись, опознать конкретного человека нечем, и транспорт честно
// отвечает «не достучаться»: роутер уйдёт на сервер.
struct BleTransport: TransportAdapter {
    private let transport: BleTextTransport
    private let discovery: NearbyDiscoveryService

    init(transport: BleTextTransport, discovery: NearbyDiscoveryService) {
        self.transport = transport
        self.discovery = discovery
    }

    var id: String { "nearby.ble" }

    var capabilities: TransportCapabilities {
        TransportCapabilities(
            // Только текст: по GATT крупное ползёт минутами.
            maxPayloadBytes: 64 * 1024,
            supportsLargeMedia: false,
            // Квитанции по прямому каналу отдельной работы требуют: сейчас
            // подтверждением служит сам факт успешной записи.
            supportsReceipts: false,
            requiresServer: false,
            leaksMetadataToServer: false,
            isMetered: false
        )
    }

    func canReach(_ recipient: String, isGroup: Bool) async -> Reachability {
        // Группы по радио не рассылаем: ключ группы у каждого свой, и веерная
        // отправка рядом стоящим — отдельная задача.
        if isGroup { return .unreachable(reason: "группы по Bluetooth не идут") }
        guard await peripheralId(for: recipient) != nil else {
            return .unreachable(reason: "собеседника нет рядом")
        }
        return .reachable(quality: RouteQuality(latency: 1.5,
                                                bandwidthHint: 20_000,
                                                preference: 80))
    }

    func send(_ message: OutgoingMessage) async throws -> DeliveryProof {
        guard let peripheralId = await peripheralId(for: message.recipient) else {
            throw TransportError.unreachable("собеседника нет рядом")
        }
        try await transport.send(peerId: message.recipient,
                                 peripheralId: peripheralId,
                                 wirePayload: message.payloadJson)
        return DeliveryProof(
            transport: id,
            physical: "bluetooth",
            serverId: nil,
            // Сервера в пути не было — хранить сообщение негде и некому.
            serverStored: false,
            deliveredTs: Int64(Date().timeIntervalSince1970 * 1000)
        )
    }

    @MainActor
    private func peripheralId(for recipient: String) -> UUID? {
        discovery.peers
            .first { $0.identityId?.caseInsensitiveCompare(recipient) == .orderedSame }?
            .peripheralId
    }
}
