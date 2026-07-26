package aether.desktop.data

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.sm_core.Chat as CoreChat
import uniffi.sm_core.KeyPin as CoreKeyPin
import uniffi.sm_core.StoreListener
import uniffi.sm_core.StoredMessage
import uniffi.sm_core.WireMessage

/**
 * JVM-адаптер над общим SQLCipher-хранилищем ядра (порт Android CoreStore без
 * Room-миграций). Метаданные чатов (тип/аватар/эмодзи-статус) — в meta-таблице
 * ядра под ключами desktop.*.
 */
class DesktopCoreStore private constructor(
    private val store: uniffi.sm_core.CoreStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val chatRefreshes = Channel<Unit>(Channel.CONFLATED)
    private val chatList = MutableStateFlow<List<ChatListEntry>>(emptyList())
    private val allChats = MutableStateFlow<List<ChatEntity>>(emptyList())
    private val messageStreams = java.util.concurrent.ConcurrentHashMap<String, MessageStream>()

    init {
        refreshChats()
        scope.launch {
            for (ignored in chatRefreshes) refreshChats()
        }
        store.setListener(object : StoreListener {
            override fun onMessagesChanged(peerId: String) {
                messageStreams[normalized(peerId)]?.refresh()
            }

            override fun onChatsChanged() {
                chatRefreshes.trySend(Unit)
            }
        })
    }

    fun close() {
        scope.cancel()
    }

    private inner class MessageStream(private val peerId: String) {
        val messages = MutableStateFlow<List<MessageEntity>>(emptyList())
        private val refreshes = Channel<Unit>(Channel.CONFLATED)
        private val loadMutex = Mutex()
        private var loaded = false

        init {
            scope.launch {
                load()
                for (ignored in refreshes) load(force = true)
            }
        }

        fun refresh() {
            refreshes.trySend(Unit)
        }

        suspend fun load(force: Boolean = false) = loadMutex.withLock {
            if (loaded && !force) return@withLock
            messages.value = readMessages(peerId)
            loaded = true
        }
    }

    fun getMessagesForPeer(peerId: String): StateFlow<List<MessageEntity>> =
        streamFor(peerId).messages

    fun getChatList(): StateFlow<List<ChatListEntry>> = chatList

    fun getAllChats(): StateFlow<List<ChatEntity>> = allChats

    fun cachedChat(peerId: String): ChatEntity? =
        chatList.value.firstOrNull { it.chat.peerId.equals(peerId, ignoreCase = true) }?.chat

    fun cachedMessages(peerId: String): List<MessageEntity> =
        messageStreams[normalized(peerId)]?.messages?.value.orEmpty()

    fun recentPeerIds(limit: Int): List<String> =
        chatList.value.asSequence().map { it.chat.peerId }.take(limit).toList()

    suspend fun preloadMessages(peerId: String) = withContext(Dispatchers.IO) {
        streamFor(peerId).load()
    }

    suspend fun preloadRecentMessages(limit: Int = 4) = withContext(Dispatchers.IO) {
        recentPeerIds(limit).forEach { streamFor(it).load() }
    }

    suspend fun insertMessage(message: MessageEntity) = withContext(Dispatchers.IO) {
        store.insertMessage(message.toCore())
        val chat = coreChat(message.peerId)
        store.touchChat(
            peerId = message.peerId,
            isGroup = chat?.isGroup ?: isGroupLike(message.peerId),
            title = chat?.title.orEmpty(),
            lastText = message.text,
            lastTs = message.timestamp,
            incUnread = false,
        )
    }

    suspend fun getMessageByMsgId(msgId: String): MessageEntity? = withContext(Dispatchers.IO) {
        store.getMessage(msgId)?.takeUnless { it.deleted }?.toEntity()
    }

    suspend fun getMessagesForPeerOnce(peerId: String): List<MessageEntity> =
        withContext(Dispatchers.IO) { readMessages(peerId) }

    suspend fun deleteByMsgId(msgId: String) = withContext(Dispatchers.IO) {
        val peerId = store.peerOf(msgId)
        store.markDeleted(msgId)
        if (peerId != null) refreshChatPreview(peerId)
    }

    suspend fun updateReactions(msgId: String, reactions: String) = withContext(Dispatchers.IO) {
        store.updateReactions(msgId, reactions.ifBlank { "{}" })
    }

    suspend fun updateText(msgId: String, text: String) = withContext(Dispatchers.IO) {
        val previous = store.getMessage(msgId)?.takeUnless { it.deleted }?.toEntity()
        val payload = if (looksLikeWireJson(text)) {
            text
        } else {
            uniffi.sm_core.wireEncode(
                WireMessage.Text(
                    text = text,
                    replyToId = previous?.replyToId,
                    replyToText = previous?.replyToText,
                    fwdFrom = previous?.forwardedFrom,
                )
            )
        }
        store.updateText(msgId, payload)
        store.peerOf(msgId)?.let(::refreshChatPreview)
    }

    suspend fun updatePayload(msgId: String, payload: String) = withContext(Dispatchers.IO) {
        store.updatePayload(msgId, payload)
        store.peerOf(msgId)?.let(::refreshChatPreview)
    }

    suspend fun updateStatus(msgId: String, status: Int) = withContext(Dispatchers.IO) {
        store.updateStatus(msgId, status)
    }

    suspend fun getPendingOutgoing(): List<MessageEntity> = withContext(Dispatchers.IO) {
        store.getPendingOutgoing().filterNot { it.deleted }.map { it.toEntity() }
    }

    suspend fun markOutgoingRead(peerId: String) = withContext(Dispatchers.IO) {
        store.markOutgoingStatus(peerId, 3)
    }

    suspend fun markOutgoingDelivered(peerId: String) = withContext(Dispatchers.IO) {
        store.markOutgoingStatus(peerId, 2)
    }

    suspend fun insertChat(chat: ChatEntity) = withContext(Dispatchers.IO) {
        store.metaSet(typeKey(chat.peerId), chat.type.toString())
        store.metaSet(avatarKey(chat.peerId), chat.avatarFileId.orEmpty())
        store.metaSet(statusEmojiKey(chat.peerId), chat.statusEmoji.orEmpty())
        val current = coreChat(chat.peerId)
        store.upsertChat(
            CoreChat(
                peerId = chat.peerId,
                isGroup = chat.type == 1 || chat.type == 2,
                title = chat.name,
                lastText = current?.lastText.orEmpty(),
                lastTs = current?.lastTs ?: 0L,
                unread = chat.unreadCount,
                pinned = chat.isPinned,
                muted = chat.isMuted,
                archived = chat.isArchived,
            )
        )
        refreshChats()
    }

    suspend fun updateChat(chat: ChatEntity) = insertChat(chat)

    suspend fun setPinned(peerId: String, value: Boolean) = withContext(Dispatchers.IO) {
        store.setPinned(peerId, value)
    }

    suspend fun setMuted(peerId: String, value: Boolean) = withContext(Dispatchers.IO) {
        store.setMuted(peerId, value)
    }

    suspend fun setArchived(peerId: String, value: Boolean) = withContext(Dispatchers.IO) {
        store.setArchived(peerId, value)
    }

    suspend fun setAvatar(peerId: String, avatarFileId: String?) = withContext(Dispatchers.IO) {
        store.metaSet(avatarKey(peerId), avatarFileId.orEmpty())
        store.notifyChats()
    }

    suspend fun deleteChatAndMessages(peerId: String) = withContext(Dispatchers.IO) {
        store.deleteChat(peerId)
        store.metaSet(typeKey(peerId), "")
        store.metaSet(avatarKey(peerId), "")
        store.metaSet(statusEmojiKey(peerId), "")
    }

    suspend fun getAllChatsOnce(): List<ChatEntity> = withContext(Dispatchers.IO) {
        store.getChatList().map { it.toEntity() }
    }

    suspend fun getChat(peerId: String): ChatEntity? = withContext(Dispatchers.IO) {
        coreChat(peerId)?.toEntity()
    }

    suspend fun incrementUnread(peerId: String) = withContext(Dispatchers.IO) {
        val chat = coreChat(peerId)
        store.touchChat(
            peerId = peerId,
            isGroup = chat?.isGroup ?: isGroupLike(peerId),
            title = chat?.title.orEmpty(),
            lastText = chat?.lastText.orEmpty(),
            lastTs = chat?.lastTs ?: 0L,
            incUnread = true,
        )
    }

    suspend fun clearUnread(peerId: String) = withContext(Dispatchers.IO) {
        store.clearUnread(peerId)
    }

    fun totalUnread(): Long = store.totalUnread()

    suspend fun pinGet(peerId: String): PinnedKeyEntity? = withContext(Dispatchers.IO) {
        store.pinGet(peerId)?.toEntity(store)
    }

    suspend fun pinUpsert(pin: PinnedKeyEntity) = withContext(Dispatchers.IO) {
        store.metaSet(previousKey(pin.peerId), pin.previousKeyB64.orEmpty())
        store.metaSet(changedKey(pin.peerId), pin.changedAt?.toString().orEmpty())
        store.pinUpsert(pin.peerId, pin.publicKeyB64, pin.pinnedAt)
        if (pin.verified) store.pinSetVerified(pin.peerId, true)
    }

    suspend fun pinSetVerified(peerId: String, verified: Boolean) = withContext(Dispatchers.IO) {
        store.pinSetVerified(peerId, verified)
    }

    suspend fun metaGet(key: String): String? = withContext(Dispatchers.IO) {
        store.metaGet(key)
    }

    suspend fun metaSet(key: String, value: String) = withContext(Dispatchers.IO) {
        store.metaSet(key, value)
    }

    suspend fun olmSessionGet(peerId: String): String? = withContext(Dispatchers.IO) {
        store.olmSessionGet(peerId.lowercase())
    }

    suspend fun olmSessionSet(peerId: String, session: String) = withContext(Dispatchers.IO) {
        store.olmSessionSet(peerId.lowercase(), session)
    }

    private fun streamFor(peerId: String): MessageStream =
        messageStreams.computeIfAbsent(normalized(peerId)) { MessageStream(peerId) }

    private fun refreshChats() {
        val entries = store.getChatList().map { chat ->
            ChatListEntry(
                chat = chat.toEntity(),
                lastText = chat.lastText.takeIf(String::isNotBlank),
                lastTimestamp = chat.lastTs.takeIf { it > 0L },
                lastIsOut = null,
            )
        }
        chatList.value = entries
        allChats.value = entries.map { it.chat }
    }

    private fun readMessages(peerId: String): List<MessageEntity> =
        store.getMessagesForPeer(peerId, 0L, HISTORY_LIMIT)
            .filterNot { it.deleted }
            .map { it.toEntity() }

    private fun coreChat(peerId: String): CoreChat? =
        store.getChatList().firstOrNull { it.peerId.equals(peerId, ignoreCase = true) }

    private fun refreshChatPreview(peerId: String) {
        val chat = coreChat(peerId) ?: return
        val latest = store.getMessagesForPeer(peerId, 0L, HISTORY_LIMIT)
            .lastOrNull { !it.deleted }
            ?.toEntity()
        store.touchChat(
            peerId = peerId,
            isGroup = chat.isGroup,
            title = chat.title,
            lastText = latest?.text.orEmpty(),
            lastTs = latest?.timestamp ?: 0L,
            incUnread = false,
        )
    }

    private fun CoreChat.toEntity(): ChatEntity = ChatEntity(
        peerId = peerId,
        name = title.takeUnless { it.isBlank() || it == "null" } ?: peerId,
        type = store.metaGet(typeKey(peerId))?.toIntOrNull() ?: if (isGroup) 1 else 0,
        isPinned = pinned,
        isMuted = muted,
        isArchived = archived,
        unreadCount = unread,
        avatarFileId = store.metaGet(avatarKey(peerId))?.takeUnless { it.isBlank() || it == "null" },
        statusEmoji = store.metaGet(statusEmojiKey(peerId))?.takeUnless { it.isBlank() || it == "null" },
    )

    private fun CoreKeyPin.toEntity(store: uniffi.sm_core.CoreStore): PinnedKeyEntity = PinnedKeyEntity(
        peerId = peerId,
        publicKeyB64 = publicKeyB64,
        pinnedAt = firstSeen,
        verified = verified,
        previousKeyB64 = store.metaGet(previousKey(peerId))?.takeIf(String::isNotBlank),
        changedAt = store.metaGet(changedKey(peerId))?.toLongOrNull(),
    )

    companion object {
        private const val HISTORY_LIMIT = 2_000u

        @Synchronized
        fun create(dbFile: File, encKeyB64: String): DesktopCoreStore {
            dbFile.parentFile?.mkdirs()
            return DesktopCoreStore(uniffi.sm_core.CoreStore.open(dbFile.absolutePath, encKeyB64))
        }
    }
}

private fun MessageEntity.toCore(): StoredMessage = StoredMessage(
    id = msgId,
    peerId = peerId,
    outgoing = isOut,
    senderId = if (isOut) "" else peerId,
    payloadJson = if (looksLikeWireJson(text)) {
        text
    } else {
        uniffi.sm_core.wireEncode(
            WireMessage.Text(
                text = text,
                replyToId = replyToId,
                replyToText = replyToText,
                fwdFrom = forwardedFrom,
            )
        )
    },
    status = status,
    ts = timestamp,
    reactionsJson = reactions.ifBlank { "{}" },
    edited = isEdited,
    deleted = false,
)

private fun StoredMessage.toEntity(): MessageEntity {
    val wire = uniffi.sm_core.wireDecode(payloadJson)
    val text: String
    val replyToId: String?
    val replyToText: String?
    val forwardedFrom: String?
    when (wire) {
        is WireMessage.Text -> {
            text = wire.text
            replyToId = wire.replyToId
            replyToText = wire.replyToText
            forwardedFrom = wire.fwdFrom
        }
        is WireMessage.Media -> {
            text = payloadJson
            replyToId = null
            replyToText = null
            forwardedFrom = wire.fwdFrom
        }
        is WireMessage.Unknown -> {
            text = wire.raw
            replyToId = null
            replyToText = null
            forwardedFrom = null
        }
        else -> {
            text = payloadJson
            replyToId = null
            replyToText = null
            forwardedFrom = null
        }
    }
    return MessageEntity(
        id = 0L,
        msgId = id,
        peerId = peerId,
        isOut = outgoing,
        text = text,
        timestamp = ts,
        replyToId = replyToId,
        replyToText = replyToText,
        reactions = reactionsJson.takeUnless { it == "{}" }.orEmpty(),
        status = status,
        isEdited = edited,
        forwardedFrom = forwardedFrom,
    )
}

private fun looksLikeWireJson(value: String): Boolean {
    val trimmed = value.trimStart()
    if (!trimmed.startsWith("{")) return false
    return try {
        org.json.JSONObject(trimmed).optString("type").isNotBlank()
    } catch (_: Exception) {
        false
    }
}

internal fun isGroupLike(peerId: String): Boolean =
    peerId.startsWith("grp_", ignoreCase = true) ||
        peerId.startsWith("group_", ignoreCase = true) ||
        peerId.startsWith("channel_", ignoreCase = true)

private fun normalized(peerId: String) = peerId.lowercase()
private fun typeKey(peerId: String) = "desktop.type.${normalized(peerId)}"
private fun avatarKey(peerId: String) = "desktop.avatar.${normalized(peerId)}"
private fun statusEmojiKey(peerId: String) = "desktop.status_emoji.${normalized(peerId)}"
private fun previousKey(peerId: String) = "desktop.pin_previous.${normalized(peerId)}"
private fun changedKey(peerId: String) = "desktop.pin_changed.${normalized(peerId)}"
