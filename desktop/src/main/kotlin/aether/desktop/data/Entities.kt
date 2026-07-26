package aether.desktop.data

import org.json.JSONObject

/** Сообщение — доменный класс над ядровым хранилищем (как на Android). */
data class MessageEntity(
    val id: Long = 0,
    val msgId: String,        // ID сообщения с сервера — общий для отправителя и получателя
    val peerId: String,       // собеседник или ID канала/группы
    val isOut: Boolean,       // true — отправлено мной
    val text: String,         // отображаемый текст или media-JSON
    val timestamp: Long,
    val replyToId: String? = null,
    val replyToText: String? = null,
    val reactions: String = "",       // JSON {userId: emoji}
    val status: Int = 1,              // -1=ошибка, 0=отправляется, 1=отправлено, 2=доставлено, 3=прочитано
    val isEdited: Boolean = false,
    val forwardedFrom: String? = null,
)

/** Чат: 0 = личный, 1 = группа, 2 = канал, 3 = Избранное. */
data class ChatEntity(
    val peerId: String,
    val name: String,
    val type: Int,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    val isArchived: Boolean = false,
    val unreadCount: Int = 0,
    val statusEmoji: String? = null,
    val avatarFileId: String? = null,
)

data class ChatListEntry(
    val chat: ChatEntity,
    val lastText: String?,
    val lastTimestamp: Long?,
    val lastIsOut: Boolean?,
)

data class PinnedKeyEntity(
    val peerId: String,
    val publicKeyB64: String,
    val pinnedAt: Long,
    val verified: Boolean = false,
    val previousKeyB64: String? = null,
    val changedAt: Long? = null,
)

/** Однострочное превью для списка чатов. */
fun messagePreview(raw: String?, emptyFallback: String = "Нет сообщений"): String {
    if (raw.isNullOrBlank()) return emptyFallback
    if (!raw.trimStart().startsWith("{")) return raw

    return try {
        val payload = JSONObject(raw)
        if (payload.optString("type") != "media") return raw
        payload.optString("caption").takeIf(String::isNotBlank) ?: when (payload.optString("kind")) {
            "image" -> "Фото"
            "video" -> "Видео"
            "voice" -> "Голосовое сообщение"
            "video_msg", "video_note", "circle" -> "Видеосообщение"
            "file", "document" -> payload.optString("file_name").takeIf(String::isNotBlank) ?: "Файл"
            else -> "Вложение"
        }
    } catch (_: Exception) {
        raw
    }
}
