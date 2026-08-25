import Foundation

// Перевод уже установленного приложения на мультисерверную раскладку.
//
// Главное правило миграции: НИЧЕГО НЕ ТЕРЯТЬ. Существующий пользователь после
// обновления обязан увидеть ровно тот же список чатов и ту же переписку —
// он вообще не должен заметить, что в приложении появились серверы.
//
// Поэтому:
//   • файлы баз НЕ переименовываются (переименование ради красоты имени —
//     это риск потерять историю при нулевой выгоде);
//   • ключ шифрования базы КОПИРУЕТСЯ под новое имя, а не создаётся заново.
//     Инцидент 10.07 (см. CoreClient.openStoreRecovering) случился ровно из-за
//     нового ключа при существующей базе;
//   • легаси-ключи Keychain остаются на месте ещё одну версию — на случай
//     отката сборки.
//
// docs/MULTI_SERVER_DESIGN.md, раздел 18.2.

@MainActor
enum ServerMigration {
    private static let versionKey = "aether.multiServerMigrated"
    private static let currentVersion = 2

    /// Легаси-имя базы конкретного аккаунта (до мультисерверности).
    private static func legacyAccountDatabaseName(_ userId: String) -> String {
        let safe = String(userId.lowercased().map { $0.isLetter || $0.isNumber ? $0 : "_" })
        return "aether_\(safe).sqlite"
    }

    static func runIfNeeded() {
        let done = UserDefaults.standard.integer(forKey: versionKey)
        guard done < currentVersion else { return }

        let registry = ServerRegistry.shared
        let officialId = registry.official?.id ?? ServerRegistry.officialPlaceholderId

        var official = registry.official ?? ServerRecord(
            id: officialId,
            kind: .official,
            displayName: "Aether Cloud",
            declaredName: "Aether Cloud",
            origin: Secrets.baseURL,
            apiURL: Secrets.baseURL,
            wsURL: Secrets.wsBaseURL + "/ws",
            registrationMode: .open,
            // До первого /server/info возможности неизвестны; берём то, что
            // официальный сервер умеет по факту сегодня.
            capabilities: ["e2ee", "ratchet", "groups", "channels", "calls", "multi_device"],
            transport: .tls
        )

        // Ключ SQLCipher: копия под серверное имя. Оригинал не трогаем.
        if let dbKey = Keychain.string(for: "db_encryption_key"),
           Keychain.string(for: Keychain.dbKeyKey(officialId)) == nil {
            Keychain.set(dbKey, for: Keychain.dbKeyKey(officialId))
        }

        let activeUser = Keychain.string(for: Keychain.kUserId)?.lowercased() ?? ""
        let savedAccounts = UserDefaults.standard.stringArray(forKey: "savedAccounts") ?? []
        // Активный аккаунт мог не попасть в реестр (миграция мультиаккаунта
        // делалась в bootstrap, а не при входе).
        let allAccounts = Set(savedAccounts.map { $0.lowercased() })
            .union(activeUser.isEmpty ? [] : [activeUser])

        for userId in allAccounts {
            let isActive = userId == activeUser
            // Креды: сначала пробуем per-account ключи, затем активные легаси.
            let token = Keychain.string(for: "acct_\(userId)_token")
                ?? (isActive ? Keychain.string(for: Keychain.kToken) : nil)
            let pub = Keychain.string(for: "acct_\(userId)_pub")
                ?? (isActive ? Keychain.string(for: Keychain.kPublicKey) : nil)
            let priv = Keychain.string(for: "acct_\(userId)_priv")
                ?? (isActive ? Keychain.string(for: Keychain.kPrivateKey) : nil)
            guard let token, let pub, let priv, !token.isEmpty else { continue }

            Keychain.set(token, for: Keychain.accessKey(officialId, userId))
            Keychain.set(pub, for: Keychain.publicKeyKey(officialId, userId))
            Keychain.set(priv, for: Keychain.privateKeyKey(officialId, userId))

            // Какой файл базы у этого аккаунта СЕЙЧАС — тот и записываем.
            // Активный жил в общей aether.sqlite, остальные — в именных.
            let legacyName = legacyAccountDatabaseName(userId)
            let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            let namedExists = FileManager.default.fileExists(
                atPath: documents.appendingPathComponent(legacyName).path)
            let dbName = namedExists ? legacyName : "aether.sqlite"

            if official.account(userId) == nil {
                official.accounts.append(AccountRef(userId: userId, dbFileName: dbName))
            }
        }

        registry.upsert(official)

        if !activeUser.isEmpty, official.account(activeUser) != nil {
            registry.setActive(SpaceRef(serverId: officialId, userId: activeUser))
        }

        UserDefaults.standard.set(currentVersion, forKey: versionKey)
    }

    /// Официальный сервер назвал свой настоящий server_id — заменяем заглушку.
    ///
    /// Переезжают и записи Keychain, и ссылка на активное пространство:
    /// пропустить это значит потерять токены всех аккаунтов официального
    /// сервера при первом же успешном /server/info.
    static func adoptOfficialServerId(_ realId: String) {
        let registry = ServerRegistry.shared
        guard let official = registry.official,
              official.id == ServerRegistry.officialPlaceholderId,
              realId != ServerRegistry.officialPlaceholderId else { return }

        let old = official.id
        for account in official.accounts {
            for (from, to) in [
                (Keychain.accessKey(old, account.userId), Keychain.accessKey(realId, account.userId)),
                (Keychain.refreshKey(old, account.userId), Keychain.refreshKey(realId, account.userId)),
                (Keychain.publicKeyKey(old, account.userId), Keychain.publicKeyKey(realId, account.userId)),
                (Keychain.privateKeyKey(old, account.userId), Keychain.privateKeyKey(realId, account.userId)),
            ] {
                if let value = Keychain.string(for: from) { Keychain.set(value, for: to) }
            }
        }
        if let dbKey = Keychain.string(for: Keychain.dbKeyKey(old)) {
            Keychain.set(dbKey, for: Keychain.dbKeyKey(realId))
        }

        // Именно rekey, а не remove+upsert: remove удаляет локальные базы
        // и не трогает официальную запись, так что заглушка осталась бы
        // висеть второй строкой рядом с настоящей.
        registry.rekey(from: old, to: realId)
    }
}
