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
import kotlinx.coroutines.withTimeoutOrNull
import org.groktest.securemessenger.api.RelayApi
import org.groktest.securemessenger.crypto.E2ECrypto
import org.groktest.securemessenger.crypto.KeyTrustStore
import org.json.JSONObject
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
    private val keys: E2ECrypto.KeyPair,
    val myId: String,
    private val store: CoreStore,
    private val trustStore: KeyTrustStore,
    private val resolver: ContentResolver,
) {
    private val crypto = E2ECrypto()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inboxMutex = Mutex()
    // CONFLATED: множественные сигналы «есть что отправить» схлопываются в один
    private val outboxSignal = Channel<Unit>(Channel.CONFLATED)

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
    )

    /**
     * Ключ группы недоступен: не участник, сбой сети при получении или
     * ключ не развернулся. Обрабатывается как ВРЕМЕННАЯ ошибка (ретраи),
     * чтобы сетевой сбой не приводил к потере сообщений.
     */
    class GroupKeyUnavailableException(groupId: String) :
        IllegalStateException("Нет ключа группы $groupId (вы не участник?)")

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
                        GroupKey(E2ECrypto.SymmetricKey(crypto.decrypt(env, keys)), isE2E = true, role = g.role, linkedGroupId = g.linkedGroupId)
                    } else {
                        // Легаси: raw base64-ключ, известный серверу → НЕ E2E
                        GroupKey(E2ECrypto.SymmetricKey(raw), isE2E = false, role = g.role, linkedGroupId = g.linkedGroupId)
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
        if (!isGroupLike(peerId)) return false
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
        if (!peerId.startsWith("channel_", ignoreCase = true)) return true
        val gk = try { groupKeyFor(peerId) } catch (e: Exception) { null }
        return gk?.role == "admin"
    }

    /** (#A6) Группа обсуждений канала (null — не подвязана). */
    suspend fun discussionGroupFor(peerId: String): String? {
        if (!peerId.startsWith("channel_", ignoreCase = true)) return null
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

    /**
     * (#A3) Единая точка шифрования исходящего wire-JSON:
     *  - личный чат → crypto_box на ключ собеседника (TOFU-пиннинг);
     *  - группа/канал → AES-GCM общим ключом, конверт с is_group=1
     *    (sender_pubkey не нужен — авторство подтверждает сервер по токену).
     */
    private suspend fun encryptWireFor(peerId: String, wire: String): Map<String, String> {
        return if (isGroupLike(peerId)) {
            val gk = groupKeyFor(peerId) ?: throw GroupKeyUnavailableException(peerId)
            val env = crypto.encryptFile(wire.toByteArray(Charsets.UTF_8), gk.key)
            mapOf(
                "is_group" to "1",
                "nonce_b64" to env.nonceB64,
                "ciphertext_b64" to env.ciphertextB64,
            )
        } else {
            crypto.encrypt(wire, keys, trustStore.keyForSending(peerId)).toMap()
        }
    }

    // ------------------------------------------------------------------
    // Жизненный цикл
    // ------------------------------------------------------------------

    /** Запуск фоновых циклов: поллинг инбокса (страховка WS) и outbox. */
    fun start() {
        scope.launch {
            while (true) {
                syncInbox()
                delay(10_000)
            }
        }
        scope.launch { outboxLoop() }
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
            val msgs = api.fetchInbox(myId)
            val ackIds = mutableListOf<String>()
            // Отправители новых личных сообщений — им разошлём квитанцию «доставлено».
            val deliveredTo = HashSet<String>()
            for (m in msgs) {
                try {
                    processInboxMessage(m)?.let { deliveredTo.add(it) }
                    ackIds.add(m.id)
                } catch (e: GroupKeyUnavailableException) {
                    // (#A3) Ключ группы временно недоступен (сеть/только вступили):
                    // НЕ ACKаем и НЕ ставим плашку — сообщение придёт в следующем sync.
                } catch (e: Exception) {
                    // «Ядовитое» сообщение: сохраняем плашку, чтобы не зацикливалось
                    try {
                        if (store.getMessageByMsgId(m.id) == null) {
                            val peer = peerIdOf(m)
                            ensureChatExists(peer)
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
                try { api.ackMessages(ackIds) } catch (e: Exception) {
                    // ACK не дошёл — дедупликация по msgId отбросит повторы
                }
            }
            // Квитанции «доставлено» отправителям (одна на отправителя за синк).
            for (sender in deliveredTo) {
                try {
                    val wire = JSONObject().put("type", "delivered")
                    api.sendMessage(myId, sender, encryptWireFor(sender, wire.toString()))
                } catch (e: Exception) { /* доставится позже при следующем синке */ }
            }
        } catch (e: Exception) {
        } finally {
            inboxMutex.unlock()
        }
    }

    private fun isGroupLike(recipientId: String) =
        recipientId.startsWith("channel_", ignoreCase = true) ||
            recipientId.startsWith("group_", ignoreCase = true)

    private fun peerIdOf(m: RelayApi.InboxMessage) =
        if (isGroupLike(m.recipientId)) m.recipientId else m.senderId

    private fun timestampOf(m: RelayApi.InboxMessage) =
        if (m.createdAtMs > 0) m.createdAtMs else System.currentTimeMillis()

    /**
     * Обработка одного входящего. Бросает исключение при сбое — caller решает про ACK.
     * @return senderId, если это новое личное контентное сообщение (нужна квитанция
     * «доставлено»); иначе null (контрол/группа/своё/дубликат).
     */
    private suspend fun processInboxMessage(m: RelayApi.InboxMessage): String? {
        // Дедупликация: своё сообщение (Избранное, ретраи) уже сохранено при отправке
        if (store.getMessageByMsgId(m.id) != null) return null

        // Чёрный список: личные сообщения от заблокированных отбрасываем (caller заACKает).
        if (!isGroupLike(m.recipientId) &&
            !m.senderId.equals(myId, ignoreCase = true) &&
            BlockStore.isBlocked(m.senderId)
        ) return null

        val env = E2ECrypto.Envelope(m.senderPubkeyB64, m.nonceB64, m.ciphertextB64)
        val groupLike = isGroupLike(m.recipientId)
        val msgPeerId = peerIdOf(m)
        val msgTimestamp = timestampOf(m)

        // P6: пиннинг ключа отправителя — без него сервер мог бы спуфить авторство
        if (!groupLike && !m.senderId.equals(myId, ignoreCase = true)) {
            val trust = trustStore.checkIncoming(m.senderId, m.senderPubkeyB64)
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

        // (#A3) Групповой конверт — AES-GCM общим ключом; личный — crypto_box;
        // плюс легаси-путь CHANNEL_NONCE внутри decrypt (только чтение истории).
        val plain = if (m.isGroupEnvelope) {
            val gk = groupKeyFor(msgPeerId) ?: throw GroupKeyUnavailableException(msgPeerId)
            String(crypto.decryptFile(E2ECrypto.Envelope("SYM", m.nonceB64, m.ciphertextB64), gk.key), Charsets.UTF_8)
        } else {
            crypto.decrypt(env, keys)
        }
        val obj = try { JSONObject(plain) } catch (e: Exception) { null }
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
        if (obj != null && ptype.isNotBlank() && ptype != "text" && ptype != "media") {
            return null
        }

        ensureChatExists(msgPeerId)

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
    suspend fun enqueueText(peerId: String, text: String, replyToId: String?, replyToText: String?): Exception? {
        return try {
            ensureChatExists(peerId, fetchProfile = false) // без сети: вставка мгновенная
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

    /** Пересылка: тоже через outbox (текст и media-JSON реконструируются из entity). */
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

    /** Повторная отправка сообщения со status=-1 (пункт меню «Повторить отправку»). */
    fun retryMessage(msgId: String) {
        scope.launch {
            store.updateStatus(msgId, 0)
            outboxSignal.trySend(Unit)
        }
    }

    /** Восстанавливает wire-JSON исходящего из полей entity. */
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
        // Счётчик подряд неудачных попыток на сообщение (в памяти): после лимита — status=-1,
        // чтобы один вечно падающий конверт не держал очередь чата бесконечно.
        val attempts = HashMap<String, Int>()
        while (true) {
            var transientFailure = false
            // Чаты, в которых случился временный сбой: их сообщения пропускаем,
            // чтобы не нарушить порядок внутри чата; другие чаты продолжают слаться.
            val blockedPeers = HashSet<String>()
            for (msg in store.getPendingOutgoing()) {
                if (msg.peerId.lowercase() in blockedPeers) continue
                try {
                    val envelope = encryptWireFor(msg.peerId, buildWire(msg))
                    api.sendMessage(myId, msg.peerId, envelope, clientMsgId = msg.msgId)
                    store.updateStatus(msg.msgId, 1)
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

    private suspend fun sendUris(peerId: String, uris: List<Uri>, caption: String?, asFile: Boolean): Exception? {
        return try {
            for (uri in uris) {
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

                    val jsonObj = JSONObject()
                        .put("type", "media")
                        .put("file_id", fileId)
                        .put("sym_key", symKey.keyB64)
                        .put("mime_type", resolver.getType(uri) ?: "application/octet-stream")
                        .put("nonce", nonceB64)
                    if (asFile) {
                        // Документ: отображается строкой с именем/размером, без сжатия.
                        jsonObj.put("kind", "file")
                        jsonObj.put("file_name", queryDisplayName(uri))
                        jsonObj.put("file_size", queryFileSize(uri))
                    }
                    if (caption != null && uri == uris.first()) jsonObj.put("caption", caption)
                    jsonText = jsonObj.toString()
                } finally {
                    tmp.delete()
                }

                val clientId = UUID.randomUUID().toString()
                api.sendMessage(myId, peerId, encryptWireFor(peerId, jsonText), clientMsgId = clientId)

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

    suspend fun sendRecording(peerId: String, bytes: ByteArray, mime: String, kind: String, durationMs: Long): Exception? {
        return try {
            // (#A4) Без base64-раздувания: шифруем стримом во временный файл
            val symKey = crypto.generateSymmetricKey()
            val tmp = java.io.File.createTempFile("aether_rec", null)
            val fileId: String
            val nonceB64: String
            try {
                nonceB64 = java.io.ByteArrayInputStream(bytes).use { input ->
                    java.io.FileOutputStream(tmp).use { output ->
                        crypto.encryptStream(input, output, symKey)
                    }
                }
                fileId = api.uploadFile(tmp)
            } finally {
                tmp.delete()
            }
            val jsonText = JSONObject()
                .put("type", "media")
                .put("kind", kind)
                .put("file_id", fileId)
                .put("sym_key", symKey.keyB64)
                .put("mime_type", mime)
                .put("nonce", nonceB64)
                .put("duration", durationMs)
                .toString()
            val clientId = UUID.randomUUID().toString()
            api.sendMessage(myId, peerId, encryptWireFor(peerId, jsonText), clientMsgId = clientId)
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
            null
        } catch (e: Exception) { e }
    }

    suspend fun downloadMedia(jsonText: String): ByteArray? {
        return try {
            val obj = JSONObject(jsonText)
            val fileId = obj.getString("file_id")
            val symKeyB64 = obj.getString("sym_key")
            val nonce = obj.getString("nonce")
            // (#A4) Дешифруем сырые байты напрямую — без base64-прохода,
            // который удваивал потребление памяти на больших файлах
            val encryptedBytes = api.downloadFile(fileId)
            crypto.decryptBytes(encryptedBytes, nonce, E2ECrypto.SymmetricKey(symKeyB64))
        } catch (e: Exception) { null }
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
            api.sendMessage(myId, peerId, encryptWireFor(peerId, wire))
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
            if (isGroupLike(peerId)) return
            if (unread == 0) return
            val wire = JSONObject().put("type", "read")
            api.sendMessage(myId, peerId, encryptWireFor(peerId, wire.toString()))
        } catch (e: Exception) {}
    }

    /** Редактирование: сетевой контрол + локальное обновление. */
    suspend fun editMessage(peerId: String, msgId: String, newText: String): Exception? {
        return try {
            val wire = JSONObject()
                .put("type", "edit")
                .put("target", msgId)
                .put("text", newText)
            api.sendMessage(myId, peerId, encryptWireFor(peerId, wire.toString()))
            store.updateText(msgId, newText)
            null
        } catch (e: Exception) { e }
    }

    /** Конверт для отложенной отправки (ScheduledMessageWorker). */
    suspend fun encryptForPeer(peerId: String, wireJson: String): Map<String, String> =
        encryptWireFor(peerId, wireJson)

    // ------------------------------------------------------------------
    // Вспомогательное
    // ------------------------------------------------------------------

    /**
     * Создаёт чат в локальной БД, если его ещё нет (имя/аватар — из профиля).
     * @param fetchProfile false — без сетевого запроса профиля (для optimistic-путей,
     *        где вставка должна быть мгновенной); имя обогатится позже из инбокса.
     */
    suspend fun ensureChatExists(peerId: String, fetchProfile: Boolean = true) {
        if (store.getChat(peerId) != null) return
        val type = when {
            peerId.startsWith("channel_", ignoreCase = true) -> 2
            peerId.startsWith("group_", ignoreCase = true) -> 1
            else -> 0
        }
        var resolvedAvatar: String? = null
        val resolvedName = if (type == 0 && fetchProfile) {
            try {
                val p = api.getUserProfile(peerId)
                resolvedAvatar = p.avatarFileId
                p.displayName.ifBlank { peerId }
            } catch (e: Exception) { peerId }
        } else if (type != 0 && fetchProfile) {
            // (#A3) Имя группы/канала вместо технического id
            try {
                api.getMyGroups().firstOrNull { it.id.equals(peerId, ignoreCase = true) }?.name?.ifBlank { peerId } ?: peerId
            } catch (e: Exception) { peerId }
        } else peerId
        store.insertChat(
            ChatEntity(
                peerId = peerId,
                name = resolvedName,
                type = type,
                avatarFileId = resolvedAvatar
            )
        )
    }

    private fun parseReactions(raw: String): JSONObject =
        try { JSONObject(if (raw.isBlank()) "{}" else raw) } catch (e: Exception) { JSONObject() }
}
