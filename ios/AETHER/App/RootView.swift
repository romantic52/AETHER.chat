import SwiftUI

struct RootView: View {
    @EnvironmentObject var session: Session
    @Environment(\.palette) private var palette
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var lock = AppLock.shared

    var body: some View {
        #if DEBUG
        if ProcessInfo.processInfo.environment["AETHER_BARNATIVE"] == "1", #available(iOS 18.0, *) {
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

            // Домашний экран монтируется СРАЗУ и живёт дальше — так же, как жил
            // самодельный бар: он был частью иерархии с первого кадра. Системный
            // таб-бар внутри TabView прикрепляется при монтировании, и делать это
            // надо заранее, а не в момент показа — иначе экран уже виден, а бар
            // ещё едет. Фаза загрузки просто накрывает готовый дом сверху.
            if session.phase != .onboarding {
                HomeView()
                    // Пересоздание — только на смену аккаунта (см. accountGeneration).
                    .id(session.accountGeneration)
            }

            if session.phase == .loading {
                palette.background.ignoresSafeArea()
                    .overlay { ProgressView().tint(palette.accent) }
            }

            if session.phase == .onboarding {
                WelcomeView()
                    .transition(.opacity)
            }
        }
        // Анимируется только уход онбординга; дом и бар встают мгновенно.
        .animation(.easeInOut(duration: 0.3), value: session.phase == .onboarding)
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
// ДИАГНОСТИЧЕСКИЙ СТЕНД. Повторяет конструкцию боевого запуска БЕЗ сессии и
// сервера: тот же switch по фазе с той же анимацией и .transition(.opacity),
// внутри — тот же TabView с пятью вкладками, включая роль .search. Нужен, чтобы
// проверить, появляется ли системный док, когда TabView монтируется внутри
// анимированного перехода. Запуск:
//   SIMCTL_CHILD_AETHER_DOCKTEST=1 xcrun simctl launch <udid> com.rmkhc.aether
// ВРЕМЕННЫЙ СТЕНД — УДАЛИТЬ. Рисует только док поверх фона темы, минуя сессию и
// экран входа: нужен, чтобы снять скриншот бара и сравнить с системным.
// Запуск: SIMCTL_CHILD_AETHER_BARONLY=1 xcrun simctl launch <udid> com.rmkhc.aether
// ВРЕМЕННЫЙ ЭТАЛОН — УДАЛИТЬ вместе со стендом. Системный TabView с теми же
// четырьмя вкладками: переключаем выбор программно и снимаем видео, чтобы
// получить эталонные тайминги и деформацию подложки. Тапать в симуляторе нечем,
// а Photos программно не переключишь — поэтому эталон строим сами.
@available(iOS 18.0, *)
private struct NativeBarReference: View {
    @State private var sel = 0
    private let titles = ["Контакты", "Звонки", "Чаты", "Настройки"]
    private let icons = ["person.crop.circle", "phone.fill",
                         "bubble.left.and.bubble.right.fill", "gearshape.fill"]

    var body: some View {
        // Пять вкладок, пятая — с ролью .search: система выносит её отдельным
        // кружком. Это и есть эталон, под который подгоняется наш бар.
        TabView(selection: $sel) {
            Tab(titles[0], systemImage: icons[0], value: 0) { Color.black.ignoresSafeArea() }
            Tab(titles[1], systemImage: icons[1], value: 1) { Color.black.ignoresSafeArea() }
            Tab(titles[2], systemImage: icons[2], value: 2) { Color.black.ignoresSafeArea() }
            Tab(titles[3], systemImage: icons[3], value: 3) { Color.black.ignoresSafeArea() }
            Tab(value: 4, role: .search) { Color.black.ignoresSafeArea() }
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
