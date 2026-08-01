import SwiftUI
import UIKit

// Домашний экран: контент + нижний таб-бар в стиле Telegram (5 вкладок).
struct HomeView: View {
    @EnvironmentObject var session: Session
    @EnvironmentObject var appearance: AppearanceSettings
    @Environment(\.palette) private var palette
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var messaging: Messaging
    @StateObject private var chrome = ChromeState()

    init() {
        _messaging = StateObject(wrappedValue: Messaging())
    }

    var body: some View {
        // Нативный TabView вместо самодельного бара — ровно как в PyLyn, где
        // стоит стоковый TabView с .tabItem и ни строчки кастома: именно поэтому
        // док там выглядит и ведёт себя системно, это и ЕСТЬ системный бар.
        //
        // Чем заплачено, сознательно:
        //  • пропал жест перетягивания пальцем по бару — в нативном баре его нет;
        //  • единый NavigationStack распался на четыре, по одному на вкладку
        //    (так же в PyLyn). Пуш чата теперь прячет бар средствами системы.
        //  • вкладки больше не живут одновременно: TabView создаёт их лениво и
        //    хранит состояние посещённых. Настройка «анимация смены вкладок»
        //    больше ни на что не влияет.
        // Взамен: настоящее Liquid Glass, схлопывание бара при скролле и
        // корректный морфинг подложки — всё то, что руками не воспроизводится.
        tabs
        .environmentObject(messaging)
        .environmentObject(chrome)
        .overlay { CallOverlay(calls: messaging.calls) }
        .overlay { GroupCallOverlay(calls: messaging.groupCalls) }
        .overlay(alignment: .top) {
            GroupCallInviteBanner(call: messaging.groupCalls)
                .animation(.spring(response: 0.35, dampingFraction: 0.85),
                           value: messaging.groupCalls.pendingInvite)
        }
        .onAppear {
            messaging.rebind(session: session)
            AppRefresh.shared.poll = { [weak messaging] in await messaging?.pollInbox() }
            Task { await MediaStore.shared.bind(core: session.core) }
            if scenePhase == .active { messaging.start() }
            #if DEBUG
            switch ProcessInfo.processInfo.environment["AETHER_TAB"] {
            case "settings": chrome.tab = .settings
            case "contacts": chrome.tab = .contacts
            case "calls": chrome.tab = .calls
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
            chrome.tab = .chats
        }
        .onDisappear { messaging.stop() }
    }

    // Схлопывание бара при скролле вниз — iOS 26+, на 17–25 просто нет.
    @ViewBuilder
    private var tabs: some View {
        if #available(iOS 26.0, *) {
            tabView.tabBarMinimizeBehavior(.onScrollDown)
        } else {
            tabView
        }
    }

    private var tabView: some View {
        TabView(selection: $chrome.tab) {
            NavigationStack { ContactsView() }
                .tabItem { Label("Контакты", systemImage: "person.crop.circle") }
                .tag(AppTab.contacts)

            NavigationStack { CallsView() }
                .tabItem { Label("Звонки", systemImage: "phone.fill") }
                .tag(AppTab.calls)

            NavigationStack { ChatsListView() }
                .tabItem { Label("Чаты", systemImage: "bubble.left.and.bubble.right.fill") }
                .badge(Int(messaging.totalUnread))
                .tag(AppTab.chats)

            NavigationStack { SettingsView() }
                .tabItem { Label("Настройки", systemImage: "gearshape.fill") }
                .tag(AppTab.settings)
        }
    }
}

// Групповой звонок поверх всего (наблюдаем менеджер напрямую, как CallOverlay).
struct GroupCallOverlay: View {
    @ObservedObject var calls: GroupCallManager
    var body: some View {
        if calls.isActive {
            GroupCallView(call: calls)
                .transition(.opacity)
                .zIndex(100)
        }
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
    @Binding var tab: AppTab
    var unread: Int64
    @Environment(\.palette) private var palette
    @EnvironmentObject var appearance: AppearanceSettings

    private let tabs: [AppTab] = [.contacts, .calls, .chats, .settings]
    // 48 + 7pt вертикального padding с каждой стороны = 62pt внешней высоты —
    // ровно как у системного дока (замерено на Photos, iPhone 17 Pro). Было 58,
    // то есть 72pt: бар выглядел на 16% толще нативного.
    private let barHeight: CGFloat = 48

    @EnvironmentObject var chrome: ChromeState
    @State private var barWidth: CGFloat = 0
    @State private var hapticTarget: Int?
    /// Общее пространство имён бара и индикатора: по нему GlassEffectContainer
    /// склеивает два стекла в одну сцену вместо двух независимых блюров.
    @Namespace private var glassNS

    // Транзиентный стейт жеста — в ChromeState: жест может начаться на баре
    // одной вкладки, а рисоваться уже на баре другой (вкладки живут вместе).
    private var isPressing: Bool { chrome.barPressing }
    private var livePosition: CGFloat? { chrome.barLivePosition }

    private var activePosition: CGFloat { livePosition ?? CGFloat(tabs.firstIndex(of: tab) ?? 0) }

    var body: some View {
        // spacing управляет тем, с какого расстояния контейнер сливает стёкла.
        // При 10 подложка растягивалась в движении на +38pt против +12pt у
        // системного TabView — «жвачка». 4 даёт деформацию близкую к нативной.
        GlassGroup(spacing: 4) {
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
                .liquidGlass(Capsule(), interactive: false, glassID: "bar", namespace: glassNS)
                // compositingGroup() убран намеренно: он схлопывал бар в отдельный
                // офскрин-слой и не давал контейнеру слить его с индикатором.
                // Если вернутся «призраки» при смене вкладок — вернуть его сюда.

                if barWidth > 0 {
                    let segment = barWidth / CGFloat(tabs.count)
                    // Единственный бар приложения: овал просто плавно едет,
                    // стекло живёт постоянно — без пересозданий при смене вкладок.
                    // Подложка нейтральная, а не акцентная — как в системном доке:
                    // там активную вкладку выделяет заметно более светлая капсула,
                    // а цветом играют уже иконка с подписью. Акцент при 12% давал
                    // бледную плёнку, по которой не читалось, что выбрано.
                    // textPrimary светлый на тёмных темах и тёмный на светлой,
                    // поэтому контраст уходит в нужную сторону в обеих.
                    Capsule()
                        .fill(palette.textPrimary.opacity(
                            appearance.glassEnabled ? (isPressing ? 0.24 : 0.16)
                                                    : (isPressing ? 0.28 : 0.20)))
                        .liquidGlass(Capsule(), interactive: true, surfaceWhenOff: false,
                                     glassID: "indicator", namespace: glassNS)
                        // Высота — почти во всю капсулу: внешняя высота бара это
                        // barHeight + 7pt padding сверху и снизу, из неё оставляем
                        // по 3pt поля, как в системном доке. Было barHeight-4 = 44pt
                        // при баре 62pt — подложка болталась с зазорами по 9pt.
                        // Ширина — во весь сегмент: у системного TabView подложка
                        // занимает 100% сегмента, а `segment - 4` давала 82% и
                        // читалась как обрубок.
                        .frame(width: max(segment, 0), height: barHeight + 14 - 6)
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
        // Системный док отстоит от краёв на 21pt со всех сторон (замерено).
        .padding(.horizontal, 21)
        .padding(.bottom, 21)
    }

    private var dragGesture: some Gesture {
        DragGesture(minimumDistance: 0, coordinateSpace: .local)
            .onChanged { value in
                guard barWidth > 0 else { return }
                if !chrome.barPressing {
                    chrome.barPressing = true
                    UIImpactFeedbackGenerator(style: .soft).impactOccurred()
                }
                let segment = barWidth / CGFloat(tabs.count)
                // Жест теперь висит до внешних отступов: вычитаем только 8pt
                // внутреннего padding бара, чтобы x=0 совпадал с первой вкладкой.
                let adjustedX = value.location.x - 8
                let pos = min(CGFloat(tabs.count - 1), max(0, adjustedX / segment))
                chrome.barLivePosition = pos
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
                chrome.barPressing = false
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
                
                withAnimation(.spring(response: 0.3, dampingFraction: 0.86)) { chrome.barLivePosition = nil }
            }
    }

    private func tabIndex(at pos: CGFloat) -> Int {
        let base = Int(pos)
        let bumped = (pos - CGFloat(base)) >= 0.6 ? base + 1 : base
        return max(0, min(tabs.count - 1, bumped))
    }

    private func item(_ t: AppTab) -> some View {
        let selected = tab == t
        return VStack(spacing: 3) {
            ZStack(alignment: .topTrailing) {
                Image(systemName: icon(for: t))
                    // 26 вместо 22: в системном доке иконка крупнее и плотнее
                    // сидит к подписи, у нас она терялась в пустоте.
                    .font(.system(size: 26, weight: selected ? .semibold : .regular))
                    .frame(height: 29)
                    .scaleEffect(selected ? 1.1 : 1.0)
                    .animation(.spring(response: 0.3, dampingFraction: 0.5), value: selected)
                if t == .chats && unread > 0 {
                    Text(unread > 99 ? "99+" : "\(unread)")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 5).padding(.vertical, 1)
                        .background(palette.danger, in: Capsule())
                        // Смещение уменьшено: при высоте бара 48pt прежние -7
                        // выносили бейдж за капсулу, и его срезал clipShape.
                        // Так он садится на угол иконки, как системный.
                        .offset(x: 11, y: -1)
                        .fixedSize()
                }
            }
            Text(title(for: t)).font(.system(size: 11, weight: selected ? .semibold : .regular))
        }
        // Неактивные вкладки — textPrimary, а не textSecondary. В системном доке
        // невыбранные иконки и подписи белые и читаются чётко; тусклый серо-синий
        // 0x9AA3B2 делал весь бар выцветшим.
        .foregroundStyle(selected ? palette.accent : palette.textPrimary)
        .frame(maxWidth: .infinity)
        .frame(height: barHeight)
        .contentShape(Rectangle())
    }

    private func select(_ value: AppTab) {
        guard tab != value else { return }
        UISelectionFeedbackGenerator().selectionChanged()
        // 0.25 вместо 0.2: по видео из симулятора переход занимал 117мс против
        // 142мс у системного TabView — читалось резче нативного.
        withAnimation(.snappy(duration: 0.25, extraBounce: 0.02)) {
            tab = value
            chrome.barLivePosition = nil
        }
    }

    private func icon(for tab: AppTab) -> String {
        switch tab {
        case .contacts: return "person.crop.circle"
        case .calls: return "phone.fill"
        case .chats: return "bubble.left.and.bubble.right.fill"
        case .settings: return "gearshape.fill"
        }
    }

    private func title(for tab: AppTab) -> LocalizedStringKey {
        switch tab {
        case .contacts: return "Контакты"
        case .calls: return "Звонки"
        case .chats: return "Чаты"
        case .settings: return "Настройки"
        }
    }
}

// Хром нативной шапки с деградацией по версиям: на iOS 26+ просим мягкий
// краевой эффект (системный аналог самодельного EdgeDim), на 18–25 — только
// снимаем фон навбара, на 17 оставляем как есть.
private struct NativeHeaderChrome: ViewModifier {
    @ViewBuilder
    func body(content: Content) -> some View {
        #if compiler(>=6.0)
        if #available(iOS 26.0, *) {
            content
                .toolbarBackgroundVisibility(.hidden, for: .navigationBar)
                .scrollEdgeEffectStyle(.soft, for: .top)
        } else if #available(iOS 18.0, *) {
            content.toolbarBackgroundVisibility(.hidden, for: .navigationBar)
        } else {
            content
        }
        #else
        content
        #endif
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

    // Без собственного NavigationStack — общий стек HomeView.
    var body: some View {
        Group {
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
            // ПРОБА нативного навбара вместо FloatingHeader. Подложку убираем
            // toolbarBackgroundVisibility (iOS 18+), а стеклянный край у скролла —
            // scrollEdgeEffectStyle (iOS 26+), который и был настоящей причиной
            // «неубираемой подложки»: toolbarBackground к нему отношения не имеет.
            .navigationTitle("Звонки")
            .navigationBarTitleDisplayMode(.large)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Label(connected ? "Связь установлена" : "Переподключение…",
                          systemImage: connected ? "checkmark.circle.fill" : "arrow.triangle.2.circlepath")
                        .labelStyle(.iconOnly)
                        .foregroundStyle(connected ? .green : palette.textSecondary)
                        .accessibilityLabel(connected ? "Сервер звонков подключён" : "Сервер звонков переподключается")
                }
            }
            .modifier(NativeHeaderChrome())
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
