import SwiftUI

/// Основной интерфейс после входа. Пока — список чатов в навигации с
/// нижней стеклянной панелью. Дальше сюда лягут звонки/настройки/профиль.
struct HomeView: View {
    @Environment(Session.self) private var session
    @State private var tab: Tab = .chats
    @Namespace private var tabNS

    enum Tab: String, CaseIterable {
        case chats, calls, settings
        var title: String { self == .chats ? "Чаты" : self == .calls ? "Звонки" : "Настройки" }
        var icon: String { self == .chats ? "bubble.left.and.bubble.right" : self == .calls ? "phone" : "gearshape" }
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            Group {
                switch tab {
                case .chats: ChatListView()
                case .calls: PlaceholderScreen(title: "Звонки", icon: "phone", note: "Будет переиспользовать WebRTC-слой ядра.")
                case .settings: SettingsView()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            glassTabBar
        }
    }

    private var glassTabBar: some View {
        GlassGroup(spacing: 8) {
            HStack(spacing: 4) {
                ForEach(Tab.allCases, id: \.self) { t in
                    Button {
                        withAnimation(.spring(response: 0.4, dampingFraction: 0.75)) { tab = t }
                    } label: {
                        VStack(spacing: 3) {
                            Image(systemName: t.icon)
                                .font(.system(size: 19, weight: .medium))
                                .symbolEffect(.bounce, value: tab == t)
                            Text(t.title).font(.system(size: 10, weight: .medium))
                        }
                        .foregroundStyle(tab == t ? Brand.textPrimary : Brand.textMuted)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 9)
                        .background {
                            if tab == t {
                                Capsule()
                                    .fill(Color.white.opacity(0.14))
                                    .matchedGeometryEffect(id: "tabsel", in: tabNS)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .liquidGlass(in: Capsule())
        }
        .padding(.horizontal, 40)
        .padding(.bottom, 6)
    }
}

/// Универсальная заглушка для ещё не реализованных вкладок.
struct PlaceholderScreen: View {
    var title: String
    var icon: String
    var note: String

    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 40, weight: .light))
                .foregroundStyle(Brand.textMuted)
            Text(title).font(.system(size: 20, weight: .semibold)).foregroundStyle(Brand.textPrimary)
            Text(note)
                .font(.system(size: 13))
                .foregroundStyle(Brand.textMuted)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
