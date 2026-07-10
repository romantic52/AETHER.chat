import SwiftUI
import UIKit
import UserNotifications

@main
struct AetherApp: App {
    @StateObject private var session = Session()
    @StateObject private var appearance = AppearanceSettings()
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(session)
                .environmentObject(appearance)
                .environment(\.palette, appearance.palette)
                .preferredColorScheme(appearance.colorScheme)
                .tint(appearance.palette.accent)
                // Deep link из островка/уведомления: aether://chat/<peer>.
                .onOpenURL { url in
                    guard url.scheme == "aether", url.host == "chat" else { return }
                    let peer = url.lastPathComponent
                    guard !peer.isEmpty, peer != "chat" else { return }
                    NotificationCenter.default.post(
                        name: NotificationsManager.openChatNotification,
                        object: nil, userInfo: ["peer": peer]
                    )
                }
        }
    }
}

// Приложение должно оставаться активным в фоне: WS-соединение и поллинг inbox
// не останавливаются при сворачивании. Чтобы ОС не приостановила процесс сразу,
// запрашиваем дополнительное фоновое время через beginBackgroundTask — iOS даёт
// до ~30с (иногда больше) активного выполнения, пока мы держим задачу открытой.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    private var bgTaskId: UIBackgroundTaskIdentifier = .invalid

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        AppRefresh.shared.register()
        // APNs: токен нужен и до включения тумблера уведомлений — сервер шлёт
        // generic-баннеры только офлайн-получателям, текста в них нет.
        PushRegistrar.requestRegistration()
        return true
    }

    func application(_ application: UIApplication,
                     didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        PushRegistrar.uploadToken(deviceToken)
    }

    func application(_ application: UIApplication,
                     didFailToRegisterForRemoteNotificationsWithError error: Error) {
        // Нет сети/окружения без aps — не критично, WS-доставка работает.
    }

    // Тап по локальному уведомлению → открыть чат отправителя.
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                didReceive response: UNNotificationResponse) async {
        if let peer = response.notification.request.content.userInfo["peer"] as? String {
            NotificationCenter.default.post(
                name: NotificationsManager.openChatNotification,
                object: nil, userInfo: ["peer": peer]
            )
        }
    }

    // Баннер показываем и при активном приложении (кроме открытого чата —
    // туда уведомление вообще не постится, см. Messaging).
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        AppRefresh.shared.schedule()
        endBgTask()
        bgTaskId = application.beginBackgroundTask(withName: "aether-bg-keepalive") { [weak self] in
            self?.endBgTask()
        }
    }

    func applicationWillEnterForeground(_ application: UIApplication) {
        endBgTask()
    }

    private func endBgTask() {
        guard bgTaskId != .invalid else { return }
        UIApplication.shared.endBackgroundTask(bgTaskId)
        bgTaskId = .invalid
    }
}
