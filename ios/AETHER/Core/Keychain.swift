import Foundation
import Security

/// Хранение сессии (сервер + userId + токен) в iOS Keychain.
/// Пароль на устройстве НЕ храним (как и на Android) — только токен сессии.
enum Keychain {
    struct SavedSession: Codable {
        let server: String
        let userId: String
        let token: String
    }

    private static let account = "org.groktest.aether.session"
    private static let service = "AETHER"

    static func saveSession(_ s: SavedSession) {
        guard let data = try? JSONEncoder().encode(s) else { return }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
        var add = query
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }

    static func loadSession() -> SavedSession? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data,
              let session = try? JSONDecoder().decode(SavedSession.self, from: data)
        else { return nil }
        return session
    }

    static func clearSession() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
