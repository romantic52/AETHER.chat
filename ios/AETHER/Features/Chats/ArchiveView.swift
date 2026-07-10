import SwiftUI

// Папка архива: чаты, свайпнутые в архив из списка. Свайп обратно —
// «Расархивировать» (вернуть в основной список) либо «Удалить».
struct ArchiveView: View {
    @EnvironmentObject var session: Session
    @EnvironmentObject var messaging: Messaging
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss

    @State private var openedPeer: String?
    @State private var query = ""

    private var archived: [Chat] { messaging.chats.filter { $0.archived } }
    private var visible: [Chat] {
        guard !query.isEmpty else { return archived }
        let q = query.lowercased()
        return archived.filter { $0.title.lowercased().contains(q) || $0.peerId.lowercased().contains(q) || $0.lastText.lowercased().contains(q) }
    }
    private var pinned: [Chat] { visible.filter { $0.pinned } }
    private var regular: [Chat] { visible.filter { !$0.pinned } }

    var body: some View {
        ZStack {
            palette.background.ignoresSafeArea()

            List {
                ForEach(pinned, id: \.peerId) { row($0) }
                ForEach(regular, id: \.peerId) { row($0) }
                Color.clear.frame(height: 24).listRowSeparator(.hidden).listRowBackground(Color.clear)
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .overlay { if archived.isEmpty { emptyState } }
        }
        .toolbar(.hidden, for: .navigationBar)
        .swipeBackEnabled()
        .safeAreaInset(edge: .top) {
            VStack(spacing: 0) {
                FloatingHeader(
                    title: "Архив",
                    leading: AnyView(HeaderIconButton(icon: "chevron.left") { dismiss() }),
                    withBackground: false
                )
                FloatingSearchBar(text: $query)
            }
            .background(EdgeDim(edge: .top).ignoresSafeArea(edges: .top))
        }
        .navigationDestination(item: $openedPeer) { peer in
            ChatView(peerId: peer, isGroup: messaging.isGroup(peer))
                .environmentObject(messaging)
        }
    }

    private var emptyState: some View {
        VStack(spacing: 14) {
            Image(systemName: "archivebox")
                .font(.system(size: 52, weight: .thin))
                .foregroundStyle(palette.textSecondary)
            Text("Архив пуст")
                .font(.title3.weight(.semibold))
                .foregroundStyle(palette.textPrimary)
            Text("Смахните чат влево в списке и нажмите «В архив» — он появится здесь")
                .font(.subheadline)
                .foregroundStyle(palette.textSecondary)
                .multilineTextAlignment(.center)
        }
        .padding(40)
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
        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
            Button {
                Task { await messaging.setArchived(chat.peerId, false) }
            } label: { Label("Расархивировать", systemImage: "tray.and.arrow.up.fill") }
                .tint(palette.accent)
            Button(role: .destructive) {
                Task { await messaging.deleteChat(chat.peerId) }
            } label: { Label("Удалить", systemImage: "trash.fill") }
            Button {
                Task { await messaging.setMuted(chat.peerId, !chat.muted) }
            } label: { Label(chat.muted ? "Вкл. звук" : "Без звука", systemImage: chat.muted ? "bell.fill" : "bell.slash.fill") }
                .tint(.orange)
        }
    }
}
