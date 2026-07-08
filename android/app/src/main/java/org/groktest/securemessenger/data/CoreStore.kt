package org.groktest.securemessenger.data

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import uniffi.sm_core.Chat as CoreChat
import uniffi.sm_core.PinnedKey as CorePinnedKey
import uniffi.sm_core.StoreListener
import uniffi.sm_core.StoredMessage

/**
 * Локальное хранилище на ядре (SQLite в Rust, [uniffi.sm_core.Store]) вместо Room.
 *
 * Доменные типы приложения ([MessageEntity]/[ChatEntity]/[ChatListEntry]/
 * [PinnedKeyEntity]) сохранены — здесь только маппинг в ядровые записи, поэтому
 * репозиторий/UI почти не меняются.
 *
 * Реактивность как у Room.Flow: ядро дёргает [StoreListener] при любом изменении,
 * мы поднимаем [version]; Flow'ы перечитывают нужный запрос. Тексты шифруются
 * at-rest внутри ядра ключом сессии (privateB64).
 */
class CoreStore(context: Context, encKeyB64: String) {

    private val store = uniffi.sm_core.Store.open(
        context.getDatabasePath("sm_core_store.db").absolutePath,
        encKeyB64,
    )

    // Любое изменение в БД поднимает версию — Flow'ы перечитывают данные.
    private val version = MutableStateFlow(0L)

    init {
        store.setListener(object : StoreListener {
            override fun onMessagesChanged(peerId: String) { version.update { it + 1 } }
            override fun onChatsChanged() { version.update { it + 1 } }
        })
    }

    // ---------------- Flow'ы (реактивный UI) ----------------

    fun getMessagesForPeer(peerId: String): Flow<List<MessageEntity>> =
        version.map {
            withContext(Dispatchers.IO) { store.getMessagesForPeer(peerId).map { it.toEntity() } }
        }.distinctUntilChanged()

    fun getChatList(): Flow<List<ChatListEntry>> =
        version.map {
            withContext(Dispatchers.IO) {
                store.getChatList().map {
                    ChatListEntry(it.chat.toEntity(), it.lastText, it.lastTimestamp, it.lastIsOut)
                }
            }
        }.distinctUntilChanged()

    fun getAllChats(): Flow<List<ChatEntity>> =
        version.map {
            withContext(Dispatchers.IO) { store.getAllChats().map { it.toEntity() } }
        }.distinctUntilChanged()

    // ---------------- messages ----------------

    suspend fun insertMessage(m: MessageEntity) = withContext(Dispatchers.IO) {
        store.insertMessage(m.toCore())
    }

    suspend fun getMessageByMsgId(msgId: String): MessageEntity? = withContext(Dispatchers.IO) {
        store.getMessageByMsgId(msgId)?.toEntity()
    }

    suspend fun getMessagesForPeerOnce(peerId: String): List<MessageEntity> = withContext(Dispatchers.IO) {
        store.getMessagesForPeer(peerId).map { it.toEntity() }
    }

    suspend fun deleteByMsgId(msgId: String) = withContext(Dispatchers.IO) {
        store.deleteMessageByMsgId(msgId)
    }

    suspend fun updateReactions(msgId: String, reactions: String) = withContext(Dispatchers.IO) {
        store.updateReactions(msgId, reactions)
    }

    suspend fun updateText(msgId: String, text: String) = withContext(Dispatchers.IO) {
        store.updateText(msgId, text)
    }

    suspend fun updateStatus(msgId: String, status: Int) = withContext(Dispatchers.IO) {
        store.updateStatus(msgId, status)
    }

    suspend fun getPendingOutgoing(): List<MessageEntity> = withContext(Dispatchers.IO) {
        store.getPendingOutgoing().map { it.toEntity() }
    }

    suspend fun markOutgoingRead(peerId: String) = withContext(Dispatchers.IO) {
        store.markOutgoingRead(peerId)
    }

    suspend fun markOutgoingDelivered(peerId: String) = withContext(Dispatchers.IO) {
        store.markOutgoingDelivered(peerId)
    }

    // ---------------- chats ----------------

    suspend fun insertChat(chat: ChatEntity) = withContext(Dispatchers.IO) {
        store.insertChat(chat.toCore())
    }

    /** В ядре insert = INSERT OR REPLACE, поэтому обновление по PK — тот же вызов. */
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
        store.setAvatar(peerId, avatarFileId)
    }

    suspend fun deleteChatAndMessages(peerId: String) = withContext(Dispatchers.IO) {
        store.deleteChatAndMessages(peerId)
    }

    suspend fun getAllChatsOnce(): List<ChatEntity> = withContext(Dispatchers.IO) {
        store.getAllChats().map { it.toEntity() }
    }

    suspend fun getChat(peerId: String): ChatEntity? = withContext(Dispatchers.IO) {
        store.getChat(peerId)?.toEntity()
    }

    suspend fun incrementUnread(peerId: String) = withContext(Dispatchers.IO) {
        store.incrementUnread(peerId)
    }

    suspend fun clearUnread(peerId: String) = withContext(Dispatchers.IO) {
        store.clearUnread(peerId)
    }

    // ---------------- pinned_keys (TOFU) ----------------

    suspend fun pinGet(peerId: String): PinnedKeyEntity? = withContext(Dispatchers.IO) {
        store.pinGet(peerId)?.toEntity()
    }

    suspend fun pinUpsert(pin: PinnedKeyEntity) = withContext(Dispatchers.IO) {
        store.pinUpsert(pin.toCore())
    }

    suspend fun pinSetVerified(peerId: String, verified: Boolean) = withContext(Dispatchers.IO) {
        store.pinSetVerified(peerId, verified)
    }

    companion object {
        /**
         * Создаёт хранилище с ключом шифрования БД из Keystore-backed prefs
         * (генерируется один раз на установку, не зависит от логина — поэтому
         * доступно и в onCreate, и в фоновых воркерах).
         */
        fun create(context: Context): CoreStore = CoreStore(context, dbKey(context))

        private fun dbKey(context: Context): String {
            val prefs = EncryptedSharedPreferences.create(
                context,
                "sm_core_db_key",
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            prefs.getString("k", null)?.let { return it }
            val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            val k = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            prefs.edit().putString("k", k).apply()
            return k
        }
    }
}

// ---------------- маппинг доменные типы ↔ ядровые записи ----------------

private fun StoredMessage.toEntity() = MessageEntity(
    id = 0,
    msgId = msgId,
    peerId = peerId,
    isOut = isOut,
    text = text,
    timestamp = timestamp,
    replyToId = replyToId,
    replyToText = replyToText,
    reactions = reactions,
    status = status,
    isEdited = isEdited,
    forwardedFrom = forwardedFrom,
)

private fun MessageEntity.toCore() = StoredMessage(
    msgId = msgId,
    peerId = peerId,
    isOut = isOut,
    text = text,
    timestamp = timestamp,
    replyToId = replyToId,
    replyToText = replyToText,
    reactions = reactions,
    status = status,
    isEdited = isEdited,
    forwardedFrom = forwardedFrom,
)

private fun CoreChat.toEntity() = ChatEntity(
    peerId = peerId,
    name = name,
    type = kind,
    isPinned = isPinned,
    isMuted = isMuted,
    isArchived = isArchived,
    unreadCount = unreadCount,
    avatarFileId = avatarFileId,
)

private fun ChatEntity.toCore() = CoreChat(
    peerId = peerId,
    name = name,
    kind = type,
    isPinned = isPinned,
    isMuted = isMuted,
    isArchived = isArchived,
    unreadCount = unreadCount,
    avatarFileId = avatarFileId,
)

private fun CorePinnedKey.toEntity() = PinnedKeyEntity(
    peerId = peerId,
    publicKeyB64 = publicKeyB64,
    pinnedAt = pinnedAt,
    verified = verified,
    previousKeyB64 = previousKeyB64,
    changedAt = changedAt,
)

private fun PinnedKeyEntity.toCore() = CorePinnedKey(
    peerId = peerId,
    publicKeyB64 = publicKeyB64,
    pinnedAt = pinnedAt,
    verified = verified,
    previousKeyB64 = previousKeyB64,
    changedAt = changedAt,
)
