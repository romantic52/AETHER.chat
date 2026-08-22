import SwiftUI

// Аватар: загруженное изображение (по avatar_file_id) или цветной кружок с инициалами.
// Цвет детерминирован по id — как в Android/Telegram.
struct Avatar: View {
    let id: String
    var name: String = ""
    var size: CGFloat = 44
    var avatarURL: URL? = nil
    var online: Bool = false

    @Environment(\.palette) private var palette
    // Локальная подмена фото (см. AvatarStore) — приоритетнее реального avatarURL:
    // это осознанный локальный выбор пользователя ("моё фото для этого контакта").
    @ObservedObject private var avatarStore = AvatarStore.shared
    /// Картинка держится в самой строке: обновляется ОДНА строка, а не весь
    /// список, как было при общем счётчике перерисовок.
    @State private var remote: UIImage?

    private var initials: String {
        let base = name.isEmpty ? id : name
        let parts = base.split(separator: " ")
        if parts.count >= 2 {
            return String(parts[0].prefix(1) + parts[1].prefix(1)).uppercased()
        }
        return String(base.prefix(2)).uppercased()
    }

    private var gradient: LinearGradient {
        let palettes: [[UInt32]] = [
            [0x5B8CFF, 0x2F5BD6], [0xE86A9A, 0xC4437A], [0x4ADE80, 0x22A55A],
            [0xF5A623, 0xD98016], [0xC4A7E7, 0x9B7BD4], [0x30C5D2, 0x1E96A1],
        ]
        var h = 5381
        for b in id.utf8 { h = ((h << 5) &+ h) &+ Int(b) }
        let pair = palettes[abs(h) % palettes.count]
        return LinearGradient(colors: [Palette.rgb(pair[0]), Palette.rgb(pair[1])],
                              startPoint: .topLeading, endPoint: .bottomTrailing)
    }

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            Group {
                if let overrideImage = avatarStore.image(for: id) {
                    Image(uiImage: overrideImage).resizable().scaledToFill()
                } else if let remote {
                    // Свой кеш (память+диск) вместо AsyncImage: сервер без
                    // cache-заголовков, и AsyncImage качал одно и то же каждый показ.
                    Image(uiImage: remote).resizable().scaledToFill()
                } else {
                    placeholder
                }
            }
            .frame(width: size, height: size)
            .clipShape(Circle())

            if online {
                Circle()
                    .fill(palette.readTick)
                    .frame(width: size * 0.26, height: size * 0.26)
                    .overlay(Circle().stroke(palette.background, lineWidth: size * 0.05))
            }
        }
        // Память — сразу и синхронно, чтобы при прокрутке ничего не мигало.
        // Диск и сеть — фоном, и только для этой строки.
        .task(id: avatarURL) {
            guard let url = avatarURL else { remote = nil; return }
            if let cached = avatarStore.cachedRemote(for: url) { remote = cached; return }
            remote = await avatarStore.remoteImageAsync(for: url)
        }
    }

    private var placeholder: some View {
        ZStack {
            gradient
            Text(initials)
                .font(.system(size: size * 0.38, weight: .semibold))
                .foregroundStyle(.white)
        }
    }
}


