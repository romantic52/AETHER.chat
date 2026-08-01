import AVFoundation
import SwiftUI
import UIKit

/// Единый полноэкранный просмотрщик AETHER: фото, видео и музыка используют
/// один chrome, а AVPlayer остаётся только движком под собственными контролами.
struct AetherMediaViewer: View {
    let payload: Wire.Payload

    @Environment(\.dismiss) private var dismiss
    @State private var image: UIImage?
    @State private var mediaURL: URL?
    @State private var quarterTurns = 0
    @State private var sharing: ActivityItems?
    @State private var loadError: String?

    private var kind: Wire.MediaKind { payload.mediaKind }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            content
            topBar
            if kind == .image { imageTools }
        }
        .statusBarHidden()
        .task(id: payload.fileId ?? payload.fileName) { await load() }
        .sheet(item: $sharing) { ActivityView(items: $0.values) }
    }

    @ViewBuilder private var content: some View {
        if let loadError {
            VStack(spacing: 14) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 42)).foregroundStyle(.yellow)
                Text(loadError).font(.headline).foregroundStyle(.white).multilineTextAlignment(.center)
                    .padding(.horizontal, 32)
                if mediaURL != nil {
                    Text("Файл можно сохранить кнопкой сверху.")
                        .font(.subheadline).foregroundStyle(.white.opacity(0.65))
                }
            }
        } else {
            switch kind {
            case .image:
                if let image {
                    ZoomableMediaImage(image: image, quarterTurns: quarterTurns)
                } else {
                    ProgressView().tint(.white)
                }
            case .video, .videoNote:
                if let mediaURL {
                    AetherPlaybackView(url: mediaURL, video: true)
                } else {
                    ProgressView().tint(.white)
                }
            case .audio, .voice:
                if let mediaURL {
                    AetherPlaybackView(url: mediaURL, video: false,
                                       title: payload.fileName ?? (kind == .voice ? "Голосовое сообщение" : "Аудио"))
                } else {
                    ProgressView().tint(.white)
                }
            case .file:
                ProgressView().tint(.white)
            }
        }
    }

    private var topBar: some View {
        VStack {
            HStack(spacing: 12) {
                roundButton("xmark", label: "Закрыть") { dismiss() }
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.system(size: 16, weight: .semibold))
                    if let caption = payload.caption, !caption.isEmpty {
                        Text(caption).font(.caption).lineLimit(1).foregroundStyle(.white.opacity(0.7))
                    }
                }
                .foregroundStyle(.white)
                Spacer()
                roundButton("square.and.arrow.up", label: "Поделиться") { share() }
            }
            .padding(.horizontal, 16).padding(.top, 8)
            Spacer()
        }
    }

    private var imageTools: some View {
        VStack {
            Spacer()
            HStack(spacing: 18) {
                roundButton("rotate.left", label: "Повернуть влево") {
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) { quarterTurns -= 1 }
                }
                roundButton("arrow.counterclockwise", label: "Сбросить изменения") {
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) { quarterTurns = 0 }
                }
                .opacity(quarterTurns == 0 ? 0.45 : 1)
                .disabled(quarterTurns == 0)
                roundButton("rotate.right", label: "Повернуть вправо") {
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) { quarterTurns += 1 }
                }
            }
            .padding(.horizontal, 18).padding(.vertical, 12)
            .background(.ultraThinMaterial, in: Capsule())
            .padding(.bottom, 18)
        }
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
        if let name = payload.fileName, !name.isEmpty { return name }
        switch kind {
        case .image: return "Фото"
        case .video, .videoNote: return "Видео"
        case .audio: return "Аудио"
        case .voice: return "Голосовое сообщение"
        case .file: return "Файл"
        }
    }

    private func load() async {
        if kind == .image {
            image = await MediaStore.shared.image(payload: payload, maxPixel: 4096)
            if image == nil { loadError = "Не удалось открыть фото." }
            return
        }
        let ext: String
        switch kind {
        case .video, .videoNote: ext = "mp4"
        case .audio, .voice: ext = "m4a"
        default: ext = "bin"
        }
        guard let url = await MediaStore.shared.materialize(payload: payload, fallbackExtension: ext) else {
            loadError = "Не удалось скачать или расшифровать файл."
            return
        }
        mediaURL = url
        do {
            let playable = try await AVURLAsset(url: url).load(.isPlayable)
            if !playable { loadError = "Этот формат пока не поддерживается плеером iPhone." }
        } catch {
            loadError = "Этот формат пока не поддерживается плеером iPhone."
        }
    }

    private func share() {
        if kind == .image, let image {
            sharing = ActivityItems(values: [image.rotatedQuarterTurns(quarterTurns)])
        } else if let mediaURL {
            sharing = ActivityItems(values: [mediaURL])
        }
    }
}

private struct ZoomableMediaImage: View {
    let image: UIImage
    let quarterTurns: Int
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
                        if scale <= 1 { resetPosition() }
                    }
            )
            .simultaneousGesture(
                DragGesture()
                    .onChanged { value in
                        guard scale > 1 else { return }
                        offset = CGSize(width: lastOffset.width + value.translation.width,
                                        height: lastOffset.height + value.translation.height)
                    }
                    .onEnded { _ in lastOffset = offset }
            )
            .onTapGesture(count: 2) {
                withAnimation(.easeOut(duration: 0.2)) {
                    if scale > 1 { resetPosition() }
                    else { scale = 2.5; lastScale = 2.5 }
                }
            }
            .onChange(of: quarterTurns) { _, _ in resetPosition() }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .ignoresSafeArea()
    }

    private func resetPosition() {
        scale = 1; lastScale = 1; offset = .zero; lastOffset = .zero
    }
}

private struct AetherPlaybackView: View {
    let video: Bool
    let title: String
    @StateObject private var model: AetherPlayerModel

    init(url: URL, video: Bool, title: String = "") {
        self.video = video
        self.title = title
        _model = StateObject(wrappedValue: AetherPlayerModel(url: url))
    }

    var body: some View {
        ZStack {
            if video {
                PlayerLayer(player: model.player).ignoresSafeArea()
            } else {
                LinearGradient(colors: [Color.black, Color(red: 0.08, green: 0.10, blue: 0.16)],
                               startPoint: .top, endPoint: .bottom).ignoresSafeArea()
                VStack(spacing: 18) {
                    Image(systemName: "waveform.circle.fill")
                        .font(.system(size: 132, weight: .thin))
                        .foregroundStyle(.white.opacity(0.88), Color.accentColor.opacity(0.75))
                    Text(title).font(.title3.weight(.semibold)).foregroundStyle(.white).lineLimit(2)
                        .multilineTextAlignment(.center).padding(.horizontal, 32)
                }
                .offset(y: -72)
            }
            VStack {
                Spacer()
                controls
            }
        }
        .onAppear { if video { model.play() } }
        .onDisappear { model.finish() }
    }

    private var controls: some View {
        VStack(spacing: 12) {
            Slider(value: Binding(get: { model.current }, set: model.seek),
                   in: 0...max(1, model.duration))
                .tint(.white)
            HStack {
                Text(Self.time(model.current))
                Spacer()
                Text("−\(Self.time(max(0, model.duration - model.current)))")
            }
            .font(.caption.monospacedDigit()).foregroundStyle(.white.opacity(0.75))
            HStack(spacing: 30) {
                Button { model.skip(-15) } label: {
                    Image(systemName: "gobackward.15")
                }
                Button { model.toggle() } label: {
                    Image(systemName: model.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 28, weight: .bold))
                        .frame(width: 58, height: 58)
                        .background(.white, in: Circle()).foregroundStyle(.black)
                }
                Button { model.skip(15) } label: {
                    Image(systemName: "goforward.15")
                }
                Button { model.cycleRate() } label: {
                    Text(model.rateText).font(.system(size: 13, weight: .bold)).frame(width: 38)
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
private final class AetherPlayerModel: ObservableObject {
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
            Task { @MainActor [weak self] in
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
        player.seek(to: CMTime(seconds: clamped, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
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
