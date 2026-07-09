import SwiftUI

// Локальное (только на этом устройстве) фото поверх чужого профиля — как «своё фото
// для контакта» в Telegram. Реальный avatar_file_id собеседника мы менять не можем
// (PUT /users/me/profile меняет только СВОЙ профиль), поэтому это чисто клиентская
// подмена отображения, без похода на сервер.
@MainActor
final class AvatarStore: ObservableObject {
    static let shared = AvatarStore()

    // Инкрементируется при любом set/remove — Avatar подписан на это, чтобы
    // перерисоваться сразу во всех местах, где он уже показан на экране.
    @Published private(set) var revision = 0

    private var cache: [String: UIImage] = [:]
    private init() {}

    private var directory: URL {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("AvatarOverrides", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private func path(for id: String) -> URL {
        directory.appendingPathComponent(id.lowercased() + ".jpg")
    }

    func image(for id: String) -> UIImage? {
        let key = id.lowercased()
        if let cached = cache[key] { return cached }
        guard let data = try? Data(contentsOf: path(for: key)), let img = UIImage(data: data) else { return nil }
        cache[key] = img
        return img
    }

    func hasOverride(for id: String) -> Bool { image(for: id) != nil }

    func setOverride(_ image: UIImage, for id: String) {
        let key = id.lowercased()
        guard let jpeg = image.jpegData(compressionQuality: 0.85) else { return }
        try? jpeg.write(to: path(for: key))
        cache[key] = image
        revision += 1
    }

    func removeOverride(for id: String) {
        let key = id.lowercased()
        try? FileManager.default.removeItem(at: path(for: key))
        cache.removeValue(forKey: key)
        revision += 1
    }

    // MARK: - Кеш скачанных аватарок (по URL)
    // AsyncImage каждый показ ходил в сеть (сервер без cache-заголовков) —
    // держим скачанные аватарки на диске и в памяти сами.

    private var remoteCache: [String: UIImage] = [:]
    private var remoteInflight: Set<String> = []

    private var remoteDirectory: URL {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("avatars", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private func remotePath(_ url: URL) -> URL {
        remoteDirectory.appendingPathComponent(url.lastPathComponent + ".img")
    }

    /// Кешированная аватарка по URL: память → диск → nil (и фоновая загрузка).
    func remoteImage(for url: URL) -> UIImage? {
        let key = url.absoluteString
        if let cached = remoteCache[key] { return cached }
        if let data = try? Data(contentsOf: remotePath(url)), let img = UIImage(data: data) {
            remoteCache[key] = img
            return img
        }
        // Нет на диске — качаем один раз в фоне, по готовности дёргаем revision.
        guard !remoteInflight.contains(key) else { return nil }
        remoteInflight.insert(key)
        Task {
            defer { remoteInflight.remove(key) }
            guard let (data, _) = try? await URLSession.shared.data(from: url),
                  let img = UIImage(data: data) else { return }
            try? data.write(to: remotePath(url), options: .atomic)
            remoteCache[key] = img
            revision += 1
        }
        return nil
    }

    /// Размер дискового кеша аватарок в байтах.
    func remoteCacheSizeBytes() -> Int64 {
        let files = (try? FileManager.default.contentsOfDirectory(
            at: remoteDirectory, includingPropertiesForKeys: [.fileSizeKey])) ?? []
        return files.reduce(Int64(0)) { sum, url in
            sum + Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
    }

    /// Очистка кеша аватарок (локальные подмены контактов не трогаем).
    func clearRemoteCache() {
        remoteCache.removeAll()
        let files = (try? FileManager.default.contentsOfDirectory(
            at: remoteDirectory, includingPropertiesForKeys: nil)) ?? []
        for url in files { try? FileManager.default.removeItem(at: url) }
        revision += 1
    }
}
