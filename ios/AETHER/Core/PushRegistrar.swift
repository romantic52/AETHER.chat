import Foundation
import UIKit

// Регистрация APNs-токена устройства на сервере. Токен приходит в AppDelegate
// после registerForRemoteNotifications() и уезжает на POST /devices/register
// с Bearer-токеном сессии. При разлогине — /devices/unregister, чтобы на
// устройство не летели чужие пуши.
enum PushRegistrar {
    private static let lastTokenKey = "lastPushToken"

    static func requestRegistration() {
        UIApplication.shared.registerForRemoteNotifications()
    }

    static func uploadToken(_ deviceToken: Data) {
        let hex = deviceToken.map { String(format: "%02x", $0) }.joined()
        UserDefaults.standard.set(hex, forKey: lastTokenKey)
        Task { await post("devices/register", token: hex) }
    }

    /// Снять токен с аккаунта (разлогин) — до очистки Keychain, пока жив Bearer.
    static func unregister() async {
        guard let hex = UserDefaults.standard.string(forKey: lastTokenKey) else { return }
        await post("devices/unregister", token: hex)
    }

    private static func post(_ path: String, token: String) async {
        guard let bearer = Keychain.string(for: Keychain.kToken), !bearer.isEmpty,
              let url = URL(string: "\(CoreClient.baseURL)/\(path)") else { return }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        req.httpBody = try? JSONSerialization.data(withJSONObject: ["token": token, "kind": "apns"])
        _ = try? await URLSession.shared.data(for: req)
    }
}
