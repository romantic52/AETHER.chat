import SwiftUI

struct RootView: View {
    @EnvironmentObject var session: Session
    @Environment(\.palette) private var palette
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var lock = AppLock.shared

    var body: some View {
        #if DEBUG
        if ProcessInfo.processInfo.environment["AETHER_MEDIATEST"] == "1" {
            MediaTestHarness()
        } else if ProcessInfo.processInfo.environment["AETHER_SWIPETEST"] == "1" {
            SwipeTestHarness()
        } else if ProcessInfo.processInfo.environment["AETHER_BARNATIVE"] == "1", #available(iOS 18.0, *) {
            NativeBarReference()
        } else if ProcessInfo.processInfo.environment["AETHER_BARONLY"] == "1" {
            BarOnlyHarness()
        } else {
            main
        }
        #else
        main
        #endif
    }

    private var main: some View {
        ZStack {
            palette.background.ignoresSafeArea()

            // Домашний экран монтируется СРАЗУ и живёт дальше — так же, как жил
            // самодельный бар: он был частью иерархии с первого кадра. Системный
            // таб-бар внутри TabView прикрепляется при монтировании, и делать это
            // надо заранее, а не в момент показа — иначе экран уже виден, а бар
            // ещё едет. Фаза загрузки просто накрывает готовый дом сверху.
            if session.phase != .onboarding {
                HomeView()
                    // Пересоздание — только на смену аккаунта (см. accountGeneration).
                    .id(session.accountGeneration)
            }

            if session.phase == .loading {
                palette.background.ignoresSafeArea()
                    .overlay { ProgressView().tint(palette.accent) }
            }

            if session.phase == .onboarding {
                WelcomeView()
                    .transition(.opacity)
            }
        }
        // Анимируется только уход онбординга; дом и бар встают мгновенно.
        .animation(.easeInOut(duration: 0.3), value: session.phase == .onboarding)
        .task {
            session.setApplicationActive(scenePhase != .background)
            await session.bootstrap()
        }
        .onChange(of: scenePhase) { _, phase in
            session.setApplicationActive(phase != .background)
            if phase == .background { lock.appDidEnterBackground() }
        }
        // Экран блокировки поверх всего (и поверх контента в App Switcher).
        .overlay {
            if lock.locked && session.phase == .ready {
                LockView(lock: lock)
                    // Появление — мгновенно/тихо (фон, App Switcher), уход — экран
                    // «слетает» вверх вслед за открывшимся замком.
                    .transition(.asymmetric(insertion: .opacity,
                                            removal: .move(edge: .top).combined(with: .opacity)))
                    .zIndex(200)
            }
        }
    }
}

#if DEBUG
// ДИАГНОСТИЧЕСКИЙ СТЕНД. Повторяет конструкцию боевого запуска БЕЗ сессии и
// сервера: тот же switch по фазе с той же анимацией и .transition(.opacity),
// внутри — тот же TabView с пятью вкладками, включая роль .search. Нужен, чтобы
// проверить, появляется ли системный док, когда TabView монтируется внутри
// анимированного перехода. Запуск:
//   SIMCTL_CHILD_AETHER_DOCKTEST=1 xcrun simctl launch <udid> com.rmkhc.aether
// ВРЕМЕННЫЙ СТЕНД — УДАЛИТЬ. Рисует только док поверх фона темы, минуя сессию и
// экран входа: нужен, чтобы снять скриншот бара и сравнить с системным.
// Запуск: SIMCTL_CHILD_AETHER_BARONLY=1 xcrun simctl launch <udid> com.rmkhc.aether
// ВРЕМЕННЫЙ ЭТАЛОН — УДАЛИТЬ вместе со стендом. Системный TabView с теми же
// четырьмя вкладками: переключаем выбор программно и снимаем видео, чтобы
// получить эталонные тайминги и деформацию подложки. Тапать в симуляторе нечем,
// а Photos программно не переключишь — поэтому эталон строим сами.
@available(iOS 18.0, *)
private struct NativeBarReference: View {
    @State private var sel = 0
    private let titles = ["Контакты", "Звонки", "Чаты", "Настройки"]
    private let icons = ["person.crop.circle", "phone.fill",
                         "bubble.left.and.bubble.right.fill", "gearshape.fill"]

    var body: some View {
        // Пять вкладок, пятая — с ролью .search: система выносит её отдельным
        // кружком. Это и есть эталон, под который подгоняется наш бар.
        TabView(selection: $sel) {
            Tab(titles[0], systemImage: icons[0], value: 0) { Color.black.ignoresSafeArea() }
            Tab(titles[1], systemImage: icons[1], value: 1) { Color.black.ignoresSafeArea() }
            Tab(titles[2], systemImage: icons[2], value: 2) { Color.black.ignoresSafeArea() }
            Tab(titles[3], systemImage: icons[3], value: 3) { Color.black.ignoresSafeArea() }
            Tab(value: 4, role: .search) { Color.black.ignoresSafeArea() }
        }

        .task {
            guard ProcessInfo.processInfo.environment["AETHER_BARCYCLE"] == "1" else { return }
            var i = 0
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 700_000_000)
                i += 1
                sel = i % 4
            }
        }
    }
}

private struct BarOnlyHarness: View {
    @Environment(\.palette) private var palette
    @StateObject private var chrome = ChromeState()

    var body: some View {
        ZStack(alignment: .bottom) {
            palette.background.ignoresSafeArea()
            TabBar(tab: $chrome.tab, unread: 3)
        }
        // Как в HomeView — иначе бар встаёт над safe area и замеры отступа врут.
        .ignoresSafeArea(edges: .bottom)
        .environmentObject(chrome)
        // AETHER_BARCYCLE=1 — гоняет вкладки по кругу той же анимацией, что и тап.
        // Нужно, чтобы снять переход серией скриншотов и увидеть, сливаются ли
        // стёкла бара и подложки в движении: тапать в симуляторе нечем.
        .task {
            guard ProcessInfo.processInfo.environment["AETHER_BARCYCLE"] == "1" else { return }
            let order: [AppTab] = [.contacts, .calls, .chats, .settings]
            var i = 0
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 700_000_000)
                i += 1
                withAnimation(.snappy(duration: 0.25, extraBounce: 0.02)) {
                    chrome.tab = order[i % order.count]
                }
            }
        }
    }
}
#endif

#if DEBUG
/// Стенд свайпов: список строк с настоящим SwipeRow, но без сессии и сервера —
/// чтобы проверять жест в симуляторе, а не только на устройстве.
/// Запуск: SIMCTL_CHILD_AETHER_SWIPETEST=1 xcrun simctl launch <udid> com.rmkhc.aether
struct SwipeTestHarness: View {
    @Environment(\.palette) private var palette
    @State private var openRow: String?
    @State private var log = "жеста не было"

    var body: some View {
        VStack(spacing: 0) {
            Text(log)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(palette.textPrimary)
                .padding(8)

            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(0..<12, id: \.self) { i in
                        SwipeRow(
                            rowId: "row-\(i)",
                            openRow: $openRow,
                            leading: [
                                RowAction(title: "Закрепить", icon: "pin.fill", tint: .purple) { log = "закрепить \(i)" },
                                RowAction(title: "Прочитать", icon: "envelope.open.fill", tint: .gray) { log = "прочитать \(i)" }
                            ],
                            trailing: [
                                RowAction(title: "Удалить", icon: "trash.fill", tint: .red) { log = "удалить \(i)" },
                                RowAction(title: "Без звука", icon: "bell.slash.fill", tint: .orange) { log = "звук \(i)" },
                                RowAction(title: "В архив", icon: "archivebox.fill", tint: .teal) { log = "архив \(i)" }
                            ],
                            onTap: { log = "тап \(i)" },
                            // Первая строка сама протаскивается вправо, вторая
                            // влево — чтобы снять результат кадрами.
                            debugScript: i == 0 ? Array(stride(from: 10.0, through: 200.0, by: 10.0))
                                       : i == 1 ? Array(stride(from: -10.0, through: -240.0, by: -10.0))
                                       : nil
                        ) {
                            HStack(spacing: 12) {
                                Circle().fill(.blue).frame(width: 46, height: 46)
                                Text("Строка \(i)").foregroundStyle(palette.textPrimary)
                                Spacer()
                            }
                            .padding(.horizontal, 16)
                            .frame(height: 68)
                        }
                        Rectangle().fill(palette.divider).frame(height: 0.5)
                    }
                }
            }
        }
        .background(palette.background)
    }
}
#endif


#if DEBUG
/// Стенд просмотрщика медиа: галерея из нарисованных прямо здесь картинок, трек
/// и документ — без сессии и сервера. Медиа в симуляторе иначе недостижимо
/// (нужен вход в аккаунт), и всё ловилось бы уже на устройстве.
/// Запуск: SIMCTL_CHILD_AETHER_MEDIATEST=1 xcrun simctl launch <udid> com.rmkhc.aether
struct MediaTestHarness: View {
    @Environment(\.palette) private var palette
    @State private var ready = false
    @State private var open = false
    @State private var items: [MediaItem] = []
    @State private var track: Wire.Payload?

    var body: some View {
        ZStack {
            palette.background.ignoresSafeArea()
            VStack(spacing: 14) {
                MiniPlayerBar()
                Text(ready ? "готово" : "готовлю файлы…")
                    .font(.system(size: 13)).foregroundStyle(palette.textSecondary)
                Button("Открыть галерею") { open = true }
                    .disabled(!ready)
                    .buttonStyle(.borderedProminent)
                if let track {
                    AudioBubble(payload: track, outgoing: false)
                        .background(palette.surfaceElevated, in: RoundedRectangle(cornerRadius: 18))
                }
            }
            .padding(20)
        }
        .task { await prepare() }
        .fullScreenCover(isPresented: $open) {
            AetherMediaViewer(items: items, start: 0)
        }
    }

    private func prepare() async {
        var built: [MediaItem] = []
        for (i, color) in [UIColor.systemTeal, .systemPink, .systemIndigo].enumerated() {
            let id = "bench_image_\(i)"
            MediaStore.shared.seed(fileId: id, data: Self.image(color: color, caption: "Фото \(i + 1)"))
            built.append(MediaItem(Wire.Payload(type: "media", raw: [
                "type": "media", "kind": "image", "file_id": id,
                "file_name": "photo_\(i + 1).jpg", "mime_type": "image/jpeg",
            ])))
        }
        // Документ отдельной страницей: проверяем, что файл открывается
        // системным просмотром прямо внутри галереи, а не крутит спиннер.
        let docId = "bench_doc"
        MediaStore.shared.seed(fileId: docId, data: Data("AETHER: проверка документа.\n".utf8))
        built.append(MediaItem(Wire.Payload(type: "media", raw: [
            "type": "media", "kind": "file", "file_id": docId,
            "file_name": "заметка.txt", "mime_type": "text/plain",
        ])))
        items = built

        let audioId = "bench_audio"
        MediaStore.shared.seed(fileId: audioId, data: Self.tone(seconds: 12))
        track = Wire.Payload(type: "media", raw: [
            "type": "media", "kind": "audio", "file_id": audioId,
            "file_name": "Проверка звука.wav", "mime_type": "audio/wav",
        ])
        ready = true
    }

    /// Кадр с подписью — чтобы на снимке было видно, какая страница открыта.
    private static func image(color: UIColor, caption: String) -> Data {
        let size = CGSize(width: 900, height: 1400)
        let image = UIGraphicsImageRenderer(size: size).image { context in
            color.setFill()
            context.fill(CGRect(origin: .zero, size: size))
            let attrs: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: 120, weight: .bold),
                .foregroundColor: UIColor.white,
            ]
            let text = caption as NSString
            let bounds = text.size(withAttributes: attrs)
            text.draw(at: CGPoint(x: (size.width - bounds.width) / 2,
                                  y: (size.height - bounds.height) / 2), withAttributes: attrs)
        }
        return image.jpegData(compressionQuality: 0.9) ?? Data()
    }

    /// WAV с синусом: AVPlayer его читает, а генерировать проще, чем m4a.
    private static func tone(seconds: Int) -> Data {
        let rate = 22050
        let count = rate * seconds
        var samples = Data(capacity: count * 2)
        for i in 0..<count {
            let value = sin(Double(i) * 2 * Double.pi * 440 / Double(rate))
            let scaled = Int16(value * 8000)
            samples.append(UInt8(truncatingIfNeeded: scaled))
            samples.append(UInt8(truncatingIfNeeded: scaled >> 8))
        }
        func le32(_ v: Int) -> Data { Data([UInt8(v & 0xff), UInt8((v >> 8) & 0xff),
                                            UInt8((v >> 16) & 0xff), UInt8((v >> 24) & 0xff)]) }
        func le16(_ v: Int) -> Data { Data([UInt8(v & 0xff), UInt8((v >> 8) & 0xff)]) }
        var wav = Data("RIFF".utf8)
        wav += le32(36 + samples.count)
        wav += Data("WAVEfmt ".utf8)
        wav += le32(16) + le16(1) + le16(1) + le32(rate) + le32(rate * 2) + le16(2) + le16(16)
        wav += Data("data".utf8) + le32(samples.count) + samples
        return wav
    }
}
#endif
