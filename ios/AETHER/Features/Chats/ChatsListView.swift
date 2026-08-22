import SwiftUI

struct ChatsListView: View {
    /// true — экран открыт из вкладки поиска (кружок справа от дока): шапка
    /// «Чаты» не нужна, поле поиска сразу активно.
    private let searchMode: Bool

    init(searchMode: Bool = false) {
        self.searchMode = searchMode
    }

    @EnvironmentObject var session: Session
    @EnvironmentObject var messaging: Messaging
    @EnvironmentObject var chrome: ChromeState
    @Environment(\.palette) private var palette

    @State private var openedPeer: String?
    @State private var query = ""
    @State private var globalResults = GlobalSearch.Results()
    @State private var joiningIds: Set<String> = []
    @State private var globalSearching = false
    @State private var globalTask: Task<Void, Never>?
    @State private var globalSearchGeneration = UUID()
    @FocusState private var searchFocused: Bool
    /// Вкладка поиска остаётся живой после ухода с экрана, поэтому отложенный
    /// фокус обязан проверять, что она всё ещё видна: иначе клавиатура всплывает
    /// над списком чатов и прячет системный док.
    @State private var onScreen = false
    @State private var newChatPresented = false
    @State private var newGroupPresented = false
    @State private var newChannelPresented = false
    @State private var showComposeMenu = false
    @State private var showArchive = false
    @State private var editMode: EditMode = .inactive
    /// Какая строка сейчас раскрыта свайпом — открытие одной закрывает соседку.
    @State private var openRow: String?
    /// Строка, которая сейчас переезжает при закреплении. LazyVStack рисует
    /// соседей в порядке объявления, и без явного zIndex переезжающая строка
    /// уходит ПОД них: видно чужой текст поверх её аватарки и пустоты по краям.
    @State private var movingId: String?
    @StateObject private var folders = ChatFoldersStore.shared
    @StateObject private var blocks = BlockStore.shared
    @State private var folder: ChatFolder?
    /// Сортировка списка «Все». У папок она своя и живёт в самой папке;
    /// «Все» — не папка, поэтому её правило хранится отдельно.
    @AppStorage("chatsSortAll") private var sortAll: FolderSort = .manual
    @State private var editingFolder: ChatFolder?
    @State private var showFolderEditor = false
    @State private var showFolderOrder = false
    /// Ручной порядок чатов из режима правки. Пустой — сортируем по времени.
    @State private var manualOrder: [String] =
        UserDefaults.standard.stringArray(forKey: "chatManualOrder") ?? []
    @State private var draggingId: String?
    @State private var dragDY: CGFloat = 0

    private var archived: [Chat] { messaging.chats.filter { $0.archived } }
    private var visible: [Chat] {
        let base = messaging.chats.filter { !$0.archived }
        guard !query.isEmpty else { return base }
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        let raw = q.hasPrefix("@") ? String(q.dropFirst()) : q
        guard !raw.isEmpty else { return base }
        return base.filter { chat in
            chat.title.lowercased().contains(raw) ||
            chat.peerId.lowercased().contains(raw) ||
            chat.lastText.lowercased().contains(raw) ||
            (messaging.profiles[chat.peerId]?.username?.lowercased().contains(raw) ?? false) ||
            (messaging.profiles[chat.peerId]?.displayName?.lowercased().contains(raw) ?? false) ||
            (messaging.groups.info(chat.peerId)?.name.lowercased().contains(raw) ?? false)
        }
    }
    /// Закреплён ли чат в ТЕКУЩЕЙ папке. В «Все» закреп общий и живёт в базе
    /// ядра; внутри папки — свой список, потому что смысл разный: чат бывает
    /// нужен первым в рабочей папке и при этом лежать по времени в общем списке.
    private func isPinned(_ chat: Chat) -> Bool {
        guard let folder else { return chat.pinned }
        return folders.isPinned(chat.peerId, in: folder)
    }

    /// Порядок закрепления: каждый новый выше предыдущих.
    private func pinRank(_ chat: Chat) -> Int {
        guard let folder else { return messaging.pinRank(chat.peerId) }
        return folders.pinRank(chat.peerId, in: folder)
    }

    private func togglePin(_ chat: Chat) {
        guard let folder else {
            Task { await messaging.setPinned(chat.peerId, !chat.pinned) }
            return
        }
        withAnimation(.spring(response: 0.28, dampingFraction: 0.9)) {
            folders.togglePin(chat.peerId, in: folder)
        }
    }
    // Сортируем ЗДЕСЬ, а не полагаемся на порядок из базы. Ядро отдаёт чаты
    // запросом «ORDER BY pinned DESC, last_ts DESC», поэтому сразу после
    // открепления строка ещё лежит близко к началу массива и показывалась первой
    // среди незакреплённых, а на своё место по времени уезжала только после
    // следующего перечитывания из базы — отсюда переезд в два шага.
    /// Чаты текущей папки. Папка «Все» — nil, тогда фильтр не применяется.
    private func inFolder(_ chats: [Chat]) -> [Chat] {
        guard let folder else { return chats }
        return chats.filter { folders.matches($0, folder: folder, isGroup: messaging.isGroup($0.peerId)) }
    }

    /// Правило сортировки для того, что открыто сейчас.
    private var currentSort: FolderSort { folder?.sort ?? sortAll }

    private func regularOrder(_ base: [Chat]) -> [Chat] {
        switch currentSort {
        case .recent:
            return base.sorted { $0.lastTs > $1.lastTs }
        case .unread:
            // Внутри каждой половины — по времени: иначе «непрочитанные сверху»
            // перемешивает свежее со старым и читается как случайный порядок.
            return base.sorted {
                let a = $0.unread > 0, b = $1.unread > 0
                return a == b ? $0.lastTs > $1.lastTs : a
            }
        case .alphabet:
            return base.sorted {
                let a = ($0.title.isEmpty ? $0.peerId : $0.title)
                let b = ($1.title.isEmpty ? $1.peerId : $1.title)
                return a.localizedCaseInsensitiveCompare(b) == .orderedAscending
            }
        case .manual:
            guard !manualOrder.isEmpty else { return base.sorted { $0.lastTs > $1.lastTs } }
            // Расставленные вручную идут в своём порядке, новые чаты — по времени
            // следом за ними.
            var rank: [String: Int] = [:]
            for (i, id) in manualOrder.enumerated() { rank[id] = i }
            return base.sorted {
                let a = rank[$0.peerId] ?? Int.max
                let b = rank[$1.peerId] ?? Int.max
                return a == b ? $0.lastTs > $1.lastTs : a < b
            }
        }
    }

    private var rowHeight: CGFloat { AetherUI.listRowHeight + 0.5 }

    /// Живая перестановка: пока палец едет, строка меняется местами с соседями,
    /// а смещение уменьшается на пройденный шаг — палец остаётся на строке.
    private func reorder(_ id: String) {
        let ids = ordered.map(\.peerId)
        guard let from = ids.firstIndex(of: id) else { return }
        let shift = Int((dragDY / rowHeight).rounded())
        guard shift != 0 else { return }
        let to = min(max(from + shift, 0), ids.count - 1)
        guard to != from else { return }
        var order = ids
        let item = order.remove(at: from)
        order.insert(item, at: to)
        withAnimation(.spring(response: 0.28, dampingFraction: 0.9)) { manualOrder = order }
        dragDY -= CGFloat(to - from) * rowHeight
        UISelectionFeedbackGenerator().selectionChanged()
    }
    // Порядок целиком определяется вью: закрепы — по порядку закрепления,
    // остальные — по времени последнего сообщения. Считаем УЖЕ внутри папки:
    // закреп в папке свой, и делить список до фильтра было бы неверно.
    private var ordered: [Chat] {
        let list = inFolder(visible)
        let pins = list.filter { isPinned($0) }.sorted { pinRank($0) < pinRank($1) }
        return pins + regularOrder(list.filter { !isPinned($0) })
    }

    @ViewBuilder
    private var searchSections: some View {
        if !visible.isEmpty {
            ForEach(ordered, id: \.peerId) { chat in
                chatRow(chat)
                separator
            }
        }

        if globalSearching {
            HStack { Spacer(); ProgressView().tint(palette.accent); Spacer() }
                .padding(.vertical, 12)
        }

        let filteredGlobalUsers = globalResults.users.filter { u in
            u.userId.lowercased() != session.myId.lowercased() &&
            !messaging.chats.contains(where: { $0.peerId == u.userId.lowercased() })
        }
        let filteredGlobalGroups = globalResults.groups.filter { g in
            !messaging.chats.contains(where: { $0.peerId == g.groupId.lowercased() })
        }

        if !filteredGlobalUsers.isEmpty || !filteredGlobalGroups.isEmpty {
            Text("Найдено в сети")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(palette.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.top, 10)
                .padding(.bottom, 2)

            ForEach(filteredGlobalUsers, id: \.userId) { profile in
                Button { openedPeer = profile.userId.lowercased() } label: {
                    globalUserRow(profile).padding(.horizontal, 16)
                }
                .buttonStyle(.plain)
                separator
            }

            ForEach(filteredGlobalGroups, id: \.groupId) { group in
                // Участник — открываем. Публичный канал — подписка в один тап
                // (сервер выдаст ключ). Приватное — только по приглашению.
                let gid = group.groupId.lowercased()
                let member = messaging.groups.info(gid) != nil
                let joinable = !member && group.publicJoin
                Button {
                    if member {
                        openedPeer = gid
                    } else if joinable {
                        Task {
                            guard !joiningIds.contains(gid) else { return }
                            joiningIds.insert(gid)
                            defer { joiningIds.remove(gid) }
                            if await ChannelDirectory.join(gid) {
                                await messaging.groups.load()
                                openedPeer = gid
                            }
                        }
                    }
                } label: {
                    globalGroupRow(group, member: member, joinable: joinable,
                                   joining: joiningIds.contains(gid))
                    .padding(.horizontal, 16)
                }
                .buttonStyle(.plain)
                .disabled(!member && !joinable)
                separator
            }
        }

        if !globalSearching && visible.isEmpty && filteredGlobalUsers.isEmpty && filteredGlobalGroups.isEmpty {
            VStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 36, weight: .thin))
                    .foregroundStyle(palette.textSecondary)
                Text("Ничего не найдено")
                    .font(.subheadline)
                    .foregroundStyle(palette.textSecondary)
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 40)
        }
    }

    // Без собственного NavigationStack: все вкладки живут в ЕДИНОМ стеке
    // HomeView — пуши (чат, архив) накрывают и контент, и общий таб-бар.
    var body: some View {
        Group {
            ZStack {
                palette.background.ignoresSafeArea()

                // ScrollView + LazyVStack вместо List. List переставляет строку
                // удалением и вставкой: при закреплении она гасла на старом месте
                // и проявлялась на новом. LazyVStack анимирует положение строки
                // напрямую — получается настоящее скольжение. Плата — свайпы
                // пришлось написать самим (SwipeRow), их нет вне List.
                ScrollView {
                    LazyVStack(spacing: 0) {
                        if query.isEmpty {
                            if !archived.isEmpty {
                                archiveRow
                                separator
                            }
                            ForEach(ordered, id: \.peerId) { chat in
                                chatRow(chat)
                                    .offset(y: draggingId == chat.peerId ? dragDY : 0)
                                    .zIndex(draggingId == chat.peerId ? 3 : 0)
                                    .gesture(editMode == .active ? reorderGesture(chat.peerId) : nil)
                                    // Зажатие — предпросмотр переписки и меню
                                    // действий. Чат при этом НЕ открывается и
                                    // прочитанным не становится.
                                    .contextMenu {
                                        chatMenu(chat)
                                    } preview: {
                                        ChatPeek(peerId: chat.peerId,
                                                 title: chat.title.isEmpty ? chat.peerId : chat.title,
                                                 myId: session.myId,
                                                 core: session.core)
                                    }
                                    .zIndex(chat.peerId == movingId ? 1 : 0)
                                    // Переезжающая строка летит скруглённой
                                    // карточкой, а не прямоугольным блоком.
                                    .clipShape(RoundedRectangle(
                                        cornerRadius: chat.peerId == movingId ? 22 : 0,
                                        style: .continuous))
                                    .shadow(color: .black.opacity(chat.peerId == movingId ? 0.35 : 0),
                                            radius: 14, y: 4)
                                    .animation(.easeInOut(duration: 0.22), value: movingId)
                                separator
                            }
                        } else {
                            searchSections
                        }
                        Color.clear.frame(height: 112)
                    }
                }
                .scrollDismissesKeyboard(.interactively)
                .overlay { if messaging.chats.isEmpty && query.isEmpty { emptyState } }
                .safeAreaInset(edge: .top) {
                    VStack(spacing: 0) {
                        if searchMode {
                            customSearchBar
                        } else {
                            customHeader
                            folderRow
                        }
                    }
                    // Свайп по шапке — соседняя папка. По самому списку так
                    // сделать нельзя: горизонтальное движение там уже занято
                    // действиями строки (архив, беззвучно, удалить), и два
                    // разных смысла на одном жесте путали бы сильнее, чем
                    // помогали. simultaneousGesture — чтобы полоса папок
                    // по-прежнему прокручивалась, когда чипы не помещаются.
                    .simultaneousGesture(folderSwipe)
                    .background(
                        // Только градиент, как на всех остальных экранах:
                        // сплошная заливка превращала шапку в панель и ломала
                        // общий вид с плавающими шапками.
                        EdgeDim(edge: .top)
                            .ignoresSafeArea(edges: .top)
                    )
                }
            }
            .toolbar(.hidden, for: .navigationBar)
            .ignoresSafeArea(.keyboard, edges: .bottom)
            .onChange(of: query) { _, q in scheduleGlobalSearch(q) }
            // Вкладки живут одновременно, поэтому onAppear здесь срабатывает
            // ОДИН раз при запуске приложения и для фокуса не годится: клавиатура
            // всплыла бы над главным экраном. Ориентир — активная вкладка.
            .onChange(of: chrome.tab) { _, tab in
                guard searchMode else { return }
                if tab == .search {
                    onScreen = true
                    // Задержка — чтобы поле успело проявиться; onScreen страхует
                    // от фокуса, если с вкладки уже ушли.
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) {
                        if onScreen { searchFocused = true }
                    }
                } else {
                    onScreen = false
                    searchFocused = false
                }
            }
            .navigationDestination(item: $openedPeer) { peer in
                ChatView(peerId: peer, isGroup: messaging.isGroup(peer))
                    .environmentObject(messaging)
            }
            .navigationDestination(isPresented: $showArchive) {
                ArchiveView()
                    .environmentObject(session)
                    .environmentObject(messaging)
            }
            .sheet(isPresented: $showFolderOrder) {
                FolderOrderView(store: folders)
            }
            .sheet(isPresented: $showFolderEditor) {
                FolderEditor(store: folders, existing: editingFolder,
                             allChats: visible,
                             titleFor: { $0.title.isEmpty ? $0.peerId : $0.title })
                    .environmentObject(session)
                    .environmentObject(messaging)
            }
            .sheet(isPresented: $newChatPresented) {
                ContactsView(onPick: { peer in openedPeer = peer })
                    .environmentObject(session)
                    .environmentObject(messaging)
                    .environmentObject(chrome)
            }
            .sheet(isPresented: $newGroupPresented) {
                GroupCreateView(isChannel: false, skipTypeSelection: true, onCreated: { id in openedPeer = id })
                    .environmentObject(session)
                    .environmentObject(messaging)
            }
            .sheet(isPresented: $newChannelPresented) {
                GroupCreateView(isChannel: true, skipTypeSelection: true, onCreated: { id in openedPeer = id })
                    .environmentObject(session)
                    .environmentObject(messaging)
            }
            .onReceive(NotificationCenter.default.publisher(for: NotificationsManager.openChatNotification)) { note in
                guard let peer = (note.userInfo?["peer"] as? String)?.lowercased(), !peer.isEmpty else { return }
                openedPeer = peer
            }
            #if DEBUG
            .onChange(of: messaging.chats.count) { _, _ in tryAutoOpen() }
            .task {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                tryAutoOpen()
            }
            #endif
        }
    }

    /// Заголовок «Чаты» с кнопками — как в эталоне. Полоса папок идёт
    /// ОТДЕЛЬНОЙ строкой под ним, а не вместо него: я её было туда затолкал,
    /// и заголовок пропал совсем.
    private var customHeader: some View {
        ZStack {
            Text("Чаты")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(palette.textPrimary)

            HStack {
                if editMode == .active {
                    Button("Готово") {
                        withAnimation(.easeInOut(duration: 0.18)) { editMode = .inactive }
                    }
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(palette.textPrimary)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 12)
                    .liquidGlass(Capsule())
                } else {
                    Menu {
                        // Перестановка руками имеет смысл только при правиле
                        // «как расставлено»: при любом другом список тут же
                        // пересортируется и труд пропадёт.
                        if currentSort == .manual {
                            Button {
                                withAnimation(.easeInOut(duration: 0.18)) { editMode = .active }
                            } label: { Label("Переставить чаты", systemImage: "arrow.up.arrow.down") }
                        }
                        Button {
                            editingFolder = nil
                            showFolderEditor = true
                        } label: { Label("Новая папка", systemImage: "folder.badge.plus") }
                        if !folders.folders.isEmpty {
                            Button { showFolderOrder = true } label: {
                                Label("Порядок папок", systemImage: "list.bullet.indent")
                            }
                        }
                        // Правило сортировки: у папки — в её настройках, потому
                        // что оно часть папки; здесь — только для списка «Все».
                        if folder == nil {
                            Menu {
                                Picker("Сортировка", selection: $sortAll) {
                                    ForEach(FolderSort.allCases, id: \.self) {
                                        Label($0.title, systemImage: $0.icon).tag($0)
                                    }
                                }
                            } label: {
                                Label("Сортировка", systemImage: "arrow.up.arrow.down.square")
                            }
                        } else {
                            Button {
                                editingFolder = folder
                                showFolderEditor = true
                            } label: { Label("Настроить папку", systemImage: "slider.horizontal.3") }
                        }
                    } label: {
                        Text("Изм.")
                            .font(.system(size: 16, weight: .medium))
                            .foregroundStyle(palette.textPrimary)
                            .padding(.horizontal, 18)
                            .padding(.vertical, 12)
                            .liquidGlass(Capsule())
                    }
                }

                Spacer()

                Button { showComposeMenu = true } label: {
                    Image(systemName: "square.and.pencil")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(palette.textPrimary)
                        .frame(width: 44, height: 44)
                        .liquidGlass(Circle())
                }
                .accessibilityLabel("Новый чат")
                .confirmationDialog("Создать", isPresented: $showComposeMenu, titleVisibility: .hidden) {
                    Button("Новый чат") { newChatPresented = true }
                    Button("Новый канал") { newChannelPresented = true }
                    Button("Новая группа") { newGroupPresented = true }
                    Button("Отмена", role: .cancel) {}
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 10)
    }

    /// Порядок папок в переключении: «Все» первая, дальше пользовательские —
    /// ровно как на полосе, иначе свайп уводил бы не туда, куда показывает глаз.
    private var folderCarousel: [ChatFolder?] { [nil] + folders.folders.map { Optional($0) } }

    private var folderSwipe: some Gesture {
        DragGesture(minimumDistance: 24)
            .onEnded { value in
                // Диагональные движения не считаем: по вертикали здесь скроллят.
                guard abs(value.translation.width) > abs(value.translation.height) * 1.5 else { return }
                switchFolder(by: value.translation.width < 0 ? 1 : -1)
            }
    }

    private func switchFolder(by step: Int) {
        let list = folderCarousel
        guard list.count > 1 else { return }
        let current = list.firstIndex { $0?.id == folder?.id } ?? 0
        let next = current + step
        guard next >= 0, next < list.count else { return }
        withAnimation(.easeInOut(duration: 0.2)) { folder = list[next] }
        UIImpactFeedbackGenerator(style: .soft).impactOccurred()
    }

    /// Полоса папок — второй строкой под заголовком.
    private var folderRow: some View {
        FolderBar(store: folders, selected: $folder,
                  counts: { f in
                      // Непрочитанные, а не число чатов: на чипе полезно видеть,
                      // где ждут ответа, а не сколько всего сложено в папку.
                      visible.filter {
                          folders.matches($0, folder: f, isGroup: messaging.isGroup($0.peerId))
                      }.reduce(0) { $0 + Int($1.unread) }
                  },
                  onEdit: { f in
                      editingFolder = f
                      showFolderEditor = true
                  },
                  onOrder: { showFolderOrder = true })
            .padding(.bottom, 10)
    }

    private var customSearchBar: some View {
        HStack(spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(palette.textSecondary)
                    .font(.system(size: 16))
                TextField("Поиск", text: $query)
                    .foregroundStyle(palette.textPrimary)
                    .tint(palette.accent)
                    .focused($searchFocused)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.search)
                if !query.isEmpty {
                    Button { query = "" } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(palette.textSecondary)
                    }
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .liquidGlass(Capsule())

            if searchFocused || !query.isEmpty {
                Button("Отмена") {
                    query = ""
                    searchFocused = false
                    globalResults = GlobalSearch.Results()
                    globalSearching = false
                    globalTask?.cancel()
                }
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(palette.accent)
                .transition(.move(edge: .trailing).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.2), value: searchFocused)
        .animation(.easeInOut(duration: 0.2), value: query.isEmpty)
        .padding(.horizontal, 16)
        .padding(.bottom, 12)
    }

    // MARK: - Серверный поиск

    private func scheduleGlobalSearch(_ q: String) {
        globalTask?.cancel()
        globalSearchGeneration = UUID()
        let generation = globalSearchGeneration
        let trimmed = q.trimmingCharacters(in: .whitespaces)
        let raw = trimmed.hasPrefix("@") ? String(trimmed.dropFirst()) : trimmed
        guard raw.count >= 2 else { globalResults = GlobalSearch.Results(); globalSearching = false; return }
        globalTask = Task {
            try? await Task.sleep(nanoseconds: 350_000_000)
            guard !Task.isCancelled else { return }
            globalSearching = true
            let found = await GlobalSearch.search(raw)
            guard !Task.isCancelled, generation == globalSearchGeneration else { return }
            globalResults = found
            globalSearching = false
        }
    }

    private func globalUserRow(_ p: FoundUser) -> some View {
        HStack(spacing: 12) {
            Avatar(id: p.userId, name: p.title, size: AetherUI.listAvatar,
                   avatarURL: p.avatarFileId.isEmpty ? nil : URL(string: "\(CoreClient.baseURL)/avatars/\(p.avatarFileId)"),
                   online: messaging.isOnline(p.userId))
            VStack(alignment: .leading, spacing: 2) {
                Text(p.title)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(palette.textPrimary)
                    .lineLimit(1)
                Text(p.subtitle)
                    .font(.system(size: 14))
                    .foregroundStyle(palette.textSecondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 4)
        }
        .frame(minHeight: AetherUI.listRowHeight)
        .contentShape(Rectangle())
    }

    private func globalGroupRow(_ g: FoundGroup, member: Bool, joinable: Bool, joining: Bool) -> some View {
        HStack(spacing: 12) {
            Avatar(id: g.groupId, name: g.name, size: AetherUI.listAvatar,
                   avatarURL: g.avatarFileId.isEmpty ? nil
                       : URL(string: "\(CoreClient.baseURL)/avatars/\(g.avatarFileId)"),
                   online: false)
            VStack(alignment: .leading, spacing: 2) {
                Text(g.name)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(palette.textPrimary)
                    .lineLimit(1)
                Text({
                    let kind = g.isChannel ? "Канал" : "Группа"
                    if !g.username.isEmpty { return "@\(g.username) · \(kind)" }
                    return member ? kind : "\(kind) · по приглашению"
                }())
                    .font(.system(size: 14))
                    .foregroundStyle(palette.textSecondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 4)
            if joining {
                ProgressView().tint(palette.accent)
            } else if joinable {
                Text(g.isChannel ? "Подписаться" : "Вступить")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(palette.onAccent)
                    .padding(.horizontal, 12).padding(.vertical, 6)
                    .background(palette.accent, in: Capsule())
            }
        }
        .frame(minHeight: AetherUI.listRowHeight)
        .contentShape(Rectangle())
        .opacity(member || joinable ? 1 : 0.55)
    }

    #if DEBUG
    private func tryAutoOpen() {
        if let peer = ProcessInfo.processInfo.environment["AETHER_OPEN_CHAT"],
           openedPeer == nil,
           messaging.chats.contains(where: { $0.peerId == peer.lowercased() }) {
            openedPeer = peer.lowercased()
        }
    }
    #endif

    private func delete(_ offsets: IndexSet, in chats: [Chat]) {
        for index in offsets where chats.indices.contains(index) {
            Task { await messaging.deleteChat(chats[index].peerId) }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 14) {
            Image(systemName: "bubble.left.and.bubble.right")
                .font(.system(size: 52, weight: .thin))
                .foregroundStyle(palette.textSecondary)
            Text("Нет чатов")
                .font(.title3.weight(.semibold))
                .foregroundStyle(palette.textPrimary)
            Text("Откройте вкладку «Контакты», чтобы найти собеседника")
                .font(.subheadline)
                .foregroundStyle(palette.textSecondary)
                .multilineTextAlignment(.center)
        }
        .padding(40)
    }

    /// Разделитель вместо системного: у LazyVStack его нет.
    private var separator: some View {
        Rectangle()
            .fill(palette.divider)
            .frame(height: 0.5)
            .padding(.leading, AetherUI.listTextInset)
    }

    private var archiveRow: some View {
        archiveRowContent
            .padding(.horizontal, 16)
            .background(palette.background)
            .contentShape(Rectangle())
            .onTapGesture { showArchive = true }
    }

    private var archiveRowContent: some View {
        HStack(spacing: 12) {
            ZStack {
                Circle().fill(LinearGradient(colors: [palette.textSecondary, palette.textSecondary.opacity(0.6)],
                                             startPoint: .top, endPoint: .bottom))
                Image(systemName: "archivebox.fill")
                    .foregroundStyle(.white)
                    .font(.system(size: 22))
            }
            .frame(width: AetherUI.listAvatar, height: AetherUI.listAvatar)

            Text("Архив")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(palette.textPrimary)
            Spacer(minLength: 4)
            Text("\(archived.count)")
                .font(.system(size: 15))
                .foregroundStyle(palette.textSecondary)
        }
        .frame(minHeight: AetherUI.listRowHeight)
    }

    private func reorderGesture(_ id: String) -> some Gesture {
        DragGesture(minimumDistance: 6)
            .onChanged { value in
                if draggingId == nil {
                    draggingId = id
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                }
                dragDY = value.translation.height
                reorder(id)
            }
            .onEnded { _ in
                draggingId = nil
                withAnimation(.spring(response: 0.3, dampingFraction: 0.9)) { dragDY = 0 }
                UserDefaults.standard.set(manualOrder, forKey: "chatManualOrder")
            }
    }

    @ViewBuilder
    private func chatMenu(_ chat: Chat) -> some View {
        Button { togglePin(chat) } label: {
            Label(isPinned(chat) ? "Открепить" : "Закрепить", systemImage: "pin.fill")
        }
        Button { Task { await messaging.markRead(chat.peerId) } } label: {
            Label("Прочитать", systemImage: "envelope.open.fill")
        }
        Button { Task { await messaging.setMuted(chat.peerId, !chat.muted) } } label: {
            Label(chat.muted ? "Вкл. звук" : "Без звука",
                  systemImage: chat.muted ? "bell.fill" : "bell.slash.fill")
        }
        Button { Task { await messaging.setArchived(chat.peerId, true) } } label: {
            Label("В архив", systemImage: "archivebox.fill")
        }
        Button {
            blocks.toggle(chat.peerId)
        } label: {
            Label(blocks.isBlocked(chat.peerId) ? "Разблокировать" : "Заблокировать",
                  systemImage: blocks.isBlocked(chat.peerId) ? "hand.raised.slash" : "hand.raised")
        }

        Menu {
            ForEach(folders.folders.filter { $0.rule == .custom }) { f in
                Button {
                    var copy = f
                    if copy.peers.contains(chat.peerId) { copy.peers.removeAll { $0 == chat.peerId } }
                    else { copy.peers.append(chat.peerId) }
                    folders.upsert(copy)
                } label: {
                    Label(f.label, systemImage: f.peers.contains(chat.peerId) ? "checkmark" : "folder")
                }
            }
            Button {
                editingFolder = nil
                showFolderEditor = true
            } label: { Label("Новая папка", systemImage: "folder.badge.plus") }
        } label: {
            Label("В папку", systemImage: "folder")
        }

        Button(role: .destructive) {
            Task {
                if chat.peerId == session.myId.lowercased() { await messaging.clearSavedMessages() }
                else { await messaging.deleteChat(chat.peerId) }
            }
        } label: {
            Label(chat.peerId == session.myId.lowercased() ? "Очистить" : "Удалить",
                  systemImage: "trash.fill")
        }
    }

    private func chatRow(_ chat: Chat) -> some View {
        let isSelf = chat.peerId == session.myId.lowercased()
        // Порядок ведомых действий — как в системе: разрушающее у самого края.
        // Порядок = слева направо. У САМОГО КРАЯ экрана стоит архив: именно он
        // растягивается и срабатывает быстрым жестом. Удаление намеренно не там —
        // смахнуть чат насмерть одним движением слишком легко.
        let trailing = [
            RowAction(title: isSelf ? "Очистить" : "Удалить",
                      icon: isSelf ? "paintbrush.fill" : "trash.fill", tint: .red) {
                Task {
                    if isSelf { await messaging.clearSavedMessages() }
                    else { await messaging.deleteChat(chat.peerId) }
                }
            },
            RowAction(title: chat.muted ? "Вкл. звук" : "Без звука",
                      icon: chat.muted ? "bell.fill" : "bell.slash.fill", tint: .orange) {
                Task { await messaging.setMuted(chat.peerId, !chat.muted) }
            },
            RowAction(title: "В архив", icon: "archivebox.fill", tint: palette.textSecondary) {
                Task { await messaging.setArchived(chat.peerId, true) }
            }
        ]
        return SwipeRow(
            rowId: chat.peerId,
            openRow: $openRow,
            // Слева две кнопки, как в эталоне: на узком ряду из одной кнопки
            // ходу не хватало, и открытие читалось рывком.
            leading: [
                RowAction(title: isPinned(chat) ? "Открепить" : "Закрепить",
                          icon: "pin.fill", tint: palette.accent) {
                    movingId = chat.peerId
                    togglePin(chat)
                    Task {
                        // Держим строку сверху всю анимацию переезда и отпускаем.
                        try? await Task.sleep(nanoseconds: 700_000_000)
                        movingId = nil
                    }
                },
                RowAction(title: "Прочитать", icon: "envelope.open.fill",
                          tint: palette.textSecondary) {
                    Task { await messaging.markRead(chat.peerId) }
                }
            ],
            trailing: trailing,
            fullSwipeLeading: true,
            swipeEnabled: editMode != .active,
            onTap: { openedPeer = chat.peerId }
        ) {
            HStack(spacing: 12) {
                if editMode == .active {
                    Button {
                        Task { await messaging.deleteChat(chat.peerId) }
                    } label: {
                        Image(systemName: "minus.circle.fill")
                            .font(.system(size: 21))
                            .foregroundStyle(.red)
                    }
                    .buttonStyle(.plain)
                    .transition(.scale.combined(with: .opacity))
                }
                ChatRow(chat: chat,
                        myId: session.myId,
                        online: messaging.isOnline(chat.peerId),
                        typing: messaging.typingPeers.contains(chat.peerId),
                        pinned: isPinned(chat))
            }
            .padding(.horizontal, 16)
            .animation(.easeInOut(duration: 0.18), value: editMode)
        }
    }
}

struct ChatRow: View {
    private static let clockFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"
        return formatter
    }()
    private static let weekdayFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = .current
        formatter.dateFormat = "EEE"
        return formatter
    }()
    private static let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "dd.MM.yy"
        return formatter
    }()
    let chat: Chat
    let myId: String
    var online: Bool
    var typing: Bool
    /// Закреплён ли чат в ТЕКУЩЕЙ папке: закреп папочный, поэтому строка сама
    /// его знать не может — считает список.
    var pinned: Bool
    @Environment(\.palette) private var palette
    @EnvironmentObject var messaging: Messaging

    private var isSaved: Bool { chat.peerId == myId.lowercased() }
    private var title: String {
        if isSaved { return String(localized: "Избранное") }
        // 1:1 — имя из профиля (display name), а не застывший title чата.
        if !chat.isGroup { return messaging.displayName(chat.peerId, fallback: chat.title) }
        return chat.title.isEmpty ? chat.peerId : chat.title
    }

    var body: some View {
        HStack(spacing: 12) {
            avatar
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 5) {
                    Text(title)
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(palette.textPrimary)
                        .lineLimit(1)
                    if !chat.isGroup, !isSaved, let status = messaging.statusEmoji(chat.peerId) {
                        Text(status).font(.system(size: 14))
                    }
                    if chat.muted {
                        Image(systemName: "speaker.slash.fill")
                            .font(.system(size: 12)).foregroundStyle(palette.textSecondary)
                    }
                    Spacer(minLength: 4)
                    // Пин виден всегда (бейдж непрочитанных раньше вытеснял его,
                    // и закреплённость чата с непрочитанными была неотличима).
                    if pinned {
                        Image(systemName: "pin.fill")
                            .font(.system(size: 11))
                            .foregroundStyle(palette.textSecondary)
                            .rotationEffect(.degrees(45))
                    }
                    Text(timeString(chat.lastTs))
                        .font(.system(size: 13)).foregroundStyle(palette.textSecondary)
                }
                HStack(alignment: .top, spacing: 6) {
                    Text(typing ? "печатает…" : chat.lastText)
                        .font(.system(size: 15))
                        .foregroundStyle(typing ? palette.accent : palette.textSecondary)
                        .lineLimit(1)
                        .truncationMode(.tail)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    trailingBadge
                }
            }
        }
        .frame(minHeight: AetherUI.listRowHeight)
        .contentShape(Rectangle())
    }

    @ViewBuilder private var avatar: some View {
        if isSaved {
            ZStack {
                Circle().fill(LinearGradient(colors: [palette.accent, palette.accent.opacity(0.6)],
                                             startPoint: .top, endPoint: .bottom))
                Image(systemName: "bookmark.fill").foregroundStyle(.white).font(.system(size: 22))
            }.frame(width: AetherUI.listAvatar, height: AetherUI.listAvatar)
        } else {
            // avatarURL и для групп: messaging.avatarURL смотрит и в groups.info.
            Avatar(id: chat.peerId, name: chat.title, size: AetherUI.listAvatar,
                   avatarURL: messaging.avatarURL(chat.peerId),
                   online: online && !chat.isGroup)
                .onAppear { if !chat.isGroup { messaging.ensureProfile(chat.peerId) } }
        }
    }

    @ViewBuilder private var trailingBadge: some View {
        if chat.unread > 0 {
            Text("\(chat.unread)")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(chat.muted ? palette.textSecondary : palette.onAccent)
                .padding(.horizontal, 7).frame(minWidth: 22, minHeight: 22)
                .background(chat.muted ? palette.surfaceElevated : palette.accent, in: Capsule())
        } else if pinned {
            Image(systemName: "pin.fill")
                .font(.system(size: 13))
                .foregroundStyle(palette.textSecondary)
                .rotationEffect(.degrees(45))
        }
    }

    private func timeString(_ ms: Int64) -> String {
        guard ms > 0 else { return "" }
        let date = Date(timeIntervalSince1970: Double(ms) / 1000)
        let cal = Calendar.current
        if cal.isDateInToday(date) { return Self.clockFormatter.string(from: date) }
        else if cal.isDateInYesterday(date) { return String(localized: "вчера") }
        else if cal.isDate(date, equalTo: Date(), toGranularity: .weekOfYear) {
            return Self.weekdayFormatter.string(from: date)
        }
        return Self.dateFormatter.string(from: date)
    }
}


