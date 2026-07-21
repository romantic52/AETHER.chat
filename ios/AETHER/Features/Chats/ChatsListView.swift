import SwiftUI

struct ChatsListView: View {
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
    @State private var newChatPresented = false
    @State private var newGroupPresented = false
    @State private var newChannelPresented = false
    @State private var showComposeMenu = false
    @State private var showArchive = false
    @State private var editMode: EditMode = .inactive

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
    // Закрепы — в порядке закрепления: каждый новый выше предыдущих.
    private var pinned: [Chat] {
        visible.filter { $0.pinned }
            .sorted { messaging.pinRank($0.peerId) < messaging.pinRank($1.peerId) }
    }
    private var regular: [Chat] { visible.filter { !$0.pinned } }
    // Один упорядоченный список (закреп сверху): List анимирует ПЕРЕЕЗД строки
    // при пине (соседи разъезжаются), а не «исчезла тут — появилась там».
    private var ordered: [Chat] { pinned + regular }

    @ViewBuilder
    private var searchSections: some View {
        if !visible.isEmpty {
            ForEach(ordered, id: \.peerId) { row($0) }
                .onDelete { delete($0, in: ordered) }
        }

        if globalSearching {
            HStack { Spacer(); ProgressView().tint(palette.accent); Spacer() }
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
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
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
                .listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 2, trailing: 16))

            ForEach(filteredGlobalUsers, id: \.userId) { profile in
                Button { openedPeer = profile.userId.lowercased() } label: {
                    globalUserRow(profile)
                }
                .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
                .listRowBackground(Color.clear)
                .listRowSeparatorTint(palette.divider)
                .alignmentGuide(.listRowSeparatorLeading) { _ in AetherUI.listTextInset }
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
                }
                .disabled(!member && !joinable)
                .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
                .listRowBackground(Color.clear)
                .listRowSeparatorTint(palette.divider)
                .alignmentGuide(.listRowSeparatorLeading) { _ in AetherUI.listTextInset }
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
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
        }
    }

    // Без собственного NavigationStack: все вкладки живут в ЕДИНОМ стеке
    // HomeView — пуши (чат, архив) накрывают и контент, и общий таб-бар.
    var body: some View {
        Group {
            ZStack {
                palette.background.ignoresSafeArea()

                List {
                    if query.isEmpty {
                        if !archived.isEmpty {
                            archiveRow
                        }
                        ForEach(ordered, id: \.peerId) { row($0) }
                            .onDelete { delete($0, in: ordered) }
                    } else {
                        searchSections
                    }
                    Color.clear.frame(height: 112).listRowSeparator(.hidden).listRowBackground(Color.clear)
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .scrollDismissesKeyboard(.interactively)
                .environment(\.editMode, $editMode)
                .overlay { if messaging.chats.isEmpty && query.isEmpty { emptyState } }
                .safeAreaInset(edge: .top) {
                    VStack(spacing: 0) {
                        customHeader
                        customSearchBar
                    }
                    .background(
                        EdgeDim(edge: .top)
                            .ignoresSafeArea(edges: .top)
                    )
                }
            }
            .toolbar(.hidden, for: .navigationBar)
            .ignoresSafeArea(.keyboard, edges: .bottom)
            .onChange(of: query) { _, q in scheduleGlobalSearch(q) }
            .navigationDestination(item: $openedPeer) { peer in
                ChatView(peerId: peer, isGroup: messaging.isGroup(peer))
                    .environmentObject(messaging)
            }
            .navigationDestination(isPresented: $showArchive) {
                ArchiveView()
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

    private var customHeader: some View {
        ZStack {
            Text("Чаты")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(palette.textPrimary)

            HStack {
                Button(editMode == .active ? "Готово" : "Изм.") {
                    withAnimation(.easeInOut(duration: 0.18)) {
                        editMode = editMode == .active ? .inactive : .active
                    }
                }
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(palette.textPrimary)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .liquidGlass(Capsule())

                Spacer()

                Button { showComposeMenu = true } label: {
                    Image(systemName: "square.and.pencil")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(palette.textPrimary)
                        .frame(width: 36, height: 36)
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
        .padding(.bottom, 12)
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

    private var archiveRow: some View {
        Button { showArchive = true } label: {
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
            .contentShape(Rectangle())
        }
        .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
        .listRowBackground(Color.clear)
        .listRowSeparatorTint(palette.divider)
        .alignmentGuide(.listRowSeparatorLeading) { _ in AetherUI.listTextInset }
    }

    private func row(_ chat: Chat) -> some View {
        Button {
            openedPeer = chat.peerId
        } label: {
            ChatRow(chat: chat,
                    myId: session.myId,
                    online: messaging.isOnline(chat.peerId),
                    typing: messaging.typingPeers.contains(chat.peerId))
        }
        .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
        .listRowBackground(Color.clear)
        .listRowSeparatorTint(palette.divider)
        .alignmentGuide(.listRowSeparatorLeading) { _ in AetherUI.listTextInset }
        .swipeActions(edge: .leading, allowsFullSwipe: true) {
            Button {
                Task { await messaging.setPinned(chat.peerId, !chat.pinned) }
            } label: { Label(chat.pinned ? "Открепить" : "Закрепить", systemImage: "pin.fill") }
                .tint(palette.accent)
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            if chat.peerId == session.myId.lowercased() {
                // Избранное — личный канал: удалить нельзя, только очистить историю.
                Button(role: .destructive) {
                    Task { await messaging.clearSavedMessages() }
                } label: { Label("Очистить", systemImage: "paintbrush.fill") }
                    .tint(.red)   // иначе акцент приложения перебивает красный
            } else {
                Button(role: .destructive) {
                    Task { await messaging.deleteChat(chat.peerId) }
                } label: { Label("Удалить", systemImage: "trash.fill") }
                    .tint(.red)
            }
            Button {
                Task { await messaging.setMuted(chat.peerId, !chat.muted) }
            } label: { Label(chat.muted ? "Вкл. звук" : "Без звука", systemImage: chat.muted ? "bell.fill" : "bell.slash.fill") }
                .tint(.orange)
            Button {
                Task { await messaging.setArchived(chat.peerId, true) }
            } label: { Label("В архив", systemImage: "archivebox.fill") }
                .tint(palette.textSecondary)
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
                    if chat.pinned {
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
        } else if chat.pinned {
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
