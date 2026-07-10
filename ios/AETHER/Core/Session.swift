import Foundation
import SwiftUI

// Глобальное состояние сессии: авторизация, идентичность, доступ к ядру.
// Живёт на main actor; сетевые вызовы делегируются актору CoreClient.
@MainActor
final class Session: ObservableObject {
    enum Phase: Equatable {
        case loading      // проверяем Keychain
        case onboarding   // не авторизован
        case ready        // работаем
    }

    @Published var phase: Phase = .loading
    @Published var myId: String = ""
    @Published var myUsername: String = ""
    @Published var myDisplayName: String = ""
    @Published var myAvatarFileId: String = ""

    var myAvatarURL: URL? {
        guard !myAvatarFileId.isEmpty else { return nil }
        // GET /avatars/{file_id} — публичный, без шифрования (см. WIRE_PROTOCOL.md).
        // Строим URL напрямую (без await core.avatarURL) — это чистая конкатенация
        // строк, а не сеть, а Session живёт на MainActor.
        return URL(string: "\(CoreClient.baseURL)/avatars/\(myAvatarFileId)")
    }

    /// Токен текущей сессии в памяти (Keychain может быть недоступен на неподписанной сборке).
    private(set) var authToken: String = ""

    let core = CoreClient()

    private var heartbeatTask: Task<Void, Never>?
    private var applicationActive = true

    // MARK: - Восстановление сессии при старте

    func bootstrap() async {
        guard let token = Keychain.string(for: Keychain.kToken),
              let userId = Keychain.string(for: Keychain.kUserId),
              let pub = Keychain.string(for: Keychain.kPublicKey),
              let priv = Keychain.string(for: Keychain.kPrivateKey),
              !token.isEmpty, !userId.isEmpty else {
            phase = .onboarding
            return
        }
        await core.restoreSession(token: token, userId: userId, publicKey: pub, privateKey: priv)
        authToken = token
        myId = userId
        phase = .ready
        startHeartbeat()
        Task { await loadMyProfile() }
    }

    // MARK: - Аутентификация

    func register(userId: String, password: String) async throws {
        let (session, priv) = try await core.register(userId: userId, password: password)
        persist(session: session, privateKey: priv)
        myId = session.userId
        phase = .ready
        startHeartbeat()
    }

    func login(userId: String, password: String) async throws {
        let (session, priv) = try await core.login(userId: userId, password: password)
        persist(session: session, privateKey: priv)
        myId = session.userId
        phase = .ready
        startHeartbeat()
        Task { await loadMyProfile() }
        // Перепривязать APNs-токен устройства к новому аккаунту.
        await MainActor.run { PushRegistrar.requestRegistration() }
    }

    func logout() async {
        heartbeatTask?.cancel()
        // Снять APNs-токен, пока Bearer ещё жив — иначе пуши полетят бывшему аккаунту.
        await PushRegistrar.unregister()
        await core.logout()
        for k in [Keychain.kToken, Keychain.kUserId, Keychain.kPublicKey, Keychain.kPrivateKey] {
            Keychain.remove(k)
        }
        authToken = ""
        myId = ""; myUsername = ""; myDisplayName = ""; myAvatarFileId = ""
        phase = .onboarding
    }

    func setApplicationActive(_ active: Bool) {
        applicationActive = active
        if active { startHeartbeat() }
        else { heartbeatTask?.cancel(); heartbeatTask = nil }
    }

    private func persist(session: AuthSession, privateKey: String) {
        authToken = session.token
        Keychain.set(session.token, for: Keychain.kToken)
        Keychain.set(session.userId, for: Keychain.kUserId)
        Keychain.set(session.publicKeyB64, for: Keychain.kPublicKey)
        Keychain.set(privateKey, for: Keychain.kPrivateKey)
    }

    // MARK: - Профиль

    func loadMyProfile() async {
        guard !myId.isEmpty else { return }
        if let p = try? await core.getProfile(myId) {
            myUsername = p.username ?? ""
            myDisplayName = p.displayName ?? ""
            myAvatarFileId = p.avatarFileId ?? ""
        }
    }

    /// Загружает фото на сервер и привязывает его к своему профилю. `nil` data — удалить
    /// (сброс на инициалы).
    func setMyAvatar(data: Data?, mime: String) async throws {
        let fileId: String
        if let data {
            fileId = try await core.uploadAvatar(data: data, mime: mime)
        } else {
            fileId = ""
        }
        try await core.updateProfile(username: nil, displayName: nil, avatarFileId: fileId, bio: nil)
        myAvatarFileId = fileId
    }

    // MARK: - Heartbeat (presence)

    private func startHeartbeat() {
        guard applicationActive, phase == .ready else { return }
        heartbeatTask?.cancel()
        heartbeatTask = Task { [weak self] in
            guard let self else { return }
            while !Task.isCancelled {
                await self.core.heartbeat()
                try? await Task.sleep(nanoseconds: 30 * 1_000_000_000)
            }
        }
    }
}
