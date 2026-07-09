import SwiftUI

struct ChatsListView: View {
    @EnvironmentObject var session: Session
    @EnvironmentObject var messaging: Messaging
    @EnvironmentObject var chrome: ChromeState
    @Environment(\.palette) private var palette

    @State private var openedPeer: String?
    @State private var query = ""
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
        let q = query.lowercased()
        return base.filter { $0.title.lowercased().contains(q) || $0.peerId.lowercased().contains(q) || $0.lastText.lowercased().contains(q) }
    }
    // Закрепы — в порядке закрепления: каждый новый выше предыдущих.
    private var pinned: [Chat] {
        visible.filter { $0.pinned }
            .sorted { messaging.pinRank($0.peerId) < messaging.pinRank($1.peerId) }
    }
    private var regular: [Chat] { visible.filter { !$0.pinned } }

    var body: some View {
        NavigationStack {
            ZStack {
                palette.background.ignoresSafeArea()

                List {
                    if !archived.isEmpty {
                        archiveRow
                    }
                    ForEach(pinned, id: \.peerId) { row($0) }
                        .onDelete { delete($0, in: pinned) }
                    ForEach(regular, id: \.peerId) { row($0) }
                        .onDelete { delete($0, in: regular) }
                    Color.clear.frame(height: 112).listRowSeparator(.hidden).listRowBackground(Color.clear)
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .environment(\.editMode, $editMode)
                .overlay { if messaging.chats.isEmpty { emptyState } }
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
            .navigationDestination(item: $openedPeer) { peer in
                ChatView(peerId: peer, isGroup: messaging.isGroup(peer))
                    .environmentObject(messaging)
            }
            // Возврат таб-бара сразу при выходе из чата: onDisappear пушнутого
            // экрана на iOS 27 срабатывает с большим лагом, item-биндинг — мгновенно.
            .onChange(of: openedPeer) { _, peer in
                if peer == nil { withAnimation(.easeOut(duration: 0.22)) { chrome.tabBarHidden = false } }
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
            Image(systemName: "magnifyingglass")
                .foregroundStyle(palette.textSecondary)
                .font(.system(size: 16))
            TextField("Поиск", text: $query)
                .foregroundStyle(palette.textPrimary)
                .tint(palette.accent)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .liquidGlass(Capsule())
        .padding(.horizontal, 16)
        .padding(.bottom, 12)
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
        .listRowInsets(EdgeInsets(top: 0, leading: 10, bottom: 0, trailing: 12))
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
        .listRowInsets(EdgeInsets(top: 0, leading: 10, bottom: 0, trailing: 12))
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
            } else {
                Button(role: .destructive) {
                    Task { await messaging.deleteChat(chat.peerId) }
                } label: { Label("Удалить", systemImage: "trash.fill") }
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
        formatter.locale = Locale(identifier: "ru_RU")
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
        if isSaved { return "Избранное" }
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
                    if chat.muted {
                        Image(systemName: "speaker.slash.fill")
                            .font(.system(size: 12)).foregroundStyle(palette.textSecondary)
                    }
                    Spacer(minLength: 4)
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
            Avatar(id: chat.peerId, name: chat.title, size: AetherUI.listAvatar,
                   avatarURL: chat.isGroup ? nil : messaging.avatarURL(chat.peerId),
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
        else if cal.isDateInYesterday(date) { return "вчера" }
        else if cal.isDate(date, equalTo: Date(), toGranularity: .weekOfYear) {
            return Self.weekdayFormatter.string(from: date)
        }
        return Self.dateFormatter.string(from: date)
    }
}
