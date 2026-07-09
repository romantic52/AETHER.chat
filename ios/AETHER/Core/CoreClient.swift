import Foundation

// Актор-обёртка над Rust-ядром (sm_core). Всё сетевое/крипто/диск I/O идёт сюда,
// вне main actor. UI-стейт живёт в @MainActor вью-моделях и дёргает эти методы через await.
//
// Ядро (ApiClient, CoreStore, WsClient и свободные функции crypto/protocol) генерируется
// UniFFI в Core/Generated/sm_core.swift. Здесь — тонкая типобезопасная фасадная прослойка.
actor CoreClient {
    static let baseURL = "https://YOUR-SERVER-HOST.nip.io"

    private let api: ApiClient
    private lazy var store: CoreStore = CoreClient.openStoreRecovering(path: CoreClient.databasePath())
    private var peerKeyCache: [String: (key: String, fetchedAt: Date)] = [:]

    // Текущая криптоидентичность (после логина/восстановления сессии).
    private(set) var myId: String = ""
    private(set) var myPublicKey: String = ""
    private var myPrivateKey: String = ""

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

    private static func dbEncryptionKey() -> String {
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
        let key = randomKeyB64()
        Keychain.set(key, for: kDbKey)
        #if DEBUG
        UserDefaults.standard.set(key, forKey: kDbKey)
        #endif
        return key
    }

    private static func openStoreRecovering(path: String) -> CoreStore {
        if let store = try? CoreStore.open(path: path, encryptionKeyB64: dbEncryptionKey()) { return store }
        let fm = FileManager.default
        let suffix = ".corrupt-\(Int(Date().timeIntervalSince1970))"
        for candidate in [path, path + "-wal", path + "-shm"] where fm.fileExists(atPath: candidate) {
            try? fm.moveItem(atPath: candidate, toPath: candidate + suffix)
        }
        // Если SQLite не открывается даже после сохранения повреждённой копии,
        // приложение действительно не может продолжить работу.
        return try! CoreStore.open(path: path, encryptionKeyB64: dbEncryptionKey())
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

    // MARK: - Отправка

    /// Запечатать личное сообщение и отправить. wirePayload — готовый JSON (см. WireBuilder).
    func sendDirect(to peerId: String, wirePayload: String, clientId: String? = nil) throws -> String {
        let (peerKey, _) = try publicKey(for: peerId)
        let envelope = try sealDirect(
            plaintextJson: wirePayload,
            recipientPubB64: peerKey,
            senderPubB64: myPublicKey,
            senderPrivB64: myPrivateKey
        )
        return try api.sendMessage(recipientId: peerId, envelopeJson: envelope, clientId: clientId)
    }

    /// Отправить копию самому себе (мультидевайс-синк) — тем же личным конвертом на свой id.
    func sendToSelf(wirePayload: String) throws -> String {
        let envelope = try sealDirect(
            plaintextJson: wirePayload,
            recipientPubB64: myPublicKey,
            senderPubB64: myPublicKey,
            senderPrivB64: myPrivateKey
        )
        return try api.sendMessage(recipientId: myId, envelopeJson: envelope, clientId: nil)
    }

    func sendGroup(groupId: String, groupKey: String, wirePayload: String) throws -> String {
        let envelope = try sealGroup(plaintextJson: wirePayload, groupKeyB64: groupKey)
        return try api.sendMessage(recipientId: groupId, envelopeJson: envelope, clientId: nil)
    }

    // MARK: - Приём

    func fetchInbox(since: String?) throws -> [InboxItem] {
        try api.fetchInbox(since: since)
    }

    func ack(_ ids: [String]) throws { try api.ackMessages(messageIds: ids) }

    /// Вскрыть конверт входящего. Для групп ключ берётся из локального стораджа.
    func open(item: InboxItem) throws -> Opened {
        let env = item.envelope
        var groupKey: String? = nil
        // Групповые адресуются на recipient_id = group_id.
        if let gk = try? store.getGroupKey(groupId: item.recipientId.lowercased()) {
            groupKey = gk
        }
        return try openEnvelope(envelopeJson: env, myPrivB64: myPrivateKey, groupKeyB64: groupKey)
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
