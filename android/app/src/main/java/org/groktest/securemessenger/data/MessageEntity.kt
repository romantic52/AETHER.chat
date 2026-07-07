package org.groktest.securemessenger.data

/**
 * Сообщение. Раньше — Room @Entity; теперь обычный доменный класс, хранилище
 * живёт в ядре ([uniffi.sm_core.Store] через [CoreStore]). Поле [id] оставлено
 * для совместимости (ядро его не отдаёт, всегда 0) — ключи в UI берут [msgId].
 */
data class MessageEntity(
    val id: Long = 0,
    val msgId: String,        // ID сообщения с сервера — общий для отправителя и получателя
    val peerId: String,       // собеседник или ID канала/группы
    val isOut: Boolean,       // true — отправлено мной
    val text: String,         // отображаемый текст или media-JSON
    val timestamp: Long,
    val replyToId: String? = null,    // msgId сообщения, на которое отвечаем
    val replyToText: String? = null,  // превью цитаты
    val reactions: String = "",       // JSON {userId: emoji}
    val status: Int = 1,              // -1=ошибка, 0=отправляется, 1=отправлено, 2=доставлено, 3=прочитано
    val isEdited: Boolean = false,    // сообщение было отредактировано
    val forwardedFrom: String? = null // ID автора оригинала при пересылке
)
