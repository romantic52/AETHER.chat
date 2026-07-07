import Foundation
import Observation

/// Глобальное состояние приложения: текущий экран-флоу, авторизованный
/// пользователь и его ключи. Логика логина/регистрации повторяет Android:
///   register → generateKeyPair → encryptPrivateKey(pwd) → api.register → login
///   login    → api.login → decryptPrivateKey(pwd) → ключи в памяти/Keychain
@Observable
@MainActor
final class Session {
    enum Phase: Equatable {
        case welcome          // стартовый экран
        case auth             // вход / регистрация
        case authenticating
        case ready            // вошли — основной интерфейс
    }

    var phase: Phase = .welcome
    var server: String = AetherServer.default
    var errorMessage: String?

    private(set) var userId: String = ""
    private(set) var keyPair: KeyPair?

    private var core: CoreClient?

    /// Попытка авто-входа по сохранённой сессии (токен в Keychain).
    func bootstrap() {
        guard let saved = Keychain.loadSession() else { return }
        server = saved.server
        userId = saved.userId
        Task { await resumeSession(saved) }
    }

    func beginAuth() { phase = .auth; errorMessage = nil }

    func register(userId: String, password: String) {
        run {
            let core = CoreClient(baseURL: self.server)
            let kp = core.generateKeyPair()
            let encPriv = try core.encryptPriv(kp.privateB64, password: password)
            try await core.register(userId: userId, publicKeyB64: kp.publicB64,
                                    encryptedPrivateKeyB64: encPriv, password: password)
            let result = try await core.login(userId: userId, password: password)
            try await self.finish(core: core, login: result, password: password, fallbackKeys: kp)
        }
    }

    func login(userId: String, password: String) {
        run {
            let core = CoreClient(baseURL: self.server)
            let result = try await core.login(userId: userId, password: password)
            try await self.finish(core: core, login: result, password: password, fallbackKeys: nil)
        }
    }

    func logout() {
        Keychain.clearSession()
        keyPair = nil
        userId = ""
        core = nil
        phase = .welcome
    }

    // MARK: - private

    private func finish(core: CoreClient, login: LoginResult, password: String, fallbackKeys: KeyPair?) async throws {
        let priv = try core.decryptPriv(login.encryptedPrivateKeyB64, password: password)
        let kp = KeyPair(privateB64: priv, publicB64: login.publicKeyB64)
        await core.setToken(login.token)
        self.core = core
        self.userId = login.userId
        self.keyPair = kp
        Keychain.saveSession(.init(server: server, userId: login.userId, token: login.token))
        self.phase = .ready
    }

    private func resumeSession(_ saved: Keychain.SavedSession) async {
        let core = CoreClient(baseURL: saved.server)
        await core.setToken(saved.token)
        do {
            try await core.heartbeat()
            self.core = core
            self.phase = .ready
        } catch {
            // токен протух — останемся на welcome, попросим войти заново
            Keychain.clearSession()
        }
    }

    private func run(_ work: @escaping () async throws -> Void) {
        phase = .authenticating
        errorMessage = nil
        Task {
            do { try await work() }
            catch {
                self.errorMessage = Self.describe(error)
                self.phase = .auth
            }
        }
    }

    private static func describe(_ error: Error) -> String {
        // ApiError из ядра реализует Display в Rust ("HTTP 401: …", "сеть: …").
        // Не матчим конкретные кейсы — их имена зависят от генерации UniFFI;
        // строкового представления достаточно для понятного сообщения.
        let raw = String(describing: error)
        if raw.contains("401") { return "Неверный логин или пароль" }
        return raw
    }
}
