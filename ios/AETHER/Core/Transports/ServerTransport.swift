import Foundation

// Доставка через сервер активного пространства — официальный он или свой.
//
// Обёртка над тем, что и так работало: шифрование Olm с рассылкой по всем
// устройствам получателя и POST /messages. Смысл обёртки не в новом поведении,
// а в том, чтобы сервер стал ОДНИМ ИЗ транспортов, а не единственным способом
// вообще что-либо отправить.
struct ServerTransport: TransportAdapter {
    private let core: CoreClient
    private let serverId: String

    init(core: CoreClient, serverId: String) {
        self.core = core
        self.serverId = serverId
    }

    var id: String { "server.\(serverId)" }

    var capabilities: TransportCapabilities {
        TransportCapabilities(
            maxPayloadBytes: 50 * 1024 * 1024,   // совпадает с MAX_UPLOAD_BYTES сервера
            supportsLargeMedia: true,
            supportsReceipts: true,
            requiresServer: true,
            leaksMetadataToServer: true,
            isMetered: false
        )
    }

    func canReach(_ recipient: String, isGroup: Bool) async -> Reachability {
        // Честная проверка достижимости сервера — это отдельная работа
        // (пинг, состояние сокета, офлайн-режим пространства). Пока сервер
        // считается достижимым, а реальный отказ приходит уже из send:
        // так же, как это работало до появления роутера.
        .reachable(quality: RouteQuality(latency: 0.3, bandwidthHint: 1_000_000, preference: 50))
    }

    func send(_ message: OutgoingMessage) async throws -> DeliveryProof {
        do {
            if message.isGroup {
                guard let key = await core.groupKey(message.recipient.lowercased()) else {
                    throw TransportError.failed("нет ключа группы")
                }
                _ = try await core.sendGroup(groupId: message.recipient, groupKey: key,
                                             wirePayload: message.payloadJson,
                                             clientId: message.messageId)
            } else {
                _ = try await core.sendDirect(to: message.recipient,
                                              wirePayload: message.payloadJson,
                                              clientId: message.messageId)
            }
        } catch let e as TransportError {
            throw e
        } catch {
            // Ошибку ядра не переводим в текст здесь: интерфейсу нужен разбор
            // по типу, а не строка. Оборачиваем, сохраняя описание.
            throw TransportError.failed(String(describing: error))
        }

        return DeliveryProof(
            transport: id,
            physical: nil,
            serverId: serverId,
            // Сервер держит конверт до подтверждения получателем. Режим
            // «только передать и забыть» появится вместе с политиками хранения.
            serverStored: true,
            deliveredTs: nil
        )
    }
}
