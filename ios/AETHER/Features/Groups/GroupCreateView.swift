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
                FloatingHeader(
                    title: step == .type ? "Новый чат" : (isChannel ? "Новый канал" : "Новая группа"),
                    large: false,
                    leading: AnyView(Button("Отмена") { dismiss() }.foregroundStyle(palette.accent)),
                    trailing: step == .details ? AnyView(Button("Создать") { create() }
                        .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || creating)
                        .foregroundStyle(palette.accent)) : nil
                )
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
                }
                .padding(12)
                .background(palette.surface, in: RoundedRectangle(cornerRadius: 10))
                .padding(.horizontal, 16)
                .padding(.bottom, 12)
            } else {
                Text("Пригласительная ссылка будет создана автоматически")
                    .font(.caption)
                    .foregroundStyle(palette.textSecondary)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 12)
            }

            if !selected.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(selected, id: \.userId) { p in chip(p) }
                    }.padding(.horizontal, 16)
                }
                .padding(.bottom, 8)
            }

            List {
                if let error {
                    Text(error).font(.footnote).foregroundStyle(palette.danger).listRowBackground(Color.clear)
                }
                Section {
                    ForEach(results, id: \.userId) { p in
                        Button { toggle(p) } label: {
                            HStack(spacing: 12) {
                                Avatar(id: p.userId, name: p.displayName ?? p.username ?? p.userId, size: 42)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(p.displayName ?? p.username ?? p.userId).foregroundStyle(palette.textPrimary)
                                    if let u = p.username, !u.isEmpty {
                                        Text("@\(u)").font(.caption).foregroundStyle(palette.textSecondary)
                                    }
                                }
                                Spacer()
                                if selected.contains(where: { $0.userId == p.userId }) {
                                    Image(systemName: "checkmark.circle.fill").foregroundStyle(palette.accent)
                                }
                            }
                        }.listRowBackground(Color.clear)
                    }
                } header: {
                    Text(isChannel ? "Подписчики (можно добавить позже)" : "Участники").foregroundStyle(palette.textSecondary)
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .safeAreaInset(edge: .top) { FloatingSearchBar(prompt: "Поиск по @username", text: $query) }
            .onChange(of: query) { _, q in scheduleSearch(q) }
        }
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
        Task {
            do {
                let id = try await messaging.groups.create(name: nm, isChannel: channel, memberIds: members)
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
