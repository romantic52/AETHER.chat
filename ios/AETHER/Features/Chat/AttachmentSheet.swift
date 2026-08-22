import SwiftUI
import Photos
import PhotosUI
import UniformTypeIdentifiers
import UIKit

// Быстрая съёмка фото/видео камерой (системный UIImagePickerController — доступен
// с iOS 17, лёгкий и не требует отдельного AVCaptureSession).
struct CameraCaptureView: UIViewControllerRepresentable {
    var onCaptured: (AttachmentPicked) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.mediaTypes = [UTType.image.identifier, UTType.movie.identifier]
        picker.videoQuality = .typeMedium
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraCaptureView
        init(_ parent: CameraCaptureView) { self.parent = parent }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            defer { parent.dismiss() }
            if let url = info[.mediaURL] as? URL, let data = try? Data(contentsOf: url) {
                parent.onCaptured(AttachmentPicked(data: data, mime: "video/mp4", kind: "video", fileName: nil))
            } else if let image = info[.originalImage] as? UIImage, let jpeg = image.jpegData(compressionQuality: 0.85) {
                parent.onCaptured(AttachmentPicked(data: jpeg, mime: "image/jpeg", kind: "image", fileName: nil))
            }
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            parent.dismiss()
        }
    }
}

// Шторка вложений в стиле Telegram: живая сетка последних фото/видео из галереи
// (мультивыбор 1–99, номер на выбранных), быстрые действия Камера/Галерея/Файл.
struct AttachmentSheet: View {
    var onSend: (_ items: [AttachmentPicked], _ asFile: Bool) -> Void
    var onOpenCamera: () -> Void
    var onOpenFullGallery: () -> Void
    var onOpenFilePicker: () -> Void

    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss
    @StateObject private var library = RecentMediaLibrary()
    /// Отправить оригиналы файлом (без сжатия) — тумблер в панели отправки.
    @State private var asFile = false

    // Предпросмотр по зажатию плитки: держишь — открыт, отпустил — закрылся;
    // подержал дольше — «прилипает» (появляется крестик, палец можно убрать).
    @State private var previewAsset: PHAsset?
    @State private var previewImage: UIImage?
    @State private var previewSticky = false
    @State private var stickyTask: Task<Void, Never>?

    private let columns = [GridItem(.flexible(), spacing: 4), GridItem(.flexible(), spacing: 4), GridItem(.flexible(), spacing: 4)]
    private let maxSelection = 99

    var body: some View {
        VStack(spacing: 0) {
            quickActions
            mediaHeader
            content
        }
        // Панель отправки прижата к низу шторки, симметрично краям —
        // без высокой подложки и задранных кнопок.
        .safeAreaInset(edge: .bottom) {
            if !library.selection.isEmpty { sendBar }
        }
        .overlay { holdPreviewOverlay }
        .task {
            await library.requestAndLoad()
            #if DEBUG
            if ProcessInfo.processInfo.environment["AETHER_ATTACH_SELECT"] == "1",
               let first = library.assets.first {
                library.toggle(first, limit: maxSelection)
            }
            // AETHER_ATTACH_SEND=compressed|file — автоотправка выбранного (для тестов).
            if let mode = ProcessInfo.processInfo.environment["AETHER_ATTACH_SEND"] {
                try? await Task.sleep(nanoseconds: 800_000_000)
                send(asFile: mode == "file")
            }
            #endif
        }
    }

    private var quickActions: some View {
        GlassGroup(spacing: 8) {
            HStack(spacing: 8) {
                quickButton(icon: "camera.fill", title: "Камера") { dismiss(); onOpenCamera() }
                quickButton(icon: "photo.on.rectangle", title: "Галерея") { dismiss(); onOpenFullGallery() }
                quickButton(icon: "doc.fill", title: "Файл") { dismiss(); onOpenFilePicker() }
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, 12)
        .padding(.bottom, 10)
    }

    private func quickButton(icon: String, title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 5) {
                Image(systemName: icon)
                    .font(.system(size: 19, weight: .medium))
                    .foregroundStyle(palette.accent)
                    .frame(height: 24)
                Text(title)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(palette.textPrimary)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 62)
            .liquidGlass(RoundedRectangle(cornerRadius: Radius.control, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: Radius.control, style: .continuous))
        }
        .buttonStyle(.squish)
        .accessibilityLabel(title)
    }

    private var mediaHeader: some View {
        HStack {
            Text("Недавние")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(palette.textPrimary)
            Spacer()
            Text(library.selection.isEmpty ? "Фото и видео" : "Выбрано: \(library.selection.count)")
                .font(.caption.weight(.medium))
                .foregroundStyle(library.selection.isEmpty ? palette.textSecondary : palette.accent)
                .contentTransition(.numericText())
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
    }

    @ViewBuilder private var content: some View {
        if library.authorizationDenied {
            deniedState
        } else if library.assets.isEmpty {
            loadingState
        } else {
            ScrollView {
                LazyVGrid(columns: columns, spacing: 4) {
                    ForEach(library.assets, id: \.localIdentifier) { asset in
                        AttachmentThumb(
                            asset: asset,
                            order: library.selection.firstIndex(of: asset.localIdentifier).map { $0 + 1 },
                            isVideo: asset.mediaType == .video,
                            duration: asset.duration
                        )
                        .onTapGesture { library.toggle(asset, limit: maxSelection) }
                        // Zero-distance DragGesture здесь забирал вертикальный жест
                        // у ScrollView. Обычный long press отменяется при прокрутке.
                        .onLongPressGesture(
                            minimumDuration: 0.3,
                            maximumDistance: 10,
                            perform: {
                                if previewAsset == nil { beginPreview(asset) }
                            },
                            onPressingChanged: { pressing in
                                if !pressing,
                                   previewAsset?.localIdentifier == asset.localIdentifier {
                                    fingerLifted()
                                }
                            }
                        )
                    }
                }
                .padding(.horizontal, 8)
                .padding(.bottom, 8)
            }
            .scrollIndicators(.hidden)
        }
    }

    // MARK: - Полноэкранный предпросмотр по зажатию

    private func beginPreview(_ asset: PHAsset) {
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) {
            previewAsset = asset
            previewSticky = false
        }
        previewImage = nil
        let options = PHImageRequestOptions()
        options.deliveryMode = .highQualityFormat
        options.isNetworkAccessAllowed = true
        PHImageManager.default().requestImage(
            for: asset, targetSize: CGSize(width: 1400, height: 1400),
            contentMode: .aspectFit, options: options
        ) { img, _ in
            if let img { Task { @MainActor in self.previewImage = img } }
        }
        stickyTask?.cancel()
        stickyTask = Task {
            try? await Task.sleep(nanoseconds: 900_000_000)
            guard !Task.isCancelled, previewAsset != nil else { return }
            withAnimation(.easeOut(duration: 0.18)) { previewSticky = true }
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        }
    }

    private func fingerLifted() {
        stickyTask?.cancel()
        if !previewSticky { closePreview() }
    }

    private func closePreview() {
        stickyTask?.cancel()
        withAnimation(.easeOut(duration: 0.18)) {
            previewAsset = nil
            previewSticky = false
        }
        previewImage = nil
    }

    @ViewBuilder private var holdPreviewOverlay: some View {
        if previewAsset != nil {
            ZStack(alignment: .topTrailing) {
                Color.black.opacity(0.96).ignoresSafeArea()
                Group {
                    if let previewImage {
                        Image(uiImage: previewImage)
                            .resizable().scaledToFit()
                    } else {
                        ProgressView().tint(.white)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .ignoresSafeArea()

                if previewSticky {
                    Button { closePreview() } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 15, weight: .heavy))
                            .foregroundStyle(.white)
                            .frame(width: 44, height: 44)
                            .background(.black.opacity(0.65), in: Circle())
                            .overlay(Circle().stroke(.white.opacity(0.5), lineWidth: 1))
                    }
                    .buttonStyle(.squish)
                    .padding(.top, 18).padding(.trailing, 18)
                    .transition(.scale(scale: 0.6).combined(with: .opacity))
                }
            }
            .transition(.opacity)
            .zIndex(50)
        }
    }

    private var loadingState: some View {
        VStack {
            Spacer()
            ProgressView().tint(palette.accent)
            Spacer()
        }
        .frame(height: 340)
    }

    private var deniedState: some View {
        VStack(spacing: 10) {
            Image(systemName: "photo.on.rectangle.angled").font(.system(size: 36)).foregroundStyle(palette.textSecondary)
            Text("Нет доступа к галерее").font(.subheadline.weight(.medium)).foregroundStyle(palette.textPrimary)
            Button("Открыть настройки") {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            }
            .font(.footnote)
        }
        .frame(height: 340)
        .frame(maxWidth: .infinity)
    }

    private var sendBar: some View {
        HStack(spacing: 8) {
            Button {
                withAnimation(.easeInOut(duration: 0.15)) {
                    library.selection.removeAll()
                    asFile = false
                }
                UIImpactFeedbackGenerator(style: .soft).impactOccurred()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(palette.textSecondary)
                    .frame(width: 44, height: 44)
                    .contentShape(Circle())
            }
            .buttonStyle(.squish)
            .accessibilityLabel("Снять выбор")

            Button {
                withAnimation(.easeInOut(duration: 0.15)) { asFile.toggle() }
                UISelectionFeedbackGenerator().selectionChanged()
            } label: {
                Label(asFile ? "Файлом" : "Сжать",
                      systemImage: asFile ? "doc.fill" : "arrow.down.right.and.arrow.up.left")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(asFile ? palette.accent : palette.textPrimary)
                    .padding(.horizontal, 10)
                    .frame(height: 38)
                    .background(palette.accent.opacity(asFile ? 0.14 : 0), in: Capsule())
                    .contentShape(Capsule())
            }
            .buttonStyle(.squish)
            .accessibilityLabel(asFile ? "Отправить оригиналом" : "Сжать перед отправкой")

            Spacer(minLength: 0)

            Text("\(library.selection.count)")
                .font(.system(size: 13, weight: .bold, design: .rounded))
                .foregroundStyle(palette.accent)
                .frame(minWidth: 30, minHeight: 30)
                .background(palette.accent.opacity(0.14), in: Circle())
                .contentTransition(.numericText())

            Button { send(asFile: asFile) } label: {
                Image(systemName: "paperplane.fill")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(palette.onAccent)
                    .frame(width: 44, height: 44)
                    .background(palette.accent, in: Circle())
                    .overlay(Circle().stroke(.white.opacity(0.18), lineWidth: 0.6))
                    .contentShape(Circle())
            }
            .buttonStyle(.squish)
            .accessibilityLabel("Отправить \(library.selection.count)")
        }
        .padding(6)
        .liquidGlass(Capsule())
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    private func send(asFile: Bool) {
        Task {
            let picked = await library.resolveSelected()
            dismiss()
            onSend(picked, asFile)
        }
    }
}

// Готовое к отправке вложение (уже прочитанные байты, вне главного потока).
struct AttachmentPicked {
    let data: Data
    let mime: String
    let kind: String   // image | video
    let fileName: String?
}

// Загрузка последних фото/видео из галереи + разрешение выбранных в байты.
@MainActor
final class RecentMediaLibrary: ObservableObject {
    @Published var assets: [PHAsset] = []
    @Published var selection: [String] = []   // порядок выбора, localIdentifier
    @Published var authorizationDenied = false

    private let manager = PHCachingImageManager()

    func requestAndLoad() async {
        let status = await withCheckedContinuation { cont in
            PHPhotoLibrary.requestAuthorization(for: .readWrite) { cont.resume(returning: $0) }
        }
        guard status == .authorized || status == .limited else {
            authorizationDenied = true
            return
        }
        let options = PHFetchOptions()
        options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        options.fetchLimit = 240
        options.predicate = NSPredicate(format: "mediaType == %d OR mediaType == %d", PHAssetMediaType.image.rawValue, PHAssetMediaType.video.rawValue)
        let result = PHAsset.fetchAssets(with: options)
        var arr: [PHAsset] = []
        result.enumerateObjects { asset, _, _ in arr.append(asset) }
        assets = arr
    }

    func toggle(_ asset: PHAsset, limit: Int) {
        if let idx = selection.firstIndex(of: asset.localIdentifier) {
            selection.remove(at: idx)
        } else if selection.count < limit {
            selection.append(asset.localIdentifier)
        }
    }

    /// Читает выбранные ассеты в Data (вне главного потока), в порядке выбора.
    func resolveSelected() async -> [AttachmentPicked] {
        let ids = selection
        let byId = Dictionary(uniqueKeysWithValues: assets.map { ($0.localIdentifier, $0) })
        var out: [AttachmentPicked] = []
        for id in ids {
            guard let asset = byId[id] else { continue }
            if asset.mediaType == .video {
                if let picked = await Self.loadVideoData(asset) { out.append(picked) }
            } else {
                if let picked = await Self.loadImageData(asset) { out.append(picked) }
            }
        }
        return out
    }

    private static func loadImageData(_ asset: PHAsset) async -> AttachmentPicked? {
        await withCheckedContinuation { cont in
            let options = PHImageRequestOptions()
            options.isSynchronous = false
            options.deliveryMode = .highQualityFormat
            options.isNetworkAccessAllowed = true
            PHImageManager.default().requestImageDataAndOrientation(for: asset, options: options) { data, uti, _, _ in
                guard let data else { cont.resume(returning: nil); return }
                let mime = uti.flatMap { UTType($0)?.preferredMIMEType } ?? "image/jpeg"
                cont.resume(returning: AttachmentPicked(data: data, mime: mime, kind: "image", fileName: nil))
            }
        }
    }

    private static func loadVideoData(_ asset: PHAsset) async -> AttachmentPicked? {
        await withCheckedContinuation { cont in
            let options = PHVideoRequestOptions()
            options.deliveryMode = .automatic
            options.isNetworkAccessAllowed = true
            PHImageManager.default().requestExportSession(forVideo: asset, options: options, exportPreset: AVAssetExportPresetMediumQuality) { session, _ in
                guard let session else { cont.resume(returning: nil); return }
                let out = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).mp4")
                session.outputURL = out
                session.outputFileType = .mp4
                session.exportAsynchronously {
                    guard session.status == .completed, let data = try? Data(contentsOf: out) else {
                        cont.resume(returning: nil); return
                    }
                    try? FileManager.default.removeItem(at: out)
                    cont.resume(returning: AttachmentPicked(data: data, mime: "video/mp4", kind: "video", fileName: nil))
                }
            }
        }
    }
}

// Одна плитка сетки: превью (даунсэмпл), номер выбора, длительность для видео.
struct AttachmentThumb: View {
    let asset: PHAsset
    let order: Int?
    let isVideo: Bool
    let duration: TimeInterval

    @Environment(\.palette) private var palette
    @State private var image: UIImage?

    private static let manager = PHCachingImageManager()

    var body: some View {
        // Без GeometryReader: он клал контент от верхнего левого угла, из-за чего
        // визуал и хит-зона плитки расходились (сетка казалась «сдвинутой»,
        // тап попадал в соседнее фото). Квадрат — через aspectRatio+overlay,
        // хит-зона — ровно клетка (contentShape после clipped).
        Color.clear
            .aspectRatio(1, contentMode: .fit)
            .overlay {
                if let image { Image(uiImage: image).resizable().scaledToFill() }
                else { Rectangle().fill(palette.surfaceElevated) }
            }
            .clipShape(RoundedRectangle(cornerRadius: Radius.nested, style: .continuous))
            .overlay {
                if order != nil {
                    RoundedRectangle(cornerRadius: Radius.nested, style: .continuous)
                        .fill(Color.black.opacity(0.15))
                }
            }
            .overlay {
                if order != nil {
                    RoundedRectangle(cornerRadius: Radius.nested, style: .continuous)
                        .stroke(palette.accent, lineWidth: 3)
                        .padding(1)
                }
            }
            .overlay(alignment: .bottomLeading) {
                if isVideo {
                    HStack(spacing: 2) {
                        Image(systemName: "video.fill").font(.system(size: 10))
                        Text(timeString(duration)).font(.system(size: 10, weight: .medium))
                    }
                    .foregroundStyle(.white)
                    .padding(.horizontal, 5).padding(.vertical, 2)
                    .background(.black.opacity(0.5), in: Capsule())
                    .padding(4)
                }
            }
            .overlay(alignment: .topTrailing) {
                if let order {
                    Text("\(order)")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 22, height: 22)
                        .background(palette.accent, in: Circle())
                        .overlay(Circle().stroke(.white, lineWidth: 1.5))
                        .padding(5)
                }
            }
            .contentShape(Rectangle())
            .task(id: asset.localIdentifier) { await load(targetSize: 240) }
    }

    private func load(targetSize: CGFloat) async {
        let options = PHImageRequestOptions()
        options.deliveryMode = .opportunistic
        options.resizeMode = .fast
        options.isNetworkAccessAllowed = true
        let scale = await UIScreen.main.scale
        let size = CGSize(width: targetSize * scale, height: targetSize * scale)
        Self.manager.requestImage(for: asset, targetSize: size, contentMode: .aspectFill, options: options) { img, _ in
            if let img { Task { @MainActor in self.image = img } }
        }
    }

    private func timeString(_ t: TimeInterval) -> String {
        String(format: "%d:%02d", Int(t) / 60, Int(t) % 60)
    }
}
