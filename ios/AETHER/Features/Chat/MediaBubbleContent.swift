import SwiftUI
import QuickLook
import AVFoundation

// Контент медиа-пузыря: фото, видео и музыка открываются в едином просмотрщике
// AETHER; документы остаются в QuickLook, голосовые — в компактном плеере чата.
struct MediaBubbleContent: View {
    let message: ChatMessage
    let payload: Wire.Payload
    let outgoing: Bool

    var body: some View {
        switch payload.mediaKind {
        case .image:
            ImageBubble(payload: payload, outgoing: outgoing)
        case .video:
            VideoBubble(payload: payload)
        case .audio:
            AudioBubble(payload: payload, outgoing: outgoing)
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
    @State private var showPlayer = false

    var body: some View {
        ZStack {
            Rectangle().fill(Color.black.opacity(0.82))
            Image(systemName: "play.fill")
                .font(.system(size: 28, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 58, height: 58)
                .background(.white.opacity(0.18), in: Circle())
            VStack {
                Spacer()
                HStack {
                    Image(systemName: "film")
                    Text("Видео")
                    Spacer()
                }
                .font(.caption.weight(.medium))
                .foregroundStyle(.white.opacity(0.9))
                .padding(10)
            }
        }
        .frame(width: 260, height: 160)
        .contentShape(Rectangle())
        .onTapGesture { showPlayer = true }
        .fullScreenCover(isPresented: $showPlayer) {
            AetherMediaViewer(payload: payload)
        }
    }
}

// Видео-кружок в чате: круглый зацикленный плеер.
struct VideoNoteBubble: View {
    let payload: Wire.Payload
    var outgoing: Bool = false
    @Environment(\.palette) private var palette
    @State private var url: URL?
    @State private var failed = false
    @State private var showViewer = false

    var body: some View {
        Group {
            if let url {
                LoopingCirclePlayer(url: url, size: 200, muted: false, loop: false,
                                    chatAlignment: outgoing ? .trailing : .leading)
            } else if failed {
                Circle().fill(palette.surfaceElevated).frame(width: 200, height: 200)
                    .overlay(Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.yellow))
            } else {
                Circle().fill(palette.surfaceElevated).frame(width: 200, height: 200)
                    .overlay(ProgressView().tint(palette.accent))
            }
        }
        .contentShape(Circle())
        .onTapGesture { showViewer = true }
        .task(id: payload.fileId) {
            guard let candidate = await MediaStore.shared.materialize(payload: payload, fallbackExtension: "mov") else {
                failed = true; return
            }
            if (try? await AVURLAsset(url: candidate).load(.isPlayable)) == true { url = candidate }
            else { failed = true }
        }
        .fullScreenCover(isPresented: $showViewer) { AetherMediaViewer(payload: payload) }
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
            AetherMediaViewer(payload: payload)
        }
    }

    private var thumbHeight: CGFloat {
        guard let thumb, thumb.size.width > 0 else { return 180 }
        let ratio = thumb.size.height / thumb.size.width
        return min(max(maxW * ratio, 120), 340)
    }

    private func load() async {
        thumb = await MediaStore.shared.image(payload: payload, maxPixel: 560)
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

// Музыка и обычные аудиофайлы открываются в том же собственном плеере, что видео.
struct AudioBubble: View {
    let payload: Wire.Payload
    let outgoing: Bool
    @Environment(\.palette) private var palette
    @State private var showPlayer = false

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "music.note")
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(outgoing ? .white : palette.accent)
                .frame(width: 46, height: 46)
                .background((outgoing ? Color.white : palette.accent).opacity(0.16), in: Circle())
            VStack(alignment: .leading, spacing: 3) {
                Text(payload.fileName ?? "Аудио")
                    .font(.system(size: 15, weight: .semibold)).lineLimit(1)
                Text("Музыка")
                    .font(.caption).foregroundStyle(outgoing ? .white.opacity(0.78) : palette.textSecondary)
            }
            Spacer(minLength: 6)
            Image(systemName: "play.fill")
                .foregroundStyle(outgoing ? .white : palette.accent)
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
        .frame(minWidth: 220, maxWidth: 290, alignment: .leading)
        .contentShape(Rectangle())
        .onTapGesture { showPlayer = true }
        .fullScreenCover(isPresented: $showPlayer) { AetherMediaViewer(payload: payload) }
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
                        .clipShape(RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
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
            guard isImageFile, thumb == nil else { return }
            thumb = await MediaStore.shared.image(payload: payload, maxPixel: 96)
        }
    }

    private var sizeText: String {
        guard let s = payload.fileSize else { return "Документ" }
        return ByteCountFormatter.string(fromByteCount: s, countStyle: .file)
    }

    private func open() async {
        guard !loading else { return }
        loading = true
        if let url = await MediaStore.shared.materialize(payload: payload, fallbackExtension: "bin") {
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
    @State private var measuredDuration: TimeInterval?
    @State private var showViewer = false

    private var playbackId: String {
        payload.fileId ?? "inline_\((payload.raw["inline_data"] as? String)?.hashValue ?? 0)"
    }
    private var isPlaying: Bool { audio.playingId == playbackId }
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

            HStack(spacing: 8) {
                if isPlaying {
                    ActiveVoiceWaveform(fileId: playbackId, accent: accent, audio: audio)
                } else {
                    VoiceWaveform(fileId: payload.fileId ?? "x", accent: accent, progress: 0)
                }
                Text(durationText).font(.caption)
                    .foregroundStyle(outgoing ? .white.opacity(0.85) : palette.textSecondary)
            }
            .contentShape(Rectangle())
            .onTapGesture { showViewer = true }
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
        .task(id: payload.fileId) {
            guard measuredDuration == nil,
                  let data = await MediaStore.shared.data(payload: payload),
                  let player = try? AVAudioPlayer(data: data) else { return }
            measuredDuration = player.duration
        }
        .fullScreenCover(isPresented: $showViewer) { AetherMediaViewer(payload: payload) }
    }

    private func toggle() async {
        let id = playbackId
        if audio.playingId == id { audio.stop(); return }
        loading = true
        let data = await MediaStore.shared.data(payload: payload)
        loading = false
        if let data {
            measuredDuration = (try? AVAudioPlayer(data: data))?.duration ?? measuredDuration
            audio.toggle(id: id, data: data)
        }
    }

    private var durationText: String {
        guard let d = measuredDuration ?? payload.duration else { return "0:00" }
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
