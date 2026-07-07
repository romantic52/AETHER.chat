import Foundation

/// Тонкая обёртка над нативным Rust-ядром (UniFFI-биндинги `sm_core`).
///
/// Все методы ядра (`ApiClient`, `generateKeypair`, `boxEncrypt`…) — блокирующие
/// (ureq + tungstenite внутри Rust), поэтому здесь они оборачиваются в async и
/// уводятся с главного актора через `Task.detached`. Сам сетевой/крипто-код НЕ
/// дублируется на Swift — это ровно то же ядро, что у Android и web.
///
/// Сгенерированные биндинги лежат в `CoreFFI/Generated/sm_core.swift`
/// (создаются `build_core_ios.sh` на Mac). До первой сборки на Mac этого файла
/// нет — символы `ApiClient` и т.п. появятся после прогона скрипта.
actor CoreClient {
    private let api: ApiClient
    let baseURL: String

    init(baseURL: String) {
        self.baseURL = baseURL
        self.api = ApiClient(baseUrl: baseURL)
    }

    func setToken(_ token: String) {
        api.setToken(token: token)
    }

    // MARK: - Крипто (локально, без сети)

    // Свободные функции ниже — из сгенерированного UniFFI-файла sm_core.swift,
    // который добавлен в таргет приложения, поэтому вызываются без квалификатора.

    /// Генерация пары ключей X25519 (base64 url-safe).
    nonisolated func generateKeyPair() -> KeyPair {
        generateKeypair()
    }

    nonisolated func encryptPriv(_ privateB64: String, password: String) throws -> String {
        try encryptPrivateKey(privateKeyB64: privateB64, password: password)
    }

    nonisolated func decryptPriv(_ blob: String, password: String) throws -> String {
        try decryptPrivateKey(blob: blob, password: password)
    }

    nonisolated func boxEnc(_ plaintext: String, myPrivateB64: String, theirPublicB64: String) throws -> Envelope {
        try boxEncrypt(plaintext: plaintext, myPrivateB64: myPrivateB64, theirPublicB64: theirPublicB64)
    }

    nonisolated func boxDec(_ env: Envelope, myPrivateB64: String) throws -> String {
        try boxDecrypt(env: env, myPrivateB64: myPrivateB64)
    }

    // MARK: - Сеть (relay)

    func health() throws -> String { try api.health() }

    func register(userId: String, publicKeyB64: String, encryptedPrivateKeyB64: String, password: String) throws {
        try api.register(userId: userId, publicKeyB64: publicKeyB64,
                         encryptedPrivateKeyB64: encryptedPrivateKeyB64, password: password)
    }

    func login(userId: String, password: String) throws -> LoginResult {
        try api.login(userId: userId, password: password)
    }

    func heartbeat() throws { try api.heartbeat() }

    func sendMessage(senderId: String, recipientId: String, envelope: [String: String], clientId: String?) throws -> String {
        try api.sendMessage(senderId: senderId, recipientId: recipientId, envelope: envelope, clientId: clientId)
    }

    func fetchInbox(userId: String) throws -> [InboxMessage] {
        try api.fetchInbox(userId: userId)
    }

    func ackMessages(_ ids: [String]) throws { try api.ackMessages(messageIds: ids) }

    func searchUsers(_ query: String) throws -> [SearchResult] { try api.searchUsers(query: query) }

    func getMyGroups() throws -> [GroupInfo] { try api.getMyGroups() }
}

/// Стандартный сервер AETHER (зеркалит android LoginScreen / web).
enum AetherServer {
    static let `default` = "https://your-server.example.com"
}
