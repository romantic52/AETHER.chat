package org.groktest.securemessenger.data

import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * Android domain adapter over the shared SQLCipher-backed Rust store.
 *
 * The UI keeps using the existing MessageEntity and ChatEntity models while the
 * persisted representation is the canonical cross-platform wire JSON.
 */
class CoreStore private constructor(
    context: Context,
    private val encKeyB64: String,
    private val accountId: String,
    private val accountKey: String,
) {
    private val appContext = context.applicationContext
    private val meta: SharedPreferences =
        appContext.getSharedPreferences("sm_core_android_meta_$accountKey", Context.MODE_PRIVATE)
    private val store = uniffi.sm_core.CoreStore.open(
        appContext.getDatabasePath("sm_core_store_$accountKey.db").absolutePath,
        encKeyB64,
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val chatRefreshes = Channel<Unit>(Channel.CONFLATED)
    private val chatList = MutableStateFlow<List<ChatListEntry>>(emptyList())
    private val allChats = MutableStateFlow<List<ChatEntity>>(emptyList())
    private val messageStreams = java.util.concurrent.ConcurrentHashMap<String, MessageStream>()

    init {
        migrateLegacyRoomStore()
        hydrateMigratedAndroidMeta()
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

    /** Imports the pre-core Room database once, into the account that owned it. */
    private fun migrateLegacyRoomStore() {
        val migration = appContext.getSharedPreferences("sm_core_store_migration", Context.MODE_PRIVATE)
        val marker = "legacy_room_v1_$accountKey"
        if (migration.getBoolean(marker, false)) {
            if (!migration.contains("legacy_room_owner")) {
                migration.edit().putString("legacy_room_owner", accountKey).apply()
            }
            return
        }
        val assignedOwner = migration.getString("legacy_room_owner", null)
        if (assignedOwner != null && assignedOwner != accountKey) return

        val legacyFile = appContext.getDatabasePath("secure_messenger_db")
        if (!legacyFile.exists()) {
            migration.edit().putBoolean(marker, true).apply()
            return
        }

        val legacy = runCatching {
            SQLiteDatabase.openDatabase(legacyFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        }.getOrNull() ?: return

        legacy.use { db ->
            if (!db.hasColumn("messages", "msgId") || !db.hasColumn("chats", "peerId")) {
                migration.edit().putBoolean(marker, true).apply()
                return
            }

            val owners = mutableSetOf<String>()
            db.rawQuery("SELECT peerId FROM chats WHERE type = 3", null).use { cursor ->
                while (cursor.moveToNext()) cursor.string("peerId")?.let(owners::add)
            }
            if (owners.isNotEmpty() && owners.none { it.equals(accountId, ignoreCase = true) }) return
            if (owners.isEmpty() && migration.getString("legacy_owner", null) != accountKey) return

            val touchedPeers = mutableSetOf<String>()
            val metaEditor = meta.edit()
            db.rawQuery(
                "SELECT peerId,name,type,isPinned,isMuted,isArchived,unreadCount,avatarFileId FROM chats",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val peerId = cursor.string("peerId") ?: continue
                    val type = cursor.int("type")
                    if (!meta.contains(typeKey(peerId))) metaEditor.putInt(typeKey(peerId), type)
                    cursor.string("avatarFileId")?.takeIf(String::isNotBlank)?.let {
                        if (!meta.contains(avatarKey(peerId))) metaEditor.putString(avatarKey(peerId), it)
                    }
                    if (coreChat(peerId) == null) {
                        store.upsertChat(
                            CoreChat(
                                peerId = peerId,
                                isGroup = type in 1..2,
                                title = cursor.string("name").orEmpty().ifBlank { peerId },
                                lastText = "",
                                lastTs = 0L,
                                unread = cursor.int("unreadCount"),
                                pinned = cursor.bool("isPinned"),
                                muted = cursor.bool("isMuted"),
                                archived = cursor.bool("isArchived"),
                            )
                        )
                    }
                    touchedPeers += peerId
                }
            }
            metaEditor.apply()

            db.rawQuery(
                """SELECT msgId,peerId,isOut,text,timestamp,replyToId,replyToText,
                          reactions,status,isEdited,forwardedFrom FROM messages ORDER BY timestamp""",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val msgId = cursor.string("msgId") ?: continue
                    if (store.getMessage(msgId) != null) continue
                    val peerId = cursor.string("peerId") ?: continue
                    val text = legacyPlaintext(cursor.string("text")).orEmpty()
                    store.insertMessage(
                        MessageEntity(
                            msgId = msgId,
                            peerId = peerId,
                            isOut = cursor.bool("isOut"),
                            text = text,
                            timestamp = cursor.long("timestamp"),
                            replyToId = cursor.string("replyToId"),
                            replyToText = legacyPlaintext(cursor.string("replyToText")),
                            reactions = legacyPlaintext(cursor.string("reactions")).orEmpty(),
                            status = cursor.int("status"),
                            isEdited = cursor.bool("isEdited"),
                            forwardedFrom = cursor.string("forwardedFrom"),
                        ).toCore()
                    )
                    if (coreChat(peerId) == null) {
                        metaEditor.putInt(typeKey(peerId), if (isGroupLike(peerId)) 1 else 0)
                        store.upsertChat(
                            CoreChat(
                                peerId = peerId,
                                isGroup = isGroupLike(peerId),
                                title = peerId,
                                lastText = text,
                                lastTs = cursor.long("timestamp"),
                                unread = 0,
                                pinned = false,
                                muted = false,
                                archived = false,
                            )
                        )
                    }
                    touchedPeers += peerId
                }
            }
            metaEditor.apply()

            if (db.hasColumn("pinned_keys", "peerId")) {
                db.rawQuery(
                    "SELECT peerId,publicKeyB64,pinnedAt,verified,previousKeyB64,changedAt FROM pinned_keys",
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val peerId = cursor.string("peerId") ?: continue
                        if (store.pinGet(peerId) != null) continue
                        store.pinUpsert(peerId, cursor.string("publicKeyB64").orEmpty(), cursor.long("pinnedAt"))
                        if (cursor.bool("verified")) store.pinSetVerified(peerId, true)
                        cursor.string("previousKeyB64")?.let { metaEditor.putString(previousKey(peerId), it) }
                        cursor.nullableLong("changedAt")?.let { metaEditor.putLong(changedKey(peerId), it) }
                    }
                }
                metaEditor.apply()
            }

            touchedPeers.forEach(::refreshChatPreview)
        }
        migration.edit()
            .putBoolean(marker, true)
            .putString("legacy_room_owner", accountKey)
            .apply()
    }

    private fun legacyPlaintext(value: String?): String? {
        if (value == null) return null
        val separator = value.indexOf(':')
        if (separator <= 0 || separator == value.lastIndex) return value
        val nonce = value.substring(0, separator)
        val encoded = value.substring(separator + 1)
        val ciphertext = runCatching {
            Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }.recoverCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return value
        return runCatching {
            String(uniffi.sm_core.aesDecrypt(encKeyB64, nonce, ciphertext), Charsets.UTF_8)
        }.getOrDefault(value)
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

    private fun streamFor(peerId: String): MessageStream =
        messageStreams.computeIfAbsent(normalized(peerId)) { MessageStream(peerId) }

    private fun refreshChats() {
        val entries = store.getChatList().map { chat ->
            ChatListEntry(
                chat = chat.toEntity(meta),
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

    companion object {
        private const val HISTORY_LIMIT = 2_000u

        @Synchronized
        fun create(context: Context, accountId: String): CoreStore {
            val accountKey = accountKey(accountId)
            if (accountId != "__anonymous__") {
                migrateLegacyStore(context, accountKey)
            }
            return CoreStore(
                context = context,
                encKeyB64 = dbKey(context),
                accountId = accountId.trim().lowercase(),
                accountKey = accountKey,
            )
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

private fun SQLiteDatabase.hasColumn(table: String, column: String): Boolean =
    rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
        val name = cursor.getColumnIndex("name")
        while (cursor.moveToNext()) {
            if (name >= 0 && cursor.getString(name) == column) return@use true
        }
        false
    }

private fun Cursor.string(column: String): String? =
    getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

private fun Cursor.int(column: String): Int =
    getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getInt) ?: 0

private fun Cursor.long(column: String): Long =
    getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong) ?: 0L

private fun Cursor.nullableLong(column: String): Long? =
    getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)

private fun Cursor.bool(column: String): Boolean = int(column) != 0
