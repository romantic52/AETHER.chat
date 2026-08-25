import SwiftUI

// Доставка и данные: как отправлять и что вообще позволено отдавать серверу.
//
// Два раздела, и они намеренно не смешаны. «Как доставить» и «что серверу
// разрешено хранить» — независимые вопросы: можно доставлять через сервер и
// запрещать хранение, можно доставлять напрямую и разрешать шифрованную копию.
struct DeliverySettingsView: View {
    @EnvironmentObject var session: Session
    @EnvironmentObject var messaging: Messaging
    @Environment(\.palette) private var palette

    /// Категории содержимого в том виде, в каком их понимает политика сервера.
    private static let kinds: [(key: String, title: String, icon: String)] = [
        ("text", "Текст", "text.bubble"),
        ("image", "Фотографии", "photo"),
        ("video", "Видео", "video"),
        ("voice", "Голосовые", "mic"),
        ("file", "Документы", "doc"),
    ]

    @State private var allowed: [String: Bool] = [:]
    @State private var chatModes: [String: DeliveryMode] = [:]

    private var serverName: String { session.activeServer?.displayName ?? "сервер" }

    var body: some View {
        List {
            Section {
                ForEach(Self.kinds, id: \.key) { kind in
                    Toggle(isOn: binding(for: kind.key)) {
                        SettingsLabel(LocalizedStringKey(kind.title), icon: kind.icon, color: .blue)
                    }
                    .listRowBackground(palette.surface)
                }
            } header: {
                Text("Что можно передавать серверу «\(serverName)»")
            } footer: {
                Text("Запрещённая категория не уйдёт на сервер ни при каких настройках чата: такое сообщение будет доставлено только напрямую. Содержимое в любом случае зашифровано — запрет означает, что сервер не получит даже шифротекст.")
            }

            Section {
                if messaging.chats.isEmpty {
                    Text("Пока нет чатов")
                        .foregroundStyle(palette.textSecondary)
                        .listRowBackground(palette.surface)
                } else {
                    ForEach(messaging.chats, id: \.peerId) { chat in
                        NavigationLink {
                            ChatDeliveryView(peerId: chat.peerId, title: chat.title)
                                .environmentObject(session)
                                .environmentObject(messaging)
                        } label: {
                            HStack {
                                Text(chat.title.isEmpty ? chat.peerId : chat.title)
                                    .foregroundStyle(palette.textPrimary)
                                Spacer()
                                Text(Self.modeTitle(chatModes[chat.peerId] ?? .auto))
                                    .font(.system(size: 15))
                                    .foregroundStyle(palette.textSecondary)
                            }
                        }
                        .listRowBackground(palette.surface)
                    }
                }
            } header: {
                Text("Доставка по чатам")
            }
        }
        .scrollContentBackground(.hidden)
        .background(palette.background.ignoresSafeArea())
        .navigationTitle("Доставка и данные")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private func binding(for kind: String) -> Binding<Bool> {
        Binding(
            get: { allowed[kind] ?? true },
            set: { value in
                allowed[kind] = value
                Task {
                    await session.core.setServerAllows(serverId: ServerContext.serverId,
                                                       contentKind: kind, allowed: value)
                }
            }
        )
    }

    private func load() async {
        var result: [String: Bool] = [:]
        for kind in Self.kinds {
            result[kind.key] = await session.core.serverAllows(serverId: ServerContext.serverId,
                                                               contentKind: kind.key)
        }
        allowed = result

        var modes: [String: DeliveryMode] = [:]
        for chat in messaging.chats {
            let stored = await session.core.chatPolicy(chat.peerId)
            modes[chat.peerId] = DeliveryMode(rawValue: stored.deliveryMode) ?? .auto
        }
        chatModes = modes
    }

    static func modeTitle(_ mode: DeliveryMode) -> String {
        switch mode {
        case .auto: return "Автоматически"
        case .directOnly: return "Только напрямую"
        case .directPlusBackup: return "Напрямую + копия"
        case .server: return "Через сервер"
        }
    }
}

// Политика одного чата.
struct ChatDeliveryView: View {
    let peerId: String
    let title: String

    @EnvironmentObject var session: Session
    @EnvironmentObject var messaging: Messaging
    @Environment(\.palette) private var palette

    @State private var mode: DeliveryMode = .auto
    @State private var storage: ServerStorage = .encryptedBackup
    @State private var loaded = false

    var body: some View {
        List {
            Section {
                ForEach([DeliveryMode.auto, .directOnly, .directPlusBackup, .server], id: \.rawValue) { option in
                    Button { pick(mode: option) } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(DeliverySettingsView.modeTitle(option))
                                    .foregroundStyle(palette.textPrimary)
                                Text(Self.modeHint(option))
                                    .font(.caption).foregroundStyle(palette.textSecondary)
                            }
                            Spacer()
                            if mode == option {
                                Image(systemName: "checkmark").foregroundStyle(palette.accent)
                            }
                        }
                    }
                    .listRowBackground(palette.surface)
                }
            } header: {
                Text("Как доставлять")
            } footer: {
                if mode == .directOnly {
                    Text("Пока прямых каналов нет, такие сообщения будут ждать получателя и не уйдут через сервер. Bluetooth и Wi-Fi появятся следующими этапами.")
                }
            }

            Section {
                ForEach([ServerStorage.encryptedBackup, .relayOnly, .never], id: \.rawValue) { option in
                    Button { pick(storage: option) } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(Self.storageTitle(option)).foregroundStyle(palette.textPrimary)
                                Text(Self.storageHint(option))
                                    .font(.caption).foregroundStyle(palette.textSecondary)
                            }
                            Spacer()
                            if storage == option {
                                Image(systemName: "checkmark").foregroundStyle(palette.accent)
                            }
                        }
                    }
                    .listRowBackground(palette.surface)
                }
            } header: {
                Text("Что может сервер")
            } footer: {
                Text("Доставка и хранение — разные вещи. Сообщение может уйти через сервер и не остаться на нём, а может уйти напрямую и получить шифрованную копию для других ваших устройств.")
            }
        }
        .scrollContentBackground(.hidden)
        .background(palette.background.ignoresSafeArea())
        .navigationTitle(title.isEmpty ? peerId : title)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard !loaded else { return }
            let stored = await session.core.chatPolicy(peerId)
            mode = DeliveryMode(rawValue: stored.deliveryMode) ?? .auto
            storage = ServerStorage(rawValue: stored.serverStorage) ?? .encryptedBackup
            loaded = true
        }
    }

    private func pick(mode newMode: DeliveryMode) {
        mode = newMode
        save()
    }

    private func pick(storage newStorage: ServerStorage) {
        storage = newStorage
        save()
    }

    private func save() {
        Task { await messaging.policies?.setChatPolicy(peer: peerId, mode: mode, storage: storage) }
    }

    static func modeHint(_ mode: DeliveryMode) -> String {
        switch mode {
        case .auto: return "Aether сам выбирает лучший доступный путь"
        case .directOnly: return "Никогда не использовать сервер"
        case .directPlusBackup: return "Напрямую, плюс шифрованная копия на сервере"
        case .server: return "Только через выбранный сервер"
        }
    }

    static func storageTitle(_ s: ServerStorage) -> String {
        switch s {
        case .encryptedBackup: return "Хранить шифрованную копию"
        case .relayOnly: return "Только передать"
        case .never: return "Ничего не отдавать серверу"
        case .ask: return "Спрашивать каждый раз"
        }
    }

    static func storageHint(_ s: ServerStorage) -> String {
        switch s {
        case .encryptedBackup: return "Нужна для синхронизации между вашими устройствами"
        case .relayOnly: return "Сервер удаляет конверт сразу после доставки"
        case .never: return "Сообщения этого чата не попадут на сервер вообще"
        case .ask: return "Спрашивать перед каждой отправкой"
        }
    }
}
