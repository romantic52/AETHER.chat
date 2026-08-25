import SwiftUI

// Идентификатор сервера изменился.
//
// Отличить переустановку сервера от подмены клиент не может — и не должен
// делать вид, что может. Поэтому решение принимает человек, но принимает
// осознанно: со старым и новым отпечатком перед глазами.
struct ServerTrustAlertView: View {
    let info: ServerInfo
    let record: ServerRecord
    let oldPin: ServerPin
    var onAccepted: () -> Void

    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss
    @State private var confirming = false

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 44))
                    .foregroundStyle(palette.danger)
                    .padding(.top, 12)

                Text("Внимание")
                    .font(.system(size: 26, weight: .bold, design: .rounded))
                    .foregroundStyle(palette.textPrimary)

                Text("Идентификатор сервера изменился.\n\nЭто может означать переустановку сервера, смену владельца или попытку подмены.")
                    .font(.subheadline)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(palette.textSecondary)

                Text(record.hostLabel)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(palette.textPrimary)

                VStack(spacing: 0) {
                    fingerprint("Старый отпечаток", oldPin.fingerprintB64,
                                subtitle: "с " + Self.dateText(oldPin.firstSeenAt), tint: palette.textSecondary)
                    Rectangle().fill(palette.divider).frame(height: 0.5)
                    fingerprint("Новый отпечаток", info.fingerprintB64,
                                subtitle: "сейчас", tint: palette.danger)
                }
                .background(palette.surface, in: RoundedRectangle(cornerRadius: 20))

                Text("Данные, которые вы уже разрешили передавать, до подтверждения передаваться не будут. Если подтвердите — все разрешения этого сервера будут сброшены: новый ключ может означать другую сторону.")
                    .font(.caption)
                    .foregroundStyle(palette.textSecondary)

                VStack(spacing: 12) {
                    Button {
                        ServerDirectory.shared.acceptNewIdentity(for: record, info: info)
                        onAccepted()
                        dismiss()
                    } label: {
                        Text(confirming ? "Да, это тот же сервер" : "Доверять новому серверу")
                            .font(.headline)
                            .frame(maxWidth: .infinity).padding(.vertical, 16)
                            .background(confirming ? palette.danger : palette.surfaceElevated,
                                        in: Capsule())
                            .foregroundStyle(confirming ? palette.onAccent : palette.textPrimary)
                    }
                    .buttonStyle(.squish)
                    .disabled(!confirming)
                    .opacity(confirming ? 1 : 0.5)

                    // Второе подтверждение намеренно: это единственное место,
                    // где пользователь может отменить защиту от подмены.
                    Button(confirming ? "Скрыть" : "Я сверил отпечаток с владельцем сервера") {
                        withAnimation { confirming.toggle() }
                    }
                    .font(.system(size: 15))
                    .foregroundStyle(palette.textSecondary)

                    Button("Отмена") { dismiss() }
                        .font(.headline)
                        .foregroundStyle(palette.accent)
                }
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 28)
        }
        .background(palette.background.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .interactiveDismissDisabled()
    }

    private func fingerprint(_ title: String, _ value: String, subtitle: String, tint: Color) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(title).font(.system(size: 13)).foregroundStyle(palette.textSecondary)
                Spacer()
                Text(subtitle).font(.system(size: 12)).foregroundStyle(palette.textSecondary)
            }
            Text(formatFingerprint(fingerprintB64: value))
                .font(.system(size: 13, design: .monospaced))
                .foregroundStyle(tint)
                .textSelection(.enabled)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
    }

    private static func dateText(_ date: Date) -> String {
        let f = DateFormatter()
        f.dateFormat = "d MMMM"
        f.locale = Locale(identifier: "ru_RU")
        return f.string(from: date)
    }
}
