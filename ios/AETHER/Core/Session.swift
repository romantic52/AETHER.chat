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
    /// Числовой номер аккаунта: логин можно сменить, номер остаётся навсегда.
    @Published private(set) var myAccountNo: Int64?
    @Published var myUsername: String = ""
    @Published var myDisplayName: String = ""
    @Published var myAvatarFileId: String = ""
    @Published var myStatusEmoji: String = ""

    var myAvatarURL: URL? {
        guard !myAvatarFileId.isEmpty else { return nil }
        // GET /avatars/{file_id} — публичный, без шифрования (см. WIRE_PROTOCOL.md).
        // Строим URL напрямую (без await core.avatarURL) — это чистая конкатенация
        // строк, а не сеть, а Session живёт на MainActor.
        return URL(string: "\(ServerContext.origin)/avatars/\(myAvatarFileId)")
    }

    /// Токен текущей сессии в памяти (Keychain может быть недоступен на неподписанной сборке).
    private(set) var authToken: String = ""

    /// Активный сервер. Пространство = этот сервер + myId.
    @Published private(set) var activeServerId: String = ServerRegistry.officialPlaceholderId

    let registry = ServerRegistry.shared

    /// Аккаунты на ТЕКУЩЕМ сервере. Аккаунты других серверов сюда не попадают:
    /// они принадлежат другим пространствам и не смешиваются.
    var accounts: [String] {
        registry.server(activeServerId)?.accounts.map { $0.userId } ?? []
    }
    static let maxAccounts = 5

    var activeServer: ServerRecord? { registry.server(activeServerId) }

    /// Подпись пространства для шапки: «Aether Cloud», «Roman Home».
    var activeSpaceTitle: String { activeServer?.displayName ?? "Aether" }

    let core = CoreClient()

    private var heartbeatTask: Task<Void, Never>?
    private var applicationActive = true

    // MARK: - Восстановление сессии при старте

    func bootstrap() async {
        // Перевод старой установки на мультисерверную раскладку. Идемпотентно
        // и до всего остального: дальше всё читается уже по новым ключам.
        ServerMigration.runIfNeeded()

        guard let space = registry.activeSpace,
              let server = registry.server(space.serverId),
              let account = server.account(space.userId) else {
            phase = .onboarding
            return
        }
        guard let creds = credentials(server: server.id, userId: account.userId) else {
            // Запись о пространстве есть, а ключей нет: аккаунт разлогинен.
            phase = .onboarding
            return
        }

        await activate(server: server, account: account, creds: creds)
        phase = .ready
        startHeartbeat()
        Task { await loadMyProfile() }
        // Сервер мог переустановиться или сменить адрес — сверяем его личность
        // при каждом холодном старте, а не только при добавлении.
        Task { await ServerDirectory.shared.refresh(serverId: server.id) }
    }

    struct SpaceCredentials {
        var token: String
        var publicKey: String
        var privateKey: String
    }

    private func credentials(server: String, userId: String) -> SpaceCredentials? {
        guard let token = Keychain.string(for: Keychain.accessKey(server, userId)),
              let pub = Keychain.string(for: Keychain.publicKeyKey(server, userId)),
              let priv = Keychain.string(for: Keychain.privateKeyKey(server, userId)),
              !token.isEmpty, !userId.isEmpty else { return nil }
        return SpaceCredentials(token: token, publicKey: pub, privateKey: priv)
    }

    /// Привязать приложение к пространству: адреса, база, ключи, идентичность.
    private func activate(server: ServerRecord, account: AccountRef,
                          creds: SpaceCredentials) async {
        ServerContext.set(origin: server.origin,
                          apiBase: server.apiURL,
                          wsEndpoint: server.wsURL,
                          serverId: server.id,
                          dbFileName: account.dbFileName)
        await core.bindSpace(serverId: server.id, apiBase: server.apiURL,
                             userId: account.userId, dbFileName: account.dbFileName)
        await core.restoreSession(token: creds.token, userId: account.userId,
                                  publicKey: creds.publicKey, privateKey: creds.privateKey)
        // Восстановленная сессия обязана объявить своё устройство: login здесь
        // не проходит, а сервер без привязки пиннит сессию к первому device_id,
        // который услышит. Фоном — привязка ходит в директорию устройств.
        Task { await core.bindCurrentDevice() }
        authToken = creds.token
        myId = account.userId
        activeServerId = server.id
        registry.setActive(SpaceRef(serverId: server.id, userId: account.userId))
    }

    /// Войти по привязке со второго устройства: bundle уже расшифрован.
    /// Зеркало bootstrap() — те же ключи Keychain, тот же порядок, чтобы
    /// дальнейшая жизнь приложения ничем не отличалась от обычного входа.
    func signIn(paired bundle: PairingBundle, server: ServerRecord? = nil) async {
        let target = server ?? activeServer ?? officialServer()
        let userId = bundle.userId.lowercased()
        try? await prepare(server: target, userId: userId)

        Keychain.set(bundle.token, for: Keychain.accessKey(target.id, userId))
        Keychain.set(bundle.publicKey, for: Keychain.publicKeyKey(target.id, userId))
        Keychain.set(bundle.privateKey, for: Keychain.privateKeyKey(target.id, userId))

        await core.restoreSession(token: bundle.token, userId: userId,
                                  publicKey: bundle.publicKey, privateKey: bundle.privateKey)
        // Связывание устройств: сессия приезжает готовой, login не вызывается —
        // привязку к device_id делаем здесь, иначе инбокс упрётся в 403.
        Task { await core.bindCurrentDevice() }
        authToken = bundle.token
        myId = userId
        activeServerId = target.id
        if target.account(userId) == nil {
            registry.addAccount(
                AccountRef(userId: userId,
                           dbFileName: ServerRegistry.databaseFileName(serverId: target.id,
                                                                      userId: userId),
                           lastLoginAt: Date()),
                to: target.id)
        }
        registry.setActive(SpaceRef(serverId: target.id, userId: userId))
        phase = .ready
        startHeartbeat()
        Task { await loadMyProfile() }
        // Ключи нового устройства должны попасть на сервер сразу, иначе ему
        // нельзя написать: Double Ratchet требует prekey-бандл получателя.
        Task { await core.ensureOlmKeys() }
    }

    // MARK: - Аутентификация

    func register(on server: ServerRecord, userId: String, password: String,
                  inviteCode: String? = nil) async throws {
        try await prepare(server: server, userId: userId)
        let (session, priv) = try await core.register(userId: userId, password: password,
                                                      inviteCode: inviteCode)
        try await finishSignIn(server: server, session: session, privateKey: priv)
        Task { await core.ensureOlmKeys() }   // выложить prekeys сразу (не ждать start())
    }

    /// Регистрация на официальном сервере — частный случай, который знает
    /// только адрес из сборки. Нужен, чтобы вкладка «Наши серверы» вела себя
    /// ровно как раньше.
    func register(userId: String, password: String) async throws {
        try await register(on: officialServer(), userId: userId, password: password)
    }

    /// Сигнал наверх: сервер требует 2FA-код. UI показывает поле и повторяет
    /// login с totpCode.
    struct TotpRequired: Error {}

    func login(on server: ServerRecord, userId: String, password: String,
               totpCode: String? = nil) async throws {
        try await prepare(server: server, userId: userId)
        let session: AuthSession
        let priv: String
        do {
            (session, priv) = try await core.login(userId: userId, password: password, totpCode: totpCode)
        } catch {
            if totpCode == nil, CoreClient.isTotpRequired(error) { throw TotpRequired() }
            throw error
        }
        try await finishSignIn(server: server, session: session, privateKey: priv)
        Task { await loadMyProfile() }
        Task { await core.ensureOlmKeys() }   // выложить prekeys сразу (не ждать start())
        // Перепривязать APNs-токен устройства к новому аккаунту.
        PushRegistrar.requestRegistration()
    }

    func login(userId: String, password: String, totpCode: String? = nil) async throws {
        try await login(on: officialServer(), userId: userId, password: password, totpCode: totpCode)
    }

    /// Запись официального сервера; создаётся, если её ещё нет (чистая установка).
    func officialServer() -> ServerRecord {
        if let existing = registry.official { return existing }
        let record = ServerRecord(
            id: ServerRegistry.officialPlaceholderId,
            kind: .official,
            displayName: "Aether Cloud",
            declaredName: "Aether Cloud",
            origin: Secrets.baseURL,
            apiURL: Secrets.baseURL,
            wsURL: Secrets.wsBaseURL + "/ws",
            registrationMode: .open,
            capabilities: ["e2ee", "ratchet", "groups", "channels", "calls", "multi_device"],
            transport: .tls
        )
        registry.upsert(record)
        return record
    }

    /// Перед сетевым вызовом переключить ядро на нужный сервер: логин уходит
    /// туда, куда просили, а не туда, где мы были до этого.
    private func prepare(server: ServerRecord, userId: String) async throws {
        guard server.identityAlert == nil else {
            throw CoreError.BadInput(msg: "Идентификатор сервера изменился — вход заблокирован до подтверждения")
        }
        let dbName = server.account(userId)?.dbFileName
            ?? ServerRegistry.databaseFileName(serverId: server.id, userId: userId)
        ServerContext.set(origin: server.origin, apiBase: server.apiURL, wsEndpoint: server.wsURL,
                          serverId: server.id, dbFileName: dbName)
        await core.bindSpace(serverId: server.id, apiBase: server.apiURL,
                             userId: userId, dbFileName: dbName)
    }

    /// Общий хвост входа и регистрации: сохранить креды в пространство сервера,
    /// зарегистрировать аккаунт в реестре, сделать пространство активным.
    private func finishSignIn(server: ServerRecord, session: AuthSession,
                              privateKey: String) async throws {
        let userId = session.userId.lowercased()
        let dbName = server.account(userId)?.dbFileName
            ?? ServerRegistry.databaseFileName(serverId: server.id, userId: userId)

        Keychain.set(session.token, for: Keychain.accessKey(server.id, userId))
        Keychain.set(session.publicKeyB64, for: Keychain.publicKeyKey(server.id, userId))
        Keychain.set(privateKey, for: Keychain.privateKeyKey(server.id, userId))

        if server.account(userId) == nil {
            registry.addAccount(AccountRef(userId: userId, dbFileName: dbName,
                                           lastLoginAt: Date()), to: server.id)
        }
        registry.update(server.id) { $0.lastConnectedAt = Date() }

        authToken = session.token
        myId = userId
        if activeServerId != server.id { accountGeneration += 1 }
        activeServerId = server.id
        registry.setActive(SpaceRef(serverId: server.id, userId: userId))
        phase = .ready
        startHeartbeat()
    }

    /// Выход из ТЕКУЩЕГО аккаунта на ТЕКУЩЕМ сервере.
    ///
    /// Затрагивает одно пространство. Аккаунты на других серверах живут
    /// дальше, их токены и базы не трогаются.
    func logout() async {
        heartbeatTask?.cancel()
        // Снять APNs-токен, пока Bearer ещё жив — иначе пуши полетят бывшему аккаунту.
        await PushRegistrar.unregister()
        await core.logout()

        let serverId = activeServerId
        let userId = myId.lowercased()
        registry.removeAccount(userId, from: serverId)

        authToken = ""
        accountGeneration += 1
        myId = ""; myUsername = ""; myDisplayName = ""; myAvatarFileId = ""

        // Куда переходим: сначала другой аккаунт этого же сервера, потом любое
        // другое пространство, и только если ничего нет — на экран входа.
        if let next = registry.server(serverId)?.accounts.first {
            await switchSpace(to: SpaceRef(serverId: serverId, userId: next.userId))
        } else if let fallback = registry.ordered.compactMap({ srv in
            srv.accounts.first.map { SpaceRef(serverId: srv.id, userId: $0.userId) }
        }).first {
            await switchSpace(to: fallback)
        } else {
            registry.setActive(nil)
            phase = .onboarding
        }
    }

    func setApplicationActive(_ active: Bool) {
        applicationActive = active
        if active { startHeartbeat() }
        else { heartbeatTask?.cancel(); heartbeatTask = nil }
    }

    /// Переключение на другой аккаунт ТЕКУЩЕГО сервера.
    func switchAccount(to id: String) async {
        await switchSpace(to: SpaceRef(serverId: activeServerId, userId: id.lowercased()))
    }

    /// Переключение пространства: другой сервер и/или другой аккаунт.
    ///
    /// Пересоздаётся всё: адреса, база, ключи, движок обмена. HomeView
    /// перемонтируется по accountGeneration — иначе в списке остались бы чаты
    /// прежнего пространства.
    func switchSpace(to space: SpaceRef) async {
        guard space != registry.activeSpace || phase != .ready else { return }
        guard let server = registry.server(space.serverId),
              let account = server.account(space.userId),
              let creds = credentials(server: server.id, userId: account.userId) else {
            // Пространство есть, ключей нет — нужен вход на этот сервер.
            registry.setActive(space)
            activeServerId = space.serverId
            accountGeneration += 1
            phase = .onboarding
            return
        }

        heartbeatTask?.cancel()
        accountGeneration += 1
        phase = .loading
        myUsername = ""; myDisplayName = ""; myAvatarFileId = ""; myStatusEmoji = ""
        myAccountNo = nil

        await activate(server: server, account: account, creds: creds)
        phase = .ready
        startHeartbeat()
        Task { await loadMyProfile() }
        // Переключение не перезапускает Messaging.start() (guard !shouldRun),
        // поэтому prekeys нового пространства выкладываем явно — иначе в него
        // нельзя написать: Double Ratchet требует prekey-бандл получателя.
        Task { await core.ensureOlmKeys() }
        Task { await ServerDirectory.shared.refresh(serverId: server.id) }
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
        myAccountNo = await ProfileHTTP.accountNo(myId)
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
