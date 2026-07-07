package org.groktest.securemessenger.data

/** Чат. Раньше Room @Entity; теперь доменный класс над ядровым хранилищем. */
data class ChatEntity(
    val peerId: String,
    val name: String,
    val type: Int, // 0 = Personal, 1 = Group, 2 = Channel, 3 = Saved Messages (Избранное)
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    val isArchived: Boolean = false,
    val unreadCount: Int = 0,
    val avatarFileId: String? = null // file_id аватарки на сервере
)

/**
 * Строка списка чатов: чат + только последнее сообщение (раньше — Room @Embedded
 * проекция). Поля last* приходят из ядрового `get_chat_list` (JOIN последнего msg).
 */
data class ChatListEntry(
    val chat: ChatEntity,
    val lastText: String?,
    val lastTimestamp: Long?,
    val lastIsOut: Boolean?
)
