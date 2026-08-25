import SwiftUI
import PhotosUI

struct SettingsView: View {
    @EnvironmentObject var session: Session
    @EnvironmentObject var appearance: AppearanceSettings
    /// Пуши ведём состоянием, а не NavigationLink: корень вкладки объявляет
    /// видимость дока и должен знать, открыт ли поверх него экран.
    @State private var showProfileEditor = false
    @State private var showPairScanner = false
    @State private var showSecurity = false
    @Environment(\.palette) private var palette

    // Без собственного NavigationStack — общий стек HomeView (см. ChatsListView).
    var body: some View {
        ZStack {
            palette.background.ignoresSafeArea()
            List {
                profileSection
                serversSection
                accountsSection
                privacySection
                securitySection
                notificationsSection
                appearanceSection
                glassSection
                bubbleSection
                navigationSection
                reactionSection
                storageSection
                experimentalSection
                accountSection
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .safeAreaPadding(.bottom, 100)
        }
        // Явно объявляем док видимым в корне вкладки. Без этого он не
        // прикрепляется при первом показе: соседнее сокрытие навбара
        // снимает видимость всей нижней панели, и возвращает её только
        // пуш с возвратом — отсюда «бар появляется после выхода из чата».
        // Строго ДО navigationDestination: у запушенного экрана своё
        // объявление (.hidden), и оно должно оставаться главнее.
        .sheet(isPresented: $showPairScanner) {
            PairScanView().environmentObject(session)
        }
        .toolbar(.hidden, for: .navigationBar)
        .navigationDestination(isPresented: $showProfileEditor) { ProfileEditorView() }
        .navigationDestination(isPresented: $showSecurity) { SecurityView() }
        .safeAreaInset(edge: .top) { FloatingHeader(title: "Настройки") }
    }

    private var profileSection: some View {
        Section {
            VStack(spacing: 8) {
                Avatar(id: session.myId, name: session.myDisplayName, size: 82, avatarURL: session.myAvatarURL)
                VStack(spacing: 3) {
                    HStack(spacing: 6) {
                        Text(session.myDisplayName.isEmpty ? session.myId : session.myDisplayName)
                            .font(.system(size: 20, weight: .semibold))
                            .foregroundStyle(palette.textPrimary)
                        // Эмодзи-статус: тап — выбор/смена.
                        Button { showStatusPicker = true } label: {
                            if session.myStatusEmoji.isEmpty {
                                Image(systemName: "face.smiling")
                                    .font(.system(size: 17))
                                    .foregroundStyle(palette.textSecondary.opacity(0.7))
                            } else {
                                Text(session.myStatusEmoji).font(.system(size: 19))
                            }
                        }
                        .buttonStyle(.plain)
                    }
                    Text(session.myUsername.isEmpty ? "@\(session.myId)" : "@\(session.myUsername)")
                        .font(.subheadline).foregroundStyle(palette.textSecondary)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            Button { showProfileEditor = true } label: {
                HStack {
                    SettingsLabel("Редактировать профиль", icon: "person.crop.circle.fill", color: .blue)
                    Spacer()
                    // NavigationLink рисовал шеврон сам; с Button добавляем вручную.
                    Image(systemName: "chevron.right")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(palette.textSecondary.opacity(0.6))
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .listRowBackground(palette.surface)
    }

    // MARK: - Эмодзи-статус

    @State private var showStatusPicker = false

    private var statusPickerSheet: some View {
        let emojis = ["😀","😎","🥳","😴","🤒","🏝","💻","📵","🎮","🎧","📚","🏋️","☕️","🍕","❤️","🔥",
                      "⭐️","🌙","⚡️","🎯","🚀","🧘","🐱","🐶","🌈","🍀","🎵","💤","🤫","👑","🫡","🥷"]
        return NavigationStack {
            ZStack {
                palette.background.ignoresSafeArea()
                VStack(spacing: 16) {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 8), spacing: 14) {
                        ForEach(emojis, id: \.self) { e in
                            Button {
                                Task { await session.setMyStatusEmoji(e) }
                                showStatusPicker = false
                                UISelectionFeedbackGenerator().selectionChanged()
                            } label: {
                                Text(e).font(.system(size: 30))
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 4)
                                    .background(session.myStatusEmoji == e
                                                ? palette.accent.opacity(0.22) : .clear,
                                                in: RoundedRectangle(cornerRadius: Radius.nested, style: .continuous))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 16)
                    if !session.myStatusEmoji.isEmpty {
                        Button(role: .destructive) {
                            Task { await session.setMyStatusEmoji("") }
                            showStatusPicker = false
                        } label: {
                            Text("Убрать статус").font(.subheadline.weight(.medium))
                        }
                    }
                    Spacer()
                }
                .padding(.top, 8)
            }
            .toolbar(.hidden, for: .navigationBar)
            .safeAreaInset(edge: .top) {
                FloatingHeader(title: "Статус", large: false,
                               trailing: AnyView(Button("Готово") { showStatusPicker = false }
                                   .foregroundStyle(palette.accent)))
                    .padding(.top, 10)
            }
        }
        .presentationDetents([.medium])
    }

    // MARK: - Аккаунты (мультиаккаунт, до 5)

    @State private var showAddAccount = false
    @State private var wallpaperItem: PhotosPickerItem?
    @ObservedObject private var wallpaperStore = WallpaperStore.shared

    private var serversSection: some View {
        Section {
            NavigationLink {
                ServersListView().environmentObject(session)
            } label: {
                HStack {
                    SettingsLabel("Серверы", icon: "server.rack", color: .indigo)
                    Spacer()
                    Text(session.activeServer?.displayName ?? "")
                        .font(.system(size: 15))
                        .foregroundStyle(palette.textSecondary)
                }
            }
            .listRowBackground(palette.surface)
        } footer: {
            Text("Официальная инфраструктура Aether и ваши собственные серверы. Каждый сервер независим: своя учётная запись, своя переписка, свои ключи.")
        }
    }

    private var accountsSection: some View {
        Section {
            ForEach(session.accounts, id: \.self) { id in
                Button {
                    guard id != session.myId.lowercased() else { return }
                    Task { await session.switchAccount(to: id) }
                } label: {
                    HStack(spacing: 12) {
                        Avatar(id: id, name: id, size: 34,
                               avatarURL: id == session.myId.lowercased() ? session.myAvatarURL : nil)
                        Text("@\(id)")
                            .foregroundStyle(palette.textPrimary)
                        Spacer()
                        if id == session.myId.lowercased() {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundStyle(palette.accent)
                        }
                    }
                }
                .listRowBackground(palette.surface)
            }
            if session.accounts.count < Session.maxAccounts {
                Button { showAddAccount = true } label: {
                    SettingsLabel("Добавить аккаунт", icon: "plus", color: .blue)
                }
                .listRowBackground(palette.surface)
            }
        } header: {
            Text("Аккаунты на \(session.activeServer?.displayName ?? "сервере")")
        } footer: {
            Text("До \(Session.maxAccounts) аккаунтов на одном сервере. Переписка и настройки каждого хранятся отдельно. Аккаунты других серверов — в переключателе пространства.")
        }
        .sheet(isPresented: $showStatusPicker) { statusPickerSheet }
        .sheet(isPresented: $showAddAccount) {
            // Тот же экран входа/регистрации; успешный вход добавляет аккаунт
            // и сразу делает его активным (шторка закрывается по смене myId).
            WelcomeView()
                .environmentObject(session)
                .onChange(of: session.myId) { _, _ in showAddAccount = false }
        }
    }

    // MARK: - Приватность (Face ID / PIN)

    @StateObject private var lock = AppLock.shared
    @State private var showPinSetup = false
    @State private var lockEnabled = UserDefaults.standard.bool(forKey: AppLock.enabledKey)

    private var privacySection: some View {
        Section {
            Toggle(isOn: Binding(
                get: { lockEnabled },
                set: { on in
                    if on {
                        showPinSetup = true   // включаем только после задания PIN
                    } else {
                        lock.disable()
                        lockEnabled = false
                    }
                }
            )) {
                SettingsLabel(lock.biometryType == .faceID ? "Face ID и PIN" : "Блокировка (PIN)",
                              icon: lock.biometryType == .faceID ? "faceid" : "lock.fill",
                              color: .green)
            }
        } header: {
            Text("Приватность")
        } footer: {
            Text("Приложение блокируется при сворачивании: вход по \(lock.biometryType == .faceID ? "Face ID" : "биометрии") или PIN. Контент скрыт в переключателе приложений.")
        }
        .listRowBackground(palette.surface)
        .sheet(isPresented: $showPinSetup) {
            PinSetupView { pin in
                lock.enable(pin: pin)
                lockEnabled = true
            }
        }
    }

    private var securitySection: some View {
        Section {
            Button { showSecurity = true } label: {
                HStack {
                    SettingsLabel("Сессии и безопасность", icon: "checkmark.shield.fill", color: .blue)
                    Spacer()
                    // NavigationLink рисовал шеврон сам; с Button добавляем вручную.
                    Image(systemName: "chevron.right")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(palette.textSecondary.opacity(0.6))
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        } footer: {
            Text("Активные устройства, двухфакторная аутентификация, удаление всех данных.")
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
            Toggle(isOn: Binding(
                get: { UserDefaults.standard.bool(forKey: Messaging.autoDownloadKey) },
                set: { UserDefaults.standard.set($0, forKey: Messaging.autoDownloadKey) }
            )) {
                SettingsLabel("Автозагрузка фото и видео", icon: "arrow.down.circle.fill", color: .blue)
            }
            Button(role: .destructive) { confirmClearCache = true } label: {
                SettingsLabel("Очистить кеш", icon: "trash.fill", color: .red)
            }
            .disabled(cacheBytes <= 0)
        } header: {
            Text("Данные и память")
        } footer: {
            Text("Голосовые и кружки скачиваются на устройство всегда (доступны офлайн, до 200 последних сообщений чата, поэтапно). Фото и видео — вручную по тапу или автоматически этим тумблером (качаются после голосовых). Очистка кеша не удаляет сообщения — медиа докачается заново.")
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
                    Slider(value: $appearance.edgeDimStrength, in: 0.05...0.8)
                }
            }

            // Обои чата: своё фото фоном во всех чатах.
            PhotosPicker(selection: $wallpaperItem, matching: .images) {
                HStack {
                    SettingsLabel("Обои чата", icon: "photo.fill", color: .mint)
                    Spacer()
                    if let img = wallpaperStore.image {
                        Image(uiImage: img)
                            .resizable().scaledToFill()
                            .frame(width: 34, height: 34)
                            // Радиус пропорционален миниатюре (34×34), не карточке:
                            // общий Radius.card превратил бы её в кляксу.
                            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                    } else {
                        Text("Стандартные")
                            .font(.caption).foregroundStyle(palette.textSecondary)
                    }
                }
            }
            .onChange(of: wallpaperItem) { _, item in
                guard let item else { return }
                Task {
                    defer { wallpaperItem = nil }
                    if let data = try? await item.loadTransferable(type: Data.self) {
                        wallpaperStore.set(data)
                    }
                }
            }
            if wallpaperStore.image != nil {
                Button(role: .destructive) { wallpaperStore.clear() } label: {
                    SettingsLabel("Убрать обои", icon: "photo", color: .gray)
                }
            }
        }
        .listRowBackground(palette.surface)
    }

    // Образец стекла с текущими настройками. Подложка пёстрая намеренно: поверх
    // однотонного фона ни блюр, ни оттенок не читаются, и кажется, что ползунки
    // мертвы. Круг и капсула, а не ряд вкладок — чтобы образец снова не приняли
    // за второй бар.
    private var glassPreview: some View {
        ZStack {
            LinearGradient(colors: [.purple, .blue, .teal, .orange],
                           startPoint: .topLeading, endPoint: .bottomTrailing)
                .frame(height: 96)
                .clipShape(RoundedRectangle(cornerRadius: Radius.panel, style: .continuous))

            HStack(spacing: 12) {
                Image(systemName: "sparkles")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(palette.textPrimary)
                    .frame(width: 44, height: 44)
                    .liquidGlass(Circle(), interactive: true)

                Text("Образец")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(palette.textPrimary)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 12)
                    .liquidGlass(Capsule(), interactive: true)
            }
        }
        .listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 16))
        .listRowBackground(Color.clear)
    }

    /// Экспериментальное: тайминги свайпа по строке списка чатов. Вынесены в
    /// интерфейс намеренно — ощущение подбирается на устройстве, вслепую по
    /// описанию его не поймать.
    private var experimentalSection: some View {
        Section {
            Toggle(isOn: $appearance.swipeVelocityEnabled) {
                SettingsLabel("Подхват скорости пальца", icon: "hand.draw.fill", color: .teal)
            }
            tuner("Открытие · длительность, с", value: $appearance.swipeOpenDuration, range: 0.1...0.6, step: 0.02)
            tuner("Открытие · раскачка", value: $appearance.swipeOpenBounce, range: 0...0.5, step: 0.05)
            tuner("Возврат · длительность, с", value: $appearance.swipeCloseDuration, range: 0.08...0.5, step: 0.02)
            tuner("Возврат · раскачка", value: $appearance.swipeCloseBounce, range: 0...0.4, step: 0.05)
            tuner("Растягивание до срабатывания", value: $appearance.swipeStretch, range: 40...220, step: 5)
            tuner("Сопротивление за пределом", value: $appearance.swipeResistance, range: 0.2...1, step: 0.05)

            Button("Вернуть исходные") {
                appearance.swipeOpenDuration = 0.3
                appearance.swipeOpenBounce = 0.15
                appearance.swipeCloseDuration = 0.18
                appearance.swipeCloseBounce = 0
                appearance.swipeStretch = 100
                appearance.swipeResistance = 0.75
                appearance.swipeVelocityEnabled = true
            }
            .foregroundStyle(palette.accent)
        } header: {
            Text("Экспериментальное")
        } footer: {
            Text("Свайп по чату в списке. Значения по умолчанию сняты с Telegram покадрово: открытие ~0,3 с, возврат ~0,18 с. Изменения применяются сразу.")
        }
        .listRowBackground(palette.surface)
    }

    /// Ползунок с текущим значением справа — иначе подбирать вслепую.
    private func tuner(_ title: LocalizedStringKey, value: Binding<Double>,
                       range: ClosedRange<Double>, step: Double) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(title)
                    .font(.system(size: 15))
                    .foregroundStyle(palette.textPrimary)
                Spacer()
                Text(step < 1 ? String(format: "%.2f", value.wrappedValue)
                              : String(format: "%.0f", value.wrappedValue))
                    .font(.system(size: 14, weight: .medium).monospacedDigit())
                    .foregroundStyle(palette.textSecondary)
            }
            Slider(value: value, in: range, step: step).tint(palette.accent)
        }
        .padding(.vertical, 2)
    }

    private var glassSection: some View {
        Section {
            Toggle(isOn: $appearance.glassEnabled) {
                SettingsLabel("Жидкое стекло", icon: "sparkles", color: .indigo)
            }
            glassPreview
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
            Text("Действует на элементы приложения: шапки, кнопки, строку поиска, вложения — образец выше показывает результат. Док внизу рисует сама система, его стекло приложению не подчиняется.")
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
        Section {
            Toggle(isOn: $appearance.switchTabOnRelease) {
                SettingsLabel("Переключать при отпускании", icon: "hand.tap.fill", color: .green)
            }
            Toggle(isOn: $appearance.tabFadeEnabled.animation()) {
                SettingsLabel("Анимация смены вкладок", icon: "sparkles", color: .purple)
            }
            if appearance.tabFadeEnabled {
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        SettingsLabel("Длительность", icon: "timer", color: .orange)
                        Spacer()
                        Text(String(format: "%.2f с", appearance.tabFadeDuration))
                            .font(.system(size: 15, design: .monospaced))
                            .foregroundStyle(palette.textSecondary)
                    }
                    Slider(value: $appearance.tabFadeDuration, in: 0.05...0.5, step: 0.05)
                        .tint(palette.accent)
                }
            }
        } header: {
            Text("Навигация")
        } footer: {
            Text("Анимация смены вкладок — плавное растворение контента при переключении. Выключи, если нужен мгновенный отклик.")
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
            // Переключение между сохранёнными аккаунтами. Мультиаккаунт в ядре
            // был давно (Session.accounts / switchAccount), но в интерфейс
            // выведен не был — сменить личность было нечем.
            ForEach(session.accounts, id: \.self) { account in
                Button {
                    guard account != session.myId.lowercased() else { return }
                    Task { await session.switchAccount(to: account) }
                } label: {
                    HStack(spacing: 12) {
                        Avatar(id: account, name: account, size: 32)
                        Text("@\(account)")
                            .font(.system(size: 16))
                            .foregroundStyle(palette.textPrimary)
                        Spacer()
                        if account == session.myId.lowercased() {
                            Image(systemName: "checkmark")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundStyle(palette.accent)
                        }
                    }
                }
            }

            Button { showPairScanner = true } label: {
                SettingsLabel("Привязать устройство", icon: "qrcode.viewfinder", color: .teal)
            }

            Button(role: .destructive) {
                Task { await session.logout() }
            } label: {
                SettingsLabel("Выйти", icon: "arrow.right.square.fill", color: .red)
            }
        } header: {
            Text(session.accounts.count > 1 ? "Аккаунты" : "Аккаунт")
        } footer: {
            if let no = session.myAccountNo {
                // Номер можно скопировать: логин пользователь вправе сменить,
                // а по номеру его всегда опознают — например, в поддержке.
                Button {
                    UIPasteboard.general.string = String(no)
                } label: {
                    Text("ID \(String(no)) · нажмите, чтобы скопировать")
                }
                .buttonStyle(.plain)
                .foregroundStyle(palette.textSecondary)
            }
        }
        .listRowBackground(palette.surface)
    }
}

struct SettingsLabel: View {
    let title: LocalizedStringKey
    let icon: String
    let color: Color

    init(_ title: LocalizedStringKey, icon: String, color: Color) {
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
                // Иконочная плитка 28×28: пропорция 0.25 стороны, как у системных
                // squircle-иконок. Вне общей шкалы намеренно.
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
                    RoundedRectangle(cornerRadius: Radius.card, style: .continuous).fill(p.background)
                    VStack(spacing: 4) {
                        Capsule().fill(p.bubbleIn).frame(width: 34, height: 10)
                        Capsule().fill(p.accent).frame(width: 34, height: 10)
                    }
                }
                .frame(width: 58, height: 58)
                .overlay(
                    RoundedRectangle(cornerRadius: Radius.card, style: .continuous)
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
