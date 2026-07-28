import SwiftUI
import AVFoundation
import CoreImage.CIFilterBuiltins

// QR-сверка ключей. Содержимое метки — канон ЯДРА
// (aether_ratchet_core::verify_qr_build/parse): клиент его сам не собирает и не
// разбирает, иначе платформы разъедутся. Здесь только картинка и камера.

// MARK: - Генерация метки

enum QRCode {
    /// Чёрно-белый QR из строки. Увеличиваем трансформом ДО растеризации, а не
    /// ресайзом готовой картинки: интерполяция размыла бы границы модулей, и
    /// код перестал бы читаться с чужого экрана.
    static func image(from text: String, scale: CGFloat = 12) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        // Уровень коррекции M: метка (~90 символов) остаётся некрупной, но
        // переживает блики и частичное перекрытие пальцем.
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        guard let cg = CIContext().createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

// MARK: - Сканер

/// Камера, отдающая ПЕРВУЮ распознанную QR-строку и на этом останавливающаяся.
/// Разбор и сверка — снаружи: вью ничего не знает про ключи.
struct QRScannerView: UIViewControllerRepresentable {
    var onScan: (String) -> Void
    var onFailure: (String) -> Void

    func makeUIViewController(context: Context) -> QRScannerController {
        let controller = QRScannerController()
        controller.onScan = onScan
        controller.onFailure = onFailure
        return controller
    }

    func updateUIViewController(_ controller: QRScannerController, context: Context) {}
}

final class QRScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onScan: ((String) -> Void)?
    var onFailure: ((String) -> Void)?

    private let session = AVCaptureSession()
    private var preview: AVCaptureVideoPreviewLayer?
    /// Сканер одноразовый: после первой метки камера гасится. Иначе один код
    /// выстрелил бы делегатом десятки раз за секунду.
    private var handled = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
            DispatchQueue.main.async {
                guard let self else { return }
                guard granted else {
                    self.onFailure?("Нет доступа к камере — разрешите его в настройках устройства.")
                    return
                }
                self.configure()
            }
        }
    }

    private func configure() {
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            onFailure?("Камера недоступна.")
            return
        }
        session.addInput(input)

        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else {
            onFailure?("Камера недоступна.")
            return
        }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        // Типы задаются ТОЛЬКО после addOutput — до этого список пуст и
        // присваивание .qr падает с исключением.
        output.metadataObjectTypes = [.qr]

        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        view.layer.addSublayer(layer)
        preview = layer

        // startRunning блокирует вызывающий поток — с главного это фриз UI.
        Task.detached(priority: .userInitiated) { [session] in session.startRunning() }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        stop()
    }

    private func stop() {
        guard session.isRunning else { return }
        Task.detached(priority: .userInitiated) { [session] in session.stopRunning() }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput,
                        didOutput objects: [AVMetadataObject],
                        from connection: AVCaptureConnection) {
        guard !handled,
              let object = objects.first as? AVMetadataMachineReadableCodeObject,
              object.type == .qr,
              let value = object.stringValue else { return }
        handled = true
        stop()
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        onScan?(value)
    }
}

// MARK: - Экран сканирования

/// Обёртка сканера: рамка-прицел, подпись и кнопка отмены.
struct QRScannerSheet: View {
    let peerName: String
    var onScan: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.palette) private var palette
    @State private var failure: String?

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if let failure {
                VStack(spacing: 16) {
                    Image(systemName: "video.slash")
                        .font(.system(size: 40))
                        .foregroundStyle(.white.opacity(0.7))
                    Text(failure)
                        .font(.subheadline)
                        .foregroundStyle(.white.opacity(0.8))
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 36)
                }
            } else {
                QRScannerView(
                    onScan: { value in
                        onScan(value)
                        dismiss()
                    },
                    onFailure: { failure = $0 }
                )
                .ignoresSafeArea()

                // Прицел: показывает, куда наводить, и прикрывает то, что рамка
                // сканирования на самом деле во весь кадр.
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .stroke(.white.opacity(0.9), lineWidth: 3)
                    .frame(width: 240, height: 240)
                    .shadow(radius: 8)

                VStack {
                    Spacer()
                    Text("Наведите на QR-код на экране \(peerName)")
                        .font(.subheadline)
                        .foregroundStyle(.white)
                        .padding(.horizontal, 18)
                        .padding(.vertical, 10)
                        .background(.black.opacity(0.55), in: Capsule())
                        .padding(.bottom, 48)
                }
            }

            VStack {
                HStack {
                    Spacer()
                    Button { dismiss() } label: {
                        Image(systemName: "xmark")
                            .font(.headline)
                            .foregroundStyle(.white)
                            .padding(12)
                            .background(.black.opacity(0.5), in: Circle())
                    }
                    .padding(.trailing, 18)
                    .padding(.top, 12)
                }
                Spacer()
            }
        }
    }
}
