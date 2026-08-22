import SwiftUI

/// Правило отбора чатов в папку. Кроме ручного списка есть «умные» правила —
/// как в Telegram, где папку можно собрать не перечислением, а условием.
enum FolderRule: String, Codable, CaseIterable {
    case custom      // ручной список
    case unread      // только непрочитанные
    case personal    // личная переписка
    case groups      // группы и каналы

    var title: LocalizedStringKey {
        switch self {
        case .custom: return "Выбранные чаты"
        case .unread: return "Непрочитанные"
        case .personal: return "Личные"
        case .groups: return "Группы"
        }
    }
}

/// Чем сортировать список внутри папки.
enum FolderSort: String, Codable, CaseIterable {
    case manual      // как расставлено вручную, дальше по времени
    case recent      // по времени последнего сообщения
    case unread      // непрочитанные сверху
    case alphabet    // по названию

    var title: LocalizedStringKey {
        switch self {
        case .manual: return "Как расставлено"
        case .recent: return "По времени"
        case .unread: return "Непрочитанные сверху"
        case .alphabet: return "По названию"
        }
    }

    var icon: String {
        switch self {
        case .manual: return "hand.draw"
        case .recent: return "clock"
        case .unread: return "circle.badge.fill"
        case .alphabet: return "textformat.abc"
        }
    }
}

struct ChatFolder: Identifiable, Codable, Hashable {
    var id = UUID()
    var name: String
    var emoji: String = ""
    var rule: FolderRule = .custom
    /// Для правила .custom — идентификаторы выбранных чатов.
    var peers: [String] = []
    /// Ключевые слова, как в Android-клиенте: чат попадает в папку, если его
    /// имя или идентификатор содержит хотя бы одно из включающих, и не содержит
    /// ни одного из исключающих. Работают поверх основного правила.
    var includeKeywords: [String] = []
    var excludeKeywords: [String] = []
    /// Порядок внутри папки. Optional в хранении — иначе папки, сохранённые до
    /// появления поля, вообще не прочитались бы, и пользователь потерял бы их все.
    var sortRaw: FolderSort?

    var sort: FolderSort {
        get { sortRaw ?? .manual }
        set { sortRaw = newValue }
    }

    var label: String { emoji.isEmpty ? name : "\(emoji) \(name)" }

    /// Разбор вручную и по одному полю: добавление нового поля не должно
    /// обнулять уже сохранённые папки. Синтезированный декодер требует ВСЕ
    /// необязательные-на-вид ключи и падает на старом JSON целиком — вместе с
    /// массивом папок.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(UUID.self, forKey: .id) ?? UUID()
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        emoji = try c.decodeIfPresent(String.self, forKey: .emoji) ?? ""
        rule = try c.decodeIfPresent(FolderRule.self, forKey: .rule) ?? .custom
        peers = try c.decodeIfPresent([String].self, forKey: .peers) ?? []
        includeKeywords = try c.decodeIfPresent([String].self, forKey: .includeKeywords) ?? []
        excludeKeywords = try c.decodeIfPresent([String].self, forKey: .excludeKeywords) ?? []
        sortRaw = try c.decodeIfPresent(FolderSort.self, forKey: .sortRaw)
    }

    init(id: UUID = UUID(), name: String, emoji: String = "", rule: FolderRule = .custom,
         peers: [String] = [], includeKeywords: [String] = [], excludeKeywords: [String] = [],
         sortRaw: FolderSort? = nil) {
        self.id = id
        self.name = name
        self.emoji = emoji
        self.rule = rule
        self.peers = peers
        self.includeKeywords = includeKeywords
        self.excludeKeywords = excludeKeywords
        self.sortRaw = sortRaw
    }
}

/// Хранилище папок. Локальное: папки — это способ смотреть на свой список,
/// серверу о них знать незачем.
@MainActor
final class ChatFoldersStore: ObservableObject {
    static let shared = ChatFoldersStore()
    private let key = "chatFolders"
    private let pinsKey = "chatFolderPins"

    @Published private(set) var folders: [ChatFolder] = []
    /// Закрепы ВНУТРИ папки: id папки → peerId в порядке закрепления (новый
    /// сверху). Отдельно от общего закрепа в базе ядра, потому что смысл разный:
    /// чат может висеть первым в рабочей папке и лежать по времени в общем
    /// списке. Хранение локальное — как и сами папки.
    @Published private(set) var folderPins: [String: [String]] = [:]

    private init() {
        if let data = UserDefaults.standard.data(forKey: key),
           let saved = try? JSONDecoder().decode([ChatFolder].self, from: data) {
            folders = saved
        }
        if let data = UserDefaults.standard.data(forKey: pinsKey),
           let saved = try? JSONDecoder().decode([String: [String]].self, from: data) {
            folderPins = saved
        }
    }

    private func savePins() {
        guard let data = try? JSONEncoder().encode(folderPins) else { return }
        UserDefaults.standard.set(data, forKey: pinsKey)
    }

    func isPinned(_ peerId: String, in folder: ChatFolder) -> Bool {
        folderPins[folder.id.uuidString]?.contains(peerId) ?? false
    }

    /// Позиция в закрепах папки; Int.max — не закреплён.
    func pinRank(_ peerId: String, in folder: ChatFolder) -> Int {
        folderPins[folder.id.uuidString]?.firstIndex(of: peerId) ?? Int.max
    }

    func togglePin(_ peerId: String, in folder: ChatFolder) {
        let key = folder.id.uuidString
        var list = folderPins[key] ?? []
        if let i = list.firstIndex(of: peerId) { list.remove(at: i) }
        else { list.insert(peerId, at: 0) }   // новый закреп — выше прежних
        folderPins[key] = list.isEmpty ? nil : list
        savePins()
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(folders) else { return }
        UserDefaults.standard.set(data, forKey: key)
    }

    func upsert(_ folder: ChatFolder) {
        if let i = folders.firstIndex(where: { $0.id == folder.id }) { folders[i] = folder }
        else { folders.append(folder) }
        save()
    }

    func remove(_ folder: ChatFolder) {
        folders.removeAll { $0.id == folder.id }
        // Закрепы удалённой папки больше ни на что не влияют, но остались бы
        // в UserDefaults навсегда.
        folderPins[folder.id.uuidString] = nil
        savePins()
        save()
    }

    func move(from: IndexSet, to: Int) {
        folders.move(fromOffsets: from, toOffset: to)
        save()
    }

    /// Подходит ли чат под папку. nil — папка «Все».
    func matches(_ chat: Chat, folder: ChatFolder?, isGroup: Bool) -> Bool {
        guard let folder else { return true }

        let byRule: Bool
        switch folder.rule {
        case .custom: byRule = folder.peers.contains(chat.peerId)
        case .unread: byRule = chat.unread > 0
        case .personal: byRule = !isGroup
        case .groups: byRule = isGroup
        }
        guard byRule else { return false }

        // Ключевые слова — поверх правила, по имени и идентификатору чата.
        guard !folder.includeKeywords.isEmpty || !folder.excludeKeywords.isEmpty else { return true }
        let haystack = (chat.title + " " + chat.peerId).lowercased()
        if folder.excludeKeywords.contains(where: { haystack.contains($0.lowercased()) }) { return false }
        if folder.includeKeywords.isEmpty { return true }
        return folder.includeKeywords.contains { haystack.contains($0.lowercased()) }
    }
}

/// Полоса папок — отдельной строкой под заголовком, одной широкой стеклянной
/// капсулой, как в Telegram. Внутри чипы: название, эмодзи и счётчик; у
/// выбранной папки светлая подложка. Долгое нажатие на чипе — меню правки.
struct FolderBar: View {
    @ObservedObject var store: ChatFoldersStore
    @Binding var selected: ChatFolder?
    var counts: (ChatFolder?) -> Int
    var onEdit: (ChatFolder?) -> Void
    var onOrder: () -> Void

    @Environment(\.palette) private var palette

    var body: some View {
        // Папок нет — полосы тоже нет: одинокая «Все» занимала строку впустую.
        if !store.folders.isEmpty {
            ScrollViewReader { proxy in
                ScrollView(.horizontal, showsIndicators: false) {
                    // Чипы РАСПРЕДЕЛЕНЫ по ширине капсулы, а не сбиты влево.
                    // Распорки растягиваются, пока содержимое помещается; когда
                    // папок много — полоса прокручивается, и распорки схлопываются.
                    HStack(spacing: 0) {
                        chip(nil).contextMenu { menu(for: nil) }.id(allChipId)
                        ForEach(store.folders) { folder in
                            Spacer(minLength: 10)
                            chip(folder).contextMenu { menu(for: folder) }.id(folder.id)
                        }
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .frame(minWidth: UIScreen.main.bounds.width - 32)
                }
                .scrollClipDisabled()
                .liquidGlass(Capsule())
                .padding(.horizontal, 16)
                // Выбранная папка подкручивается в видимую часть: иначе при
                // переключении из меню чип мог остаться за краем полосы.
                .onChange(of: selected?.id) { _, id in
                    withAnimation(.easeInOut(duration: 0.25)) {
                        proxy.scrollTo(id ?? allChipId, anchor: .center)
                    }
                }
            }
        }
    }

    private var allChipId: UUID { UUID(uuidString: "00000000-0000-0000-0000-000000000000")! }

    @ViewBuilder
    private func menu(for folder: ChatFolder?) -> some View {
        if let folder {
            Button { onEdit(folder) } label: { Label("Изменить", systemImage: "pencil") }
            Button(role: .destructive) {
                if selected?.id == folder.id { selected = nil }
                store.remove(folder)
            } label: { Label("Удалить папку", systemImage: "trash") }
        }
        Button { onEdit(nil) } label: { Label("Новая папка", systemImage: "folder.badge.plus") }
        if !store.folders.isEmpty {
            Button { onOrder() } label: { Label("Порядок папок", systemImage: "list.bullet.indent") }
        }
    }

    @ViewBuilder
    private func chip(_ folder: ChatFolder?) -> some View {
        let isOn = selected?.id == folder?.id
        let count = counts(folder)
        Button {
            withAnimation(.easeInOut(duration: 0.2)) { selected = folder }
        } label: {
            HStack(spacing: 6) {
                Text(folder?.label ?? "Все")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(palette.textPrimary)
                if count > 0 {
                    Text("\(count)")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(isOn ? palette.onAccent : palette.textSecondary)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 2)
                        .background(isOn ? palette.accent : palette.textPrimary.opacity(0.12),
                                    in: Capsule())
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 7)
            .background {
                if isOn { Capsule().fill(palette.textPrimary.opacity(0.14)) }
            }
        }
        .buttonStyle(.plain)
    }
}

/// Создание и правка папки.
struct FolderEditor: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.palette) private var palette
    @ObservedObject var store: ChatFoldersStore
    /// nil — создаём новую.
    var existing: ChatFolder?
    var allChats: [Chat]
    var titleFor: (Chat) -> String

    @State private var name = ""
    @State private var emoji = ""
    @State private var rule: FolderRule = .custom
    @State private var peers: Set<String> = []
    @State private var includeText = ""
    @State private var excludeText = ""
    @State private var sort: FolderSort = .manual

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Название", text: $name)
                    TextField("Эмодзи (необязательно)", text: $emoji)
                        .onChange(of: emoji) { _, v in
                            // Одна картинка, не строка: иначе полоса папок разъедется.
                            if v.count > 1 { emoji = String(v.prefix(1)) }
                        }
                }
                Section("Что показывать") {
                    Picker("Правило", selection: $rule) {
                        ForEach(FolderRule.allCases, id: \.self) { Text($0.title).tag($0) }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                }
                Section("Порядок чатов") {
                    Picker("Сортировка", selection: $sort) {
                        ForEach(FolderSort.allCases, id: \.self) { Text($0.title).tag($0) }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                }
                Section {
                    TextField("Включать: слова через запятую", text: $includeText)
                        .textInputAutocapitalization(.never)
                    TextField("Исключать: слова через запятую", text: $excludeText)
                        .textInputAutocapitalization(.never)
                } header: {
                    Text("Ключевые слова")
                } footer: {
                    Text("Сверяются с именем и идентификатором чата. Работают поверх правила выше: например «channel_» соберёт каналы.")
                }

                if rule == .custom {
                    Section("Чаты") {
                        ForEach(allChats, id: \.peerId) { chat in
                            Button {
                                if peers.contains(chat.peerId) { peers.remove(chat.peerId) }
                                else { peers.insert(chat.peerId) }
                            } label: {
                                HStack {
                                    Text(titleFor(chat)).foregroundStyle(palette.textPrimary)
                                    Spacer()
                                    if peers.contains(chat.peerId) {
                                        Image(systemName: "checkmark").foregroundStyle(palette.accent)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle(existing == nil ? "Новая папка" : "Папка")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Отмена") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Готово") {
                        var folder = existing ?? ChatFolder(name: "")
                        folder.name = name.trimmingCharacters(in: .whitespaces)
                        folder.emoji = emoji
                        folder.rule = rule
                        folder.peers = Array(peers)
                        folder.includeKeywords = Self.words(includeText)
                        folder.excludeKeywords = Self.words(excludeText)
                        folder.sort = sort
                        if !folder.name.isEmpty || !folder.emoji.isEmpty { store.upsert(folder) }
                        dismiss()
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .onAppear {
                guard let existing else { return }
                name = existing.name
                emoji = existing.emoji
                rule = existing.rule
                peers = Set(existing.peers)
                includeText = existing.includeKeywords.joined(separator: ", ")
                excludeText = existing.excludeKeywords.joined(separator: ", ")
                sort = existing.sort
            }
        }
    }
}


extension FolderEditor {
    /// «a, b ,, c» → ["a", "b", "c"].
    static func words(_ text: String) -> [String] {
        text.split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
    }
}

/// Порядок папок: перетаскиванием, как в Telegram. Отдельным листом, потому что
/// в самой полосе для этого нет места.
struct FolderOrderView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var store: ChatFoldersStore

    var body: some View {
        NavigationStack {
            List {
                ForEach(store.folders) { folder in
                    HStack {
                        Text(folder.label)
                        Spacer()
                        Text(folder.rule.title)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                .onMove { from, to in store.move(from: from, to: to) }
                .onDelete { idx in
                    idx.map { store.folders[$0] }.forEach { store.remove($0) }
                }
            }
            .environment(\.editMode, .constant(.active))
            .navigationTitle("Порядок папок")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Готово") { dismiss() } }
            }
        }
    }
}
