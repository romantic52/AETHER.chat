package org.groktest.securemessenger.data

/**
 * P6: Запиненный публичный ключ собеседника (TOFU — trust on first use).
 * Раньше Room @Entity; теперь доменный класс над ядровым хранилищем
 * ([CoreStore.pinGet]/[CoreStore.pinUpsert]). Доступ — только через
 * [org.groktest.securemessenger.crypto.KeyTrustStore].
 */
data class PinnedKeyEntity(
    val peerId: String,                    // всегда lowercase
    val publicKeyB64: String,              // ключ, которому доверяем
    val pinnedAt: Long,                    // когда запинили (millis)
    val verified: Boolean = false,         // пользователь сверил цифры безопасности вручную
    val previousKeyB64: String? = null,    // прежний ключ, если была смена (для UI-предупреждений)
    val changedAt: Long? = null            // когда заметили смену ключа
)
