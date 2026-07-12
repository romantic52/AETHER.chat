package org.groktest.securemessenger.data

import android.content.Context
import android.content.SharedPreferences
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
import uniffi.sm_core.KeyPin as CoreKeyPin
import uniffi.sm_core.StoreListener
import uniffi.sm_core.StoredMessage
import uniffi.sm_core.WireMessage

/**
 * Android domain adapter over the shared SQLCipher-backed Rust store.
 *
 * The UI keeps using the existing MessageEntity and ChatEntity models while the
 * persisted representation is the canonical cross-platform wire JSON.
 */
class CoreStore private constructor(context: Context, encKeyB64: String, accountKey: String) {
    private val appContext = context.applicationContext
    private val meta: SharedPreferences =
        appContext.getSharedPreferences("sm_core_android_meta_$accountKey", Context.MODE_PRIVATE)
    private val store = uniffi.sm_core.CoreStore.open(
        appContext.getDatabasePath("sm_core_store_$accountKey.db").absolutePath,
        encKeyB64,
    )
    private val version = MutableStateFlow(0L)

    init {
        hydrateMigratedAndroidMeta()
        store.setListener(object : StoreListener {
            override fun onMessagesChanged(peerId: String) {
                version.update { it + 1 }
            }

            override fun onChatsChanged() {
                version.update { it + 1 }
            }
        })
    }

    fun getMessagesForPeer(peerId: String): Flow<List<MessageEntity>> =
        version.map {
            withContext(Dispatchers.IO) { readMessages(peerId) }
        }.distinctUntilChanged()

    fun getChatList(): Flow<List<ChatListEntry>> =
        version.map {
            withContext(Dispatchers.IO) {
                store.getChatList().map { chat ->
                    ChatListEntry(
                        chat = chat.toEntity(meta),
                        lastText = chat.lastText.takeIf(String::isNotBlank),
                        lastTimestamp = chat.lastTs.takeIf { it > 0L },
                        lastIsOut = null,
                    )
                }
            }
        }.distinctUntilChanged()

    fun getAllChats(): Flow<List<ChatEntity>> =
        version.map {
            withContext(Dispatchers.IO) {
                store.getChatList().map { it.toEntity(meta) }
            }
        }.distinctUntilChanged()

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
        meta.edit()
            .putInt(typeKey(chat.peerId), chat.type)
            .apply {
                if (chat.avatarFileId == null) remove(avatarKey(chat.peerId))
                else putString(avatarKey(chat.peerId), chat.avatarFileId)
                if (chat.statusEmoji.isNullOrBlank()) remove(statusEmojiKey(chat.peerId))
                else putString(statusEmojiKey(chat.peerId), chat.statusEmoji)
            }
            .apply()
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
        meta.edit().apply {
            if (avatarFileId == null) remove(avatarKey(peerId))
            else putString(avatarKey(peerId), avatarFileId)
        }.apply()
        store.notifyChats()
    }

    suspend fun deleteChatAndMessages(peerId: String) = withContext(Dispatchers.IO) {
        store.deleteChat(peerId)
        meta.edit().remove(typeKey(peerId)).remove(avatarKey(peerId)).remove(statusEmojiKey(peerId)).apply()
    }

    suspend fun getAllChatsOnce(): List<ChatEntity> = withContext(Dispatchers.IO) {
        store.getChatList().map { it.toEntity(meta) }
    }

    suspend fun getChat(peerId: String): ChatEntity? = withContext(Dispatchers.IO) {
        coreChat(peerId)?.toEntity(meta)
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

    suspend fun pinGet(peerId: String): PinnedKeyEntity? = withContext(Dispatchers.IO) {
        store.pinGet(peerId)?.toEntity(meta, store)
    }

    suspend fun pinUpsert(pin: PinnedKeyEntity) = withContext(Dispatchers.IO) {
        meta.edit().apply {
            if (pin.previousKeyB64 == null) remove(previousKey(pin.peerId))
            else putString(previousKey(pin.peerId), pin.previousKeyB64)
            if (pin.changedAt == null) remove(changedKey(pin.peerId))
            else putLong(changedKey(pin.peerId), pin.changedAt)
        }.apply()
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

    private fun hydrateMigratedAndroidMeta() {
        val editor = meta.edit()
        var changed = false
        store.getChatList().forEach { chat ->
            if (!meta.contains(typeKey(chat.peerId))) {
                store.metaGet("android.type.${chat.peerId.lowercase()}")
                    ?.toIntOrNull()
                    ?.let {
                        editor.putInt(typeKey(chat.peerId), it)
                        changed = true
                    }
            }
            if (!meta.contains(avatarKey(chat.peerId))) {
                store.metaGet("android.avatar.${chat.peerId.lowercase()}")
                    ?.takeIf(String::isNotBlank)
                    ?.let {
                        editor.putString(avatarKey(chat.peerId), it)
                        changed = true
                    }
            }
        }
        if (changed) editor.apply()
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

    companion object {
        private const val HISTORY_LIMIT = 2_000u

        @Synchronized
        fun create(context: Context, accountId: String): CoreStore {
            val accountKey = accountKey(accountId)
            if (accountId != "__anonymous__") {
                migrateLegacyStore(context, accountKey)
            }
            return CoreStore(context, dbKey(context), accountKey)
        }

        private fun accountKey(accountId: String): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(accountId.trim().lowercase().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                .take(24)

        private fun migrateLegacyStore(context: Context, accountKey: String) {
            val migration = context.getSharedPreferences(
                "sm_core_store_migration",
                Context.MODE_PRIVATE,
            )
            val owner = migration.getString("legacy_owner", null)
            if (owner != null && owner != accountKey) return

            val legacyDatabase = context.getDatabasePath("sm_core_store.db")
            val legacyMeta = context.getSharedPreferences("sm_core_android_meta", Context.MODE_PRIVATE)
            if (!legacyDatabase.exists() && legacyMeta.all.isEmpty()) return

            migrateLegacyDatabase(context, accountKey)
            migrateLegacyMeta(context, accountKey)
            migration.edit().putString("legacy_owner", accountKey).apply()
        }

        private fun migrateLegacyDatabase(context: Context, accountKey: String) {
            val legacy = context.getDatabasePath("sm_core_store.db")
            val target = context.getDatabasePath("sm_core_store_$accountKey.db")
            if (!legacy.exists() || target.exists()) return
            target.parentFile?.mkdirs()
            listOf("", "-wal", "-shm").forEach { suffix ->
                val source = java.io.File(legacy.absolutePath + suffix)
                if (source.exists()) source.copyTo(java.io.File(target.absolutePath + suffix))
            }
        }

        private fun migrateLegacyMeta(context: Context, accountKey: String) {
            val target = context.getSharedPreferences(
                "sm_core_android_meta_$accountKey",
                Context.MODE_PRIVATE,
            )
            if (target.all.isNotEmpty()) return
            val legacy = context.getSharedPreferences("sm_core_android_meta", Context.MODE_PRIVATE)
            if (legacy.all.isEmpty()) return
            target.edit().apply {
                legacy.all.forEach { (key, value) ->
                    when (value) {
                        is String -> putString(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
            }.apply()
        }

        private fun dbKey(context: Context): String {
            val prefs = EncryptedSharedPreferences.create(
                context,
                "sm_core_db_key",
                MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            prefs.getString("k", null)?.let { return it }
            val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            val key = Base64.encodeToString(
                bytes,
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
            )
            prefs.edit().putString("k", key).apply()
            return key
        }
    }
}

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

private fun CoreChat.toEntity(meta: SharedPreferences): ChatEntity = ChatEntity(
    peerId = peerId,
    name = title.takeUnless { it.isBlank() || it == "null" } ?: peerId,
    type = meta.getInt(typeKey(peerId), if (isGroup) 1 else 0),
    isPinned = pinned,
    isMuted = muted,
    isArchived = archived,
    unreadCount = unread,
    avatarFileId = meta.getString(avatarKey(peerId), null)
        ?.takeUnless { it.isBlank() || it == "null" },
    statusEmoji = meta.getString(statusEmojiKey(peerId), null)
        ?.takeUnless { it.isBlank() || it == "null" },
)

private fun CoreKeyPin.toEntity(
    meta: SharedPreferences,
    store: uniffi.sm_core.CoreStore,
): PinnedKeyEntity = PinnedKeyEntity(
    peerId = peerId,
    publicKeyB64 = publicKeyB64,
    pinnedAt = firstSeen,
    verified = verified,
    previousKeyB64 = meta.getString(previousKey(peerId), null)
        ?: store.metaGet("android.pin_previous.${peerId.lowercase()}"),
    changedAt = meta.getLong(changedKey(peerId), 0L).takeIf { it > 0L }
        ?: store.metaGet("android.pin_changed.${peerId.lowercase()}")?.toLongOrNull(),
)

private fun looksLikeWireJson(value: String): Boolean {
    val trimmed = value.trimStart()
    if (!trimmed.startsWith("{")) return false
    return try {
        org.json.JSONObject(trimmed).optString("type").isNotBlank()
    } catch (_: Exception) {
        false
    }
}

private fun isGroupLike(peerId: String): Boolean =
    peerId.startsWith("grp_", ignoreCase = true) ||
        peerId.startsWith("group_", ignoreCase = true) ||
        peerId.startsWith("channel_", ignoreCase = true)

private fun normalized(peerId: String) = peerId.lowercase()
private fun typeKey(peerId: String) = "type_${normalized(peerId)}"
private fun avatarKey(peerId: String) = "avatar_${normalized(peerId)}"
private fun statusEmojiKey(peerId: String) = "status_emoji_${normalized(peerId)}"
private fun previousKey(peerId: String) = "pin_previous_${normalized(peerId)}"
private fun changedKey(peerId: String) = "pin_changed_${normalized(peerId)}"
