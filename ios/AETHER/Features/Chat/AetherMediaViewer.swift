import AVFoundation
import QuickLook
import SwiftUI
import UIKit

/// Одна карточка галереи. Идентификатор нужен ForEach и постраничному листанию,
/// а `Wire.Payload` для этого не годится: внутри него сырой словарь.
struct MediaItem: Identifiable, Equatable {
    let id: String
    let payload: Wire.Payload

    init(_ payload: Wire.Payload) {
        self.payload = payload
        self.id = MediaPlaybackCenter.identity(payload)
    }

    static func == (lhs: MediaItem, rhs: MediaItem) -> Bool { lhs.id == rhs.id }
}

/// Все медиа открытого чата — чтобы просмотрщик листался, а не показывал одну
/// карточку. Отдаём ССЫЛОЧНОЙ коробкой: пузырь лежит глубоко, тащить массив
/// параметром через все промежуточные вью — шум, а класс в среде не заставляет
/// SwiftUI перерисовывать ленту при каждом новом сообщении.
final class ChatGallery {
    var items: [Wire.Payload] = []

    /// Что листается: фото и видео. Музыка и документы живут в ленте своей
    /// жизнью, подмешивать их в перелистывание фотографий неудобно.
    func viewable() -> [MediaItem] {
        items.filter { $0.mediaKind == .image || $0.mediaKind == .video }.map(MediaItem.init)
    }

    /// Просмотрщик, открытый на этом медиа. Если ленты нет (например, открыли из
    /// профиля группы) — показываем одну карточку, как раньше.
    @MainActor
    func viewer(opening payload: Wire.Payload) -> AetherMediaViewer {
        let list = viewable()
        let id = MediaPlaybackCenter.identity(payload)
        guard let start = list.firstIndex(where: { $0.id == id }) else {
            return AetherMediaViewer(payload: payload)
        }
        return AetherMediaViewer(items: list, start: start)
    }

    /// Все аудио чата — очередь плеера.
    func audioQueue() -> [Wire.Payload] {
        items.filter { $0.mediaKind == .audio }
    }
}

private struct ChatGalleryKey: EnvironmentKey {
    static let defaultValue = ChatGallery()
}

extension EnvironmentValues {
    var chatGallery: ChatGallery {
        get { self[ChatGalleryKey.self] }
        set { self[ChatGalleryKey.self] = newValue }
    }
}

/// Полноэкранный просмотрщик AETHER: фото, видео, музыка и документы под одним
/// chrome. Листается вбок, закрывается потягиванием вниз, тап убирает панели —
/// то, чего ждёшь от галереи мессенджера.
struct AetherMediaViewer: View {
    let items: [MediaItem]
    @State private var index: Int

    init(payload: Wire.Payload) {
        items = [MediaItem(payload)]
        _index = State(initialValue: 0)
    }

    init(items: [MediaItem], start: Int) {
        self.items = items
        _index = State(initialValue: min(max(start, 0), max(items.count - 1, 0)))
    }

    @Environment(\.dismiss) private var dismiss
    @State private var chromeHidden = false
    @State private var dragY: CGFloat = 0
    @State private var zoomed = false
    @State private var rotation: [String: Int] = [:]
    @State private var sharing: ActivityItems?

    private var item: MediaItem? { items.indices.contains(index) ? items[index] : nil }
    private var payload: Wire.Payload? { item?.payload }
    /// 0 в покое, 1 у порога закрытия. Фон гаснет ровно на столько же — картинка
    /// «отрывается» от экрана, а не проваливается в чёрное.
    private var dismissProgress: CGFloat { min(abs(dragY) / 240, 1) }

    var body: some View {
        ZStack {
            // Double(...) не для красоты: opacity принимает Double, а
            // dismissProgress — CGFloat, и Swift 26.1 объявляет «1 - x * 0.7»
            // неоднозначным (какой из перегруженных «-» брать). На 26.6 вывод
            // типов справляется сам, поэтому локально ошибки не видно, а сборка
            // в CI падает. Приводим явно.
            Color.black.opacity(Double(1 - dismissProgress * 0.7)).ignoresSafeArea()

            pages
                .offset(y: dragY)
                .scaleEffect(1 - dismissProgress * 0.12)

            if !chromeHidden {
                topBar.transition(.opacity)
                if payload?.mediaKind == .image { imageTools.transition(.opacity) }
            }
        }
        .statusBarHidden()
        .simultaneousGesture(dismissDrag)
        .sheet(item: $sharing) { ActivityView(items: $0.values) }
        // Зум сбрасывается сменой страницы: иначе следующее фото открывается
        // приближённым и непонятно, куда смотреть.
        .onChange(of: index) { _, _ in zoomed = false }
    }

    @ViewBuilder private var pages: some View {
        if items.isEmpty {
            EmptyView()
        } else if items.count == 1, let item {
            page(item)
        } else {
            TabView(selection: $index) {
                ForEach(Array(items.enumerated()), id: \.element.id) { position, item in
                    page(item).tag(position)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
        }
    }

    @ViewBuilder private func page(_ pageItem: MediaItem) -> some View {
        MediaPage(
            item: pageItem,
            chromeHidden: $chromeHidden,
            zoomed: $zoomed,
            quarterTurns: Binding(
                get: { rotation[pageItem.id] ?? 0 },
                set: { rotation[pageItem.id] = $0 }
            ),
            active: pageItem.id == item?.id,
            // Соседние страницы готовим заранее: иначе на каждом перелистывании
            // на месте фотографии секунду висит спиннер. Дальше соседей не
            // трогаем — незачем тянуть весь чат.
            preload: abs((items.firstIndex(of: pageItem) ?? 0) - index) <= 1,
            audioQueue: audioQueue
        )
    }

    /// Очередь для плеера собирается из самой галереи: если просмотрщик открыт
    /// списком, соседние аудио уже здесь.
    private var audioQueue: [Wire.Payload] {
        items.map(\.payload).filter { $0.mediaKind == .audio }
    }

    // MARK: - Панели

    private var topBar: some View {
        VStack {
            HStack(spacing: 12) {
                roundButton("xmark", label: "Закрыть") { dismiss() }
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.system(size: 16, weight: .semibold)).lineLimit(1)
                    if items.count > 1 {
                        Text("\(index + 1) из \(items.count)")
                            .font(.caption).foregroundStyle(.white.opacity(0.7))
                    } else if let caption = payload?.caption, !caption.isEmpty {
                        Text(caption).font(.caption).lineLimit(1).foregroundStyle(.white.opacity(0.7))
                    }
                }
                .foregroundStyle(.white)
                Spacer()
                roundButton("square.and.arrow.up", label: "Поделиться") { Task { await share() } }
            }
            .padding(.horizontal, 16).padding(.top, 8)
            Spacer()
        }
    }

    private var imageTools: some View {
        VStack {
            Spacer()
            HStack(spacing: 18) {
                roundButton("rotate.left", label: "Повернуть влево") { turn(-1) }
                roundButton("arrow.counterclockwise", label: "Сбросить изменения") { reset() }
                    .opacity(currentTurns == 0 ? 0.45 : 1)
                    .disabled(currentTurns == 0)
                roundButton("rotate.right", label: "Повернуть вправо") { turn(1) }
            }
            .padding(.horizontal, 18).padding(.vertical, 12)
            .background(.ultraThinMaterial, in: Capsule())
            .padding(.bottom, 18)
        }
    }

    private var currentTurns: Int { item.map { rotation[$0.id] ?? 0 } ?? 0 }

    private func turn(_ delta: Int) {
        guard let item else { return }
        withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) {
            rotation[item.id] = (rotation[item.id] ?? 0) + delta
        }
    }

    private func reset() {
        guard let item else { return }
        withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) { rotation[item.id] = 0 }
    }

    private func roundButton(_ systemName: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 17, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: 44, height: 44)
                .background(.ultraThinMaterial, in: Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    private var title: String {
        guard let payload else { return "Медиа" }
        if let name = payload.fileName, !name.isEmpty { return name }
        switch payload.mediaKind {
        case .image: return "Фото"
        case .video, .videoNote: return "Видео"
        case .audio: return "Аудио"
        case .voice: return "Голосовое сообщение"
        case .file: return "Файл"
        }
    }

    // MARK: - Закрытие потягиванием

    private var dismissDrag: some Gesture {
        DragGesture(minimumDistance: 12)
            .onChanged { value in
                // Приближённое фото таскают, а не закрывают: там жест занят.
                guard !zoomed else { return }
                guard abs(value.translation.height) > abs(value.translation.width) else { return }
                dragY = value.translation.height
            }
            .onEnded { value in
                guard !zoomed else { return }
                let far = abs(value.translation.height) > 120
                let fast = abs(value.predictedEndTranslation.height) > 320
                if far || fast {
                    dismiss()
                } else {
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) { dragY = 0 }
                }
            }
    }

    private func share() async {
        guard let payload else { return }
        if payload.mediaKind == .image {
            guard let image = await MediaStore.shared.image(payload: payload, maxPixel: 4096) else { return }
            sharing = ActivityItems(values: [image.rotatedQuarterTurns(currentTurns)])
        } else if let url = await MediaStore.shared.materialize(payload: payload, fallbackExtension: "bin") {
            sharing = ActivityItems(values: [url])
        }
    }
}

// MARK: - Страница

/// Одна страница галереи. Грузится ТОЛЬКО когда стала активной: иначе открытие
/// чата с полусотней фотографий разом тянуло бы их все.
private struct MediaPage: View {
    let item: MediaItem
    @Binding var chromeHidden: Bool
    @Binding var zoomed: Bool
    @Binding var quarterTurns: Int
    let active: Bool
    let preload: Bool
    let audioQueue: [Wire.Payload]

    @State private var image: UIImage?
    @State private var mediaURL: URL?
    @State private var loadError: String?
    @State private var loaded = false

    private var payload: Wire.Payload { item.payload }
    private var kind: Wire.MediaKind { payload.mediaKind }

    var body: some View {
        ZStack {
            if let loadError {
                failure(loadError)
            } else {
                switch kind {
                case .image:
                    if let image {
                        ZoomableMediaImage(image: image, quarterTurns: quarterTurns, zoomed: $zoomed)
                            .onTapGesture { toggleChrome() }
                    } else {
                        ProgressView().tint(.white)
                    }
                case .video, .videoNote:
                    if let mediaURL {
                        VideoPage(url: mediaURL, active: active, onTapBackground: toggleChrome)
                    } else {
                        ProgressView().tint(.white)
                    }
                case .audio, .voice:
                    AudioPage(payload: payload, queue: audioQueue)
                case .file:
                    if let mediaURL {
                        // Документы отдаём системному просмотру: он умеет PDF,
                        // офисные форматы и архивы, свой такой писать незачем.
                        DocumentPage(url: mediaURL)
                    } else {
                        ProgressView().tint(.white)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task(id: preload) {
            guard preload, !loaded else { return }
            loaded = true
            await load()
        }
    }

    private func toggleChrome() {
        withAnimation(.easeInOut(duration: 0.18)) { chromeHidden.toggle() }
    }

    private func failure(_ text: String) -> some View {
        VStack(spacing: 14) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 42)).foregroundStyle(.yellow)
            Text(text).font(.headline).foregroundStyle(.white)
                .multilineTextAlignment(.center).padding(.horizontal, 32)
            if mediaURL != nil {
                Text("Файл можно сохранить кнопкой сверху.")
                    .font(.subheadline).foregroundStyle(.white.opacity(0.65))
            }
        }
    }

    private func load() async {
        if kind == .image {
            image = await MediaStore.shared.image(payload: payload, maxPixel: 4096)
            if image == nil { loadError = "Не удалось открыть фото." }
            return
        }
        // Аудио материализует сам центр воспроизведения — здесь только видео и
        // документы, иначе один и тот же файл качался бы дважды.
        guard kind != .audio, kind != .voice else { return }

        let ext: String
        switch kind {
        case .video, .videoNote: ext = "mp4"
        default: ext = "bin"
        }
        guard let url = await MediaStore.shared.materialize(payload: payload, fallbackExtension: ext) else {
            loadError = "Не удалось скачать или расшифровать файл."
            return
        }
        mediaURL = url
        guard kind != .file else { return }
        do {
            let playable = try await AVURLAsset(url: url).load(.isPlayable)
            if !playable { loadError = "Этот формат пока не поддерживается плеером iPhone." }
        } catch {
            loadError = "Этот формат пока не поддерживается плеером iPhone."
        }
    }
}

// MARK: - Документ

/// Документ внутри галереи. Берём голый системный просмотр, а не QuickLookCover:
/// у того своя кнопка закрытия, и она встала бы ровно под нашей. Отступ сверху —
/// под нашу панель: QuickLook рисует от самого края и заезжал под заголовок.
private struct DocumentPage: View {
    let url: URL

    var body: some View {
        QuickLookView(url: url)
            .padding(.top, 76)
            .background(Color.black)
    }
}

private struct QuickLookView: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> QLPreviewController {
        let controller = QLPreviewController()
        controller.dataSource = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: QLPreviewController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(url: url) }

    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        let url: URL
        init(url: URL) { self.url = url }
        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }
        func previewController(_ controller: QLPreviewController,
                               previewItemAt index: Int) -> QLPreviewItem { url as NSURL }
    }
}

// MARK: - Картинка

private struct ZoomableMediaImage: View {
    let image: UIImage
    let quarterTurns: Int
    @Binding var zoomed: Bool

    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero

    var body: some View {
        Image(uiImage: image)
            .resizable().scaledToFit()
            .rotationEffect(.degrees(Double(quarterTurns * 90)))
            .scaleEffect(scale)
            .offset(offset)
            .contentShape(Rectangle())
            .gesture(
                MagnificationGesture()
                    .onChanged { scale = min(6, max(1, lastScale * $0)) }
                    .onEnded { _ in
                        lastScale = scale
                        if scale <= 1 { resetPosition() } else { zoomed = true }
                    }
            )
            // Тянуть приближённое фото важнее, чем листать: пока увеличено,
            // жест забираем у постраничной прокрутки высоким приоритетом.
            .highPriorityGesture(panGesture, isEnabled: scale > 1)
            .onTapGesture(count: 2) {
                withAnimation(.easeOut(duration: 0.2)) {
                    if scale > 1 { resetPosition() } else { scale = 2.5; lastScale = 2.5; zoomed = true }
                }
            }
            .onChange(of: quarterTurns) { _, _ in resetPosition() }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .ignoresSafeArea()
    }

    private var panGesture: some Gesture {
        DragGesture()
            .onChanged { value in
                guard scale > 1 else { return }
                offset = CGSize(width: lastOffset.width + value.translation.width,
                                height: lastOffset.height + value.translation.height)
            }
            .onEnded { _ in lastOffset = offset }
    }

    private func resetPosition() {
        scale = 1; lastScale = 1; offset = .zero; lastOffset = .zero
        zoomed = false
    }
}

// MARK: - Видео

/// Видео с собственными контролами. Плеер живёт вместе со страницей: уносить
/// его в общий центр незачем — в фоне видео всё равно не играет.
private struct VideoPage: View {
    let url: URL
    let active: Bool
    var onTapBackground: () -> Void

    @StateObject private var model: VideoPlayerModel
    @State private var showControls = true

    init(url: URL, active: Bool, onTapBackground: @escaping () -> Void) {
        self.url = url
        self.active = active
        self.onTapBackground = onTapBackground
        _model = StateObject(wrappedValue: VideoPlayerModel(url: url))
    }

    var body: some View {
        ZStack {
            PlayerLayer(player: model.player).ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture {
                    withAnimation(.easeInOut(duration: 0.18)) { showControls.toggle() }
                    onTapBackground()
                }
            if showControls {
                VStack {
                    Spacer()
                    PlaybackControls(
                        current: model.current, duration: model.duration, isPlaying: model.isPlaying,
                        rateText: model.rateText,
                        onSeek: model.seek, onSkip: model.skip,
                        onToggle: model.toggle, onRate: model.cycleRate
                    )
                }
                .transition(.opacity)
            }
        }
        .onAppear { if active { model.play() } }
        .onChange(of: active) { _, isActive in
            // Ушли на соседнюю страницу — звук не должен идти из-за кадра.
            if isActive { model.play() } else { model.pause() }
        }
        .onDisappear { model.finish() }
    }
}

// MARK: - Музыка и голосовые

/// Аудио отдаём общему центру: закрытый просмотрщик не должен обрывать трек.
private struct AudioPage: View {
    let payload: Wire.Payload
    let queue: [Wire.Payload]

    @ObservedObject private var center = MediaPlaybackCenter.shared

    private var isMine: Bool { center.isCurrent(payload) }

    var body: some View {
        ZStack {
            LinearGradient(colors: [Color.black, Color(red: 0.08, green: 0.10, blue: 0.16)],
                           startPoint: .top, endPoint: .bottom).ignoresSafeArea()
            VStack(spacing: 18) {
                Image(systemName: "waveform.circle.fill")
                    .font(.system(size: 132, weight: .thin))
                    .foregroundStyle(.white.opacity(0.88), Color.accentColor.opacity(0.75))
                Text(payload.fileName ?? (payload.mediaKind == .voice ? "Голосовое сообщение" : "Аудио"))
                    .font(.title3.weight(.semibold)).foregroundStyle(.white).lineLimit(2)
                    .multilineTextAlignment(.center).padding(.horizontal, 32)
                if center.preparing == MediaPlaybackCenter.identity(payload) {
                    ProgressView().tint(.white)
                }
            }
            .offset(y: -72)

            VStack {
                Spacer()
                PlaybackControls(
                    current: isMine ? center.current : 0,
                    duration: isMine ? center.duration : 0,
                    isPlaying: isMine && center.isPlaying,
                    rateText: center.rateText,
                    onSeek: { center.seek(to: $0) },
                    onSkip: { center.skip($0) },
                    onToggle: { Task { await center.play(payload, queue: queue) } },
                    onRate: { center.cycleRate() }
                )
            }
        }
        .task(id: payload.fileId) {
            // Открыли карточку — сразу играем, как в Telegram. Уже играющий трек
            // не перезапускаем: play() сам это различает.
            await center.play(payload, queue: queue)
        }
    }
}

// MARK: - Общие контролы

private struct PlaybackControls: View {
    let current: Double
    let duration: Double
    let isPlaying: Bool
    let rateText: String
    let onSeek: (Double) -> Void
    let onSkip: (Double) -> Void
    let onToggle: () -> Void
    let onRate: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            Slider(value: Binding(get: { current }, set: onSeek), in: 0...max(1, duration))
                .tint(.white)
            HStack {
                Text(Self.time(current))
                Spacer()
                Text("−\(Self.time(max(0, duration - current)))")
            }
            .font(.caption.monospacedDigit()).foregroundStyle(.white.opacity(0.75))
            HStack(spacing: 30) {
                Button { onSkip(-15) } label: { Image(systemName: "gobackward.15") }
                Button(action: onToggle) {
                    Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 28, weight: .bold))
                        .frame(width: 58, height: 58)
                        .background(.white, in: Circle()).foregroundStyle(.black)
                }
                Button { onSkip(15) } label: { Image(systemName: "goforward.15") }
                Button(action: onRate) {
                    Text(rateText).font(.system(size: 13, weight: .bold)).frame(width: 38)
                }
            }
            .font(.system(size: 25, weight: .semibold)).foregroundStyle(.white)
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 18).padding(.vertical, 16)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .padding(.horizontal, 12).padding(.bottom, 14)
    }

    private static func time(_ value: Double) -> String {
        guard value.isFinite else { return "0:00" }
        let seconds = max(0, Int(value.rounded()))
        return String(format: "%d:%02d", seconds / 60, seconds % 60)
    }
}

@MainActor
private final class VideoPlayerModel: ObservableObject {
    let player: AVPlayer
    @Published var current = 0.0
    @Published var duration = 0.0
    @Published var isPlaying = false
    @Published private(set) var rate: Float = 1
    private var observer: Any?
    private var ownsAudioSession = false

    init(url: URL) {
        player = AVPlayer(url: url)
        observer = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.2, preferredTimescale: 600), queue: .main
        ) { [weak self] time in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.current = time.seconds.isFinite ? max(0, time.seconds) : 0
                let value = self.player.currentItem?.duration.seconds ?? 0
                if value.isFinite { self.duration = max(0, value) }
                if self.duration > 0, self.current >= self.duration - 0.05 { self.isPlaying = false }
            }
        }
    }

    deinit {
        if let observer { player.removeTimeObserver(observer) }
    }

    func play() {
        prepareAudioSession()
        if duration > 0, current >= duration - 0.1 { seek(0) }
        player.playImmediately(atRate: rate)
        isPlaying = true
    }

    func pause() { player.pause(); isPlaying = false }

    func finish() {
        pause()
        guard ownsAudioSession else { return }
        ownsAudioSession = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    func toggle() { isPlaying ? pause() : play() }

    func seek(_ seconds: Double) {
        let clamped = min(max(0, seconds), max(duration, 0))
        player.seek(to: CMTime(seconds: clamped, preferredTimescale: 600),
                    toleranceBefore: .zero, toleranceAfter: .zero)
        current = clamped
    }

    func skip(_ seconds: Double) { seek(current + seconds) }

    func cycleRate() {
        let rates: [Float] = [0.5, 1, 1.5, 2]
        rate = rates[((rates.firstIndex(of: rate) ?? 0) + 1) % rates.count]
        if isPlaying { player.playImmediately(atRate: rate) }
    }

    var rateText: String { rate == 1 ? "1×" : "\(rate.formatted())×" }

    private func prepareAudioSession() {
        guard !ownsAudioSession else { return }
        let session = AVAudioSession.sharedInstance()
        // Не перестраиваем voiceChat-сессию активного WebRTC-звонка.
        guard session.mode != .voiceChat && session.mode != .videoChat else { return }
        AudioPlaybackManager.shared.stop()
        MediaPlaybackCenter.shared.pause()
        try? session.setCategory(.playback, mode: .moviePlayback)
        try? session.setActive(true)
        ownsAudioSession = true
    }
}

private final class PlayerUIView: UIView {
    override static var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
}

private struct PlayerLayer: UIViewRepresentable {
    let player: AVPlayer
    func makeUIView(context: Context) -> PlayerUIView {
        let view = PlayerUIView()
        view.backgroundColor = .black
        view.playerLayer.videoGravity = .resizeAspect
        view.playerLayer.player = player
        return view
    }
    func updateUIView(_ view: PlayerUIView, context: Context) { view.playerLayer.player = player }
}

private struct ActivityItems: Identifiable {
    let id = UUID()
    let values: [Any]
}

private struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

private extension UIImage {
    func rotatedQuarterTurns(_ turns: Int) -> UIImage {
        let normalized = (turns % 4 + 4) % 4
        guard normalized != 0 else { return self }
        let swap = normalized % 2 == 1
        let size = swap ? CGSize(width: self.size.height, height: self.size.width) : self.size
        let format = UIGraphicsImageRendererFormat()
        format.scale = scale
        return UIGraphicsImageRenderer(size: size, format: format).image { context in
            let cg = context.cgContext
            cg.translateBy(x: size.width / 2, y: size.height / 2)
            cg.rotate(by: CGFloat(normalized) * .pi / 2)
            draw(in: CGRect(x: -self.size.width / 2, y: -self.size.height / 2,
                            width: self.size.width, height: self.size.height))
        }
    }
}
