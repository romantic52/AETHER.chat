import SwiftUI
import CryptoKit

// TOFU-экран сверки ключей: общий отпечаток пары ключей (мой + собеседника)
// как эмодзи-сетка + числовой код. Совпадает у обоих — связь точно без MITM;
// «Подтвердить» помечает пин verified в ядре. Смена ключа сбрасывает подтверждение
// автоматически (CoreStore.pinUpsert) — экран честно покажет тревогу.
struct KeyVerificationView: View {
    let peerId: String
    let peerName: String

    @EnvironmentObject var session: Session
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss

    @State private var pin: KeyPin?
    @State private var myKey = ""
    @State private var loading = true
    @State private var confirmMismatch = false
    // P8: сверяем МАСТЕР-ключи аккаунтов — именно ими подписаны все устройства,
    // а значит и Double Ratchet. Box-ключи остаются фолбэком для необновлённых пиров.
    @State private var masterPin: MasterPin?
    @State private var myMaster = ""
    /// Непринятый мастер пира: во время тревоги сверяем ЕГО, а не старый.
    @State private var pendingMaster: String?
    // QR-сверка: точнее чтения вслух — ошибиться при сравнении нечем.
    // Картинку держим готовой: генерация тянет CIContext, а body пересчитывается часто.
    @State private var myQrImage: UIImage?
    @State private var showScanner = false
    @State private var scanResult: ScanOutcome?
    /// Отсканированное значение обрабатываем ПОСЛЕ закрытия листа: алерт,
    /// поднятый одновременно с его дисмиссом, SwiftUI проглатывает.
    @State private var pendingScan: String?

    // 64 «словарных» эмодзи — легко назвать вслух по телефону.
    private static let emojiTable: [String] = [
        "🐶","🐱","🦊","🐻","🐼","🐨","🦁","🐯","🐮","🐷","🐸","🐵","🐔","🐧","🐦","🦆",
        "🦅","🦉","🐺","🐗","🐴","🦄","🐝","🐛","🦋","🐌","🐞","🐜","🕷","🦂","🐢","🐍",
        "🦎","🐙","🦑","🦐","🦀","🐡","🐠","🐟","🐬","🐳","🐋","🦈","🐊","🐅","🐆","🦓",
        "🦍","🐘","🦏","🐪","🦒","🐃","🐂","🐄","🐎","🐖","🐏","🐑","🐐","🦌","🐕","🐈",
    ]

    /// Сверяем мастер-ключи, если они есть у обеих сторон: подтверждённый мастер
    /// аутентифицирует ВСЕ устройства пира (cross-signing), тогда как box-ключ
    /// к Double Ratchet отношения не имеет. Версия канона входит в хеш.
    private var usingMaster: Bool { (masterPin != nil || pendingMaster != nil) && !myMaster.isEmpty }

    private var isVerified: Bool {
        // Пока смена мастера не принята, «подтверждено» показывать нельзя:
        // подтверждение относилось к прежнему корню доверия.
        if pendingMaster != nil { return false }
        return usingMaster ? (masterPin?.verified ?? false) : (pin?.verified ?? false)
    }

    private var fingerprint: (emoji: [String], code: String)? {
        let version = usingMaster ? "AetherSafety#2" : "AetherSafety#1"
        let mine = usingMaster ? myMaster : myKey
        let theirs = usingMaster ? (pendingMaster ?? masterPin?.masterKeyB64 ?? "")
                                 : (pin?.publicKeyB64 ?? "")
        guard !mine.isEmpty, !theirs.isEmpty else { return nil }
        // Симметрия: сортируем ключи, чтобы отпечаток совпадал у обеих сторон.
        let combined = ([mine, theirs].sorted() + [version]).joined(separator: "|")
        let hash = SHA256.hash(data: Data(combined.utf8))
        let bytes = Array(hash)
        let emoji = bytes.prefix(12).map { Self.emojiTable[Int($0) % Self.emojiTable.count] }
        // 6 групп по 5 цифр из хеша (30 цифр — достаточно и читаемо).
        let code = stride(from: 12, to: 24, by: 2).map { i -> String in
            let value = (UInt32(bytes[i]) << 8 | UInt32(bytes[i + 1])) % 100_000
            return String(format: "%05d", value)
        }.joined(separator: " ")
        return (emoji, code)
    }

    var body: some View {
        ZStack {
            palette.background.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 22) {
                    Image(systemName: statusIcon)
                        .font(.system(size: 44))
                        .foregroundStyle(statusColor)
                        .padding(.top, 24)

                    Text(statusTitle)
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(palette.textPrimary)

                    Text("Сравни отпечаток с экраном \(peerName) — вживую или голосом. Совпадает — шифрование точно между вами, без посредников.")
                        .font(.subheadline)
                        .foregroundStyle(palette.textSecondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 28)

                    if let fp = fingerprint {
                        // Эмодзи-сетка 4×3.
                        let columns = Array(repeating: GridItem(.flexible()), count: 4)
                        LazyVGrid(columns: columns, spacing: 14) {
                            ForEach(Array(fp.emoji.enumerated()), id: \.offset) { _, e in
                                Text(e).font(.system(size: 34))
                            }
                        }
                        .padding(.vertical, 18)
                        .padding(.horizontal, 22)
                        .liquidGlass(cornerRadius: 22)
                        .padding(.horizontal, 24)

                        Text(fp.code)
                            .font(.system(size: 15, weight: .medium, design: .monospaced))
                            .foregroundStyle(palette.textSecondary)
                            .padding(.horizontal, 24)

                        // QR доступен только для мастер-канона: сверять им box-ключ
                        // бессмысленно — он не аутентифицирует Double Ratchet.
                        if usingMaster, let myQrImage {
                            qrBlock(myQrImage)
                        }
                    } else if loading {
                        ProgressView().tint(palette.accent).padding(.vertical, 40)
                    } else {
                        Text("Ключ собеседника ещё не получен — напиши ему сообщение и вернись.")
                            .font(.subheadline)
                            .foregroundStyle(palette.textSecondary)
                            .multilineTextAlignment(.center)
                            .padding(30)
                    }

                    if pin != nil || masterPin != nil {
                        VStack(spacing: 10) {
                            if !isVerified {
                                Button {
                                    Task {
                                        if usingMaster {
                                            try? await session.core.setMasterVerified(peerId, true)
                                        } else {
                                            try? await session.core.setKeyVerified(peerId, true)
                                        }
                                        await reload()
                                        UINotificationFeedbackGenerator().notificationOccurred(.success)
                                    }
                                } label: {
                                    Label("Совпадает — подтвердить", systemImage: "checkmark.shield.fill")
                                        .font(.headline)
                                        .foregroundStyle(palette.onAccent)
                                        .frame(maxWidth: .infinity)
                                        .padding(.vertical, 14)
                                        .background(palette.accent, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                                }
                                .buttonStyle(.squish)
                            } else {
                                Button {
                                    Task {
                                        if usingMaster {
                                            try? await session.core.setMasterVerified(peerId, false)
                                        } else {
                                            try? await session.core.setKeyVerified(peerId, false)
                                        }
                                        await reload()
                                    }
                                } label: {
                                    Label("Снять подтверждение", systemImage: "shield.slash")
                                        .font(.subheadline)
                                        .foregroundStyle(palette.textSecondary)
                                }
                                .buttonStyle(.squish)
                            }

                            Button {
                                confirmMismatch = true
                            } label: {
                                Text("Не совпадает")
                                    .font(.subheadline.weight(.medium))
                                    .foregroundStyle(palette.danger)
                            }
                            .buttonStyle(.squish)
                        }
                        .padding(.horizontal, 24)
                        .padding(.top, 6)
                    }

                    Spacer(minLength: 30)
                }
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .safeAreaInset(edge: .top) {
            FloatingHeader(
                title: "Проверка ключа",
                large: false,
                leading: AnyView(HeaderIconButton(icon: "chevron.left") { dismiss() })
            )
        }
        .alert("Отпечатки не совпадают", isPresented: $confirmMismatch) {
            Button("Понятно", role: .cancel) {}
        } message: {
            Text("Кто-то может подменять ключи (MITM) — не отправляй чувствительное. Сверься с собеседником по другому каналу и попроси его перелогиниться, затем проверь снова.")
        }
        .sheet(isPresented: $showScanner, onDismiss: {
            guard let scanned = pendingScan else { return }
            pendingScan = nil
            Task { await handleScan(scanned) }
        }) {
            QRScannerSheet(peerName: peerName) { pendingScan = $0 }
        }
        .alert(scanResult?.title ?? "", isPresented: showingScanResult, presenting: scanResult) { _ in
            Button("Понятно", role: .cancel) {}
        } message: { outcome in
            Text(outcome.message)
        }
        .task { await reload() }
    }

    /// Итог сканирования — для алерта.
    private struct ScanOutcome: Identifiable {
        let id = UUID()
        let title: String
        let message: String
    }

    @ViewBuilder
    private func qrBlock(_ image: UIImage) -> some View {
        VStack(spacing: 12) {
            Image(uiImage: image)
                // Без интерполяции: сглаживание размывает модули, и код
                // перестаёт считываться с чужого экрана.
                .interpolation(.none)
                .resizable()
                .scaledToFit()
                .frame(width: 190, height: 190)
                .padding(12)
                // Белая подложка всегда, независимо от темы — иначе в тёмной
                // теме прозрачный фон метки сливается с модулями.
                .background(.white, in: RoundedRectangle(cornerRadius: 14, style: .continuous))

            Text("Покажите код собеседнику или отсканируйте его — это надёжнее, чем сверять эмодзи вслух.")
                .font(.footnote)
                .foregroundStyle(palette.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 30)

            Button { showScanner = true } label: {
                Label("Сканировать код", systemImage: "qrcode.viewfinder")
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(palette.accent)
            }
            .buttonStyle(.squish)
        }
        .padding(.top, 6)
    }

    private var showingScanResult: Binding<Bool> {
        Binding(get: { scanResult != nil }, set: { if !$0 { scanResult = nil } })
    }

    private func handleScan(_ scanned: String) async {
        do {
            switch try await session.core.verifyByQr(peerId: peerId, scanned: scanned) {
            case .verified:
                await reload()
                UINotificationFeedbackGenerator().notificationOccurred(.success)
                scanResult = ScanOutcome(
                    title: "Ключ подтверждён",
                    message: "Код совпал с ключом \(peerName) — шифрование точно между вами.")
            case .mismatch:
                UINotificationFeedbackGenerator().notificationOccurred(.error)
                scanResult = ScanOutcome(
                    title: "Ключи не совпадают",
                    message: "Код не сходится с тем ключом \(peerName), который знает приложение. Кто-то может подменять ключи — не отправляйте чувствительное и свяжитесь с собеседником по другому каналу.")
            case .wrongPeer(let scanned):
                scanResult = ScanOutcome(
                    title: "Код другого аккаунта",
                    message: "Это код @\(scanned), а сверяется чат с \(peerName).")
            case .noPin:
                scanResult = ScanOutcome(
                    title: "Ключ ещё не получен",
                    message: "Приложение пока не видело ключ \(peerName). Напишите сообщение и вернитесь — подтвердить можно только тот ключ, который пришёл по защищённому каналу, а не с чужого экрана.")
            }
        } catch {
            scanResult = ScanOutcome(
                title: "Не удалось прочитать код",
                message: "Это не код сверки Æther или он повреждён.")
        }
    }

    private func reload() async {
        loading = true
        pin = try? await session.core.keyPin(peerId)
        myKey = await session.core.myPublicKeyB64()
        masterPin = await session.core.masterPin(peerId)
        pendingMaster = await session.core.pendingMasterKey(for: peerId)
        myMaster = await session.core.myMasterKeyB64() ?? ""
        myQrImage = await session.core.myVerifyQr().flatMap { QRCode.image(from: $0) }
        loading = false
    }

    private var hasAnyPin: Bool { pin != nil || masterPin != nil }

    private var statusIcon: String {
        guard hasAnyPin else { return "questionmark.circle" }
        return isVerified ? "checkmark.shield.fill" : "shield.lefthalf.filled"
    }
    private var statusColor: Color {
        guard hasAnyPin else { return palette.textSecondary }
        return isVerified ? palette.readTick : palette.accent
    }
    private var statusTitle: String {
        guard hasAnyPin else { return "Нет данных о ключе" }
        return isVerified ? "Ключ подтверждён" : "Ключ не подтверждён"
    }
}
