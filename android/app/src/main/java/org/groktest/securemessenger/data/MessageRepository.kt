package org.groktest.securemessenger.data

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.groktest.securemessenger.api.RelayApi
import org.groktest.securemessenger.crypto.E2ECrypto
import org.groktest.securemessenger.crypto.KeyTrustStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * (#A2) Слой данных мессенджера: синхронизация инбокса, очередь отправки (outbox),
 * крипта и контрол-сообщения. Вынесен из MainActivity — UI только делегирует сюда.
 *
 * Жизненный цикл: создаётся после логина, [start] запускает фоновые циклы
 * в собственном scope, [shutdown] гасит всё (logout / повторный вход).
 *
 * Отправка текста — optimistic (телеграм-паттерн):
 *  1. [enqueueText] мгновенно пишет сообщение в Room со status=0 («отправляется») —
 *     пузырь появляется сразу, поле ввода не ждёт сеть;
 *  2. outbox-цикл шифрует и шлёт по порядку; client_id = msgId, поэтому ретрай
 *     после обрыва сети не создаёт дубликат на сервере;
 *  3. успех → status=1; постоянная ошибка (4xx, смена ключа) → status=-1
 *     (UI показывает ошибку и пункт «Повторить отправку»);
 *  4. временная ошибка (сеть/5xx) → экспоненциальный backoff, сообщения того же
 *     чата ждут (порядок внутри чата сохраняется), другие чаты не блокируются.
 */
class MessageRepository(
    private val api: RelayApi,
    serverId: String,
    private val keys: E2ECrypto.KeyPair,
    val myId: String,
    private val store: CoreStore,
    private val trustStore: KeyTrustStore,
    private val olmTrustStore: KeyTrustStore,
    private val resolver: ContentResolver,
    private val cacheRoot: File,
) {
    private val crypto = E2ECrypto()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inboxMutex = Mutex()
    private val ratchetMutex = Mutex()
    private var cachedOlmIdentity = ""
    // CONFLATED: множественные сигналы «есть что отправить» схлопываются в один
    private val outboxSignal = Channel<Unit>(Channel.CONFLATED)
    /**
     * Готовность к отправке: сессия привязана к устройству, наши ключи
     * опубликованы. Без этих ворот outbox стартовал одновременно с публикацией
     * и первое сообщение после чистой установки уходило раньше ключей —
     * падало и уползало в откат до 30 секунд.
     */
    private val sendingReady = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val mediaDownloadLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val transportRouter = TransportRouter(store, listOf(ServerTransport(serverId)))
    @Volatile private var appActive = true

    // ------------------------------------------------------------------
    // (#A3) Групповые ключи: общий симметричный ключ, завёрнутый box'ом
    // на публичный ключ каждого участника (encrypted_key_b64 в group_members).
    // ------------------------------------------------------------------

    /** isE2E=false — легаси-канал: raw-ключ лежал на сервере открыто. */
    private data class GroupKey(
        val key: E2ECrypto.SymmetricKey,
        val isE2E: Boolean,
        val role: String = "member",
        val linkedGroupId: String? = null,
        val isChannel: Boolean = false,
    )

    /**
     * Ключ группы недоступен: не участник, сбой сети при получении или
     * ключ не развернулся. Обрабатывается как ВРЕМЕННАЯ ошибка (ретраи),
     * чтобы сетевой сбой не приводил к потере сообщений.
     */
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

    /**
     * Ключ группы из кэша; при промахе — обновление с сервера (не чаще раза в 15с,
     * чтобы чат несуществующей группы не долбил /groups/me на каждый чих).
     * Сетевые сбои не кэшируются: null → вызывающий код ретраит позже.
     */
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
                        // Новый формат: box-конверт {sender_pubkey_b64, nonce_b64, ciphertext_b64}
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
                        // Легаси: raw base64-ключ, известный серверу → НЕ E2E
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

    /**
     * true — чат НЕ защищён сквозным шифрованием (легаси-канал с raw-ключом).
     * Для личных чатов и недоступных групп — false (там другая диагностика).
     */
    suspend fun isPeerNotE2E(peerId: String): Boolean {
        val gk = try { groupKeyFor(peerId) } catch (e: Exception) { null }
        return gk != null && !gk.isE2E
    }

    /** Заворачивает групповой ключ box'ом для участника (формат encrypted_key_b64). */
    suspend fun wrapGroupKeyFor(memberId: String, groupKeyB64: String): String {
        // Себе — на собственный известный ключ (не спрашиваем сервер о самом себе)
        val memberPk = if (memberId.equals(myId, ignoreCase = true)) keys.publicB64
            else trustStore.keyForSending(memberId)
        val env = crypto.encrypt(groupKeyB64, keys, memberPk)
        return JSONObject()
            .put("sender_pubkey_b64", env.senderPubkeyB64)
            .put("nonce_b64", env.nonceB64)
            .put("ciphertext_b64", env.ciphertextB64)
            .toString()
    }

    /** Кладёт свежесозданный ключ в кэш (после создания группы; создатель — админ). */
    fun cacheGroupKey(groupId: String, keyB64: String, linkedGroupId: String? = null) {
        groupKeys[groupId.lowercase()] = GroupKey(E2ECrypto.SymmetricKey(keyB64), isE2E = true, role = "admin", linkedGroupId = linkedGroupId)
    }

    /**
     * (#A6) false — публиковать нельзя (канал, а вы не админ).
     * Личные чаты и группы — всегда true.
     */
    suspend fun canPostTo(peerId: String): Boolean {
        val gk = try { groupKeyFor(peerId) } catch (e: Exception) { null }
        return gk == null || !gk.isChannel || gk.role == "admin"
    }

    /** (#A6) Группа обсуждений канала (null — не подвязана). */
    suspend fun discussionGroupFor(peerId: String): String? {
        val gk = try { groupKeyFor(peerId) } catch (e: Exception) { null }
        return gk?.linkedGroupId?.takeIf { it.isNotBlank() }
    }

    /** Новый случайный групповой ключ (256 бит, base64). */
    fun newGroupKeyB64(): String = crypto.generateSymmetricKey().keyB64

    /** Сырой групповой ключ (base64) — для добавления участника в существующую группу. */
    suspend fun groupKeyB64For(groupId: String): String? = groupKeyFor(groupId)?.key?.keyB64

    /** После создания группы: ключ в кэш + локальный чат с человеческим именем. */
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

    // --- Multi-device: это устройство — отдельный Olm-аккаунт. Существующая
    // установка узнаёт себя по identity в директории (обычно 'primary'),
    // свежая при живом аккаунте занимает новый слот 'android-xxx'. ---
    private var cachedDeviceId: String = ""

    private suspend fun myDeviceIdLocked(): String {
        if (cachedDeviceId.isNotBlank()) return cachedDeviceId
        store.metaGet("device_id")?.takeIf(String::isNotBlank)?.let {
            cachedDeviceId = it
            return it
        }
        // Сетевая ошибка здесь пробрасывается: нельзя вслепую занять 'primary'
        // и затереть ключи телефона-владельца.
        val devices = api.listDevices(myId, myId)
        val identity = myOlmIdentityLocked()
        val resolved = when {
            devices.isEmpty() -> "primary"
            devices.any { it.identityKeyB64 == identity } ->
                devices.first { it.identityKeyB64 == identity }.deviceId
            else -> "android-" + java.util.UUID.randomUUID().toString().replace("-", "").take(10)
        }
        cachedDeviceId = resolved
        store.metaSet("device_id", resolved)
        return resolved
    }

    suspend fun myDeviceId(): String = ratchetMutex.withLock { myDeviceIdLocked() }

    /** Ключ Olm-сессии в сторадже: primary — под старым ключом peerId
     * (существующие сессии переживают апгрейд), остальные — peer::device. */
    private fun sessionKeyOf(peerId: String, deviceId: String) =
        if (deviceId == "primary") peerId else "$peerId::$deviceId"

    // ConcurrentHashMap: читается из inbox-корутины (senderDeviceOf) и пишется из
    // outbox-корутины (sendWire) одновременно — обычный HashMap здесь портился бы.
    private val peerDevicesCache = java.util.concurrent.ConcurrentHashMap<String, Pair<List<uniffi.sm_core.DeviceInfo>, Long>>()

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

    /** Личные сообщения идут только через общее Rust Olm-ядро; box остаётся у групповых ключей. */
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

    /** Единая отправка wire-нагрузки: группа — общий ключ, личка — Olm-копия
     * каждому устройству получателя (multi-device fanout). Возвращает message_id
     * первой копии. */
    private suspend fun sendWire(peerId: String, wire: String, clientMsgId: String? = null): String =
        transportRouter.send(
            DeliveryRequest(
                messageId = clientMsgId,
                peerId = peerId,
                contentKind = contentKindOf(wire),
                sealAndSend = { sendViaServer(peerId, wire, clientMsgId) },
                wire = wire,
            )
        )

    private suspend fun sendViaServer(peerId: String, wire: String, clientMsgId: String?): String {
        if (isGroupPeer(peerId)) {
            return api.sendMessage(myId, peerId, encryptGroupWire(peerId, wire), clientMsgId = clientMsgId)
        }
        val id = peerId.lowercase()
        // Сбой запроса директории НЕ должен молча ужимать fanout до primary:
        // копия для остальных устройств аккаунта пропала бы навсегда (проверено
        // вживую: десктоп не получил сообщение). Пробрасываем — outbox повторит
        // с backoff. Пустой список — легаси-аккаунт без устройств, primary законен.
        val devices = peerDevices(id).ifEmpty {
            listOf(
                uniffi.sm_core.DeviceInfo(
                    deviceId = "primary",
                    identityKeyB64 = "",
                    ed25519KeyB64 = null,
                    identitySigB64 = null,
                    masterKeyB64 = null,
                    deviceSigB64 = null,
                )
            )
        }
        var firstId: String? = null
        var firstError: Exception? = null
        for ((index, dev) in devices.withIndex()) {
            var cidForRetry: String? = null
            try {
                val envelope = ratchetMutex.withLock { encryptDirectForDeviceLocked(id, dev.deviceId, wire) }
                // Детерминированный client_msg_id на копию: не-первичные — msgId::deviceId,
                // чтобы серверная дедупликация работала и при ретраях fanout'а
                // (раньше random UUID на каждый ретрай плодил дубликаты).
                val cid = when {
                    index == 0 -> clientMsgId
                    clientMsgId != null -> copyClientId(clientMsgId, dev.deviceId)
                    else -> java.util.UUID.randomUUID().toString()
                }
                cidForRetry = cid
                val mid = api.sendMessageDevice(myId, peerId, envelope, cid, dev.deviceId)
                if (firstId == null) firstId = mid
            } catch (e: Exception) {
                // Недоставленная копия не выдаётся за успех и не теряется:
                // она уходит в очередь дозасылки (см. retryPendingCopies).
                if (firstError == null) firstError = e
                if (cidForRetry != null) {
                    enqueueCopyRetry(PendingCopy(id, dev.deviceId, wire, cidForRetry))
                }
            }
        }
        return firstId ?: throw (firstError ?: IllegalStateException("У получателя нет доступных устройств"))
    }

    /** Копия сообщения, не доехавшая до конкретного устройства получателя. */
    private data class PendingCopy(
        val peerId: String,
        val deviceId: String,
        val wire: String,
        val clientId: String,
        var attempts: Int = 0,
    )

    private val pendingCopies = java.util.Collections.synchronizedList(mutableListOf<PendingCopy>())

    private fun enqueueCopyRetry(copy: PendingCopy) {
        synchronized(pendingCopies) {
            if (pendingCopies.size >= MAX_PENDING_COPIES) return
            if (pendingCopies.any { it.clientId == copy.clientId && it.deviceId == copy.deviceId }) return
            pendingCopies.add(copy)
        }
    }

    /**
     * Дозасылка копий, не доехавших до отдельных устройств аккаунта (устройство
     * было офлайн, кончились one-time keys). Сообщение у отправителя уже
     * отмечено доставленным — здесь добираются остальные устройства.
     */
    private suspend fun retryPendingCopies() {
        val batch = synchronized(pendingCopies) { pendingCopies.toList() }
        for (copy in batch) {
            val done = try {
                transportRouter.requireAvailableRoute(copy.peerId, contentKindOf(copy.wire))
                val envelope = ratchetMutex.withLock {
                    encryptDirectForDeviceLocked(copy.peerId, copy.deviceId, copy.wire)
                }
                api.sendMessageDevice(myId, copy.peerId, envelope, copy.clientId, copy.deviceId)
                true
            } catch (_: WaitingForNearbyException) {
                false
            } catch (e: Exception) {
                copy.attempts += 1
                if (copy.attempts >= MAX_COPY_ATTEMPTS) {
                    android.util.Log.w(
                        "Outbox",
                        "копия для ${copy.deviceId} (${copy.peerId}) отброшена после ${copy.attempts} попыток",
                        e,
                    )
                    true
                } else {
                    false
                }
            }
            if (done) synchronized(pendingCopies) { pendingCopies.remove(copy) }
        }
    }

    /**
     * Детерминированный client_id копии для конкретного устройства.
     * Сервер принимает client_id ТОЛЬКО как UUID (`uuid.UUID(client_id)`), поэтому
     * прежняя схема «msgId::deviceId» уходила в 400 и копии для вторых устройств
     * молча терялись. Имя-based UUID сохраняет стабильность между ретраями,
     * то есть дедупликацию на сервере.
     */
    private fun copyClientId(clientMsgId: String, deviceId: String): String =
        java.util.UUID.nameUUIDFromBytes("$clientMsgId::$deviceId".toByteArray(Charsets.UTF_8)).toString()

    // ------------------------------------------------------------------
    // Жизненный цикл
    // ------------------------------------------------------------------

    /**
     * Подключить доставку по Bluetooth. Отдельным вызовом, а не в конструкторе:
     * транспорту нужны разрешения, и без них он не должен мешать обычной работе.
     */
    fun enableDirectTransport(context: android.content.Context) {
        val discovery = org.groktest.securemessenger.nearby.NearbyDiscoveryService.get(context)
        val ble = org.groktest.securemessenger.nearby.BleTextTransport(
            context = context,
            myId = myId,
            ownPrekey = { ownPrekeyBundle() },
            seal = { peerId, peer, wire -> sealForDirect(peerId, peer, wire) },
            deliver = { senderId, envelopeJson -> acceptDirect(senderId, envelopeJson) },
        )
        ble.startServer()
        discovery.connectable = true
        transportRouter.register(
            org.groktest.securemessenger.nearby.BleTransport(ble, discovery)
        )
    }

    /** Своя связка для того, кто подключился по Bluetooth. */
    private suspend fun ownPrekeyBundle(): org.groktest.securemessenger.nearby.BleTextTransport.Prekey =
        ratchetMutex.withLock {
            val published = uniffi.sm_core.olmAccountGenerateOtks(olmAccountLocked(), 1u)
            store.metaSet("olm_account", published.accountPickle)
            cachedOlmIdentity = published.identityKeyB64
            val otk = org.json.JSONObject(published.oneTimeKeysJson)
                .optJSONObject("curve25519")
                ?.let { keys -> keys.keys().asSequence().firstOrNull()?.let(keys::getString) }
                ?: throw IllegalStateException("Не удалось выделить одноразовый ключ")
            org.groktest.securemessenger.nearby.BleTextTransport.Prekey(
                userId = myId,
                deviceId = myDeviceIdLocked(),
                identityKeyB64 = published.identityKeyB64,
                oneTimeKeyB64 = otk,
            )
        }

    /**
     * Запечатать текст связкой, полученной по Bluetooth.
     *
     * Ключ проходит то же закрепление, что и серверный: если собеседник нам
     * знаком, а identity не совпал — бросаем, а не доверяем «потому что рядом».
     * Именно здесь ловится подмена знакомого по радио.
     */
    private suspend fun sealForDirect(
        peerId: String,
        peer: org.groktest.securemessenger.nearby.BleTextTransport.Prekey,
        wire: String,
    ): String = ratchetMutex.withLock {
        val id = peerId.lowercase()
        val key = sessionKeyOf(id, peer.deviceId)
        olmTrustStore.checkForSending(key, peer.identityKeyB64)
        val session = olmSession(key) ?: uniffi.sm_core.olmCreateOutbound(
            olmAccountLocked(),
            peer.identityKeyB64,
            peer.oneTimeKeyB64,
        )
        val encrypted = uniffi.sm_core.olmEncrypt(session, wire)
        saveOlmSession(key, encrypted.sessionPickle)
        org.json.JSONObject()
            .put("ratchet", "1")
            .put("olm_identity", myOlmIdentityLocked())
            .put("sender_device", myDeviceIdLocked())
            .put("type", encrypted.messageType.toInt())
            .put("body_b64", encrypted.bodyB64)
            .toString()
    }

    /** Входящее по Bluetooth идёт тем же путём, что и с сервера: дедупликация по mid общая. */
    private suspend fun acceptDirect(senderId: String, envelopeJson: String) {
        processInboxMessage(
            RelayApi.InboxMessage(
                id = "ble:" + java.util.UUID.randomUUID(),
                senderId = senderId,
                recipientId = myId,
                envelopeJson = envelopeJson,
                senderPubkeyB64 = "",
                nonceB64 = "",
                ciphertextB64 = "",
                createdAtMs = System.currentTimeMillis(),
                isRatchetEnvelope = true,
            )
        )
    }

    /** Запуск фоновых циклов: поллинг инбокса (страховка WS) и outbox. */
    fun start() {
        scope.launch {
            ensureChatExists(myId, fetchProfile = false)
            warmUiCache()
        }
        scope.launch {
            // Привязка сессии к крипто-устройству: адресный «выкинуть» на экране
            // «Сессии и безопасность». Здесь, а не в login — сюда сходятся оба пути
            // входа (пароль и восстановление по токену), а device_id уже отрезолвлен.
            // Идёт ПЕРЕД публикацией ключей: сервер разрешает менять identity
            // слота только сессии, привязанной к этому device_id.
            runCatching { api.bindSessionDevice(myId, myDeviceId()) }
            runCatching { ensureOlmKeys() }
            // Открываем и при неудаче: иначе offline-старт запер бы очередь
            // навсегда. Не вышло — outbox сам переспросит с откатом.
            sendingReady.complete(Unit)
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
        scope.launch {
            while (true) {
                store.dueEphemeral().forEach { messageId ->
                    store.getMessageByMsgId(messageId)?.text?.let(::mediaRef)?.let { media ->
                        mediaCacheFile(media.fileId, media.nonce, media.key).delete()
                    }
                    store.purgeEphemeral(messageId)
                }
                delay(1_000)
            }
        }
        scope.launch { outboxLoop() }
    }

    fun setAppActive(active: Boolean) {
        appActive = active
        if (active) scope.launch { runCatching { api.heartbeat() } }
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

    /** Полная остановка (logout). Повторное использование объекта невозможно. */
    fun shutdown() {
        scope.cancel()
    }

    /** Вызывается из WS-пуша: мгновенная синхронизация. */
    fun onPushReceived() {
        scope.launch { syncInbox() }
    }

    // ------------------------------------------------------------------
    // Inbox (#A1): per-message обработка + ACK после записи в Room
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
                        // Сначала квитанция, потом ACK. Иначе при убийстве процесса между
                        // этими действиями отправитель навсегда останется с одной галочкой.
                        try {
                            val wire = JSONObject().put("type", "delivered").toString()
                            sendWire(deliveryPeer, wire)
                        } catch (_: Exception) {
                            continue
                        }
                    }
                    ackIds.add(m.id)
                } catch (e: GroupKeyUnavailableException) {
                    // (#A3) Ключ группы временно недоступен (сеть/только вступили):
                    // НЕ ACKаем и НЕ ставим плашку — сообщение придёт в следующем sync.
                } catch (e: RatchetSessionUnavailableException) {
                    // Normal без подходящей сессии нельзя потерять: ждём prekey/восстановление.
                } catch (e: Exception) {
                    // «Ядовитое» сообщение: сохраняем плашку, чтобы не зацикливалось
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
                        // Даже плашку не записали — НЕ подтверждаем, придёт снова
                    }
                }
            }
            if (ackIds.isNotEmpty()) {
                try { api.ackMessagesDevice(ackIds, device) } catch (e: Exception) {
                    // ACK не дошёл — дедупликация по msgId отбросит повторы
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

    /** Устройство отправителя: из конверта (новые клиенты) или по identity в
     * директории устройств (легаси-конверты без sender_device → primary). */
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

    /**
     * Обработка одного входящего. Бросает исключение при сбое — caller решает про ACK.
     * @return senderId, если это новое личное контентное сообщение (нужна квитанция
     * «доставлено»); иначе null (контрол/группа/своё/дубликат).
     */
    private suspend fun processInboxMessage(m: RelayApi.InboxMessage): String? {
        // Дедупликация: своё сообщение (Избранное, ретраи) уже сохранено при отправке
        store.getMessageByMsgId(m.id)?.let { existing ->
            return if (
                !m.isGroupEnvelope && !existing.isOut &&
                !m.senderId.equals(myId, ignoreCase = true)
            ) m.senderId else null
        }

        // Чёрный список: личные сообщения от заблокированных отбрасываем (caller заACKает).
        if (!m.isGroupEnvelope &&
            !m.senderId.equals(myId, ignoreCase = true) &&
            BlockStore.isBlocked(m.senderId)
        ) return null

        val groupLike = m.isGroupEnvelope
        val msgPeerId = peerIdOf(m)
        val msgTimestamp = timestampOf(m)

        // TOFU: для Ratchet пиним Olm identity, для легаси-box — старый box-ключ.
        if (!groupLike && !m.senderId.equals(myId, ignoreCase = true)) {
            val envelopeKey = if (m.isRatchetEnvelope) {
                JSONObject(m.envelopeJson).optString("olm_identity")
            } else {
                m.senderPubkeyB64
            }
            val trust = if (m.isRatchetEnvelope) {
                // Пин per (отправитель, устройство): у одного аккаунта легально
                // несколько identity-ключей — по одному на устройство.
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
        val logicalId = runCatching { uniffi.sm_core.messageIdFromPayload(plain) }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: m.id
        if (logicalId != m.id) {
            store.getMessageByMsgId(logicalId)?.let { existing ->
                return if (
                    !m.isGroupEnvelope && !existing.isOut &&
                    !m.senderId.equals(myId, ignoreCase = true)
                ) m.senderId else null
            }
        }

        val obj = try { JSONObject(plain) } catch (e: Exception) { null }?.let { normalizeIncomingPayload(it) }
        val ptype = obj?.optString("type") ?: ""

        // Контрол: реакция. Применяем только к сообщениям ТОГО ЖЕ чата —
        // иначе любой контакт, зная UUID, накручивал бы реакции в чужих чатах.
        if (obj != null && ptype == "reaction") {
            // Разбор wire-нагрузки — общим ядром (Rust), единый протокол для всех платформ
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
        // Контрол: собеседник прочитал мои сообщения.
        // Только личные чаты: в группе markOutgoingRead(senderId) пометил бы
        // сообщения ЛИЧНОГО чата с этим участником — чужой контекст.
        if (obj != null && ptype == "read") {
            if (!groupLike) store.markOutgoingRead(m.senderId)
            return null
        }
        // Контрол: собеседник ПОЛУЧИЛ мои сообщения (доставлено, но ещё не прочитал).
        if (obj != null && ptype == "delivered") {
            if (!groupLike) store.markOutgoingDelivered(m.senderId)
            return null
        }
        // Контрол: редактирование. Только автор исходного входящего того же чата.
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

        // Неизвестный типизированный контрол (delete/pin/poll_vote/read_receipt/sync_sent/
        // webrtc/опросы/инлайн-медиа веба и т.п.) — игнорируем, чтобы не засорять чат сырым
        // JSON. Реализованные типы (reaction/read/delivered/edit/text/media) обработаны выше.
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
            // Известные чистые контролы игнорируем молча, как раньше. Но НЕИЗВЕСТНЫЙ
            // тип с контентными признаками (file_id/media{}/text) — это чьё-то
            // сообщение: молчаливый дроп + ACK терял его навсегда. Ставим плашку.
            val knownControl = ptype in setOf("pin", "poll_vote", "read_receipt", "sync_sent", "webrtc")
            val hasContent = obj.has("file_id") || obj.optJSONObject("media") != null ||
                obj.optString("text").isNotBlank()
            if (knownControl || !hasContent) return null
            ensureChatExists(msgPeerId, forceGroup = groupLike)
            store.insertMessage(
                MessageEntity(
                    msgId = logicalId,
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
            storeText = plain; replyId = null; replyText = null
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
                msgId = logicalId,
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
        store.trackEphemeral(logicalId, plain, msgTimestamp)
        if (ptype == "media" && shouldAutoCache(storeText)) {
            scope.launch { downloadMedia(storeText) }
        }
        // (#A4) Своё эхо (Избранное) не считается непрочитанным
        if (!m.senderId.equals(myId, ignoreCase = true)) {
            store.incrementUnread(msgPeerId)
        }
        // Личное контентное сообщение от другого — отправителю нужна квитанция «доставлено».
        return if (!groupLike && !m.senderId.equals(myId, ignoreCase = true)) m.senderId else null
    }

    // ------------------------------------------------------------------
    // Outbox (#A2): optimistic send с retry
    // ------------------------------------------------------------------

    /** Мгновенно сохраняет текст в чат (status=0) и будит очередь. Без сети. */
    suspend fun enqueueText(
        peerId: String,
        text: String,
        replyToId: String?,
        replyToText: String?,
        ephemeral: EphemeralDraft? = null,
    ): Exception? {
        return try {
            ensureChatExists(peerId, fetchProfile = false) // без сети: вставка мгновенная
            val messageId = uniffi.sm_core.newMessageId()
            val timestamp = System.currentTimeMillis()
            val basePayload = uniffi.sm_core.wireEncode(
                uniffi.sm_core.WireMessage.Text(text, replyToId, replyToText, null)
            )
            val ephemeralPayload = ephemeral?.let { draft ->
                uniffi.sm_core.payloadWithEphemeral(
                    basePayload,
                    uniffi.sm_core.EphemeralSpec(
                        kind = draft.kind,
                        ttlSeconds = draft.ttlSeconds,
                        trigger = uniffi.sm_core.EphemeralTrigger.FIRST_OPEN,
                        absoluteMs = null,
                        viewLimit = draft.viewLimit,
                    ),
                )
            } ?: basePayload
            val payload = withMessageId(ephemeralPayload, messageId)
            store.insertMessage(
                MessageEntity(
                    msgId = messageId,
                    peerId = peerId,
                    isOut = true,
                    text = text,
                    timestamp = timestamp,
                    replyToId = replyToId,
                    replyToText = replyToText,
                    status = 0,
                    payloadJson = payload,
                )
            )
            store.trackEphemeral(messageId, payload, timestamp)
            outboxSignal.trySend(Unit)
            null
        } catch (e: Exception) { e }
    }

    /** Пересылка: тоже через outbox (текст и media-JSON реконструируются из entity). */
    suspend fun enqueueForward(targetPeerId: String, msg: MessageEntity): Exception? {
        return try {
            val fwdFrom = if (msg.isOut) myId else msg.peerId
            ensureChatExists(targetPeerId, fetchProfile = false)
            store.insertMessage(
                MessageEntity(
                    msgId = uniffi.sm_core.newMessageId(),
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

    /** Повторная отправка сообщения со status=-1 (пункт меню «Повторить отправку»). */
    fun retryMessage(msgId: String) {
        scope.launch {
            store.updateStatus(msgId, 0)
            outboxSignal.trySend(Unit)
        }
    }

    suspend fun openEphemeral(message: MessageEntity): Boolean = withContext(Dispatchers.IO) {
        message.payloadJson?.let { store.openEphemeral(message.msgId, it) } ?: true
    }

    suspend fun closeEphemeral(message: MessageEntity) = withContext(Dispatchers.IO) {
        message.payloadJson?.let { store.closeEphemeral(message.msgId, it) }
    }

    /** Восстанавливает wire-JSON исходящего из полей entity. */
    private fun buildWire(msg: MessageEntity): String {
        msg.payloadJson?.takeIf(String::isNotBlank)?.let {
            return withMessageId(it, msg.msgId)
        }
        val isMedia = msg.text.startsWith("{\"type\":\"media\"")
        val wire = if (isMedia) {
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
        return withMessageId(wire, msg.msgId)
    }

    private suspend fun outboxLoop() {
        sendingReady.await()
        var backoffMs = 2_000L
        // Счётчик подряд неудачных попыток на сообщение (в памяти): после лимита — status=-1,
        // чтобы один вечно падающий конверт не держал очередь чата бесконечно.
        val attempts = HashMap<String, Int>()
        while (true) {
            retryPendingCopies()
            var transientFailure = false
            // Чаты, в которых случился временный сбой: их сообщения пропускаем,
            // чтобы не нарушить порядок внутри чата; другие чаты продолжают слаться.
            val blockedPeers = HashSet<String>()
            for (msg in store.getPendingOutgoing()) {
                if (msg.peerId.lowercase() in blockedPeers) continue
                val localId = localRecordingId(msg.text)
                if (localId != null && !findLocalRecordingFile(localId).let { it.exists() && it.length() > 0L }) {
                    android.util.Log.w("Outbox", "Local recording is missing for msg=${msg.msgId}")
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
                } catch (e: WaitingForNearbyException) {
                    store.updateStatus(msg.msgId, 4)
                    attempts.remove(msg.msgId)
                } catch (e: KeyTrustStore.KeyChangedException) {
                    android.util.Log.w("Outbox", "KeyChanged for ${msg.peerId} msg=${msg.msgId}", e)
                    store.updateStatus(msg.msgId, -1) // до явного решения пользователя
                } catch (e: RelayApi.HttpError) {
                    android.util.Log.w("Outbox", "HttpError ${e.code} sending to ${msg.peerId}: ${e.message}", e)
                    if (e.code in 400..499) {
                        store.updateStatus(msg.msgId, -1) // постоянная ошибка
                    } else {
                        transientFailure = true
                        blockedPeers.add(msg.peerId.lowercase())
                        if (bumpAttempts(attempts, msg.msgId)) store.updateStatus(msg.msgId, -1)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("Outbox", "Transient send error to ${msg.peerId} msg=${msg.msgId}: ${e.javaClass.simpleName}: ${e.message}", e)
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
                // Спим до сигнала; таймаут — страховка (например, сеть вернулась)
                withTimeoutOrNull(15_000L) { outboxSignal.receive() }
            }
        }
    }

    /** @return true — лимит попыток исчерпан, сообщение пора пометить ошибкой. */
    private fun bumpAttempts(attempts: HashMap<String, Int>, msgId: String): Boolean {
        val n = (attempts[msgId] ?: 0) + 1
        attempts[msgId] = n
        return n >= 20
    }

    private companion object {
        const val MAX_PENDING_COPIES = 500
        const val MAX_COPY_ATTEMPTS = 30
    }

    // ------------------------------------------------------------------
    // Медиа (синхронно, как раньше; стриминг — этап 4)
    // ------------------------------------------------------------------

    suspend fun sendMedia(peerId: String, uris: List<Uri>, caption: String?): Exception? =
        sendUris(peerId, uris, caption, asFile = false)

    /** Отправить выбранное как ДОКУМЕНТ (файл): без сжатия, с именем и размером. */
    suspend fun sendFiles(peerId: String, uris: List<Uri>, caption: String?): Exception? =
        sendUris(peerId, uris, caption, asFile = true)

    /** Имя файла из ContentResolver (DISPLAY_NAME), иначе хвост URI. */
    private fun queryDisplayName(uri: Uri): String {
        try {
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && !c.isNull(i)) return c.getString(i)
                }
            }
        } catch (e: Exception) {}
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    private fun queryFileSize(uri: Uri): Long {
        try {
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (i >= 0 && !c.isNull(i)) return c.getLong(i)
                }
            }
        } catch (e: Exception) {}
        return 0L
    }

    private fun queryImageSize(uri: Uri): Pair<Int, Int>? = try {
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    } catch (_: Exception) {
        null
    }

    private suspend fun sendUris(peerId: String, uris: List<Uri>, caption: String?, asFile: Boolean): Exception? {
        return try {
            for (uri in uris) {
                val selectedMime = resolver.getType(uri) ?: "application/octet-stream"
                transportRouter.requireAvailableRoute(
                    peerId,
                    if (asFile) "file" else mediaKindFor(selectedMime),
                )
                // (#A4) Стриминг: plaintext шифруется кусками во временный файл,
                // файл уходит на сервер тоже стримом. Раньше readBytes() + base64
                // держали в памяти ~3.5 размера файла → OOM на слабых устройствах.
                val symKey = crypto.generateSymmetricKey()
                val tmp = java.io.File.createTempFile("aether_up", null)
                val jsonText: String
                try {
                    val nonceB64 = resolver.openInputStream(uri)?.use { input ->
                        java.io.FileOutputStream(tmp).use { output ->
                            crypto.encryptStream(input, output, symKey)
                        }
                    } ?: continue

                    val fileId = api.uploadFile(tmp)
                    cachePlaintext(uri, mediaCacheFile(fileId, nonceB64, symKey.keyB64))

                    val mimeType = selectedMime
                    val jsonObj = JSONObject()
                        .put("type", "media")
                        .put("file_id", fileId)
                        .put("sym_key", symKey.keyB64)
                        .put("mime_type", mimeType)
                        .put("nonce", nonceB64)
                    if (asFile) {
                        // Документ: отображается строкой с именем/размером, без сжатия.
                        jsonObj.put("kind", "file")
                        jsonObj.put("file_name", queryDisplayName(uri))
                        jsonObj.put("file_size", queryFileSize(uri))
                    } else {
                        jsonObj.put("kind", mediaKindFor(mimeType))
                        if (mimeType.startsWith("image/")) {
                            queryImageSize(uri)?.let { (width, height) ->
                                jsonObj.put("width", width).put("height", height)
                            }
                        }
                    }
                    if (caption != null && uri == uris.first()) jsonObj.put("caption", caption)
                    jsonText = jsonObj.toString()
                } finally {
                    tmp.delete()
                }

                val clientId = uniffi.sm_core.newMessageId()
                val payload = withMessageId(jsonText, clientId)
                sendWire(peerId, payload, clientMsgId = clientId)

                ensureChatExists(peerId)
                store.insertMessage(
                    MessageEntity(
                        msgId = clientId,
                        peerId = peerId,
                        isOut = true,
                        text = payload,
                        timestamp = System.currentTimeMillis(),
                        status = 1
                    )
                )
            }
            null
        } catch (e: Exception) { e }
    }

    /**
     * Запись появляется в чате из локального кеша сразу. Загрузка, шифрование и
     * отправка выполняются общей outbox-очередью и переживают перезапуск процесса.
     */
    suspend fun sendRecording(
        peerId: String,
        source: File,
        mime: String,
        kind: String,
        durationMs: Long,
        waveform: List<Int>? = null
    ): Exception? {
        return try {
            val clientId = uniffi.sm_core.newMessageId()
            val wireKind = if (kind == "video_note" || kind == "circle") "video_msg" else kind
            val localFile = localRecordingFile(clientId)
            if (!source.renameTo(localFile)) {
                source.copyTo(localFile, overwrite = true)
                // rename не прошёл (другой том/ФС) — исходник после копии не нужен,
                // иначе осиротевшие записи копятся в cacheDir.
                source.delete()
            }
            require(localFile.length() > 0L) { "Пустая запись" }
            val localJson = JSONObject()
                .put("type", "media")
                .put("kind", wireKind)
                .put("mime_type", mime)
                .put("duration", durationMs / 1000.0)
                .put("local_id", clientId)
                .apply {
                    // Волна ГС (амплитуды 0..31): хранится в Room вместе с pending-записью
                    // и из uploadLocalRecording уезжает в wire-JSON как есть.
                    if (!waveform.isNullOrEmpty()) put("waveform", JSONArray(waveform))
                }
                .toString()

            ensureChatExists(peerId, fetchProfile = false)
            store.insertMessage(
                MessageEntity(
                    msgId = clientId,
                    peerId = peerId,
                    isOut = true,
                    text = localJson,
                    timestamp = System.currentTimeMillis(),
                    status = 0
                )
            )
            outboxSignal.trySend(Unit)
            null
        } catch (e: Exception) {
            e
        }
    }

    private suspend fun uploadLocalRecording(msg: MessageEntity) {
        transportRouter.requireAvailableRoute(msg.peerId, contentKindOf(msg.text))
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

        // После upload сообщение уже пригодно для обычного сетевого retry.
        store.updatePayload(msg.msgId, wireJson)
        source.delete()
        sendWire(msg.peerId, wireJson, clientMsgId = msg.msgId)
        store.updateStatus(msg.msgId, 1)
    }
    private data class MediaRef(
        val fileId: String,
        val nonce: String,
        val key: String,
        val kind: String,
        val mime: String,
        /** 0 — размер неизвестен (старые клиенты не шлют file_size). */
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

    /** Неотправленные записи живут в filesDir/outbox_media — их не смоет ни
     *  «Очистить кеш», ни авто-очистка cacheDir системой (иначе запись, сделанная
     *  офлайн, терялась безвозвратно до отправки). */
    private fun localRecordingFile(id: String): File =
        MediaCache.outboxFileFor(cacheRoot, "outgoing-recording|$id")

    /** Чтение pending-файла: новый путь, с фолбэком на старую кеш-папку —
     *  записи, сделанные до переноса, дошлются без потери. */
    private fun findLocalRecordingFile(id: String): File {
        val current = localRecordingFile(id)
        if (current.exists() && current.length() > 0L) return current
        val legacy = MediaCache.fileFor(cacheRoot, "outgoing-recording|$id")
        return if (legacy.exists() && legacy.length() > 0L) legacy else current
    }

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
            // Файл в кеше — Mutex больше не нужен: без remove map рос бы всю сессию
            // (по записи на каждое уникальное медиа). При ошибке Mutex остаётся.
            mediaDownloadLocks.remove(cacheKey)
            result
        } catch (_: Exception) {
            null
        }
    }

    /** Автозагрузка при приёме: картинки — всегда; ГС и кружки — как в Telegram,
     *  но крупные (при известном размере > ~15 МБ) качаем только по тапу. */
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

    private fun cachePlaintext(uri: Uri, target: File) {
        if (target.exists() && target.length() > 0L) return
        resolver.openInputStream(uri)?.use { input ->
            val tmp = File(target.parentFile, "${target.name}.tmp")
            try {
                tmp.outputStream().use { output -> input.copyTo(output) }
                if (!tmp.renameTo(target)) tmp.copyTo(target, overwrite = true)
            } finally {
                tmp.delete()
            }
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
        // audio/* из пикера — документ (mp3-музыка ≠ голосовое): kind 'voice'
        // ставит только sendRecording для собственных записей диктофона.
        else -> "file"
    }

    private fun contentKindOf(wire: String): String = runCatching {
        val value = JSONObject(wire)
        when (value.optString("kind").ifBlank { value.optString("type") }) {
            "image" -> "image"
            "video", "video_msg", "video_note", "circle" -> "video"
            "voice", "audio" -> "voice"
            "file", "media" -> "file"
            else -> "text"
        }
    }.getOrDefault("text")

    private fun withMessageId(wire: String, messageId: String): String = runCatching {
        uniffi.sm_core.payloadWithMessageId(wire, messageId)
    }.getOrDefault(wire)

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
        // Волна ГС: массив интов переносим как есть (и top-level, и из media{})
        (obj.optJSONArray("waveform") ?: media?.optJSONArray("waveform"))
            ?.let { out.put("waveform", it) }
        return normalizeMediaPayload(out, fallbackKind = when (type) {
            "image" -> "image"
            "video" -> "video"
            "voice" -> "voice"
            "video_msg", "video_note", "circle" -> "video_msg"
            else -> "file" // в т.ч. 'audio': музыка — документ, не голосовое
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
        // Волна из вложенного media{} — на верхний уровень, где её читает плеер
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
    // Контрол-сообщения (reaction / read / edit) — best effort, как раньше
    // ------------------------------------------------------------------

    /** Локально применяет мою реакцию и шлёт контрол собеседнику. */
    suspend fun react(peerId: String, targetMsgId: String, emoji: String) {
        try {
            val existing = store.getMessageByMsgId(targetMsgId)
            if (existing != null) {
                val map = parseReactions(existing.reactions)
                if (emoji.isBlank()) map.remove(myId) else map.put(myId, emoji)
                store.updateReactions(targetMsgId, map.toString())
            }
            // Wire-нагрузку собирает общее ядро (Rust) — единый протокол для всех платформ
            val wire = uniffi.sm_core.wireEncode(uniffi.sm_core.WireMessage.Reaction(target = targetMsgId, emoji = emoji))
            sendWire(peerId, wire)
        } catch (e: Exception) {}
    }

    /**
     * Контрол «прочитано» — только личные чаты (в группах смысл другой,
     * см. processInboxMessage). (#A4) Шлётся ТОЛЬКО при наличии непрочитанных:
     * открытие давно прочитанного чата не генерирует мусорный трафик.
     * Заодно сбрасывает локальный счётчик unreadCount.
     */
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

    /** Редактирование: сетевой контрол + локальное обновление. */
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

    /** Конверт для отложенной отправки (ScheduledMessageWorker). */
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

    suspend fun encryptForPeer(peerId: String, wireJson: String): Map<String, Any> =
        if (isGroupPeer(peerId)) {
            encryptGroupWire(peerId, wireJson)
        } else {
            // ponytail: отложенные сообщения шифруются заранее одной копией на
            // primary; fanout для scheduled — вместе с переносом истории
            ratchetMutex.withLock { encryptDirectForDeviceLocked(peerId.lowercase(), "primary", wireJson) }
        }

    // ------------------------------------------------------------------
    // Вспомогательное
    // ------------------------------------------------------------------

    /**
     * Создаёт чат в локальной БД, если его ещё нет (имя/аватар — из профиля).
     * @param fetchProfile false — без сетевого запроса профиля (для optimistic-путей,
     *        где вставка должна быть мгновенной); имя обогатится позже из инбокса.
     */
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
            // (#A3) Имя группы/канала вместо технического id
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
}
