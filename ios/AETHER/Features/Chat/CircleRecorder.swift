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

    // Telegram-флоу: запись стартует сразу; пауза копит сегменты (дозапись);
    // отправка в любой момент; прогресс — белая дуга вокруг кружка.
    @State private var segments: [URL] = []
    @State private var recordedBefore: TimeInterval = 0   // сумма прошлых сегментов
    @State private var paused = false
    @State private var sending = false
    @State private var pendingSend = false                // стоп ради отправки
    private let maxDuration: TimeInterval = 60

    private var totalElapsed: TimeInterval {
        recordedBefore + (cam.isRecording ? cam.elapsed : 0)
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            // Кружок — по центру, чуть выше середины (как в Telegram).
            VStack {
                Spacer().frame(height: 120)
                ZStack {
                    if cam.available {
                        CameraPreview(session: cam.session)
                            .frame(width: 300, height: 300)
                            .clipShape(Circle())
                            .opacity(paused ? 0.45 : 1)
                        if paused {
                            Image(systemName: "pause.fill")
                                .font(.system(size: 40, weight: .bold))
                                .foregroundStyle(.white.opacity(0.9))
                        }
                    } else {
                        Circle().fill(palette.surfaceElevated).frame(width: 300, height: 300)
                            .overlay(VStack(spacing: 10) {
                                Image(systemName: "video.slash").font(.system(size: 40)).foregroundStyle(palette.textSecondary)
                                Text("Камера недоступна").multilineTextAlignment(.center)
                                    .font(.footnote).foregroundStyle(palette.textSecondary)
                            })
                    }

                    // Белая дуга прогресса ВОКРУГ кружка (обозначение записи, как в TG):
                    // длина = записанное время из 60с, лёгкая пульсация при записи.
                    TimelineView(.animation) { timeline in
                        let t = timeline.date.timeIntervalSinceReferenceDate
                        let pulse: CGFloat = cam.isRecording ? 1 + 0.5 * abs(sin(t * 3.0)) : 1
                        Circle()
                            .trim(from: 0, to: max(0.015, CGFloat(min(1, totalElapsed / maxDuration))))
                            .stroke(.white.opacity(0.95),
                                    style: StrokeStyle(lineWidth: 3.5 * pulse, lineCap: .round))
                            .frame(width: 314, height: 314)
                            .rotationEffect(.degrees(-90))
                    }
                    .allowsHitTesting(false)
                }
                Spacer()
            }

            // Нижняя панель: [● таймер · Отмена] — слева; пауза и синяя отправка — справа.
            VStack {
                Spacer()
                HStack(alignment: .center, spacing: 12) {
                    HStack(spacing: 10) {
                        Circle().fill(palette.danger).frame(width: 9, height: 9)
                            .opacity(cam.isRecording ? 1 : 0.4)
                        Text(timeString(totalElapsed))
                            .font(.system(size: 16, weight: .medium, design: .monospaced))
                            .foregroundStyle(.white)
                            .contentTransition(.numericText())
                        Spacer(minLength: 8)
                        Button("Отмена") { cancelAndClose() }
                            .font(.system(size: 16, weight: .medium))
                            .foregroundStyle(palette.accent)
                        Spacer(minLength: 8)
                    }
                    .padding(.horizontal, 16)
                    .frame(height: 50)
                    .background(.ultraThinMaterial, in: Capsule())

                    // Пауза / продолжить (дозапись сегментами).
                    Button { togglePause() } label: {
                        Image(systemName: paused ? "record.circle" : "pause.fill")
                            .font(.system(size: 19, weight: .semibold))
                            .foregroundStyle(.white)
                            .frame(width: 50, height: 50)
                            .background(.ultraThinMaterial, in: Circle())
                    }
                    .buttonStyle(.squish)
                    .disabled(!cam.available || sending || (paused && remainingIsOut))

                    // Отправить.
                    Button { send() } label: {
                        Group {
                            if sending { ProgressView().tint(.white) }
                            else {
                                Image(systemName: "arrow.up")
                                    .font(.system(size: 24, weight: .bold))
                                    .foregroundStyle(.white)
                            }
                        }
                        .frame(width: 64, height: 64)
                        .background(palette.accent, in: Circle())
                    }
                    .buttonStyle(.squish)
                    .disabled(sending || (!cam.isRecording && segments.isEmpty))
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
        }
        .task {
            cam.setFinishHandler { url in segmentFinished(url) }
            await cam.configure()
            if cam.available { cam.startRecording() }   // автостарт, как в Telegram
        }
        .onChange(of: cam.elapsed) { _, t in
            // Лимит 60с суммарно: автостоп и отправка.
            if recordedBefore + t >= maxDuration, cam.isRecording, !pendingSend {
                send()
            }
        }
        .onDisappear { cam.stop() }
    }

    private var remainingIsOut: Bool { recordedBefore >= maxDuration - 0.5 }

    private func togglePause() {
        if cam.isRecording {
            paused = true
            cam.stopRecording()   // сегмент докопится в segmentFinished
        } else if !remainingIsOut {
            paused = false
            cam.startRecording()
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        }
    }

    private func send() {
        guard !sending else { return }
        if cam.isRecording {
            pendingSend = true
            sending = true
            cam.stopRecording()   // отправим из segmentFinished
        } else if !segments.isEmpty {
            sending = true
            Task { await mergeAndSend() }
        }
    }

    private func segmentFinished(_ url: URL) {
        let duration = cam.elapsed
        if duration >= 0.4 {
            segments.append(url)
            recordedBefore += duration
        } else {
            try? FileManager.default.removeItem(at: url)
        }
        if pendingSend {
            pendingSend = false
            Task { await mergeAndSend() }
        }
    }

    private func mergeAndSend() async {
        defer { sending = false }
        guard !segments.isEmpty else { return }
        let data: Data?
        let duration = min(recordedBefore, maxDuration)
        if segments.count == 1 {
            data = try? Data(contentsOf: segments[0])
        } else {
            // Склейка дозаписи; таймаут, чтобы не зависнуть навсегда.
            let merged: URL? = await withTaskGroup(of: URL?.self) { group in
                group.addTask { await MediaSanitizer.mergeClips(segments) }
                group.addTask {
                    try? await Task.sleep(nanoseconds: 15_000_000_000)
                    return nil
                }
                let first = await group.next() ?? nil
                group.cancelAll()
                return first
            }
            if let merged {
                data = try? Data(contentsOf: merged)
                try? FileManager.default.removeItem(at: merged)
            } else {
                data = try? Data(contentsOf: segments[segments.count - 1])   // фолбэк
            }
        }
        cleanupSegments()
        guard let data else { return }
        onSend(data, duration)
        dismiss()
    }

    private func cleanupSegments() {
        for url in segments { try? FileManager.default.removeItem(at: url) }
        segments = []
        recordedBefore = 0
    }

    private func cancelAndClose() {
        if cam.isRecording { cam.cancelRecording() }
        cleanupSegments()
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
final class CircleCamera: NSObject, ObservableObject, AVCaptureFileOutputRecordingDelegate,
                          AVCaptureAudioDataOutputSampleBufferDelegate {
    let session = AVCaptureSession()
    @Published var available = false
    @Published var isRecording = false
    @Published var elapsed: TimeInterval = 0
    /// Реальная громкость микрофона 0…1 (сглаженный RMS) — для ореола-эквалайзера.
    @Published var audioLevel: Double = 0

    private let output = AVCaptureMovieFileOutput()
    private let audioTap = AVCaptureAudioDataOutput()
    private let audioQueue = DispatchQueue(label: "circle.audio.meter")
    /// Троттлинг публикации уровня (~15 Гц): без него каждый аудио-буфер
    /// (50/с) прыгал на main actor и к концу длинной записи копил фриз.
    private nonisolated(unsafe) var lastLevelPush = Date.distantPast
    private var timer: Timer?
    private var startedAt: Date?
    private var onFinish: ((URL) -> Void)?
    private var videoInput: AVCaptureDeviceInput?
    private var position: AVCaptureDevice.Position = .front
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
        let position = self.position
        let audioTap = self.audioTap
        let audioQueue = self.audioQueue
        let result: AVCaptureDeviceInput? = await Task.detached(priority: .userInitiated) { [weak self] in
            guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position),
                  let input = try? AVCaptureDeviceInput(device: device) else { return nil }
            session.beginConfiguration()
            session.sessionPreset = .high
            if session.canAddInput(input) { session.addInput(input) }
            if let mic = AVCaptureDevice.default(for: .audio),
               let micIn = try? AVCaptureDeviceInput(device: mic),
               session.canAddInput(micIn) { session.addInput(micIn) }
            if session.canAddOutput(output) { session.addOutput(output) }
            // Аудио-метеринг для эквалайзера: отдельный tap на тот же микрофон.
            // Только если сессия согласна держать его рядом с MovieFileOutput —
            // иначе остаёмся без эквалайзера, но со звуком записи.
            if let self, session.canAddOutput(audioTap) {
                audioTap.setSampleBufferDelegate(self, queue: audioQueue)
                session.addOutput(audioTap)
            }
            session.commitConfiguration()
            // Зеркалим запись с фронталки — как в превью.
            if let connection = output.connection(with: .video), connection.isVideoMirroringSupported {
                connection.automaticallyAdjustsVideoMirroring = false
                connection.isVideoMirrored = (position == .front)
            }
            if !session.isRunning { session.startRunning() }
            return input
        }.value
        videoInput = result
        available = result != nil
    }

    // RMS с микрофона → сглаженный уровень 0…1 (поддержка Int16 и Float32 PCM).
    nonisolated func captureOutput(_ output: AVCaptureOutput,
                                   didOutput sampleBuffer: CMSampleBuffer,
                                   from connection: AVCaptureConnection) {
        guard let block = CMSampleBufferGetDataBuffer(sampleBuffer),
              let format = CMSampleBufferGetFormatDescription(sampleBuffer),
              let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(format)?.pointee else { return }
        var length = 0
        var raw: UnsafeMutablePointer<Int8>?
        guard CMBlockBufferGetDataPointer(block, atOffset: 0, lengthAtOffsetOut: nil,
                                          totalLengthOut: &length, dataPointerOut: &raw) == kCMBlockBufferNoErr,
              let raw, length > 0 else { return }

        var sum: Double = 0
        var count = 0
        if asbd.mFormatFlags & kAudioFormatFlagIsFloat != 0 {
            let n = length / MemoryLayout<Float32>.size
            UnsafeRawPointer(raw).withMemoryRebound(to: Float32.self, capacity: n) { p in
                var i = 0
                while i < n { let v = Double(p[i]); sum += v * v; count += 1; i += 8 }
            }
        } else {
            let n = length / MemoryLayout<Int16>.size
            UnsafeRawPointer(raw).withMemoryRebound(to: Int16.self, capacity: n) { p in
                var i = 0
                while i < n { let v = Double(p[i]) / 32768; sum += v * v; count += 1; i += 8 }
            }
        }
        guard count > 0 else { return }
        let rms = (sum / Double(count)).squareRoot()
        let db = 20 * log10(max(rms, 1e-5))               // −100…0 dBFS
        let norm = max(0, min(1, (db + 50) / 44))          // рабочий диапазон речи
        let now = Date()
        guard now.timeIntervalSince(lastLevelPush) > 0.066 else { return }
        lastLevelPush = now
        Task { @MainActor [weak self] in
            guard let self else { return }
            // Атака быстрее спада — «дышит» как эквалайзер.
            self.audioLevel = norm > self.audioLevel
                ? self.audioLevel * 0.35 + norm * 0.65
                : self.audioLevel * 0.82 + norm * 0.18
        }
    }

    /// Переворот камеры (фронт ↔ тыл) — работает и во время записи.
    func switchCamera() {
        guard let current = videoInput else { return }
        let next: AVCaptureDevice.Position = position == .front ? .back : .front
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: next),
              let input = try? AVCaptureDeviceInput(device: device) else { return }
        session.beginConfiguration()
        session.removeInput(current)
        if session.canAddInput(input) {
            session.addInput(input)
            videoInput = input
            position = next
        } else {
            session.addInput(current)   // откат
        }
        session.commitConfiguration()
        if let connection = output.connection(with: .video), connection.isVideoMirroringSupported {
            connection.automaticallyAdjustsVideoMirroring = false
            connection.isVideoMirrored = (position == .front)
        }
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
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
    /// true — бесконечный цикл (превью записи); false — одно воспроизведение
    /// (кружок в чате: доиграл — уменьшился и остановился).
    var loop: Bool = true
    @State private var player: AVQueuePlayer?
    @State private var looper: AVPlayerLooper?
    @State private var isMuted = true
    @State private var isPlaying = false
    /// Выравнивание в ленте (leading/trailing по автору); при увеличении — центр.
    var chatAlignment: Alignment? = nil
    /// Кружок увеличен во время воспроизведения и остаётся таким на паузе;
    /// уменьшается только когда доиграл или уехал с экрана. Рост — лейаутный:
    /// кружок реально становится больше и раздвигает соседние сообщения.
    @State private var enlarged = false
    @State private var scrubStart: Double? = nil

    private var displaySize: CGFloat { enlarged ? size * 1.4 : size }
    private static let growSpring = Animation.spring(response: 0.5, dampingFraction: 0.88)

    private var progress: Double {
        guard let player, let item = player.currentItem else { return 0 }
        let duration = item.duration.seconds
        guard duration.isFinite, duration > 0 else { return 0 }
        return min(1, max(0, player.currentTime().seconds / duration))
    }

    private func setEnlarged(_ value: Bool) {
        withAnimation(Self.growSpring) { enlarged = value }
    }

    private func seek(toProgress value: Double) {
        guard let player, let item = player.currentItem else { return }
        let duration = item.duration.seconds
        guard duration.isFinite, duration > 0 else { return }
        let target = CMTime(seconds: min(0.999, max(0, value)) * duration, preferredTimescale: 600)
        player.seek(to: target, toleranceBefore: .zero, toleranceAfter: .zero)
    }

    var body: some View {
        VideoLayerView(player: player)
            .frame(width: displaySize, height: displaySize)
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
            // Контролы поверх играющего кружка: кольцо прогресса, закрыть, перемотка драгом.
            .overlay {
                if enlarged {
                    TimelineView(.periodic(from: .now, by: 0.1)) { _ in
                        Circle()
                            .trim(from: 0, to: max(0.003, progress))
                            .stroke(.white.opacity(0.9), style: StrokeStyle(lineWidth: 3, lineCap: .round))
                            .rotationEffect(.degrees(-90))
                            .padding(2)
                    }
                    .allowsHitTesting(false)
                    .transition(.opacity)
                }
            }
            .overlay(alignment: .topTrailing) {
                if enlarged {
                    Button {
                        player?.pause()
                        player?.seek(to: .zero)
                        isPlaying = false
                        setEnlarged(false)
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 15, weight: .heavy))
                            .foregroundStyle(.white)
                            .padding(9)
                            .background(.black.opacity(0.65), in: Circle())
                            .overlay(Circle().stroke(.white.opacity(0.35), lineWidth: 1))
                    }
                    .padding(2)
                    .transition(.opacity)
                }
            }
            .frame(maxWidth: chatAlignment != nil ? .infinity : nil,
                   alignment: enlarged ? .center : (chatAlignment ?? .center))
            .onTapGesture {
                if player == nil {
                    setup()
                    isPlaying = true
                    setEnlarged(true)
                    player?.play()
                    return
                }
                isPlaying.toggle()
                if isPlaying {
                    setEnlarged(true)
                    player?.play()
                } else {
                    player?.pause()   // пауза НЕ уменьшает кружок
                }
            }
            // Перемотка: слой с драгом существует ТОЛЬКО у увеличенного кружка —
            // иначе жест перехватывал скролл ленты и всё дёргалось.
            .overlay {
                if enlarged {
                    Circle()
                        .fill(Color.clear)
                        .contentShape(Circle())
                        .onTapGesture {
                            isPlaying.toggle()
                            if isPlaying { player?.play() } else { player?.pause() }
                        }
                        .gesture(
                            DragGesture(minimumDistance: 14)
                                .onChanged { value in
                                    if scrubStart == nil { scrubStart = progress }
                                    let delta = Double(value.translation.width / max(displaySize, 1))
                                    seek(toProgress: (scrubStart ?? 0) + delta)
                                }
                                .onEnded { _ in scrubStart = nil }
                        )
                }
            }
            .onDisappear {
                // Промотал переписку — кружок сворачивается и глохнет.
                player?.pause()
                player?.removeAllItems()
                player = nil
                looper = nil
                isPlaying = false
                enlarged = false
            }
            .onReceive(NotificationCenter.default.publisher(for: .AVPlayerItemDidPlayToEndTime)) { notif in
                // Доиграл (не-loop): уменьшить, остановить, перемотать в начало.
                guard !loop, let item = notif.object as? AVPlayerItem,
                      item == player?.currentItem else { return }
                isPlaying = false
                setEnlarged(false)
                player?.seek(to: .zero)
            }
    }

    private func setup() {
        let item = AVPlayerItem(url: url)
        let q = AVQueuePlayer()
        if loop {
            looper = AVPlayerLooper(player: q, templateItem: item)
        } else {
            q.insert(item, after: nil)
            q.actionAtItemEnd = .pause
        }
        q.isMuted = false   // кружок играет со звуком (запуск и так по тапу)
        isMuted = false
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
