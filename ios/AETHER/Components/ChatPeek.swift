import SwiftUI

/// Предпросмотр чата по зажатию строки — как в Telegram: видно последние
/// сообщения, но чат НЕ открывается и прочитанным не становится.
///
/// Читаем прямо из локального хранилища и не трогаем активный чат: отметка о
/// прочтении ставится только при настоящем открытии переписки.
struct ChatPeek: View {
    let peerId: String
    let title: String
    let myId: String
    let core: CoreClient

    @Environment(\.palette) private var palette
    @State private var messages: [StoredMessage] = []
    @State private var loaded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                Avatar(id: peerId, name: title, size: 34)
                Text(title)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(palette.textPrimary)
                    .lineLimit(1)
            }

            Rectangle().fill(palette.divider).frame(height: 0.5)

            if messages.isEmpty {
                Text(loaded ? "Нет сообщений" : "…")
                    .font(.system(size: 14))
                    .foregroundStyle(palette.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.vertical, 18)
            } else {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(Array(messages.enumerated()), id: \.offset) { _, m in
                        bubble(m)
                    }
                }
            }
        }
        .padding(14)
        .frame(width: 320)
        .background(palette.background)
        .task {
            // Заведомо будущая отметка времени — забираем самые свежие.
            let now = Int64(Date().timeIntervalSince1970 * 1000) + 86_400_000
            let got = (try? await core.messages(peer: peerId, beforeTs: now, limit: 8)) ?? []
            messages = got.sorted { $0.ts < $1.ts }
            loaded = true
        }
    }

    private func bubble(_ m: StoredMessage) -> some View {
        let mine = m.senderId.lowercased() == myId.lowercased()
        return HStack {
            if mine { Spacer(minLength: 40) }
            Text(Wire.preview(m.payloadJson))
                .font(.system(size: 14))
                .foregroundStyle(palette.textPrimary)
                .lineLimit(3)
                .padding(.horizontal, 10)
                .padding(.vertical, 7)
                .background(mine ? palette.accentSoft : palette.surface,
                            in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            if !mine { Spacer(minLength: 40) }
        }
    }
}
