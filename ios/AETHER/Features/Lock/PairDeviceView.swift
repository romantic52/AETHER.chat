import SwiftUI

/// Экран НОВОГО устройства: показывает QR и ждёт подтверждения.
struct PairShowQRView: View {
    @EnvironmentObject var session: Session
    @Environment(\.dismiss) private var dismiss
    @Environment(\.palette) private var palette
    @StateObject private var service = PairingService()

    @State private var draft: PairingService.Draft?
    @State private var error: String?
    @State private var waiting = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                if let draft, let image = QRCode.image(from: draft.link.url) {
                    Image(uiImage: image)
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 240, height: 240)
                        .padding(12)
                        .background(.white, in: RoundedRectangle(cornerRadius: 20, style: .continuous))

                    Text("Откройте Æther на устройстве, где вы уже вошли, и отсканируйте этот код.")
                        .font(.system(size: 15))
                        .foregroundStyle(palette.textSecondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 24)

                    if waiting {
                        HStack(spacing: 8) {
                            ProgressView().tint(palette.accent)
                            Text("Ожидание подтверждения…")
                                .font(.footnote)
                                .foregroundStyle(palette.textSecondary)
                        }
                    }
                } else if let error {
                    Text(error)
                        .font(.system(size: 15))
                        .foregroundStyle(palette.danger)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 24)
                } else {
                    ProgressView().tint(palette.accent)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(palette.background)
            .navigationTitle("Вход по QR")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Отмена") { dismiss() } }
            }
        }
        .task { await run() }
    }

    private func run() async {
        do {
            let created = try await service.start(host: ServerContext.origin)
            draft = created
            waiting = true
            // Заявка живёт 10 минут; спрашиваем раз в две секунды.
            for _ in 0..<300 {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                if Task.isCancelled { return }
                if let bundle = try await service.claim(created) {
                    await session.signIn(paired: bundle)
                    dismiss()
                    return
                }
            }
            waiting = false
            error = "Время заявки истекло. Попробуйте ещё раз."
        } catch {
            waiting = false
            self.error = error.localizedDescription
        }
    }
}

/// Экран ДОВЕРЕННОГО устройства: сканирует QR и подтверждает привязку.
struct PairScanView: View {
    @EnvironmentObject var session: Session
    @Environment(\.dismiss) private var dismiss
    @Environment(\.palette) private var palette
    @StateObject private var service = PairingService()

    @State private var pending: PairingLink?
    @State private var status: String?
    @State private var busy = false

    var body: some View {
        NavigationStack {
            ZStack {
                QRScannerView(onScan: { code in
                    guard pending == nil, let link = PairingLink.parse(code) else { return }
                    pending = link
                }, onFailure: { message in
                    status = message
                })
                .ignoresSafeArea()

                VStack {
                    Spacer()
                    if let status {
                        Text(status)
                            .font(.system(size: 15, weight: .medium))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 18).padding(.vertical, 12)
                            .background(.black.opacity(0.6), in: Capsule())
                            .padding(.bottom, 40)
                    }
                }
            }
            .navigationTitle("Привязать устройство")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Отмена") { dismiss() } }
            }
            .alert("Привязать устройство?", isPresented: .constant(pending != nil && !busy)) {
                Button("Отмена", role: .cancel) { pending = nil }
                Button("Привязать") { Task { await approve() } }
            } message: {
                Text("Новое устройство получит доступ к вашему аккаунту. Подтверждайте только если это ваше устройство.")
            }
        }
    }

    private func approve() async {
        guard let link = pending else { return }
        busy = true
        status = "Подтверждаем…"
        let bundle = PairingBundle(userId: session.myId,
                                   token: session.authToken,
                                   publicKey: Keychain.string(for: Keychain.kPublicKey) ?? "",
                                   privateKey: Keychain.string(for: Keychain.kPrivateKey) ?? "")
        do {
            try await service.approve(link, bundle: bundle, token: session.authToken)
            status = "Готово"
            try? await Task.sleep(nanoseconds: 700_000_000)
            dismiss()
        } catch {
            status = error.localizedDescription
            busy = false
            pending = nil
        }
    }
}
