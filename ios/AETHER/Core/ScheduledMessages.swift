import Foundation
import SwiftUI

/// Отложенные сообщения — перенос возможности из Android-клиента, где их
/// отправляет WorkManager.
///
/// ЧЕСТНО ПРО ОГРАНИЧЕНИЕ iOS: гарантировать отправку ровно в назначенную
/// минуту здесь нельзя. Система не обещает разбудить приложение в заданное
/// время: фоновые задачи выдаются по её усмотрению. Поэтому механизм такой —
/// сообщение хранится локально и уходит при первой же возможности после срока:
/// при открытии приложения, при возврате из фона или в фоновой задаче, если
/// система её выдаст. То есть «в 9:00» означает «в 9:00 или при первой
/// возможности после».
struct ScheduledMessage: Identifiable, Codable, Hashable {
    var id = UUID()
    var peerId: String
    var text: String
    var dueAt: Date
}

@MainActor
final class ScheduledStore: ObservableObject {
    static let shared = ScheduledStore()
    private let key = "scheduledMessages"

    @Published private(set) var items: [ScheduledMessage] = []

    private init() {
        if let data = UserDefaults.standard.data(forKey: key),
           let saved = try? JSONDecoder().decode([ScheduledMessage].self, from: data) {
            items = saved
        }
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(items) else { return }
        UserDefaults.standard.set(data, forKey: key)
    }

    func add(peerId: String, text: String, dueAt: Date) {
        items.append(ScheduledMessage(peerId: peerId.lowercased(), text: text, dueAt: dueAt))
        items.sort { $0.dueAt < $1.dueAt }
        save()
    }

    func remove(_ item: ScheduledMessage) {
        items.removeAll { $0.id == item.id }
        save()
    }

    func pending(for peer: String) -> [ScheduledMessage] {
        items.filter { $0.peerId == peer.lowercased() }
    }

    /// Отправляет всё, чей срок наступил. Зовётся при запуске, при возврате из
    /// фона и из фоновой задачи.
    func flushDue(using messaging: Messaging) async {
        let now = Date()
        let due = items.filter { $0.dueAt <= now }
        guard !due.isEmpty else { return }
        for item in due {
            _ = messaging.sendText(to: item.peerId, text: item.text,
                                   isGroup: messaging.isGroup(item.peerId))
            remove(item)
        }
    }
}
