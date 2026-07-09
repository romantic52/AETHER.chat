import SwiftUI
import UIKit

// Домашний экран: контент + нижний таб-бар в стиле Telegram (5 вкладок).
struct HomeView: View {
    @EnvironmentObject var session: Session
    @Environment(\.palette) private var palette
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var messaging: Messaging
    @StateObject private var chrome = ChromeState()
    @State private var tab: Tab = .chats

    enum Tab: Hashable { case contacts, calls, chats, settings }

    init() {
        _messaging = StateObject(wrappedValue: Messaging())
    }

    var body: some View {
        ZStack {
            palette.background.ignoresSafeArea()

            Group {
                switch tab {
                case .contacts: ContactsView()
                case .calls: CallsView()
                case .chats: ChatsListView()
                case .settings: SettingsView()
                }
            }
            .environmentObject(messaging)
            .environmentObject(chrome)
        }
        .ignoresSafeArea(edges: .bottom)
        .overlay(alignment: .bottom) {
            ZStack(alignment: .bottom) {
                EdgeDim(edge: .bottom, boost: 1.6)
                    .frame(height: 120)
                    .ignoresSafeArea(edges: .bottom)

                TabBar(tab: $tab, unread: messaging.totalUnread)
            }
            .opacity(chrome.tabBarHidden ? 0 : 1)
            .offset(y: 34)
            .allowsHitTesting(!chrome.tabBarHidden)
            .zIndex(10)
        }
        .overlay { CallOverlay(calls: messaging.calls) }
        .onAppear {
            messaging.rebind(session: session)
            AppRefresh.shared.poll = { [weak messaging] in await messaging?.pollInbox() }
            Task { await MediaStore.shared.bind(core: session.core) }
            if scenePhase == .active { messaging.start() }
            #if DEBUG
            switch ProcessInfo.processInfo.environment["AETHER_TAB"] {
            case "settings": tab = .settings
            case "contacts": tab = .contacts
            case "calls": tab = .calls
            default: break
            }
            #endif
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { messaging.start() }
        }
        // Deep link из островка/уведомления: переключаемся на вкладку чатов,
        // сам чат откроет ChatsListView (подписан на то же уведомление).
        .onReceive(NotificationCenter.default.publisher(for: NotificationsManager.openChatNotification)) { _ in
            tab = .chats
        }
        .onDisappear { messaging.stop() }
    }

}

// Наблюдает за CallManager напрямую (вложенный ObservableObject не поднимает изменения
// до родителя) и показывает экран звонка полноэкранным оверлеем поверх всего.
struct CallOverlay: View {
    @ObservedObject var calls: CallManager
    var body: some View {
        if calls.state != .idle {
            CallView(call: calls)
                .transition(.opacity)
                .zIndex(100)
        }
    }
}

struct TabBar: View {
    @Binding var tab: HomeView.Tab
    var unread: Int64
    @Environment(\.palette) private var palette
    @EnvironmentObject var appearance: AppearanceSettings

    private let tabs: [HomeView.Tab] = [.contacts, .calls, .chats, .settings]
    private let barHeight: CGFloat = 58

    @State private var barWidth: CGFloat = 0
    @State private var isPressing = false
    @State private var livePosition: CGFloat?
    @State private var hapticTarget: Int?

    private var activePosition: CGFloat { livePosition ?? CGFloat(tabs.firstIndex(of: tab) ?? 0) }

    var body: some View {
        GlassGroup(spacing: 10) {
            ZStack(alignment: .leading) {
                GeometryReader { geo in
                    HStack(spacing: 0) {
                        ForEach(tabs, id: \.self) { itemTab in
                            item(itemTab).frame(maxWidth: .infinity)
                        }
                    }
                    .onAppear { barWidth = geo.size.width }
                    .onChange(of: geo.size.width) { _, w in barWidth = w }
                }
                .frame(height: barHeight)
                .clipShape(Capsule())
                .padding(.horizontal, 8)
                .padding(.vertical, 7)
                .liquidGlass(Capsule(), interactive: false)
                .compositingGroup()

                if barWidth > 0 {
                    let segment = barWidth / CGFloat(tabs.count)
                    Capsule()
                        .fill(palette.accent.opacity(isPressing ? 0.22 : 0.12))
                        .liquidGlass(Capsule(), interactive: true)
                        .frame(width: max(segment - 4, 0), height: barHeight - 4)
                        .offset(x: 8 + segment * activePosition + 2)
                        .allowsHitTesting(false)
                        .animation(.easeOut(duration: 0.18), value: isPressing)
                }
            }
        }
        // contentShape + gesture ДО внешних отступов: иначе жест ловит тапы
        // в прозрачной зоне вокруг капсулы (16pt по бокам и снизу).
        .contentShape(Rectangle())
        .gesture(dragGesture)
        .padding(.horizontal, 16)
        .padding(.bottom, 16)
    }

    private var dragGesture: some Gesture {
        DragGesture(minimumDistance: 0, coordinateSpace: .local)
            .onChanged { value in
                guard barWidth > 0 else { return }
                if !isPressing {
                    isPressing = true
                    UIImpactFeedbackGenerator(style: .soft).impactOccurred()
                }
                let segment = barWidth / CGFloat(tabs.count)
                // Жест теперь висит до внешних отступов: вычитаем только 8pt
                // внутреннего padding бара, чтобы x=0 совпадал с первой вкладкой.
                let adjustedX = value.location.x - 8
                let pos = min(CGFloat(tabs.count - 1), max(0, adjustedX / segment))
                livePosition = pos
                let target = tabIndex(at: pos)
                if tabs.indices.contains(target) {
                    if hapticTarget != target {
                        UISelectionFeedbackGenerator().selectionChanged()
                        hapticTarget = target
                    }
                    if !appearance.switchTabOnRelease, tabs[target] != tab {
                        tab = tabs[target]
                    }
                }
            }
            .onEnded { value in
                isPressing = false
                hapticTarget = nil
                let moved = abs(value.translation.width) + abs(value.translation.height)
                
                if appearance.switchTabOnRelease {
                    guard barWidth > 0 else { return }
                    let segment = barWidth / CGFloat(tabs.count)
                    let adjustedX = value.location.x - 8
                    let pos = min(CGFloat(tabs.count - 1), max(0, adjustedX / segment))
                    let target = moved < 10 ? Int(max(0, min(CGFloat(tabs.count - 1), adjustedX / segment))) : tabIndex(at: pos)
                    if tabs.indices.contains(target) { select(tabs[target]) }
                } else {
                    if moved < 10, barWidth > 0 {
                        let segment = barWidth / CGFloat(tabs.count)
                        let adjustedX = value.location.x - 8
                        let target = Int(max(0, min(CGFloat(tabs.count - 1), adjustedX / segment)))
                        if tabs.indices.contains(target) { select(tabs[target]) }
                    }
                }
                
                withAnimation(.spring(response: 0.3, dampingFraction: 0.86)) { livePosition = nil }
            }
    }

    private func tabIndex(at pos: CGFloat) -> Int {
        let base = Int(pos)
        let bumped = (pos - CGFloat(base)) >= 0.6 ? base + 1 : base
        return max(0, min(tabs.count - 1, bumped))
    }

    private func item(_ t: HomeView.Tab) -> some View {
        let selected = tab == t
        return VStack(spacing: 3) {
            ZStack(alignment: .topTrailing) {
                Image(systemName: icon(for: t))
                    .font(.system(size: 22, weight: selected ? .semibold : .regular))
                    .frame(height: 28)
                    .scaleEffect(selected ? 1.1 : 1.0)
                    .animation(.spring(response: 0.3, dampingFraction: 0.5), value: selected)
                if t == .chats && unread > 0 {
                    Text(unread > 99 ? "99+" : "\(unread)")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 5).padding(.vertical, 1)
                        .background(palette.danger, in: Capsule())
                        .offset(x: 14, y: -7)
                        .fixedSize()
                }
            }
            Text(title(for: t)).font(.system(size: 11, weight: selected ? .semibold : .regular))
        }
        .foregroundStyle(selected ? palette.accent : palette.textSecondary)
        .frame(maxWidth: .infinity)
        .frame(height: 58)
        .contentShape(Rectangle())
    }

    private func select(_ value: HomeView.Tab) {
        guard tab != value else { return }
        UISelectionFeedbackGenerator().selectionChanged()
        withAnimation(.snappy(duration: 0.2, extraBounce: 0.02)) {
            tab = value
            livePosition = nil
        }
    }

    private func icon(for tab: HomeView.Tab) -> String {
        switch tab {
        case .contacts: return "person.crop.circle"
        case .calls: return "phone.fill"
        case .chats: return "bubble.left.and.bubble.right.fill"
        case .settings: return "gearshape.fill"
        }
    }

    private func title(for tab: HomeView.Tab) -> String {
        switch tab {
        case .contacts: return "Контакты"
        case .calls: return "Звонки"
        case .chats: return "Чаты"
        case .settings: return "Настройки"
        }
    }
}

struct CallsView: View {
    @EnvironmentObject private var messaging: Messaging
    var body: some View { CallsContent(call: messaging.calls, connected: messaging.realtimeConnected) }
}

private struct CallsContent: View {
    @ObservedObject var call: CallManager
    let connected: Bool
    @Environment(\.palette) private var palette

    var body: some View {
        NavigationStack {
            ZStack {
                palette.background.ignoresSafeArea()
                if call.history.isEmpty {
                    VStack(spacing: 14) {
                        Image(systemName: "phone.circle")
                            .font(.system(size: 56, weight: .thin))
                            .foregroundStyle(palette.textSecondary)
                        Text("Звонков пока нет")
                            .font(.title3.weight(.semibold)).foregroundStyle(palette.textPrimary)
                        Text("Начни аудио- или видеозвонок из любого личного чата")
                            .font(.subheadline).foregroundStyle(palette.textSecondary)
                            .multilineTextAlignment(.center)
                    }
                    .padding(40)
                } else {
                    List(call.history) { record in
                        callRow(record)
                            .listRowBackground(palette.background)
                            .listRowSeparatorTint(palette.divider)
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                    .safeAreaPadding(.bottom, 110)
                }
            }
            .toolbar(.hidden, for: .navigationBar)
            .safeAreaInset(edge: .top) {
                FloatingHeader(title: "Звонки", trailing: AnyView(
                    Label(connected ? "Связь установлена" : "Переподключение…",
                          systemImage: connected ? "checkmark.circle.fill" : "arrow.triangle.2.circlepath")
                        .labelStyle(.iconOnly)
                        .foregroundStyle(connected ? .green : palette.textSecondary)
                        .accessibilityLabel(connected ? "Сервер звонков подключён" : "Сервер звонков переподключается")
                ))
            }
        }
    }

    private func callRow(_ record: CallManager.Record) -> some View {
        HStack(spacing: 12) {
            Avatar(id: record.peerId, name: record.peerId, size: 50)
            VStack(alignment: .leading, spacing: 4) {
                Text(record.peerId)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(record.result == .missed ? palette.danger : palette.textPrimary)
                Label(detail(record), systemImage: directionIcon(record))
                    .font(.caption)
                    .foregroundStyle(record.result == .missed ? palette.danger : palette.textSecondary)
            }
            Spacer()
            Text(record.startedAt, style: .relative)
                .font(.system(size: 13))
                .foregroundStyle(palette.textSecondary)
            Button {
                call.startCall(peer: record.peerId, video: record.isVideo)
            } label: {
                Image(systemName: record.isVideo ? "video.fill" : "phone.fill")
                    .frame(width: 34, height: 34)
            }
            .buttonStyle(.plain)
            .foregroundStyle(palette.accent)
            .accessibilityLabel(record.isVideo ? "Позвонить по видео" : "Позвонить")
        }
        .frame(minHeight: 66)
    }

    private func directionIcon(_ record: CallManager.Record) -> String {
        record.direction == .incoming ? "arrow.down.left" : "arrow.up.right"
    }

    private func detail(_ record: CallManager.Record) -> String {
        switch record.result {
        case .completed:
            if record.duration >= 1 {
                return String(format: "%@ · %02d:%02d",
                              record.direction == .incoming ? "Входящий" : "Исходящий",
                              Int(record.duration) / 60, Int(record.duration) % 60)
            }
            return record.direction == .incoming ? "Входящий" : "Исходящий"
        case .missed: return "Пропущенный"
        case .declined: return "Отклонён"
        case .cancelled: return "Отменён"
        case .busy: return "Занято"
        case .failed: return "Ошибка соединения"
        }
    }
}
