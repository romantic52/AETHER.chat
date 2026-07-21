import SwiftUI
import PhotosUI

// Создание группы/канала в 2 шага: тип карточками → имя + участники с чипами.
struct GroupCreateView: View {
    var onCreated: (String) -> Void
    @EnvironmentObject var session: Session
    @EnvironmentObject var messaging: Messaging
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss

    enum Step { case type, details }
    @State private var step: Step
    @State private var isChannel: Bool
    @State private var name = ""
    @State private var query = ""
    @State private var results: [Profile] = []
    @State private var selected: [Profile] = []
    @State private var searchTask: Task<Void, Never>?
    @State private var creating = false
    @State private var error: String?
    
    @State private var isPublic = false
    @State private var publicUsername = ""
    @State private var showPeopleSearch = false
    @State private var avatarItem: PhotosPickerItem?
    @State private var avatarData: Data?

    init(isChannel: Bool = false, skipTypeSelection: Bool = false, onCreated: @escaping (String) -> Void) {
        self.onCreated = onCreated
        _isChannel = State(initialValue: isChannel)
        _step = State(initialValue: skipTypeSelection ? .details : .type)
    }

    var body: some View {
        NavigationStack {
            ZStack {
                palette.background.ignoresSafeArea()
                switch step {
                case .type: typeStep
                case .details: detailsStep
                }
            }
            .toolbar(.hidden, for: .navigationBar)
            .safeAreaInset(edge: .top) {
                // Шторка без чёлки-safe-area: опускаем шапку от скруглённого верха.
                FloatingHeader(
                    title: step == .type ? "Новый чат" : (isChannel ? "Новый канал" : "Новая группа"),
                    large: false,
                    leading: AnyView(Button("Отмена") { dismiss() }.foregroundStyle(palette.accent)),
                    trailing: step == .details ? AnyView(Button("Создать") { create() }
                        .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || creating)
                        .foregroundStyle(palette.accent)) : nil
                )
                .padding(.top, 14)
            }
        }
    }

    // Шаг 1 — тип.
    private var typeStep: some View {
        VStack(spacing: 16) {
            Spacer()
            typeCard(icon: "person.3.fill", title: "Группа",
                     subtitle: "Общий чат с участниками, все могут писать") {
                isChannel = false; step = .details
            }
            typeCard(icon: "megaphone.fill", title: "Канал",
                     subtitle: "Лента постов, писать могут только админы") {
                isChannel = true; step = .details
            }
            Spacer(); Spacer()
        }
        .padding(24)
    }

    private func typeCard(icon: String, title: String, subtitle: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 16) {
                Image(systemName: icon).font(.system(size: 28))
                    .foregroundStyle(palette.onAccent)
                    .frame(width: 60, height: 60)
                    .background(palette.accent, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(.headline).foregroundStyle(palette.textPrimary)
                    Text(subtitle).font(.subheadline).foregroundStyle(palette.textSecondary)
                        .multilineTextAlignment(.leading)
                }
                Spacer()
                Image(systemName: "chevron.right").foregroundStyle(palette.textSecondary)
            }
            .padding(16)
            .liquidGlass(cornerRadius: 18, interactive: true)
        }
        .buttonStyle(.squish)
    }

    // Шаг 2 — имя + участники.
    private var detailsStep: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                PhotosPicker(selection: $avatarItem, matching: .images) {
                    ZStack {
                        if let avatarData, let uiImage = UIImage(data: avatarData) {
                            Image(uiImage: uiImage)
                                .resizable()
                                .scaledToFill()
                                .frame(width: 56, height: 56)
                                .clipShape(Circle())
                        } else {
                            Circle().fill(palette.accent.opacity(0.2))
                            Image(systemName: "camera.fill").foregroundStyle(palette.accent)
                        }
                    }.frame(width: 56, height: 56)
                }
                .onChange(of: avatarItem) { _, item in
                    Task {
                        if let data = try? await item?.loadTransferable(type: Data.self) {
                            avatarData = data
                        }
                    }
                }
                
                TextField(isChannel ? "Название канала" : "Название группы", text: $name)
                    .font(.title3).foregroundStyle(palette.textPrimary)
            }
            .padding(16)
            
            // Публичность (Telegram-модель): публичный = @username, виден в поиске,
            // вступить может любой. Лимит — 25 публичных групп и каналов на владельца.
            Picker("Тип", selection: $isPublic) {
                Text("Частный").tag(false)
                Text("Публичный").tag(true)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16)
            .padding(.bottom, 8)

            if isPublic {
                HStack {
                    Text("@").foregroundStyle(palette.textSecondary)
                    TextField("username", text: $publicUsername)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .foregroundStyle(palette.textPrimary)
                }
                .padding(.horizontal, 16).padding(.vertical, 12)
                .liquidGlass(Capsule())
                .padding(.horizontal, 16)
                .padding(.bottom, 4)
            }
            Text(isPublic
                 ? (isChannel ? "Канал будет виден в поиске по @имени, подписаться может любой."
                              : "Группа будет видна в поиске по @имени, вступить может любой.")
                 : (isChannel ? "Подписчиков добавляют владелец и админы."
                              : "Участников добавляют владелец и админы."))
                .font(.caption)
                .foregroundStyle(palette.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.bottom, 12)

            if !selected.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(selected, id: \.userId) { p in chip(p) }
                    }.padding(.horizontal, 16)
                }
                .padding(.bottom, 8)
            }

            // Список контактов (люди, с которыми уже есть чат). Найти НОВОГО
            // человека — плюс-кнопка в углу (открывает поиск людей).
            List {
                if let error {
                    Text(error).font(.footnote).foregroundStyle(palette.danger).listRowBackground(Color.clear)
                }
                Section {
                    ForEach(localContacts, id: \.userId) { p in
                        personRow(p)
                    }
                } header: {
                    Text(isChannel ? "Подписчики (можно добавить позже)" : "Участники").foregroundStyle(palette.textSecondary)
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
        }
        .overlay(alignment: .bottomTrailing) {
            // Плюс: поиск людей по всему серверу (имя или @username).
            Button { showPeopleSearch = true } label: {
                Image(systemName: "plus")
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(palette.onAccent)
                    .frame(width: 56, height: 56)
                    .background(palette.accent, in: Circle())
                    .shadow(color: .black.opacity(0.25), radius: 8, y: 4)
            }
            .buttonStyle(.squish)
            .padding(.trailing, 20)
            .padding(.bottom, 28)
            .accessibilityLabel("Найти людей")
        }
        .sheet(isPresented: $showPeopleSearch) { peopleSearchSheet }
    }

    /// Контакты = собеседники существующих личных чатов.
    private var localContacts: [Profile] {
        messaging.chats
            .filter { !$0.isGroup && $0.peerId != session.myId.lowercased() }
            .map { chat in
                messaging.profiles[chat.peerId] ?? Profile(
                    userId: chat.peerId, username: nil,
                    displayName: chat.title.isEmpty ? nil : chat.title,
                    avatarFileId: nil, bio: nil, lastActive: nil, publicKeyB64: nil)
            }
    }

    private func personRow(_ p: Profile) -> some View {
        // Имя из профиля (display name), а не сырой id для старых чатов.
        let name = messaging.displayName(p.userId, fallback: p.displayName ?? p.username ?? p.userId)
        return Button { toggle(p) } label: {
            HStack(spacing: 12) {
                Avatar(id: p.userId, name: name, size: 42,
                       avatarURL: messaging.avatarURL(p.userId))
                    .onAppear { messaging.ensureProfile(p.userId) }
                VStack(alignment: .leading, spacing: 2) {
                    Text(name).foregroundStyle(palette.textPrimary)
                    if let u = p.username, !u.isEmpty {
                        Text("@\(u)").font(.caption).foregroundStyle(palette.textSecondary)
                    }
                }
                Spacer()
                if selected.contains(where: { $0.userId == p.userId }) {
                    Image(systemName: "checkmark.circle.fill").foregroundStyle(palette.accent)
                }
            }
        }
        .listRowBackground(Color.clear)
    }

    // Шторка поиска людей (для приглашения тех, с кем ещё нет чата).
    private var peopleSearchSheet: some View {
        NavigationStack {
            ZStack {
                palette.background.ignoresSafeArea()
                List {
                    ForEach(results, id: \.userId) { p in
                        personRow(p)
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
            .toolbar(.hidden, for: .navigationBar)
            .safeAreaInset(edge: .top) {
                VStack(spacing: 0) {
                    FloatingHeader(
                        title: "Найти людей", large: false,
                        trailing: AnyView(Button("Готово") { showPeopleSearch = false }
                            .foregroundStyle(palette.accent)),
                        withBackground: false
                    )
                    FloatingSearchBar(prompt: "Имя или @username", text: $query)
                }
                .padding(.top, 14)
                .background(EdgeDim(edge: .top).ignoresSafeArea(edges: .top))
            }
            .onChange(of: query) { _, q in scheduleSearch(q) }
        }
        .presentationDetents([.large])
    }

    private func chip(_ p: Profile) -> some View {
        HStack(spacing: 6) {
            Avatar(id: p.userId, name: p.displayName ?? p.userId, size: 24)
            Text(p.displayName ?? p.username ?? p.userId).font(.subheadline).foregroundStyle(palette.textPrimary)
            Button { toggle(p) } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(palette.textSecondary) }
        }
        .padding(.horizontal, 8).padding(.vertical, 5)
        .background(palette.surfaceElevated, in: Capsule())
    }

    private func toggle(_ p: Profile) {
        if let i = selected.firstIndex(where: { $0.userId == p.userId }) { selected.remove(at: i) }
        else { selected.append(p) }
    }

    private func scheduleSearch(_ q: String) {
        searchTask?.cancel()
        let t = q.trimmingCharacters(in: .whitespaces)
        guard t.count >= 2 else { results = []; return }
        searchTask = Task {
            try? await Task.sleep(nanoseconds: 300_000_000)
            guard !Task.isCancelled else { return }
            let found = (try? await session.core.searchUsers(t)) ?? []
            results = found.filter { $0.userId.lowercased() != session.myId.lowercased() }
        }
    }

    private func create() {
        creating = true; error = nil
        let nm = name.trimmingCharacters(in: .whitespaces)
        let members = selected.map { $0.userId }
        let channel = isChannel
        let uname = publicUsername.trimmingCharacters(in: .whitespaces)
            .lowercased().replacingOccurrences(of: "@", with: "")
        if isPublic, uname.range(of: "^[a-z][a-z0-9_]{3,31}$", options: .regularExpression) == nil {
            error = "@имя: 4–32 символа, латиница/цифры/_, начинается с буквы"
            creating = false
            return
        }
        Task {
            do {
                let id = try await messaging.groups.create(name: nm, isChannel: channel, memberIds: members)
                // Аватар: даунсэмплинг до 512px и загрузка (публичный, как у профилей).
                if let avatarData,
                   let jpeg = MediaStore.downsample(data: avatarData, maxPixel: 512)?.jpegData(compressionQuality: 0.85) {
                    await messaging.groups.setGroupAvatar(groupId: id, data: jpeg, mime: "image/jpeg")
                }
                if isPublic {
                    if let err = await messaging.groups.setGroupPublic(groupId: id, isPublic: true, username: uname) {
                        // Создано, но публичность не включилась (имя занято/лимит) —
                        // показываем причину, публичность можно включить позже в профиле.
                        self.error = "Создано как частный: \(err)"
                        creating = false
                        return
                    }
                }
                creating = false
                dismiss()
                onCreated(id)
            } catch {
                self.error = "Не удалось создать: \(error.localizedDescription)"
                creating = false
            }
        }
    }
}
