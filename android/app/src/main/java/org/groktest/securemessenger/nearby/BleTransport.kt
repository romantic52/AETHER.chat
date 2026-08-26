package org.groktest.securemessenger.nearby

import org.groktest.securemessenger.data.DeliveryProof
import org.groktest.securemessenger.data.DeliveryRequest
import org.groktest.securemessenger.data.Reachability
import org.groktest.securemessenger.data.TransportAdapter
import org.groktest.securemessenger.data.TransportCapabilities

/**
 * Доставка по Bluetooth как один из транспортов.
 *
 * Сервера не требует и метаданных ему не отдаёт — ради этого всё и затевалось:
 * в поезде без интернета сообщение уходит напрямую, и никакой посредник не
 * узнаёт ни кто кому пишет, ни когда.
 *
 * Достижимость определяется тем, видим ли мы собеседника прямо сейчас. Пока
 * ключами обнаружения не обменялись, опознать конкретного человека нельзя, и
 * транспорт честно отвечает «не достучаться» — роутер уйдёт на сервер.
 */
class BleTransport(
    private val transport: BleTextTransport,
    private val discovery: NearbyDiscoveryService,
) : TransportAdapter {

    override val id: String = "ble"
    override val serverId: String? = null

    override val capabilities = TransportCapabilities(
        requiresServer = false,
        leaksMetadataToServer = false,
    )

    override fun canReach(peerId: String): Reachability =
        if (addressOf(peerId) != null) Reachability.REACHABLE else Reachability.UNREACHABLE

    override suspend fun send(request: DeliveryRequest): DeliveryProof {
        val address = addressOf(request.peerId)
            ?: throw BleTextTransport.NotReachableException(request.peerId)
        // Прямой транспорт запечатывает сам: сессия поднимается из связки,
        // взятой у собеседника по радио, а не из ответа сервера.
        val wire = request.wire
            ?: throw IllegalStateException("Bluetooth: нечего отправлять без исходной нагрузки")
        transport.send(request.peerId, address, wire)
        return DeliveryProof(
            messageId = request.messageId ?: java.util.UUID.randomUUID().toString(),
            transport = id,
            serverId = null,
            // Никакого сервера в пути не было — хранить сообщение негде и некому.
            serverStored = false,
        )
    }

    private fun addressOf(peerId: String): String? = discovery.peers.value
        .firstOrNull { it.identityId.equals(peerId, ignoreCase = true) }
        ?.address
}
