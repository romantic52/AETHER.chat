import SwiftUI

struct RootView: View {
    @EnvironmentObject var session: Session
    @Environment(\.palette) private var palette
    @Environment(\.scenePhase) private var scenePhase

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
        }
    }
}
