import SwiftUI

// Онбординг + вход/регистрация. Пароль здесь — это и пароль аккаунта на сервере,
// и пароль резервной копии приватного ключа (шифрование в ядре). Ключи никогда
// не покидают устройство в открытом виде.
struct WelcomeView: View {
    @EnvironmentObject var session: Session
    @Environment(\.palette) private var palette

    enum Mode { case login, register }
    @State private var mode: Mode = .login
    @State private var userId = ""
    @State private var password = ""
    @State private var busy = false
    @State private var error: String?
    @State private var appeared = false

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

                    // Переключатель Вход/Регистрация.
                    Picker("", selection: $mode) {
                        Text("Вход").tag(Mode.login)
                        Text("Регистрация").tag(Mode.register)
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal, 32)

                    VStack(spacing: 14) {
                        field(icon: "at", placeholder: "Имя пользователя", text: $userId)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        secureField(icon: "lock", placeholder: mode == .register ? "Придумайте пароль" : "Пароль", text: $password)

                        if let error {
                            Text(error)
                                .font(.footnote)
                                .foregroundStyle(palette.danger)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }

                        if mode == .register {
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
                        .padding(.vertical, 15)
                        .background(palette.accent, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .foregroundStyle(palette.onAccent)
                    }
                    .buttonStyle(.squish)
                    .disabled(busy || userId.isEmpty || password.count < 4)
                    .opacity(busy || userId.isEmpty || password.count < 4 ? 0.6 : 1)
                    .padding(.horizontal, 28)

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

    private func field(icon: String, placeholder: String, text: Binding<String>) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon).foregroundStyle(palette.textSecondary).frame(width: 20)
            TextField(placeholder, text: text)
                .foregroundStyle(palette.textPrimary)
        }
        .padding(.horizontal, 16).padding(.vertical, 14)
        .liquidGlass(cornerRadius: 14, interactive: false)
    }

    private func secureField(icon: String, placeholder: String, text: Binding<String>) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon).foregroundStyle(palette.textSecondary).frame(width: 20)
            SecureField(placeholder, text: text)
                .foregroundStyle(palette.textPrimary)
        }
        .padding(.horizontal, 16).padding(.vertical, 14)
        .liquidGlass(cornerRadius: 14, interactive: false)
    }

    private func submit() {
        error = nil
        busy = true
        let id = userId.trimmingCharacters(in: .whitespaces).lowercased()
        let pass = password
        Task {
            do {
                if mode == .login {
                    try await session.login(userId: id, password: pass)
                } else {
                    try await session.register(userId: id, password: pass)
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
        case .Api(let status, let msg):
            if status == 401 || status == 403 { return "Неверное имя пользователя или пароль" }
            if status == 409 { return "Это имя уже занято" }
            return "Ошибка сервера (\(status)): \(msg)"
        case .Network(let msg): return "Нет соединения: \(msg)"
        case .BadInput(let msg): return msg
        case .Crypto(let msg): return "Ошибка ключа: \(msg)"
        case .Store(let msg): return msg
        case .Ws(let msg): return msg
        }
    }
}
