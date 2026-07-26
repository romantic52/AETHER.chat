package aether.desktop.data

import aether.desktop.api.RelayApi
import aether.desktop.crypto.E2ECrypto
import aether.desktop.crypto.KeyTrustStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Слой данных мессенджера на десктопе — порт Android MessageRepository:
 * инбокс+ACK per-device, optimistic outbox с backoff, multi-device fanout,
 * группы общим ключом, TOFU-гейт. Логика идентична Android, вместо
 * ContentResolver/Uri — java.io.File.
 */
class MessageRepository(
    private val api: RelayApi,
    private val keys: E2ECrypto.KeyPair,
    val myId: String,
    private val store: DesktopCoreStore,
    private val trustStore: KeyTrustStore,
    private val olmTrustStore: KeyTrustStore,
    private val cacheRoot: File,
) {
    private val crypto = E2ECrypto()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inboxMutex = Mutex()
    private val ratchetMutex = Mutex()
    private var cachedOlmIdentity = ""
    private val outboxSignal = Channel<Unit>(Channel.CONFLATED)
    private val mediaDownloadLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    @Volatile private var appActive = true

    // ------------------------------------------------------------------
    // Групповые ключи: общий симметричный ключ, box на паблик каждого участника.
    // ------------------------------------------------------------------

    private data class GroupKey(
        val key: E2ECrypto.SymmetricKey,
        val isE2E: Boolean,
        val role: String = "member",
        val linkedGroupId: String? = null,
        val isChannel: Boolean = false,
    )

    class GroupKeyUnavailableException(groupId: String) :
        IllegalStateException("Нет ключа группы $groupId (вы не участник?)")

    private class RatchetSessionUnavailableException(message: String) : IllegalStateException(message)

    /**
     * У устройства собеседника ещё нет опубликованных ключей (первый запуск,
     * только что привязанное устройство). Это ВРЕМЕННО: outbox повторит.
     * Раньше 404 от claim считался постоянной ошибкой и убивал сообщение.
     */
    class PeerKeysUnavailableException(peerId: String, deviceId: String) :
        IllegalStateException("У устройства $deviceId ($peerId) пока нет ключей")

    private val groupKeys = java.util.concurrent.ConcurrentHashMap<String, GroupKey>()
    private val groupKeysMutex = Mutex()
    @Volatile private var groupKeysFetchedAt = 0L

    private suspend fun groupKeyFor(groupId: String): GroupKey? {
        val id = groupId.lowercase()
        groupKeysMutex.lock()
        try {
            groupKeys[id]?.let { return it }
            if (System.currentTimeMillis() - groupKeysFetchedAt < 15_000) return null
            val groups = try { api.getMyGroups() } catch (e: Exception) { return null }
            groupKeysFetchedAt = System.currentTimeMillis()
            for (g in groups) {
                val raw = g.encryptedKeyB64
                if (raw.isBlank()) continue
                val parsed: GroupKey? = try {
                    if (raw.trimStart().startsWith("{")) {
                        val o = JSONObject(raw)
                        val env = E2ECrypto.Envelope(
                            o.getString("sender_pubkey_b64"),
                            o.getString("nonce_b64"),
                            o.getString("ciphertext_b64"),
                        )
                        GroupKey(
                            E2ECrypto.SymmetricKey(crypto.decrypt(env, keys)),
                            isE2E = true,
                            role = g.role,
                            linkedGroupId = g.linkedGroupId,
                            isChannel = g.isChannel,
                        )
                    } else {
                        GroupKey(
                            E2ECrypto.SymmetricKey(raw),
                            isE2E = false,
                            role = g.role,
                            linkedGroupId = g.linkedGroupId,
                            isChannel = g.isChannel,
                        )
                    }
                } catch (e: Exception) { null }
                if (parsed != null) groupKeys[g.id.lowercase()] = parsed
            }
            return groupKeys[id]
        } finally {
            groupKeysMutex.unlock()
        }
    }

    suspend fun isPeerNotE2E(peerId: String): Boolean {
        val gk = try { groupKeyFor(peerId) } catch (e: Exception) { null }
        return gk != null && !gk.isE2E
    }

    suspend fun wrapGroupKeyFor(memberId: String, groupKeyB64: String): String {
        val memberPk = if (memberId.equals(myId, ignoreCase = true)) keys.publicB64
        else trustStore.keyForSending(memberId)
        val env = crypto.encrypt(groupKeyB64, keys, memberPk)
        return JSONObject()
            .put("sender_pubkey_b64", env.senderPubkeyB64)
            .put("nonce_b64", env.nonceB64)
            .put("ciphertext_b64", env.ciphertextB64)
            .toString()
    }

    fun cacheGroupKey(groupId: String, keyB64: String, linkedGroupId: String? = null) {
        groupKeys[groupId.lowercase()] =
            GroupKey(E2ECrypto.SymmetricKey(keyB64), isE2E = true, role = "admin", linkedGroupId = linkedGroupId)
    }

    suspend fun canPostTo(peerId: String): Boolean {
        val gk = try { groupKeyFor(peerId) } catch (e: Exception) { null }
        return gk == null || !gk.isChannel || gk.role == "admin"
    }

    suspend fun discussionGroupFor(peerId: String): String? {
        val gk = try { groupKeyFor(peerId) } catch (e: Exception) { null }
        return gk?.linkedGroupId?.takeIf { it.isNotBlank() }
    }

    fun newGroupKeyB64(): String = crypto.generateSymmetricKey().keyB64

    suspend fun groupKeyB64For(groupId: String): String? = groupKeyFor(groupId)?.key?.keyB64

    suspend fun registerCreatedGroup(groupId: String, name: String, keyB64: String, isChannel: Boolean) {
        cacheGroupKey(groupId, keyB64)
        if (store.getChat(groupId) == null) {
            store.insertChat(
                ChatEntity(peerId = groupId, name = name, type = if (isChannel) 2 else 1)
            )
        }
    }

    private suspend fun olmAccountLocked(): String {
        store.metaGet("olm_account")?.takeIf(String::isNotBlank)?.let { return it }
        return uniffi.sm_core.olmAccountNew().also { store.metaSet("olm_account", it) }
    }

    private suspend fun myOlmIdentityLocked(): String {
        if (cachedOlmIdentity.isBlank()) {
            cachedOlmIdentity = uniffi.sm_core.olmAccountIdentity(olmAccountLocked())
        }
        return cachedOlmIdentity
    }

    suspend fun myOlmIdentity(): String = ratchetMutex.withLock { myOlmIdentityLocked() }

    // --- Multi-device: десктоп НИКОГДА не занимает 'primary'. Существующая
    // установка узнаёт себя по identity в директории, свежая берёт desktop-слот. ---
    private var cachedDeviceId: String = ""

    private suspend fun myDeviceIdLocked(): String {
        if (cachedDeviceId.isNotBlank()) return cachedDeviceId
        store.metaGet("device_id")?.takeIf(String::isNotBlank)?.let {
            cachedDeviceId = it
            return it
        }
        // Сетевая ошибка пробрасывается: нельзя вслепую занять чужой слот.
        val devices = api.listDevices(myId, myId)
        val identity = myOlmIdentityLocked()
        val resolved = devices.firstOrNull { it.identityKeyB64 == identity }?.deviceId
            ?: ("desktop-" + UUID.randomUUID().toString().replace("-", "").take(10))
        cachedDeviceId = resolved
        store.metaSet("device_id", resolved)
        return resolved
    }

    suspend fun myDeviceId(): String = ratchetMutex.withLock { myDeviceIdLocked() }

    private fun sessionKeyOf(peerId: String, deviceId: String) =
        if (deviceId == "primary") peerId else "$peerId::$deviceId"

    private val peerDevicesCache =
        java.util.concurrent.ConcurrentHashMap<String, Pair<List<uniffi.sm_core.DeviceInfo>, Long>>()

    private fun peerDevices(peerId: String, force: Boolean = false): List<uniffi.sm_core.DeviceInfo> {
        val id = peerId.lowercase()
        peerDevicesCache[id]?.let { (cached, at) ->
            if (!force && System.currentTimeMillis() - at < 60_000) return cached
        }
        val devices = api.listDevices(myId, id)
        // Пустой список НЕ кэшируем: собеседник мог ещё не опубликовать ключи
        // (первый запуск), и минута кэша превратилась бы в минуту недоставки.
        if (devices.isNotEmpty()) peerDevicesCache[id] = devices to System.currentTimeMillis()
        return devices
    }

    private suspend fun ensureOlmKeysLocked() {
        val account = olmAccountLocked()
        val device = myDeviceIdLocked()
        if (api.olmKeysCountDevice(myId, device) >= 20u) return
        val published = uniffi.sm_core.olmAccountGenerateOtks(account, 40u)
        store.metaSet("olm_account", published.accountPickle)
        cachedOlmIdentity = published.identityKeyB64
        api.uploadOlmKeysDevice(myId, published.identityKeyB64, published.oneTimeKeysJson, device)
        peerDevicesCache.clear()
    }

    suspend fun ensureOlmKeys() = ratchetMutex.withLock { ensureOlmKeysLocked() }

    private suspend fun olmSession(peerId: String): String? =
        if (store.metaGet("olm_session_reset.${peerId.lowercase()}") == "1") null
        else store.olmSessionGet(peerId)

    private suspend fun saveOlmSession(peerId: String, session: String) {
        store.olmSessionSet(peerId, session)
        store.metaSet("olm_session_reset.${peerId.lowercase()}", "0")
    }

    private suspend fun encryptGroupWire(peerId: String, wire: String): Map<String, Any> {
        val gk = groupKeyFor(peerId) ?: throw GroupKeyUnavailableException(peerId)
        val env = crypto.encryptFile(wire.toByteArray(Charsets.UTF_8), gk.key)
        return mapOf(
            "is_group" to "1",
            "nonce_b64" to env.nonceB64,
            "ciphertext_b64" to env.ciphertextB64,
        )
    }

    private suspend fun encryptDirectForDeviceLocked(id: String, deviceId: String, wire: String): Map<String, Any> {
        val key = sessionKeyOf(id, deviceId)
        var session = olmSession(key)
        if (session == null) {
            val bundle = try {
                api.claimOlmKeysDevice(myId, id, deviceId)
            } catch (e: RelayApi.HttpError) {
                if (e.code == 404) throw PeerKeysUnavailableException(id, deviceId) else throw e
            }
            olmTrustStore.checkForSending(key, bundle.identityKeyB64)
            session = uniffi.sm_core.olmCreateOutbound(
                olmAccountLocked(),
                bundle.identityKeyB64,
                bundle.oneTimeKeyB64,
            )
        }
        val encrypted = uniffi.sm_core.olmEncrypt(session, wire)
        saveOlmSession(key, encrypted.sessionPickle)
        return mapOf(
            "ratchet" to "1",
            "olm_identity" to myOlmIdentityLocked(),
            "sender_device" to myDeviceIdLocked(),
            "type" to encrypted.messageType.toInt(),
            "body_b64" to encrypted.bodyB64,
        )
    }

    /** Единая отправка wire-нагрузки: группа — общий ключ, личка — Olm-fanout. */
    private suspend fun sendWire(peerId: String, wire: String, clientMsgId: String? = null): String {
        if (isGroupPeer(peerId)) {
            return api.sendMessage(myId, peerId, encryptGroupWire(peerId, wire), clientMsgId = clientMsgId)
        }
        val id = peerId.lowercase()
        // Сбой запроса директории НЕ должен молча ужимать fanout до primary:
        // копия для остальных устройств пропала бы навсегда. Пробрасываем —
        // outbox повторит с backoff. Пустой список — легаси-аккаунт без
        // зарегистрированных устройств, там primary законен.
        val devices = peerDevices(id).ifEmpty { listOf(uniffi.sm_core.DeviceInfo("primary", "")) }
        var firstId: String? = null
        var firstError: Exception? = null
        for ((index, dev) in devices.withIndex()) {
            try {
                val envelope = ratchetMutex.withLock { encryptDirectForDeviceLocked(id, dev.deviceId, wire) }
                val cid = when {
                    index == 0 -> clientMsgId
                    clientMsgId != null -> "$clientMsgId::${dev.deviceId}"
                    else -> UUID.randomUUID().toString()
                }
                val mid = api.sendMessageDevice(myId, peerId, envelope, cid, dev.deviceId)
                if (firstId == null) firstId = mid
            } catch (e: Exception) {
                if (firstError == null) firstError = e
            }
        }
        return firstId ?: throw (firstError ?: IllegalStateException("У получателя нет доступных устройств"))
    }

    // ------------------------------------------------------------------
    // Жизненный цикл
    // ------------------------------------------------------------------

    fun start() {
        scope.launch {
            ensureChatExists(myId, fetchProfile = false)
            syncDialogs()
            warmUiCache()
        }
        scope.launch {
            // Привязка сессии к устройству идёт ПЕРЕД публикацией ключей: сервер
            // разрешает менять identity слота только сессии, привязанной к нему.
            runCatching { api.bindSessionDevice(myId, myDeviceId()) }
            runCatching { ensureOlmKeys() }
            while (true) {
                syncInbox()
                delay(10_000)
            }
        }
        scope.launch {
            while (true) {
                syncGroups()
                delay(60_000)
            }
        }
        scope.launch {
            while (true) {
                if (appActive) runCatching { api.heartbeat() }
                delay(25_000)
            }
        }
        scope.launch { outboxLoop() }
    }

    fun setAppActive(active: Boolean) {
        appActive = active
        if (active) scope.launch { runCatching { api.heartbeat() } }
    }

    /** Раскладка чатов аккаунта (второе устройство: истории нет, диалоги есть). */
    private suspend fun syncDialogs() {
        val dialogs = try { api.getMyDialogs() } catch (_: Exception) { return }
        for (peerId in dialogs) {
            runCatching { ensureChatExists(peerId) }
        }
    }

    private suspend fun syncGroups() {
        val groups = try { api.getMyGroups() } catch (_: Exception) { return }
        for (group in groups) {
            val current = store.getChat(group.id)
            val next = current?.copy(
                name = group.name.ifBlank { current.name },
                type = if (group.isChannel) 2 else 1,
            ) ?: ChatEntity(
                peerId = group.id,
                name = group.name.ifBlank { group.id },
                type = if (group.isChannel) 2 else 1,
            )
            if (current == null) store.insertChat(next) else if (current != next) store.updateChat(next)
        }
    }

    fun shutdown() {
        scope.cancel()
    }

    fun onPushReceived() {
        scope.launch { syncInbox() }
    }

    // ------------------------------------------------------------------
    // Inbox: per-message обработка + ACK после записи в БД
    // ------------------------------------------------------------------

    suspend fun syncInbox() {
        inboxMutex.lock()
        try {
            val device = myDeviceId()
            val msgs = api.fetchInboxDevice(myId, device)
            val ackIds = mutableListOf<String>()
            for (m in msgs) {
                try {
                    val deliveryPeer = processInboxMessage(m)
                    if (deliveryPeer != null) {
                        try {
                            val wire = JSONObject().put("type", "delivered").toString()
                            sendWire(deliveryPeer, wire)
                        } catch (_: Exception) {
                            continue
                        }
                    }
                    ackIds.add(m.id)
                } catch (e: GroupKeyUnavailableException) {
                    // Ключ группы временно недоступен: НЕ ACKаем, придёт в следующем sync.
                } catch (e: RatchetSessionUnavailableException) {
                    // Normal без сессии нельзя потерять: ждём prekey/восстановление.
                } catch (e: Exception) {
                    try {
                        if (store.getMessageByMsgId(m.id) == null) {
                            val peer = peerIdOf(m)
                            ensureChatExists(peer, forceGroup = m.isGroupEnvelope)
                            store.insertMessage(
                                MessageEntity(
                                    msgId = m.id,
                                    peerId = peer,
                                    isOut = false,
                                    text = "⚠️ Не удалось расшифровать сообщение.",
                                    timestamp = timestampOf(m),
                                    status = 1
                                )
                            )
                        }
                        ackIds.add(m.id)
                    } catch (e2: Exception) {
                        // Даже плашку не записали — НЕ подтверждаем, придёт снова.
                    }
                }
            }
            if (ackIds.isNotEmpty()) {
                try { api.ackMessagesDevice(ackIds, device) } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
        } finally {
            inboxMutex.unlock()
        }
    }

    private fun isLegacyGroupId(recipientId: String) =
        recipientId.startsWith("channel_", ignoreCase = true) ||
            recipientId.startsWith("group_", ignoreCase = true)

    private suspend fun isGroupPeer(peerId: String): Boolean =
        isLegacyGroupId(peerId) || store.getChat(peerId)?.type in 1..2 || groupKeyFor(peerId) != null

    private fun peerIdOf(m: RelayApi.InboxMessage) =
        if (m.isGroupEnvelope) m.recipientId else m.senderId

    private fun timestampOf(m: RelayApi.InboxMessage) =
        if (m.createdAtMs > 0) m.createdAtMs else System.currentTimeMillis()

    private fun senderDeviceOf(m: RelayApi.InboxMessage, senderIdentity: String): String {
        JSONObject(m.envelopeJson).optString("sender_device").takeIf(String::isNotBlank)?.let { return it }
        return try {
            var devices = peerDevices(m.senderId)
            if (devices.none { it.identityKeyB64 == senderIdentity }) {
                devices = peerDevices(m.senderId, force = true)
            }
            devices.firstOrNull { it.identityKeyB64 == senderIdentity }?.deviceId ?: "primary"
        } catch (_: Exception) {
            "primary"
        }
    }

    private suspend fun decryptRatchet(m: RelayApi.InboxMessage): String {
        val envelope = JSONObject(m.envelopeJson)
        val senderIdentity = envelope.optString("olm_identity")
        val body = envelope.optString("body_b64")
        val type = envelope.optInt("type", -1)
        require(senderIdentity.isNotBlank() && body.isNotBlank() && type in 0..1) {
            "Некорректный Ratchet-конверт"
        }
        val senderDevice = senderDeviceOf(m, senderIdentity)

        var consumedOneTimeKey = false
        val plaintext = ratchetMutex.withLock {
            val peer = sessionKeyOf(m.senderId.lowercase(), senderDevice)
            olmSession(peer)?.let { session ->
                runCatching { uniffi.sm_core.olmDecrypt(session, type.toUInt(), body) }
                    .getOrNull()
                    ?.let { decrypted ->
                        saveOlmSession(peer, decrypted.sessionPickle)
                        return@withLock decrypted.plaintext
                    }
                if (type != 0) {
                    throw RatchetSessionUnavailableException("Ratchet-сессия рассинхронизирована")
                }
            }
            if (type != 0) {
                throw RatchetSessionUnavailableException("Нет Ratchet-сессии для normal-сообщения")
            }
            val inbound = uniffi.sm_core.olmCreateInbound(
                olmAccountLocked(),
                senderIdentity,
                body,
            )
            store.metaSet("olm_account", inbound.accountPickle)
            saveOlmSession(peer, inbound.sessionPickle)
            consumedOneTimeKey = true
            inbound.plaintext
        }
        if (consumedOneTimeKey) scope.launch { runCatching { ensureOlmKeys() } }
        return plaintext
    }

    private suspend fun processInboxMessage(m: RelayApi.InboxMessage): String? {
        store.getMessageByMsgId(m.id)?.let { existing ->
            return if (
                !m.isGroupEnvelope && !existing.isOut &&
                !m.senderId.equals(myId, ignoreCase = true)
            ) m.senderId else null
        }

        if (!m.isGroupEnvelope &&
            !m.senderId.equals(myId, ignoreCase = true) &&
            BlockStore.isBlocked(m.senderId)
        ) return null

        val groupLike = m.isGroupEnvelope
        val msgPeerId = peerIdOf(m)
        val msgTimestamp = timestampOf(m)

        // TOFU: для Ratchet пиним Olm identity per (отправитель, устройство).
        if (!groupLike && !m.senderId.equals(myId, ignoreCase = true)) {
            val envelopeKey = if (m.isRatchetEnvelope) {
                JSONObject(m.envelopeJson).optString("olm_identity")
            } else {
                m.senderPubkeyB64
            }
            val trust = if (m.isRatchetEnvelope) {
                olmTrustStore.checkIncoming(
                    sessionKeyOf(m.senderId.lowercase(), senderDeviceOf(m, envelopeKey)),
                    envelopeKey,
                )
            } else {
                trustStore.checkIncoming(m.senderId, envelopeKey)
            }
            if (trust == KeyTrustStore.IncomingTrust.MISMATCH) {
                ensureChatExists(msgPeerId)
                store.insertMessage(
                    MessageEntity(
                        msgId = m.id,
                        peerId = msgPeerId,
                        isOut = false,
                        text = "⚠️ Сообщение отклонено: ключ отправителя не совпадает с сохранённым. Возможна подмена ключа. Откройте «Цифры безопасности» в чате.",
                        timestamp = msgTimestamp,
                        status = 1
                    )
                )
                return null
            }
        }

        val plain = when {
            m.isRatchetEnvelope -> decryptRatchet(m)
            m.isGroupEnvelope -> {
                val gk = groupKeyFor(msgPeerId) ?: throw GroupKeyUnavailableException(msgPeerId)
                String(crypto.decryptFile(E2ECrypto.Envelope("SYM", m.nonceB64, m.ciphertextB64), gk.key), Charsets.UTF_8)
            }
            else -> {
                // Переходный период: старый direct box только читаем, никогда не отправляем.
                crypto.decrypt(E2ECrypto.Envelope(m.senderPubkeyB64, m.nonceB64, m.ciphertextB64), keys)
            }
        }
        val obj = try { JSONObject(plain) } catch (e: Exception) { null }?.let { normalizeIncomingPayload(it) }
        val ptype = obj?.optString("type") ?: ""

        if (obj != null && ptype == "reaction") {
            val r = uniffi.sm_core.wireDecode(plain) as? uniffi.sm_core.WireMessage.Reaction
            if (r != null) {
                val existing = store.getMessageByMsgId(r.target)
                if (existing != null && existing.peerId.equals(msgPeerId, ignoreCase = true)) {
                    val map = parseReactions(existing.reactions)
                    if (r.emoji.isBlank()) map.remove(m.senderId) else map.put(m.senderId, r.emoji)
                    store.updateReactions(r.target, map.toString())
                }
            }
            return null
        }
        if (obj != null && ptype == "read") {
            if (!groupLike) store.markOutgoingRead(m.senderId)
            return null
        }
        if (obj != null && ptype == "delivered") {
            if (!groupLike) store.markOutgoingDelivered(m.senderId)
            return null
        }
        if (obj != null && ptype == "edit") {
            val target = obj.optString("target")
            val newText = obj.optString("text")
            if (target.isNotBlank()) {
                val original = store.getMessageByMsgId(target)
                if (original != null && !original.isOut && original.peerId.equals(msgPeerId, ignoreCase = true)) {
                    store.updateText(target, newText)
                }
            }
            return null
        }

        if (obj != null && ptype == "delete") {
            val targets = listOf(
                obj.optString("target"),
                obj.optString("target_id"),
                obj.optString("message_id")
            ).filter { it.isNotBlank() }.distinct()
            if (!deleteExistingMessage(targets, msgPeerId)) {
                deleteMessageByFingerprint(obj, msgPeerId)
            }
            return null
        }

        if (obj != null && ptype.isNotBlank() && ptype != "text" && ptype != "media") {
            val knownControl = ptype in setOf("pin", "poll_vote", "read_receipt", "sync_sent", "webrtc")
            val hasContent = obj.has("file_id") || obj.optJSONObject("media") != null ||
                obj.optString("text").isNotBlank()
            if (knownControl || !hasContent) return null
            ensureChatExists(msgPeerId, forceGroup = groupLike)
            store.insertMessage(
                MessageEntity(
                    msgId = m.id,
                    peerId = msgPeerId,
                    isOut = false,
                    text = "[Вложение не поддерживается этой версией]",
                    timestamp = msgTimestamp,
                    status = 1
                )
            )
            if (!m.senderId.equals(myId, ignoreCase = true)) store.incrementUnread(msgPeerId)
            return if (!groupLike && !m.senderId.equals(myId, ignoreCase = true)) m.senderId else null
        }

        ensureChatExists(msgPeerId, forceGroup = groupLike)

        val storeText: String
        val replyId: String?
        val replyText: String?
        var fwdFrom: String? = null
        if (obj != null && ptype == "media") {
            storeText = obj.toString(); replyId = null; replyText = null
            fwdFrom = obj.optString("fwd_from").takeIf { it.isNotBlank() }
        } else if (obj != null && ptype == "text") {
            storeText = obj.optString("text")
            replyId = if (obj.has("reply_to_id")) obj.optString("reply_to_id") else null
            replyText = if (obj.has("reply_to_text")) obj.optString("reply_to_text") else null
            fwdFrom = obj.optString("fwd_from").takeIf { it.isNotBlank() }
        } else {
            storeText = plain; replyId = null; replyText = null
        }

        store.insertMessage(
            MessageEntity(
                msgId = m.id,
                peerId = msgPeerId,
                isOut = false,
                text = storeText,
                timestamp = msgTimestamp,
                replyToId = replyId,
                replyToText = replyText,
                status = 1,
                forwardedFrom = fwdFrom
            )
        )
        if (ptype == "media" && shouldAutoCache(storeText)) {
            scope.launch { downloadMedia(storeText) }
        }
        if (!m.senderId.equals(myId, ignoreCase = true)) {
            store.incrementUnread(msgPeerId)
        }
        return if (!groupLike && !m.senderId.equals(myId, ignoreCase = true)) m.senderId else null
    }

    // ------------------------------------------------------------------
    // Outbox: optimistic send с retry
    // ------------------------------------------------------------------

    suspend fun enqueueText(peerId: String, text: String, replyToId: String?, replyToText: String?): Exception? {
        return try {
            ensureChatExists(peerId, fetchProfile = false)
            store.insertMessage(
                MessageEntity(
                    msgId = UUID.randomUUID().toString(),
                    peerId = peerId,
                    isOut = true,
                    text = text,
                    timestamp = System.currentTimeMillis(),
                    replyToId = replyToId,
                    replyToText = replyToText,
                    status = 0
                )
            )
            outboxSignal.trySend(Unit)
            null
        } catch (e: Exception) { e }
    }

    suspend fun enqueueForward(targetPeerId: String, msg: MessageEntity): Exception? {
        return try {
            val fwdFrom = if (msg.isOut) myId else msg.peerId
            ensureChatExists(targetPeerId, fetchProfile = false)
            store.insertMessage(
                MessageEntity(
                    msgId = UUID.randomUUID().toString(),
                    peerId = targetPeerId,
                    isOut = true,
                    text = msg.text,
                    timestamp = System.currentTimeMillis(),
                    status = 0,
                    forwardedFrom = fwdFrom
                )
            )
            outboxSignal.trySend(Unit)
            null
        } catch (e: Exception) { e }
    }

    fun retryMessage(msgId: String) {
        scope.launch {
            store.updateStatus(msgId, 0)
            outboxSignal.trySend(Unit)
        }
    }

    private fun buildWire(msg: MessageEntity): String {
        val isMedia = msg.text.startsWith("{\"type\":\"media\"")
        return if (isMedia) {
            val o = JSONObject(msg.text)
            if (msg.forwardedFrom != null) o.put("fwd_from", msg.forwardedFrom)
            o.toString()
        } else {
            val o = JSONObject().put("type", "text").put("text", msg.text)
            if (msg.replyToId != null) o.put("reply_to_id", msg.replyToId)
            if (msg.replyToText != null) o.put("reply_to_text", msg.replyToText)
            if (msg.forwardedFrom != null) o.put("fwd_from", msg.forwardedFrom)
            o.toString()
        }
    }

    private suspend fun outboxLoop() {
        var backoffMs = 2_000L
        val attempts = HashMap<String, Int>()
        while (true) {
            var transientFailure = false
            val blockedPeers = HashSet<String>()
            for (msg in store.getPendingOutgoing()) {
                if (msg.peerId.lowercase() in blockedPeers) continue
                val localId = localRecordingId(msg.text)
                if (localId != null && !findLocalRecordingFile(localId).let { it.exists() && it.length() > 0L }) {
                    log("Outbox: локальная запись потеряна msg=${msg.msgId}")
                    store.updateStatus(msg.msgId, -1)
                    attempts.remove(msg.msgId)
                    continue
                }
                try {
                    if (localId != null) {
                        uploadLocalRecording(msg)
                    } else {
                        sendWire(msg.peerId, buildWire(msg), clientMsgId = msg.msgId)
                        store.updateStatus(msg.msgId, 1)
                    }
                    attempts.remove(msg.msgId)
                } catch (e: KeyTrustStore.KeyChangedException) {
                    log("Outbox: KeyChanged для ${msg.peerId} msg=${msg.msgId}")
                    store.updateStatus(msg.msgId, -1)
                } catch (e: RelayApi.HttpError) {
                    log("Outbox: HttpError ${e.code} -> ${msg.peerId}: ${e.message}")
                    if (e.code in 400..499) {
                        store.updateStatus(msg.msgId, -1)
                    } else {
                        transientFailure = true
                        blockedPeers.add(msg.peerId.lowercase())
                        if (bumpAttempts(attempts, msg.msgId)) store.updateStatus(msg.msgId, -1)
                    }
                } catch (e: Exception) {
                    log("Outbox: временная ошибка -> ${msg.peerId} msg=${msg.msgId}: ${e.message}")
                    transientFailure = true
                    blockedPeers.add(msg.peerId.lowercase())
                    if (bumpAttempts(attempts, msg.msgId)) store.updateStatus(msg.msgId, -1)
                }
            }
            if (transientFailure) {
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            } else {
                backoffMs = 2_000L
                withTimeoutOrNull(15_000L) { outboxSignal.receive() }
            }
        }
    }

    private fun bumpAttempts(attempts: HashMap<String, Int>, msgId: String): Boolean {
        val n = (attempts[msgId] ?: 0) + 1
        attempts[msgId] = n
        return n >= 20
    }

    // ------------------------------------------------------------------
    // Медиа (File вместо Uri)
    // ------------------------------------------------------------------

    suspend fun sendMedia(peerId: String, files: List<File>, caption: String?): Exception? =
        sendLocalFiles(peerId, files, caption, asFile = false)

    suspend fun sendFiles(peerId: String, files: List<File>, caption: String?): Exception? =
        sendLocalFiles(peerId, files, caption, asFile = true)

    private fun probeMime(file: File): String =
        runCatching { java.nio.file.Files.probeContentType(file.toPath()) }.getOrNull()
            ?: "application/octet-stream"

    private fun queryImageSize(file: File): Pair<Int, Int>? = try {
        javax.imageio.ImageIO.createImageInputStream(file)?.use { stream ->
            val readers = javax.imageio.ImageIO.getImageReaders(stream)
            if (readers.hasNext()) {
                val reader = readers.next()
                try {
                    reader.input = stream
                    reader.getWidth(0) to reader.getHeight(0)
                } finally {
                    reader.dispose()
                }
            } else null
        }
    } catch (_: Exception) {
        null
    }

    private suspend fun sendLocalFiles(peerId: String, files: List<File>, caption: String?, asFile: Boolean): Exception? {
        return try {
            for (file in files) {
                if (!file.isFile || file.length() == 0L) continue
                val symKey = crypto.generateSymmetricKey()
                val tmp = File.createTempFile("aether_up", null)
                val jsonText: String
                try {
                    val nonceB64 = file.inputStream().use { input ->
                        tmp.outputStream().use { output ->
                            crypto.encryptStream(input, output, symKey)
                        }
                    }

                    val fileId = api.uploadFile(tmp)
                    val cacheTarget = mediaCacheFile(fileId, nonceB64, symKey.keyB64)
                    if (!cacheTarget.exists() || cacheTarget.length() == 0L) {
                        file.copyTo(cacheTarget, overwrite = true)
                    }

                    val mimeType = probeMime(file)
                    val jsonObj = JSONObject()
                        .put("type", "media")
                        .put("file_id", fileId)
                        .put("sym_key", symKey.keyB64)
                        .put("mime_type", mimeType)
                        .put("nonce", nonceB64)
                    if (asFile) {
                        jsonObj.put("kind", "file")
                        jsonObj.put("file_name", file.name)
                        jsonObj.put("file_size", file.length())
                    } else {
                        jsonObj.put("kind", mediaKindFor(mimeType))
                        if (mimeType.startsWith("image/")) {
                            queryImageSize(file)?.let { (width, height) ->
                                jsonObj.put("width", width).put("height", height)
                            }
                        }
                    }
                    if (caption != null && file == files.first()) jsonObj.put("caption", caption)
                    jsonText = jsonObj.toString()
                } finally {
                    tmp.delete()
                }

                val clientId = UUID.randomUUID().toString()
                sendWire(peerId, jsonText, clientMsgId = clientId)

                ensureChatExists(peerId)
                store.insertMessage(
                    MessageEntity(
                        msgId = clientId,
                        peerId = peerId,
                        isOut = true,
                        text = jsonText,
                        timestamp = System.currentTimeMillis(),
                        status = 1
                    )
                )
            }
            null
        } catch (e: Exception) { e }
    }

    private data class MediaRef(
        val fileId: String,
        val nonce: String,
        val key: String,
        val kind: String,
        val mime: String,
        val sizeBytes: Long,
    )

    private fun mediaRef(jsonText: String): MediaRef? {
        return try {
            val obj = JSONObject(jsonText)
            val media = obj.optJSONObject("media")
            val fileId = firstString(obj, "file_id", "fileId", "id")
                ?: media?.let { firstString(it, "file_id", "fileId", "id") }
                ?: return null
            val key = firstString(obj, "sym_key", "symKey", "key", "key_b64")
                ?: media?.let { firstString(it, "sym_key", "symKey", "key", "key_b64") }
                ?: return null
            val nonce = firstString(obj, "nonce", "nonce_b64", "iv")
                ?: media?.let { firstString(it, "nonce", "nonce_b64", "iv") }
                ?: return null
            val kind = firstString(obj, "kind", "type")
                ?: media?.let { firstString(it, "kind", "type") }
                ?: ""
            val mime = firstString(obj, "mime_type", "mimeType", "mime")
                ?: media?.let { firstString(it, "mime_type", "mimeType", "mime") }
                ?: ""
            val size = obj.optLong("file_size", 0L).takeIf { it > 0L }
                ?: media?.optLong("file_size", 0L)?.takeIf { it > 0L }
                ?: 0L
            MediaRef(fileId, nonce, key, kind, mime, size)
        } catch (_: Exception) {
            null
        }
    }

    private fun mediaCacheFile(fileId: String, nonce: String, key: String): File =
        MediaCache.fileFor(cacheRoot, "$fileId|$nonce|$key")

    private fun localRecordingFile(id: String): File =
        MediaCache.outboxFileFor(cacheRoot, "outgoing-recording|$id")

    private fun findLocalRecordingFile(id: String): File = localRecordingFile(id)

    private fun localRecordingId(jsonText: String): String? = try {
        JSONObject(jsonText).optString("local_id")
            .takeIf { it.isNotBlank() && runCatching { UUID.fromString(it) }.isSuccess }
    } catch (_: Exception) {
        null
    }

    private fun cachedLocalRecording(jsonText: String): File? =
        localRecordingId(jsonText)?.let(::findLocalRecordingFile)
            ?.takeIf { it.exists() && it.length() > 0L }

    fun cachedMediaFile(jsonText: String): File? =
        cachedLocalRecording(jsonText) ?: mediaRef(jsonText)?.let {
            mediaCacheFile(it.fileId, it.nonce, it.key)
                .takeIf { file -> file.exists() && file.length() > 0L }
        }

    suspend fun downloadMedia(jsonText: String): File? {
        return try {
            cachedLocalRecording(jsonText)?.let { return it }
            val ref = mediaRef(jsonText) ?: return null
            val cacheKey = "${ref.fileId}|${ref.nonce}|${ref.key}"
            val cacheFile = mediaCacheFile(ref.fileId, ref.nonce, ref.key)
            if (cacheFile.exists() && cacheFile.length() > 0L) return cacheFile

            val lock = mediaDownloadLocks.getOrPut(cacheKey) { Mutex() }
            val result = lock.withLock {
                if (cacheFile.exists() && cacheFile.length() > 0L) return@withLock cacheFile
                val encryptedBytes = api.downloadFile(ref.fileId)
                val plain = crypto.decryptBytes(
                    encryptedBytes,
                    ref.nonce,
                    E2ECrypto.SymmetricKey(ref.key),
                )
                cacheBytes(plain, cacheFile)
                cacheFile
            }
            mediaDownloadLocks.remove(cacheKey)
            result
        } catch (_: Exception) {
            null
        }
    }

    private fun shouldAutoCache(jsonText: String): Boolean {
        val ref = mediaRef(jsonText) ?: return false
        if (ref.kind == "image" || ref.mime.startsWith("image/")) return true
        val voiceLike = ref.kind in setOf("voice", "video_note", "video_msg") ||
            ref.mime.startsWith("audio/")
        if (!voiceLike) return false
        return ref.sizeBytes <= 0L || ref.sizeBytes <= 15L * 1024 * 1024
    }

    private suspend fun warmUiCache() {
        store.preloadRecentMessages()
        for (peerId in store.recentPeerIds(4)) {
            store.cachedMessages(peerId)
                .asReversed()
                .asSequence()
                .filter { shouldAutoCache(it.text) }
                .take(2)
                .forEach { downloadMedia(it.text) }
        }
    }

    private fun cacheBytes(bytes: ByteArray, target: File) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) tmp.copyTo(target, overwrite = true)
        } finally {
            tmp.delete()
        }
    }

    private fun mediaKindFor(mimeType: String): String = when {
        mimeType.startsWith("image/") -> "image"
        mimeType.startsWith("video/") -> "video"
        else -> "file"
    }

    private suspend fun uploadLocalRecording(msg: MessageEntity) {
        val localId = localRecordingId(msg.text)
            ?: throw IllegalArgumentException("Нет локальной записи")
        val source = findLocalRecordingFile(localId)
        require(source.exists() && source.length() > 0L) { "Локальная запись потеряна" }

        val payload = JSONObject(msg.text)
        val symKey = crypto.generateSymmetricKey()
        val encrypted = File.createTempFile("aether_rec", null)
        val nonceB64: String
        val fileId: String
        try {
            nonceB64 = source.inputStream().use { input ->
                encrypted.outputStream().use { output ->
                    crypto.encryptStream(input, output, symKey)
                }
            }
            fileId = api.uploadFile(encrypted)
        } finally {
            encrypted.delete()
        }

        val finalCache = mediaCacheFile(fileId, nonceB64, symKey.keyB64)
        if (!finalCache.exists() || finalCache.length() == 0L) {
            source.copyTo(finalCache, overwrite = true)
        }
        payload.remove("local_id")
        val wireJson = payload
            .put("file_id", fileId)
            .put("sym_key", symKey.keyB64)
            .put("nonce", nonceB64)
            .toString()

        store.updatePayload(msg.msgId, wireJson)
        source.delete()
        sendWire(msg.peerId, wireJson, clientMsgId = msg.msgId)
        store.updateStatus(msg.msgId, 1)
    }

    private fun normalizeIncomingPayload(obj: JSONObject): JSONObject {
        val type = obj.optString("type")
        if (type == "media") return normalizeMediaPayload(obj, fallbackKind = null)
        if (type !in setOf("image", "video", "voice", "video_msg", "video_note", "circle", "audio", "file")) return obj

        val media = obj.optJSONObject("media")
        val out = JSONObject().put("type", "media")
        putFirst(out, "file_id", obj, media, "file_id", "fileId", "id")
            ?: obj.optString("content").takeIf { it.isNotBlank() && !it.startsWith("data:") }?.let { out.put("file_id", it) }
        putFirst(out, "sym_key", obj, media, "sym_key", "symKey", "key", "key_b64")
        putFirst(out, "nonce", obj, media, "nonce", "nonce_b64", "iv")
        putFirst(out, "mime_type", obj, media, "mime_type", "mimeType", "mime")
        putFirst(out, "file_name", obj, media, "file_name", "fileName", "filename", "name")
        putFirst(out, "caption", obj, media, "caption")
            ?: obj.optString("text").takeIf { it.isNotBlank() }?.let { out.put("caption", it) }
        if (obj.has("file_size")) out.put("file_size", obj.optLong("file_size"))
        if (obj.has("fileSize")) out.put("file_size", obj.optLong("fileSize"))
        if (media?.has("file_size") == true) out.put("file_size", media.optLong("file_size"))
        if (media?.has("fileSize") == true) out.put("file_size", media.optLong("fileSize"))
        for (key in listOf("width", "height")) {
            when {
                obj.has(key) -> out.put(key, obj.optInt(key))
                media?.has(key) == true -> out.put(key, media.optInt(key))
            }
        }
        (obj.optJSONArray("waveform") ?: media?.optJSONArray("waveform"))
            ?.let { out.put("waveform", it) }
        if (obj.has("duration")) out.put("duration", obj.optDouble("duration"))
        return normalizeMediaPayload(out, fallbackKind = when (type) {
            "image" -> "image"
            "video" -> "video"
            "voice" -> "voice"
            "video_msg", "video_note", "circle" -> "video_msg"
            else -> "file"
        })
    }

    private fun normalizeMediaPayload(obj: JSONObject, fallbackKind: String?): JSONObject {
        if (!obj.has("kind") || obj.optString("kind").isBlank()) {
            val mime = firstString(obj, "mime_type", "mimeType", "mime") ?: ""
            obj.put("kind", fallbackKind ?: mediaKindFor(mime))
        }
        if (obj.optString("kind") in setOf("video_msg", "circle")) {
            obj.put("kind", "video_note")
        }
        if (!obj.has("waveform")) {
            obj.optJSONObject("media")?.optJSONArray("waveform")?.let { obj.put("waveform", it) }
        }
        return obj
    }

    private fun putFirst(
        out: JSONObject,
        target: String,
        primary: JSONObject,
        nested: JSONObject?,
        vararg keys: String
    ): String? {
        val value = firstString(primary, *keys) ?: nested?.let { firstString(it, *keys) }
        if (!value.isNullOrBlank()) out.put(target, value)
        return value
    }

    private fun firstString(obj: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val value = obj.optString(key, "")
            if (value.isNotBlank()) return value
        }
        return null
    }

    private suspend fun deleteExistingMessage(targets: List<String>, peerId: String): Boolean {
        for (target in targets) {
            val original = store.getMessageByMsgId(target)
            if (original != null && original.peerId.equals(peerId, ignoreCase = true)) {
                store.deleteByMsgId(target)
                return true
            }
        }
        return false
    }

    private suspend fun deleteMessageByFingerprint(obj: JSONObject, peerId: String): Boolean {
        val targetText = obj.optString("target_text", "")
        if (targetText.isBlank()) return false
        val targetTs = obj.optLong("target_ts", 0L)
        val messages = store.getMessagesForPeerOnce(peerId)
        val match = messages.firstOrNull { candidate ->
            candidate.text == targetText &&
                (targetTs <= 0L || kotlin.math.abs(candidate.timestamp - targetTs) <= 10 * 60_000L)
        } ?: return false
        store.deleteByMsgId(match.msgId)
        return true
    }

    // ------------------------------------------------------------------
    // Контрол-сообщения
    // ------------------------------------------------------------------

    suspend fun react(peerId: String, targetMsgId: String, emoji: String) {
        try {
            val existing = store.getMessageByMsgId(targetMsgId)
            if (existing != null) {
                val map = parseReactions(existing.reactions)
                if (emoji.isBlank()) map.remove(myId) else map.put(myId, emoji)
                store.updateReactions(targetMsgId, map.toString())
            }
            val wire = uniffi.sm_core.wireEncode(
                uniffi.sm_core.WireMessage.Reaction(target = targetMsgId, emoji = emoji)
            )
            sendWire(peerId, wire)
        } catch (e: Exception) {}
    }

    suspend fun sendReadReceipt(peerId: String) {
        try {
            val unread = store.getChat(peerId)?.unreadCount ?: 0
            if (unread > 0) store.clearUnread(peerId)
            if (isGroupPeer(peerId)) return
            if (unread == 0) return
            val wire = JSONObject().put("type", "read")
            sendWire(peerId, wire.toString())
        } catch (e: Exception) {}
    }

    suspend fun editMessage(peerId: String, msgId: String, newText: String): Exception? {
        return try {
            val wire = JSONObject()
                .put("type", "edit")
                .put("target", msgId)
                .put("text", newText)
            sendWire(peerId, wire.toString())
            store.updateText(msgId, newText)
            null
        } catch (e: Exception) { e }
    }

    suspend fun deleteForMe(msgId: String): Exception? {
        return try {
            store.deleteByMsgId(msgId)
            null
        } catch (e: Exception) { e }
    }

    suspend fun deleteForEveryone(peerId: String, msgId: String): Exception? {
        return try {
            val original = store.getMessageByMsgId(msgId)
            val wire = JSONObject()
                .put("type", "delete")
                .put("target", msgId)
                .put("target_id", msgId)
            if (original != null) {
                wire
                    .put("target_text", original.text)
                    .put("target_ts", original.timestamp)
                    .put("target_is_out", original.isOut)
            }
            sendWire(peerId, wire.toString())
            store.deleteByMsgId(msgId)
            null
        } catch (e: Exception) { e }
    }

    // ------------------------------------------------------------------
    // Вспомогательное
    // ------------------------------------------------------------------

    suspend fun ensureChatExists(peerId: String, fetchProfile: Boolean = true, forceGroup: Boolean = false) {
        val existing = store.getChat(peerId)
        if (peerId.equals(myId, ignoreCase = true)) {
            val saved = existing?.copy(
                name = "Избранное",
                type = 3,
                isPinned = true,
                isArchived = false,
                unreadCount = 0
            ) ?: ChatEntity(
                peerId = myId,
                name = "Избранное",
                type = 3,
                isPinned = true
            )
            if (existing == null) store.insertChat(saved) else store.updateChat(saved)
            return
        }
        if (existing != null && (!forceGroup || existing.type in 1..2)) return
        val group = if (forceGroup || isLegacyGroupId(peerId)) {
            try { api.getMyGroups().firstOrNull { it.id.equals(peerId, ignoreCase = true) } }
            catch (_: Exception) { null }
        } else null
        val type = when {
            group?.isChannel == true || peerId.startsWith("channel_", ignoreCase = true) -> 2
            group != null || forceGroup || peerId.startsWith("group_", ignoreCase = true) -> 1
            else -> 0
        }
        var resolvedAvatar: String? = null
        var resolvedStatusEmoji: String? = null
        val resolvedName = if (type == 0 && fetchProfile) {
            try {
                val p = api.getUserProfile(peerId)
                resolvedAvatar = p.avatarFileId
                resolvedStatusEmoji = p.statusEmoji
                p.displayName.ifBlank { p.username.ifBlank { peerId } }
            } catch (e: Exception) { peerId }
        } else if (type != 0 && fetchProfile) {
            group?.name?.ifBlank { existing?.name ?: peerId } ?: existing?.name ?: peerId
        } else peerId
        val chat = existing?.copy(
            name = resolvedName,
            type = type,
            avatarFileId = resolvedAvatar ?: existing.avatarFileId,
            statusEmoji = resolvedStatusEmoji ?: existing.statusEmoji,
        ) ?: ChatEntity(
            peerId = peerId,
            name = resolvedName,
            type = type,
            avatarFileId = resolvedAvatar,
            statusEmoji = resolvedStatusEmoji,
        )
        if (existing == null) store.insertChat(chat) else store.updateChat(chat)
    }

    private fun parseReactions(raw: String): JSONObject =
        try { JSONObject(if (raw.isBlank()) "{}" else raw) } catch (e: Exception) { JSONObject() }

    private fun log(message: String) {
        println("[repo] $message")
    }
}
