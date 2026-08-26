package org.groktest.securemessenger.data

import kotlinx.coroutines.CancellationException

/** Возможности транспорта нужны политике; выбор не зависит от имени адаптера. */
data class TransportCapabilities(
    val requiresServer: Boolean,
    val leaksMetadataToServer: Boolean,
)

enum class Reachability { REACHABLE, UNREACHABLE }

data class DeliveryRequest(
    val messageId: String?,
    val peerId: String,
    val contentKind: String,
    /** Шифрование остаётся внутри выбранного транспорта: fan-out зависит от устройств. */
    val sealAndSend: suspend () -> String,
    /**
     * Исходная нагрузка для прямых транспортов. Серверный путь запечатывает
     * внутри sealAndSend, а Bluetooth обязан сделать это сам: связку он берёт
     * у собеседника напрямую, без сервера.
     */
    val wire: String? = null,
)

data class DeliveryProof(
    val messageId: String,
    val transport: String,
    val serverId: String?,
    val serverStored: Boolean,
)

interface TransportAdapter {
    val id: String
    val serverId: String?
    val capabilities: TransportCapabilities
    fun canReach(peerId: String): Reachability
    suspend fun send(request: DeliveryRequest): DeliveryProof
}

class ServerTransport(override val serverId: String) : TransportAdapter {
    override val id: String = "server.$serverId"
    override val capabilities = TransportCapabilities(
        requiresServer = true,
        leaksMetadataToServer = true,
    )

    override fun canReach(peerId: String): Reachability = Reachability.REACHABLE

    override suspend fun send(request: DeliveryRequest): DeliveryProof {
        val serverMessageId = request.sealAndSend()
        return DeliveryProof(
            // Логический mid не меняется при смене транспорта. Серверный id —
            // лишь идентификатор конкретного конверта и наружу не протекает.
            messageId = request.messageId ?: serverMessageId,
            transport = id,
            serverId = serverId,
            // Текущий relay API хранит сообщение до ACK. Не выдаём желаемую политику за факт.
            serverStored = true,
        )
    }
}

class WaitingForNearbyException : IllegalStateException(
    "Сервер запрещён политикой; сообщение ждёт прямого транспорта"
)

/** Первый этап роутера: один серверный адаптер, поэтому обычное поведение не меняется. */
class TransportRouter(
    private val store: CoreStore,
    initialAdapters: List<TransportAdapter>,
) {
    /** Прямые транспорты подключаются позже сервера: им нужны разрешения. */
    private val adapters = java.util.concurrent.CopyOnWriteArrayList(initialAdapters)

    fun register(adapter: TransportAdapter) {
        if (adapters.none { it.id == adapter.id }) adapters += adapter
    }

    fun requireAvailableRoute(peerId: String, contentKind: String = "text") {
        if (allowedAdapters(peerId, contentKind).isEmpty()) throw WaitingForNearbyException()
    }

    suspend fun send(request: DeliveryRequest): String {
        val allowed = allowedAdapters(request.peerId, request.contentKind)
        if (allowed.isEmpty()) throw WaitingForNearbyException()

        var lastError: Exception? = null
        for (adapter in allowed) {
            if (adapter.canReach(request.peerId) != Reachability.REACHABLE) continue
            val started = System.currentTimeMillis()
            val attempt = request.messageId?.let {
                store.addDeliveryAttempt(it, adapter.id, started)
            }
            try {
                val proof = adapter.send(request)
                if (attempt != null) {
                    val messageId = requireNotNull(request.messageId)
                    store.finishDeliveryAttempt(messageId, attempt, "ok", System.currentTimeMillis())
                    store.setMessageRoute(
                        messageId = messageId,
                        transport = proof.transport,
                        serverId = proof.serverId,
                        serverStored = proof.serverStored,
                    )
                }
                return proof.messageId
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
                if (attempt != null) {
                    val messageId = requireNotNull(request.messageId)
                    store.finishDeliveryAttempt(
                        messageId,
                        attempt,
                        "error",
                        System.currentTimeMillis(),
                    )
                }
            }
        }
        throw lastError ?: IllegalStateException("Нет доступного транспорта")
    }

    private fun allowedAdapters(peerId: String, contentKind: String): List<TransportAdapter> {
        val policy = store.chatDeliveryPolicy(peerId)
        return adapters.filter { adapter ->
            val serverForbidden = policy.deliveryMode == "DIRECT_ONLY" ||
                policy.serverStorage == "NEVER"
            val categoryForbidden = adapter.serverId?.let {
                !store.serverAllows(it, contentKind)
            } ?: false
            !(serverForbidden && adapter.capabilities.requiresServer) && !categoryForbidden
        }
    }
}

data class MessageRouteInfo(
    val transport: String,
    val physical: String?,
    val serverId: String?,
    val serverStored: Boolean,
    val deliveredTs: Long?,
    val readTs: Long?,
)

data class DeliveryAttemptInfo(
    val attempt: Int,
    val transport: String,
    val startedTs: Long,
    val finishedTs: Long?,
    val outcome: String?,
)

data class MessageDeliveryInfo(
    val route: MessageRouteInfo?,
    val attempts: List<DeliveryAttemptInfo>,
)

data class DeliveryPolicySnapshot(
    val deliveryMode: String,
    val serverStorage: String,
)
