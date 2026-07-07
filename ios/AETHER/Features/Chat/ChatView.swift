import SwiftUI

/// Оболочка диалога: стеклянная шапка, пузыри сообщений, стеклянная строка
/// ввода. Демо-сообщения — каркас под реальную крипту (`core.boxEncrypt`/
/// `boxDecrypt`) и доставку через relay.
struct ChatView: View {
    @Environment(\.dismiss) private var dismiss
    var title: String

    @State private var draft = ""
    @State private var messages: [DemoMessage] = [
        .init(text: "Стартовое окно на iOS готово.", mine: false),
        .init(text: "И стекло настоящее, iOS 26 ✨", mine: true),
        .init(text: "Шифрование — то же ядро, что на Android.", mine: false),
    ]

    var body: some View {
        ZStack {
            Brand.backdrop.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 8) {
                        ForEach(messages) { m in
                            MessageBubble(message: m)
                        }
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                }

                inputBar
            }
        }
    }

    private var header: some View {
        HStack(spacing: 12) {
            Button { dismiss() } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Brand.textPrimary)
                    .frame(width: 40, height: 40)
            }
            Avatar(name: title, size: 38)
            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(.system(size: 16, weight: .semibold)).foregroundStyle(Brand.textPrimary)
                Text("в сети").font(.system(size: 12)).foregroundStyle(Brand.textMuted)
            }
            Spacer()
            Button {} label: {
                Image(systemName: "phone").font(.system(size: 17, weight: .medium)).foregroundStyle(Brand.textPrimary)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(.ultraThinMaterial)
        .overlay(Rectangle().fill(Brand.border).frame(height: 0.5), alignment: .bottom)
    }

    private var inputBar: some View {
        HStack(spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "paperclip").foregroundStyle(Brand.textMuted)
                TextField("Сообщение", text: $draft, axis: .vertical)
                    .foregroundStyle(Brand.textPrimary)
                    .lineLimit(1...4)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 11)
            .liquidGlass(in: RoundedRectangle(cornerRadius: 22, style: .continuous))

            Button { send() } label: {
                Image(systemName: draft.isEmpty ? "mic" : "arrow.up")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(.black)
                    .frame(width: 46, height: 46)
                    .background(Circle().fill(.white))
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .animation(.spring(duration: 0.2), value: draft.isEmpty)
    }

    private func send() {
        let t = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        messages.append(.init(text: t, mine: true))
        draft = ""
    }
}

struct DemoMessage: Identifiable {
    let id = UUID()
    let text: String
    let mine: Bool
}

private struct MessageBubble: View {
    let message: DemoMessage

    var body: some View {
        HStack {
            if message.mine { Spacer(minLength: 50) }
            Text(message.text)
                .font(.system(size: 15))
                .foregroundStyle(Brand.textPrimary)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .liquidGlass(
                    in: RoundedRectangle(cornerRadius: 20, style: .continuous),
                    tinted: message.mine
                )
            if !message.mine { Spacer(minLength: 50) }
        }
    }
}

#Preview {
    ChatView(title: "Aether Team")
}
