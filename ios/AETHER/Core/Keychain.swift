import Foundation
import Security

// Тонкая обёртка над Keychain. Приватный ключ и токен сессии живут ТОЛЬКО здесь
// (никогда в UserDefaults и никогда на сервер в открытом виде).
enum Keychain {
    private static let service = "io.aether.app"

    static func set(_ value: String, for key: String) {
        set(Data(value.utf8), for: key)
    }

    static func set(_ data: Data, for key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        SecItemDelete(query as CFDictionary)
        var add = query
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }

    static func data(for key: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return data
    }

    static func string(for key: String) -> String? {
        guard let d = data(for: key) else { return nil }
        return String(data: d, encoding: .utf8)
    }

    static func remove(_ key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        SecItemDelete(query as CFDictionary)
    }

    // Ключи.
    static let kToken = "session_token"
    static let kUserId = "session_user_id"
    static let kPublicKey = "identity_public_key"
    static let kPrivateKey = "identity_private_key"
    static let kPin = "app_pin"

    // MARK: - Пространство имён по серверам
    //
    // Раньше креды аккаунта лежали под acct_<логин>_*. Пока сервер был один,
    // это работало; с пользовательскими серверами одинаковый логин на двух
    // серверах затирал бы чужую запись и подставлял токен не туда. Первичным
    // ключом стала ПАРА (server_id, логин).

    static func accessKey(_ serverId: String, _ userId: String) -> String {
        "srv.\(serverId).acct.\(userId.lowercased()).access"
    }
    static func refreshKey(_ serverId: String, _ userId: String) -> String {
        "srv.\(serverId).acct.\(userId.lowercased()).refresh"
    }
    static func publicKeyKey(_ serverId: String, _ userId: String) -> String {
        "srv.\(serverId).acct.\(userId.lowercased()).pub"
    }
    static func privateKeyKey(_ serverId: String, _ userId: String) -> String {
        "srv.\(serverId).acct.\(userId.lowercased()).priv"
    }
    /// Ключ SQLCipher для баз этого сервера. У каждого сервера свой.
    static func dbKeyKey(_ serverId: String) -> String {
        "srv.\(serverId).dbkey"
    }
    /// Поданная и ещё не решённая заявка на регистрацию (request_id + claim_token).
    static func pendingRequestKey(_ serverId: String) -> String {
        "srv.\(serverId).pending_request"
    }

    static func removeSpace(serverId: String, userId: String) {
        remove(accessKey(serverId, userId))
        remove(refreshKey(serverId, userId))
        remove(publicKeyKey(serverId, userId))
        remove(privateKeyKey(serverId, userId))
    }
}
