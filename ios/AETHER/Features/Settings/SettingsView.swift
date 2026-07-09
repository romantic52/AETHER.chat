import SwiftUI
import PhotosUI

struct SettingsView: View {
    @EnvironmentObject var session: Session
    @EnvironmentObject var appearance: AppearanceSettings
    @Environment(\.palette) private var palette

    var body: some View {
        NavigationStack {
            ZStack {
                palette.background.ignoresSafeArea()
                List {
                    profileSection
                    notificationsSection
                    appearanceSection
                    glassSection
                    bubbleSection
                    navigationSection
                    reactionSection
                    storageSection
                    accountSection
                }
                .listStyle(.insetGrouped)
                .scrollContentBackground(.hidden)
            }
            .toolbar(.hidden, for: .navigationBar)
            .safeAreaInset(edge: .top) { FloatingHeader(title: "Настройки") }
            .safeAreaPadding(.bottom, 100)
        }
    }

    private var profileSection: some View {
        Section {
            VStack(spacing: 8) {
                Avatar(id: session.myId, name: session.myDisplayName, size: 82, avatarURL: session.myAvatarURL)
                VStack(spacing: 3) {
                    Text(session.myDisplayName.isEmpty ? session.myId : session.myDisplayName)
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(palette.textPrimary)
                    Text(session.myUsername.isEmpty ? "@\(session.myId)" : "@\(session.myUsername)")
                        .font(.subheadline).foregroundStyle(palette.textSecondary)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            NavigationLink { ProfileEditorView() } label: {
                SettingsLabel("Редактировать профиль", icon: "person.crop.circle.fill", color: .blue)
            }
        }
        .listRowBackground(palette.surface)
    }

    @AppStorage(NotificationsManager.notifyKey) private var notifyMessages = false
    @AppStorage(NotificationsManager.islandKey) private var islandCounter = false

    private var notificationsSection: some View {
        Section {
            Toggle(isOn: $notifyMessages) {
                SettingsLabel("Уведомления о сообщениях", icon: "bell.badge.fill", color: .red)
            }
            .onChange(of: notifyMessages) { _, on in
                if on { Task { await NotificationsManager.shared.requestPermission() } }
            }
            Toggle(isOn: $islandCounter) {
                SettingsLabel("Счётчик в Dynamic Island", icon: "capsule.fill", color: .indigo)
            }
            .onChange(of: islandCounter) { _, on in
                if !on { NotificationsManager.shared.endIsland() }
            }
        } header: {
            Text("Уведомления")
        } footer: {
            Text("Счётчик показывает число сообщений за день и последнего отправителя в Dynamic Island; тап по островку открывает чат. Работает, пока приложение запущено (сервер пока без push).")
        }
        .listRowBackground(palette.surface)
    }

    // MARK: - Данные и память

    @State private var cacheBytes: Int64 = -1
    @State private var confirmClearCache = false

    private var storageSection: some View {
        Section {
            HStack {
                SettingsLabel("Кеш медиа", icon: "internaldrive.fill", color: .teal)
                Spacer()
                Text(cacheBytes < 0 ? "…" : ByteCountFormatter.string(fromByteCount: cacheBytes, countStyle: .file))
                    .foregroundStyle(palette.textSecondary)
            }
            Button(role: .destructive) { confirmClearCache = true } label: {
                SettingsLabel("Очистить кеш", icon: "trash.fill", color: .red)
            }
            .disabled(cacheBytes <= 0)
        } header: {
            Text("Данные и память")
        } footer: {
            Text("Фото, видео, голосовые и аватарки хранятся в кеше и не скачиваются повторно. Очистка не удаляет сообщения — медиа скачается заново при открытии.")
        }
        .listRowBackground(palette.surface)
        .task { await refreshCacheSize() }
        .confirmationDialog("Очистить кеш?", isPresented: $confirmClearCache, titleVisibility: .visible) {
            Button("Очистить", role: .destructive) {
                Task {
                    await MediaStore.shared.clearCache()
                    AvatarStore.shared.clearRemoteCache()
                    await refreshCacheSize()
                }
            }
        }
    }

    private func refreshCacheSize() async {
        let media = await MediaStore.shared.cacheSizeBytes()
        cacheBytes = media + AvatarStore.shared.remoteCacheSizeBytes()
    }

    private var appearanceSection: some View {
        Section("Внешний вид") {
            // Свотчи 6 тем.
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    ForEach(ThemePalette.allCases) { theme in
                        ThemeSwatch(theme: theme, selected: appearance.theme == theme) {
                            withAnimation(.easeInOut(duration: 0.25)) { appearance.theme = theme }
                        }
                    }
                }
                .padding(.vertical, 4)
            }

            Toggle(isOn: $appearance.edgeDimEnabled) {
                SettingsLabel("Затемнение краёв", icon: "circle.tophalf.filled", color: .gray)
            }
            if appearance.edgeDimEnabled {
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        SettingsLabel("Сила затемнения", icon: "sun.min.fill", color: .yellow)
                        Spacer()
                        Text("\(Int(appearance.edgeDimStrength * 100))%")
                            .font(.caption).foregroundStyle(palette.textSecondary)
                    }
                    Slider(value: $appearance.edgeDimStrength, in: 0.05...0.5)
                }
            }
        }
        .listRowBackground(palette.surface)
    }

    private var glassSection: some View {
        Section {
            Toggle(isOn: $appearance.glassEnabled) {
                SettingsLabel("Жидкое стекло", icon: "sparkles", color: .indigo)
            }
            if appearance.glassEnabled {
                Picker(selection: $appearance.glassStyle) {
                    ForEach(GlassStyleOption.allCases) { style in
                        Text(style.title).tag(style)
                    }
                } label: {
                    SettingsLabel("Режим", icon: "circle.lefthalf.filled", color: .cyan)
                }
                .pickerStyle(.segmented)

                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        SettingsLabel("Оттенок темы", icon: "paintpalette.fill", color: .pink)
                        Spacer()
                        Text("\(Int(appearance.glassTint * 100))%")
                            .font(.caption).foregroundStyle(palette.textSecondary)
                    }
                    Slider(value: $appearance.glassTint, in: 0...0.35)
                }

                Toggle(isOn: $appearance.glassInteractive) {
                    SettingsLabel("Отклик на касание", icon: "hand.tap.fill", color: .orange)
                }
                Toggle(isOn: $appearance.glassOnInput) {
                    SettingsLabel("Стекло на поле ввода", icon: "keyboard", color: .green)
                }
            }
        } header: {
            Text("Жидкое стекло")
        } footer: {
            Text("Режим и оттенок напрямую меняют системный Glass на доке. Отклик включает деформацию при касании, но не возвращает постоянные фоновые анимации.")
        }
        .listRowBackground(palette.surface)
    }

    // Мини-демо стекла с текущими настройками (стиль/оттенок/отклик). Специально ощутимо
    // ýже и без подписей-табов — чтобы не путать с настоящим навигационным баром внизу
    // экрана (эта путаница уже случалась: пользователь принял превью за второй бар).

    private var bubbleSection: some View {
        Section {
            // Превью пузырей — входящий и исходящий
            VStack(spacing: 6) {
                HStack {
                    Text("Привет!")
                        .font(.system(size: 15))
                        .foregroundStyle(palette.textPrimary)
                        .padding(.horizontal, 12).padding(.vertical, 7)
                        .background {
                            BubbleShapeNew(outgoing: false, tail: appearance.bubbleTails,
                                           radius: CGFloat(appearance.bubbleRadius),
                                           connected: CGFloat(appearance.bubbleConnected))
                                .fill(palette.bubbleIn)
                        }
                    Spacer()
                }
                HStack {
                    Spacer()
                    Text("Как дела?")
                        .font(.system(size: 15))
                        .foregroundStyle(palette.bubbleOutText)
                        .padding(.horizontal, 12).padding(.vertical, 7)
                        .background {
                            BubbleShapeNew(outgoing: true, tail: appearance.bubbleTails,
                                           radius: CGFloat(appearance.bubbleRadius),
                                           connected: CGFloat(appearance.bubbleConnected))
                                .fill(palette.bubbleOut)
                        }
                }
            }
            .padding(.vertical, 8)
            .listRowBackground(Color.clear)
            .animation(.easeInOut(duration: 0.2), value: appearance.bubbleRadius)
            .animation(.easeInOut(duration: 0.2), value: appearance.bubbleConnected)
            .animation(.easeInOut(duration: 0.2), value: appearance.bubbleTails)

            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    SettingsLabel("Скругление", icon: "circle.bottomhalf.filled", color: .blue)
                    Spacer()
                    Text("\(Int(appearance.bubbleRadius))")
                        .font(.caption).foregroundStyle(palette.textSecondary)
                }
                Slider(value: $appearance.bubbleRadius, in: 0...30, step: 1)
            }

            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    SettingsLabel("Углы склейки", icon: "square.bottomhalf.filled", color: .purple)
                    Spacer()
                    Text("\(Int(appearance.bubbleConnected))")
                        .font(.caption).foregroundStyle(palette.textSecondary)
                }
                Slider(value: $appearance.bubbleConnected, in: 0...20, step: 1)
            }

            Toggle(isOn: $appearance.bubbleTails) {
                SettingsLabel("Хвостики", icon: "bubble.left.fill", color: .teal)
            }
        } header: {
            Text("Пузыри сообщений")
        } footer: {
            Text("Скругление — радиус углов пузыря. Углы склейки — радиус стыковки последовательных сообщений. Хвостики — треугольный «хвост» у последнего сообщения в группе.")
        }
        .listRowBackground(palette.surface)
    }

    private var navigationSection: some View {
        Section("Навигация") {
            Toggle(isOn: $appearance.switchTabOnRelease) {
                SettingsLabel("Переключать при отпускании", icon: "hand.tap.fill", color: .green)
            }
        }
        .listRowBackground(palette.surface)
    }

    private var reactionSection: some View {
        Section("Чаты") {
            HStack {
                SettingsLabel("Быстрая реакция", icon: "heart.fill", color: .red)
                Spacer()
                Menu {
                    ForEach(["❤️", "👍", "🔥", "😂", "🙏", "😮", "😢"], id: \.self) { e in
                        Button(e) { appearance.quickReaction = e }
                    }
                } label: {
                    Text(appearance.quickReaction).font(.system(size: 22))
                }
            }
        }
        .listRowBackground(palette.surface)
    }

    private var accountSection: some View {
        Section {
            Button(role: .destructive) {
                Task { await session.logout() }
            } label: {
                SettingsLabel("Выйти", icon: "arrow.right.square.fill", color: .red)
            }
        }
        .listRowBackground(palette.surface)
    }
}

struct SettingsLabel: View {
    let title: String
    let icon: String
    let color: Color

    init(_ title: String, icon: String, color: Color) {
        self.title = title
        self.icon = icon
        self.color = color
    }

    var body: some View {
        HStack(spacing: 11) {
            Image(systemName: icon)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 28, height: 28)
                .background(color, in: RoundedRectangle(cornerRadius: 7, style: .continuous))
            Text(title)
        }
    }
}

struct ThemeSwatch: View {
    let theme: ThemePalette
    let selected: Bool
    let action: () -> Void

    var body: some View {
        let p = theme.palette
        Button(action: action) {
            VStack(spacing: 6) {
                ZStack {
                    RoundedRectangle(cornerRadius: 14, style: .continuous).fill(p.background)
                    VStack(spacing: 4) {
                        Capsule().fill(p.bubbleIn).frame(width: 34, height: 10)
                        Capsule().fill(p.accent).frame(width: 34, height: 10)
                    }
                }
                .frame(width: 58, height: 58)
                .overlay(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .stroke(selected ? p.accent : .clear, lineWidth: 2.5)
                )
                Text(theme.title).font(.system(size: 11, weight: selected ? .semibold : .regular))
            }
        }
        .buttonStyle(.squish)
    }
}

// Редактор профиля: имя, @username, био, аватар.
struct ProfileEditorView: View {
    @EnvironmentObject var session: Session
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss

    @State private var displayName = ""
    @State private var username = ""
    @State private var bio = ""
    @State private var saving = false
    @State private var avatarItem: PhotosPickerItem?
    @State private var avatarBusy = false

    var body: some View {
        Form {
            Section {
                avatarEditor
            }
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets())
            Section("Имя") {
                TextField("Отображаемое имя", text: $displayName)
            }
            Section("Имя пользователя") {
                TextField("username", text: $username)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
            }
            Section("О себе") {
                TextField("Био", text: $bio, axis: .vertical).lineLimit(3...6)
            }
        }
        .scrollContentBackground(.hidden)
        .background(palette.background)
        .toolbar(.hidden, for: .navigationBar)
        .swipeBackEnabled()
        .safeAreaInset(edge: .top) {
            FloatingHeader(
                title: "Профиль",
                large: false,
                leading: AnyView(HeaderIconButton(icon: "chevron.left") { dismiss() }),
                trailing: AnyView(Button("Готово") { save() }.disabled(saving).foregroundStyle(palette.accent))
            )
        }
        .onAppear {
            displayName = session.myDisplayName
            username = session.myUsername
        }
        .onChange(of: avatarItem) { _, item in
            guard let item else { return }
            Task { await applyAvatar(item) }
        }
    }

    private var avatarEditor: some View {
        VStack(spacing: 10) {
            ZStack {
                Avatar(id: session.myId, name: session.myDisplayName, size: 96, avatarURL: session.myAvatarURL)
                if avatarBusy {
                    Circle().fill(.black.opacity(0.4)).frame(width: 96, height: 96)
                    ProgressView().tint(.white)
                }
            }
            HStack(spacing: 16) {
                PhotosPicker(selection: $avatarItem, matching: .images) {
                    Label("Сменить фото", systemImage: "camera.fill")
                }
                if session.myAvatarURL != nil {
                    Button(role: .destructive) {
                        Task { await removeAvatar() }
                    } label: { Label("Удалить фото", systemImage: "trash") }
                }
            }
            .font(.system(size: 14, weight: .medium))
            .disabled(avatarBusy)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 16)
    }

    private func applyAvatar(_ item: PhotosPickerItem) async {
        avatarBusy = true
        defer { avatarBusy = false; avatarItem = nil }
        guard let data = try? await item.loadTransferable(type: Data.self),
              let image = MediaStore.downsample(data: data, maxPixel: 512),
              let jpeg = image.jpegData(compressionQuality: 0.85) else { return }
        try? await session.setMyAvatar(data: jpeg, mime: "image/jpeg")
    }

    private func removeAvatar() async {
        avatarBusy = true
        defer { avatarBusy = false }
        try? await session.setMyAvatar(data: nil, mime: "image/jpeg")
    }

    private func save() {
        saving = true
        let d = displayName, u = username, b = bio
        Task {
            try? await session.core.updateProfile(
                username: u.isEmpty ? nil : u,
                displayName: d.isEmpty ? nil : d,
                avatarFileId: nil,
                bio: b.isEmpty ? nil : b
            )
            await session.loadMyProfile()
            saving = false
            dismiss()
        }
    }
}
