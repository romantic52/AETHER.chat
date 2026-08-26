import SwiftUI

// Онбординг + вход/регистрация. Пароль здесь — это и пароль аккаунта на сервере,
// и пароль резервной копии приватного ключа (шифрование в ядре). Ключи никогда
// не покидают устройство в открытом виде.
struct WelcomeView: View {
    @EnvironmentObject var session: Session
    @Environment(\.palette) private var palette

    enum Mode { case login, register }
    /// Выбор инфраструктуры, а не режима приложения: официальные серверы
    /// Aether или сервер, который пользователь поднял сам.
    enum Infra { case official, custom }

    @State private var mode: Mode = .login
    @State private var infra: Infra = .official
    /// Одна шторка на экран.
    ///
    /// Несколько .sheet на ОДНОМ вью SwiftUI не обслуживает: срабатывает не та,
    /// что просили, — здесь строка сервера просто ничего не открывала. Поэтому
    /// все шторки экрана сведены в одно состояние.
    enum Sheet: Identifiable {
        case pairing
        case addServer
        case auth(ServerRecord)

        var id: String {
            switch self {
            case .pairing: return "pairing"
            case .addServer: return "add"
            case .auth(let server): return "auth-" + server.id
            }
        }
    }
    @State private var sheet: Sheet?
    @StateObject private var registry = ServerRegistry.shared
    @State private var userId = ""
    @State private var password = ""
    @State private var busy = false
        @State private var error: String?
    @State private var appeared = false
    @State private var needsTotp = false
    @State private var totpCode = ""

    var body: some View {
        GeometryReader { geo in
            ScrollView {
                VStack(spacing: 28) {
                    Spacer(minLength: 8)
                    AetherLogo(size: 104)
                        .scaleEffect(appeared ? 1 : 0.82)
                        .opacity(appeared ? 1 : 0)
                        .offset(y: appeared ? 0 : 8)
                    VStack(spacing: 6) {
                        Text("Æther")
                            .font(.system(size: 34, weight: .bold, design: .rounded))
                            .foregroundStyle(palette.textPrimary)
                        Text("Защищённый мессенджер")
                            .font(.subheadline)
                            .foregroundStyle(palette.textSecondary)
                    }
                    .opacity(appeared ? 1 : 0)

                    Picker("", selection: $infra) {
                        Text("Наши серверы").tag(Infra.official)
                        Text("Пользовательские").tag(Infra.custom)
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal, 24)

                    if infra == .custom {
                        customServers
                    } else {
                    VStack(spacing: 14) {
                        field(icon: "at", placeholder: "Имя пользователя", text: $userId)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        secureField(icon: "lock", placeholder: mode == .register ? "Придумайте пароль" : "Пароль", text: $password)

                        if needsTotp {
                            field(icon: "lock.shield", placeholder: "Код 2FA из аутентификатора", text: $totpCode)
                                .keyboardType(.numberPad)
                        }

                        if let error {
                            Text(error)
                                .font(.footnote)
                                .foregroundStyle(palette.danger)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }

                        if mode == .register {
                            Text("Не короче \(Self.minPasswordLength) символов. Длина важнее спецсимволов: «Password1!» подбирается словарём так же легко.")
                                .font(.caption)
                                .foregroundStyle(palette.textSecondary)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            Text("Пароль шифрует резервную копию вашего приватного ключа. Забудете — доступ к переписке восстановить нельзя.")
                                .font(.caption)
                                .foregroundStyle(palette.textSecondary)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                    .padding(.horizontal, 28)

                    Button(action: submit) {
                        HStack {
                            if busy { ProgressView().tint(palette.onAccent) }
                            Text(mode == .login ? "Войти" : "Создать аккаунт")
                                .font(.headline)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                        .background(palette.accent, in: Capsule())
                        .foregroundStyle(palette.onAccent)
                    }
                    .buttonStyle(.squish)
                    .disabled(busy || !canSubmit)
                    .opacity(busy || !canSubmit ? 0.6 : 1)
                    .padding(.horizontal, 28)

                    modeSwitch
                        .padding(.top, 2)

                    // Вход по QR: устройство ещё не вошло, поэтому точка входа
                    // именно здесь. Пароль при этом не нужен — подтверждение с
                    // доверенного устройства само по себе является доказательством.
                    Button {
                        sheet = .pairing
                    } label: {
                        Label("Войти по QR с другого устройства", systemImage: "qrcode")
                            .font(.system(size: 15, weight: .medium))
                            .foregroundStyle(palette.textSecondary)
                    }
                    .buttonStyle(.plain)
                    .padding(.top, 2)
                    }

                    Spacer(minLength: 8)
                }
                .frame(maxWidth: .infinity)
                .frame(minHeight: geo.size.height)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .onAppear {
            withAnimation(.easeOut(duration: 0.45)) { appeared = true }
        }
        .sheet(item: $sheet) { which in
            switch which {
            case .pairing:
                PairShowQRView().environmentObject(session)
            case .addServer:
                NavigationStack { AddServerView().environmentObject(session) }
            case .auth(let record):
                NavigationStack {
                    ServerAuthView(server: record) { sheet = nil }
                        .environmentObject(session)
                }
            }
        }
        #if DEBUG
        .task {
            // Тестовый хук: AETHER_AUTOLOGIN=user:pass → автоматический вход (только DEBUG).
            if let creds = ProcessInfo.processInfo.environment["AETHER_AUTOLOGIN"],
               creds.contains(":"), !busy {
                let parts = creds.split(separator: ":", maxSplits: 1)
                userId = String(parts[0]); password = String(parts[1])
                submit()
            }
        }
        #endif
    }

    /// Нижний переключатель Вход ●────○ Регистрация.
    private var modeSwitch: some View {
        HStack(spacing: 14) {
            Text("Вход")
                .foregroundStyle(mode == .login ? palette.textPrimary : palette.textSecondary)
            Button {
                withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) {
                    mode = mode == .login ? .register : .login
                    error = nil
                }
            } label: {
                Capsule()
                    .fill(palette.surfaceElevated)
                    .frame(width: 56, height: 30)
                    .overlay(alignment: mode == .login ? .leading : .trailing) {
                        Circle().fill(palette.accent).frame(width: 24, height: 24).padding(3)
                    }
            }
            .buttonStyle(.plain)
            Text("Регистрация")
                .foregroundStyle(mode == .register ? palette.textPrimary : palette.textSecondary)
        }
        .font(.system(size: 15, weight: .medium))
    }

    /// Вкладка «Пользовательские»: сохранённые серверы и добавление нового.
    private var customServers: some View {
        VStack(spacing: 14) {
            let saved = registry.ordered.filter { !$0.isOfficial }
            if saved.isEmpty {
                VStack(spacing: 8) {
                    Text("Свои серверы")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(palette.textPrimary)
                    Text("Подключитесь к серверу Aether, который подняли вы или ваши знакомые. Он независим: своя учётная запись, своя переписка, свои правила.")
                        .font(.caption)
                        .multilineTextAlignment(.center)
                        .foregroundStyle(palette.textSecondary)
                }
                .padding(.vertical, 8)
            } else {
                VStack(spacing: 0) {
                    ForEach(saved) { server in
                        Button { sheet = .auth(server) } label: {
                            HStack(spacing: 12) {
                                Image(systemName: "server.rack")
                                    .foregroundStyle(palette.accent).frame(width: 24)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(server.displayName)
                                        .font(.system(size: 16, weight: .medium))
                                        .foregroundStyle(palette.textPrimary)
                                    Text(server.hostLabel)
                                        .font(.caption).foregroundStyle(palette.textSecondary)
                                }
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .font(.system(size: 13, weight: .semibold))
                                    .foregroundStyle(palette.textSecondary)
                            }
                            .padding(16)
                            // Без этого нажимается только текст: середина
                            // строки прозрачна, и тап по ней проваливается.
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        if server.id != saved.last?.id {
                            Rectangle().fill(palette.divider).frame(height: 0.5).padding(.leading, 52)
                        }
                    }
                }
                .background(palette.surface, in: RoundedRectangle(cornerRadius: 18))
            }

            Button { sheet = .addServer } label: {
                Label("Добавить сервер", systemImage: "plus")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
                    .background(palette.accent, in: Capsule())
                    .foregroundStyle(palette.onAccent)
            }
            .buttonStyle(.squish)
        }
        .padding(.horizontal, 24)
    }

    private func field(icon: String, placeholder: String, text: Binding<String>) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon).foregroundStyle(palette.textSecondary).frame(width: 20)
            TextField(placeholder, text: text)
                .foregroundStyle(palette.textPrimary)
        }
        .padding(.horizontal, 18).padding(.vertical, 15)
        .liquidGlass(Capsule(), interactive: false)
    }

    private func secureField(icon: String, placeholder: String, text: Binding<String>) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon).foregroundStyle(palette.textSecondary).frame(width: 20)
            SecureField(placeholder, text: text)
                .foregroundStyle(palette.textPrimary)
        }
        .padding(.horizontal, 18).padding(.vertical, 15)
        .liquidGlass(Capsule(), interactive: false)
    }

    /// Порог совпадает с серверным. Раньше кнопка оживала на четырёх символах,
    /// а сервер требовал восемь — человек жал «Создать аккаунт» и получал отказ.
    private var canSubmit: Bool {
        guard !userId.trimmingCharacters(in: .whitespaces).isEmpty else { return false }
        // На входе длину не проверяем: у существующих учётных записей пароль
        // может быть короче нынешних требований, и это не их вина.
        return mode == .login ? !password.isEmpty : password.count >= Self.minPasswordLength
    }

    static let minPasswordLength = 10

    private func submit() {
        error = nil
        busy = true
        let id = userId.trimmingCharacters(in: .whitespaces).lowercased()
        let pass = password
        let code = needsTotp ? totpCode.trimmingCharacters(in: .whitespaces) : nil
        Task {
            do {
                if mode == .login {
                    try await session.login(userId: id, password: pass, totpCode: code)
                } else {
                    try await session.register(userId: id, password: pass)
                }
            } catch is Session.TotpRequired {
                // Пароль верный — просим код и повторяем submit с ним.
                needsTotp = true
                error = "Введите код из приложения-аутентификатора"
            } catch let e as CoreError {
                error = describe(e)
            } catch {
                self.error = error.localizedDescription
            }
            busy = false
        }
    }

    /// Человеческий текст для отказов по паролю. Сервер отдаёт код, а не
    /// готовую фразу: перевод — дело клиента.
    static func passwordProblem(_ message: String) -> String? {
        if message.contains("password_too_short") {
            return "Пароль слишком короткий: нужно не меньше \(minPasswordLength) символов"
        }
        if message.contains("password_too_common") { return "Такой пароль слишком часто встречается" }
        if message.contains("password_too_simple") { return "Пароль слишком предсказуем" }
        if message.contains("password_contains_username") { return "Пароль не должен содержать имя пользователя" }
        return nil
    }

    private func describe(_ e: CoreError) -> String {
        switch e {
        case .Api(let status, let msg):
            if status == 401 || status == 403 { return "Неверное имя пользователя или пароль" }
            if status == 409 { return "Это имя уже занято" }
            if let reason = Self.passwordProblem(msg) { return reason }
            return "Ошибка сервера (\(status)): \(msg)"
        case .Network(let msg): return "Нет соединения: \(msg)"
        case .BadInput(let msg): return msg
        case .Crypto(let msg): return "Ошибка ключа: \(msg)"
        case .Store(let msg): return msg
        case .Ws(let msg): return msg
        }
    }
}
