import SwiftUI

/// Корневой роутер: переключает флоу по `session.phase`.
struct RootView: View {
    @Environment(Session.self) private var session

    var body: some View {
        ZStack {
            Brand.backdrop.ignoresSafeArea()

            switch session.phase {
            case .welcome:
                WelcomeView()
                    .transition(.opacity)
            case .auth, .authenticating:
                AuthView()
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            case .ready:
                HomeView()
                    .transition(.opacity)
            }
        }
        .animation(.spring(duration: 0.5), value: session.phase)
    }
}
