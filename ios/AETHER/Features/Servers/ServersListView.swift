import SwiftUI

// «Ваши серверы»: управление списком. Точка входа — переключатель пространств
// и раздел настроек.
struct ServersListView: View {
    @EnvironmentObject var session: Session
    @Environment(\.palette) private var palette

    @StateObject private var registry = ServerRegistry.shared
    @State private var showAddServer = false
    @State private var pendingRemoval: ServerRecord?

    var body: some View {
        List {
            Section {
                ForEach(registry.ordered) { server in
                    NavigationLink {
                        ServerDetailView(serverId: server.id).environmentObject(session)
                    } label: {
                        row(server)
                    }
                    .listRowBackground(palette.surface)
                    .swipeActions(edge: .trailing) {
                        if !server.isOfficial {
                            Button(role: .destructive) { pendingRemoval = server } label: {
                                Label("Удалить", systemImage: "trash")
                            }
                        }
                    }
                }
            } footer: {
                Text("Серверы независимы друг от друга: у каждого своя учётная запись, своя переписка и свои ключи. Ничего не переносится между ними без вашего явного разрешения.")
            }

            Section {
                Button {
                    showAddServer = true
                } label: {
                    Label("Добавить сервер", systemImage: "plus")
                }
                .listRowBackground(palette.surface)
            }
        }
        .scrollContentBackground(.hidden)
        .background(palette.background.ignoresSafeArea())
        .navigationTitle("Ваши серверы")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showAddServer) {
            NavigationStack { AddServerView().environmentObject(session) }
        }
        .alert("Удалить сервер?", isPresented: Binding(
            get: { pendingRemoval != nil },
            set: { if !$0 { pendingRemoval = nil } }
        ), presenting: pendingRemoval) { server in
            Button("Удалить", role: .destructive) {
                registry.remove(serverId: server.id)
                pendingRemoval = nil
            }
            Button("Отмена", role: .cancel) { pendingRemoval = nil }
        } message: { server in
            Text("Локальная переписка и ключи для «\(server.displayName)» будут удалены с этого устройства. На самом сервере данные останутся — Aether не управляет чужой инфраструктурой.")
        }
    }

    private func row(_ server: ServerRecord) -> some View {
        HStack(spacing: 12) {
            Image(systemName: server.isOfficial ? "cloud.fill" : "server.rack")
                .foregroundStyle(palette.accent).frame(width: 26)
            VStack(alignment: .leading, spacing: 2) {
                Text(server.displayName).foregroundStyle(palette.textPrimary)
                Text(server.isOfficial ? "Официальный" : server.hostLabel)
                    .font(.caption).foregroundStyle(palette.textSecondary)
            }
            Spacer()
            statusDot(server)
        }
    }

    @ViewBuilder
    private func statusDot(_ server: ServerRecord) -> some View {
        if server.identityAlert != nil {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(palette.danger)
        } else if registry.activeSpace?.serverId == server.id {
            Text("Подключён").font(.caption).foregroundStyle(palette.accent)
        } else if server.accounts.isEmpty {
            Text("Нет аккаунта").font(.caption).foregroundStyle(palette.textSecondary)
        }
    }
}

// Карточка сервера: чем он является, чем подтверждается его личность,
// как он подключён.
struct ServerDetailView: View {
    let serverId: String

    @EnvironmentObject var session: Session
    @Environment(\.palette) private var palette
    @StateObject private var registry = ServerRegistry.shared
    @State private var renaming = ""
    @State private var checking = false

    private var server: ServerRecord? { registry.server(serverId) }

    var body: some View {
        Form {
            if let server {
                Section("Название") {
                    TextField("Название", text: Binding(
                        get: { renaming.isEmpty ? server.displayName : renaming },
                        set: { renaming = $0 }
                    ))
                    .onSubmit {
                        let name = renaming.trimmingCharacters(in: .whitespaces)
                        guard !name.isEmpty else { return }
                        registry.update(serverId) { $0.displayName = name }
                    }
                    .listRowBackground(palette.surface)
                }

                Section("О сервере") {
                    detail("Адрес", server.hostLabel)
                    detail("Сервер назвал себя", server.declaredName)
                    detail("Протокол", "v\(server.protocolVersion)")
                    detail("Регистрация", modeText(server.registrationMode))
                    detail("Транспорт", server.transport == .tls
                           ? "HTTPS с проверкой сертификата"
                           : "Локальная сеть без TLS")
                    detail("Управляется", server.isOfficial ? "Aether" : "третьей стороной")
                }
                .listRowBackground(palette.surface)

                if let pin = server.pin {
                    Section {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(formatFingerprint(fingerprintB64: pin.fingerprintB64))
                                .font(.system(size: 13, design: .monospaced))
                                .foregroundStyle(palette.textPrimary)
                                .textSelection(.enabled)
                            Text("Запомнен \(Self.dateText(pin.firstSeenAt)). Сверьте его с владельцем сервера, если подключаетесь впервые.")
                                .font(.caption).foregroundStyle(palette.textSecondary)
                        }
                        if !pin.changes.isEmpty {
                            ForEach(Array(pin.changes.enumerated()), id: \.offset) { _, change in
                                Text("Идентификатор менялся \(Self.dateText(change.at))")
                                    .font(.caption).foregroundStyle(palette.danger)
                            }
                        }
                        Button {
                            checking = true
                            Task {
                                await ServerDirectory.shared.refresh(serverId: serverId)
                                checking = false
                            }
                        } label: {
                            HStack {
                                Text("Проверить сервер сейчас")
                                if checking { Spacer(); ProgressView() }
                            }
                        }
                    } header: {
                        Text("Отпечаток")
                    }
                    .listRowBackground(palette.surface)
                }

                if !server.accounts.isEmpty {
                    Section("Аккаунты") {
                        ForEach(server.accounts) { account in
                            HStack {
                                Text("@" + account.userId).foregroundStyle(palette.textPrimary)
                                Spacer()
                                if registry.activeSpace == SpaceRef(serverId: serverId,
                                                                    userId: account.userId) {
                                    Text("активен").font(.caption).foregroundStyle(palette.accent)
                                }
                            }
                        }
                    }
                    .listRowBackground(palette.surface)
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(palette.background.ignoresSafeArea())
        .navigationTitle(server?.displayName ?? "Сервер")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func detail(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title).foregroundStyle(palette.textSecondary)
            Spacer()
            Text(value).foregroundStyle(palette.textPrimary)
                .multilineTextAlignment(.trailing)
        }
    }

    private func modeText(_ mode: RegistrationMode) -> String {
        switch mode {
        case .open: return "Открыта"
        case .approval: return "По подтверждению"
        case .inviteOnly: return "По приглашению"
        case .closed: return "Закрыта"
        }
    }

    private static func dateText(_ date: Date) -> String {
        let f = DateFormatter()
        f.dateStyle = .medium
        f.timeStyle = .none
        f.locale = Locale(identifier: "ru_RU")
        return f.string(from: date)
    }
}
