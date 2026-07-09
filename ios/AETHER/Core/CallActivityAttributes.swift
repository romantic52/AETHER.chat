import Foundation
#if canImport(ActivityKit)
import ActivityKit

// Атрибуты Live Activity звонка — общий тип приложения и виджет-расширения
// (файл компилируется в оба таргета, см. project.yml).
struct CallActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        /// Человекочитаемая фаза: «Вызов…», «Входящий…», «Соединение…»; пустая — разговор идёт.
        var phase: String
        /// Начало разговора — от него островок сам тикает таймер (без пушей обновлений).
        var startedAt: Date
        /// true — звонок активен, показываем таймер вместо фазы.
        var active: Bool
    }

    var peerName: String
    var isVideo: Bool
}

// Live Activity «сообщения за день»: счётчик + последний отправитель.
// Включается тумблером в Настройках. Тап по островку ведёт в чат (widgetURL).
struct MessagesActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var count: Int        // сообщений за сегодня
        var lastSender: String
        var lastPeer: String  // peer id для deep link aether://chat/<peer>
    }
}
#endif
