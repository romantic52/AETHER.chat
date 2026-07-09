import SwiftUI
import AVFoundation

// Запись видео-кружка (1:1). Фон — размытая камера, вокруг кружка «эквалайзер»,
// лимит 60 секунд с кольцом прогресса. Зажал — запись, отпустил — отправка,
// вверх — без рук (стоп-кнопкой). Фронталка зеркалится, камера переворачивается.
struct CircleRecorderView: View {
    var onSend: (Data, TimeInterval) -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.palette) private var palette
    @StateObject private var cam = CircleCamera()

    @State private var locked = false
    @State private var finishing = false
    private let maxDuration: TimeInterval = 60

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            VStack(spacing: 0) {
                HStack {
                    Button { cancelAndClose() } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 17, weight: .bold))
                            .foregroundStyle(.white)
                            .frame(width: 42, height: 42)
                            .background(.ultraThinMaterial, in: Circle())
                    }
                    Spacer()
                }
                .padding(.horizontal, 20)
                .padding(.top, 8)

                Spacer()

                ZStack {
                    // Круговой blur-ореол из камеры: сильный у кружка, растворяется
                    // к краю пятна (с отступом от краёв экрана) и пульсирует
                    // как эквалайзер во время записи.
                    if cam.available {
                        // Ореол: второй слой камеры + системный материал сверху
                        // (backdrop-блюр умеет размывать видеослой, в отличие от
                        // SwiftUI .blur, который на видео даёт черноту). Края
                        // растворяются в фон радиальным градиентом, с отступом
                        // от краёв экрана; пульс — «эквалайзер» при записи.
                        TimelineView(.animation) { timeline in
                            let t = timeline.date.timeIntervalSinceReferenceDate
                            let pulse: CGFloat = cam.isRecording ? 1 + 0.07 * abs(sin(t * 2.6)) + 0.03 * abs(sin(t * 4.1)) : 1
                            let halo = UIScreen.main.bounds.width - 36
                            ZStack {
                                CameraPreview(session: cam.session)
                                    .frame(width: halo, height: halo)
                                Circle().fill(.regularMaterial)
                                    .frame(width: halo, height: halo)
                                RadialGradient(colors: [.clear, .black.opacity(0.25), .black],
                                               center: .center,
                                               startRadius: 140, endRadius: halo / 2)
                            }
                            .frame(width: halo, height: halo)
                            .clipShape(Circle())
                            .scaleEffect(pulse)
                            .allowsHitTesting(false)
                            .environment(\.colorScheme, .dark)
                        }
                    }

                    // «Эквалайзер»: пульсирующие кольца вокруг кружка во время записи.
                    if cam.isRecording {
                        TimelineView(.animation) { timeline in
                            let t = timeline.date.timeIntervalSinceReferenceDate
                            ZStack {
                                ForEach(0..<3, id: \.self) { ring in
                                    let phase = t * 2.2 + Double(ring) * 1.1
                                    let pulse = 0.5 + 0.5 * abs(sin(phase))
                                    Circle()
                                        .stroke(palette.accent.opacity(0.5 - Double(ring) * 0.14),
                                                lineWidth: 3 - CGFloat(ring) * 0.7)
                                        .frame(width: 310, height: 310)
                                        .scaleEffect(1 + CGFloat(pulse) * 0.055 * CGFloat(ring + 1))
                                }
                            }
                        }
                    }

                    if cam.available {
                        CameraPreview(session: cam.session)
                            .frame(width: 300, height: 300)
                            .clipShape(Circle())
                    } else {
                        Circle().fill(palette.surfaceElevated).frame(width: 300, height: 300)
                            .overlay(VStack(spacing: 10) {
                                Image(systemName: "video.slash").font(.system(size: 40)).foregroundStyle(palette.textSecondary)
                                Text("Камера недоступна").multilineTextAlignment(.center)
                                    .font(.footnote).foregroundStyle(palette.textSecondary)
                            })
                    }

                    // Прогресс лимита 60 секунд.
                    Circle()
                        .trim(from: 0, to: CGFloat(min(1, cam.elapsed / maxDuration)))
                        .stroke(palette.accent, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                        .frame(width: 312, height: 312)
                        .rotationEffect(.degrees(-90))
                        .animation(.linear(duration: 0.1), value: cam.elapsed)
                }

                Text(timeString(cam.isRecording ? cam.elapsed : 0))
                    .font(.system(size: 19, weight: .semibold, design: .monospaced))
                    .foregroundStyle(.white)
                    .contentTransition(.numericText())
                    .padding(.top, 22)

                Spacer()

                recordControl
                    .padding(.bottom, 6)

                Text(locked ? "Тапни — закончить и отправить"
                            : (cam.isRecording ? "Отпусти — отправить · вверх — без рук"
                                               : "Зажми и говори"))
                    .font(.footnote)
                    .foregroundStyle(.white.opacity(0.75))
                    .padding(.bottom, 26)
                    .animation(.easeInOut(duration: 0.15), value: locked)
                    .animation(.easeInOut(duration: 0.15), value: cam.isRecording)
            }
        }
        .task {
            cam.setFinishHandler { url in finish(url) }
            await cam.configure()
        }
        .onChange(of: cam.elapsed) { _, t in
            // Жёсткий лимит кружка — 60 секунд: автостоп и отправка.
            if t >= maxDuration, cam.isRecording, !finishing {
                finishing = true
                cam.stopRecording()
            }
        }
        .onDisappear { cam.stop() }
    }

    // Кнопка записи: зажал — пишем, отпустил — отправилось; вверх — лок;
    // в локе кнопка становится «стоп» (тап — закончить и отправить).
    @ViewBuilder private var recordControl: some View {
        if locked {
            Button {
                guard !finishing else { return }
                finishing = true
                cam.stopRecording()
            } label: {
                ZStack {
                    Circle().stroke(.white, lineWidth: 4).frame(width: 82, height: 82)
                    RoundedRectangle(cornerRadius: 7, style: .continuous)
                        .fill(palette.danger)
                        .frame(width: 34, height: 34)
                }
            }
            .buttonStyle(.squish)
        } else {
            ZStack {
                Circle().stroke(.white, lineWidth: 4).frame(width: 82, height: 82)
                Circle()
                    .fill(palette.danger)
                    .frame(width: cam.isRecording ? 74 : 64, height: cam.isRecording ? 74 : 64)
                    .animation(.spring(response: 0.25, dampingFraction: 0.7), value: cam.isRecording)
            }
            .contentShape(Circle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        if !cam.isRecording, cam.available, !finishing {
                            cam.startRecording()
                            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        }
                        if value.translation.height < -70, cam.isRecording, !locked {
                            locked = true
                            UISelectionFeedbackGenerator().selectionChanged()
                        }
                    }
                    .onEnded { _ in
                        guard cam.isRecording, !locked, !finishing else { return }
                        finishing = true
                        cam.stopRecording()   // отпустил — отправляем
                    }
            )
        }
    }

    private func finish(_ url: URL) {
        defer { try? FileManager.default.removeItem(at: url) }
        let duration = cam.elapsed
        guard finishing, duration >= 0.5, let data = try? Data(contentsOf: url) else {
            finishing = false
            locked = false
            return
        }
        onSend(data, min(duration, maxDuration))
        dismiss()
    }

    private func cancelAndClose() {
        finishing = false
        if cam.isRecording { cam.cancelRecording() }
        dismiss()
    }

    private func timeString(_ t: TimeInterval) -> String { String(format: "%d:%02d", Int(t) / 60, Int(t) % 60) }

    init(onSend: @escaping (Data, TimeInterval) -> Void) {
        self.onSend = onSend
    }
}

// Камера кружка через AVCaptureSession + MovieFileOutput.
// Фронталка пишется зеркально (как видит себя пользователь); камеру можно переворачивать.
@MainActor
final class CircleCamera: NSObject, ObservableObject, AVCaptureFileOutputRecordingDelegate {
    let session = AVCaptureSession()
    @Published var available = false
    @Published var isRecording = false
    @Published var elapsed: TimeInterval = 0

    private let output = AVCaptureMovieFileOutput()
    private var timer: Timer?
    private var startedAt: Date?
    private var onFinish: ((URL) -> Void)?
    private var cancelled = false

    func configure() async {
        let granted = await withCheckedContinuation { c in
            AVCaptureDevice.requestAccess(for: .video) { c.resume(returning: $0) }
        }
        guard granted else { available = false; return }
        // Конфигурация и запуск сессии — вне главного потока: на старте экрана
        // это давало заметный лаг открытия.
        let session = self.session
        let output = self.output
        let ok: Bool = await Task.detached(priority: .userInitiated) {
            guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front),
                  let input = try? AVCaptureDeviceInput(device: device) else { return false }
            session.beginConfiguration()
            session.sessionPreset = .high
            if session.canAddInput(input) { session.addInput(input) }
            if let mic = AVCaptureDevice.default(for: .audio),
               let micIn = try? AVCaptureDeviceInput(device: mic),
               session.canAddInput(micIn) { session.addInput(micIn) }
            if session.canAddOutput(output) { session.addOutput(output) }
            session.commitConfiguration()
            // Зеркалим запись с фронталки — как в превью.
            if let connection = output.connection(with: .video), connection.isVideoMirroringSupported {
                connection.automaticallyAdjustsVideoMirroring = false
                connection.isVideoMirrored = true
            }
            session.startRunning()
            return true
        }.value
        available = ok
    }

    func startRecording() {
        cancelled = false
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("circle_\(UUID().uuidString).mov")
        output.startRecording(to: url, recordingDelegate: self)
        isRecording = true; elapsed = 0; startedAt = Date()
        timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.elapsed = Date().timeIntervalSince(self?.startedAt ?? Date()) }
        }
    }

    func stopRecording() {
        output.stopRecording()
        timer?.invalidate(); timer = nil
        isRecording = false
    }

    /// Стоп с отбросом результата (закрыли крестиком во время записи).
    func cancelRecording() {
        cancelled = true
        stopRecording()
    }

    func setFinishHandler(_ h: @escaping (URL) -> Void) { onFinish = h }

    func stop() { if session.isRunning { session.stopRunning() } }

    nonisolated func fileOutput(_ output: AVCaptureFileOutput, didFinishRecordingTo outputFileURL: URL,
                                from connections: [AVCaptureConnection], error: Error?) {
        Task { @MainActor in
            if self.cancelled {
                try? FileManager.default.removeItem(at: outputFileURL)
                self.cancelled = false
            } else {
                self.onFinish?(outputFileURL)
            }
        }
    }
}

// Превью камеры.
struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession
    func makeUIView(context: Context) -> PreviewView {
        let v = PreviewView(); v.videoPreviewLayer.session = session
        v.videoPreviewLayer.videoGravity = .resizeAspectFill; return v
    }
    func updateUIView(_ uiView: PreviewView, context: Context) {}

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var videoPreviewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }
}

// Круглый зацикленный плеер видео-кружка (тап = звук вкл/выкл).
struct LoopingCirclePlayer: View {
    let url: URL
    var size: CGFloat = 200
    var muted: Bool = true
    @State private var player: AVQueuePlayer?
    @State private var looper: AVPlayerLooper?
    @State private var isMuted = true
    @State private var isPlaying = false

    var body: some View {
        VideoLayerView(player: player)
            .frame(width: size, height: size)
            .background(Color.black, in: Circle())
            .clipShape(Circle())
            .overlay(Circle().stroke(.white.opacity(0.15), lineWidth: 1))
            .overlay {
                if !isPlaying {
                    Image(systemName: "play.fill")
                        .font(.system(size: 28, weight: .semibold))
                        .foregroundStyle(.white)
                        .padding(16)
                        .background(.black.opacity(0.38), in: Circle())
                        .allowsHitTesting(false)
                }
            }
            .overlay(alignment: .bottomTrailing) {
                Button {
                    isMuted.toggle()
                    player?.isMuted = isMuted
                } label: {
                    Image(systemName: isMuted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                        .font(.system(size: 12)).foregroundStyle(.white)
                        .padding(6).background(.black.opacity(0.4), in: Circle()).padding(8)
                }
                .buttonStyle(.plain)
            }
            .onTapGesture {
                if player == nil {
                    setup()
                    isPlaying = true
                    player?.play()
                    return
                }
                isPlaying.toggle()
                if isPlaying { player?.play() } else { player?.pause() }
            }
            .onDisappear {
                player?.pause()
                player?.removeAllItems()
                player = nil
                looper = nil
            }
    }

    private func setup() {
        let item = AVPlayerItem(url: url)
        let q = AVQueuePlayer()
        looper = AVPlayerLooper(player: q, templateItem: item)
        q.isMuted = muted
        isMuted = muted
        player = q
        isPlaying = false
    }
}

struct VideoLayerView: UIViewRepresentable {
    let player: AVQueuePlayer?
    func makeUIView(context: Context) -> PlayerView { PlayerView() }
    func updateUIView(_ uiView: PlayerView, context: Context) { uiView.playerLayer.player = player }
    final class PlayerView: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
        override init(frame: CGRect) { super.init(frame: frame); playerLayer.videoGravity = .resizeAspectFill }
        required init?(coder: NSCoder) { fatalError() }
    }
}
