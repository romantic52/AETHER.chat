import WidgetKit
import SwiftUI
import ActivityKit

// Live Activity звонка: компактный вид в Dynamic Island (иконка + таймер),
// развёрнутый — имя, фаза/таймер; на лок-скрине — баннер звонка.
@main
struct AetherIslandBundle: WidgetBundle {
    var body: some Widget {
        CallLiveActivity()
        MessagesLiveActivity()
    }
}

// Кружок-аватар с инициалами, градиент детерминирован по id — та же схема,
// что в компоненте Avatar приложения.
struct InitialsAvatar: View {
    let id: String
    let name: String
    var size: CGFloat = 26

    private var initials: String {
        let base = name.isEmpty ? id : name
        let parts = base.split(separator: " ")
        if parts.count >= 2 { return String(parts[0].prefix(1) + parts[1].prefix(1)).uppercased() }
        return String(base.prefix(2)).uppercased()
    }

    private var colors: [Color] {
        let palettes: [[UInt32]] = [
            [0x5B8CFF, 0x2F5BD6], [0xE86A9A, 0xC4437A], [0x4ADE80, 0x22A55A],
            [0xF5A623, 0xD98016], [0xC4A7E7, 0x9B7BD4], [0x30C5D2, 0x1E96A1],
        ]
        var h = 5381
        for b in id.utf8 { h = ((h << 5) &+ h) &+ Int(b) }
        let pair = palettes[abs(h) % palettes.count]
        return pair.map { hex in
            Color(red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255)
        }
    }

    var body: some View {
        ZStack {
            Circle().fill(LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing))
            Text(initials)
                .font(.system(size: size * 0.38, weight: .semibold))
                .foregroundStyle(.white)
        }
        .frame(width: size, height: size)
    }
}

// Островок «сообщения за день»: ава+имя последнего отправителя, счётчик,
// тап в любом месте — deep link в этот чат (aether://chat/<peer>).
struct MessagesLiveActivity: Widget {
    private let accent = Color(red: 0x5B / 255, green: 0x8C / 255, blue: 0xFF / 255)

    private func chatURL(_ context: ActivityViewContext<MessagesActivityAttributes>) -> URL? {
        URL(string: "aether://chat/\(context.state.lastPeer)")
    }

    var body: some WidgetConfiguration {
        ActivityConfiguration(for: MessagesActivityAttributes.self) { context in
            // Лок-скрин / баннер.
            HStack(spacing: 12) {
                InitialsAvatar(id: context.state.lastPeer, name: context.state.lastSender, size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(context.state.lastSender).font(.headline).lineLimit(1)
                    Text("Сообщений сегодня: \(context.state.count)")
                        .font(.subheadline).foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "bubble.left.and.bubble.right.fill")
                    .foregroundStyle(accent)
            }
            .padding(14)
            .activityBackgroundTint(Color.black.opacity(0.55))
            .activitySystemActionForegroundColor(accent)
            .widgetURL(chatURL(context))
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    InitialsAvatar(id: context.state.lastPeer, name: context.state.lastSender, size: 36)
                        .padding(.leading, 4)
                        .widgetURL(chatURL(context))
                }
                DynamicIslandExpandedRegion(.center) {
                    VStack(spacing: 2) {
                        Text(context.state.lastSender).font(.headline).lineLimit(1)
                        Text("Перейти в чат").font(.caption).foregroundStyle(.secondary)
                    }
                    .widgetURL(chatURL(context))
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text("\(context.state.count)")
                        .font(.system(.title3, design: .rounded).weight(.bold))
                        .foregroundStyle(accent)
                        .padding(.trailing, 6)
                        .widgetURL(chatURL(context))
                }
            } compactLeading: {
                InitialsAvatar(id: context.state.lastPeer, name: context.state.lastSender, size: 22)
                    .widgetURL(chatURL(context))
            } compactTrailing: {
                Text("\(context.state.count)")
                    .font(.system(size: 14, design: .rounded).weight(.bold))
                    .foregroundStyle(accent)
                    .widgetURL(chatURL(context))
            } minimal: {
                InitialsAvatar(id: context.state.lastPeer, name: context.state.lastSender, size: 22)
                    .widgetURL(chatURL(context))
            }
            .keylineTint(accent)
        }
    }
}

struct CallLiveActivity: Widget {
    private let accent = Color(red: 0x4A / 255, green: 0xDE / 255, blue: 0x80 / 255)

    var body: some WidgetConfiguration {
        ActivityConfiguration(for: CallActivityAttributes.self) { context in
            // Лок-скрин / баннер.
            HStack(spacing: 12) {
                callIcon(context)
                VStack(alignment: .leading, spacing: 2) {
                    Text(context.attributes.peerName)
                        .font(.headline)
                    statusLine(context)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Text("Æ")
                    .font(.system(size: 22, weight: .bold, design: .serif))
                    .foregroundStyle(accent)
            }
            .padding(14)
            .activityBackgroundTint(Color.black.opacity(0.55))
            .activitySystemActionForegroundColor(accent)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    callIcon(context)
                        .padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.center) {
                    VStack(spacing: 2) {
                        Text(context.attributes.peerName)
                            .font(.headline)
                            .lineLimit(1)
                        statusLine(context)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                DynamicIslandExpandedRegion(.trailing) {
                    if context.state.active {
                        timerText(context)
                            .font(.system(.body, design: .monospaced).weight(.semibold))
                            .foregroundStyle(accent)
                            .frame(maxWidth: 60)
                            .padding(.trailing, 4)
                    }
                }
            } compactLeading: {
                callIcon(context)
            } compactTrailing: {
                if context.state.active {
                    timerText(context)
                        .font(.system(size: 13, design: .monospaced).weight(.semibold))
                        .foregroundStyle(accent)
                        .frame(maxWidth: 46)
                } else {
                    Image(systemName: "ellipsis")
                        .foregroundStyle(accent)
                }
            } minimal: {
                callIcon(context)
            }
            .keylineTint(accent)
        }
    }

    private func callIcon(_ context: ActivityViewContext<CallActivityAttributes>) -> some View {
        Image(systemName: context.attributes.isVideo ? "video.fill" : "phone.fill")
            .foregroundStyle(accent)
    }

    @ViewBuilder
    private func statusLine(_ context: ActivityViewContext<CallActivityAttributes>) -> some View {
        if context.state.active {
            Text(context.attributes.isVideo ? "Видеозвонок" : "Аудиозвонок")
        } else {
            Text(context.state.phase)
        }
    }

    private func timerText(_ context: ActivityViewContext<CallActivityAttributes>) -> Text {
        // Островок тикает сам от startedAt — обновления из приложения не нужны.
        Text(timerInterval: context.state.startedAt...Date(timeIntervalSinceNow: 8 * 3600),
             countsDown: false)
    }
}
