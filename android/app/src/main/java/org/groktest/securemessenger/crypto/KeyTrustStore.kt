package org.groktest.securemessenger.crypto

import org.groktest.securemessenger.data.CoreStore
import org.groktest.securemessenger.data.PinnedKeyEntity
import java.security.MessageDigest

/**
 * P6: единая точка доверия к публичным ключам (TOFU-пиннинг).
 *
 * Правила:
 *  - Первый контакт с peer'ом — запоминаем (пиним) его ключ.
 *  - Дальше ЛЮБОЕ использование ключа сверяется с пином:
 *      * исходящие: [keyForSending] — при расхождении бросает [KeyChangedException],
 *        отправка блокируется до явного решения пользователя;
 *      * входящие: [checkIncoming] — при расхождении сообщение отклоняется
 *        (вызывающий код показывает плашку-предупреждение).
 *  - Принятие нового ключа — ТОЛЬКО явное действие пользователя ([acceptServerKey],
 *    вызывается с экрана «Цифры безопасности»). Тихих перепинов нет.
 *
 * Каналы (peerId с префиксом "channel_") в схему не входят — у них нет E2E.
 *
 * Как расширять:
 *  - Новый способ получения ключей? Меняйте только [keyFetcher].
 *  - Новая реакция UI на смену ключа? Ловите [KeyChangedException] / [IncomingTrust.MISMATCH].
 *  - Формат цифр безопасности версионирован константой [SAFETY_VERSION].
 */
class KeyTrustStore(
    private val dao: CoreStore,
    private val namespace: String = "",
) {

    /** Источник ключей с сервера. Устанавливается после логина: { api.getPublicKey(it) }. */
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

    /** Исходящие: ключ собеседника изменился по сравнению с запиненным. */
    class KeyChangedException(
        val peerId: String,
        val pinnedKeyB64: String,
        val serverKeyB64: String,
    ) : IllegalStateException(
        "Ключ собеседника $peerId изменился! Это может быть смена устройства или атака. " +
        "Откройте «Цифры безопасности» в чате, чтобы сверить ключи или принять новый."
    )

    /** Результат проверки входящего сообщения. */
    enum class IncomingTrust {
        OK_PINNED,   // ключ совпал с пином
        OK_TOFU,     // первый контакт — ключ только что запинен
        MISMATCH,    // ключ НЕ совпал — сообщение отклонять
    }

    /**
     * Исходящие. Возвращает доверенный публичный ключ собеседника.
     * При первом контакте пинит серверный ключ (TOFU).
     * @throws KeyChangedException если серверный ключ не совпал с запиненным.
     */
    suspend fun keyForSending(peerId: String): String {
        val id = peerId.lowercase()
        val fetch = keyFetcher ?: throw IllegalStateException("KeyTrustStore: keyFetcher не установлен")
        return checkForSending(id, fetch(id))
    }

    /** Проверяет уже полученный вместе с prekey-бандлом identity, не делая второй запрос. */
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
                // Фиксируем факт смены для UI, но НЕ перепиниваем
                savePin(id, pin.copy(previousKeyB64 = pin.publicKeyB64, changedAt = System.currentTimeMillis()))
                throw KeyChangedException(id, pin.publicKeyB64, serverKey)
            }
        }
    }

    /**
     * Входящие. Сверяет ключ из конверта с пином; первый контакт — пинит.
     * Не бросает исключений: решение, что делать с MISMATCH, — на вызывающем коде.
     */
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

    /**
     * Блокирующая версия [keyForSending] для не-suspend колбэков
     * (onSendMessage и т.п. уже выполняются на IO-потоке).
     */
    fun keyForSendingBlocking(peerId: String): String =
        kotlinx.coroutines.runBlocking { keyForSending(peerId) }

    /** Текущий пин (для экрана «Цифры безопасности»). */
    suspend fun pinFor(peerId: String): PinnedKeyEntity? {
        val id = peerId.lowercase()
        return storedPin(id)?.copy(peerId = id)
    }

    /**
     * Явное принятие текущего серверного ключа (после смены).
     * Сбрасывает verified — цифры нужно сверять заново.
     */
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

    /** Отметка «цифры сверены вручную». */
    suspend fun setVerified(peerId: String, verified: Boolean) =
        dao.pinSetVerified(storageId(peerId), verified)

    companion object {
        /** Версия формата цифр безопасности — поднимайте при смене алгоритма. */
        const val SAFETY_VERSION = "AetherSafety#1"

        /**
         * «Цифры безопасности» (как в Signal): 60 цифр, 12 групп по 5.
         * Одинаковы у обеих сторон: отпечатки сортируются лексикографически,
         * поэтому порядок аргументов не важен.
         */
        fun safetyNumber(userA: String, keyA: String, userB: String, keyB: String): String {
            val fpA = fingerprint(userA, keyA)
            val fpB = fingerprint(userB, keyB)
            val (first, second) = if (fpA <= fpB) fpA to fpB else fpB to fpA
            return (first + second)
                .chunked(5)
                .joinToString(" ")
        }

        /** 30 цифр из SHA-512(version || userId || key): 6 групп по 5 цифр из 5 байт. */
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
