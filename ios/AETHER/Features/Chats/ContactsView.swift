import SwiftUI

// Поиск пользователей по @username и вход в чат. Также «Избранное» (чат с собой).
struct ContactsView: View {
    var onPick: ((String) -> Void)? = nil
    var globalSearch: Bool = false
    @EnvironmentObject var session: Session
    @EnvironmentObject var messaging: Messaging
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss

    @State private var query = ""
    @State private var results: [Profile] = []
    @State private var searching = false
    @State private var searchTask: Task<Void, Never>?
    @State private var searchGeneration = UUID()
    @State private var navPeer: String?

    private var localContacts: [Chat] {
        let own = session.myId.lowercased()
        var seen = Set<String>()
        return messaging.chats
            .filter { !$0.isGroup && $0.peerId != own && seen.insert($0.peerId).inserted }
            .filter {
                query.isEmpty || $0.title.localizedCaseInsensitiveContains(query) ||
                    $0.peerId.localizedCaseInsensitiveContains(query)
            }
            .sorted { ($0.title.isEmpty ? $0.peerId : $0.title).localizedCaseInsensitiveCompare($1.title.isEmpty ? $1.peerId : $1.title) == .orderedAscending }
    }

    var body: some View {
        NavigationStack {
            ZStack {
                palette.background.ignoresSafeArea()
                List {
                    Section {
                        Button {
                            pick(session.myId)
                        } label: {
                            HStack(spacing: 12) {
                                ZStack {
                                    Circle().fill(palette.accent)
                                    Image(systemName: "bookmark.fill").foregroundStyle(.white)
                                }.frame(width: 50, height: 50)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("Избранное").font(.system(size: 17, weight: .medium)).foregroundStyle(palette.textPrimary)
                                    Text("Личное облако").font(.system(size: 14)).foregroundStyle(palette.textSecondary)
                                }
                            }
                        }
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                        .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                    }

                    if !localContacts.isEmpty {
                        Section {
                            // Заголовок обычной строкой: липнущий header в plain-списке
                            // рисовал системную градиент-подложку при скролле.
                            Text("Контакты")
                                .font(.footnote.weight(.semibold))
                                .foregroundStyle(palette.textSecondary)
                                .listRowBackground(Color.clear)
                                .listRowSeparator(.hidden)
                                .listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 2, trailing: 16))
                            ForEach(localContacts, id: \.peerId) { chat in
                                Button { pick(chat.peerId) } label: {
                                    contactRow(id: chat.peerId, name: chat.title.isEmpty ? chat.peerId : chat.title)
                                }
                                .listRowBackground(Color.clear)
                                .listRowSeparator(.hidden)
                                .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                            }
                        }
                    }

                    if searching {
                        HStack { Spacer(); ProgressView().tint(palette.accent); Spacer() }
                            .listRowBackground(Color.clear)
                    }

                    Section {
                        if !results.isEmpty {
                            Text("Результаты")
                                .font(.footnote.weight(.semibold))
                                .foregroundStyle(palette.textSecondary)
                                .listRowBackground(Color.clear)
                                .listRowSeparator(.hidden)
                                .listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 2, trailing: 16))
                        }
                        ForEach(results, id: \.userId) { p in
                            Button { pick(p.userId) } label: {
                                HStack(spacing: 12) {
                                    Avatar(id: p.userId, name: p.displayName ?? p.username ?? p.userId, size: 50,
                                           avatarURL: (p.avatarFileId ?? "").isEmpty ? nil : URL(string: "\(CoreClient.baseURL)/avatars/\(p.avatarFileId!)"),
                                           online: messaging.isOnline(p.userId))
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(p.displayName ?? p.username ?? p.userId)
                                            .font(.system(size: 17, weight: .medium))
                                            .foregroundStyle(palette.textPrimary)
                                        if let u = p.username, !u.isEmpty {
                                            Text("@\(u)").font(.caption).foregroundStyle(palette.textSecondary)
                                        }
                                    }
                                }
                            }
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                            .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                        }
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .safeAreaPadding(.bottom, 110)
            }
            .toolbar(.hidden, for: .navigationBar)
            .safeAreaInset(edge: .top) {
                VStack(spacing: 0) {
                    FloatingHeader(
                        title: onPick != nil ? "Новый чат" : (globalSearch ? "Поиск" : "Контакты"),
                        large: onPick == nil,
                        trailing: onPick != nil ? AnyView(Button("Закрыть") { dismiss() }.foregroundStyle(palette.accent)) : nil,
                        withBackground: false
                    )
                    FloatingSearchBar(prompt: "Имя или @username", text: $query)
                }
                .background(EdgeDim(edge: .top).ignoresSafeArea(edges: .top))
            }
            .onChange(of: query) { _, q in scheduleSearch(q) }
            #if DEBUG
            .task {
                if let q = ProcessInfo.processInfo.environment["AETHER_SEARCH_QUERY"], !q.isEmpty {
                    try? await Task.sleep(nanoseconds: 1_000_000_000)
                    query = q
                }
            }
            #endif
            .navigationDestination(item: $navPeer) { peer in
                ChatView(peerId: peer, isGroup: false).environmentObject(messaging)
            }
        }
    }

    private func contactRow(id: String, name: String) -> some View {
        HStack(spacing: 12) {
            Avatar(id: id, name: name, size: 50,
                   avatarURL: messaging.avatarURL(id), online: messaging.isOnline(id))
                .onAppear { messaging.ensureProfile(id) }
            VStack(alignment: .leading, spacing: 2) {
                Text(name).font(.system(size: 17, weight: .medium)).foregroundStyle(palette.textPrimary)
                Text(messaging.presenceText(id).isEmpty ? "был(а) недавно" : messaging.presenceText(id))
                    .font(.system(size: 14))
                    .foregroundStyle(messaging.isOnline(id) ? palette.accent : palette.textSecondary)
            }
            Spacer()
        }
        .frame(minHeight: 58)
        .contentShape(Rectangle())
    }

    private func pick(_ userId: String) {
        let id = userId.lowercased()
        if let onPick { onPick(id); dismiss() }
        else { navPeer = id }
    }

    private func scheduleSearch(_ q: String) {
        searchTask?.cancel()
        searchGeneration = UUID()
        let generation = searchGeneration
        let trimmed = q.trimmingCharacters(in: .whitespaces)
        guard trimmed.count >= 2 else { results = []; searching = false; return }
        searchTask = Task {
            try? await Task.sleep(nanoseconds: 300_000_000)
            guard !Task.isCancelled else { return }
            searching = true
            let found = (try? await session.core.searchUsers(trimmed)) ?? []
            guard !Task.isCancelled, generation == searchGeneration else { return }
            results = found.filter { $0.userId.lowercased() != session.myId.lowercased() }
            searching = false
        }
    }
}
