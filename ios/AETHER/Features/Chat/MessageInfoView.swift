import SwiftUI

// «Где сейчас моё сообщение».
//
// Главный ответ, который Aether обязан уметь давать честно: каким путём
// сообщение ушло, участвовал ли сервер, осталась ли у него копия. Без этого
// экрана все режимы доставки — обещание на словах.
//
// docs/TRANSPORT_LAYER_DESIGN.md, разделы 10 и 67.
struct MessageInfoView: View {
    let message: ChatMessage
    let peerTitle: String

    @EnvironmentObject var session: Session
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss

    @State private var route: MessageRoute?
    @State private var attempts: [DeliveryAttempt] = []
    @State private var loaded = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    row("Состояние", statusText, tint: statusTint)
                    row("Отправлено", Self.time(message.ts))
                    if let delivered = route?.deliveredTs {
                        row("Доставлено", Self.time(delivered))
                    }
                    if let read = route?.readTs {
                        row("Прочитано", Self.time(read))
                    }
                }
                .listRowBackground(palette.surface)

                Section {
                    row("Маршрут", transportText)
                    if let physical = route?.physical {
                        row("Канал", physical)
                    }
                    row("Сервер", serverText, tint: route?.serverId == nil ? palette.accent : nil)
                    row("Хранение на сервере", storageText,
                        tint: (route?.serverStored ?? false) ? nil : palette.accent)
                    row("Шифрование", "Сквозное")
                } header: {
                    Text("Как доставлено")
                } footer: {
                    Text(footerText)
                }
                .listRowBackground(palette.surface)

                if !attempts.isEmpty {
                    Section {
                        ForEach(attempts, id: \.attempt) { attempt in
                            VStack(alignment: .leading, spacing: 3) {
                                HStack {
                                    Text(Self.transportTitle(attempt.transport))
                                        .foregroundStyle(palette.textPrimary)
                                    Spacer()
                                    Text(Self.outcomeTitle(attempt.outcome))
                                        .font(.system(size: 14))
                                        .foregroundStyle(Self.outcomeTint(attempt.outcome,
                                                                          palette: palette))
                                }
                                Text(Self.time(attempt.startedTs))
                                    .font(.caption).foregroundStyle(palette.textSecondary)
                                if let detail = attempt.detail, !detail.isEmpty {
                                    Text(detail)
                                        .font(.caption)
                                        .foregroundStyle(palette.textSecondary)
                                        .lineLimit(3)
                                }
                            }
                            .padding(.vertical, 2)
                        }
                    } header: {
                        Text("Попытки доставки")
                    } footer: {
                        Text("Каждая строка — отдельная попытка. Идентификатор сообщения при смене маршрута не меняется, поэтому у получателя не появится второй копии.")
                    }
                    .listRowBackground(palette.surface)
                }

                Section {
                    Text(message.id)
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundStyle(palette.textSecondary)
                        .textSelection(.enabled)
                } header: {
                    Text("Идентификатор")
                }
                .listRowBackground(palette.surface)
            }
            .scrollContentBackground(.hidden)
            .background(palette.background.ignoresSafeArea())
            .navigationTitle("О сообщении")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Готово") { dismiss() }
                }
            }
        }
        .task {
            guard !loaded else { return }
            route = await session.core.routeFor(message.id)
            attempts = await session.core.deliveryAttempts(message.id)
            loaded = true
        }
    }

    // MARK: - Тексты

    private var statusText: String {
        switch message.status {
        case -1: return "Не отправлено"
        case 0: return "Отправляется"
        case 1: return "Отправлено"
        case 2: return "Доставлено"
        case 3: return "Прочитано"
        case 4: return "Ждёт получателя рядом"
        default: return "Неизвестно"
        }
    }

    private var statusTint: Color? {
        switch message.status {
        case -1: return palette.danger
        case 4: return palette.accent
        default: return nil
        }
    }

    private var transportText: String {
        guard let route else {
            return message.status == 4 ? "Ещё не выбран" : "Неизвестно"
        }
        return Self.transportTitle(route.transport)
    }

    private var serverText: String {
        guard let route else { return message.status == 4 ? "Не использован" : "Неизвестно" }
        guard let serverId = route.serverId else { return "Не использован" }
        return ServerRegistry.shared.server(serverId)?.displayName ?? serverId
    }

    private var storageText: String {
        guard let route, route.serverId != nil else { return "Нет" }
        return route.serverStored ? "Шифрованная копия" : "Только передача"
    }

    private var footerText: String {
        if message.status == 4 {
            // Про САМО СООБЩЕНИЕ, а не про текущую настройку чата: режим мог
            // смениться уже после отправки, и утверждать «чат настроен так»
            // было бы неправдой ровно на том экране, который создан не врать.
            return "Сообщение отправлено в режиме «только напрямую»: оно ждёт, когда получатель окажется рядом, и серверу передано не будет."
        }
        if route?.serverId == nil && route != nil {
            return "Сервер в доставке не участвовал."
        }
        return "Сервер получает только шифротекст: содержимое сообщения ему недоступно. Видны технические данные — кто, кому и когда."
    }

    static func transportTitle(_ id: String) -> String {
        if id.hasPrefix("server.") {
            let serverId = String(id.dropFirst("server.".count))
            let name = ServerRegistry.shared.server(serverId)?.displayName
            return name.map { "Сервер · \($0)" } ?? "Сервер"
        }
        switch id {
        case "nearby.ble": return "Рядом · Bluetooth"
        case "nearby.wifi": return "Рядом · Wi-Fi"
        default: return id
        }
    }

    static func outcomeTitle(_ outcome: String?) -> String {
        switch outcome {
        case "ok": return "доставлено"
        case "unreachable": return "недоступен"
        case "rejected": return "отклонено"
        case "timeout": return "нет ответа"
        case "error": return "ошибка"
        case nil: return "в процессе"
        default: return outcome ?? ""
        }
    }

    static func outcomeTint(_ outcome: String?, palette: Palette) -> Color {
        switch outcome {
        case "ok": return palette.accent
        case nil: return palette.textSecondary
        default: return palette.danger
        }
    }

    private static let formatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "d MMM, HH:mm:ss"
        f.locale = Locale(identifier: "ru_RU")
        return f
    }()

    static func time(_ ms: Int64) -> String {
        formatter.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
    }

    private func row(_ title: String, _ value: String, tint: Color? = nil) -> some View {
        HStack {
            Text(title).foregroundStyle(palette.textSecondary)
            Spacer()
            Text(value)
                .foregroundStyle(tint ?? palette.textPrimary)
                .multilineTextAlignment(.trailing)
        }
    }
}
