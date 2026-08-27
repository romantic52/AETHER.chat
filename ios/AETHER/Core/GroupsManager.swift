import Foundation
import SwiftUI

// Метаданные группы/канала (кэш из /groups/me).
struct GroupInfo: Identifiable, Equatable {
    let id: String
    var name: String
    var description: String
    var isChannel: Bool
    var linkedGroupId: String?
    var ownerId: String?
    var memberCount: Int
    /// Публичность: вступить может любой через поиск (у публичных есть @username).
    var publicJoin: Bool = false
    /// @имя публичной группы/канала (общее пространство имён с пользователями).
    var username: String? = nil
    /// Аватар группы/канала (file_id на сервере, публичный как у профилей).
    var avatarFileId: String? = nil
    /// Моя роль в группе — приходит прямо в /groups/me, без отдельного запроса участников.
    var myRole: String

    var isOwnerOrAdmin: Bool { myRole == "owner" || myRole == "admin" }
}

struct GroupMember: Identifiable, Equatable {
    let userId: String
    var role: String        // owner | admin | member
    var displayName: String
    var username: String?
    var lastActive: String?
    var id: String { userId }

    var isOwner: Bool { role == "owner" }
    var isAdmin: Bool { role == "admin" || role == "owner" }
}

// Управление E2E-группами и каналами: загрузка (с разворотом ключей), создание,
// участники, роли. Ключи заворачиваются box'ом каждому участнику (канон Android/web).
@MainActor
final class GroupsManager: ObservableObject {
    @Published private(set) var groups: [String: GroupInfo] = [:]

    private weak var session: Session?
    private weak var messaging: Messaging?
    private var core: CoreClient { session!.core }
    private var myId: String { session?.myId.lowercased() ?? "" }

    func bind(session: Session, messaging: Messaging) {
        self.session = session
        self.messaging = messaging
    }

    func info(_ id: String) -> GroupInfo? { groups[id.lowercased()] }
    func isChannel(_ id: String) -> Bool { groups[id.lowercased()]?.isChannel ?? false }

    // MARK: - Загрузка

    func load() async {
        guard session != nil,
              let json = try? await core.myGroups(),
              let data = json.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let arr = obj["groups"] as? [[String: Any]] else { return }

        // Чтобы отличить «новый чат» (ещё не было в списке) от уже известного —
        // для новых каналов, где я не админ, по умолчанию звук выключен, пока не «подписался».
        let knownPeerIds = Set((messaging?.chats ?? []).map(\.peerId))

        var map: [String: GroupInfo] = [:]
        for g in arr {
            guard let id = (g["id"] as? String)?.lowercased() else { continue }
            let name = (g["name"] as? String) ?? id
            let isChannel = boolOf(g["is_channel"])
            let desc = (g["description"] as? String) ?? ""
            let linked = g["linked_group_id"] as? String
            let owner = (g["owner_id"] as? String) ?? (g["owner"] as? String)
            let count = (g["member_count"] as? NSNumber)?.intValue ?? (g["members"] as? [Any])?.count ?? 0
            let role = (g["role"] as? String) ?? "member"

            // RB-1: ключ группы меняется при ротации, поэтому «уже есть» больше
            // не значит «актуальный». Разворачиваем заново, если сервер отдал
            // эпоху новее той, для которой мы держим ключ.
            let serverEpoch = intOf(g["my_key_epoch"]) ?? intOf(g["key_epoch"]) ?? 1
            let haveStaleKey = await core.groupEpoch(id) < serverEpoch
            if await core.groupKey(id) == nil || haveStaleKey,
               let enc = g["encrypted_key_b64"] as? String,
               let key = await core.unwrapMyGroupKey(enc) {
                await core.setGroupKey(id, key)
                await core.setGroupEpoch(id, serverEpoch)
            }
            let isNew = !knownPeerIds.contains(id)
            // Показать группу в списке чатов.
            await core.touchChat(peer: id, isGroup: true, title: name, lastText: "", lastTs: 0, incUnread: false)
            if isNew && isChannel && role != "owner" && role != "admin" {
                // Не «подписан» по умолчанию — покажем кнопку «Подписаться» в чате.
                await core.setMutedFlag(id, true)
            }

            map[id] = GroupInfo(id: id, name: name, description: desc, isChannel: isChannel,
                                linkedGroupId: linked, ownerId: owner?.lowercased(), memberCount: count,
                                publicJoin: boolOf(g["public_join"]),
                                username: g["username"] as? String,
                                avatarFileId: g["avatar_file_id"] as? String, myRole: role)
        }
        groups = map
        // RB-1: кто-то вышел — до смены ключа он читает всё новое, и группа не
        // пишет вовсе (сервер отвергает отправку). Провернуть ротацию может
        // только админ; чей клиент дойдёт сюда первым, тот её и сделает.
        for g in arr {
            guard let id = (g["id"] as? String)?.lowercased() else { continue }
            let role = (g["role"] as? String) ?? "member"
            if boolOf(g["rekey_required"]), role == "admin" || role == "owner" {
                await rotateKey(groupId: id)
            }
        }
        await messaging?.refreshChats()
        // Список чатов наблюдает Messaging, а не вложенный GroupsManager —
        // будим его явно, иначе аватарки/имена групп не подтянутся в строки,
        // пока чаты сами не изменятся.
        messaging?.objectWillChange.send()
    }

    // MARK: - Создание

    /// Создать группу/канал, добавить участников. Возвращает id группы.
    @discardableResult
    func create(name: String, isChannel: Bool, memberIds: [String],
                description: String? = nil, linkedGroupId: String? = nil) async throws -> String {
        let id = "grp_\(core_randomId())"
        let key = await core.newGroupKey()
        let ownerWrapped = try await core.wrapGroupKeyForSelf(groupKey: key)
        _ = try await core.createGroup(id: id, name: name, description: description,
                                       isChannel: isChannel, ownerWrappedKey: ownerWrapped,
                                       linkedGroupId: linkedGroupId)
        await core.setGroupKey(id, key)
        await core.setGroupEpoch(id, 1)   // RB-1: новая группа — первая эпоха

        for uid in memberIds where uid.lowercased() != myId {
            if let (pub, _) = try? await core.publicKey(for: uid) {
                if let wrapped = try? await core.wrapGroupKeyFor(groupKey: key, recipientPub: pub) {
                    try? await core.addGroupMember(groupId: id, userId: uid, wrappedKey: wrapped, role: "member")
                }
            }
        }
        let ts = Int64(Date().timeIntervalSince1970 * 1000)
        await core.touchChat(peer: id, isGroup: true, title: name, lastText: "", lastTs: ts, incUnread: false)
        await load()
        return id
    }

    /// Владелец: публичность группы/канала (Telegram-модель: публичный = @username).
    /// При включении сервер получает ключ, чтобы самостоятельно выдавать его
    /// вступающим (контент публичного не секретен; E2E приватных не тронут).
    /// Возвращает nil при успехе, иначе — текст ошибки (имя занято, лимит 25).
    @discardableResult
    func setGroupPublic(groupId: String, isPublic: Bool, username: String?) async -> String? {
        let id = groupId.lowercased()
        var keyB64: String? = nil
        if isPublic {
            guard let key = await core.groupKey(id) else { return "Ключ группы недоступен" }
            keyB64 = key
        }
        let error = await ChannelDirectory.setPublic(id, isPublic: isPublic,
                                                     joinKeyB64: keyB64, username: username)
        if error == nil, var info = groups[id] {
            info.publicJoin = isPublic
            info.username = isPublic ? username?.lowercased() : nil
            groups[id] = info
        }
        return error
    }

    /// Владелец/админ: установить аватар группы/канала (публичный, как у профилей).
    @discardableResult
    func setGroupAvatar(groupId: String, data: Data, mime: String) async -> Bool {
        let id = groupId.lowercased()
        guard let fileId = try? await core.uploadAvatar(data: data, mime: mime) else { return false }
        let ok = await ChannelDirectory.updateGroup(id, fields: ["avatar_file_id": fileId])
        if ok, var info = groups[id] {
            info.avatarFileId = fileId
            groups[id] = info
            messaging?.objectWillChange.send()
        }
        return ok
    }

    // MARK: - Участники / управление

    func members(_ groupId: String) async -> [GroupMember] {
        guard let json = try? await core.groupMembers(groupId),
              let data = json.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [] }
        let arr = (obj["members"] as? [[String: Any]]) ?? (obj["users"] as? [[String: Any]]) ?? []
        let result = arr.compactMap { m -> GroupMember? in
            guard let uid = (m["user_id"] as? String) ?? (m["id"] as? String) else { return nil }
            return GroupMember(userId: uid.lowercased(),
                               role: (m["role"] as? String) ?? "member",
                               displayName: (m["display_name"] as? String) ?? (m["username"] as? String) ?? uid,
                               username: m["username"] as? String,
                               lastActive: m["last_active"] as? String)
        }
        // /groups/me не отдаёт member_count — досчитываем и кэшируем при первой загрузке
        // участников, чтобы шапка чата/профиль показывали реальное число, а не 0.
        let id = groupId.lowercased()
        if !result.isEmpty, groups[id]?.memberCount != result.count {
            groups[id]?.memberCount = result.count
        }
        return result
    }

    func addMember(groupId: String, userId: String) async throws {
        guard let key = await core.groupKey(groupId.lowercased()) else { throw CoreError.BadInput(msg: "Нет ключа группы") }
        guard let (pub, _) = try? await core.publicKey(for: userId) else { throw CoreError.BadInput(msg: "Пользователь не найден") }
        let wrapped = try await core.wrapGroupKeyFor(groupKey: key, recipientPub: pub)
        try await core.addGroupMember(groupId: groupId, userId: userId, wrappedKey: wrapped, role: "member")
    }

    func removeMember(groupId: String, userId: String) async {
        try? await core.removeGroupMember(groupId: groupId, userId: userId)
        // RB-1: пока ключ не сменён, у удалённого остаётся рабочая копия, и
        // группа не пишет вовсе — сервер отвергает отправку. Меняем сразу же,
        // а не ждём следующей загрузки списка групп.
        await rotateKey(groupId: groupId)
    }

    func rename(groupId: String, name: String, description: String?) async {
        try? await core.updateGroup(groupId: groupId, name: name, description: description)
        if var g = groups[groupId.lowercased()] { g.name = name; if let d = description { g.description = d }; groups[groupId.lowercased()] = g }
        await core.touchChat(peer: groupId.lowercased(), isGroup: true, title: name, lastText: "", lastTs: 0, incUnread: false)
        await messaging?.refreshChats()
    }

    func leave(groupId: String) async {
        try? await core.leaveGroup(groupId.lowercased())
        groups[groupId.lowercased()] = nil
        await core.deleteChatData(groupId.lowercased())
        await messaging?.refreshChats()
    }

    func remove(groupId: String) async {
        try? await core.deleteGroup(groupId.lowercased())
        groups[groupId.lowercased()] = nil
        await core.deleteChatData(groupId.lowercased())
        await messaging?.refreshChats()
    }

    /// RB-1: сменить групповой ключ и раздать его всем оставшимся участникам.
    ///
    /// Вызывается, когда сервер сообщил `rekey_required`, и только у админа —
    /// остальным сервер ответит 403. Ключ считает клиент: сервер группового
    /// ключа не знает (кроме публичных) и знать не должен.
    ///
    /// Идёт напрямую через URLSession, а не через FFI: добавлять ручку в
    /// Rust-ядро значило бы пересобирать .xcframework и .so, а тело запроса
    /// здесь — обычный JSON.
    func rotateKey(groupId: String) async {
        let id = groupId.lowercased()
        guard let session, let base = session.activeServer?.apiURL,
              !session.authToken.isEmpty else { return }

        // Состав берём с сервера: локальный кэш мог не знать про удаление,
        // ради которого ротация и затеяна.
        let roster = await members(id)
        guard !roster.isEmpty else { return }

        let newKey = await core.newGroupKey()
        var shares: [[String: String]] = []
        for m in roster {
            guard let (pub, changed) = try? await core.publicKey(for: m.userId) else {
                // Хотя бы один участник без ключа — ротация не проводится.
                // Сервер всё равно отвергнет неполное покрытие, а раздать
                // новый ключ части группы значит расколоть её молча.
                return
            }
            // Ключ участника сменился с прошлого раза. При обычной отправке это
            // повод показать тревогу, здесь — повод остановиться: завернуть
            // новый групповой ключ на неподтверждённый ключ значит отдать его
            // тому, кто эту смену и устроил. Ротация подождёт разбора TOFU.
            if changed { return }
            guard let wrapped = try? await core.wrapGroupKeyFor(groupKey: newKey, recipientPub: pub) else {
                return
            }
            shares.append(["user_id": m.userId, "encrypted_key_b64": wrapped])
        }

        var body: [String: Any] = ["epoch": (await core.groupEpoch(id)) + 1, "keys": shares]
        // Публичная группа/канал: сервер сам заворачивает ключ вступающим,
        // поэтому ему нужна новая копия — иначе новичок получит мёртвый ключ.
        if groups[id]?.publicJoin == true { body["join_key_b64"] = newKey }

        guard let url = URL(string: base + "/groups/" + id + "/key"),
              let payload = try? JSONSerialization.data(withJSONObject: body) else { return }
        var req = URLRequest(url: url)
        req.httpMethod = "PUT"
        req.timeoutInterval = 20
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("Bearer \(session.authToken)", forHTTPHeaderField: "Authorization")
        req.httpBody = payload

        guard let (data, resp) = try? await URLSession.shared.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200 else {
            // 409 — состав или эпоха изменились, пока мы считали. Не затираем
            // чужую ротацию: попробуем на следующей загрузке групп.
            return
        }
        let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        let nextEpoch = (await core.groupEpoch(id)) + 1
        let epoch = intOf(obj?["epoch"]) ?? nextEpoch
        await core.setGroupKey(id, newKey)
        await core.setGroupEpoch(id, epoch)
    }

    private func intOf(_ v: Any?) -> Int? {
        if let n = v as? NSNumber { return n.intValue }
        if let s = v as? String { return Int(s) }
        if let i = v as? Int { return i }
        return nil
    }

    private func boolOf(_ v: Any?) -> Bool {
        if let b = v as? Bool { return b }
        if let n = v as? NSNumber { return n.boolValue }
        if let s = v as? String { return s == "1" || s.lowercased() == "true" }
        return false
    }

    private func core_randomId() -> String {
        let chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return String((0..<12).map { _ in chars.randomElement()! })
    }
}
