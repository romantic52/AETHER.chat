import AVFoundation
import Combine
import MediaPlayer
import SwiftUI

/// Единый центр звука: музыка и голосовые из просмотрщика. Живёт ДОЛЬШЕ экрана,
/// поэтому трек не обрывается, когда просмотрщик закрыли, — как в Telegram, где
/// музыка продолжает играть, пока листаешь переписку. Отсюда же берутся экран
/// блокировки и Пункт управления.
///
/// Видео сюда не заводим: его плеер привязан к экрану и умирает вместе с ним.
@MainActor
final class MediaPlaybackCenter: ObservableObject {
    static let shared = MediaPlaybackCenter()

    struct Track: Equatable {
        let id: String
        let title: String
        let subtitle: String
    }

    @Published private(set) var track: Track?
    /// Сам конверт играющего: мини-плееру нужно чем-то открыть полный экран.
    @Published private(set) var currentPayload: Wire.Payload?
    @Published private(set) var isPlaying = false
    @Published private(set) var current: Double = 0
    @Published private(set) var duration: Double = 0
    @Published private(set) var rate: Float = 1
    /// Идёт подготовка файла (скачивание и расшифровка) — показываем это и в
    /// пузыре, и в мини-плеере, иначе тап выглядит как «ничего не произошло».
    @Published private(set) var preparing: String?

    private var player: AVPlayer?
    private var timeObserver: Any?
    private var endObserver: NSObjectProtocol?
    private var commandsInstalled = false
    private var ownsAudioSession = false

    /// Очередь чата: соседние аудио, чтобы «вперёд/назад» вели к следующему
    /// треку, а не в тупик. Храним payload, а не готовые файлы: материализация
    /// каждого стоила бы скачивания всего чата разом.
    private var queue: [Wire.Payload] = []
    private var queueIndex = 0

    var hasNext: Bool { queueIndex + 1 < queue.count }
    var hasPrevious: Bool { queueIndex > 0 }

    // MARK: - Запуск

    /// Поставить трек. `queue` — все аудио того же чата в порядке ленты.
    func play(_ payload: Wire.Payload, queue: [Wire.Payload] = []) async {
        let id = Self.identity(payload)
        // Повторный тап по играющему треку — пауза/продолжение, а не перезапуск
        // с начала: перезапуск на длинной записи особенно обиден.
        if track?.id == id, player != nil {
            toggle()
            return
        }

        self.queue = queue.isEmpty ? [payload] : queue
        queueIndex = self.queue.firstIndex { Self.identity($0) == id } ?? 0
        await start(payload)
    }

    private func start(_ payload: Wire.Payload) async {
        let id = Self.identity(payload)
        preparing = id
        let ext = payload.mediaKind == .voice ? "m4a" : Self.extension(for: payload)
        guard let url = await MediaStore.shared.materialize(payload: payload, fallbackExtension: ext) else {
            preparing = nil
            return
        }
        preparing = nil

        teardownPlayer()
        prepareSession()

        let item = AVPlayerItem(url: url)
        let player = AVPlayer(playerItem: item)
        player.actionAtItemEnd = .pause
        self.player = player

        track = Track(id: id, title: Self.title(payload), subtitle: Self.subtitle(payload))
        currentPayload = payload
        current = 0
        duration = 0

        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.2, preferredTimescale: 600), queue: .main
        ) { [weak self] time in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.current = time.seconds.isFinite ? max(0, time.seconds) : 0
                let total = self.player?.currentItem?.duration.seconds ?? 0
                if total.isFinite, total > 0 { self.duration = total }
                self.refreshNowPlayingTime()
            }
        }
        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                // Доиграл — сам переходим к следующему, как в плеере Telegram.
                if self.hasNext { self.next() } else { self.isPlaying = false; self.pushNowPlaying() }
            }
        }

        installCommands()
        resume()
    }

    // MARK: - Управление

    func toggle() { isPlaying ? pause() : resume() }

    func resume() {
        guard let player else { return }
        prepareSession()
        if duration > 0, current >= duration - 0.1 { seek(to: 0) }
        player.playImmediately(atRate: rate)
        isPlaying = true
        pushNowPlaying()
    }

    func pause() {
        player?.pause()
        isPlaying = false
        pushNowPlaying()
    }

    func seek(to seconds: Double) {
        guard let player else { return }
        let clamped = min(max(0, seconds), max(duration, 0))
        player.seek(to: CMTime(seconds: clamped, preferredTimescale: 600),
                    toleranceBefore: .zero, toleranceAfter: .zero)
        current = clamped
        refreshNowPlayingTime()
    }

    func skip(_ seconds: Double) { seek(to: current + seconds) }

    func cycleRate() {
        let rates: [Float] = [1, 1.5, 2, 0.5]
        rate = rates[((rates.firstIndex(of: rate) ?? 0) + 1) % rates.count]
        if isPlaying { player?.playImmediately(atRate: rate) }
        pushNowPlaying()
    }

    var rateText: String { rate == 1 ? "1×" : "\(rate.formatted())×" }

    func next() {
        guard hasNext else { return }
        queueIndex += 1
        Task { await start(queue[queueIndex]) }
    }

    func previous() {
        // Как у всех плееров: первые секунды — «в начало», дальше — предыдущий.
        guard current < 3, hasPrevious else { seek(to: 0); return }
        queueIndex -= 1
        Task { await start(queue[queueIndex]) }
    }

    /// Полная остановка: закрыли мини-плеер.
    func stop() {
        teardownPlayer()
        track = nil
        currentPayload = nil
        isPlaying = false
        current = 0
        duration = 0
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        guard ownsAudioSession else { return }
        ownsAudioSession = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    func isCurrent(_ payload: Wire.Payload) -> Bool { track?.id == Self.identity(payload) }

    private func teardownPlayer() {
        if let timeObserver { player?.removeTimeObserver(timeObserver) }
        timeObserver = nil
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        endObserver = nil
        player?.pause()
        player = nil
    }

    // MARK: - Сессия и экран блокировки

    private func prepareSession() {
        let session = AVAudioSession.sharedInstance()
        // Не перестраиваем voiceChat-сессию активного звонка: музыка не стоит
        // того, чтобы уронить разговор.
        guard session.mode != .voiceChat && session.mode != .videoChat else { return }
        AudioPlaybackManager.shared.stop()   // короткий плеер голосовых в пузыре
        try? session.setCategory(.playback, mode: .default)
        try? session.setActive(true)
        ownsAudioSession = true
    }

    private func installCommands() {
        guard !commandsInstalled else { return }
        commandsInstalled = true
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated { self?.resume() }
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated { self?.pause() }
            return .success
        }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated { self?.toggle() }
            return .success
        }
        center.skipForwardCommand.preferredIntervals = [15]
        center.skipForwardCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated { self?.skip(15) }
            return .success
        }
        center.skipBackwardCommand.preferredIntervals = [15]
        center.skipBackwardCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated { self?.skip(-15) }
            return .success
        }
        center.nextTrackCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated { self?.next() }
            return .success
        }
        center.previousTrackCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated { self?.previous() }
            return .success
        }
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            MainActor.assumeIsolated { self?.seek(to: event.positionTime) }
            return .success
        }
    }

    /// Полная карточка: имя, отправитель, длительность. Отдаём только название и
    /// чат — ни текста сообщения, ни содержимого файла на экран блокировки.
    private func pushNowPlaying() {
        guard let track else { return }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: track.title,
            MPMediaItemPropertyArtist: track.subtitle,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? Double(rate) : 0,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: current,
        ]
        if duration > 0 { info[MPMediaItemPropertyPlaybackDuration] = duration }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    private func refreshNowPlayingTime() {
        guard var info = MPNowPlayingInfoCenter.default().nowPlayingInfo else {
            pushNowPlaying()
            return
        }
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = current
        if duration > 0 { info[MPMediaItemPropertyPlaybackDuration] = duration }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    // MARK: - Описание трека

    /// Чистая функция без состояния — доступна откуда угодно, иначе карточку
    /// галереи нельзя было бы собрать вне главного потока.
    nonisolated static func identity(_ payload: Wire.Payload) -> String {
        payload.fileId ?? payload.fileName ?? "inline_\(payload.raw.count)"
    }

    private static func title(_ payload: Wire.Payload) -> String {
        if let name = payload.fileName, !name.isEmpty { return name }
        return payload.mediaKind == .voice ? "Голосовое сообщение" : "Аудио"
    }

    private static func subtitle(_ payload: Wire.Payload) -> String {
        if let caption = payload.caption, !caption.isEmpty { return caption }
        return "AETHER"
    }

    private static func `extension`(for payload: Wire.Payload) -> String {
        if let name = payload.fileName, name.contains("."),
           let ext = name.split(separator: ".").last, ext.count <= 5 {
            return String(ext)
        }
        return "m4a"
    }
}
