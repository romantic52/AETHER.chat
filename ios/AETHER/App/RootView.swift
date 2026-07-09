import SwiftUI

struct RootView: View {
    @EnvironmentObject var session: Session
    @Environment(\.palette) private var palette
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var lock = AppLock.shared

    var body: some View {
        ZStack {
            palette.background.ignoresSafeArea()

            switch session.phase {
            case .loading:
                ProgressView().tint(palette.accent)
            case .onboarding:
                WelcomeView()
                    .transition(.opacity)
            case .ready:
                HomeView()
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
