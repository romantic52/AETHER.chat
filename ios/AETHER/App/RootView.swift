import SwiftUI

struct RootView: View {
    @EnvironmentObject var session: Session
    @Environment(\.palette) private var palette
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var lock = AppLock.shared

    var body: some View {
        #if DEBUG
        if ProcessInfo.processInfo.environment["AETHER_BARNATIVE"] == "1" {
            NativeBarReference()
        } else if ProcessInfo.processInfo.environment["AETHER_BARONLY"] == "1" {
            BarOnlyHarness()
        } else {
            main
        }
        #else
        main
        #endif
    }

    private var main: some View {
        ZStack {
            palette.background.ignoresSafeArea()

            switch session.phase {
            case .loading:
                ProgressView().tint(palette.accent)
            case .onboarding:
                WelcomeView()
                    .transition(.opacity)
            case .ready:
                // .id(myId): смена аккаунта пересоздаёт весь домашний экран
                // (Messaging, чаты, вкладки) под новую личность.
                HomeView()
                    .id(session.myId)
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.3), value: session.phase)
        .task {
            session.setApplicationActive(scenePhase != .background)
            await session.bootstrap()
        }
        .onChange(of: scenePhase) { _, phase in
            session.setApplicationActive(phase != .background)
            if phase == .background { lock.appDidEnterBackground() }
        }
        // Экран блокировки поверх всего (и поверх контента в App Switcher).
        .overlay {
            if lock.locked && session.phase == .ready {
                LockView(lock: lock)
                    // Появление — мгновенно/тихо (фон, App Switcher), уход — экран
                    // «слетает» вверх вслед за открывшимся замком.
                    .transition(.asymmetric(insertion: .opacity,
                                            removal: .move(edge: .top).combined(with: .opacity)))
                    .zIndex(200)
            }
        }
    }
}

#if DEBUG
// ВРЕМЕННЫЙ СТЕНД — УДАЛИТЬ. Рисует только док поверх фона темы, минуя сессию и
// экран входа: нужен, чтобы снять скриншот бара и сравнить с системным.
// Запуск: SIMCTL_CHILD_AETHER_BARONLY=1 xcrun simctl launch <udid> com.rmkhc.aether
// ВРЕМЕННЫЙ ЭТАЛОН — УДАЛИТЬ вместе со стендом. Системный TabView с теми же
// четырьмя вкладками: переключаем выбор программно и снимаем видео, чтобы
// получить эталонные тайминги и деформацию подложки. Тапать в симуляторе нечем,
// а Photos программно не переключишь — поэтому эталон строим сами.
private struct NativeBarReference: View {
    @State private var sel = 0
    private let titles = ["Контакты", "Звонки", "Чаты", "Настройки"]
    private let icons = ["person.crop.circle", "phone.fill",
                         "bubble.left.and.bubble.right.fill", "gearshape.fill"]

    var body: some View {
        TabView(selection: $sel) {
            ForEach(0..<4, id: \.self) { i in
                Color.black.ignoresSafeArea()
                    .tabItem { Label(titles[i], systemImage: icons[i]) }
                    .tag(i)
            }
        }
        .task {
            guard ProcessInfo.processInfo.environment["AETHER_BARCYCLE"] == "1" else { return }
            var i = 0
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 700_000_000)
                i += 1
                sel = i % 4
            }
        }
    }
}

private struct BarOnlyHarness: View {
    @Environment(\.palette) private var palette
    @StateObject private var chrome = ChromeState()

    var body: some View {
        ZStack(alignment: .bottom) {
            palette.background.ignoresSafeArea()
            TabBar(tab: $chrome.tab, unread: 3)
        }
        // Как в HomeView — иначе бар встаёт над safe area и замеры отступа врут.
        .ignoresSafeArea(edges: .bottom)
        .environmentObject(chrome)
        // AETHER_BARCYCLE=1 — гоняет вкладки по кругу той же анимацией, что и тап.
        // Нужно, чтобы снять переход серией скриншотов и увидеть, сливаются ли
        // стёкла бара и подложки в движении: тапать в симуляторе нечем.
        .task {
            guard ProcessInfo.processInfo.environment["AETHER_BARCYCLE"] == "1" else { return }
            let order: [AppTab] = [.contacts, .calls, .chats, .settings]
            var i = 0
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 700_000_000)
                i += 1
                withAnimation(.snappy(duration: 0.25, extraBounce: 0.02)) {
                    chrome.tab = order[i % order.count]
                }
            }
        }
    }
}
#endif
