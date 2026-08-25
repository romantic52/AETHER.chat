import Foundation
import SwiftUI

// Реестр серверов и пространств.
//
// Пространство (Space) — это пара «сервер + аккаунт на нём». Всё состояние
// приложения принадлежит одному пространству: список чатов, контакты, ключи,
// медиа-кеш, WebSocket. Между пространствами не разделяется ничего, кроме
// блокировки приложения, темы и самого этого реестра.
//
// Хранится обычным JSON в Application Support, а не в SQLite: реестр читается
// РАНЬШЕ, чем открывается хоть одна база, и не должен зависеть от того, какая
// база сейчас активна. Секретов внутри нет — токены и ключи живут в Keychain.
//
// Проект целиком — docs/MULTI_SERVER_DESIGN.md.

enum ServerKind: String, Codable {
    case official   // инфраструктура Aether. Вывеска и порядок в списке — всё,
                    // что даёт этот флаг. Привилегий на уровне протокола нет.
    case custom
}

enum RegistrationMode: String, Codable {
    case open, approval, inviteOnly = "invite_only", closed

    /// Из ответа сервера; незнакомое значение считаем закрытым — так безопаснее,
    /// чем предлагать регистрацию, которой нет.
    init(wire: String) {
        self = RegistrationMode(rawValue: wire.lowercased()) ?? .closed
    }

    var canSelfRegister: Bool { self == .open }
    var needsInvite: Bool { self == .inviteOnly }
    var needsApproval: Bool { self == .approval }
}

enum Transport: String, Codable {
    case tls              // обычный HTTPS с полной проверкой сертификата
    case lanCleartext     // локальная сеть без TLS, выбирается пользователем явно
}

/// TOFU-отпечаток сервера. Меняется — значит либо сервер переустановили,
/// либо это не тот сервер. Молча принимать изменение нельзя.
struct ServerPin: Codable, Equatable {
    var serverId: String
    var publicKeyB64: String
    var fingerprintB64: String
    var firstSeenAt: Date
    var lastVerifiedAt: Date
    /// История смен: пользователь должен видеть, что менялось и когда.
    var changes: [PinChange] = []

    struct PinChange: Codable, Equatable {
        var at: Date
        var oldServerId: String
        var oldFingerprintB64: String
        var acceptedByUser: Bool
    }
}

struct AccountRef: Codable, Identifiable, Equatable {
    var userId: String
    var displayName: String = ""
    var avatarFileId: String = ""
    var accountNo: Int64?
    var role: String = "USER"
    /// Имя файла локальной базы этого пространства.
    var dbFileName: String
    var lastLoginAt: Date?

    var id: String { userId }
}

struct ServerRecord: Codable, Identifiable, Equatable {
    var id: String                   // server_id, первичный ключ. Не домен и не имя.
    var kind: ServerKind
    var displayName: String          // локальное имя, пользователь может менять
    var declaredName: String         // как сервер назвал себя сам
    var origin: String               // https://chat.example.com:8443
    var apiURL: String
    var wsURL: String
    var protocolVersion: Int = 1
    var registrationMode: RegistrationMode = .closed
    var capabilities: [String] = []
    var transport: Transport = .tls
    var pin: ServerPin?
    var trusted: Bool = false
    var accounts: [AccountRef] = []
    var addedAt: Date = Date()
    var lastConnectedAt: Date?
    /// Идентификатор сервера изменился и пользователь ещё не принял решение.
    /// Пока флаг стоит, вход и любая передача данных заблокированы.
    var identityAlert: ServerPin.PinChange?

    var isOfficial: Bool { kind == .official }
    var supportsDataImport: Bool { capabilities.contains("data_import") }
    var supportsE2EE: Bool { capabilities.contains("e2ee") }

    /// Хост без схемы — то, что показывается под именем сервера.
    var hostLabel: String {
        origin.replacingOccurrences(of: "https://", with: "")
              .replacingOccurrences(of: "http://", with: "")
    }

    func account(_ userId: String) -> AccountRef? {
        accounts.first { $0.userId.caseInsensitiveCompare(userId) == .orderedSame }
    }
}

/// Ссылка на конкретное пространство.
struct SpaceRef: Codable, Equatable, Hashable {
    var serverId: String
    var userId: String
}

@MainActor
final class ServerRegistry: ObservableObject {
    static let shared = ServerRegistry()

    /// Идентификатор официального сервера до того, как он сам себя назовёт.
    /// Заменяется настоящим UUID при первом успешном /server/info.
    static let officialPlaceholderId = "official-legacy"

    @Published private(set) var servers: [ServerRecord] = []
    @Published private(set) var activeSpace: SpaceRef?

    private let fileURL: URL

    private init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory,
                                            in: .userDomainMask)[0]
            .appendingPathComponent("Aether", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        fileURL = base.appendingPathComponent("servers.json")
        load()
    }

    // MARK: - Доступ

    var active: ServerRecord? {
        guard let space = activeSpace else { return nil }
        return server(space.serverId)
    }

    var activeAccount: AccountRef? {
        guard let space = activeSpace, let srv = server(space.serverId) else { return nil }
        return srv.account(space.userId)
    }

    func server(_ id: String) -> ServerRecord? {
        servers.first { $0.id == id }
    }

    var official: ServerRecord? { servers.first { $0.kind == .official } }

    /// Сервер, уже известный по этому origin (сравнение без схемы и хвостов).
    func server(matchingOrigin origin: String) -> ServerRecord? {
        let needle = origin.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        return servers.first { $0.origin.trimmingCharacters(in: CharacterSet(charactersIn: "/")) == needle }
    }

    /// Порядок для UI: официальный всегда первым, дальше по времени добавления.
    var ordered: [ServerRecord] {
        servers.sorted { a, b in
            if a.isOfficial != b.isOfficial { return a.isOfficial }
            return a.addedAt < b.addedAt
        }
    }

    // MARK: - Изменения

    func upsert(_ record: ServerRecord) {
        if let idx = servers.firstIndex(where: { $0.id == record.id }) {
            servers[idx] = record
        } else {
            servers.append(record)
        }
        save()
    }

    func update(_ id: String, _ change: (inout ServerRecord) -> Void) {
        guard let idx = servers.firstIndex(where: { $0.id == id }) else { return }
        change(&servers[idx])
        save()
    }

    func remove(serverId: String) {
        guard let record = server(serverId), !record.isOfficial else { return }
        for account in record.accounts {
            Keychain.removeSpace(serverId: serverId, userId: account.userId)
            removeDatabase(named: account.dbFileName)
        }
        servers.removeAll { $0.id == serverId }
        if activeSpace?.serverId == serverId {
            activeSpace = official.flatMap { srv in
                srv.accounts.first.map { SpaceRef(serverId: srv.id, userId: $0.userId) }
            }
        }
        save()
    }

    /// Сменить идентификатор записи, сохранив её саму и все аккаунты.
    ///
    /// Нужно ровно один раз: официальный сервер жил под заглушкой, пока не
    /// сообщил свой настоящий server_id. Через remove+upsert это делать нельзя —
    /// remove сносит локальные базы и вдобавок отказывается трогать
    /// официальную запись, так что заглушка осталась бы второй строкой.
    /// Keychain переносит вызывающий (ServerMigration).
    func rekey(from oldId: String, to newId: String) {
        guard let idx = servers.firstIndex(where: { $0.id == oldId }),
              server(newId) == nil else { return }
        servers[idx].id = newId
        if let space = activeSpace, space.serverId == oldId {
            activeSpace = SpaceRef(serverId: newId, userId: space.userId)
        }
        save()
    }

    func setActive(_ space: SpaceRef?) {
        activeSpace = space
        save()
    }

    func addAccount(_ account: AccountRef, to serverId: String) {
        update(serverId) { srv in
            if let idx = srv.accounts.firstIndex(where: {
                $0.userId.caseInsensitiveCompare(account.userId) == .orderedSame
            }) {
                srv.accounts[idx] = account
            } else {
                srv.accounts.append(account)
            }
        }
    }

    func removeAccount(_ userId: String, from serverId: String) {
        Keychain.removeSpace(serverId: serverId, userId: userId)
        update(serverId) { srv in
            srv.accounts.removeAll { $0.userId.caseInsensitiveCompare(userId) == .orderedSame }
        }
    }

    // MARK: - Имя файла базы

    /// aether_<8 символов server_id>_<логин>.sqlite. Логин в имени — для
    /// читаемости при разборе проблем; уникальность даёт префикс сервера.
    static func databaseFileName(serverId: String, userId: String) -> String {
        let srv = serverId.replacingOccurrences(of: "-", with: "").prefix(8)
        let safeUser = String(userId.lowercased().map { $0.isLetter || $0.isNumber ? $0 : "_" })
        return "aether_\(srv)_\(safeUser).sqlite"
    }

    private func removeDatabase(named name: String) {
        // Легаси-файл общей базы не трогаем никогда: в нём переписка
        // официального аккаунта.
        guard name != "aether.sqlite" else { return }
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        for suffix in ["", "-wal", "-shm"] {
            try? FileManager.default.removeItem(at: dir.appendingPathComponent(name + suffix))
        }
    }

    // MARK: - Хранение

    private struct Snapshot: Codable {
        var servers: [ServerRecord]
        var activeSpace: SpaceRef?
    }

    private func load() {
        guard let data = try? Data(contentsOf: fileURL) else { return }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        guard let snapshot = try? decoder.decode(Snapshot.self, from: data) else { return }
        servers = snapshot.servers
        activeSpace = snapshot.activeSpace
    }

    private func save() {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        guard let data = try? encoder.encode(Snapshot(servers: servers, activeSpace: activeSpace)) else { return }
        try? data.write(to: fileURL, options: .atomic)
    }
}
