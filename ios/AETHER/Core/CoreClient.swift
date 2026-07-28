import Foundation

// Актор-обёртка над Rust-ядром (sm_core). Всё сетевое/крипто/диск I/O идёт сюда,
// вне main actor. UI-стейт живёт в @MainActor вью-моделях и дёргает эти методы через await.
//
// Ядро (ApiClient, CoreStore, WsClient и свободные функции crypto/protocol) генерируется
// UniFFI в Core/Generated/sm_core.swift. Здесь — тонкая типобезопасная фасадная прослойка.

/// Тревога доверия ключам (TOFU/подписи, SEC HIGH-2). Отдельный тип, потому что
/// ядро маппит в CoreError.Crypto ВСЁ подряд (включая битый base64) — по нему
/// нельзя отличить «подмена ключей» от «мусор в одном бандле».
struct KeyTrustAlert: Error {
    let message: String
    /// true — тревога касается ОДНОГО устройства пира (например, оно ещё не
    /// обновилось и не подписано аккаунтом): его пропускаем, остальным шлём.
    /// false — тревога об аккаунте целиком (подмена ключей): отправка отменяется.
    var skipDevice: Bool = false
}

actor CoreClient {
    static let baseURL = "https://YOUR-SERVER-HOST.nip.io"

    private let api: ApiClient
    private lazy var store: CoreStore = CoreClient.openStoreRecovering(path: CoreClient.databasePath())
    private var peerKeyCache: [String: (key: String, fetchedAt: Date)] = [:]

    // Текущая криптоидентичность (после логина/восстановления сессии).
    private(set) var myId: String = ""
    private(set) var myPublicKey: String = ""
    private var myPrivateKey: String = ""
    /// Кэш Olm identity-ключа (curve25519) текущего аккаунта.
    private var myOlmIdentity: String = ""

    init() {
        self.api = ApiClient(baseUrl: CoreClient.baseURL)
    }

    static func databasePath() -> String {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return dir.appendingPathComponent("aether.sqlite").path
    }

    private static func accountDatabasePath(_ userId: String) -> String {
        let safe = userId.lowercased().map { $0.isLetter || $0.isNumber ? $0 : "_" }
        let name = "aether_\(String(safe)).sqlite"
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return dir.appendingPathComponent(name).path
    }

    // Ключ шифрования локальной базы (SQLCipher): 32 байта, живёт в Keychain.
    // На DEBUG-сборках дублируется в UserDefaults: Keychain на неподписанных
    // сборках симулятора ненадёжен, а потеря ключа = потеря локальной истории.
    private static let kDbKey = "db_encryption_key"

    /// Как часто ротируем fallback-ключ (P10 / SEC MED-3). Он переиспользуемый,
    /// поэтому чем дольше живёт, тем больше начал переписок вскрывается разом при
    /// его компрометации; неделя — компромисс с расходом публикаций.
    private static let fallbackRotateInterval: TimeInterval = 7 * 24 * 3600

    /// Сохранённый ключ БД, если он ДОСТУПЕН прямо сейчас. Ничего не генерирует:
    /// nil может означать и «первый запуск», и «Keychain ещё заперт» — решает вызывающий.
    private static func storedDbKey() -> String? {
        if let key = Keychain.string(for: kDbKey) {
            #if DEBUG
            UserDefaults.standard.set(key, forKey: kDbKey)
            #endif
            return key
        }
        #if DEBUG
        if let key = UserDefaults.standard.string(forKey: kDbKey) {
            Keychain.set(key, for: kDbKey)
            return key
        }
        #endif
        return nil
    }

    private static func makeAndStoreDbKey() -> String {
        let key = randomKeyB64()
        Keychain.set(key, for: kDbKey)
        #if DEBUG
        UserDefaults.standard.set(key, forKey: kDbKey)
        #endif
        return key
    }

    // Открытие базы, которое НИКОГДА не уничтожает историю из-за недоступного
    // ключа. Инцидент 10.07: запуск при запертом Keychain → ключ «не найден» →
    // сгенерирован новый → база не открылась → её похоронили как corrupt.
    // Теперь: нет ключа при существующей базе — ждём Keychain (ретраи), в худшем
    // случае падаем (перезапуск безопаснее потери переписки). Переименование в
    // .corrupt-* — только когда база реально не открывается ВАЛИДНЫМ ключом,
    // и файл при этом остаётся рядом как бэкап.
    private static func openStoreRecovering(path: String) -> CoreStore {
        let fm = FileManager.default
        let dbExists = fm.fileExists(atPath: path)

        var key = storedDbKey()
        if key == nil, dbExists {
            // База есть, ключа «нет» — при холодном старте почти наверняка Keychain
            // ещё заперт: ждём его (10с ретраев). Если после этого ключа всё равно
            // нет — это НЕ временная блокировка (на логине Keychain разблокирован),
            // а реальная потеря ключа (переустановка/смена keychain-группы). Тогда
            // НЕ крашимся вечным лупом: старую БД откладываем в бэкап (ниже по
            // corrupt-пути) и стартуем свежую с новым ключом.
            for _ in 0..<20 where key == nil {
                Thread.sleep(forTimeInterval: 0.5)
                key = storedDbKey()
            }
        }
        let effectiveKey = key ?? makeAndStoreDbKey()   // nil здесь = первый запуск ИЛИ ключ утерян

        if let store = try? CoreStore.open(path: path, encryptionKeyB64: effectiveKey) { return store }
        // Валидный ключ, но база не открылась — настоящее повреждение.
        // Сохраняем файлы рядом (восстановимы вручную) и начинаем с чистой.
        let suffix = ".corrupt-\(Int(Date().timeIntervalSince1970))"
        for candidate in [path, path + "-wal", path + "-shm"] where fm.fileExists(atPath: candidate) {
            try? fm.moveItem(atPath: candidate, toPath: candidate + suffix)
        }
        return try! CoreStore.open(path: path, encryptionKeyB64: effectiveKey)
    }

    private func selectStore(for userId: String) {
        let legacyPath = Self.databasePath()
        let legacy = Self.openStoreRecovering(path: legacyPath)
        let owner = (try? legacy.metaGet(key: "account_owner")) ?? nil
        if owner == nil || owner == userId {
            if owner == nil { try? legacy.metaSet(key: "account_owner", value: userId) }
            store = legacy
        } else {
            let account = Self.openStoreRecovering(path: Self.accountDatabasePath(userId))
            try? account.metaSet(key: "account_owner", value: userId)
            store = account
        }
    }

    // MARK: - Идентичность / сессия

    func setIdentity(id: String, publicKey: String, privateKey: String) {
        if myId != id {
            selectStore(for: id)
            peerKeyCache.removeAll(keepingCapacity: true)
            myOlmIdentity = ""   // у нового аккаунта свой Olm-аккаунт в его store
            // Всё, что привязано к прежнему аккаунту, обнуляем: иначе ключи
            // публиковались бы и инбокс читался бы под чужим device_id, а тревоги
            // доверия «переезжали» бы на другого пользователя.
            cachedDeviceId = ""
            deviceCache.removeAll(keepingCapacity: true)
            pendingKeyChanges.removeAll()
            pendingInboundKeys.removeAll()
            pendingMasterChanges.removeAll()
            pendingUnsignedDevices.removeAll()
        }
        self.myId = id
        self.myPublicKey = publicKey
        self.myPrivateKey = privateKey
    }

    func restoreSession(token: String, userId: String, publicKey: String, privateKey: String) {
        api.setSession(token: token, userId: userId)
        setIdentity(id: userId, publicKey: publicKey, privateKey: privateKey)
    }

    var privateKey: String { myPrivateKey }

    // MARK: - Аккаунт

    /// Регистрация: ядро генерит пару, шифрует приватный ключ паролем (PBKDF2+AES-GCM),
    /// шлёт на сервер. Возвращает сессию; приватный ключ оседает в акторе (Keychain — снаружи).
    func register(userId: String, password: String) throws -> (session: AuthSession, privateKey: String) {
        let kp = generateKeypair()
        let encPriv = try encryptPrivateKey(privateKeyB64: kp.privateB64, password: password)
        let session = try api.register(
            userId: userId,
            password: password,
            publicKeyB64: kp.publicB64,
            encryptedPrivateKeyB64: encPriv
        )
        setIdentity(id: session.userId, publicKey: kp.publicB64, privateKey: kp.privateB64)
        return (session, kp.privateB64)
    }

    /// Вход: логин на сервере, затем расшифровка приватного ключа паролем-бэкапа.
    /// totpCode — одноразовый код, когда на аккаунте включена 2FA.
    func login(userId: String, password: String, totpCode: String? = nil) throws -> (session: AuthSession, privateKey: String) {
        let session = try api.loginTotp(userId: userId, password: password, totpCode: totpCode)
        let priv: String
        if session.encryptedPrivateKeyB64.isEmpty {
            throw CoreError.BadInput(msg: "На сервере нет резервной копии ключа для этого аккаунта")
        }
        priv = try decryptPrivateKey(blob: session.encryptedPrivateKeyB64, password: password)
        setIdentity(id: session.userId, publicKey: session.publicKeyB64, privateKey: priv)
        // Привязка сессии к устройству — чтобы её можно было выкинуть адресно.
        // Olm-identity доказывает владение устройством (сервер сверяет с директорией).
        if let device = try? requireDeviceId() {
            try? api.bindSessionDeviceProof(deviceId: device,
                                            identityKeyB64: myOlmIdentityKey())
        }
        return (session, priv)
    }

    /// true — сервер ответил, что нужен 2FA-код (для повторного логина с кодом).
    nonisolated static func isTotpRequired(_ error: Error) -> Bool {
        guard case let CoreError.Api(status, msg) = error, status == 401 else { return false }
        return msg.contains("totp_required") || msg.contains("totp_invalid")
    }

    func logout() {
        try? api.logout()
        myId = ""; myPublicKey = ""; myPrivateKey = ""
        peerKeyCache.removeAll(keepingCapacity: false)
    }

    func heartbeat() { try? api.heartbeat() }

    // MARK: - Контроль сессий / 2FA / wipe

    struct DeviceSession: Identifiable {
        var id: String { deviceId }
        let deviceId: String
        let createdAt: String
        let sessions: Int
        let current: Bool
    }
    struct SessionsInfo {
        let devices: [DeviceSession]
        let canKick: Bool
        let kickMinHours: Int
        let unbound: Int
        var myDeviceId: String { devices.first(where: { $0.current })?.deviceId ?? "" }
    }

    func listSessions() throws -> SessionsInfo {
        let json = try api.listSessions()
        let obj = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any] ?? [:]
        let devices = (obj["devices"] as? [[String: Any]] ?? []).map {
            DeviceSession(
                deviceId: $0["device_id"] as? String ?? "",
                createdAt: $0["device_created_at"] as? String ?? "",
                sessions: $0["sessions"] as? Int ?? 0,
                current: $0["current"] as? Bool ?? false
            )
        }
        return SessionsInfo(
            devices: devices,
            canKick: obj["can_kick"] as? Bool ?? false,
            kickMinHours: obj["kick_min_hours"] as? Int ?? 12,
            unbound: obj["unbound_sessions"] as? Int ?? 0
        )
    }

    func kickDevice(_ deviceId: String) throws { try api.kickDevice(deviceId: deviceId) }

    func totpEnabled() throws -> Bool { try api.totpStatus() }

    /// Возвращает секрет (base32) для добавления в аутентификатор.
    func totpSetup() throws -> String {
        let json = try api.totpSetup()
        let obj = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any] ?? [:]
        return obj["secret"] as? String ?? ""
    }

    func totpEnable(code: String) throws { try api.totpEnable(code: code) }
    func totpDisable(code: String) throws { try api.totpDisable(code: code) }
    func wipeAccount(password: String) throws { try api.wipeAccount(password: password) }

    func updateProfile(username: String?, displayName: String?, avatarFileId: String?, bio: String?) throws {
        try api.updateProfile(username: username, displayName: displayName, avatarFileId: avatarFileId, bio: bio)
    }

    /// Загружает картинку аватара (публичный, без шифрования — см. WIRE_PROTOCOL.md
    /// `POST /avatars`), возвращает file_id для последующего updateProfile.
    func uploadAvatar(data: Data, mime: String) throws -> String {
        try api.uploadAvatar(data: data, mime: mime)
    }

    func getProfile(_ userId: String) throws -> Profile {
        try api.getUserProfile(userId: userId)
    }

    func searchUsers(_ query: String) throws -> [Profile] {
        try api.searchUsers(query: query)
    }

    // MARK: - Публичные ключи + TOFU

    /// Получает публичный ключ пира (кэш пинов). Возвращает ключ и флаг «ключ сменился» (MITM-тревога).
    func publicKey(for peerId: String) throws -> (key: String, changed: Bool) {
        let id = peerId.lowercased()
        if let cached = peerKeyCache[id], Date().timeIntervalSince(cached.fetchedAt) < 10 * 60 {
            return (cached.key, false)
        }
        let key = try api.getPublicKey(userId: id)
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let changed = try store.pinUpsert(peerId: id, publicKeyB64: key, firstSeen: now)
        peerKeyCache[id] = (key, Date())
        return (key, changed)
    }

    /// Пин ключа пира (для TOFU-экрана).
    func keyPin(_ peerId: String) throws -> KeyPin? {
        try store.pinGet(peerId: peerId.lowercased())
    }

    /// Отметить ключ пира проверенным/непроверенным (сверка отпечатков).
    func setKeyVerified(_ peerId: String, _ verified: Bool) throws {
        try store.pinSetVerified(peerId: peerId.lowercased(), verified: verified)
    }

    /// Мой публичный ключ (для отпечатка на TOFU-экране).
    func myPublicKeyB64() -> String { myPublicKey }

    // MARK: - Olm / Double Ratchet (1:1)

    /// Pickle Olm-аккаунта (identity + one-time keys). Создаётся при первом обращении.
    private func olmAccount() throws -> String {
        if let pickle = try? store.metaGet(key: "olm_account"), !pickle.isEmpty {
            return pickle
        }
        let pickle = try olmAccountNew()
        try store.metaSet(key: "olm_account", value: pickle)
        return pickle
    }

    private func myOlmIdentityKey() throws -> String {
        if !myOlmIdentity.isEmpty { return myOlmIdentity }
        myOlmIdentity = try olmAccountIdentity(accountPickle: olmAccount())
        return myOlmIdentity
    }

    // MARK: - Multi-device

    /// device_id этого устройства. Существующая установка с Olm-аккаунтом,
    /// чей identity уже лежит на сервере, узнаёт себя (обычно 'primary');
    /// свежая установка при живом аккаунте занимает новый слот 'ios-xxx'.
    private var cachedDeviceId: String = ""
    private func myDeviceId() -> String {
        if !cachedDeviceId.isEmpty { return cachedDeviceId }
        if let stored = try? store.metaGet(key: "device_id"), !stored.isEmpty {
            cachedDeviceId = stored
            return stored
        }
        // Директория НЕДОСТУПНА (сеть/сервер) — это не то же самое, что «устройств
        // нет». Молча зафиксировать 'primary' значило бы захватить слот чужого
        // устройства и затереть его ключи. Ждём следующей попытки.
        guard let devices = try? api.listDevices(userId: myId) else { return "" }
        var resolved = "primary"
        if devices.isEmpty {
            resolved = "primary"
        } else if let identity = try? myOlmIdentityKey(),
                  let mine = devices.first(where: { $0.identityKeyB64 == identity }) {
            resolved = mine.deviceId
        } else {
            resolved = "ios-" + UUID().uuidString.prefix(10).lowercased()
        }
        cachedDeviceId = resolved
        try? store.metaSet(key: "device_id", value: resolved)
        return resolved
    }

    /// device_id или ошибка: пустой означает «директория была недоступна».
    /// Работать под неизвестным устройством нельзя — повторим позже.
    private func requireDeviceId() throws -> String {
        let id = myDeviceId()
        guard !id.isEmpty else {
            throw CoreError.Network(msg: "устройство не определено: директория недоступна")
        }
        return id
    }

    /// Ключ Olm-сессии в сторадже: primary живёт под старым ключом peerId
    /// (существующие сессии не теряются), остальные — peer::device.
    private func sessionKey(_ peerId: String, _ deviceId: String) -> String {
        deviceId == "primary" ? peerId : "\(peerId)::\(deviceId)"
    }

    private var deviceCache: [String: (devices: [DeviceInfo], at: Date, forcedAt: Date)] = [:]
    private func peerDevices(_ peerId: String, force: Bool = false) throws -> [DeviceInfo] {
        let id = peerId.lowercased()
        if let cached = deviceCache[id] {
            // Форс-рефетч гасим отдельным окном (короче обычного): иначе каждое
            // застрявшее сообщение на каждом поллинге било бы по директории заново,
            // но и ждать минуту при появлении нового устройства пира не годится.
            let window: TimeInterval = force ? 10 : 60
            let fresh = Date().timeIntervalSince(force ? cached.forcedAt : cached.at) < window
            if fresh { return cached.devices }
        }
        let devices = try api.listDevices(userId: id)
        let forcedAt = force ? Date() : (deviceCache[id]?.forcedAt ?? .distantPast)
        deviceCache[id] = (devices, Date(), forcedAt)
        return devices
    }

    /// Опубликовать/пополнить prekeys на сервере. Идемпотентно: заливает identity
    /// и генерит новые OTK, когда на сервере их мало. Дёргается при старте и после
    /// расхода OTK на входящей prekey-сессии.
    func ensureOlmKeys() {
        do {
            let acct = try olmAccount()
            let device = try requireDeviceId()
            let count = (try? api.keysCountDevice(deviceId: device)) ?? 0
            // Форс-перепубликация один раз после апдейта: подписанный и cross-signed
            // бандл должен заместить старые OTK на сервере, даже если их ≥20.
            // Версия флага бумпается при КАЖДОМ изменении формата публикации,
            // иначе устройства, пережившие предыдущую версию, останутся без неё.
            let alreadyPublished = ((try? store.metaGet(key: "olm_published_v2")) ?? nil) == "1"
            // Fallback-ключ (P10 / SEC MED-3) — «последний рубеж», когда одноразовые
            // кончились. Он переиспользуемый, поэтому ротируем: чем дольше живёт
            // один и тот же ключ, тем больше начал переписок вскрывается разом при
            // его компрометации. Ядро держит две приватные части подряд, так что
            // сообщения, отправленные на прежний, ещё какое-то время читаются.
            let fallbackTs = Double(((try? store.metaGet(key: "olm_fallback_ts")) ?? nil) ?? "") ?? 0
            let fallbackStale = Date().timeIntervalSince1970 - fallbackTs > Self.fallbackRotateInterval
            guard count < 20 || !alreadyPublished || fallbackStale else { return }
            let published = try olmAccountGenerateOtksSigned(accountPickle: acct, count: 40,
                                                             userId: myId, deviceId: device)
            try store.metaSet(key: "olm_account", value: published.accountPickle)
            myOlmIdentity = published.identityKeyB64
            // Fallback генерируем ПОСЛЕ одноразовых: он выводится из того же
            // аккаунта, и порядок гарантирует, что публикуем актуальный pickle.
            let fallback = fallbackStale
                ? try? olmAccountGenerateFallbackSigned(accountPickle: published.accountPickle,
                                                        userId: myId, deviceId: device)
                : nil
            if let fallback {
                try store.metaSet(key: "olm_account", value: fallback.accountPickle)
            }
            // Cross-signing (P8): устройство подписывается мастер-ключом аккаунта,
            // выведенным из приватного ключа — он есть на каждом устройстве после логина.
            let masterKey = try olmMasterPublic(accountSecretB64: myPrivateKey)
            let deviceSig = try olmSignDevice(accountSecretB64: myPrivateKey, userId: myId,
                                              deviceId: device,
                                              identityKeyB64: published.identityKeyB64,
                                              ed25519KeyB64: published.ed25519KeyB64)
            try api.uploadKeysDeviceSigned(identityKeyB64: published.identityKeyB64,
                                           ed25519KeyB64: published.ed25519KeyB64,
                                           identitySigB64: published.identitySigB64,
                                           oneTimeKeysJson: published.oneTimeKeysJson,
                                           otkSignaturesJson: published.otkSignaturesJson,
                                           deviceId: device,
                                           masterKeyB64: masterKey,
                                           deviceSigB64: deviceSig,
                                           fallbackKeyId: fallback?.keyId,
                                           fallbackKeyB64: fallback?.keyB64,
                                           fallbackSigB64: fallback?.sigB64)
            // Метку ставим только после успешного upload: иначе сорвавшаяся
            // публикация считалась бы состоявшейся и следующая попытка ушла бы
            // на неделю вперёд — с fallback-ключом, которого сервер не видел.
            if fallback != nil {
                try store.metaSet(key: "olm_fallback_ts", value: String(Date().timeIntervalSince1970))
            }
            deviceCache.removeAll()
            // Флаг ставим ТОЛЬКО когда сервер реально сохранил подписи: старая
            // версия сервера молча игнорирует незнакомые поля и отвечает 200 —
            // устройство считало бы cross-signing опубликованным, не опубликовав
            // его, и не повторяло бы попытку до расхода OTK.
            let stored = (try? api.listDevices(userId: myId))?
                .first { $0.deviceId == device }
            if stored?.masterKeyB64 != nil {
                try store.metaSet(key: "olm_published_v2", value: "1")
            }
        } catch {
            // Не критично для UI: повторится при следующем старте/приёме.
        }
    }

    // MARK: - TOFU olm-identity (SEC HIGH-2)

    /// Ожидающие подтверждения смены ключа: peerKey → бандл, который не прошёл пин.
    /// Пользователь принимает новый ключ явно (acceptNewOlmKey) — тихих перепинов нет.
    private var pendingKeyChanges: [String: PrekeyBundle] = [:]
    /// То же для входящих: в конверте только curve-identity, бандла нет.
    private var pendingInboundKeys: [String: String] = [:]
    /// Непринятая смена МАСТЕР-ключа аккаунта пира (P8): peerId → новый мастер.
    private var pendingMasterChanges: [String: String] = [:]
    /// Устройства пира без cross-signing, которые мы отказались принять
    /// автоматически: peerKey → curve-identity. Пользователь решает явно.
    private var pendingUnsignedDevices: [String: String] = [:]

    private func nowTs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// Разобрать ключ пина/сессии обратно в (peer, device).
    private func splitSessionKey(_ peerKey: String) -> (peer: String, device: String) {
        guard let range = peerKey.range(of: "::") else { return (peerKey, "primary") }
        return (String(peerKey[..<range.lowerBound]), String(peerKey[range.upperBound...]))
    }

    /// Проверка подписи бандла. Возвращает ВЕРИФИЦИРОВАННЫЙ ed25519 (nil — легаси-бандл
    /// без подписей). Частично подписанный бандл (есть часть полей) отвергается: честный
    /// сервер такой не отдаёт, а «мягкий» проход пинил бы непроверенный ключ.
    private func verifiedEd25519(peer: String, deviceId: String, bundle: PrekeyBundle) throws -> String? {
        let key = sessionKey(peer, deviceId)
        let pinnedEd = ((try? store.olmPinGet(peerKey: key)) ?? nil)?.ed25519B64
        let anySig = bundle.ed25519KeyB64 != nil || bundle.identitySigB64 != nil
            || bundle.oneTimeKeySigB64 != nil
        guard anySig || pinnedEd != nil else { return nil }   // легаси-путь: TOFU только по curve
        guard let ed = bundle.ed25519KeyB64, let idSig = bundle.identitySigB64,
              let otkSig = bundle.oneTimeKeySigB64 else {
            throw KeyTrustAlert(message: pinnedEd != nil
                ? "prekey-бандл без подписи, хотя устройство её публиковало — возможна подмена сервером"
                : "неполный подписанный prekey-бандл — возможна подмена сервером")
        }
        do {
            try olmVerifyPrekeyBundle(userId: peer, deviceId: deviceId,
                                      identityKeyB64: bundle.identityKeyB64,
                                      ed25519KeyB64: ed, identitySigB64: idSig,
                                      otkId: bundle.oneTimeKeyId,
                                      otkB64: bundle.oneTimeKeyB64,
                                      otkSigB64: otkSig)
        } catch {
            throw KeyTrustAlert(message: "подпись prekey-бандла не сошлась — возможна подмена ключей сервером")
        }
        return ed
    }

    /// Cross-signing (P8): устройство должно быть подписано мастер-ключом аккаунта
    /// пира, а сам мастер — совпасть с запиненным. Подписи бандла (P7) доказывают
    /// лишь самосогласованность записи, поэтому без этой проверки сервер мог бы
    /// подсадить пиру фантомное устройство и получать копию каждого сообщения.
    private func verifyDeviceOwnership(peer: String, deviceId: String, curve: String,
                                       ed: String?, master: String?, deviceSig: String?) throws {
        let pinnedMaster = ((try? store.masterPinGet(peerId: peer)) ?? nil)?.masterKeyB64
        // Знание об ЭТОМ устройстве, а не об аккаунте: иначе у пира со смешанным
        // набором (обновлённый веб + необновлённый телефон) старое устройство
        // отвергалось бы навсегда, а с ним — и вся переписка.
        let devicePin = ((try? store.olmPinGet(peerKey: sessionKey(peer, deviceId))) ?? nil)
        guard let master, let deviceSig, let ed else {
            // Сервер публикует подписанный бандл только вместе с мастер-подписью,
            // поэтому «ed есть, мастера нет» — стриппинг полей, а не старый клиент.
            if devicePin?.ed25519B64 != nil || ed != nil {
                throw KeyTrustAlert(message: "устройство собеседника не подписано его аккаунтом — возможна подмена ключей сервером")
            }
            if pinnedMaster != nil && devicePin == nil {
                // Аккаунт уже публикует подписи, а это устройство мы видим впервые
                // и оно без подписи — форма подсадки. Пропускаем его, не роняя
                // отправку остальным (легитимный случай: пир ещё не обновил клиент).
                // Тревогу делаем выводимой: пользователь увидит баннер и сможет
                // осознанно довериться устройству.
                pendingUnsignedDevices[sessionKey(peer, deviceId)] = curve
                throw KeyTrustAlert(
                    message: "устройство собеседника не подписано его аккаунтом — попросите его обновить приложение",
                    skipDevice: true)
            }
            return   // знакомое легаси-устройство (до P7/P8): TOFU только по curve
        }
        // Сначала доказательство, потом доверие: пин ставится ТОЛЬКО после того,
        // как мастер действительно подписал эту запись устройства.
        do {
            try olmVerifyDevice(masterKeyB64: master, userId: peer, deviceId: deviceId,
                                identityKeyB64: curve, ed25519KeyB64: ed, deviceSigB64: deviceSig)
        } catch {
            throw KeyTrustAlert(message: "подпись устройства не сошлась с мастер-ключом аккаунта — возможна подсадка устройства сервером")
        }
        if let pinnedMaster, pinnedMaster != master {
            pendingMasterChanges[peer] = master
            throw KeyTrustAlert(message: "мастер-ключ аккаунта собеседника изменился — сверьте цифры безопасности или примите новый ключ")
        }
        if pinnedMaster == nil {
            _ = try store.masterPinCheck(peerId: peer, masterKeyB64: master, nowTs: nowTs())
        }
    }

    /// Гейт исходящих: cross-signing + верификация подписи бандла + TOFU-пин olm-identity.
    /// Бросает KeyTrustAlert при неверной/неполной подписи, стриппинге и смене ключа —
    /// только эти ошибки роняют отправку целиком (см. sendDirect).
    private func verifyAndPinBundle(peer: String, deviceId: String, bundle: PrekeyBundle) throws {
        let key = sessionKey(peer, deviceId)
        try verifyDeviceOwnership(peer: peer, deviceId: deviceId, curve: bundle.identityKeyB64,
                                  ed: bundle.ed25519KeyB64, master: bundle.masterKeyB64,
                                  deviceSig: bundle.deviceSigB64)
        // В пин уходит только ПРОВЕРЕННЫЙ ed25519: иначе сервер мог бы подсунуть
        // свой ed в неподписанном бандле и намертво поссорить нас с честным пиром.
        let ed = try verifiedEd25519(peer: peer, deviceId: deviceId, bundle: bundle)
        let status = try store.olmPinCheck(peerKey: key, curve25519B64: bundle.identityKeyB64,
                                           ed25519B64: ed, nowTs: nowTs())
        if status == .mismatch {
            pendingKeyChanges[key] = bundle
            throw KeyTrustAlert(message: "olm-ключ собеседника изменился — сверьте цифры безопасности или примите новый ключ")
        }
    }

    /// Мой мастер-ключ аккаунта (для отпечатка на экране сверки).
    func myMasterKeyB64() -> String? {
        guard !myPrivateKey.isEmpty else { return nil }
        return try? olmMasterPublic(accountSecretB64: myPrivateKey)
    }

    /// Содержимое МОЕЙ QR-метки для сверки (канон ядра `aether:verify?v=2`).
    /// nil, если мастер-ключа ещё нет — показывать нечего.
    func myVerifyQr() -> String? {
        guard let master = myMasterKeyB64(), !myId.isEmpty else { return nil }
        return try? olmVerifyQrBuild(userId: myId, masterKeyB64: master)
    }

    /// Итог сканирования чужой QR-метки.
    enum QrVerifyResult {
        /// Ключ совпал с запиненным — пин помечен подтверждённым.
        case verified
        /// Отсканирован QR другого аккаунта (id не совпал с открытым чатом).
        case wrongPeer(scanned: String)
        /// Ключ НЕ совпал с тем, что мы знаем о пире, — возможен MITM.
        case mismatch
        /// Пира мы ещё не знаем: пинить с бумажки нельзя (см. ниже).
        case noPin
    }

    /// Сверить отсканированную метку с запиненным мастер-ключом пира.
    ///
    /// QR только ПОДТВЕРЖДАЕТ уже известный ключ, но никогда не заводит новый пин:
    /// иначе сканирование стало бы способом принять ключ, который ядро ни разу не
    /// видело в подписанном бандле, — а это ровно та подмена, от которой сверка и
    /// защищает. При тревоге сверяем непринятый мастер: подтверждаем именно тот
    /// ключ, который пользователю предлагается принять.
    func verifyByQr(peerId: String, scanned: String) throws -> QrVerifyResult {
        let parsed = try olmVerifyQrParse(text: scanned)
        let peer = peerId.lowercased()
        guard parsed.userId == peer else { return .wrongPeer(scanned: parsed.userId) }
        guard let known = pendingMasterKey(for: peer) ?? masterPin(peer)?.masterKeyB64 else {
            return .noPin
        }
        guard known == parsed.masterKeyB64 else { return .mismatch }
        try setMasterVerified(peer, true)
        return .verified
    }

    /// Запиненный мастер-ключ пира и статус его ручной сверки.
    func masterPin(_ peerId: String) -> MasterPin? {
        (try? store.masterPinGet(peerId: peerId.lowercased())) ?? nil
    }

    /// Мастер-ключ, ожидающий решения пользователя (если есть). Сверять при
    /// тревоге надо именно его — иначе экран показывал бы СТАРЫЙ ключ под
    /// подписью «сверьте новый» и зелёную галочку «подтверждён».
    func pendingMasterKey(for peerId: String) -> String? {
        pendingMasterChanges[peerId.lowercased()]
    }

    func setMasterVerified(_ peerId: String, _ verified: Bool) throws {
        try store.masterPinSetVerified(peerId: peerId.lowercased(), verified: verified)
    }

    /// Есть ли непринятая смена ключа у пира (для алерта/баннера в чате).
    /// Смена мастер-ключа приоритетнее: она обесценивает все device-пины пира.
    func pendingKeyChange(for peerId: String) -> String? {
        let id = peerId.lowercased()
        let matches: (String) -> Bool = { $0 == id || $0.hasPrefix("\(id)::") }
        if pendingMasterChanges[id] != nil { return id }
        return pendingKeyChanges.keys.first(where: matches)
            ?? pendingInboundKeys.keys.first(where: matches)
            ?? pendingUnsignedDevices.keys.first(where: matches)
    }

    /// Вид ожидающей тревоги — тексты и последствия у них разные.
    enum KeyAlertKind {
        case masterChanged       // сменился ключ аккаунта: всё доверие к пиру обнуляется
        case deviceKeyChanged    // сменился ключ одного устройства
        case deviceUnsigned      // устройство не подписано аккаунтом (старый клиент пира)
    }

    func pendingKeyAlertKind(for peerId: String) -> KeyAlertKind? {
        let id = peerId.lowercased()
        let matches: (String) -> Bool = { $0 == id || $0.hasPrefix("\(id)::") }
        if pendingMasterChanges[id] != nil { return .masterChanged }
        if pendingKeyChanges.keys.contains(where: matches)
            || pendingInboundKeys.keys.contains(where: matches) { return .deviceKeyChanged }
        if pendingUnsignedDevices.keys.contains(where: matches) { return .deviceUnsigned }
        return nil
    }

    /// Явно принять новый ключ устройства пира. Для исходящих бандл берётся СВЕЖИМ
    /// claim'ом: отложенный в стэше OTK мог быть давно израсходован или вытеснен, а
    /// olmCreateOutbound — чистая математика и молча собрала бы мёртвую сессию.
    /// Пин обновляется только после успешного создания сессии. Для входящих —
    /// перепин curve-ключа; сессию заведёт следующий prekey-конверт (отклонённое
    /// сообщение ретраится поллингом и вскроется само).
    func acceptNewOlmKey(peerKey: String) throws {
        // Смена мастера обесценивает все device-пины и сессии пира: переустановка
        // аккаунта либо атака. Принимаем мастер и обнуляем производное доверие —
        // сессии переустановятся свежим prekey-обменом с проверкой подписи.
        if let master = pendingMasterChanges[peerKey] {
            // Чистка ПЕРЕД принятием пина и без сети: иначе при обрыве связи мастер
            // оказался бы принят, а мёртвые сессии остались — и отправка пошла бы
            // старыми ключами мимо всех проверок (claim не делается при живой сессии).
            try store.peerTrustReset(peerId: peerKey)
            try store.masterPinAccept(peerId: peerKey, masterKeyB64: master, nowTs: nowTs())
            pendingMasterChanges.removeValue(forKey: peerKey)
            let prefix = "\(peerKey)::"
            let mine: (String) -> Bool = { $0 != peerKey && !$0.hasPrefix(prefix) }
            pendingKeyChanges = pendingKeyChanges.filter { mine($0.key) }
            pendingInboundKeys = pendingInboundKeys.filter { mine($0.key) }
            pendingUnsignedDevices = pendingUnsignedDevices.filter { mine($0.key) }
            deviceCache.removeValue(forKey: peerKey)
            return
        }
        if pendingKeyChanges[peerKey] != nil {
            let (peer, device) = splitSessionKey(peerKey)
            let bundle = try api.claimKeysDevice(userId: peer, deviceId: device)
            // Принятие смены ключа устройства НЕ отменяет cross-signing: устройство
            // всё так же обязано принадлежать аккаунту пира.
            try verifyDeviceOwnership(peer: peer, deviceId: device, curve: bundle.identityKeyB64,
                                      ed: bundle.ed25519KeyB64, master: bundle.masterKeyB64,
                                      deviceSig: bundle.deviceSigB64)
            let ed = try verifiedEd25519(peer: peer, deviceId: device, bundle: bundle)
            let session = try olmCreateOutbound(accountPickle: olmAccount(),
                                                theirIdentityB64: bundle.identityKeyB64,
                                                theirOneTimeKeyB64: bundle.oneTimeKeyB64)
            try store.olmPinAccept(peerKey: peerKey, curve25519B64: bundle.identityKeyB64,
                                   ed25519B64: ed, nowTs: nowTs())
            try store.olmSessionSet(peerId: peerKey, sessionJson: session)
            pendingKeyChanges.removeValue(forKey: peerKey)
        } else if let curve = pendingInboundKeys[peerKey] {
            // ed25519 не затираем: если устройство раньше публиковало подписи,
            // анти-стриппинг должен остаться вооружённым и после принятия.
            let keepEd = ((try? store.olmPinGet(peerKey: peerKey)) ?? nil)?.ed25519B64
            try store.olmPinAccept(peerKey: peerKey, curve25519B64: curve,
                                   ed25519B64: keepEd, nowTs: nowTs())
            // Старая сессия мертва: ключ устройства сменился. Без её удаления
            // исходящие продолжали бы уходить прежними ключами — claim при живой
            // сессии не делается, и все новые проверки обходились бы стороной.
            try? store.olmSessionDelete(peerId: peerKey)
            pendingInboundKeys.removeValue(forKey: peerKey)
        } else if let curve = pendingUnsignedDevices[peerKey] {
            // Пользователь осознанно доверяет устройству без cross-signing
            // (обычно: пир ещё не обновил приложение). Пин по curve, без ed25519 —
            // как у легаси-устройства; появятся подписи — заработает анти-стриппинг.
            try store.olmPinAccept(peerKey: peerKey, curve25519B64: curve,
                                   ed25519B64: nil, nowTs: nowTs())
            pendingUnsignedDevices.removeValue(forKey: peerKey)
        } else {
            throw CoreError.BadInput(msg: "нет ожидающей смены ключа")
        }
    }

    /// Ratchet-конверт 1:1: {ratchet, olm_identity, sender_device, type, body_b64}.
    private func ratchetEnvelope(type: UInt32, body: String) throws -> String {
        let obj: [String: Any] = ["ratchet": "1", "olm_identity": try myOlmIdentityKey(),
                                  "sender_device": try requireDeviceId(),
                                  "type": Int(type), "body_b64": body]
        let data = try JSONSerialization.data(withJSONObject: obj)
        return String(decoding: data, as: UTF8.self)
    }

    // MARK: - Отправка

    /// Запечатать личное сообщение Double Ratchet'ом и отправить. При первом
    /// сообщении устанавливает Olm-сессию по prekey-бандлу пира (X3DH).
    /// Отправка 1:1 ТОЛЬКО через Double Ratchet — никаких даунгрейдов. Если у пира
    /// нет prekeys (claim 404/409) — сообщение видимо падает, а не уходит слабым
    /// статическим box. box остаётся лишь для обёртки групповых ключей.
    func sendDirect(to peerId: String, wirePayload: String, clientId: String? = nil) throws -> String {
        let id = peerId.lowercased()
        // Multi-device fanout: своя Olm-сессия и копия конверта каждому устройству
        // получателя. Различаем «директория пуста» (легаси-аккаунт без устройств)
        // и «директория недоступна» — во втором случае молчаливый откат на primary
        // тихо терял бы копии для остальных устройств.
        var devices: [DeviceInfo]
        do {
            devices = try peerDevices(id)
        } catch {
            throw error
        }
        if devices.isEmpty {
            devices = [DeviceInfo(deviceId: "primary", identityKeyB64: "",
                                  ed25519KeyB64: nil, identitySigB64: nil,
                                  masterKeyB64: nil, deviceSigB64: nil)]
        }
        // Фаза 1 — сессии: claim + верификация подписи бандла + TOFU-пин (SEC HIGH-2)
        // для ВСЕХ устройств до отправки первой копии. Крипто-тревога (смена ключа,
        // битая подпись) роняет отправку целиком — никаких «ушло наполовину».
        var sessions: [(device: DeviceInfo, pickle: String)] = []
        var firstError: Error? = nil
        for dev in devices {
            let key = sessionKey(id, dev.deviceId)
            if let existing = ((try? store.olmSessionGet(peerId: key)) ?? nil) {
                sessions.append((dev, existing))
                continue
            }
            do {
                let bundle = try api.claimKeysDevice(userId: id, deviceId: dev.deviceId)
                try verifyAndPinBundle(peer: id, deviceId: dev.deviceId, bundle: bundle)
                let pickle = try olmCreateOutbound(accountPickle: olmAccount(),
                                                   theirIdentityB64: bundle.identityKeyB64,
                                                   theirOneTimeKeyB64: bundle.oneTimeKeyB64)
                sessions.append((dev, pickle))
            } catch let alert as KeyTrustAlert {
                if alert.skipDevice {
                    // Проблема одного устройства: остальным доставляем (тревога уже
                    // зарегистрирована в verifyDeviceOwnership и видна в баннере).
                    if firstError == nil { firstError = CoreError.Crypto(msg: alert.message) }
                    continue
                }
                // Тревога доверия ключам — стоп всей отправке, ни одна копия не ушла.
                throw CoreError.Crypto(msg: alert.message)
            } catch {
                // Сеть, 404/409, битый бандл одного устройства — пропускаем его.
                if firstError == nil { firstError = error }
            }
        }
        // Фаза 2 — шифрование и отправка копий.
        var firstMessageId: String? = nil
        for (dev, pickle) in sessions {
            do {
                let key = sessionKey(id, dev.deviceId)
                let enc = try olmEncrypt(sessionPickle: pickle, plaintext: wirePayload)
                try store.olmSessionSet(peerId: key, sessionJson: enc.sessionPickle)
                let envelope = try ratchetEnvelope(type: enc.messageType, body: enc.bodyB64)
                let cid = firstMessageId == nil ? clientId : UUID().uuidString.lowercased()
                let msgId = try api.sendMessageDevice(recipientId: peerId, envelopeJson: envelope,
                                                      clientId: cid, targetDeviceId: dev.deviceId)
                if firstMessageId == nil { firstMessageId = msgId }
            } catch {
                // Одно недоступное устройство не должно ронять отправку остальным.
                if firstError == nil { firstError = error }
            }
        }
        if let messageId = firstMessageId { return messageId }
        throw firstError ?? CoreError.Crypto(msg: "у получателя нет доступных устройств")
    }

    func sendGroup(groupId: String, groupKey: String, wirePayload: String) throws -> String {
        let envelope = try sealGroup(plaintextJson: wirePayload, groupKeyB64: groupKey)
        return try api.sendMessage(recipientId: groupId, envelopeJson: envelope, clientId: nil)
    }

    // MARK: - Приём

    func fetchInbox(since: String?) throws -> [InboxItem] {
        try api.fetchInboxDevice(since: since, deviceId: requireDeviceId())
    }

    func ack(_ ids: [String]) throws {
        try api.ackMessagesDevice(messageIds: ids, deviceId: requireDeviceId())
    }

    /// Вскрыть конверт входящего. 1:1 — Double Ratchet (Olm); группы — общий
    /// симметричный ключ из локального стораджа (crypto_box-обёртка).
    func open(item: InboxItem) throws -> Opened {
        let env = item.envelope
        let obj = (env.data(using: .utf8)).flatMap {
            try? JSONSerialization.jsonObject(with: $0) as? [String: Any]
        }
        // Ratchet-конверт 1:1?
        if let obj, (obj["ratchet"] as? String) == "1" {
            return try ratchetOpen(item: item, obj: obj)
        }
        // 1:1 ТОЛЬКО через Double Ratchet: статический box-конверт для личного
        // чата — это даунгрейд, который обходит подписи P7 и cross-signing P8
        // (в нём нет ни olm_identity, ни sender_device). Web такое отвергает —
        // расхождение сделало бы iOS слабым звеном.
        let isGroupEnvelope = obj.map { $0["is_group"] != nil } ?? false
        if !isGroupEnvelope && item.recipientId.lowercased() == myId.lowercased() {
            throw CoreError.Crypto(msg: "личное сообщение без Double Ratchet отвергнуто (даунгрейд)")
        }
        // Групповое: адресуется на recipient_id = group_id.
        let groupKey = (try? store.getGroupKey(groupId: item.recipientId.lowercased())) ?? nil
        return try openEnvelope(envelopeJson: env, myPrivB64: myPrivateKey, groupKeyB64: groupKey)
    }

    private func ratchetOpen(item: InboxItem, obj: [String: Any]) throws -> Opened {
        let senderIdentity = obj["olm_identity"] as? String ?? ""
        let type = UInt32(obj["type"] as? Int ?? 1)
        let body = obj["body_b64"] as? String ?? ""
        let peer = item.senderId.lowercased()

        // Устройство отправителя: из конверта (новые клиенты) или по identity
        // в директории устройств (легаси-конверты без sender_device → primary).
        var senderDevice = obj["sender_device"] as? String ?? ""
        if senderDevice.isEmpty {
            var devices = (try? peerDevices(peer)) ?? []
            if !devices.contains(where: { $0.identityKeyB64 == senderIdentity }) {
                devices = (try? peerDevices(peer, force: true)) ?? devices
            }
            senderDevice = devices.first(where: { $0.identityKeyB64 == senderIdentity })?.deviceId ?? "primary"
        }
        let key = sessionKey(peer, senderDevice)

        // TOFU-гейт входящих (SEC HIGH-2): olm_identity конверта сверяется с пином
        // ДО расшифровки. Расхождение = смена устройства пира или спуфинг авторства
        // сервером — сообщение отклоняется, тихого перепина нет.
        guard !senderIdentity.isEmpty else {
            throw CoreError.Crypto(msg: "ratchet: конверт без olm_identity")
        }
        // Пин первичен: если это устройство мы уже знаем и его olm-identity совпадает
        // с конвертом, доверие уже установлено — директория тут ничего не доказывает,
        // а поход в сеть только ломал бы приём (пир мог выкинуть/переустановить
        // устройство, и его прошлые сообщения стали бы невскрываемыми навсегда).
        let devicePin = ((try? store.olmPinGet(peerKey: key)) ?? nil)
        let deviceKnown = devicePin?.curve25519B64 == senderIdentity
        let masterArmed = ((try? store.masterPinGet(peerId: peer)) ?? nil) != nil
        // Директория нужна, когда устройство незнакомо (TOFU + cross-signing) ИЛИ
        // когда мастер пира ещё не вооружён. Второе обязательно: в чатах, переживших
        // P7, device-пин уже есть, и без этой ветки мастер не пинился бы никогда —
        // тогда подсадка неподписанного устройства снова проходила бы молча.
        if !deviceKnown || !masterArmed {
            var devices = (try? peerDevices(peer)) ?? []
            if !devices.contains(where: { $0.deviceId == senderDevice }) {
                devices = (try? peerDevices(peer, force: true)) ?? devices
            }
            let entry = devices.first(where: { $0.deviceId == senderDevice })
            if let entry, entry.identityKeyB64 == senderIdentity {
                do {
                    try verifyDeviceOwnership(peer: peer, deviceId: senderDevice,
                                              curve: senderIdentity, ed: entry.ed25519KeyB64,
                                              master: entry.masterKeyB64, deviceSig: entry.deviceSigB64)
                } catch let alert as KeyTrustAlert {
                    // Тревога уже зарегистрирована в verifyDeviceOwnership (баннер в
                    // чате даёт явный выход) — здесь только прекращаем разбор конверта.
                    throw CoreError.Crypto(msg: "ratchet: \(alert.message)")
                }
                if !deviceKnown {
                    // В пин уходит ПРОВЕРЕННЫЙ мастером ed25519 (а не nil): иначе
                    // анти-стриппинг не вооружался бы для чатов, начатых пиром.
                    let pinStatus = try store.olmPinCheck(peerKey: key, curve25519B64: senderIdentity,
                                                          ed25519B64: entry.ed25519KeyB64, nowTs: nowTs())
                    if pinStatus == .mismatch {
                        pendingInboundKeys[key] = senderIdentity
                        throw CoreError.Crypto(msg: "ratchet: olm-ключ отправителя не совпадает с запиненным — возможна подмена")
                    }
                }
            } else if !deviceKnown {
                // Fail-closed только для НЕзнакомого устройства: и обычный сетевой сбой
                // (повторим поллингом), и способ сервера не дать гейту вооружиться.
                throw CoreError.Crypto(msg: "ratchet: устройство отправителя не подтверждено директорией его аккаунта — повторим позже")
            }
            // Знакомое устройство при недоступной директории — вскрываем: пин уже
            // доказывает подлинность, а блокировка потеряла бы переписку зря.
        }

        // Сессий с устройством может быть НЕСКОЛЬКО (SEC MED-4): когда обе стороны
        // начали переписку одновременно, у каждой своя исходящая. Раньше входящий
        // prekey затирал единственную строку, и всё, что собеседник уже зашифровал
        // в затёртой сессии, становилось невскрываемым навсегда. Пробуем все.
        let sessions = ((try? store.olmSessionsFor(peerId: key)) ?? [])
        // prekey (type 0) при живой сессии — обычное дело: пир ещё не увидел наш
        // ответ и продолжает слать prekey. Такой конверт принадлежит УЖЕ
        // существующей сессии — узнаём её по session_id и пробуем первой.
        let prekeySessionId = type == 0 ? try? olmPrekeySessionId(bodyB64: body) : nil
        var ordered = sessions
        if let sid = prekeySessionId, let i = ordered.firstIndex(where: { $0.sessionId == sid }) {
            ordered.insert(ordered.remove(at: i), at: 0)
        }
        for session in ordered {
            if let dec = try? olmDecrypt(sessionPickle: session.sessionJson,
                                         messageType: type, bodyB64: body) {
                try store.olmSessionPut(peerId: key, sessionId: session.sessionId,
                                        sessionJson: dec.sessionPickle)
                return Opened(senderPubB64: senderIdentity, plaintext: dec.plaintext, isGroup: false)
            }
        }
        // Ни одна не подошла. Завести новую может только prekey.
        guard type == 0 else {
            throw CoreError.Crypto(msg: sessions.isEmpty
                ? "ratchet: нет сессии для normal-сообщения"
                : "ratchet: normal-сообщение не расшифровалось ни одной сессией")
        }
        // Prekey ЗНАКОМОЙ сессии, который она не расшифровала — это дубль доставки
        // или битый конверт, а не новая сессия. Заводить её нельзя: create_inbound
        // впустую спалил бы одноразовый ключ (а при их дефиците — и fallback).
        if let sid = prekeySessionId, sessions.contains(where: { $0.sessionId == sid }) {
            throw CoreError.Crypto(msg: "ratchet: prekey уже известной сессии не расшифровался")
        }
        let inb = try olmCreateInbound(accountPickle: olmAccount(),
                                       theirIdentityB64: senderIdentity, bodyB64: body)
        try store.metaSet(key: "olm_account", value: inb.accountPickle)   // OTK списан
        try store.olmSessionPut(peerId: key,
                                sessionId: try olmSessionId(sessionPickle: inb.sessionPickle),
                                sessionJson: inb.sessionPickle)
        ensureOlmKeys()   // пополнить OTK на сервере
        return Opened(senderPubB64: senderIdentity, plaintext: inb.plaintext, isGroup: false)
    }

    // MARK: - Медиа

    /// Зашифровать байты AES-GCM, залить на /upload. Возвращает (file_id, sym_key, nonce).
    func uploadMedia(_ data: Data) throws -> (fileId: String, symKey: String, nonce: String) {
        let symKey = randomKeyB64()
        let sealed = try aesEncrypt(keyB64: symKey, plaintext: data)
        let fileId = try api.upload(data: sealed.ciphertext)
        return (fileId, symKey, sealed.nonceB64)
    }

    /// Скачать и расшифровать медиа.
    func downloadMedia(fileId: String, symKey: String, nonce: String) throws -> Data {
        let ct = try api.download(fileId: fileId)
        return try aesDecrypt(keyB64: symKey, nonceB64: nonce, ciphertext: ct)
    }

    func uploadAvatar(_ data: Data, mime: String) throws -> String {
        try api.uploadAvatar(data: data, mime: mime)
    }
    func avatarURL(_ fileId: String) -> String { api.avatarUrl(fileId: fileId) }

    // MARK: - Группы

    func createGroup(id: String, name: String, description: String?, isChannel: Bool,
                     ownerWrappedKey: String, linkedGroupId: String?) throws -> String {
        try api.createGroup(id: id, name: name, description: description, isChannel: isChannel,
                            encryptedKeyB64: ownerWrappedKey, linkedGroupId: linkedGroupId)
    }
    func addGroupMember(groupId: String, userId: String, wrappedKey: String, role: String?) throws {
        try api.addGroupMember(groupId: groupId, userId: userId, encryptedKeyB64: wrappedKey, role: role)
    }
    func myGroups() throws -> String { try api.getMyGroups() }
    func groupMembers(_ groupId: String) throws -> String { try api.getGroupMembers(groupId: groupId) }
    func removeGroupMember(groupId: String, userId: String) throws { try api.removeGroupMember(groupId: groupId, userId: userId) }
    func updateGroup(groupId: String, name: String?, description: String?) throws { try api.updateGroup(groupId: groupId, name: name, description: description) }
    func leaveGroup(_ groupId: String) throws { try api.leaveGroup(groupId: groupId) }
    func deleteGroup(_ groupId: String) throws { try api.deleteGroup(groupId: groupId) }

    /// Новый случайный симметричный ключ группы (32 байта, b64url).
    func newGroupKey() -> String { randomKeyB64() }

    /// Обернуть групповой ключ box'ом для получателя (моими ключами). Формат encrypted_key_b64.
    func wrapGroupKeyFor(groupKey: String, recipientPub: String) throws -> String {
        try wrapGroupKey(groupKeyB64: groupKey, recipientPubB64: recipientPub,
                         senderPubB64: myPublicKey, senderPrivB64: myPrivateKey)
    }

    /// Обернуть групповой ключ для себя (на собственный публичный ключ).
    func wrapGroupKeyForSelf(groupKey: String) throws -> String {
        try wrapGroupKey(groupKeyB64: groupKey, recipientPubB64: myPublicKey,
                         senderPubB64: myPublicKey, senderPrivB64: myPrivateKey)
    }

    /// Развернуть мой encrypted_key_b64 → b64url ключа группы (или nil).
    func unwrapMyGroupKey(_ encryptedKeyB64: String) -> String? {
        try? unwrapGroupKey(encryptedKeyB64: encryptedKeyB64, myPrivB64: myPrivateKey)
    }

    // MARK: - Хранилище (прокси на акторе — выполняется вне main thread)

    func chatList() throws -> [Chat] { try store.getChatList() }
    func messages(peer: String, beforeTs: Int64, limit: UInt32) throws -> [StoredMessage] {
        try store.getMessagesForPeer(peerId: peer, beforeTs: beforeTs, limit: limit)
    }
    func totalUnread() -> Int64 { (try? store.totalUnread()) ?? 0 }

    // MARK: - Резервная копия истории (P9)
    //
    // Сервер хранит только шифротекст: история пакуется в JSON и шифруется
    // AES-256-GCM ключом, выведенным из приватного ключа аккаунта. Значит копию
    // расшифрует любое устройство владельца сразу после входа по паролю, а
    // сервер — никогда. Плата та же, что и у мастер-ключа: подобрав пароль к
    // сохранённому на сервере приватному ключу, копию можно прочитать.

    static let backupEnabledKey = "history_backup_enabled"
    private static let backupCursorTs = "history_backup_ts"
    private static let backupCursorId = "history_backup_id"
    private static let backupRestoredSeq = "history_backup_seq"

    var backupEnabled: Bool { ((try? store.metaGet(key: Self.backupEnabledKey)) ?? nil) == "1" }

    private func backupKeyB64() throws -> String {
        guard !myPrivateKey.isEmpty else {
            throw CoreError.BadInput(msg: "нет ключа аккаунта")
        }
        return try backupKey(accountSecretB64: myPrivateKey)
    }

    /// Включить/выключить резервную копию. Выключение удаляет её с сервера
    /// и сбрасывает курсор — повторное включение выгрузит историю заново.
    func setBackupEnabled(_ on: Bool) throws {
        if on {
            _ = try backupKeyB64()   // без ключа аккаунта включать нечего
            try store.metaSet(key: Self.backupEnabledKey, value: "1")
        } else {
            try store.metaSet(key: Self.backupEnabledKey, value: "0")
            try api.backupDelete()
            try store.metaSet(key: Self.backupCursorTs, value: "0")
            try store.metaSet(key: Self.backupCursorId, value: "")
        }
    }

    /// Выгрузить новые сообщения. Идемпотентно: курсор (ts, id) двигается только
    /// после успешной загрузки чанка, поэтому обрыв связи не теряет и не дублирует.
    @discardableResult
    func backupSyncUp(limit: UInt32 = 200) -> Int {
        guard backupEnabled else { return 0 }
        var uploaded = 0
        do {
            let key = try backupKeyB64()
            while true {
                let ts = Int64((try? store.metaGet(key: Self.backupCursorTs)) ?? "0" ?? "0") ?? 0
                let lastId = ((try? store.metaGet(key: Self.backupCursorId)) ?? nil) ?? ""
                let batch = try store.messagesForBackup(afterTs: ts, afterId: lastId, limit: limit)
                guard let last = batch.last else { break }
                let payload = batch.map { m -> [String: Any] in
                    ["id": m.id, "peer_id": m.peerId, "outgoing": m.outgoing,
                     "sender_id": m.senderId, "payload_json": m.payloadJson,
                     "status": Int(m.status), "ts": m.ts,
                     "reactions_json": m.reactionsJson, "edited": m.edited, "deleted": m.deleted]
                }
                let json = try JSONSerialization.data(withJSONObject: payload)
                let sealed = try aesEncrypt(keyB64: key, plaintext: json)
                _ = try api.backupUpload(nonceB64: sealed.nonceB64,
                                         ciphertextB64: b64urlEncode(data: Data(sealed.ciphertext)))
                try store.metaSet(key: Self.backupCursorTs, value: String(last.ts))
                try store.metaSet(key: Self.backupCursorId, value: last.id)
                uploaded += batch.count
                if batch.count < Int(limit) { break }
            }
        } catch {
            // Не критично: догрузим при следующем проходе.
        }
        return uploaded
    }

    /// Скачать и вложить резервную копию (новое устройство/переустановка).
    /// Возвращает число восстановленных сообщений.
    @discardableResult
    func backupRestore() throws -> Int {
        let key = try backupKeyB64()
        var restored = 0
        var after = Int64((try? store.metaGet(key: Self.backupRestoredSeq)) ?? "0" ?? "0") ?? 0
        while true {
            let chunks = try api.backupFetch(afterSeq: after, limit: 50)
            if chunks.isEmpty { break }
            for chunk in chunks {
                let raw = try aesDecrypt(keyB64: key, nonceB64: chunk.nonceB64,
                                         ciphertext: try b64urlDecode(s: chunk.ciphertextB64))
                if let rows = try? JSONSerialization.jsonObject(with: Data(raw)) as? [[String: Any]] {
                    for row in rows { restored += restoreRow(row) ? 1 : 0 }
                }
                after = chunk.seq
                try store.metaSet(key: Self.backupRestoredSeq, value: String(after))
            }
        }
        return restored
    }

    private func restoreRow(_ row: [String: Any]) -> Bool {
        guard let id = row["id"] as? String, let peer = row["peer_id"] as? String,
              let payload = row["payload_json"] as? String, let ts = row["ts"] as? Int64 else {
            return false
        }
        if (try? store.messageExists(id: id)) == true { return false }
        let message = StoredMessage(
            id: id, peerId: peer, outgoing: row["outgoing"] as? Bool ?? false,
            senderId: row["sender_id"] as? String ?? peer, payloadJson: payload,
            status: Int32(row["status"] as? Int ?? 1), ts: ts,
            reactionsJson: row["reactions_json"] as? String ?? "{}",
            edited: row["edited"] as? Bool ?? false, deleted: row["deleted"] as? Bool ?? false)
        try? store.insertMessage(m: message)
        // Чат восстанавливаем без счётчика непрочитанного: это старая переписка.
        let text = Wire.parse(payload)?.text ?? ""
        try? store.touchChat(peerId: peer, isGroup: false, title: "", lastText: text,
                             lastTs: ts, incUnread: false)
        return true
    }

    func metaGet(_ key: String) -> String? { try? store.metaGet(key: key) ?? nil }
    func metaSet(_ key: String, _ value: String) { try? store.metaSet(key: key, value: value) }

    func messageExists(_ id: String) -> Bool { (try? store.messageExists(id: id)) ?? false }
    func getMessage(_ id: String) -> StoredMessage? { (try? store.getMessage(id: id)) ?? nil }
    func insertMessage(_ m: StoredMessage) { try? store.insertMessage(m: m) }
    func updateText(_ id: String, _ payloadJson: String) { try? store.updateText(id: id, payloadJson: payloadJson) }
    func updatePayload(_ id: String, _ payloadJson: String) { try? store.updatePayload(id: id, payloadJson: payloadJson) }
    func updateReactions(_ id: String, _ json: String) { try? store.updateReactions(id: id, reactionsJson: json) }
    func updateStatus(_ id: String, _ status: Int32) { try? store.updateStatus(id: id, status: status) }
    func markOutgoingStatus(peer: String, status: Int32) { try? store.markOutgoingStatus(peerId: peer, status: status) }
    func replaceMessageId(old: String, new: String, status: Int32) { try? store.replaceMessageId(oldId: old, newId: new, status: status) }
    func markDeleted(_ id: String) { try? store.markDeleted(id: id) }
    func pendingOutgoing() -> [StoredMessage] { (try? store.getPendingOutgoing()) ?? [] }

    func touchChat(peer: String, isGroup: Bool, title: String, lastText: String, lastTs: Int64, incUnread: Bool) {
        try? store.touchChat(peerId: peer, isGroup: isGroup, title: title, lastText: lastText, lastTs: lastTs, incUnread: incUnread)
    }
    func clearUnread(_ peer: String) { try? store.clearUnread(peerId: peer) }
    func setPinnedFlag(_ peer: String, _ v: Bool) { try? store.setPinned(peerId: peer, v: v) }
    func setMutedFlag(_ peer: String, _ v: Bool) { try? store.setMuted(peerId: peer, v: v) }
    func setArchivedFlag(_ peer: String, _ v: Bool) { try? store.setArchived(peerId: peer, v: v) }
    func deleteChatData(_ peer: String) { try? store.deleteChat(peerId: peer) }
    func deleteHistory(peer: String) throws { try api.deleteHistory(peerId: peer.lowercased()) }

    func groupKey(_ groupId: String) -> String? { (try? store.getGroupKey(groupId: groupId)) ?? nil }
    func setGroupKey(_ groupId: String, _ keyB64: String) { try? store.setGroupKey(groupId: groupId, keyB64: keyB64) }
}
