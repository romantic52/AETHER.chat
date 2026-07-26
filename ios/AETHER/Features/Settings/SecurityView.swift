import SwiftUI

// Контроль сессий, 2FA и «удалить всё». Серверная часть — общая с web/Android.
struct SecurityView: View {
    @EnvironmentObject var session: Session
    @Environment(\.palette) private var palette

    @State private var info: CoreClient.SessionsInfo?
    @State private var totpOn = false
    @State private var loading = true
    @State private var error: String?

    // 2FA setup
    @State private var showTotpSetup = false
    @State private var totpSecret = ""
    @State private var totpCode = ""
    // disable / wipe
    @State private var showTotpDisable = false
    @State private var showWipe = false
    @State private var confirmField = ""
    @State private var kickTarget: CoreClient.DeviceSession?
    // Резервная копия истории (P9)
    @State private var backupOn = false
    @State private var backupBusy = false
    @State private var backupNote = ""
    @State private var showBackupOff = false

    var body: some View {
        ZStack {
            palette.background.ignoresSafeArea()
            List {
                sessionsSection
                backupSection
                twoFactorSection
                dangerSection
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .safeAreaPadding(.bottom, 100)
            .refreshable { await reload() }
        }
        .toolbar(.hidden, for: .navigationBar)
        .safeAreaInset(edge: .top) {
            FloatingHeader(title: "Безопасность",
                           leading: AnyView(HeaderIconButton(icon: "chevron.left") { dismiss() }))
        }
        .task { await reload() }
        .alert("Секрет 2FA", isPresented: $showTotpSetup) { totpSetupButtons } message: {
            Text("Добавьте секрет в аутентификатор (Google Authenticator, 1Password):\n\n\(totpSecret)\n\nЗатем введите код для подтверждения.")
        }
        .alert("Выключить 2FA", isPresented: $showTotpDisable) { totpDisableButtons } message: {
            Text("Введите текущий код из аутентификатора.")
        }
        .alert("Удалить всё", isPresented: $showWipe) { wipeButtons } message: {
            Text("Переписки на сервере, выход из всех групп и каналов, отзыв остальных сессий. Введите пароль аккаунта.")
        }
        .alert(item: $kickTarget) { dev in
            Alert(title: Text("Выкинуть устройство?"),
                  message: Text("Сессии \(dev.deviceId) будут отозваны, его ключи удалены."),
                  primaryButton: .destructive(Text(dev.current ? "Выйти" : "Выкинуть")) { kick(dev) },
                  secondaryButton: .cancel(Text("Отмена")))
        }
    }

    @Environment(\.dismiss) private var dismiss

    // MARK: Резервная копия истории

    private var backupSection: some View {
        Section {
            Toggle(isOn: Binding(get: { backupOn }, set: { on in
                if on { enableBackup() } else { showBackupOff = true }
            })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Копия переписки на сервере")
                        .foregroundStyle(palette.textPrimary)
                    Text("Зашифрована ключом, который сервер вывести не может")
                        .font(.caption)
                        .foregroundStyle(palette.textSecondary)
                }
            }
            .disabled(backupBusy)

            if backupOn {
                Button {
                    restoreBackup()
                } label: {
                    Label("Восстановить переписку из копии", systemImage: "arrow.down.circle")
                        .foregroundStyle(palette.accent)
                }
                .disabled(backupBusy)
            }
            if !backupNote.isEmpty {
                Text(backupNote).font(.caption).foregroundStyle(palette.textSecondary)
            }
        } header: {
            Text("Резервная копия")
        } footer: {
            Text("История шифруется на устройстве и выгружается на сервер. Расшифровать её может только вход в ваш аккаунт по паролю — сервер видит лишь шифротекст. Выключение удаляет копию с сервера.")
        }
        .alert("Выключить копию?", isPresented: $showBackupOff) {
            Button("Выключить и удалить", role: .destructive) { disableBackup() }
            Button("Отмена", role: .cancel) {}
        } message: {
            Text("Резервная копия будет удалена с сервера. После переустановки приложения переписку восстановить будет нельзя.")
        }
    }

    private func enableBackup() {
        backupBusy = true
        Task {
            do {
                try await session.core.setBackupEnabled(true)
                backupOn = true
                backupNote = "Выгружаем историю…"
                let sent = await session.core.backupSyncUp()
                backupNote = sent > 0 ? "Выгружено сообщений: \(sent)" : "Копия актуальна"
            } catch {
                backupNote = "Не удалось включить: \(error.localizedDescription)"
            }
            backupBusy = false
        }
    }

    private func disableBackup() {
        backupBusy = true
        Task {
            do {
                try await session.core.setBackupEnabled(false)
                backupOn = false
                backupNote = "Копия удалена с сервера"
            } catch {
                backupNote = "Не удалось выключить: \(error.localizedDescription)"
            }
            backupBusy = false
        }
    }

    private func restoreBackup() {
        backupBusy = true
        backupNote = "Восстанавливаем…"
        Task {
            do {
                let restored = try await session.core.backupRestore()
                backupNote = restored > 0 ? "Восстановлено сообщений: \(restored)"
                                          : "Новых сообщений в копии нет"
            } catch {
                backupNote = "Не удалось восстановить: \(error.localizedDescription)"
            }
            backupBusy = false
        }
    }

    // MARK: Sessions

    private var sessionsSection: some View {
        Section {
            if loading && info == nil {
                HStack { Spacer(); ProgressView(); Spacer() }
            } else if let info {
                ForEach(info.devices) { dev in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(deviceTitle(dev.deviceId) + (dev.current ? " · это устройство" : ""))
                                .font(.subheadline.weight(.medium))
                                .foregroundStyle(palette.textPrimary)
                            Text("\(dev.deviceId) · сессий: \(dev.sessions)")
                                .font(.caption).foregroundStyle(palette.textSecondary)
                        }
                        Spacer()
                        let allowed = dev.current || info.canKick
                        Button(dev.current ? "Выйти" : "Выкинуть") { kickTarget = dev }
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(allowed ? palette.danger : palette.textSecondary)
                            .disabled(!allowed)
                    }
                    .listRowBackground(palette.surface)
                }
                if !info.canKick {
                    Text("С нового устройства выкидывать другие можно через \(info.kickMinHours) ч.")
                        .font(.caption).foregroundStyle(palette.textSecondary)
                        .listRowBackground(palette.surface)
                }
                if info.unbound > 0 {
                    Text("+ \(info.unbound) сессий со старых версий приложений.")
                        .font(.caption).foregroundStyle(palette.textSecondary)
                        .listRowBackground(palette.surface)
                }
            }
        } header: { Text("Устройства и сессии") }
        .listRowBackground(palette.surface)
    }

    // MARK: 2FA

    private var twoFactorSection: some View {
        Section {
            HStack {
                SettingsLabel("Двухфакторная (TOTP)", icon: "lock.shield.fill", color: .green)
                Spacer()
                Text(totpOn ? "Вкл" : "Выкл").foregroundStyle(palette.textSecondary)
            }
            Button(totpOn ? "Выключить 2FA" : "Включить 2FA") {
                if totpOn { totpCode = ""; showTotpDisable = true } else { startTotpSetup() }
            }
            .foregroundStyle(palette.accent)
        } footer: {
            Text("Когда включена, вход всегда требует одноразовый код из приложения-аутентификатора.")
        }
        .listRowBackground(palette.surface)
    }

    private var dangerSection: some View {
        Section {
            Button(role: .destructive) { confirmField = ""; showWipe = true } label: {
                Label("Удалить всё: чаты, группы, каналы", systemImage: "trash.fill")
            }
        } footer: {
            Text("Не удаляет сам аккаунт и ключи — только очищает данные и выходит отовсюду.")
        }
        .listRowBackground(palette.surface)
    }

    // MARK: Alert buttons

    @ViewBuilder private var totpSetupButtons: some View {
        TextField("Код", text: $totpCode).keyboardType(.numberPad)
        Button("Включить") { enableTotp() }
        Button("Отмена", role: .cancel) {}
    }
    @ViewBuilder private var totpDisableButtons: some View {
        TextField("Код", text: $totpCode).keyboardType(.numberPad)
        Button("Выключить", role: .destructive) { disableTotp() }
        Button("Отмена", role: .cancel) {}
    }
    @ViewBuilder private var wipeButtons: some View {
        SecureField("Пароль", text: $confirmField)
        Button("Удалить всё", role: .destructive) { wipe() }
        Button("Отмена", role: .cancel) {}
    }

    // MARK: Actions

    private func reload() async {
        loading = true
        let backup = await session.core.backupEnabled
        do {
            let s = try await session.core.listSessions()
            let on = try await session.core.totpEnabled()
            await MainActor.run { info = s; totpOn = on; backupOn = backup; loading = false }
        } catch {
            await MainActor.run { self.error = "\(error)"; loading = false }
        }
    }

    private func kick(_ dev: CoreClient.DeviceSession) {
        Task {
            do {
                try await session.core.kickDevice(dev.deviceId)
                if dev.current { await session.logout() } else { await reload() }
            } catch { await MainActor.run { self.error = "\(error)" } }
        }
    }

    private func startTotpSetup() {
        Task {
            do {
                let secret = try await session.core.totpSetup()
                await MainActor.run { totpSecret = secret; totpCode = ""; showTotpSetup = true }
            } catch { await MainActor.run { self.error = "\(error)" } }
        }
    }
    private func enableTotp() {
        Task {
            do { try await session.core.totpEnable(code: totpCode.trimmingCharacters(in: .whitespaces)); await reload() }
            catch { await MainActor.run { self.error = "Неверный код" } }
        }
    }
    private func disableTotp() {
        Task {
            do { try await session.core.totpDisable(code: totpCode.trimmingCharacters(in: .whitespaces)); await reload() }
            catch { await MainActor.run { self.error = "Неверный код" } }
        }
    }
    private func wipe() {
        let pass = confirmField
        Task {
            do {
                try await session.core.wipeAccount(password: pass)
                await reload()
            } catch { await MainActor.run { self.error = "Не удалось: проверьте пароль" } }
        }
    }

    private func deviceTitle(_ id: String) -> String {
        if id == "primary" { return "📱 Основное" }
        if id.hasPrefix("web-") { return "🌐 Веб" }
        if id.hasPrefix("ios-") { return "📱 iOS" }
        if id.hasPrefix("android-") { return "🤖 Android" }
        return "🖥 Устройство"
    }
}
