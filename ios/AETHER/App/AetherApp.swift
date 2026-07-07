import SwiftUI

@main
struct AetherApp: App {
    @State private var session = Session()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(session)
                .preferredColorScheme(.dark)
                .tint(Brand.accent)
                .task { session.bootstrap() }
        }
    }
}
