import SwiftUI

/// Список чатов. Заголовок и поиск идут в потоке скролла (большой тайтл как в
/// Telegram), снизу — морфящаяся стеклянная кнопка «написать». Демо-строки задают
/// визуальный язык; дальше подключим `core.fetchInbox` + хранилище ядра.
struct ChatListView: View {
    @Environment(Session.self) private var session
    @State private var query = ""
    @State private var openChat: DemoChat?

    private let demo: [DemoChat] = [
        .init(name: "Избранное", last: "Заметки, которые видите только вы", time: "09:24", unread: 0),
        .init(name: "Aether Team", last: "Стекло на iOS встало 🔥", time: "08:10", unread: 3),
        .init(name: "Roman", last: "проверим доставку начисто", time: "Вчера", unread: 0),
    ]

    private var filtered: [DemoChat] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        return q.isEmpty ? demo : demo.filter { $0.name.lowercased().contains(q) }
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                HStack(alignment: .firstTextBaseline) {
                    Text("Чаты")
                        .font(.system(size: 30, weight: .bold))
                        .foregroundStyle(Brand.textPrimary)
                    Spacer()
                    Text("E2E")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(Brand.textMuted)
                        .padding(.horizontal, 10).padding(.vertical, 5)
                        .liquidGlass(in: Capsule())
                }
                .padding(.top, 6)

                searchField

                ForEach(filtered) { chat in
                    Button { openChat = chat } label: { ChatRow(chat: chat) }
                        .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 110)
        }
        .overlay(alignment: .bottomTrailing) {
            ComposeFab(
                onNewChat: { openChat = DemoChat(name: "Новый чат", last: "", time: "сейчас", unread: 0) },
                onNewGroup: {}
            )
            .padding(.trailing, 20)
            .padding(.bottom, 96)
        }
        .fullScreenCover(item: $openChat) { chat in
            ChatView(title: chat.name)
        }
    }

    private var searchField: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass").foregroundStyle(Brand.textMuted)
            TextField("Поиск", text: $query)
                .foregroundStyle(Brand.textPrimary)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
        .liquidGlass(in: Capsule())
    }
}

struct DemoChat: Identifiable {
    let id = UUID()
    let name: String
    let last: String
    let time: String
    let unread: Int
}

private struct ChatRow: View {
    let chat: DemoChat

    var body: some View {
        HStack(spacing: 12) {
            Avatar(name: chat.name, size: 52)
            VStack(alignment: .leading, spacing: 3) {
                Text(chat.name)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Brand.textPrimary)
                Text(chat.last.isEmpty ? "Нет сообщений" : chat.last)
                    .font(.system(size: 14))
                    .foregroundStyle(Brand.textSecondary)
                    .lineLimit(1)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 6) {
                Text(chat.time)
                    .font(.system(size: 12))
                    .foregroundStyle(Brand.textMuted)
                if chat.unread > 0 {
                    Text("\(chat.unread)")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(.black)
                        .frame(minWidth: 20, minHeight: 20)
                        .background(Circle().fill(.white))
                }
            }
        }
        .padding(12)
        .glassCard(cornerRadius: 20)
    }
}
