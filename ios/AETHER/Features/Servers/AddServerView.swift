import SwiftUI

// Добавление пользовательского сервера: адрес → карточка найденного сервера →
// вход. Никаких списков-колонок и «гильдий»: сервер здесь инфраструктура,
// а не сущность, вокруг которой строится интерфейс.
struct AddServerView: View {
    @EnvironmentObject var session: Session
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss

    /// Куда вести после успешного входа. nil — просто закрыть.
    var onSignedIn: (() -> Void)?

    @State private var address = ""
    @State private var allowLocal = false
    @State private var busy = false
    @State private var error: String?
    @State private var found: ServerInfo?
    /// Одна шторка на экран: несколько .sheet на одном вью SwiftUI не тянет.
    enum Sheet: Identifiable {
        case auth(ServerRecord)
        case trust(info: ServerInfo, record: ServerRecord, old: ServerPin)

        var id: String {
            switch self {
            case .auth(let s): return "auth-" + s.id
            case .trust(let info, _, _): return "trust-" + info.serverId
            }
        }
    }
    @State private var sheet: Sheet?
    @FocusState private var addressFocused: Bool

    var body: some View {
        ScrollView {
            VStack(spacing: 22) {
                if let found {
                    serverCard(found)
                } else {
                    addressForm
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 28)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(palette.background.ignoresSafeArea())
        .navigationTitle("Добавить сервер")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $sheet) { which in
            switch which {
            case .auth(let record):
                NavigationStack {
                    ServerAuthView(server: record) {
                        sheet = nil
                        onSignedIn?()
                        dismiss()
                    }
                    .environmentObject(session)
                }
            case .trust(let info, let record, let old):
                NavigationStack {
                    ServerTrustAlertView(info: info, record: record, oldPin: old) {
                        sheet = nil
                        found = info
                    }
                }
            }
        }
    }

    // MARK: - Ввод адреса

    private var addressForm: some View {
        VStack(spacing: 18) {
            VStack(spacing: 8) {
                Text("Адрес сервера")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(palette.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)

                HStack(spacing: 12) {
                    Image(systemName: "network")
                        .foregroundStyle(palette.textSecondary).frame(width: 20)
                    TextField("chat.example.com", text: $address)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .focused($addressFocused)
                        .foregroundStyle(palette.textPrimary)
                        .onSubmit(discover)
                }
                .padding(.horizontal, 18).padding(.vertical, 15)
                .liquidGlass(Capsule(), interactive: false)

                Text("Можно ввести IP, домен или ссылку aether://")
                    .font(.caption)
                    .foregroundStyle(palette.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }

            if isLocalAddress {
                localToggle
            }

            if let error {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(palette.danger)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }

            Button(action: discover) {
                HStack {
                    if busy { ProgressView().tint(palette.onAccent) }
                    Text("Найти сервер").font(.headline)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 16)
                .background(palette.accent, in: Capsule())
                .foregroundStyle(palette.onAccent)
            }
            .buttonStyle(.squish)
            .disabled(busy || address.trimmingCharacters(in: .whitespaces).isEmpty)
            .opacity(busy || address.trimmingCharacters(in: .whitespaces).isEmpty ? 0.6 : 1)
        }
        .onAppear { addressFocused = true }
        #if DEBUG
        // Тестовый хук: в симуляторе нет способа надёжно ввести адрес с точками
        // (клавиатура отдаёт русскую раскладку), а проверять поток надо.
        //   SIMCTL_CHILD_AETHER_ADD_SERVER=192.168.1.57:8099
        .task {
            guard let preset = ProcessInfo.processInfo.environment["AETHER_ADD_SERVER"],
                  !preset.isEmpty, address.isEmpty else { return }
            address = preset
            allowLocal = isLocalAddress
            try? await Task.sleep(nanoseconds: 300_000_000)
            discover()
        }
        #endif
    }

    /// Локальный режим предлагается ТОЛЬКО для адресов из домашней сети и
    /// только явным переключателем. Для публичного домена его нет вовсе.
    private var localToggle: some View {
        VStack(alignment: .leading, spacing: 8) {
            Toggle(isOn: $allowLocal) {
                Text("Локальная сеть без шифрования канала")
                    .font(.system(size: 15))
                    .foregroundStyle(palette.textPrimary)
            }
            if allowLocal {
                Text("Содержимое сообщений останется зашифрованным сквозным шифрованием, но адреса, размеры и время будут видны в вашей сети. Проверка сертификата при этом не отключается — её просто нет, потому что нет TLS.")
                    .font(.caption)
                    .foregroundStyle(palette.danger)
            }
        }
        .padding(16)
        .background(palette.surface, in: RoundedRectangle(cornerRadius: 18))
    }

    private var isLocalAddress: Bool {
        let raw = address.trimmingCharacters(in: .whitespaces)
        guard !raw.isEmpty else { return false }
        let host = raw
            .replacingOccurrences(of: "https://", with: "")
            .replacingOccurrences(of: "http://", with: "")
            .replacingOccurrences(of: "aether://", with: "")
            .split(separator: "/").first.map(String.init) ?? raw
        let bare = host.split(separator: ":").first.map(String.init) ?? host
        return isPrivateHost(host: bare)
    }

    // MARK: - Карточка найденного сервера

    private func serverCard(_ info: ServerInfo) -> some View {
        VStack(spacing: 18) {
            VStack(spacing: 6) {
                Text(info.name)
                    .font(.system(size: 26, weight: .bold, design: .rounded))
                    .foregroundStyle(palette.textPrimary)
                Text("Сервер найден")
                    .font(.subheadline)
                    .foregroundStyle(palette.textSecondary)
            }
            .padding(.top, 8)

            VStack(spacing: 0) {
                row("Адрес", info.origin.replacingOccurrences(of: "https://", with: "")
                                        .replacingOccurrences(of: "http://", with: ""))
                divider
                row("Протокол", "Aether v\(info.protocolVersion)")
                divider
                row("Регистрация", registrationText(info))
                divider
                row("Шифрование", info.supportsE2ee ? "Поддерживается" : "НЕ поддерживается",
                    danger: !info.supportsE2ee)
                divider
                row("Перенос данных", info.supportsDataImport ? "Поддерживается" : "Не поддерживается")
                divider
                VStack(alignment: .leading, spacing: 6) {
                    Text("Отпечаток сервера")
                        .font(.system(size: 13))
                        .foregroundStyle(palette.textSecondary)
                    Text(formatFingerprint(fingerprintB64: info.fingerprintB64))
                        .font(.system(size: 13, design: .monospaced))
                        .foregroundStyle(palette.textPrimary)
                        .textSelection(.enabled)
                    Text("Сверьте его с владельцем сервера по другому каналу — это единственная защита от подмены при первом подключении.")
                        .font(.caption)
                        .foregroundStyle(palette.textSecondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
            }
            .background(palette.surface, in: RoundedRectangle(cornerRadius: 20))

            warnings(info)

            Button {
                sheet = .auth(ServerDirectory.shared.add(info))
            } label: {
                Text("Продолжить").font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
                    .background(palette.accent, in: Capsule())
                    .foregroundStyle(palette.onAccent)
            }
            .buttonStyle(.squish)

            Button("Другой адрес") {
                found = nil
                error = nil
            }
            .font(.system(size: 15))
            .foregroundStyle(palette.textSecondary)
        }
    }

    @ViewBuilder
    private func warnings(_ info: ServerInfo) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            if !info.officialClaim {
                label("Управляется третьей стороной. Aether не контролирует его администратора, хранилище и журналы.",
                      icon: "person.badge.shield.checkmark", tint: palette.textSecondary)
            }
            if info.cleartext {
                label("Соединение без TLS. Только для локальной сети.",
                      icon: "lock.open", tint: palette.danger)
            }
            if !info.endpointsMatchOrigin {
                label("Сервер назвал адреса на другом домене. Это возможный признак посредника — продолжайте, только если понимаете почему.",
                      icon: "exclamationmark.triangle.fill", tint: palette.danger)
            }
        }
    }

    private func label(_ text: String, icon: String, tint: Color) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: icon).foregroundStyle(tint).frame(width: 20)
            Text(text).font(.caption).foregroundStyle(tint)
            Spacer(minLength: 0)
        }
    }

    private var divider: some View {
        Rectangle().fill(palette.divider).frame(height: 0.5).padding(.leading, 16)
    }

    private func row(_ title: String, _ value: String, danger: Bool = false) -> some View {
        HStack {
            Text(title).font(.system(size: 15)).foregroundStyle(palette.textSecondary)
            Spacer()
            Text(value)
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(danger ? palette.danger : palette.textPrimary)
                .multilineTextAlignment(.trailing)
        }
        .padding(16)
    }

    private func registrationText(_ info: ServerInfo) -> String {
        switch RegistrationMode(wire: info.registrationMode) {
        case .open: return "Открыта"
        case .approval: return "Нужно подтверждение администратора"
        case .inviteOnly: return "Только по приглашению"
        case .closed: return "Закрыта"
        }
    }

    // MARK: - Действие

    private func discover() {
        error = nil
        busy = true
        let input = address.trimmingCharacters(in: .whitespaces)
        let cleartext = allowLocal
        Task {
            do {
                switch try await ServerDirectory.shared.inspect(input: input, allowCleartext: cleartext) {
                case .fresh(let info), .known(let info, _):
                    found = info
                case .identityChanged(let info, let record, let old):
                    sheet = .trust(info: info, record: record, old: old)
                }
            } catch let e as CoreError {
                error = describe(e)
            } catch {
                self.error = error.localizedDescription
            }
            busy = false
        }
    }

    private func describe(_ e: CoreError) -> String {
        switch e {
        case .Network:
            return isLocalAddress && !allowLocal
                ? "Сервер не отвечает по HTTPS. Если это сервер в вашей домашней сети, включите локальный режим ниже."
                : "Нет соединения с сервером. Проверьте адрес."
        case .BadInput(let msg): return msg
        case .Crypto(let msg): return msg
        case .Api(let status, _): return "Сервер ответил ошибкой \(status)"
        default: return "Не удалось получить сведения о сервере"
        }
    }
}
