import SwiftUI
import QuickLook
import AVKit

// Контент медиа-пузыря: фото (даунсэмпл-превью + полноэкранный просмотр), файл (иконка+скачать),
// голос/кружок — базовое отображение (полноценные плееры — итерация 4).
struct MediaBubbleContent: View {
    let message: ChatMessage
    let payload: Wire.Payload
    let outgoing: Bool
    @Environment(\.palette) private var palette

    var body: some View {
        switch payload.mediaKind {
        case .image:
            ImageBubble(payload: payload, outgoing: outgoing)
        case .video:
            VideoBubble(payload: payload)
        case .file:
            FileBubble(payload: payload, outgoing: outgoing)
        case .voice:
            VoiceBubble(payload: payload, outgoing: outgoing)
        case .videoNote:
            VideoNoteBubble(payload: payload, outgoing: outgoing)
        }
    }
}

// В строке чата видео не декодируется и не запускает AVPlayer. Плеер создаётся
// только после явного тапа пользователя в полноэкранном просмотре.
struct VideoBubble: View {
    let payload: Wire.Payload
    @State private var url: URL?
    @State private var showPlayer = false

    var body: some View {
        ZStack {
            Rectangle().fill(Color.black.opacity(0.82))
            if url == nil {
                ProgressView().tint(.white)
            } else {
                Image(systemName: "play.fill")
                    .font(.system(size: 28, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 58, height: 58)
                    .background(.white.opacity(0.18), in: Circle())
            }
            VStack {
                Spacer()
                HStack {
                    Image(systemName: "film")
                    Text(payload.duration.map(Self.durationText) ?? "Видео")
                    Spacer()
                }
                .font(.caption.weight(.medium))
                .foregroundStyle(.white.opacity(0.9))
                .padding(10)
            }
        }
        .frame(width: 260, height: 160)
        .contentShape(Rectangle())
        .onTapGesture { if url != nil { showPlayer = true } }
        .task(id: payload.fileId) {
            guard let fileId = payload.fileId else { return }
            url = await MediaStore.shared.materialize(fileId: fileId, fileName: "\(fileId).mp4",
                                                      symKey: payload.symKey ?? "", nonce: payload.nonce ?? "")
        }
        .fullScreenCover(isPresented: $showPlayer) {
            if let url { FullScreenVideoView(url: url) }
        }
    }

    private static func durationText(_ duration: Double) -> String {
        String(format: "%d:%02d", Int(duration) / 60, Int(duration) % 60)
    }
}

private struct FullScreenVideoView: View {
    let url: URL
    @Environment(\.dismiss) private var dismiss
    @State private var player: AVPlayer

    init(url: URL) {
        self.url = url
        _player = State(initialValue: AVPlayer(url: url))
    }

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.ignoresSafeArea()
            VideoPlayer(player: player).ignoresSafeArea()
            Button { dismiss() } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(.white)
                    .padding(12)
                    .background(.ultraThinMaterial, in: Circle())
            }
            .padding()
        }
        .onAppear { player.play() }
        .onDisappear { player.pause() }
    }
}

// Видео-кружок в чате: круглый зацикленный плеер.
struct VideoNoteBubble: View {
    let payload: Wire.Payload
    var outgoing: Bool = false
    @Environment(\.palette) private var palette
    @State private var url: URL?

    var body: some View {
        Group {
            if let url {
                LoopingCirclePlayer(url: url, size: 200, muted: false, loop: false,
                                    chatAlignment: outgoing ? .trailing : .leading)
            } else {
                Circle().fill(palette.surfaceElevated).frame(width: 200, height: 200)
                    .overlay(ProgressView().tint(palette.accent))
            }
        }
        .task(id: payload.fileId) {
            guard let fid = payload.fileId else { return }
            url = await MediaStore.shared.materialize(fileId: fid, fileName: "\(fid).mov",
                                                      symKey: payload.symKey ?? "", nonce: payload.nonce ?? "")
        }
    }
}

// Фото: превью с даунсэмплингом, тап → полноэкранный зум.
struct ImageBubble: View {
    let payload: Wire.Payload
    let outgoing: Bool
    @Environment(\.palette) private var palette
    @State private var thumb: UIImage?
    @State private var showFull = false

    private var maxW: CGFloat { 260 }

    var body: some View {
        ZStack {
            if let thumb {
                Image(uiImage: thumb)
                    .resizable().scaledToFill()
                    .frame(width: maxW, height: thumbHeight)
                    .clipped()
            } else {
                Rectangle().fill(palette.surfaceElevated)
                    .frame(width: maxW, height: 180)
                    .overlay(ProgressView().tint(palette.accent))
            }
            if let cap = payload.caption, !cap.isEmpty {
                VStack { Spacer()
                    Text(cap).font(.system(size: 14)).foregroundStyle(.white)
                        .padding(8).frame(maxWidth: .infinity, alignment: .leading)
                        .background(LinearGradient(colors: [.clear, .black.opacity(0.55)], startPoint: .top, endPoint: .bottom))
                }
            }
        }
        .frame(width: maxW)
        .contentShape(Rectangle())
        .onTapGesture { showFull = true }
        .task(id: payload.fileId) { await load() }
        .fullScreenCover(isPresented: $showFull) {
            FullScreenImageView(payload: payload)
        }
    }

    private var thumbHeight: CGFloat {
        guard let thumb, thumb.size.width > 0 else { return 180 }
        let ratio = thumb.size.height / thumb.size.width
        return min(max(maxW * ratio, 120), 340)
    }

    private func load() async {
        guard let fid = payload.fileId else { return }
        thumb = await MediaStore.shared.image(fileId: fid, symKey: payload.symKey ?? "",
                                              nonce: payload.nonce ?? "", maxPixel: 560)
    }
}

// Полноэкранный просмотр фото с зумом и pan.
struct FullScreenImageView: View {
    let payload: Wire.Payload
    @Environment(\.dismiss) private var dismiss
    @State private var image: UIImage?
    @State private var scale: CGFloat = 1
    @State private var offset: CGSize = .zero

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let image {
                Image(uiImage: image)
                    .resizable().scaledToFit()
                    .scaleEffect(scale)
                    .offset(offset)
                    .gesture(
                        MagnificationGesture()
                            .onChanged { scale = max(1, $0) }
                            .onEnded { _ in if scale < 1.05 { withAnimation { scale = 1; offset = .zero } } }
                    )
                    .simultaneousGesture(
                        DragGesture()
                            .onChanged { if scale > 1 { offset = $0.translation } }
                            .onEnded { _ in if scale <= 1 { withAnimation { offset = .zero } } }
                    )
                    .onTapGesture(count: 2) {
                        withAnimation(.easeOut(duration: 0.18)) { scale = scale > 1 ? 1 : 2.5; offset = .zero }
                    }
            } else {
                ProgressView().tint(.white)
            }
            VStack {
                HStack {
                    Spacer()
                    Button { dismiss() } label: {
                        Image(systemName: "xmark").font(.system(size: 18, weight: .bold))
                            .foregroundStyle(.white).padding(12)
                            .background(.ultraThinMaterial, in: Circle())
                    }.padding()
                }
                Spacer()
            }
        }
        .task {
            guard let fid = payload.fileId else { return }
            image = await MediaStore.shared.image(fileId: fid, symKey: payload.symKey ?? "",
                                                 nonce: payload.nonce ?? "", maxPixel: 2400)
        }
    }
}

// Полноэкранный QuickLook со своим крестиком: системный Done-бар
// на iOS 27 beta может не отображаться, оставляя просмотр без выхода.
struct IdentifiableURL: Identifiable {
    let url: URL
    var id: String { url.absoluteString }
}

struct QuickLookCover: View {
    let url: URL
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack(alignment: .topTrailing) {
            QLController(url: url).ignoresSafeArea()
            Button { dismiss() } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(.white)
                    .padding(12)
                    .background(.ultraThinMaterial, in: Circle())
            }
            .padding()
        }
        .background(Color.black.ignoresSafeArea())
    }
}

private struct QLController: UIViewControllerRepresentable {
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
        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem {
            url as NSURL
        }
    }
}

// Файл-документ: превью/иконка+имя+размер; тап → скачать/расшифровать → QuickLook.
// Для файлов-картинок слева маленькое превью самого изображения (как в Telegram),
// для остальных — круг с иконкой дока.
struct FileBubble: View {
    let payload: Wire.Payload
    let outgoing: Bool
    var forcedLabel: String? = nil
    var forcedIcon: String? = nil
    @Environment(\.palette) private var palette
    @State private var previewURL: IdentifiableURL?
    @State private var loading = false
    @State private var thumb: UIImage?

    private var isImageFile: Bool { (payload.mimeType ?? "").hasPrefix("image/") }

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                if let thumb {
                    Image(uiImage: thumb)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 48, height: 48)
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    if loading { ProgressView().tint(.white) }
                } else {
                    Circle().fill(outgoing ? Color.white.opacity(0.18) : palette.accent.opacity(0.18))
                        .frame(width: 44, height: 44)
                    if loading { ProgressView().tint(outgoing ? .white : palette.accent) }
                    else {
                        Image(systemName: forcedIcon ?? "doc.fill")
                            .font(.system(size: 19))
                            .foregroundStyle(outgoing ? .white : palette.accent)
                    }
                }
            }
            VStack(alignment: .leading, spacing: 3) {
                // Имя файла — акцентным, как ссылка в Telegram.
                Text(forcedLabel ?? payload.fileName ?? "Файл")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(outgoing ? .white : palette.accent)
                    .lineLimit(1)
                Text(sizeText).font(.system(size: 13))
                    .foregroundStyle(outgoing ? .white.opacity(0.8) : palette.textSecondary)
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
        .frame(minWidth: 180, alignment: .leading)
        .contentShape(Rectangle())
        .onTapGesture { Task { await open() } }
        .fullScreenCover(item: $previewURL) { item in QuickLookCover(url: item.url) }
        .task(id: payload.fileId) {
            // Мини-превью для файлов-картинок (кэшируется MediaStore).
            guard isImageFile, thumb == nil, let fid = payload.fileId else { return }
            thumb = await MediaStore.shared.image(fileId: fid, symKey: payload.symKey ?? "",
                                                  nonce: payload.nonce ?? "", maxPixel: 96)
        }
    }

    private var sizeText: String {
        guard let s = payload.fileSize else { return "Документ" }
        return ByteCountFormatter.string(fromByteCount: s, countStyle: .file)
    }

    private func open() async {
        guard let fid = payload.fileId, !loading else { return }
        loading = true
        if let url = await MediaStore.shared.materialize(fileId: fid, fileName: payload.fileName ?? "file",
                                                         symKey: payload.symKey ?? "", nonce: payload.nonce ?? "") {
            previewURL = IdentifiableURL(url: url)
        }
        loading = false
    }
}

// Голосовое: плеер с волной (из хэша) и прогрессом. Тап — играть/пауза.
struct VoiceBubble: View {
    let payload: Wire.Payload
    let outgoing: Bool
    @Environment(\.palette) private var palette
    @ObservedObject private var audio = AudioPlaybackManager.shared
    @State private var loading = false

    private var isPlaying: Bool { audio.playingId == payload.fileId }
    private var accent: Color { outgoing ? .white : palette.accent }

    var body: some View {
        HStack(spacing: 10) {
            Button { Task { await toggle() } } label: {
                ZStack {
                    Circle().fill(accent.opacity(outgoing ? 0.22 : 0.16)).frame(width: 40, height: 40)
                    if loading { ProgressView().tint(accent) }
                    else { Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 16)).foregroundStyle(accent) }
                }
            }.buttonStyle(.plain)

            if isPlaying, let fileId = payload.fileId {
                ActiveVoiceWaveform(fileId: fileId, accent: accent, audio: audio)
            } else {
                VoiceWaveform(fileId: payload.fileId ?? "x", accent: accent, progress: 0)
            }
            Text(durationText).font(.caption)
                .foregroundStyle(outgoing ? .white.opacity(0.85) : palette.textSecondary)
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
    }

    private func toggle() async {
        guard let fid = payload.fileId else { return }
        if audio.playingId == fid { audio.stop(); return }
        loading = true
        let data = await MediaStore.shared.data(fileId: fid, symKey: payload.symKey ?? "", nonce: payload.nonce ?? "")
        loading = false
        if let data { audio.toggle(id: fid, data: data) }
    }

    private var durationText: String {
        guard let d = payload.duration else { return "0:00" }
        return String(format: "%d:%02d", Int(d) / 60, Int(d) % 60)
    }
}

private struct ActiveVoiceWaveform: View {
    let fileId: String
    let accent: Color
    @ObservedObject var audio: AudioPlaybackManager
    @State private var progress = 0.0

    var body: some View {
        VoiceWaveform(fileId: fileId, accent: accent, progress: progress)
            .onReceive(audio.progressPublisher) { progress = $0 }
    }
}

private struct VoiceWaveform: View {
    let fileId: String
    let accent: Color
    let progress: Double

    var body: some View {
        HStack(spacing: 2) {
            ForEach(0..<28, id: \.self) { index in
                Capsule()
                    .fill(accent.opacity(Double(index) / 28 <= progress ? 1 : 0.4))
                    .frame(width: 2.5, height: barHeight(index))
            }
        }
    }

    private func barHeight(_ index: Int) -> CGFloat {
        let value = abs((fileId.hashValue &* (index + 7)) % 18)
        return CGFloat(6 + value)
    }
}
