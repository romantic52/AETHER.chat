import SwiftUI

/// Вход / регистрация. Поля на стекле, переключатель режима, кнопка действия.
/// Логику делегирует в `Session` (тот же флоу, что Android: ключи → ядро → relay).
struct AuthView: View {
    @Environment(Session.self) private var session

    @State private var isRegister = false
    @State private var username = ""
    @State private var password = ""
    @State private var showServer = false
    @State private var serverDraft = AetherServer.default

    private var busy: Bool { session.phase == .authenticating }
    private var canSubmit: Bool {
        !username.trimmingCharacters(in: .whitespaces).isEmpty && password.count >= 4 && !busy
    }

    var body: some View {
        VStack(spacing: 0) {
            header

            ScrollView {
                VStack(spacing: 18) {
                    modeSwitch

                    GlassGroup(spacing: 12) {
                        VStack(spacing: 12) {
                            GlassField(systemImage: "at", placeholder: "Имя пользователя", text: $username)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                            GlassField(systemImage: "lock", placeholder: "Пароль", text: $password, secure: true)
                        }
                    }

                    if let err = session.errorMessage {
                        Text(err)
                            .font(.system(size: 13))
                            .foregroundStyle(.red.opacity(0.9))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .transition(.opacity)
                    }

                    Button {
                        if isRegister {
                            session.register(userId: username, password: password)
                        } else {
                            session.login(userId: username, password: password)
                        }
                    } label: {
                        if busy {
                            ProgressView().tint(.black)
                        } else {
                            Text(isRegister ? "Создать аккаунт" : "Войти")
                        }
                    }
                    .buttonStyle(.glassProminent)
                    .disabled(!canSubmit)
                    .opacity(canSubmit ? 1 : 0.5)

                    serverRow
                }
                .padding(.horizontal, 24)
                .padding(.top, 8)
            }
        }
        .animation(.spring(duration: 0.3), value: session.errorMessage)
        .animation(.spring(duration: 0.3), value: isRegister)
    }

    private var header: some View {
        HStack {
            Button {
                session.phase = .welcome
            } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Brand.textPrimary)
                    .frame(width: 40, height: 40)
                    .liquidGlass(in: Circle())
            }
            Spacer()
            Text(isRegister ? "Регистрация" : "Вход")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(Brand.textPrimary)
            Spacer()
            Color.clear.frame(width: 40, height: 40)
        }
        .padding(.horizontal, 20)
        .padding(.top, 8)
        .padding(.bottom, 16)
    }

    private var modeSwitch: some View {
        HStack(spacing: 0) {
            segment("Войти", active: !isRegister) { isRegister = false }
            segment("Создать", active: isRegister) { isRegister = true }
        }
        .padding(4)
        .liquidGlass(in: Capsule())
    }

    private func segment(_ title: String, active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(active ? Color.black : Brand.textSecondary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
                .background {
                    if active {
                        Capsule().fill(.white)
                    }
                }
        }
        .animation(.spring(duration: 0.25), value: active)
    }

    private var serverRow: some View {
        VStack(spacing: 10) {
            Button {
                withAnimation { showServer.toggle() }
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "server.rack").font(.system(size: 12))
                    Text(showServer ? "Скрыть сервер" : "Сервер")
                        .font(.system(size: 13))
                }
                .foregroundStyle(Brand.textMuted)
            }

            if showServer {
                GlassField(systemImage: "link", placeholder: "https://…", text: $serverDraft)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .onChange(of: serverDraft) { _, new in session.server = new }
                    .transition(.opacity)
            }
        }
        .padding(.top, 4)
    }
}

/// Поле ввода на стекле с иконкой.
struct GlassField: View {
    var systemImage: String
    var placeholder: String
    @Binding var text: String
    var secure: Bool = false

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 15))
                .foregroundStyle(Brand.textMuted)
                .frame(width: 20)
            Group {
                if secure {
                    SecureField(placeholder, text: $text)
                } else {
                    TextField(placeholder, text: $text)
                }
            }
            .font(.system(size: 16))
            .foregroundStyle(Brand.textPrimary)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 15)
        .liquidGlass(in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

#Preview {
    ZStack { Brand.backdrop.ignoresSafeArea(); AuthView() }
        .environment(Session())
}
