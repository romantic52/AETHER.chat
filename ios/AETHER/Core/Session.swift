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
    /// Растёт только при СМЕНЕ аккаунта. Домашний экран пересоздаётся по нему,
    /// а не по myId: myId на старте меняется с пустого на настоящий, и HomeView
    /// пересоздавался в момент показа — системный таб-бар при этом прикреплялся
    /// заново и приезжал позже экрана.
    @Published private(set) var accountGeneration = 0
    @Published var myUsername: String = ""
    @Published var myDisplayName: String = ""
    @Published var myAvatarFileId: String = ""
    @Published var myStatusEmoji: String = ""

    var myAvatarURL: URL? {
        guard !myAvatarFileId.isEmpty else { return nil }
        // GET /avatars/{file_id} — публичный, без шифрования (см. WIRE_PROTOCOL.md).
        // Строим URL напрямую (без await core.avatarURL) — это чистая конкатенация
        // строк, а не сеть, а Session живёт на MainActor.
        return URL(string: "\(CoreClient.baseURL)/avatars/\(myAvatarFileId)")
    }

    /// Токен текущей сессии в памяти (Keychain может быть недоступен на неподписанной сборке).
    private(set) var authToken: String = ""

    /// Мультиаккаунт (до 5): id всех добавленных на устройстве аккаунтов.
    /// Креды каждого — в Keychain под acct_<id>_*; активный дублируется
    /// в legacy-ключах (kToken и т.д.), чтобы bootstrap/HTTP-хелперы не менялись.
    @Published private(set) var accounts: [String] =
        (UserDefaults.standard.stringArray(forKey: "savedAccounts") ?? [])
    static let maxAccounts = 5

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
        // Миграция на мультиаккаунт: активный аккаунт попадает в реестр.
        registerAccount(id: userId.lowercased(), token: token, publicKey: pub, privateKey: priv)
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
        Task { await core.ensureOlmKeys() }   // выложить prekeys сразу (не ждать start())
    }

    /// Сигнал наверх: сервер требует 2FA-код. UI показывает поле и повторяет
    /// login с totpCode.
    struct TotpRequired: Error {}

    func login(userId: String, password: String, totpCode: String? = nil) async throws {
        let session: AuthSession
        let priv: String
        do {
            (session, priv) = try await core.login(userId: userId, password: password, totpCode: totpCode)
        } catch {
            if totpCode == nil, CoreClient.isTotpRequired(error) { throw TotpRequired() }
            throw error
        }
        persist(session: session, privateKey: priv)
        myId = session.userId
        phase = .ready
        startHeartbeat()
        Task { await loadMyProfile() }
        Task { await core.ensureOlmKeys() }   // выложить prekeys сразу (не ждать start())
        // Перепривязать APNs-токен устройства к новому аккаунту.
        await MainActor.run { PushRegistrar.requestRegistration() }
    }

    /// Выход из ТЕКУЩЕГО аккаунта. Если на устройстве есть другие —
    /// переключаемся на первый из них, иначе — онбординг.
    func logout() async {
        heartbeatTask?.cancel()
        // Снять APNs-токен, пока Bearer ещё жив — иначе пуши полетят бывшему аккаунту.
        await PushRegistrar.unregister()
        await core.logout()
        let current = myId.lowercased()
        dropAccount(current)
        for k in [Keychain.kToken, Keychain.kUserId, Keychain.kPublicKey, Keychain.kPrivateKey] {
            Keychain.remove(k)
        }
        authToken = ""
        accountGeneration += 1
        myId = ""; myUsername = ""; myDisplayName = ""; myAvatarFileId = ""
        if let next = accounts.first {
            await switchAccount(to: next)
        } else {
            phase = .onboarding
        }
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
        registerAccount(id: session.userId.lowercased(), token: session.token,
                        publicKey: session.publicKeyB64, privateKey: privateKey)
    }

    // MARK: - Мультиаккаунт

    private func registerAccount(id: String, token: String, publicKey: String, privateKey: String) {
        Keychain.set(token, for: "acct_\(id)_token")
        Keychain.set(publicKey, for: "acct_\(id)_pub")
        Keychain.set(privateKey, for: "acct_\(id)_priv")
        if !accounts.contains(id) {
            accounts.append(id)
            UserDefaults.standard.set(accounts, forKey: "savedAccounts")
        }
    }

    private func dropAccount(_ id: String) {
        for suffix in ["token", "pub", "priv"] { Keychain.remove("acct_\(id)_\(suffix)") }
        accounts.removeAll { $0 == id }
        UserDefaults.standard.set(accounts, forKey: "savedAccounts")
    }

    /// Переключиться на другой сохранённый аккаунт (креды уже на устройстве).
    func switchAccount(to id: String) async {
        let target = id.lowercased()
        guard target != myId.lowercased(),
              let token = Keychain.string(for: "acct_\(target)_token"),
              let pub = Keychain.string(for: "acct_\(target)_pub"),
              let priv = Keychain.string(for: "acct_\(target)_priv"),
              !token.isEmpty else { return }
        heartbeatTask?.cancel()
        accountGeneration += 1
        phase = .loading
        myUsername = ""; myDisplayName = ""; myAvatarFileId = ""
        // Активные (legacy) ключи — на новый аккаунт; per-account креды не трогаем.
        authToken = token
        Keychain.set(token, for: Keychain.kToken)
        Keychain.set(target, for: Keychain.kUserId)
        Keychain.set(pub, for: Keychain.kPublicKey)
        Keychain.set(priv, for: Keychain.kPrivateKey)
        await core.restoreSession(token: token, userId: target, publicKey: pub, privateKey: priv)
        myId = target
        phase = .ready
        startHeartbeat()
        Task { await loadMyProfile() }
        // Переключение аккаунта не перезапускает Messaging.start() (guard !shouldRun),
        // поэтому prekeys нового аккаунта надо выложить явно — иначе ему нельзя
        // написать (Double Ratchet требует prekey-бандл получателя).
        Task { await core.ensureOlmKeys() }
        PushRegistrar.requestRegistration()
    }

    // MARK: - Профиль

    func loadMyProfile() async {
        guard !myId.isEmpty else { return }
        if let p = try? await core.getProfile(myId) {
            myUsername = p.username ?? ""
            myDisplayName = p.displayName ?? ""
            myAvatarFileId = p.avatarFileId ?? ""
        }
        if let status = await ProfileHTTP.statusEmoji(myId) { myStatusEmoji = status }
    }

    /// Эмодзи-статус рядом с ником; пустая строка — снять.
    func setMyStatusEmoji(_ emoji: String) async {
        if await ProfileHTTP.setStatusEmoji(emoji) { myStatusEmoji = emoji }
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
