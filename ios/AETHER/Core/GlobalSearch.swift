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
    var id: String { groupId }
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
                isChannel: (g["is_channel"] as? Bool) ?? false
            ))
        }
        return out
    }
}
