import SwiftUI

// Вход и регистрация на конкретном сервере. Что именно предлагается —
// решает политика сервера, но проверяет её всё равно сервер: клиент лишь
// рисует подходящую кнопку.
struct ServerAuthView: View {
    let server: ServerRecord
    var onDone: () -> Void

    @EnvironmentObject var session: Session
    @Environment(\.palette) private var palette

    private enum Mode { case login, register }
    @State private var mode: Mode = .login
    @State private var userId = ""
    @State private var password = ""
    @State private var passwordRepeat = ""
    @State private var inviteCode = ""
    @State private var totpCode = ""
    @State private var needsTotp = false
    @State private var busy = false
    @State private var error: String?

    private var isCustom: Bool { !server.isOfficial }
    private var canRegister: Bool {
        server.registrationMode == .open || server.registrationMode == .inviteOnly
            || server.registrationMode == .approval
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                header

                VStack(spacing: 14) {
                    field(icon: "at", placeholder: "Имя пользователя", text: $userId)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    secureField(icon: "lock",
                                placeholder: mode == .register ? "Придумайте пароль" : "Пароль",
                                text: $password)
                    if mode == .register {
                        secureField(icon: "lock.rotation", placeholder: "Повторите пароль",
                                    text: $passwordRepeat)
                    }
                    if mode == .register, needsInviteField {
                        field(icon: "ticket", placeholder: invitePlaceholder, text: $inviteCode)
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                    }
                    if needsTotp {
                        field(icon: "lock.shield", placeholder: "Код 2FA", text: $totpCode)
                            .keyboardType(.numberPad)
                    }

                    if let error {
                        Text(error)
                            .font(.footnote).foregroundStyle(palette.danger)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }

                    if mode == .register {
                        Text("Пароль шифрует резервную копию вашего приватного ключа. Забудете — доступ к переписке восстановить нельзя.")
                            .font(.caption).foregroundStyle(palette.textSecondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    policyNote
                }

                Button(action: submit) {
                    HStack {
                        if busy { ProgressView().tint(palette.onAccent) }
                        Text(mode == .login ? "Войти" : "Создать аккаунт").font(.headline)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
                    .background(palette.accent, in: Capsule())
                    .foregroundStyle(palette.onAccent)
                }
                .buttonStyle(.squish)
                .disabled(!canSubmit)
                .opacity(canSubmit ? 1 : 0.6)

                if canRegister {
                    modeSwitch
                }
            }
            .padding(.horizontal, 26)
            .padding(.vertical, 24)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(palette.background.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
    }

    private var header: some View {
        VStack(spacing: 6) {
            Text(server.displayName)
                .font(.system(size: 26, weight: .bold, design: .rounded))
                .foregroundStyle(palette.textPrimary)
            Text(server.isOfficial ? "Официальный сервер" : "Пользовательский сервер")
                .font(.subheadline).foregroundStyle(palette.textSecondary)
            if isCustom {
                Text(server.hostLabel)
                    .font(.caption).foregroundStyle(palette.textSecondary)
            }
        }
    }

    /// Нижний переключатель Вход ●───○ Регистрация.
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
                        Circle().fill(palette.accent)
                            .frame(width: 24, height: 24).padding(3)
                    }
            }
            .buttonStyle(.plain)
            Text("Регистрация")
                .foregroundStyle(mode == .register ? palette.textPrimary : palette.textSecondary)
        }
        .font(.system(size: 15, weight: .medium))
    }

    @ViewBuilder
    private var policyNote: some View {
        switch server.registrationMode {
        case .closed where mode == .register:
            note("Регистрация на этом сервере отключена. Обратитесь к администратору.")
        case .approval where mode == .register:
            note("Сервер принимает новых пользователей по подтверждению администратора. Если у вас есть код приглашения или код владельца — введите его, иначе дождитесь, когда администратор откроет вам доступ.")
        case .inviteOnly where mode == .register:
            note("Для регистрации нужен код приглашения от администратора сервера.")
        default:
            EmptyView()
        }
    }

    private func note(_ text: String) -> some View {
        Text(text)
            .font(.caption).foregroundStyle(palette.textSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var needsInviteField: Bool {
        server.registrationMode == .inviteOnly || server.registrationMode == .approval
    }

    private var invitePlaceholder: String {
        server.registrationMode == .approval ? "Код приглашения или владельца" : "Код приглашения"
    }

    private var canSubmit: Bool {
        guard !busy, !userId.trimmingCharacters(in: .whitespaces).isEmpty else { return false }
        if mode == .login { return password.count >= 4 }
        // На регистрации порог тот же, что у сервера: иначе кнопка активна,
        // а сервер отвечает отказом — так было до мультисерверности.
        guard password.count >= 8, password == passwordRepeat else { return false }
        if server.registrationMode == .closed { return false }
        if server.registrationMode == .inviteOnly && inviteCode.isEmpty { return false }
        return true
    }

    private func field(icon: String, placeholder: String, text: Binding<String>) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon).foregroundStyle(palette.textSecondary).frame(width: 20)
            TextField(placeholder, text: text).foregroundStyle(palette.textPrimary)
        }
        .padding(.horizontal, 18).padding(.vertical, 15)
        .liquidGlass(Capsule(), interactive: false)
    }

    private func secureField(icon: String, placeholder: String, text: Binding<String>) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon).foregroundStyle(palette.textSecondary).frame(width: 20)
            SecureField(placeholder, text: text).foregroundStyle(palette.textPrimary)
        }
        .padding(.horizontal, 18).padding(.vertical, 15)
        .liquidGlass(Capsule(), interactive: false)
    }

    private func submit() {
        error = nil
        busy = true
        let id = userId.trimmingCharacters(in: .whitespaces).lowercased()
        let pass = password
        let code = inviteCode.trimmingCharacters(in: .whitespaces)
        let totp = needsTotp ? totpCode.trimmingCharacters(in: .whitespaces) : nil
        Task {
            do {
                if mode == .login {
                    try await session.login(on: server, userId: id, password: pass, totpCode: totp)
                } else {
                    try await session.register(on: server, userId: id, password: pass,
                                               inviteCode: code.isEmpty ? nil : code)
                }
                onDone()
            } catch is Session.TotpRequired {
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

    /// Текст сервера показывается отдельно и подписанным: пользовательский
    /// сервер — недоверенная сторона, его detail нельзя выдавать за системное
    /// сообщение приложения.
    private func describe(_ e: CoreError) -> String {
        guard case .Api(let status, let msg) = e else {
            if case .Network = e { return "Нет соединения с \(server.displayName)" }
            if case .BadInput(let m) = e { return m }
            if case .Crypto(let m) = e { return m }
            return "Ошибка"
        }
        switch msg {
        case let m where m.contains("registration_closed"):
            return "Регистрация на этом сервере отключена"
        case let m where m.contains("invite_required"):
            return "Нужен код приглашения"
        case let m where m.contains("invite_invalid"):
            return "Код приглашения недействителен или уже использован"
        case let m where m.contains("approval_required"):
            return "Сервер принимает новых пользователей только по подтверждению администратора"
        case let m where m.contains("account_disabled"):
            return "Аккаунт заблокирован администратором сервера"
        case let m where m.contains("Username already taken"):
            return "Это имя уже занято"
        default:
            if status == 401 || status == 403 { return "Неверное имя пользователя или пароль" }
            return "Сервер ответил ошибкой \(status)"
        }
    }
}
