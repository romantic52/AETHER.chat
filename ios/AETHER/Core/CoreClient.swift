import Foundation

// Актор-обёртка над Rust-ядром (sm_core). Всё сетевое/крипто/диск I/O идёт сюда,
// вне main actor. UI-стейт живёт в @MainActor вью-моделях и дёргает эти методы через await.
//
// Ядро (ApiClient, CoreStore, WsClient и свободные функции crypto/protocol) генерируется
// UniFFI в Core/Generated/sm_core.swift. Здесь — тонкая типобезопасная фасадная прослойка.
actor CoreClient {
    static let baseURL = "https://144-31-181-10.nip.io"

    private let api: ApiClient
    private lazy var store: CoreStore = CoreClient.openStoreRecovering(path: CoreClient.databasePath())
    private var peerKeyCache: [String: (key: String, fetchedAt: Date)] = [:]

    // Текущая криптоидентичность (после логина/восстановления сессии).
    private(set) var myId: String = ""
    private(set) var myPublicKey: String = ""
    private var myPrivateKey: String = ""
    /// Кэш Olm identity-ключа (curve25519) текущего аккаунта.
    private var myOlmIdentity: String = ""

    init() {
        self.api = ApiClient(baseUrl: CoreClient.baseURL)
    }

    static func databasePath() -> String {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return dir.appendingPathComponent("aether.sqlite").path
    }

    private static func accountDatabasePath(_ userId: String) -> String {
        let safe = userId.lowercased().map { $0.isLetter || $0.isNumber ? $0 : "_" }
        let name = "aether_\(String(safe)).sqlite"
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return dir.appendingPathComponent(name).path
    }

    // Ключ шифрования локальной базы (SQLCipher): 32 байта, живёт в Keychain.
    // На DEBUG-сборках дублируется в UserDefaults: Keychain на неподписанных
    // сборках симулятора ненадёжен, а потеря ключа = потеря локальной истории.
    private static let kDbKey = "db_encryption_key"

    /// Сохранённый ключ БД, если он ДОСТУПЕН прямо сейчас. Ничего не генерирует:
    /// nil может означать и «первый запуск», и «Keychain ещё заперт» — решает вызывающий.
    private static func storedDbKey() -> String? {
        if let key = Keychain.string(for: kDbKey) {
            #if DEBUG
            UserDefaults.standard.set(key, forKey: kDbKey)
            #endif
            return key
        }
        #if DEBUG
        if let key = UserDefaults.standard.string(forKey: kDbKey) {
            Keychain.set(key, for: kDbKey)
            return key
        }
        #endif
        return nil
    }

    private static func makeAndStoreDbKey() -> String {
        let key = randomKeyB64()
        Keychain.set(key, for: kDbKey)
        #if DEBUG
        UserDefaults.standard.set(key, forKey: kDbKey)
        #endif
        return key
    }

    // Открытие базы, которое НИКОГДА не уничтожает историю из-за недоступного
    // ключа. Инцидент 10.07: запуск при запертом Keychain → ключ «не найден» →
    // сгенерирован новый → база не открылась → её похоронили как corrupt.
    // Теперь: нет ключа при существующей базе — ждём Keychain (ретраи), в худшем
    // случае падаем (перезапуск безопаснее потери переписки). Переименование в
    // .corrupt-* — только когда база реально не открывается ВАЛИДНЫМ ключом,
    // и файл при этом остаётся рядом как бэкап.
    private static func openStoreRecovering(path: String) -> CoreStore {
        let fm = FileManager.default
        let dbExists = fm.fileExists(atPath: path)

        var key = storedDbKey()
        if key == nil, dbExists {
            // База есть, ключа «нет» — при холодном старте почти наверняка Keychain
            // ещё заперт: ждём его (10с ретраев). Если после этого ключа всё равно
            // нет — это НЕ временная блокировка (на логине Keychain разблокирован),
            // а реальная потеря ключа (переустановка/смена keychain-группы). Тогда
            // НЕ крашимся вечным лупом: старую БД откладываем в бэкап (ниже по
            // corrupt-пути) и стартуем свежую с новым ключом.
            for _ in 0..<20 where key == nil {
                Thread.sleep(forTimeInterval: 0.5)
                key = storedDbKey()
            }
        }
        let effectiveKey = key ?? makeAndStoreDbKey()   // nil здесь = первый запуск ИЛИ ключ утерян

        if let store = try? CoreStore.open(path: path, encryptionKeyB64: effectiveKey) { return store }
        // Валидный ключ, но база не открылась — настоящее повреждение.
        // Сохраняем файлы рядом (восстановимы вручную) и начинаем с чистой.
        let suffix = ".corrupt-\(Int(Date().timeIntervalSince1970))"
        for candidate in [path, path + "-wal", path + "-shm"] where fm.fileExists(atPath: candidate) {
            try? fm.moveItem(atPath: candidate, toPath: candidate + suffix)
        }
        return try! CoreStore.open(path: path, encryptionKeyB64: effectiveKey)
    }

    private func selectStore(for userId: String) {
        let legacyPath = Self.databasePath()
        let legacy = Self.openStoreRecovering(path: legacyPath)
        let owner = (try? legacy.metaGet(key: "account_owner")) ?? nil
        if owner == nil || owner == userId {
            if owner == nil { try? legacy.metaSet(key: "account_owner", value: userId) }
            store = legacy
        } else {
            let account = Self.openStoreRecovering(path: Self.accountDatabasePath(userId))
            try? account.metaSet(key: "account_owner", value: userId)
            store = account
        }
    }

    // MARK: - Идентичность / сессия

    func setIdentity(id: String, publicKey: String, privateKey: String) {
        if myId != id {
            selectStore(for: id)
            peerKeyCache.removeAll(keepingCapacity: true)
            deviceCache.removeAll(keepingCapacity: true)
            cachedDeviceId = ""
            myOlmIdentity = ""   // у нового аккаунта свой Olm-аккаунт в его store
        }
        self.myId = id
        self.myPublicKey = publicKey
        self.myPrivateKey = privateKey
    }

    func restoreSession(token: String, userId: String, publicKey: String, privateKey: String) {
        api.setSession(token: token, userId: userId)
        setIdentity(id: userId, publicKey: publicKey, privateKey: privateKey)
    }

    var privateKey: String { myPrivateKey }

    // MARK: - Аккаунт

    /// Регистрация: ядро генерит пару, шифрует приватный ключ паролем (PBKDF2+AES-GCM),
    /// шлёт на сервер. Возвращает сессию; приватный ключ оседает в акторе (Keychain — снаружи).
    func register(userId: String, password: String) throws -> (session: AuthSession, privateKey: String) {
        let kp = generateKeypair()
        let encPriv = try encryptPrivateKey(privateKeyB64: kp.privateB64, password: password)
        let session = try api.register(
            userId: userId,
            password: password,
            publicKeyB64: kp.publicB64,
            encryptedPrivateKeyB64: encPriv
        )
        setIdentity(id: session.userId, publicKey: kp.publicB64, privateKey: kp.privateB64)
        return (session, kp.privateB64)
    }

    /// Вход: логин на сервере, затем расшифровка приватного ключа паролем-бэкапа.
    func login(userId: String, password: String) throws -> (session: AuthSession, privateKey: String) {
        let session = try api.login(userId: userId, password: password)
        let priv: String
        if session.encryptedPrivateKeyB64.isEmpty {
            throw CoreError.BadInput(msg: "На сервере нет резервной копии ключа для этого аккаунта")
        }
        priv = try decryptPrivateKey(blob: session.encryptedPrivateKeyB64, password: password)
        setIdentity(id: session.userId, publicKey: session.publicKeyB64, privateKey: priv)
        return (session, priv)
    }

    func logout() {
        try? api.logout()
        myId = ""; myPublicKey = ""; myPrivateKey = ""
        peerKeyCache.removeAll(keepingCapacity: false)
        deviceCache.removeAll(keepingCapacity: false)
        cachedDeviceId = ""
        myOlmIdentity = ""
    }

    func heartbeat() { try? api.heartbeat() }

    func updateProfile(username: String?, displayName: String?, avatarFileId: String?, bio: String?) throws {
        try api.updateProfile(username: username, displayName: displayName, avatarFileId: avatarFileId, bio: bio)
    }

    /// Загружает картинку аватара (публичный, без шифрования — см. WIRE_PROTOCOL.md
    /// `POST /avatars`), возвращает file_id для последующего updateProfile.
    func uploadAvatar(data: Data, mime: String) throws -> String {
        try api.uploadAvatar(data: data, mime: mime)
    }

    func getProfile(_ userId: String) throws -> Profile {
        try api.getUserProfile(userId: userId)
    }

    func searchUsers(_ query: String) throws -> [Profile] {
        try api.searchUsers(query: query)
    }

    // MARK: - Публичные ключи + TOFU

    /// Получает публичный ключ пира (кэш пинов). Возвращает ключ и флаг «ключ сменился» (MITM-тревога).
    func publicKey(for peerId: String) throws -> (key: String, changed: Bool) {
        let id = peerId.lowercased()
        if let cached = peerKeyCache[id], Date().timeIntervalSince(cached.fetchedAt) < 10 * 60 {
            return (cached.key, false)
        }
        let key = try api.getPublicKey(userId: id)
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let changed = try store.pinUpsert(peerId: id, publicKeyB64: key, firstSeen: now)
        peerKeyCache[id] = (key, Date())
        return (key, changed)
    }

    /// Пин ключа пира (для TOFU-экрана).
    func keyPin(_ peerId: String) throws -> KeyPin? {
        try store.pinGet(peerId: peerId.lowercased())
    }

    /// Отметить ключ пира проверенным/непроверенным (сверка отпечатков).
    func setKeyVerified(_ peerId: String, _ verified: Bool) throws {
        try store.pinSetVerified(peerId: peerId.lowercased(), verified: verified)
    }

    /// Мой публичный ключ (для отпечатка на TOFU-экране).
    func myPublicKeyB64() -> String { myPublicKey }

    // MARK: - Olm / Double Ratchet (1:1)

    /// Pickle Olm-аккаунта (identity + one-time keys). Создаётся при первом обращении.
    private func olmAccount() throws -> String {
        if let pickle = try? store.metaGet(key: "olm_account"), !pickle.isEmpty {
            return pickle
        }
        let pickle = try olmAccountNew()
        try store.metaSet(key: "olm_account", value: pickle)
        return pickle
    }

    private func myOlmIdentityKey() throws -> String {
        if !myOlmIdentity.isEmpty { return myOlmIdentity }
        myOlmIdentity = try olmAccountIdentity(accountPickle: olmAccount())
        return myOlmIdentity
    }

    // MARK: - Multi-device

    /// Existing installations recover their server device by Olm identity;
    /// a fresh installation gets a separate iOS slot instead of overwriting primary.
    private var cachedDeviceId = ""
    private func myDeviceId() throws -> String {
        if !cachedDeviceId.isEmpty { return cachedDeviceId }
        if let stored = try? store.metaGet(key: "device_id"), !stored.isEmpty {
            cachedDeviceId = stored
            return stored
        }

        let devices = try api.listDevices(userId: myId)
        let identity = try myOlmIdentityKey()
        let resolved: String
        if devices.isEmpty {
            resolved = "primary"
        } else if let mine = devices.first(where: { $0.identityKeyB64 == identity }) {
            resolved = mine.deviceId
        } else {
            let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(10).lowercased()
            resolved = "ios-" + suffix
        }
        cachedDeviceId = resolved
        try store.metaSet(key: "device_id", value: resolved)
        return resolved
    }

    /// Preserve the legacy primary session while isolating every secondary device.
    private func sessionKey(_ peerId: String, _ deviceId: String) -> String {
        deviceId == "primary" ? peerId : "\(peerId)::\(deviceId)"
    }

    private var deviceCache: [String: (devices: [DeviceInfo], at: Date)] = [:]
    private func peerDevices(_ peerId: String, force: Bool = false) throws -> [DeviceInfo] {
        let id = peerId.lowercased()
        if !force, let cached = deviceCache[id], Date().timeIntervalSince(cached.at) < 60 {
            return cached.devices
        }
        let devices = try api.listDevices(userId: id)
        if !devices.isEmpty { deviceCache[id] = (devices, Date()) }
        return devices
    }

    /// Опубликовать/пополнить prekeys на сервере. Идемпотентно: заливает identity
    /// и генерит новые OTK, когда на сервере их мало. Дёргается при старте и после
    /// расхода OTK на входящей prekey-сессии.
    func ensureOlmKeys() {
        do {
            let acct = try olmAccount()
            let localIdentity = try olmAccountIdentity(accountPickle: acct)
            let device = try myDeviceId()
            // Bind before publishing so this session cannot replace another device's identity.
            try api.bindSessionDevice(deviceId: device)
            let serverIdentity = try api.listDevices(userId: myId)
                .first(where: { $0.deviceId == device })?.identityKeyB64
            if try api.keysCountDevice(deviceId: device) >= 20,
               serverIdentity == localIdentity {
                myOlmIdentity = localIdentity
                return
            }
            let published = try olmAccountGenerateOtks(accountPickle: acct, count: 40)
            try store.metaSet(key: "olm_account", value: published.accountPickle)
            myOlmIdentity = published.identityKeyB64
            try api.uploadKeysDevice(identityKeyB64: published.identityKeyB64,
                                     oneTimeKeysJson: published.oneTimeKeysJson,
                                     deviceId: device)
            deviceCache.removeAll(keepingCapacity: true)
        } catch {
            // Не критично для UI: повторится при следующем старте/приёме.
        }
    }

    /// Ratchet-конверт 1:1: {ratchet, olm_identity, sender_device, type, body_b64}.
    private func ratchetEnvelope(type: UInt32, body: String) throws -> String {
        let obj: [String: Any] = ["ratchet": "1", "olm_identity": try myOlmIdentityKey(),
                                  "sender_device": try myDeviceId(),
                                  "type": Int(type), "body_b64": body]
        let data = try JSONSerialization.data(withJSONObject: obj)
        return String(decoding: data, as: UTF8.self)
    }

    // MARK: - Отправка

    /// Запечатать личное сообщение Double Ratchet'ом и отправить. При первом
    /// сообщении устанавливает Olm-сессию по prekey-бандлу пира (X3DH).
    /// Отправка 1:1 ТОЛЬКО через Double Ratchet — никаких даунгрейдов. Если у пира
    /// нет prekeys (claim 404/409) — сообщение видимо падает, а не уходит слабым
    /// статическим box. box остаётся лишь для обёртки групповых ключей.
    func sendDirect(to peerId: String, wirePayload: String, clientId: String? = nil) throws -> String {
        let id = peerId.lowercased()
        var devices = try peerDevices(id)
        if devices.isEmpty {
            devices = [DeviceInfo(deviceId: "primary", identityKeyB64: "")]
        }

        var firstMessageId: String?
        var firstError: Error?
        for device in devices {
            do {
                let key = sessionKey(id, device.deviceId)
                var sessionPickle = try store.olmSessionGet(peerId: key)
                if sessionPickle == nil {
                    let bundle = try api.claimKeysDevice(userId: id, deviceId: device.deviceId)
                    sessionPickle = try olmCreateOutbound(accountPickle: olmAccount(),
                                                          theirIdentityB64: bundle.identityKeyB64,
                                                          theirOneTimeKeyB64: bundle.oneTimeKeyB64)
                }
                let enc = try olmEncrypt(sessionPickle: sessionPickle!, plaintext: wirePayload)
                try store.olmSessionSet(peerId: key, sessionJson: enc.sessionPickle)
                let envelope = try ratchetEnvelope(type: enc.messageType, body: enc.bodyB64)
                let copyId = firstMessageId == nil ? clientId : UUID().uuidString.lowercased()
                let messageId = try api.sendMessageDevice(
                    recipientId: peerId,
                    envelopeJson: envelope,
                    clientId: copyId,
                    targetDeviceId: device.deviceId
                )
                if firstMessageId == nil { firstMessageId = messageId }
            } catch {
                if firstError == nil { firstError = error }
            }
        }
        if let firstMessageId { return firstMessageId }
        throw firstError ?? CoreError.Crypto(msg: "у получателя нет доступных устройств")
    }

    func sendGroup(groupId: String, groupKey: String, wirePayload: String) throws -> String {
        let envelope = try sealGroup(plaintextJson: wirePayload, groupKeyB64: groupKey)
        return try api.sendMessage(recipientId: groupId, envelopeJson: envelope, clientId: nil)
    }

    // MARK: - Приём

    func fetchInbox(since: String?) throws -> [InboxItem] {
        try api.fetchInboxDevice(since: since, deviceId: myDeviceId())
    }

    func ack(_ ids: [String]) throws {
        try api.ackMessagesDevice(messageIds: ids, deviceId: myDeviceId())
    }

    /// Вскрыть конверт входящего. 1:1 — Double Ratchet (Olm); группы — общий
    /// симметричный ключ из локального стораджа (crypto_box-обёртка).
    func open(item: InboxItem) throws -> Opened {
        let env = item.envelope
        // Ratchet-конверт 1:1?
        if let data = env.data(using: .utf8),
           let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           (obj["ratchet"] as? String) == "1" {
            return try ratchetOpen(item: item, obj: obj)
        }
        // Групповое: адресуется на recipient_id = group_id.
        let groupKey = (try? store.getGroupKey(groupId: item.recipientId.lowercased())) ?? nil
        return try openEnvelope(envelopeJson: env, myPrivB64: myPrivateKey, groupKeyB64: groupKey)
    }

    private func ratchetOpen(item: InboxItem, obj: [String: Any]) throws -> Opened {
        let senderIdentity = obj["olm_identity"] as? String ?? ""
        let type = UInt32(obj["type"] as? Int ?? 1)
        let body = obj["body_b64"] as? String ?? ""
        let peer = item.senderId.lowercased()

        var senderDevice = obj["sender_device"] as? String ?? ""
        if senderDevice.isEmpty {
            var devices = (try? peerDevices(peer)) ?? []
            if !devices.contains(where: { $0.identityKeyB64 == senderIdentity }) {
                devices = (try? peerDevices(peer, force: true)) ?? devices
            }
            senderDevice = devices.first(where: { $0.identityKeyB64 == senderIdentity })?.deviceId ?? "primary"
        }
        let key = sessionKey(peer, senderDevice)

        // Есть сессия — пробуем ею. prekey (type 0) при живой сессии — обычное дело
        // (пир ещё не увидел наш ответ), сессия его тоже расшифрует.
        // The legacy peer-only key is also tried once so upgrades keep live sessions.
        for storedKey in key == peer ? [key] : [key, peer] {
            if let session = try? store.olmSessionGet(peerId: storedKey),
               let dec = try? olmDecrypt(sessionPickle: session, messageType: type, bodyB64: body) {
                try store.olmSessionSet(peerId: key, sessionJson: dec.sessionPickle)
                return Opened(senderPubB64: senderIdentity, plaintext: dec.plaintext, isGroup: false)
            }
        }
        // Нет сессии (или расхождение) + prekey → установить входящую (расходует OTK).
        guard type == 0 else {
            throw CoreError.Crypto(msg: "ratchet: нет сессии для normal-сообщения")
        }
        let inb = try olmCreateInbound(accountPickle: olmAccount(),
                                       theirIdentityB64: senderIdentity, bodyB64: body)
        try store.metaSet(key: "olm_account", value: inb.accountPickle)   // OTK списан
        try store.olmSessionSet(peerId: key, sessionJson: inb.sessionPickle)
        ensureOlmKeys()   // пополнить OTK на сервере
        return Opened(senderPubB64: senderIdentity, plaintext: inb.plaintext, isGroup: false)
    }

    // MARK: - Медиа

    /// Зашифровать байты AES-GCM, залить на /upload. Возвращает (file_id, sym_key, nonce).
    func uploadMedia(_ data: Data) throws -> (fileId: String, symKey: String, nonce: String) {
        let symKey = randomKeyB64()
        let sealed = try aesEncrypt(keyB64: symKey, plaintext: data)
        let fileId = try api.upload(data: sealed.ciphertext)
        return (fileId, symKey, sealed.nonceB64)
    }

    /// Скачать и расшифровать медиа.
    func downloadMedia(fileId: String, symKey: String, nonce: String) throws -> Data {
        let ct = try api.download(fileId: fileId)
        return try aesDecrypt(keyB64: symKey, nonceB64: nonce, ciphertext: ct)
    }

    func uploadAvatar(_ data: Data, mime: String) throws -> String {
        try api.uploadAvatar(data: data, mime: mime)
    }
    func avatarURL(_ fileId: String) -> String { api.avatarUrl(fileId: fileId) }

    // MARK: - Группы

    func createGroup(id: String, name: String, description: String?, isChannel: Bool,
                     ownerWrappedKey: String, linkedGroupId: String?) throws -> String {
        try api.createGroup(id: id, name: name, description: description, isChannel: isChannel,
                            encryptedKeyB64: ownerWrappedKey, linkedGroupId: linkedGroupId)
    }
    func addGroupMember(groupId: String, userId: String, wrappedKey: String, role: String?) throws {
        try api.addGroupMember(groupId: groupId, userId: userId, encryptedKeyB64: wrappedKey, role: role)
    }
    func myGroups() throws -> String { try api.getMyGroups() }
    func groupMembers(_ groupId: String) throws -> String { try api.getGroupMembers(groupId: groupId) }
    func removeGroupMember(groupId: String, userId: String) throws { try api.removeGroupMember(groupId: groupId, userId: userId) }
    func updateGroup(groupId: String, name: String?, description: String?) throws { try api.updateGroup(groupId: groupId, name: name, description: description) }
    func leaveGroup(_ groupId: String) throws { try api.leaveGroup(groupId: groupId) }
    func deleteGroup(_ groupId: String) throws { try api.deleteGroup(groupId: groupId) }

    /// Новый случайный симметричный ключ группы (32 байта, b64url).
    func newGroupKey() -> String { randomKeyB64() }

    /// Обернуть групповой ключ box'ом для получателя (моими ключами). Формат encrypted_key_b64.
    func wrapGroupKeyFor(groupKey: String, recipientPub: String) throws -> String {
        try wrapGroupKey(groupKeyB64: groupKey, recipientPubB64: recipientPub,
                         senderPubB64: myPublicKey, senderPrivB64: myPrivateKey)
    }

    /// Обернуть групповой ключ для себя (на собственный публичный ключ).
    func wrapGroupKeyForSelf(groupKey: String) throws -> String {
        try wrapGroupKey(groupKeyB64: groupKey, recipientPubB64: myPublicKey,
                         senderPubB64: myPublicKey, senderPrivB64: myPrivateKey)
    }

    /// Развернуть мой encrypted_key_b64 → b64url ключа группы (или nil).
    func unwrapMyGroupKey(_ encryptedKeyB64: String) -> String? {
        try? unwrapGroupKey(encryptedKeyB64: encryptedKeyB64, myPrivB64: myPrivateKey)
    }

    // MARK: - Хранилище (прокси на акторе — выполняется вне main thread)

    func chatList() throws -> [Chat] { try store.getChatList() }
    func messages(peer: String, beforeTs: Int64, limit: UInt32) throws -> [StoredMessage] {
        try store.getMessagesForPeer(peerId: peer, beforeTs: beforeTs, limit: limit)
    }
    func totalUnread() -> Int64 { (try? store.totalUnread()) ?? 0 }

    func metaGet(_ key: String) -> String? { try? store.metaGet(key: key) ?? nil }
    func metaSet(_ key: String, _ value: String) { try? store.metaSet(key: key, value: value) }

    func messageExists(_ id: String) -> Bool { (try? store.messageExists(id: id)) ?? false }
    func getMessage(_ id: String) -> StoredMessage? { (try? store.getMessage(id: id)) ?? nil }
    func insertMessage(_ m: StoredMessage) { try? store.insertMessage(m: m) }
    func updateText(_ id: String, _ payloadJson: String) { try? store.updateText(id: id, payloadJson: payloadJson) }
    func updatePayload(_ id: String, _ payloadJson: String) { try? store.updatePayload(id: id, payloadJson: payloadJson) }
    func updateReactions(_ id: String, _ json: String) { try? store.updateReactions(id: id, reactionsJson: json) }
    func updateStatus(_ id: String, _ status: Int32) { try? store.updateStatus(id: id, status: status) }
    func markOutgoingStatus(peer: String, status: Int32) { try? store.markOutgoingStatus(peerId: peer, status: status) }
    func replaceMessageId(old: String, new: String, status: Int32) { try? store.replaceMessageId(oldId: old, newId: new, status: status) }
    func markDeleted(_ id: String) { try? store.markDeleted(id: id) }
    func pendingOutgoing() -> [StoredMessage] { (try? store.getPendingOutgoing()) ?? [] }

    func touchChat(peer: String, isGroup: Bool, title: String, lastText: String, lastTs: Int64, incUnread: Bool) {
        try? store.touchChat(peerId: peer, isGroup: isGroup, title: title, lastText: lastText, lastTs: lastTs, incUnread: incUnread)
    }
    func clearUnread(_ peer: String) { try? store.clearUnread(peerId: peer) }
    func setPinnedFlag(_ peer: String, _ v: Bool) { try? store.setPinned(peerId: peer, v: v) }
    func setMutedFlag(_ peer: String, _ v: Bool) { try? store.setMuted(peerId: peer, v: v) }
    func setArchivedFlag(_ peer: String, _ v: Bool) { try? store.setArchived(peerId: peer, v: v) }
    func deleteChatData(_ peer: String) { try? store.deleteChat(peerId: peer) }
    func deleteHistory(peer: String) throws { try api.deleteHistory(peerId: peer.lowercased()) }

    func groupKey(_ groupId: String) -> String? { (try? store.getGroupKey(groupId: groupId)) ?? nil }
    func setGroupKey(_ groupId: String, _ keyB64: String) { try? store.setGroupKey(groupId: groupId, keyB64: keyB64) }
}
