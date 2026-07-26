package aether.desktop.crypto

import aether.desktop.data.DesktopCoreStore
import aether.desktop.data.PinnedKeyEntity
import java.security.MessageDigest

/**
 * P6: TOFU-пиннинг публичных ключей (порт Android KeyTrustStore, та же семантика):
 *  - первый контакт — пиним ключ;
 *  - исходящие: [keyForSending] бросает [KeyChangedException] при расхождении;
 *  - входящие: [checkIncoming] возвращает MISMATCH — сообщение отклоняется;
 *  - перепин — только явный [acceptServerKey].
 */
class KeyTrustStore(
    private val dao: DesktopCoreStore,
    private val namespace: String = "",
) {

    @Volatile
    var keyFetcher: ((String) -> String)? = null

    var onKeyAccepted: (suspend (String) -> Unit)? = null

    private fun storageId(peerId: String): String {
        val id = peerId.lowercase()
        return if (namespace.isBlank()) id else "$namespace:$id"
    }

    private suspend fun storedPin(peerId: String) = dao.pinGet(storageId(peerId))

    private suspend fun savePin(peerId: String, pin: PinnedKeyEntity) =
        dao.pinUpsert(pin.copy(peerId = storageId(peerId)))

    class KeyChangedException(
        val peerId: String,
        val pinnedKeyB64: String,
        val serverKeyB64: String,
    ) : IllegalStateException(
        "Ключ собеседника $peerId изменился! Это может быть смена устройства или атака. " +
            "Откройте «Цифры безопасности» в чате, чтобы сверить ключи или принять новый."
    )

    enum class IncomingTrust { OK_PINNED, OK_TOFU, MISMATCH }

    suspend fun keyForSending(peerId: String): String {
        val id = peerId.lowercase()
        val fetch = keyFetcher ?: throw IllegalStateException("KeyTrustStore: keyFetcher не установлен")
        return checkForSending(id, fetch(id))
    }

    suspend fun checkForSending(peerId: String, serverKey: String): String {
        val id = peerId.lowercase()
        require(serverKey.isNotBlank()) { "Пустой ключ собеседника" }
        val pin = storedPin(id)
        return when {
            pin == null -> {
                savePin(id, PinnedKeyEntity(id, serverKey, System.currentTimeMillis()))
                serverKey
            }
            pin.publicKeyB64 == serverKey -> serverKey
            else -> {
                savePin(id, pin.copy(previousKeyB64 = pin.publicKeyB64, changedAt = System.currentTimeMillis()))
                throw KeyChangedException(id, pin.publicKeyB64, serverKey)
            }
        }
    }

    suspend fun checkIncoming(senderId: String, envelopeKeyB64: String): IncomingTrust {
        val id = senderId.lowercase()
        require(envelopeKeyB64.isNotBlank()) { "Пустой ключ отправителя" }
        val pin = storedPin(id)
        return when {
            pin == null -> {
                savePin(id, PinnedKeyEntity(id, envelopeKeyB64, System.currentTimeMillis()))
                IncomingTrust.OK_TOFU
            }
            pin.publicKeyB64 == envelopeKeyB64 -> IncomingTrust.OK_PINNED
            else -> {
                savePin(id, pin.copy(previousKeyB64 = pin.publicKeyB64, changedAt = System.currentTimeMillis()))
                IncomingTrust.MISMATCH
            }
        }
    }

    suspend fun pinFor(peerId: String): PinnedKeyEntity? {
        val id = peerId.lowercase()
        return storedPin(id)?.copy(peerId = id)
    }

    suspend fun acceptServerKey(peerId: String): String {
        val id = peerId.lowercase()
        val fetch = keyFetcher ?: throw IllegalStateException("KeyTrustStore: keyFetcher не установлен")
        val serverKey = fetch(id)
        require(serverKey.isNotBlank()) { "Пустой ключ собеседника" }
        val old = storedPin(id)
        savePin(
            id,
            PinnedKeyEntity(
                peerId = id,
                publicKeyB64 = serverKey,
                pinnedAt = System.currentTimeMillis(),
                verified = false,
                previousKeyB64 = old?.publicKeyB64,
                changedAt = old?.changedAt,
            )
        )
        onKeyAccepted?.invoke(id)
        return serverKey
    }

    suspend fun setVerified(peerId: String, verified: Boolean) =
        dao.pinSetVerified(storageId(peerId), verified)

    companion object {
        const val SAFETY_VERSION = "AetherSafety#1"

        /** «Цифры безопасности»: 60 цифр, 12 групп по 5 — одинаковы у обеих сторон. */
        fun safetyNumber(userA: String, keyA: String, userB: String, keyB: String): String {
            val fpA = fingerprint(userA, keyA)
            val fpB = fingerprint(userB, keyB)
            val (first, second) = if (fpA <= fpB) fpA to fpB else fpB to fpA
            return (first + second)
                .chunked(5)
                .joinToString(" ")
        }

        private fun fingerprint(userId: String, publicKeyB64: String): String {
            val digest = MessageDigest.getInstance("SHA-512")
                .digest((SAFETY_VERSION + userId.lowercase() + publicKeyB64).toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(30)
            for (group in 0 until 6) {
                var v = 0L
                for (i in 0 until 5) {
                    v = (v shl 8) or (digest[group * 5 + i].toLong() and 0xFF)
                }
                sb.append((v % 100000).toString().padStart(5, '0'))
            }
            return sb.toString()
        }
    }
}
