import Foundation
import BackgroundTasks
import UIKit

// Фоновая подкачка inbox: без APNs уведомления приходят, только пока процесс жив.
// BGAppRefreshTask даёт системные «пробуждения» (iOS сама решает когда, обычно
// раз в 15+ минут при регулярном использовании) — будимся, поллим inbox один раз,
// новые сообщения по обычному пути порождают баннеры/островок.
@MainActor
final class AppRefresh {
    static let shared = AppRefresh()
    static let taskId = "com.rmkhc.aether.refresh"
    private init() {}

    /// Хук поллинга — назначается из HomeView, когда Messaging уже привязан к сессии.
    var poll: (() async -> Void)?

    func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.taskId, using: nil) { task in
            guard let refresh = task as? BGAppRefreshTask else { return }
            let work = Task { @MainActor in
                await AppRefresh.shared.poll?()
                AppRefresh.shared.schedule()   // следующее пробуждение
                refresh.setTaskCompleted(success: true)
            }
            refresh.expirationHandler = { work.cancel() }
        }
    }

    /// Планируем при уходе в фон; повторный submit того же id безвреден.
    func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: Self.taskId)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 60)
        try? BGTaskScheduler.shared.submit(request)
    }
}
