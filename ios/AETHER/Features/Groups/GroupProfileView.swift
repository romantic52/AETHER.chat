import SwiftUI
import PhotosUI

// Профиль группы/канала в стиле Telegram: крупная шапка, «N участников, X онлайн»,
// круглые кнопки-действия, секции-карточки, роли владелец/админ, онлайн-точки.
struct GroupProfileView: View {
    let groupId: String
    @EnvironmentObject var session: Session
    @EnvironmentObject var messaging: Messaging
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss

    @State private var members: [GroupMember] = []
    @State private var loading = true
    @State private var showAddMember = false
    @State private var showRenameSheet = false
    @State private var editName = ""
    @State private var editDesc = ""
    @State private var confirmLeave = false
    @State private var confirmDelete = false
    // Черновики публичности (владелец).
    @State private var publicDraft = false
    @State private var usernameDraft = ""
    @State private var visibilityError: String?
    @State private var groupAvatarItem: PhotosPickerItem?

    private var info: GroupInfo? { messaging.groups.info(groupId) }
    private var isChannel: Bool { info?.isChannel ?? false }
    private var myRole: String { info?.myRole ?? "member" }
    private var isOwnerOrAdmin: Bool { info?.isOwnerOrAdmin ?? false }
    private var onlineCount: Int { members.filter { messaging.isOnline($0.userId) }.count }

    var body: some View {
        NavigationStack {
            ZStack {
                palette.background.ignoresSafeArea()
                List {
                    headerSection
                    actionsSection
                    infoSection
                    if info?.myRole == "owner" { visibilitySection }
                    // Подписчиков канала видят владелец/админы; участников группы — все.
                    if !isChannel || isOwnerOrAdmin { membersSection }
                    dangerSection
                }
                .listStyle(.insetGrouped)
                .scrollContentBackground(.hidden)
            }
            .toolbar(.hidden, for: .navigationBar)
            .safeAreaInset(edge: .top) {
                // Шторка: у неё нет статус-бара, поэтому опускаем кнопки от
                // скругления и держим одинаковые поля с обеих сторон.
                FloatingHeader(
                    title: "",
                    large: false,
                    leading: AnyView(Button("Закрыть") { dismiss() }.foregroundStyle(palette.accent)),
                    trailing: isOwnerOrAdmin ? AnyView(Button("Изменить") {
                        editName = info?.name ?? ""; editDesc = info?.description ?? ""
                        showRenameSheet = true
                    }.foregroundStyle(palette.accent)) : nil
                )
                .padding(.top, 14)
            }
            .task { await reload() }
            .sheet(isPresented: $showAddMember) {
                AddMemberView(groupId: groupId) { Task { await reload() } }
                    .environmentObject(session).environmentObject(messaging)
            }
            .sheet(isPresented: $showRenameSheet) {
                RenameGroupSheet(name: $editName, description: $editDesc, isChannel: isChannel) {
                    Task { await messaging.groups.rename(groupId: groupId, name: editName, description: editDesc) }
                }
            }
            .confirmationDialog(isChannel ? "Покинуть канал?" : "Покинуть группу?", isPresented: $confirmLeave, titleVisibility: .visible) {
                Button("Покинуть", role: .destructive) {
                    Task { await messaging.groups.leave(groupId: groupId); dismiss() }
                }
            }
            .confirmationDialog("Удалить безвозвратно?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Удалить", role: .destructive) {
                    Task { await messaging.groups.remove(groupId: groupId); dismiss() }
                }
            }
        }
    }

    private func reload() async {
        loading = true
        members = await messaging.groups.members(groupId)
        loading = false
    }

    // MARK: - Шапка

    private var headerSection: some View {
        Section {
            VStack(spacing: 14) {
                ZStack(alignment: .bottomTrailing) {
                    if let fid = info?.avatarFileId, !fid.isEmpty {
                        Avatar(id: groupId, name: info?.name ?? groupId, size: 110,
                               avatarURL: URL(string: "\(CoreClient.baseURL)/avatars/\(fid)"))
                    } else {
                        ZStack {
                            Circle().fill(LinearGradient(colors: [palette.accent, palette.accent.opacity(0.6)],
                                                         startPoint: .top, endPoint: .bottom))
                            Image(systemName: isChannel ? "megaphone.fill" : "person.3.fill")
                                .font(.system(size: 46)).foregroundStyle(.white)
                        }
                        .frame(width: 110, height: 110)
                    }
                    if isOwnerOrAdmin {
                        PhotosPicker(selection: $groupAvatarItem, matching: .images) {
                            Image(systemName: "camera.fill")
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(palette.onAccent)
                                .frame(width: 32, height: 32)
                                .background(palette.accent, in: Circle())
                        }
                        .offset(x: 2, y: 2)
                    }
                }
                .frame(width: 110, height: 110)
                .onChange(of: groupAvatarItem) { _, item in
                    guard let item else { return }
                    Task {
                        defer { groupAvatarItem = nil }
                        if let data = try? await item.loadTransferable(type: Data.self),
                           let jpeg = MediaStore.downsample(data: data, maxPixel: 512)?
                               .jpegData(compressionQuality: 0.85) {
                            await messaging.groups.setGroupAvatar(groupId: groupId, data: jpeg, mime: "image/jpeg")
                        }
                    }
                }

                Text(info?.name ?? groupId)
                    .font(.title2.weight(.bold)).foregroundStyle(palette.textPrimary)

                Text(subtitleText)
                    .font(.subheadline).foregroundStyle(palette.textSecondary)

                if let uname = info?.username, !uname.isEmpty {
                    Text("@\(uname)")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(palette.accent)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets())
        }
    }

    private var subtitleText: String {
        if isChannel { return "Канал • \(info?.memberCount ?? members.count) подписчиков" }
        return "\(members.count) участников" + (onlineCount > 0 ? ", \(onlineCount) в сети" : "")
    }

    // MARK: - Круглые кнопки-действия

    // Ряд действий — равные карточки во всю ширину с одинаковыми зазорами
    // (как в Telegram), а не круглые кнопки, разлетевшиеся по пустой строке.
    private var actionsSection: some View {
        Section {
            HStack(spacing: 10) {
                actionButton(icon: "bubble.left.fill", title: "Чат") { dismiss() }
                if !isChannel {
                    actionButton(icon: "phone.fill", title: "Звонок") {
                        messaging.groupCalls.start(groupId: groupId)
                        dismiss()
                    }
                }
                if isOwnerOrAdmin {
                    actionButton(icon: "person.badge.plus", title: "Добавить") { showAddMember = true }
                }
                // Рабочий тумблер звука (mute чата), иконка отражает состояние.
                actionButton(icon: isMuted ? "bell.slash.fill" : "bell.fill",
                             title: isMuted ? "Без звука" : "Звук") {
                    Task { await messaging.setMuted(groupId, !isMuted) }
                }
            }
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 4, trailing: 16))
        }
    }

    private var isMuted: Bool {
        messaging.chats.first { $0.peerId == groupId.lowercased() }?.muted ?? false
    }

    // Инфо-карточка: @имя (тап — копия) и описание отдельным блоком, как в Telegram.
    @ViewBuilder private var infoSection: some View {
        let username = info?.username ?? ""
        let desc = info?.description ?? ""
        if !username.isEmpty || !desc.isEmpty {
            Section {
                if !username.isEmpty {
                    Button {
                        UIPasteboard.general.string = "@\(username)"
                        UINotificationFeedbackGenerator().notificationOccurred(.success)
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("@\(username)")
                                .foregroundStyle(palette.accent)
                            Text("имя — нажми, чтобы скопировать")
                                .font(.caption).foregroundStyle(palette.textSecondary)
                        }
                    }
                    .listRowBackground(palette.surface)
                }
                if !desc.isEmpty {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(desc).foregroundStyle(palette.textPrimary)
                        Text("описание")
                            .font(.caption).foregroundStyle(palette.textSecondary)
                    }
                    .listRowBackground(palette.surface)
                }
            }
        }
    }

    private func actionButton(icon: String, title: LocalizedStringKey, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 5) {
                Image(systemName: icon).font(.system(size: 19, weight: .medium))
                    .foregroundStyle(palette.accent)
                Text(title)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(palette.accent)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 58)
            .liquidGlass(RoundedRectangle(cornerRadius: 14, style: .continuous), interactive: true)
            .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.squish)
    }

    // MARK: - Публичность (владелец): @username как в Telegram, лимит 25.

    private var visibilitySection: some View {
        Section {
            Toggle(isOn: $publicDraft.animation()) {
                Label(isChannel ? "Публичный канал" : "Публичная группа", systemImage: "globe")
                    .foregroundStyle(palette.textPrimary)
            }
            .listRowBackground(palette.surface)
            if publicDraft {
                HStack {
                    Text("@").foregroundStyle(palette.textSecondary)
                    TextField("username", text: $usernameDraft)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .foregroundStyle(palette.textPrimary)
                }
                .listRowBackground(palette.surface)
            }
            if visibilityDirty {
                Button {
                    Task {
                        visibilityError = await messaging.groups.setGroupPublic(
                            groupId: groupId, isPublic: publicDraft,
                            username: publicDraft ? usernameDraft : nil)
                        if visibilityError == nil { await reload() }
                    }
                } label: {
                    Text("Сохранить").foregroundStyle(palette.accent)
                }
                .listRowBackground(palette.surface)
            }
            if let visibilityError {
                Text(visibilityError).font(.footnote).foregroundStyle(palette.danger)
                    .listRowBackground(Color.clear)
            }
        } footer: {
            Text("Публичные видны в поиске по @имени, вступить может любой (лимит — 25 публичных на владельца). Частные — только по приглашению.")
        }
        .onAppear {
            publicDraft = info?.publicJoin ?? false
            usernameDraft = info?.username ?? ""
        }
    }

    private var visibilityDirty: Bool {
        publicDraft != (info?.publicJoin ?? false)
            || (publicDraft && usernameDraft.lowercased() != (info?.username ?? ""))
    }

    // MARK: - Участники

    private var membersSection: some View {
        Section {
            if isOwnerOrAdmin {
                Button { showAddMember = true } label: {
                    Label(isChannel ? "Добавить подписчика" : "Добавить участника",
                          systemImage: "person.badge.plus")
                }
                .listRowBackground(palette.surface)
            }
            ForEach(members.sorted(by: memberSort)) { m in
                memberRow(m)
                    .listRowBackground(palette.surface)
                    .swipeActions(edge: .trailing) {
                        if isOwnerOrAdmin && m.userId != session.myId.lowercased() && m.role != "owner" {
                            Button(role: .destructive) {
                                Task { await messaging.groups.removeMember(groupId: groupId, userId: m.userId); await reload() }
                            } label: { Label("Убрать", systemImage: "person.badge.minus") }
                        }
                    }
            }
        } header: {
            Text("Участники").foregroundStyle(palette.textSecondary)
        }
    }

    private func memberSort(_ a: GroupMember, _ b: GroupMember) -> Bool {
        func rank(_ r: String) -> Int { r == "owner" ? 0 : (r == "admin" ? 1 : 2) }
        return rank(a.role) != rank(b.role) ? rank(a.role) < rank(b.role) : a.displayName < b.displayName
    }

    private func memberRow(_ m: GroupMember) -> some View {
        HStack(spacing: 12) {
            Avatar(id: m.userId, name: m.displayName, size: 42,
                   avatarURL: messaging.avatarURL(m.userId), online: messaging.isOnline(m.userId))
                .onAppear { messaging.ensureProfile(m.userId) }
            VStack(alignment: .leading, spacing: 2) {
                Text(m.displayName).foregroundStyle(palette.textPrimary)
                Text(messaging.isOnline(m.userId) ? "в сети" : "не в сети")
                    .font(.caption).foregroundStyle(palette.textSecondary)
            }
            Spacer()
            if m.role == "owner" {
                roleBadge("Владелец", palette.accent)
            } else if m.role == "admin" {
                roleBadge("Админ", palette.textSecondary)
            }
        }
    }

    private func roleBadge(_ text: String, _ color: Color) -> some View {
        Text(text).font(.caption2.weight(.semibold)).foregroundStyle(color)
            .padding(.horizontal, 8).padding(.vertical, 3)
            .background(color.opacity(0.15), in: Capsule())
    }

    // MARK: - Опасная зона

    private var dangerSection: some View {
        Section {
            Button(role: .destructive) { confirmLeave = true } label: {
                Label(isChannel ? "Покинуть канал" : "Покинуть группу", systemImage: "rectangle.portrait.and.arrow.right")
            }
            if myRole == "owner" {
                Button(role: .destructive) { confirmDelete = true } label: {
                    Label(isChannel ? "Удалить канал" : "Удалить группу", systemImage: "trash")
                }
            }
        }
        .listRowBackground(palette.surface)
    }
}

// Добавление участника (поиск + подтверждение).
struct AddMemberView: View {
    let groupId: String
    var onAdded: () -> Void
    @EnvironmentObject var session: Session
    @EnvironmentObject var messaging: Messaging
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss

    @State private var query = ""
    @State private var results: [Profile] = []
    @State private var searchTask: Task<Void, Never>?
    @State private var adding = false

    var body: some View {
        NavigationStack {
            ZStack {
                palette.background.ignoresSafeArea()
                List(results, id: \.userId) { p in
                    Button {
                        add(p.userId)
                    } label: {
                        HStack(spacing: 12) {
                            Avatar(id: p.userId, name: p.displayName ?? p.userId, size: 42)
                            Text(p.displayName ?? p.username ?? p.userId).foregroundStyle(palette.textPrimary)
                            Spacer()
                            if adding { ProgressView() }
                        }
                    }
                    .disabled(adding)
                    .listRowBackground(Color.clear)
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .onChange(of: query) { _, q in scheduleSearch(q) }
            }
            .toolbar(.hidden, for: .navigationBar)
            .safeAreaInset(edge: .top) {
                // Шторка без чёлки-safe-area: опускаем шапку от скруглённого верха.
                VStack(spacing: 0) {
                    FloatingHeader(
                        title: (messaging.groups.info(groupId)?.isChannel ?? false)
                            ? "Добавить подписчика" : "Добавить участника",
                        large: false,
                        leading: AnyView(Button("Отмена") { dismiss() }.foregroundStyle(palette.accent)),
                        withBackground: false
                    )
                    FloatingSearchBar(prompt: "Имя или @username", text: $query)
                }
                .padding(.top, 14)
                .background(SystemBarBackground().ignoresSafeArea(edges: .top))
            }
        }
    }

    private func scheduleSearch(_ q: String) {
        searchTask?.cancel()
        let t = q.trimmingCharacters(in: .whitespaces)
        guard t.count >= 2 else { results = []; return }
        searchTask = Task {
            try? await Task.sleep(nanoseconds: 300_000_000)
            guard !Task.isCancelled else { return }
            results = (try? await session.core.searchUsers(t)) ?? []
        }
    }

    private func add(_ userId: String) {
        adding = true
        Task {
            try? await messaging.groups.addMember(groupId: groupId, userId: userId)
            adding = false
            onAdded()
            dismiss()
        }
    }
}

struct RenameGroupSheet: View {
    @Binding var name: String
    @Binding var description: String
    let isChannel: Bool
    var onSave: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section(isChannel ? "Название канала" : "Название группы") { TextField("Название", text: $name) }
                Section("Описание") { TextField("Описание (необязательно)", text: $description, axis: .vertical).lineLimit(2...5) }
            }
            .toolbar(.hidden, for: .navigationBar)
            .safeAreaInset(edge: .top) {
                FloatingHeader(
                    title: "Изменить",
                    large: false,
                    leading: AnyView(Button("Отмена") { dismiss() }),
                    trailing: AnyView(Button("Готово") { onSave(); dismiss() }
                        .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty))
                )
            }
        }
    }
}
import SwiftUI

// Квадратная миниатюра для сетки общих медиа. Фото декодируется через кэш
// MediaStore; для видео (превью-кадр вытащить нечем без загрузки) — плашка с плеем.
private struct SharedMediaThumb: View {
    let payload: Wire.Payload
    @State private var image: UIImage?
    @Environment(\.palette) private var palette

    var body: some View {
        GeometryReader { geo in
            ZStack {
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .frame(width: geo.size.width, height: geo.size.height)
                } else {
                    palette.surfaceElevated
                    Image(systemName: payload.mediaKind == .video ? "play.circle.fill" : "photo")
                        .font(.system(size: 22))
                        .foregroundStyle(palette.textSecondary)
                }
                if payload.mediaKind == .video, image != nil {
                    Image(systemName: "play.circle.fill")
                        .font(.system(size: 26))
                        .foregroundStyle(.white.opacity(0.9))
                }
            }
        }
        .task {
            guard image == nil, payload.mediaKind == .image, let fid = payload.fileId else { return }
            image = await MediaStore.shared.image(fileId: fid, symKey: payload.symKey ?? "",
                                                  nonce: payload.nonce ?? "", maxPixel: 300)
        }
    }
}

struct UserProfileView: View {
    let userId: String
    @EnvironmentObject var session: Session
    @EnvironmentObject var messaging: Messaging
    @Environment(\.palette) private var palette
    @State private var profile: Profile?

    // Общие медиа из переписки (как в Telegram): Медиа (фото/видео) и Файлы.
    private struct SharedItem: Identifiable {
        let id: String
        let payload: Wire.Payload
    }
    private struct ViewerPayload: Identifiable {
        let id: String
        let payload: Wire.Payload
    }
    @State private var sharedTab = 0
    @State private var mediaItems: [SharedItem] = []
    @State private var fileItems: [SharedItem] = []
    @State private var imageViewer: ViewerPayload?
    @State private var quickLookURL: IdentifiableURL?

    var body: some View {
        ZStack {
            palette.background.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 24) {
                    Avatar(id: userId, name: profile?.displayName ?? userId, size: 100,
                           avatarURL: messaging.avatarURL(userId))
                        .onAppear {
                            messaging.ensureProfile(userId)
                            messaging.refreshStatusEmoji(userId)
                        }
                        .padding(.top, 24)
                    
                    VStack(spacing: 4) {
                        HStack(spacing: 6) {
                            Text(profile?.displayName ?? userId)
                                .font(.title2.weight(.bold))
                                .foregroundStyle(palette.textPrimary)
                            if let status = messaging.statusEmoji(userId) {
                                Text(status).font(.system(size: 22))
                            }
                        }
                        
                        if let u = profile?.username, !u.isEmpty {
                            Text("@\(u)")
                                .font(.subheadline)
                                .foregroundStyle(palette.textSecondary)
                        } else {
                            Text(userId)
                                .font(.caption)
                                .foregroundStyle(palette.textSecondary)
                        }
                    }
                    
                    // Buttons
                    HStack(spacing: 16) {
                        profileButton(icon: "message.fill", title: "Сообщение") {
                            // already in chat or could pop back
                        }
                        profileButton(icon: "phone.fill", title: "Звонок") {
                            messaging.calls.startCall(peer: userId, video: false)
                        }
                        profileButton(icon: "video.fill", title: "Видео") {
                            messaging.calls.startCall(peer: userId, video: true)
                        }
                    }
                    .padding(.horizontal, 24)

                    keyVerificationRow
                        .padding(.horizontal, 24)

                    if !mediaItems.isEmpty || !fileItems.isEmpty {
                        sharedMediaSection
                    }

                    Spacer()
                }
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .safeAreaInset(edge: .top) { FloatingHeader(title: "Профиль", large: false) }
        .fullScreenCover(item: $imageViewer) { item in
            FullScreenImageView(payload: item.payload)
        }
        .fullScreenCover(item: $quickLookURL) { item in QuickLookCover(url: item.url) }
        .task {
            profile = try? await session.core.getProfile(userId)
            await loadSharedMedia()
        }
    }

    // MARK: - Проверка ключа (TOFU)

    @State private var keyPin: KeyPin?
    @State private var showKeyVerification = false

    private var keyVerificationRow: some View {
        Button { showKeyVerification = true } label: {
            HStack(spacing: 12) {
                Image(systemName: (keyPin?.verified ?? false) ? "checkmark.shield.fill" : "shield.lefthalf.filled")
                    .font(.system(size: 20))
                    .foregroundStyle((keyPin?.verified ?? false) ? palette.readTick : palette.accent)
                    .frame(width: 36, height: 36)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Проверка ключа шифрования")
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(palette.textPrimary)
                    Text((keyPin?.verified ?? false) ? "Подтверждён" : "Сравни отпечаток с собеседником")
                        .font(.caption)
                        .foregroundStyle(palette.textSecondary)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(palette.textSecondary)
            }
            .padding(.horizontal, 14).padding(.vertical, 12)
            .background(palette.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .task { keyPin = try? await session.core.keyPin(userId) }
        .fullScreenCover(isPresented: $showKeyVerification, onDismiss: {
            Task { keyPin = try? await session.core.keyPin(userId) }
        }) {
            KeyVerificationView(peerId: userId, peerName: profile?.displayName ?? userId)
                .environmentObject(session)
        }
    }

    // MARK: - Общие медиа

    private func loadSharedMedia() async {
        let page = (try? await session.core.messages(peer: userId.lowercased(), beforeTs: 0, limit: 500)) ?? []
        var media: [SharedItem] = []
        var files: [SharedItem] = []
        for m in page.reversed() where !m.deleted {   // свежие сверху
            guard let p = Wire.parse(m.payloadJson), p.type == "media" else { continue }
            switch p.mediaKind {
            case .image, .video: media.append(SharedItem(id: m.id, payload: p))
            case .file: files.append(SharedItem(id: m.id, payload: p))
            case .voice, .videoNote: break
            }
        }
        mediaItems = media
        fileItems = files
    }

    private var sharedMediaSection: some View {
        VStack(spacing: 12) {
            Picker("", selection: $sharedTab) {
                Text("Медиа \(mediaItems.count)").tag(0)
                Text("Файлы \(fileItems.count)").tag(1)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16)

            if sharedTab == 0 {
                if mediaItems.isEmpty {
                    sharedEmpty("Нет общих фото и видео")
                } else {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 2), count: 3), spacing: 2) {
                        ForEach(mediaItems) { item in
                            SharedMediaThumb(payload: item.payload)
                                .aspectRatio(1, contentMode: .fill)
                                .clipped()
                                .contentShape(Rectangle())
                                .onTapGesture { openMedia(item) }
                        }
                    }
                }
            } else {
                if fileItems.isEmpty {
                    sharedEmpty("Нет общих файлов")
                } else {
                    VStack(spacing: 0) {
                        ForEach(fileItems) { item in
                            fileRow(item)
                            if item.id != fileItems.last?.id {
                                Rectangle().fill(palette.divider).frame(height: 0.5).padding(.leading, 60)
                            }
                        }
                    }
                    .background(palette.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .padding(.horizontal, 16)
                }
            }
        }
        .padding(.top, 8)
    }

    private func sharedEmpty(_ text: String) -> some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(palette.textSecondary)
            .padding(.vertical, 24)
    }

    private func openMedia(_ item: SharedItem) {
        if item.payload.mediaKind == .image {
            imageViewer = ViewerPayload(id: item.id, payload: item.payload)
        } else {
            // Видео: скачиваем/расшифровываем во временный файл и играем через QuickLook.
            Task {
                guard let fid = item.payload.fileId else { return }
                if let url = await MediaStore.shared.materialize(
                    fileId: fid, fileName: item.payload.fileName ?? "\(fid).mp4",
                    symKey: item.payload.symKey ?? "", nonce: item.payload.nonce ?? "") {
                    quickLookURL = IdentifiableURL(url: url)
                }
            }
        }
    }

    private func fileRow(_ item: SharedItem) -> some View {
        Button {
            Task {
                guard let fid = item.payload.fileId else { return }
                if let url = await MediaStore.shared.materialize(
                    fileId: fid, fileName: item.payload.fileName ?? "file",
                    symKey: item.payload.symKey ?? "", nonce: item.payload.nonce ?? "") {
                    quickLookURL = IdentifiableURL(url: url)
                }
            }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "doc.fill")
                    .font(.system(size: 20))
                    .foregroundStyle(palette.accent)
                    .frame(width: 36, height: 36)
                    .background(palette.accent.opacity(0.14), in: RoundedRectangle(cornerRadius: 9, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.payload.fileName ?? "Файл")
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(palette.textPrimary)
                        .lineLimit(1)
                    if let size = item.payload.fileSize {
                        Text(ByteCountFormatter.string(fromByteCount: size, countStyle: .file))
                            .font(.caption)
                            .foregroundStyle(palette.textSecondary)
                    }
                }
                Spacer()
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
    
    private func profileButton(icon: String, title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Image(systemName: icon)
                    .font(.system(size: 24))
                Text(title)
                    .font(.caption)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .background(palette.surface, in: RoundedRectangle(cornerRadius: 12))
            .foregroundStyle(palette.accent)
        }
        .buttonStyle(.squish)
    }
}
