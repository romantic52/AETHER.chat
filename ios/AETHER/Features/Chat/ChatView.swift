import SwiftUI
import PhotosUI
import UniformTypeIdentifiers
import Combine
import AVFoundation

struct ChatView: View {
    let peerId: String
    let isGroup: Bool

    @EnvironmentObject var session: Session
    @EnvironmentObject var messaging: Messaging
    @EnvironmentObject var appearance: AppearanceSettings
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss

    @StateObject private var vm: ChatViewModel
    @State private var draft = ""
    @State private var replyTo: ChatMessage?
    @State private var editing: ChatMessage?
    @State private var pickerFor: ChatMessage?
    /// Подсвеченное сообщение (после прыжка по цитате), гаснет плавно за 3с.
    @State private var highlightedId: String?
    @FocusState private var inputFocused: Bool

    // Вложения.
    @State private var showAttachMenu = false
    @State private var showPhotoPicker = false
    @State private var showFileImporter = false
    @State private var showCamera = false
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var attachmentError = ""

    // Голос / кружки.
    @StateObject private var recorder = VoiceRecorder()
    @StateObject private var circleCam = CircleCamera()
    @State private var circleMode = false
    // Сегменты кружка: переключение камеры обрывает MovieFileOutput, поэтому
    // свитч = закрыть сегмент → переключить → писать следующий; отправка склеивает.
    @State private var circleSegments: [URL] = []
    @State private var circleRecordedBefore: TimeInterval = 0
    @State private var circleSwitching = false
    @State private var circleWantPreview = false
    @State private var circlePreviewURL: URL?
    @State private var circlePreviewDuration: TimeInterval = 0
    private var circleTotalElapsed: TimeInterval {
        circleRecordedBefore + (circleCam.isRecording ? circleCam.elapsed : 0)
    }
    /// Кружок пишется прямо в чате (без отдельного экрана): активная запись камерой.
    private var circleActive: Bool {
        circleMode && (recordPhase == .recording || recordPhase == .locked || recordPhase == .preview)
    }

    private enum RecordPhase { case idle, arming, recording, locked, preview }
    @State private var recordPhase: RecordPhase = .idle
    // Предпросмотр/обрезка записанного ГС (фаза .preview).
    @State private var voicePreviewURL: URL?
    @State private var voicePreviewDuration: TimeInterval = 0
    @State private var trimStart: CGFloat = 0
    @State private var trimEnd: CGFloat = 1
    @State private var dragTranslation: CGSize = .zero
    @State private var armTask: Task<Void, Never>?
    private let holdThreshold: TimeInterval = 0.18
    private let lockDistance: CGFloat = -80
    private let cancelDistance: CGFloat = -70

    // Группа/канал.
    @State private var showGroupProfile = false
    @ObservedObject private var wallpaperStore = WallpaperStore.shared

    // Локальное фото для контакта (см. AvatarStore) — только для 1:1.
    @State private var showAvatarMenu = false
    @State private var showAvatarPicker = false
    @State private var avatarPickerItem: PhotosPickerItem?

    // TOFU: непринятая смена olm-ключа собеседника (SEC HIGH-2) — баннер.
    @State private var pendingKeyChange: String?
    @State private var pendingKeyKind: CoreClient.KeyAlertKind?

    init(peerId: String, isGroup: Bool) {
        self.peerId = peerId
        self.isGroup = isGroup
        // vm привязывается в .task через rebind-паттерн — здесь плейсхолдер невозможен,
        // поэтому конструируем лениво в onAppear. Используем обёртку через State.
        _vm = StateObject(wrappedValue: ChatViewModel.placeholder)
    }

    private var title: String {
        if peerId == session.myId.lowercased() { return String(localized: "Избранное") }
        let chatTitle = messaging.chats.first { $0.peerId == peerId }?.title ?? ""
        // 1:1 — display name из профиля.
        if !isGroup { return messaging.displayName(peerId, fallback: chatTitle) }
        if !chatTitle.isEmpty { return chatTitle }
        // Чат ещё не создан локально (вход из поиска): имя группы/канала из инфо.
        if let name = messaging.groups.info(peerId)?.name, !name.isEmpty { return name }
        return peerId
    }

    private var subtitle: String {
        if isGroup {
            if let info = messaging.groups.info(peerId) {
                return info.isChannel ? "\(info.memberCount) подписчиков" : "\(info.memberCount) участников"
            }
            return ""
        }
        if isSaved { return "личное облако" }
        if messaging.typingPeers.contains(peerId) { return "печатает…" }
        return messaging.presenceText(peerId)
    }

    private var isChannel: Bool {
        messaging.groups.info(peerId)?.isChannel ?? false
    }

    /// Избранное — личный канал: id равен id аккаунта, без typing/онлайна.
    private var isSaved: Bool { peerId == session.myId.lowercased() }

    /// Канал: писать могут только владелец/админы; остальные — read-only.
    private var isReadOnlyChannel: Bool {
        guard let info = messaging.groups.info(peerId), info.isChannel else { return false }
        return !info.isOwnerOrAdmin
    }

    /// Подписан ли я на уведомления канала (переиспользуем общий флаг muted чата).
    private var channelSubscribed: Bool {
        !(messaging.chats.first { $0.peerId == peerId }?.muted ?? false)
    }

    var body: some View {
        ZStack {
            wallpaper
            messageList
                // Затемняется ТОЛЬКО лента: композер (safeAreaInset ниже) и шапка
                // остаются яркими и кликабельными.
                .overlay {
                    if circleActive {
                        Color.black.opacity(0.45)
                            .ignoresSafeArea(edges: .top)
                            .transition(.opacity)
                            .allowsHitTesting(false)
                    }
                }
                .safeAreaInset(edge: .top) {
                    if let peerKey = pendingKeyChange, !isGroup {
                        keyChangeBanner(peerKey: peerKey)
                    }
                }
                .safeAreaInset(edge: .bottom) {
                    inputBarContainer
                }

            // Кружок поверх переписки (как в Telegram) — без отдельного экрана.
            if circleActive {
                VStack {
                    Spacer().frame(height: 70)
                    ZStack {
                        // Градиент-ореол «на звук»: мягкое свечение вокруг кружка,
                        // дышит амплитудой во время записи.
                        // Ореол на РЕАЛЬНУЮ громкость микрофона (RMS из audio-tap
                        // камеры): говоришь — свечение растёт, тишина — минимум.
                        TimelineView(.animation) { _ in
                            let level = 0.25 + 0.75 * circleCam.audioLevel
                            RadialGradient(
                                colors: [palette.accent.opacity(0.55 * level), palette.accent.opacity(0.18 * level), .clear],
                                center: .center, startRadius: 130, endRadius: 210 + 26 * level
                            )
                            .frame(width: 430, height: 430)
                        }
                        .allowsHitTesting(false)

                        if let preview = circlePreviewURL, recordPhase == .preview {
                            LoopingCirclePlayer(url: preview, size: 280, muted: false)
                                .id(preview)
                        } else if circleCam.available {
                            CameraPreview(session: circleCam.session)
                                .frame(width: 280, height: 280)
                                .clipShape(Circle())
                                .onTapGesture(count: 2) { flipCircleCamera() }
                        } else {
                            Circle().fill(palette.surfaceElevated)
                                .frame(width: 280, height: 280)
                                .overlay(ProgressView().tint(palette.accent))
                        }

                        // Белая дуга прогресса 60с вокруг кружка, пульсирует при записи.
                        TimelineView(.animation) { timeline in
                            let t = timeline.date.timeIntervalSinceReferenceDate
                            let pulse: CGFloat = 1 + 0.5 * abs(sin(t * 3.0))
                            Circle()
                                .trim(from: 0, to: max(0.015, CGFloat(min(1, circleTotalElapsed / 60))))
                                .stroke(.white.opacity(0.95),
                                        style: StrokeStyle(lineWidth: 3.5 * pulse, lineCap: .round))
                                .frame(width: 292, height: 292)
                                .rotationEffect(.degrees(-90))
                        }
                        .allowsHitTesting(false)

                        // Переворот камеры (и двойной тап по кружку) — не в превью.
                        if recordPhase != .preview {
                        Button { flipCircleCamera() } label: {
                            Image(systemName: "arrow.triangle.2.circlepath.camera.fill")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(.white)
                                .frame(width: 40, height: 40)
                                .background(.ultraThinMaterial, in: Circle())
                        }
                        .buttonStyle(.squish)
                        .offset(x: 100, y: 118)   // правый нижний край кружка
                        }
                    }
                    Spacer()
                }
                .transition(.scale(scale: 0.8).combined(with: .opacity))
            }
        }
        .animation(AetherUI.sendAnimation, value: circleActive)
        .toolbar(.hidden, for: .navigationBar)
        .swipeBackEnabled()
        .safeAreaInset(edge: .top) { chatTopBar }
        .sheet(isPresented: $showGroupProfile) {
            if isGroup {
                GroupProfileView(groupId: peerId)
                    .environmentObject(session).environmentObject(messaging)
            } else {
                NavigationStack {
                    UserProfileView(userId: peerId)
                        .environmentObject(session).environmentObject(messaging)
                }
            }
        }
        // Долгое нажатие на аватар в шапке 1:1-чата — локальное (только у меня)
        // фото для контакта, реальный профиль собеседника мы менять не можем.
        .confirmationDialog("Фото контакта", isPresented: $showAvatarMenu, titleVisibility: .visible) {
            Button("Установить фото") { showAvatarPicker = true }
            if AvatarStore.shared.hasOverride(for: peerId) {
                Button("Удалить фото", role: .destructive) { AvatarStore.shared.removeOverride(for: peerId) }
            }
            Button("Отмена", role: .cancel) {}
        }
        .photosPicker(isPresented: $showAvatarPicker, selection: $avatarPickerItem, matching: .images)
        .onChange(of: avatarPickerItem) { _, item in
            guard let item else { return }
            Task {
                defer { avatarPickerItem = nil }
                if let data = try? await item.loadTransferable(type: Data.self),
                   let image = MediaStore.downsample(data: data, maxPixel: 512) {
                    AvatarStore.shared.setOverride(image, for: peerId)
                }
            }
        }
        // .task(id:) — при переиспользовании вью под другой peer задача перезапускается.
        .task(id: peerId) {
            vm.bind(peerId: peerId, isGroup: isGroup, session: session, messaging: messaging)
            await vm.onAppear()
            if !isGroup {
                pendingKeyChange = await messaging.pendingOlmKeyChange(for: peerId)
                pendingKeyKind = await messaging.pendingOlmAlertKind(for: peerId)
            }
            #if DEBUG
            if let msg = ProcessInfo.processInfo.environment["AETHER_SEND"], !msg.isEmpty {
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                vm.send(text: msg, replyTo: nil)
            }
            if isGroup, ProcessInfo.processInfo.environment["AETHER_OPEN_GROUP_PROFILE"] == "1" {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                showGroupProfile = true
            }
            if ProcessInfo.processInfo.environment["AETHER_OPEN_ATTACH"] == "1" {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                showAttachMenu = true
            }
            #endif
        }
        .onChange(of: circleCam.elapsed) { _, t in
            if circleRecordedBefore + t >= 60, circleCam.isRecording, !circleSwitching { finishCircle(preview: true) }
        }
        .onDisappear {
            vm.onDisappear()
            armTask?.cancel()
            if recordPhase != .idle {
                recorder.cancel()
                if circleCam.isRecording { circleCam.cancelRecording() }
                circleCam.stop()
                recordPhase = .idle
            }
        }
        .onReceive(messaging.inboxTick.$tick.dropFirst()) { _ in
            vm.requestReload()
            if !isGroup {
                Task {
                    pendingKeyChange = await messaging.pendingOlmKeyChange(for: peerId)
                    pendingKeyKind = await messaging.pendingOlmAlertKind(for: peerId)
                }
            }
        }
        .sheet(item: $pickerFor) { msg in
            ReactionPicker { emoji in
                vm.react(to: msg, emoji: emoji)
                pickerFor = nil
            }
            .presentationDetents([.height(90)])
            .presentationBackground(.ultraThinMaterial)
        }
        .sheet(isPresented: $showAttachMenu) {
            AttachmentSheet(
                onSend: { picked, asFile in sendPicked(picked, asFile: asFile) },
                onOpenCamera: { showCamera = true },
                onOpenFullGallery: { showPhotoPicker = true },
                onOpenFilePicker: { showFileImporter = true }
            )
            .presentationDetents([.fraction(0.62), .large])
            .presentationDragIndicator(.visible)
            .presentationBackground(
                appearance.glassEnabled
                    ? AnyShapeStyle(.ultraThinMaterial)
                    : AnyShapeStyle(palette.background)
            )
            // Скролл сетки фото имеет приоритет над ресайзом шторки: тянешь фото —
            // листается контент, а не дёргается детент.
            .presentationContentInteraction(.scrolls)
        }
        .fullScreenCover(isPresented: $showCamera) {
            CameraCaptureView { picked in sendPicked([picked]) }
                .ignoresSafeArea()
        }
        // Полная галерея (все альбомы) — открывается кнопкой «Галерея» в шторке,
        // системный пикер поверх нашей сетки последних фото.
        .photosPicker(isPresented: $showPhotoPicker, selection: $photoItems, maxSelectionCount: 99, matching: .any(of: [.images, .videos]))
        .onChange(of: photoItems) { _, items in Task { await handlePhotos(items) } }
        .fileImporter(isPresented: $showFileImporter, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            handleFiles(result)
        }
        .alert("Не удалось прикрепить файл", isPresented: Binding(
            get: { !attachmentError.isEmpty },
            set: { if !$0 { attachmentError = "" } }
        )) {
            Button("OK", role: .cancel) { attachmentError = "" }
        } message: {
            Text(attachmentError)
        }
    }

    // MARK: - Вложения

    private func handlePhotos(_ items: [PhotosPickerItem]) async {
        var picked: [AttachmentPicked] = []
        for item in items {
            guard let data = try? await item.loadTransferable(type: Data.self) else { continue }
            let isVideo = item.supportedContentTypes.contains { $0.conforms(to: .movie) }
            picked.append(AttachmentPicked(data: data, mime: isVideo ? "video/mp4" : "image/jpeg",
                                           kind: isVideo ? "video" : "image", fileName: nil))
        }
        photoItems = []
        sendPicked(picked)
    }

    /// Отправка вложений (шторка/камера/системный пикер) в порядке выбора.
    /// asFile — оригиналы без сжатия, файлом. Метаданные (EXIF/GPS/устройство)
    /// зачищаются ВСЕГДА, обоими путями (MediaSanitizer).
    private func sendPicked(_ items: [AttachmentPicked], asFile: Bool = false) {
        Task {
            // Обезличенные имена: file_ГГГГММДД_ЧЧММСС.расширение; несколько
            // в одну отправку — добавляется порядковый номер (_2, _3, …).
            let formatter = DateFormatter()
            formatter.dateFormat = "yyyyMMdd_HHmmss"
            let stamp = formatter.string(from: Date())
            var fileIndex = 0
            func anonName(ext: String) -> String {
                fileIndex += 1
                return fileIndex == 1 ? "file_\(stamp).\(ext)" : "file_\(stamp)_\(fileIndex).\(ext)"
            }
            for item in items {
                if item.kind == "video" {
                    guard item.data.count <= 100 * 1024 * 1024 else {
                        attachmentError = "Видео больше 100 МБ. Выбери файл поменьше."
                        continue
                    }
                    if asFile {
                        // Файлом — оригинальные дорожки, только зачистка метаданных.
                        let clean = await MediaSanitizer.strippedVideo(item.data)
                        vm.sendMedia(data: clean, mime: "video/mp4", kind: "file", fileName: anonName(ext: "mp4"))
                    } else {
                        // Обычная отправка — реальное сжатие видео (720p H.264).
                        let clean = await MediaSanitizer.compressedVideo(item.data)
                        vm.sendMedia(data: clean, mime: "video/mp4", kind: "video", fileName: nil)
                    }
                } else if asFile {
                    // Оригинал файлом: пиксели без потерь, метаданные вычищены,
                    // имя обезличено (оригинальное имя из галереи не светим).
                    let clean = await Task.detached(priority: .userInitiated) {
                        MediaSanitizer.strippedImage(item.data)
                    }.value
                    let ext = item.mime.contains("png") ? "png" : (item.mime.contains("heic") ? "heic" : "jpg")
                    vm.sendMedia(data: clean, mime: item.mime, kind: "file", fileName: anonName(ext: ext))
                } else {
                    // Сжатое фото — реальная экономия места: даунсэмплинг до 1280px
                    // + JPEG 0.6 (как «сжатая» отправка Telegram; в 15–30 раз меньше
                    // оригинала). EXIF при перекодировании не переносится.
                    let jpeg = await Task.detached(priority: .userInitiated) {
                        autoreleasepool {
                            let image = MediaStore.downsample(data: item.data, maxPixel: 1280) ?? UIImage(data: item.data)
                            let compressed = image?.jpegData(compressionQuality: 0.6)
                            // Страховка: если «сжатие» вдруг вышло крупнее оригинала —
                            // шлём оригинал (уже без метаданных по пути перекодирования).
                            if let compressed, compressed.count < item.data.count { return compressed }
                            return compressed ?? item.data
                        }
                    }.value
                    vm.sendMedia(data: jpeg, mime: "image/jpeg", kind: "image", fileName: nil)
                }
            }
        }
    }

    private func handleFiles(_ result: Result<[URL], Error>) {
        guard case .success(let urls) = result else { return }
        for url in urls {
            Task {
                let loaded = await Task.detached(priority: .userInitiated) { () -> (Data?, String?) in
                    let access = url.startAccessingSecurityScopedResource()
                    defer { if access { url.stopAccessingSecurityScopedResource() } }
                    let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
                    guard size <= 100 * 1024 * 1024 else { return (nil, "Максимальный размер файла — 100 МБ.") }
                    guard let data = try? Data(contentsOf: url, options: .mappedIfSafe) else {
                        return (nil, "Не удалось прочитать \(url.lastPathComponent).")
                    }
                    return (data, nil)
                }.value
                if let error = loaded.1 { attachmentError = error; return }
                guard let data = loaded.0 else { return }
                let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
                // Фото/видео-файлы чистим от метаданных; прочие форматы — как есть
                // (правка байтов произвольного формата сломала бы содержимое).
                let clean = await MediaSanitizer.sanitize(data: data, mime: mime)
                vm.sendMedia(data: clean, mime: mime, kind: "file", fileName: url.lastPathComponent)
            }
        }
    }

    // MARK: - Шапка

    // Плавающая шапка диалога вместо системного навбара (на iOS 27 beta
    // toolbarBackground(.hidden) не убирает стеклянную подложку).
    // Слева — отдельная круглая «назад», остальное (шапка чата + звонки + меню) —
    // единая скруглённая стеклянная панель, в стиле нижнего композера.
    /// Баннер «ключ собеседника изменился» (TOFU, SEC HIGH-2): переустановка
    /// приложения у пира или атака — решает пользователь, тихих перепинов нет.
    private func keyChangeBanner(peerKey: String) -> some View {
        let isMaster = pendingKeyKind == .masterChanged
        let isUnsigned = pendingKeyKind == .deviceUnsigned
        let title = isMaster ? "Изменился ключ аккаунта собеседника"
            : isUnsigned ? "Устройство собеседника не подписано его аккаунтом"
            : "Ключ шифрования собеседника изменился"
        let detail = isMaster
            ? "Так выглядит переустановка аккаунта — либо атака. Приняв ключ, вы обнулите всё доверие к прежним устройствам собеседника. Сверьте отпечаток по другому каналу."
            : isUnsigned
            ? "Обычно это значит, что собеседник ещё не обновил приложение: на это устройство сообщения не отправляются, а его сообщения не читаются. Доверять стоит, только если вы уверены, что это его устройство."
            : "Это смена устройства или переустановка приложения — либо попытка подмены. Сверьте отпечаток по другому каналу, прежде чем принимать."
        return VStack(alignment: .leading, spacing: 8) {
            Label(title, systemImage: "exclamationmark.shield.fill")
                .font(.footnote.weight(.semibold))
            Text(detail)
                .font(.caption2)
                .foregroundStyle(.secondary)
            HStack {
                Button(isUnsigned ? "Доверять устройству" : "Принять новый ключ") {
                    Task {
                        await messaging.acceptNewOlmKey(peerKey: peerKey)
                        pendingKeyChange = await messaging.pendingOlmKeyChange(for: peerId)
                        pendingKeyKind = await messaging.pendingOlmAlertKind(for: peerId)
                    }
                }
                .font(.footnote.weight(.semibold))
                .buttonStyle(.borderedProminent)
                .tint(.orange)
                Spacer()
            }
        }
        .padding(12)
        .background(.orange.opacity(0.15), in: RoundedRectangle(cornerRadius: Radius.control, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: Radius.control, style: .continuous).stroke(.orange.opacity(0.4)))
        .padding(.horizontal, 12)
        .padding(.top, 6)
    }

    private var chatTopBar: some View {
        HStack(spacing: 10) {
            // 48 = высота стеклянной капсулы (аватар 36 + 6+6 вертикальных отступов).
            HeaderIconButton(icon: "chevron.left", size: 48) { dismiss() }

            // Тап в любом месте капсулы (кроме кнопки звонка) открывает профиль;
            // кнопка звонка — меню «аудио/видео». Кнопки-дети выигрывают у тапа родителя.
            HStack(spacing: 6) {
                header
                Spacer(minLength: 0)
                if peerId != session.myId.lowercased() && !isGroup {
                    Menu {
                        Button { messaging.calls.startCall(peer: peerId, video: false) } label: {
                            Label("Аудиозвонок", systemImage: "phone.fill")
                        }
                        Button { messaging.calls.startCall(peer: peerId, video: true) } label: {
                            Label("Видеозвонок", systemImage: "video.fill")
                        }
                    } label: {
                        Image(systemName: "phone.fill")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(palette.accent)
                            .frame(width: 36, height: 36)
                            .contentShape(Circle())
                    }
                }
            }
            .padding(.leading, 10)
            .padding(.trailing, 6)
            .padding(.vertical, 6)
            .frame(maxWidth: .infinity)
            .contentShape(Capsule())
            .onTapGesture { showGroupProfile = true }
            .liquidGlass(Capsule())
        }
        .padding(.horizontal, 12)
        .padding(.top, 4)
        .padding(.bottom, 8)
        .background(EdgeDim(edge: .top).ignoresSafeArea(edges: .top))
    }

    private var header: some View {
        HStack(spacing: 10) {
            Avatar(id: peerId, name: title, size: 36,
                   avatarURL: messaging.avatarURL(peerId),
                   online: messaging.isOnline(peerId) && !isGroup && !isSaved)
                .onLongPressGesture {
                    guard !isGroup, peerId != session.myId.lowercased() else { return }
                    showAvatarMenu = true
                }
            VStack(alignment: .leading, spacing: 1) {
                HStack(spacing: 4) {
                    Text(title).font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(palette.textPrimary)
                    if !isGroup, !isSaved, let status = messaging.statusEmoji(peerId) {
                        Text(status).font(.system(size: 13))
                    }
                }
                if !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.system(size: 12))
                        .foregroundStyle(subtitle == "печатает…" || subtitle == "в сети" ? palette.accent : palette.textSecondary)
                }
            }
        }
    }

    // MARK: - Список сообщений

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                // Обычный VStack, НЕ LazyVStack: ленивые строки измеряются прямо во
                // время скролла, высота контента меняется под пальцем — отсюда рывки
                // в начале прокрутки. Страница — 40 сообщений с плоскими пузырями,
                // измерить всё заранее дешевле, чем дёргать ленту.
                VStack(spacing: 5) {
                    if vm.canLoadMore {
                        Button {
                            Task { await vm.loadMore() }
                        } label: {
                            if vm.loading { ProgressView().tint(palette.accent) }
                            else { Text("Показать более ранние сообщения").font(.caption) }
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(palette.accent)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                    }
                    ForEach(vm.timeline) { item in
                        switch item.kind {
                        case .date(let label):
                            DateSeparator(label: label)
                                .padding(.vertical, 6)
                        case .message(let msg, let tail, let showSender):
                            MessageBubble(
                                message: msg, isGroup: isGroup, channelStyle: isChannel,
                                viewCount: isChannel ? vm.viewCounts[msg.id] : nil,
                                showTail: tail, showSender: showSender,
                                myId: vm.myId, readTick: palette.readTick,
                                onQuoteTap: {
                                    if let rid = msg.payload?.replyToId {
                                        jumpToMessage(rid, proxy: proxy)
                                    }
                                },
                                onReply: { withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) { replyTo = msg; inputFocused = true } },
                                onQuickReact: { vm.react(to: msg, emoji: appearance.quickReaction) },
                                onPicker: { pickerFor = msg },
                                onEdit: { withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) { editing = msg; draft = msg.payload?.text ?? ""; inputFocused = true } },
                                onDelete: { vm.delete(msg) },
                                onRetry: { vm.retry(msg) }
                            )
                            // Комментарии: у канала с обсуждением — лёгкая кнопка под
                            // постом (БЕЗ стекла: 40 glassEffect на экран = лаги).
                            .safeAreaInset(edge: .bottom, spacing: 0) {
                                if isChannel, let linked = messaging.groups.info(peerId)?.linkedGroupId {
                                    Button {
                                        // Через общий deep-link (без вложенного
                                        // navigationDestination — на iOS 27 он
                                        // выкидывал из запушенного чата).
                                        NotificationCenter.default.post(
                                            name: NotificationsManager.openChatNotification,
                                            object: nil, userInfo: ["peer": linked.lowercased()]
                                        )
                                    } label: {
                                        HStack(spacing: 6) {
                                            Image(systemName: "bubble.left.and.bubble.right")
                                                .font(.system(size: 13, weight: .semibold))
                                            Text("Комментарии")
                                                .font(.system(size: 14, weight: .semibold))
                                        }
                                        .foregroundStyle(palette.accent)
                                        .padding(.horizontal, 12).padding(.vertical, 6)
                                        .background(palette.surface, in: Capsule())
                                    }
                                    .buttonStyle(.squish)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(.top, 4)
                                }
                            }
                            .id(msg.id)
                            .background {
                                if highlightedId == msg.id {
                                    RoundedRectangle(cornerRadius: Radius.control, style: .continuous)
                                        .fill(palette.accent.opacity(0.18))
                                        .padding(.horizontal, -8).padding(.vertical, -3)
                                        .transition(.opacity)
                                }
                            }
                            // Новое сообщение плавно въезжает снизу с фейдом — без
                            // scale-«попапа», как в Telegram.
                            .transition(.asymmetric(
                                insertion: .offset(y: 22).combined(with: .opacity),
                                removal: .opacity
                            ))
                        }
                    }
                    if messaging.typingPeers.contains(peerId) {
                        TypingBubble().id("typing")
                    }
                    Color.clear.frame(height: 8).id("bottom")
                }
                .padding(.horizontal, 10)
                .padding(.top, 8)
            }
            .scrollDismissesKeyboard(.interactively)
            // Контент сразу заякорен снизу — без программного скролла при открытии
            // (анимированный scrollTo на старте дрался с жестом пользователя:
            // начнёшь скроллить в этот момент — всё дёргается).
            .defaultScrollAnchor(.bottom)
            .onChange(of: vm.messages.last?.id) { oldId, newId in
                guard newId != nil else { return }
                if oldId == nil {
                    // Первая загрузка — мгновенный прыжок вниз, без анимации.
                    proxy.scrollTo("bottom", anchor: .bottom)
                } else {
                    // Новое сообщение — той же пружиной, что и вставка пузыря.
                    withAnimation(AetherUI.sendAnimation) {
                        proxy.scrollTo("bottom", anchor: .bottom)
                    }
                }
            }
            // Подъём ленты синхронно с клавиатурой: в keyboardWillShow финальная
            // геометрия уже известна, скроллим с длительностью её же анимации —
            // лента едет вместе с клавиатурой, а не после.
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { notif in
                let duration = notif.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double ?? 0.25
                withAnimation(.easeOut(duration: duration)) {
                    proxy.scrollTo("bottom", anchor: .bottom)
                }
                // Контрольный доскролл после завершения (округления инсетов).
                Task {
                    try? await Task.sleep(nanoseconds: UInt64((duration + 0.05) * 1_000_000_000))
                    proxy.scrollTo("bottom", anchor: .bottom)
                }
            }
        }
    }

    /// Прыжок к сообщению по id (тап по цитате): скролл к нему и плавно
    /// гаснущая подсветка на 3 секунды.
    private func jumpToMessage(_ id: String, proxy: ScrollViewProxy) {
        guard vm.messages.contains(where: { $0.id == id }) else { return }
        // Плавно, но резво: детерминированный easeInOut вместо пружины —
        // пружина на длинной дистанции выглядела как телепорт.
        withAnimation(.easeInOut(duration: 0.45)) {
            proxy.scrollTo(id, anchor: .center)
        }
        withAnimation(.easeIn(duration: 0.25)) {
            highlightedId = id
        }
        Task {
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard highlightedId == id else { return }
            withAnimation(.easeOut(duration: 0.9)) { highlightedId = nil }
        }
    }

    // MARK: - Поле ввода

    private var inputBarContainer: some View {
        inputBar
            .background(
                EdgeDim(edge: .bottom, boost: 1.6)
                    .padding(.top, -20)
                    .ignoresSafeArea(edges: .bottom)
            )
    }

    private var inputBar: some View {
        Group {
            if isReadOnlyChannel { readOnlyBar } else { composerBar }
        }
    }

    // Плашка для read-only канала: до первой подписки — крупная кнопка «Подписаться»
    // (включает уведомления), после — компактный переключатель звука. Как в Telegram.
    // Плавающий остров: стеклянная капсула с отступами, не во всю ширину.
    private var readOnlyBar: some View {
        Group {
            if channelSubscribed {
                HStack(spacing: 10) {
                    // Звук канала (подписка живёт, меняется только звук).
                    Button {
                        Task { await messaging.setMuted(peerId, true) }
                    } label: {
                        HStack(spacing: 8) {
                            Image(systemName: "bell.fill")
                            Text("Выключить звук")
                        }
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(palette.textPrimary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 13)
                        .liquidGlass(Capsule())
                        .contentShape(Capsule())
                    }
                    .buttonStyle(.squish)

                    // Маленькая кнопка «покинуть канал».
                    Button {
                        Task {
                            await messaging.groups.leave(groupId: peerId)
                            dismiss()
                        }
                    } label: {
                        Image(systemName: "rectangle.portrait.and.arrow.right")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(palette.danger)
                            .frame(width: 46, height: 46)
                            .liquidGlass(Circle())
                            .contentShape(Circle())
                    }
                    .buttonStyle(.squish)
                }
            } else {
                Button {
                    Task { await messaging.setMuted(peerId, false) }
                } label: {
                    Text("Подписаться")
                        .font(.headline)
                        .foregroundStyle(palette.onAccent)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(palette.accent, in: RoundedRectangle(cornerRadius: Radius.control, style: .continuous))
                }
                .buttonStyle(.squish)
            }
        }
        .padding(.horizontal, 12)
        .padding(.bottom, 8)
    }

    /// Толщина контролов композера: единая (толстая) в обоих состояниях —
    /// анимируются только ширина и позиция, без схлопывания в компактный размер.
    private var composerControl: CGFloat { 46 }

    private var composerBar: some View {
        VStack(spacing: 6) {
            // Единая раскладка во всех фазах: слева скрепка/корзина, посередине
            // поле/строка записи/обрезка, справа ОДНА круглая кнопка, которая
            // морфится: микрофон → (запись) → стоп → отправить. Кнопка не исчезает
            // из иерархии — жест зажатия не рвётся.
            HStack(alignment: .bottom, spacing: 8) {
                if recordPhase == .idle || recordPhase == .arming {
                    // Левая круглая кнопка — вложения (скрепка).
                    Button { showAttachMenu = true } label: {
                        Image(systemName: "paperclip")
                            .font(.system(size: 20, weight: .regular))
                            .foregroundStyle(palette.textSecondary)
                            .frame(width: composerControl, height: composerControl)
                            .liquidGlass(Circle())
                            .contentShape(Circle())
                            .rotationEffect(.degrees(showAttachMenu ? 45 : 0))
                    }
                    .buttonStyle(.squish)
                    .animation(.spring(response: 0.3, dampingFraction: 0.7), value: showAttachMenu)
                    .transition(.scale.combined(with: .opacity))
                } else {
                    // Во время записи/предпросмотра — корзина (отмена).
                    Button { cancelRecording() } label: {
                        Image(systemName: "trash.fill")
                            .font(.system(size: 18, weight: .regular))
                            .foregroundStyle(palette.danger)
                            .frame(width: composerControl, height: composerControl)
                            .liquidGlass(Circle())
                            .contentShape(Circle())
                    }
                    .buttonStyle(.squish)
                    .transition(.scale.combined(with: .opacity))
                }

                Group {
                    switch recordPhase {
                    case .recording, .locked:
                        recordingStrip
                    case .preview:
                        if circleMode {
                            HStack(spacing: 10) {
                                Image(systemName: "play.circle.fill")
                                    .foregroundStyle(palette.accent)
                                Text("Предпросмотр · \(Int(circlePreviewDuration))с")
                                    .font(.system(size: 15, weight: .medium))
                                    .foregroundStyle(palette.textPrimary)
                                Spacer()
                            }
                            .padding(.horizontal, 14)
                            .frame(minHeight: composerControl)
                            .frame(maxWidth: .infinity)
                            .liquidGlass(Capsule())
                        } else {
                        VoiceTrimStrip(duration: voicePreviewDuration, start: $trimStart, end: $trimEnd)
                            .padding(.horizontal, 14).padding(.vertical, 8)
                            .frame(maxWidth: .infinity)
                            .liquidGlass(RoundedRectangle(cornerRadius: Radius.panel, style: .continuous))
                        }
                    case .idle, .arming:
                        // Полоса ввода. Плашки ответа/редактирования — внутри той же
                        // стеклянной формы, как утолщение поля сверху.
                        VStack(spacing: 0) {
                            if let replyTo {
                                replyPreview(replyTo)
                                    .transition(.move(edge: .bottom).combined(with: .opacity))
                                Rectangle().fill(palette.divider).frame(height: 0.5)
                                    .padding(.horizontal, 12)
                            }
                            if editing != nil {
                                editBanner
                                    .transition(.move(edge: .bottom).combined(with: .opacity))
                                Rectangle().fill(palette.divider).frame(height: 0.5)
                                    .padding(.horizontal, 12)
                            }
                            TextField("Сообщение", text: $draft, axis: .vertical)
                                .lineLimit(1...5)
                                .focused($inputFocused)
                                .foregroundStyle(palette.textPrimary)
                                .padding(.horizontal, 14).padding(.vertical, 9)
                                .frame(minHeight: composerControl, alignment: .center)
                                .onChange(of: draft) { _, value in
                                    vm.typingChanged(isEmpty: value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                                }
                        }
                        .frame(maxWidth: .infinity)
                        .liquidGlass(RoundedRectangle(cornerRadius: Radius.panel, style: .continuous))
                        .padding(.horizontal, inputFocused ? 0 : 8)
                        .animation(AetherUI.sendAnimation, value: replyTo?.id)
                        .animation(AetherUI.sendAnimation, value: editing?.id)
                    }
                }

                // Правая круглая кнопка (морф по фазе).
                sendOrMic
            }
            .animation(AetherUI.sendAnimation, value: inputFocused)
            .animation(AetherUI.sendAnimation, value: recordPhase)
        }
        // Без клавиатуры: бар уже по ширине, ниже к краю и толще (composerControl 46);
        // с клавиатурой — компактный, одинаковый зазор с боков и от клавиатуры.
        .padding(.horizontal, inputFocused ? 8 : 24)
        .padding(.bottom, inputFocused ? 8 : 4)
        .animation(AetherUI.sendAnimation, value: inputFocused)
    }

    // Иконка-кнопка в поле ввода (без стеклянного кружка — панель уже стеклянная).
    private func composerButton(_ icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: 20, weight: .regular))
                .foregroundStyle(palette.textSecondary)
                .frame(width: composerControl, height: composerControl)
                .contentShape(Circle())
        }
        .buttonStyle(.squish)
    }

    // Микрофон/кружок (зажать — запись) либо кнопка отправки при наборе текста.
    // Тап переключает голос/кружок; зажатие — стартует запись (голос — тут же,
    // кружок — открывает полноэкранную камеру с автостартом). Как в Telegram.
    // В обоих состояниях — круглый, чтобы совпадать с левой кнопкой-скрепкой.
    // Морф между состояниями плавный: scale+opacity transition + spring по
    // draft.isEmpty, чтобы кнопка «превращалась» в отправку, а не мгновенно
    // перещёлкивалась.
    @ViewBuilder private var sendOrMic: some View {
        let hasText = !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        Group {
            switch recordPhase {
            case .locked:
                if circleMode {
                    // Кружок в локе: красный стоп-квадрат — стоп и предпросмотр.
                    Button { finishCircle(preview: true) } label: {
                        // Глиф стоп-квадрата 17×17 внутри круга — радиус привязан
                        // к его собственному размеру, а не к шкале контейнеров.
                        RoundedRectangle(cornerRadius: 5, style: .continuous)
                            .fill(.white)
                            .frame(width: 17, height: 17)
                            .frame(width: composerControl, height: composerControl)
                            .background(palette.danger, in: Circle())
                            .contentShape(Circle())
                    }
                    .buttonStyle(.squish)
                    .transition(.scale.combined(with: .opacity))
                } else {
                // Голос: «закончить» → превью с обрезкой.
                Button { stopToPreview() } label: {
                    Image(systemName: "stop.fill")
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(palette.onAccent)
                        .frame(width: composerControl, height: composerControl)
                        .background(palette.danger, in: Circle())
                        .contentShape(Circle())
                }
                .buttonStyle(.squish)
                .transition(.scale.combined(with: .opacity))
                }
            case .preview:
                if circleMode {
                    // Кружок: предпросмотр — кнопка отправки.
                    Button { sendCirclePreview() } label: {
                        Image(systemName: "arrow.up")
                            .font(.system(size: 20, weight: .bold))
                            .foregroundStyle(palette.onAccent)
                            .frame(width: composerControl, height: composerControl)
                            .background(palette.accent, in: Circle())
                            .contentShape(Circle())
                    }
                    .buttonStyle(.squish)
                    .transition(.scale.combined(with: .opacity))
                } else {
                // Голос: запись закончена — кнопка «отправить» (с обрезкой).
                Button { sendTrimmedVoice() } label: {
                    Image(systemName: "arrow.up")
                        .font(.system(size: 20, weight: .bold))
                        .foregroundStyle(palette.onAccent)
                        .frame(width: composerControl, height: composerControl)
                        .background(palette.accent, in: Circle())
                        .contentShape(Circle())
                }
                .buttonStyle(.squish)
                .transition(.scale.combined(with: .opacity))
                }
            default:
                if hasText {
                    Button(action: submit) {
                        Image(systemName: "arrow.up")
                            .font(.system(size: 20, weight: .bold))
                            .foregroundStyle(palette.onAccent)
                            .frame(width: composerControl, height: composerControl)
                            .background(palette.accent, in: Circle())
                            .overlay(Circle().stroke(.white.opacity(0.12), lineWidth: 0.5))
                            .contentShape(Circle())
                    }
                    .buttonStyle(.squish)
                    .transition(.scale.combined(with: .opacity))
                } else {
                    Image(systemName: circleMode ? "video.fill" : "mic.fill")
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(recordPhase == .recording ? palette.onAccent : palette.accent)
                        .frame(width: composerControl, height: composerControl)
                        .background {
                            if recordPhase == .recording {
                                Circle().fill(palette.accent)
                            }
                        }
                        .liquidGlass(Circle())
                        .contentShape(Circle())
                        .scaleEffect(recordPhase == .recording ? 1.2 : 1)
                        .gesture(micGesture)
                        .transition(.scale.combined(with: .opacity))
                }
            }
        }
        .animation(.spring(response: 0.3, dampingFraction: 0.8), value: draft.isEmpty)
        .animation(.spring(response: 0.3, dampingFraction: 0.8), value: recordPhase)
    }

    // Единый жест для микрофона/камеры: короткий тап (< holdThreshold, без движения)
    // переключает режим голос/кружок; более долгое нажатие запускает запись.
    // Во время записи голоса: свайп вверх — лок (hands-free), свайп влево — отмена,
    // отпустить — отправка. Курок кружка просто открывает камеру (автостарт внутри).
    private var micGesture: some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                switch recordPhase {
                case .idle:
                    recordPhase = .arming
                    dragTranslation = .zero
                    armTask?.cancel()
                    armTask = Task {
                        try? await Task.sleep(nanoseconds: UInt64(holdThreshold * 1_000_000_000))
                        guard !Task.isCancelled else { return }
                        await MainActor.run { beginHold() }
                    }
                case .arming:
                    break   // ждём решения таймера — тап это или зажатие
                case .recording:
                    dragTranslation = value.translation
                    if value.translation.height < lockDistance { lockRecording() }
                case .locked, .preview:
                    break
                }
            }
            .onEnded { value in
                armTask?.cancel()
                switch recordPhase {
                case .arming:
                    // Отпустили раньше порога — это тап, а не зажатие.
                    recordPhase = .idle
                    withAnimation(.easeInOut(duration: 0.15)) { circleMode.toggle() }
                case .recording:
                    if circleMode {
                        if value.translation.height < lockDistance { lockRecording(); return }
                        if value.translation.width < cancelDistance { cancelRecording() }
                        else { finishCircle(preview: false) }
                        return
                    }
                    guard recorder.isRecording else { cancelRecording(); return }
                    if value.translation.height < lockDistance { lockRecording(); return }
                    if value.translation.width < cancelDistance { cancelRecording() }
                    else { finishAndSend() }
                case .locked, .idle, .preview:
                    break
                }
            }
    }

    private func beginHold() {
        guard recordPhase == .arming else { return }
        if circleMode {
            // Кружок: инлайн-запись тем же жестом, что голосовые.
            switch AVCaptureDevice.authorizationStatus(for: .video) {
            case .authorized:
                recordPhase = .recording
                Task {
                    circleCam.setFinishHandler { url in circleFinished(url) }
                    await circleCam.configure()
                    await MainActor.run {
                        if circleCam.available, recordPhase == .recording || recordPhase == .locked {
                            circleCam.startRecording()
                        } else if !circleCam.available {
                            recordPhase = .idle
                            attachmentError = "Камера недоступна."
                        }
                    }
                }
            case .notDetermined:
                recordPhase = .idle
                AVCaptureDevice.requestAccess(for: .video) { _ in }   // спросили; зажмёт ещё раз
            default:
                recordPhase = .idle
                attachmentError = "Нет доступа к камере. Разреши в Настройках iOS."
            }
            return
        }
        // Разрешение микрофона проверяем ДО старта записи: системный диалог
        // крадёт палец у жеста, и запись зависала без кнопок управления.
        switch AVAudioApplication.shared.recordPermission {
        case .granted:
            recordPhase = .recording
            recorder.start()
        case .undetermined:
            recordPhase = .idle
            Task { _ = await recorder.requestPermission() }   // спросили; зажмёт ещё раз
        default:
            recordPhase = .idle
            attachmentError = "Нет доступа к микрофону. Разреши в Настройках iOS."
        }
    }

    private func lockRecording() {
        guard recordPhase == .recording else { return }
        withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) { recordPhase = .locked }
        dragTranslation = .zero
    }

    private func cancelRecording() {
        if circleMode {
            circleSwitching = false
            if circleCam.isRecording { circleCam.cancelRecording() }
            circleCam.stop()
            for u in circleSegments { try? FileManager.default.removeItem(at: u) }
            circleSegments = []
            circleRecordedBefore = 0
            circleWantPreview = false
            if let url = circlePreviewURL { try? FileManager.default.removeItem(at: url) }
            circlePreviewURL = nil
        }
        recorder.cancel()
        if let url = voicePreviewURL {
            try? FileManager.default.removeItem(at: url)
            voicePreviewURL = nil
        }
        withAnimation(AetherUI.sendAnimation) { recordPhase = .idle }
        dragTranslation = .zero
    }

    /// Кружок: остановить запись. preview=true — показать предпросмотр (стоп-квадрат),
    /// false — мгновенная отправка (отпустил палец). Итог решается в circleFinished.
    private func finishCircle(preview: Bool = false) {
        guard circleCam.isRecording else { cancelRecording(); return }
        circleWantPreview = preview
        circleCam.stopRecording()
        if !preview {
            withAnimation(AetherUI.sendAnimation) { recordPhase = .idle }
        }
        dragTranslation = .zero
    }

    /// Переворот камеры во время записи: закрываем сегмент, свитчимся,
    /// продолжаем следующим сегментом (склейка при отправке).
    private func flipCircleCamera() {
        if circleCam.isRecording {
            guard !circleSwitching else { return }
            circleSwitching = true
            circleCam.stopRecording()   // сегмент придёт в circleFinished
        } else {
            circleCam.switchCamera()
        }
    }

    private func circleFinished(_ url: URL) {
        let duration = circleCam.elapsed

        if circleSwitching {
            // Промежуточный сегмент из-за свитча: копим и продолжаем запись.
            if duration >= 0.3 {
                circleSegments.append(url)
                circleRecordedBefore += duration
            } else {
                try? FileManager.default.removeItem(at: url)
            }
            circleCam.switchCamera()
            circleCam.startRecording()
            circleSwitching = false
            return
        }

        // Финал: собрать сегменты → превью или мгновенная отправка.
        if duration >= 0.3 { circleSegments.append(url) } else { try? FileManager.default.removeItem(at: url) }
        let segments = circleSegments
        let total = min(circleRecordedBefore + max(duration, 0), 60)
        let wantPreview = circleWantPreview
        circleWantPreview = false
        circleSegments = []
        circleRecordedBefore = 0
        guard !segments.isEmpty, total >= 0.5 else {
            for u in segments { try? FileManager.default.removeItem(at: u) }
            circleCam.stop()
            withAnimation(AetherUI.sendAnimation) { recordPhase = .idle }
            return
        }
        Task {
            // Единый файл (склейка при сегментах); для превью файл сохраняем.
            let fileURL: URL?
            if segments.count == 1 {
                fileURL = segments[0]
            } else {
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
                if merged != nil {
                    for u in segments { try? FileManager.default.removeItem(at: u) }
                }
                fileURL = merged ?? segments.last
            }
            guard let fileURL else {
                circleCam.stop()
                withAnimation(AetherUI.sendAnimation) { recordPhase = .idle }
                return
            }
            if wantPreview {
                // Стоп-квадрат: показать предпросмотр, камера больше не нужна.
                circleCam.stop()
                circlePreviewURL = fileURL
                circlePreviewDuration = total
                withAnimation(AetherUI.sendAnimation) { recordPhase = .preview }
            } else {
                defer { try? FileManager.default.removeItem(at: fileURL) }
                circleCam.stop()
                // 30-сек кружок — десятки МБ: читаем вне главного потока,
                // иначе UI «зависает» на несколько секунд.
                guard let data = await Task.detached(priority: .userInitiated, operation: {
                    try? Data(contentsOf: fileURL)
                }).value else { return }
                vm.sendMedia(data: data, mime: "video/mp4", kind: "video_msg", fileName: nil, duration: total, replyTo: replyTo)
                clearReplyAfterSend()
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            }
        }
    }

    /// Отправка кружка из предпросмотра.
    private func sendCirclePreview() {
        guard let url = circlePreviewURL else { return }
        circlePreviewURL = nil
        withAnimation(AetherUI.sendAnimation) { recordPhase = .idle }
        Task {
            defer { try? FileManager.default.removeItem(at: url) }
            guard let data = await Task.detached(priority: .userInitiated, operation: {
                try? Data(contentsOf: url)
            }).value else { return }
            vm.sendMedia(data: data, mime: "video/mp4", kind: "video_msg", fileName: nil, duration: circlePreviewDuration, replyTo: replyTo)
            clearReplyAfterSend()
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        }
    }

    /// Сброс плашки ответа после отправки медиа (ГС/кружок/вложение-ответ).
    private func clearReplyAfterSend() {
        guard replyTo != nil else { return }
        Task { @MainActor in
            withAnimation(AetherUI.sendAnimation) { replyTo = nil }
        }
    }

    private func finishAndSend() {
        if let (data, dur) = recorder.finish() {
            vm.sendMedia(data: data, mime: "audio/mp4", kind: "voice", fileName: nil, duration: dur, replyTo: replyTo)
            clearReplyAfterSend()
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        }
        recordPhase = .idle
        dragTranslation = .zero
    }

    // Тонкая строка записи на месте поля ввода: точка, таймер, подсказки.
    private var recordingStrip: some View {
        HStack(spacing: 10) {
            RecordingDot()
            Text(timeString(circleMode ? circleTotalElapsed : recorder.elapsed))
                .font(.system(size: 16, weight: .medium, design: .monospaced))
                .foregroundStyle(palette.textPrimary)
                .contentTransition(.numericText())
            Spacer(minLength: 8)
            if recordPhase == .locked {
                Label("Идёт запись", systemImage: "lock.fill")
                    .font(.caption)
                    .foregroundStyle(palette.textSecondary)
                    .labelStyle(.titleAndIcon)
            } else if dragTranslation.width < cancelDistance {
                Text("Отпусти — отмена")
                    .font(.caption).foregroundStyle(palette.danger)
            } else {
                HStack(spacing: 10) {
                    Text("← отмена")
                    VStack(spacing: 1) {
                        Image(systemName: "chevron.up").font(.system(size: 9, weight: .bold))
                        Image(systemName: "lock.fill").font(.system(size: 11))
                    }
                    .offset(y: max(lockDistance, min(0, dragTranslation.height)) * 0.4)
                }
                .font(.caption)
                .foregroundStyle(palette.textSecondary)
            }
        }
        .padding(.horizontal, 14)
        .frame(minHeight: composerControl)
        .frame(maxWidth: .infinity)
        .liquidGlass(Capsule())
    }

    /// Локнутая запись: «закончить» → предпросмотр с обрезкой.
    private func stopToPreview() {
        guard recordPhase == .locked else { return }
        if let (url, duration) = recorder.stopKeepingFile() {
            voicePreviewURL = url
            voicePreviewDuration = duration
            trimStart = 0
            trimEnd = 1
            withAnimation(AetherUI.sendAnimation) { recordPhase = .preview }
        } else {
            recordPhase = .idle
        }
        dragTranslation = .zero
    }

    /// Отправка из предпросмотра с учётом обрезки.
    private func sendTrimmedVoice() {
        guard let url = voicePreviewURL else { recordPhase = .idle; return }
        let startSec = Double(trimStart) * voicePreviewDuration
        let endSec = Double(trimEnd) * voicePreviewDuration
        voicePreviewURL = nil
        withAnimation(AetherUI.sendAnimation) { recordPhase = .idle }
        Task {
            defer { try? FileManager.default.removeItem(at: url) }
            let result: (data: Data, duration: TimeInterval)?
            if trimStart <= 0.001 && trimEnd >= 0.999 {
                result = (try? Data(contentsOf: url)).map { ($0, voicePreviewDuration) }
            } else {
                result = await AudioTrimmer.trim(url: url, start: startSec, end: endSec)
            }
            if let (data, duration) = result {
                vm.sendMedia(data: data, mime: "audio/mp4", kind: "voice", fileName: nil, duration: duration, replyTo: replyTo)
                clearReplyAfterSend()
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            }
        }
    }

    private func timeString(_ t: TimeInterval) -> String {
        String(format: "%d:%02d", Int(t) / 60, Int(t) % 60)
    }

    private func replyPreview(_ msg: ChatMessage) -> some View {
        HStack(spacing: 8) {
            Rectangle().fill(palette.accent).frame(width: 3, height: 34).clipShape(Capsule())
            VStack(alignment: .leading, spacing: 1) {
                Text("Ответ").font(.caption.weight(.semibold)).foregroundStyle(palette.accent)
                Text(Wire.preview(msg.payloadJson)).font(.caption).foregroundStyle(palette.textSecondary).lineLimit(1)
            }
            Spacer()
            // Крестик: крупная хит-зона + plain-стиль, чтобы тап не съедался
            // контейнером/стеклом и гарантированно закрывал плашку.
            Button {
                withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) { replyTo = nil }
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.system(size: 18))
                    .foregroundStyle(palette.textSecondary)
                    .frame(width: 40, height: 40)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.leading, 14).padding(.trailing, 4).padding(.vertical, 4)
    }

    private var editBanner: some View {
        HStack(spacing: 8) {
            Image(systemName: "pencil").foregroundStyle(palette.accent)
            Text("Редактирование").font(.caption.weight(.semibold)).foregroundStyle(palette.accent)
            Spacer()
            Button {
                withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) { editing = nil; draft = "" }
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.system(size: 18))
                    .foregroundStyle(palette.textSecondary)
                    .frame(width: 40, height: 40)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.leading, 14).padding(.trailing, 4).padding(.vertical, 4)
    }

    private func submit() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        if let editing {
            vm.edit(editing, newText: text)
        } else {
            vm.send(text: text, replyTo: replyTo)
        }
        withAnimation(AetherUI.sendAnimation) {
            draft = ""
            replyTo = nil
            self.editing = nil
        }
    }

    @ViewBuilder private var wallpaper: some View {
        palette.background.ignoresSafeArea()
        if let img = wallpaperStore.image {
            // Пользовательские обои + вуаль читаемости (сильнее на тёмных темах).
            GeometryReader { geo in
                Image(uiImage: img)
                    .resizable()
                    .scaledToFill()
                    .frame(width: geo.size.width, height: geo.size.height)
                    .clipped()
            }
            .ignoresSafeArea()
            Color.black.opacity(appearance.theme.isDark ? 0.32 : 0.10)
                .ignoresSafeArea()
        } else {
            LinearGradient(colors: [palette.accent.opacity(0.04), .clear],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
        }
    }

}

// Обрезка голосового: волна + две ручки (начало/конец), тайминги снизу.
// start/end — доли 0…1 от полной длительности.
struct VoiceTrimStrip: View {
    let duration: TimeInterval
    @Binding var start: CGFloat
    @Binding var end: CGFloat
    @Environment(\.palette) private var palette

    private let minGap: CGFloat = 0.08   // минимум ~8% длительности

    var body: some View {
        VStack(spacing: 5) {
            GeometryReader { geo in
                let width = geo.size.width
                ZStack(alignment: .leading) {
                    waveform(width: width)
                    // Затемнение отрезанных краёв.
                    Rectangle().fill(Color.black.opacity(0.45))
                        .frame(width: max(0, start * width))
                    Rectangle().fill(Color.black.opacity(0.45))
                        .frame(width: max(0, (1 - end) * width))
                        .offset(x: end * width)
                    handle(at: start * width) { x in
                        start = min(max(0, x / width), end - minGap)
                    }
                    handle(at: end * width) { x in
                        end = max(min(1, x / width), start + minGap)
                    }
                }
            }
            .frame(height: 34)
            HStack {
                Text(timeText(Double(start) * duration))
                Spacer()
                Text(timeText(Double(end) * duration))
            }
            .font(.system(size: 11, weight: .medium, design: .monospaced))
            .foregroundStyle(palette.textSecondary)
        }
    }

    private func waveform(width: CGFloat) -> some View {
        let count = max(16, Int(width / 4.5))
        return HStack(alignment: .center, spacing: 2) {
            ForEach(0..<count, id: \.self) { index in
                let height = 8 + 22 * abs(sin(Double(index) * 1.7 + duration))
                Capsule()
                    .fill(palette.accent)
                    .frame(width: 2.5, height: height)
            }
        }
        .frame(width: width, height: 34)
    }

    private func handle(at x: CGFloat, onDrag: @escaping (CGFloat) -> Void) -> some View {
        Capsule()
            .fill(.white)
            .frame(width: 5, height: 34)
            .shadow(radius: 1.5)
            .contentShape(Rectangle().inset(by: -12))
            .position(x: x, y: 17)
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { onDrag($0.location.x) }
            )
    }

    private func timeText(_ t: TimeInterval) -> String {
        String(format: "%d:%02d", Int(t) / 60, Int(t) % 60)
    }
}

// Пульсирующая красная точка записи.
struct RecordingDot: View {
    @Environment(\.palette) private var palette
    @State private var pulsing = false
    var body: some View {
        Circle()
            .fill(palette.danger)
            .frame(width: 11, height: 11)
            .opacity(pulsing ? 0.35 : 1)
            .animation(.easeInOut(duration: 0.7).repeatForever(autoreverses: true), value: pulsing)
            .onAppear { pulsing = true }
    }
}

struct DateSeparator: View {
    let label: String
    @Environment(\.palette) private var palette
    var body: some View {
        Text(label)
            .font(.system(size: 12, weight: .medium))
            .foregroundStyle(.white.opacity(0.92))
            .padding(.horizontal, 12).padding(.vertical, 5)
            .background(.black.opacity(0.26), in: Capsule())
            .frame(maxWidth: .infinity)
    }
}

struct TypingBubble: View {
    @Environment(\.palette) private var palette
    @State private var phase = 0.0
    var body: some View {
        HStack(spacing: 4) {
            ForEach(0..<3) { i in
                Circle().fill(palette.textSecondary)
                    .frame(width: 7, height: 7)
                    .scaleEffect(1 + 0.4 * sin(phase + Double(i) * 0.6))
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 11)
        .background(palette.bubbleIn, in: RoundedRectangle(cornerRadius: Radius.control, style: .continuous))
        .frame(maxWidth: .infinity, alignment: .leading)
        .onAppear {
            withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) { phase = .pi * 2 }
        }
    }
}

// Пикер реакций (быстрый ряд эмодзи).
struct ReactionPicker: View {
    var onPick: (String) -> Void
    private let emojis = ["❤️", "👍", "🔥", "😂", "😮", "😢", "🙏", "👎"]
    var body: some View {
        HStack(spacing: 12) {
            ForEach(emojis, id: \.self) { e in
                Button { onPick(e) } label: {
                    Text(e).font(.system(size: 30))
                }.buttonStyle(.squish)
            }
        }
        .padding()
    }
}

// Плавающий стеклянный остров ввода: скруглённая капсула с жидким стеклом,
// поверх которой стоит контент поля/кнопок. Не во всю ширину — отступы снаружи.
// Уважает glassOnInput: при выключенном стекле на инпуте — плоская surface-капсула.
private struct IslandBackground: ViewModifier {
    var cornerRadius: CGFloat = Radius.panel
    @EnvironmentObject var appearance: AppearanceSettings
    @Environment(\.palette) private var palette

    func body(content: Content) -> some View {
        if appearance.glassEnabled && appearance.glassOnInput {
            content.liquidGlass(cornerRadius: cornerRadius, interactive: false)
        } else {
            content
                .background(palette.surface, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .stroke(palette.divider, lineWidth: 0.5))
        }
    }
}

private extension View {
    func islandBackground(cornerRadius: CGFloat = Radius.panel) -> some View {
        modifier(IslandBackground(cornerRadius: cornerRadius))
    }
}
