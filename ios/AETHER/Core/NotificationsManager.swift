import Foundation
import UserNotifications
import UIKit
#if canImport(ActivityKit)
import ActivityKit
#endif

// Уведомления о сообщениях: локальные баннеры (лок-скрин/шторка) + Live Activity
// со счётчиком сообщений за день в Dynamic Island. Оба режима опциональны —
// тумблеры в Настройках. Настоящих push нет (сервер без APNs), поэтому баннеры
// работают, пока процесс жив (фоновый keepalive + WS/поллинг).
@MainActor
final class NotificationsManager {
    static let shared = NotificationsManager()
    private init() {}

    static let notifyKey = "notifyMessages"
    static let islandKey = "islandDailyCounter"
    static let openChatNotification = Notification.Name("aetherOpenChat")

    var notifyEnabled: Bool { UserDefaults.standard.bool(forKey: Self.notifyKey) }
    var islandEnabled: Bool { UserDefaults.standard.bool(forKey: Self.islandKey) }

    @discardableResult
    func requestPermission() async -> Bool {
        (try? await UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    // MARK: - Входящее сообщение

    func incomingMessage(peer: String, chatTitle: String, sender: String,
                         preview: String, muted: Bool, isGroup: Bool) {
        guard !muted else { return }
        let count = bumpDailyCount()
        let displayName = chatTitle.isEmpty ? sender : chatTitle

        if islandEnabled {
            updateIsland(count: count, sender: displayName, peer: peer)
        }
        if notifyEnabled, UIApplication.shared.applicationState != .active {
            let content = UNMutableNotificationContent()
            content.title = displayName
            content.body = isGroup ? "\(sender): \(preview)" : preview
            content.sound = .default
            content.userInfo = ["peer": peer]
            let request = UNNotificationRequest(identifier: UUID().uuidString,
                                                content: content, trigger: nil)
            UNUserNotificationCenter.current().add(request)
        }
    }

    /// Счётчик сообщений за календарный день (сбрасывается в полночь).
    private func bumpDailyCount() -> Int {
        let defaults = UserDefaults.standard
        let today = Calendar.current.startOfDay(for: Date())
        let lastDay = defaults.object(forKey: "dailyCountDate") as? Date ?? .distantPast
        var count = Calendar.current.isDate(lastDay, inSameDayAs: today)
            ? defaults.integer(forKey: "dailyCount") : 0
        count += 1
        defaults.set(count, forKey: "dailyCount")
        defaults.set(today, forKey: "dailyCountDate")
        return count
    }

    // MARK: - Live Activity со счётчиком

    #if canImport(ActivityKit)
    private var activity: Activity<MessagesActivityAttributes>?
    #endif

    private func updateIsland(count: Int, sender: String, peer: String) {
        #if canImport(ActivityKit)
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        let content = ActivityContent(
            state: MessagesActivityAttributes.ContentState(count: count, lastSender: sender, lastPeer: peer),
            staleDate: Date().addingTimeInterval(4 * 3600)
        )
        // Подхватываем и активность, пережившую перезапуск приложения.
        if let existing = activity ?? Activity<MessagesActivityAttributes>.activities.first {
            activity = existing
            Task { await existing.update(content) }
        } else {
            activity = try? Activity.request(attributes: MessagesActivityAttributes(), content: content)
        }
        #endif
    }

    /// Погасить островок (выключили тумблер или разлогин).
    func endIsland() {
        #if canImport(ActivityKit)
        for existing in Activity<MessagesActivityAttributes>.activities {
            Task { await existing.end(nil, dismissalPolicy: .immediate) }
        }
        activity = nil
        #endif
    }
}
