import SwiftUI

// Переключатель пространств: шторка из шапки списка чатов.
//
// Намеренно НЕ колонка иконок слева. Главная сущность Aether — чаты и люди;
// сервер это инфраструктурный уровень, к которому обращаются изредка. Поэтому
// он живёт за одним нажатием на заголовок, а не занимает место на экране.
struct SpaceSwitcher: View {
    @EnvironmentObject var session: Session
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss

    @StateObject private var registry = ServerRegistry.shared
    /// Одна шторка на экран: несколько .sheet на одном вью SwiftUI не тянет.
    enum Sheet: Identifiable {
        case addServer
        case auth(ServerRecord)

        var id: String {
            switch self {
            case .addServer: return "add"
            case .auth(let s): return "auth-" + s.id
            }
        }
    }
    @State private var sheet: Sheet?
    @State private var showManage = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 0) {
                    if let official = registry.official {
                        section(for: official, title: nil)
                    }

                    let custom = registry.ordered.filter { !$0.isOfficial }
                    if !custom.isEmpty {
                        header("Ваши серверы")
                        ForEach(custom) { server in
                            section(for: server, title: nil)
                        }
                    }

                    Rectangle().fill(palette.divider).frame(height: 0.5)
                        .padding(.vertical, 8)

                    action("Добавить сервер", icon: "plus") { sheet = .addServer }
                    action("Управление серверами", icon: "gearshape") { showManage = true }
                }
                .padding(.vertical, 8)
            }
            .background(palette.background.ignoresSafeArea())
            .navigationTitle("Пространство")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Готово") { dismiss() }
                }
            }
            .navigationDestination(isPresented: $showManage) {
                ServersListView().environmentObject(session)
            }
        }
        .sheet(item: $sheet) { which in
            switch which {
            case .addServer:
                NavigationStack {
                    AddServerView { dismiss() }.environmentObject(session)
                }
            case .auth(let record):
                NavigationStack {
                    ServerAuthView(server: record) {
                        sheet = nil
                        dismiss()
                    }
                    .environmentObject(session)
                }
            }
        }
    }

    // MARK: - Строки

    @ViewBuilder
    private func section(for server: ServerRecord, title: String?) -> some View {
        if server.accounts.isEmpty {
            // Сервер добавлен, но аккаунта на нём ещё нет — ведём на вход.
            row(server: server, account: nil, active: false) { sheet = .auth(server) }
        } else {
            ForEach(server.accounts) { account in
                let isActive = registry.activeSpace == SpaceRef(serverId: server.id,
                                                                userId: account.userId)
                row(server: server, account: account, active: isActive) {
                    guard !isActive else { dismiss(); return }
                    Task {
                        await session.switchSpace(to: SpaceRef(serverId: server.id,
                                                               userId: account.userId))
                        dismiss()
                    }
                }
            }
        }
    }

    private func row(server: ServerRecord, account: AccountRef?, active: Bool,
                     action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: server.isOfficial ? "cloud.fill" : "server.rack")
                    .font(.system(size: 17))
                    .foregroundStyle(active ? palette.accent : palette.textSecondary)
                    .frame(width: 28)

                VStack(alignment: .leading, spacing: 2) {
                    Text(server.displayName)
                        .font(.system(size: 16, weight: active ? .semibold : .regular))
                        .foregroundStyle(palette.textPrimary)
                    Text(subtitle(server: server, account: account))
                        .font(.caption)
                        .foregroundStyle(server.identityAlert != nil ? palette.danger
                                                                     : palette.textSecondary)
                }
                Spacer()
                if server.identityAlert != nil {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(palette.danger)
                } else if active {
                    Image(systemName: "checkmark").foregroundStyle(palette.accent)
                } else if account == nil {
                    Text("Войти").font(.caption).foregroundStyle(palette.accent)
                }
            }
            .padding(.horizontal, 20).padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func subtitle(server: ServerRecord, account: AccountRef?) -> String {
        if server.identityAlert != nil { return "Идентификатор сервера изменился" }
        var parts: [String] = []
        parts.append(server.isOfficial ? "Официальный" : server.hostLabel)
        if let account { parts.append("@" + account.userId) }
        return parts.joined(separator: " · ")
    }

    private func header(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 13, weight: .medium))
            .foregroundStyle(palette.textSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 20)
            .padding(.top, 14).padding(.bottom, 4)
    }

    private func action(_ title: String, icon: String, run: @escaping () -> Void) -> some View {
        Button(action: run) {
            HStack(spacing: 12) {
                Image(systemName: icon).font(.system(size: 16))
                    .foregroundStyle(palette.accent).frame(width: 28)
                Text(title).font(.system(size: 16)).foregroundStyle(palette.textPrimary)
                Spacer()
            }
            .padding(.horizontal, 20).padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
