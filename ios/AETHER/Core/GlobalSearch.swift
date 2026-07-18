import Foundation

// Глобальный поиск с сервера: люди (по имени и @username) + группы/каналы
// (по имени). Дёргает /users/search напрямую (Bearer из Keychain) — ядро
// не трогаем, формат ответа простой и стабильный.
struct FoundUser: Identifiable, Equatable {
    let userId: String
    let username: String
    let displayName: String
    let avatarFileId: String
    var id: String { userId }

    var title: String { displayName.isEmpty ? (username.isEmpty ? userId : username) : displayName }
    var subtitle: String { username.isEmpty ? "@\(userId)" : "@\(username)" }
}

struct FoundGroup: Identifiable, Equatable {
    let groupId: String
    let name: String
    let description: String
    let isChannel: Bool
    /// Публичность: вступить может любой (сервер выдаст ключ).
    let publicJoin: Bool
    /// @имя публичной группы/канала.
    let username: String
    /// Аватар (file_id) — публичный.
    let avatarFileId: String
    var id: String { groupId }
}

// Публичные каналы: подписка и управление видимостью (владелец).
enum ChannelDirectory {
    /// Подписаться на публичный канал — сервер добавит участником и завернёт
    /// ключ канала персональным конвертом (подхватится в GroupsManager.load()).
    static func join(_ groupId: String) async -> Bool {
        await request("groups/\(groupId)/join", method: "POST", body: nil)
    }

    /// Владелец: публичность (@username обязателен) или закрытие. Возвращает
    /// nil при успехе, иначе — человекочитаемую ошибку сервера (имя занято, лимит 25).
    static func setPublic(_ groupId: String, isPublic: Bool, joinKeyB64: String?, username: String?) async -> String? {
        var body: [String: Any] = ["public": isPublic]
        if let joinKeyB64 { body["join_key_b64"] = joinKeyB64 }
        if let username { body["username"] = username }
        return await requestDetailed("groups/\(groupId)/public", method: "PUT", body: body)
    }

    /// Просмотры постов канала: отмечает просмотренными и возвращает счётчики.
    static func markViews(_ messageIds: [String]) async -> [String: Int] {
        guard !messageIds.isEmpty,
              let bearer = Keychain.string(for: Keychain.kToken), !bearer.isEmpty,
              let url = URL(string: "\(CoreClient.baseURL)/messages/views") else { return [:] }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        req.httpBody = try? JSONSerialization.data(withJSONObject: ["message_ids": messageIds])
        guard let (data, resp) = try? await URLSession.shared.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let views = obj["views"] as? [String: Int] else { return [:] }
        return views
    }

    /// Частичное обновление группы (name/description/avatar_file_id).
    static func updateGroup(_ groupId: String, fields: [String: Any]) async -> Bool {
        await request("groups/\(groupId)", method: "PUT", body: fields)
    }

    private static func request(_ path: String, method: String, body: [String: Any]?) async -> Bool {
        await requestDetailed(path, method: method, body: body) == nil
    }

    /// nil = успех; строка = ошибка (detail из FastAPI либо генерик).
    private static func requestDetailed(_ path: String, method: String, body: [String: Any]?) async -> String? {
        guard let bearer = Keychain.string(for: Keychain.kToken), !bearer.isEmpty,
              let url = URL(string: "\(CoreClient.baseURL)/\(path)") else { return "Нет сессии" }
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        if let body {
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try? JSONSerialization.data(withJSONObject: body)
        }
        guard let (data, resp) = try? await URLSession.shared.data(for: req) else { return "Нет соединения" }
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        if code == 200 { return nil }
        if let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let detail = obj["detail"] as? String { return detail }
        return "Ошибка сервера (\(code))"
    }
}

// Эмодзи-статусы профилей (ядро про них не знает — ходим напрямую).
enum ProfileHTTP {
    static func statusEmoji(_ userId: String) async -> String? {
        guard let bearer = Keychain.string(for: Keychain.kToken), !bearer.isEmpty,
              let url = URL(string: "\(CoreClient.baseURL)/users/\(userId)/profile") else { return nil }
        var req = URLRequest(url: url)
        req.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        guard let (data, resp) = try? await URLSession.shared.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        return obj["status_emoji"] as? String ?? ""
    }

    /// Пустая строка — снять статус.
    @discardableResult
    static func setStatusEmoji(_ emoji: String) async -> Bool {
        guard let bearer = Keychain.string(for: Keychain.kToken), !bearer.isEmpty,
              let url = URL(string: "\(CoreClient.baseURL)/users/me/profile") else { return false }
        var req = URLRequest(url: url)
        req.httpMethod = "PUT"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        req.httpBody = try? JSONSerialization.data(withJSONObject: ["status_emoji": emoji])
        guard let (_, resp) = try? await URLSession.shared.data(for: req) else { return false }
        return (resp as? HTTPURLResponse)?.statusCode == 200
    }
}

enum GlobalSearch {
    struct Results: Equatable {
        var users: [FoundUser] = []
        var groups: [FoundGroup] = []
        var isEmpty: Bool { users.isEmpty && groups.isEmpty }
    }

    static func search(_ query: String) async -> Results {
        guard let bearer = Keychain.string(for: Keychain.kToken), !bearer.isEmpty,
              let encoded = query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed),
              let url = URL(string: "\(CoreClient.baseURL)/users/search?q=\(encoded)") else { return Results() }
        var req = URLRequest(url: url)
        req.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        guard let (data, resp) = try? await URLSession.shared.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return Results() }

        var out = Results()
        for u in obj["users"] as? [[String: Any]] ?? [] {
            guard let id = u["user_id"] as? String else { continue }
            out.users.append(FoundUser(
                userId: id.lowercased(),
                username: (u["username"] as? String) ?? "",
                displayName: (u["display_name"] as? String) ?? "",
                avatarFileId: (u["avatar_file_id"] as? String) ?? ""
            ))
        }
        for g in obj["groups"] as? [[String: Any]] ?? [] {
            guard let id = g["id"] as? String, let name = g["name"] as? String else { continue }
            out.groups.append(FoundGroup(
                groupId: id.lowercased(),
                name: name,
                description: (g["description"] as? String) ?? "",
                isChannel: (g["is_channel"] as? Bool) ?? false,
                publicJoin: (g["public_join"] as? Bool) ?? false,
                username: (g["username"] as? String) ?? "",
                avatarFileId: (g["avatar_file_id"] as? String) ?? ""
            ))
        }
        return out
    }
}
