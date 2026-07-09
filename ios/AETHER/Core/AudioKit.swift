import Foundation
import AVFoundation
import Combine

// Запись голосовых сообщений (AAC/m4a). Зажал микрофон → start, отпустил → stop → байты+длительность.
@MainActor
final class VoiceRecorder: ObservableObject {
    @Published var isRecording = false
    @Published var level: CGFloat = 0      // 0…1 для индикатора
    @Published var elapsed: TimeInterval = 0

    private var recorder: AVAudioRecorder?
    private var url: URL?
    private var timer: Timer?
    private var startedAt: Date?

    func requestPermission() async -> Bool {
        await withCheckedContinuation { cont in
            AVAudioApplication.requestRecordPermission { ok in cont.resume(returning: ok) }
        }
    }

    func start() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetoothHFP])
        try? session.setActive(true)

        let out = FileManager.default.temporaryDirectory.appendingPathComponent("voice_\(UUID().uuidString).m4a")
        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: 44100,
            AVNumberOfChannelsKey: 1,
            AVEncoderAudioQualityKey: AVAudioQuality.medium.rawValue,
        ]
        guard let rec = try? AVAudioRecorder(url: out, settings: settings) else { return }
        rec.isMeteringEnabled = true
        rec.record()
        self.recorder = rec
        self.url = out
        self.startedAt = Date()
        self.isRecording = true
        self.elapsed = 0
        timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, let rec = self.recorder else { return }
                rec.updateMeters()
                let power = rec.averagePower(forChannel: 0)   // dB, ~ -60…0
                self.level = CGFloat(max(0, min(1, (power + 55) / 55)))
                self.elapsed = Date().timeIntervalSince(self.startedAt ?? Date())
            }
        }
    }

    /// Остановить и вернуть записанное. nil, если слишком коротко (< 0.4с).
    func finish() -> (data: Data, duration: TimeInterval)? {
        timer?.invalidate(); timer = nil
        recorder?.stop()
        isRecording = false
        let dur = elapsed
        defer { cleanup() }
        guard let url, dur >= 0.4, let data = try? Data(contentsOf: url) else { return nil }
        return (data, dur)
    }

    func cancel() {
        timer?.invalidate(); timer = nil
        recorder?.stop()
        isRecording = false
        cleanup()
    }

    /// Остановить запись, СОХРАНИВ файл (для предпрослушивания/обрезки).
    /// Вызывающий отвечает за удаление файла после отправки/отмены.
    func stopKeepingFile() -> (url: URL, duration: TimeInterval)? {
        timer?.invalidate(); timer = nil
        recorder?.stop()
        isRecording = false
        let duration = elapsed
        guard let kept = url, duration >= 0.4 else { cleanup(); return nil }
        recorder = nil; url = nil; startedAt = nil; level = 0
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        return (kept, duration)
    }

    private func cleanup() {
        if let url { try? FileManager.default.removeItem(at: url) }
        recorder = nil; url = nil; startedAt = nil; level = 0
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}

// Проигрывание голосовых/аудио с прогрессом. Один общий плеер (останавливает предыдущий).
@MainActor
final class AudioPlaybackManager: ObservableObject {
    static let shared = AudioPlaybackManager()

    @Published private(set) var playingId: String?
    private let progressSubject = CurrentValueSubject<Double, Never>(0)
    var progressPublisher: AnyPublisher<Double, Never> { progressSubject.eraseToAnyPublisher() }

    private var player: AVAudioPlayer?
    private var timer: Timer?

    func toggle(id: String, data: Data) {
        if playingId == id { stop(); return }
        stop()
        try? AVAudioSession.sharedInstance().setCategory(.playback)
        try? AVAudioSession.sharedInstance().setActive(true)
        guard let p = try? AVAudioPlayer(data: data) else { return }
        p.play()
        player = p
        playingId = id
        progressSubject.send(0)
        timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, let p = self.player else { return }
                self.progressSubject.send(p.duration > 0 ? p.currentTime / p.duration : 0)
                if !p.isPlaying && p.currentTime >= p.duration - 0.05 { self.stop() }
            }
        }
    }

    func stop() {
        timer?.invalidate(); timer = nil
        player?.stop(); player = nil
        playingId = nil
        progressSubject.send(0)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}


// Обрезка голосового: экспорт выбранного диапазона m4a без перекодирования качества.
enum AudioTrimmer {
    static func trim(url: URL, start: TimeInterval, end: TimeInterval) async -> (data: Data, duration: TimeInterval)? {
        let duration = max(0, end - start)
        guard duration >= 0.3 else { return nil }
        let asset = AVURLAsset(url: url)
        guard let export = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else {
            return fallback(url: url, duration: duration)
        }
        let out = FileManager.default.temporaryDirectory.appendingPathComponent("trim_\(UUID().uuidString).m4a")
        defer { try? FileManager.default.removeItem(at: out) }
        export.outputURL = out
        export.outputFileType = .m4a
        export.timeRange = CMTimeRange(
            start: CMTime(seconds: start, preferredTimescale: 600),
            end: CMTime(seconds: end, preferredTimescale: 600)
        )
        await export.export()
        guard export.status == .completed, let data = try? Data(contentsOf: out) else {
            return fallback(url: url, duration: duration)
        }
        return (data, duration)
    }

    /// Экспорт не удался — шлём файл целиком, чтобы запись не потерялась.
    private static func fallback(url: URL, duration: TimeInterval) -> (data: Data, duration: TimeInterval)? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return (data, duration)
    }
}
