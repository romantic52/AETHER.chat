#if DEBUG
import SwiftUI

// DEBUG-стенд анимации закрепления на LazyVStack (в отличие от List он уважает
// zIndex: закрепляемая строка летит ПОВЕРХ соседей, они расступаются).
struct PinDemoView: View {
    @Environment(\.palette) private var palette

    struct Item: Identifiable, Equatable {
        let id: String
        var title: String
        var pinned: Bool
    }

    @State private var items: [Item] = (1...8).map {
        Item(id: "c\($0)", title: "Чат \($0)", pinned: false)
    }
    @State private var pinOrder: [String] = []
    @State private var movingId: String?

    private var ordered: [Item] {
        let pins = items.filter(\.pinned).sorted {
            (pinOrder.firstIndex(of: $0.id) ?? .max) < (pinOrder.firstIndex(of: $1.id) ?? .max)
        }
        return pins + items.filter { !$0.pinned }
    }

    var body: some View {
        ZStack {
            palette.background.ignoresSafeArea()
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(ordered) { item in
                        rowView(item)
                            .zIndex(movingId == item.id ? 1 : 0)
                            .onTapGesture { pin(item) }
                    }
                }
            }
        }
        .onAppear {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                if let c5 = items.first(where: { $0.id == "c5" }) { pin(c5) }
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 6.5) {
                if let c5 = items.first(where: { $0.id == "c5" }) { pin(c5) }
            }
        }
    }

    private func rowView(_ item: Item) -> some View {
        HStack(spacing: 12) {
            Circle().fill(palette.accent.opacity(0.5)).frame(width: 52, height: 52)
                .overlay(Text(item.pinned ? "📌" : "").font(.title3))
            VStack(alignment: .leading, spacing: 3) {
                Text(item.title).font(.headline).foregroundStyle(palette.textPrimary)
                Text("последнее сообщение…").font(.subheadline).foregroundStyle(palette.textSecondary)
            }
            Spacer()
        }
        .padding(.horizontal, 16)
        .frame(height: 76)
        .background(palette.background)   // непрозрачный фон → перекрывает соседей
        .overlay(alignment: .bottom) { Divider().padding(.leading, 80) }
        .contentShape(Rectangle())
    }

    private func pin(_ item: Item) {
        let pinning = !item.pinned
        movingId = item.id
        withAnimation(.spring(response: 2.6, dampingFraction: 1.0)) {
            pinOrder.removeAll { $0 == item.id }
            if pinning { pinOrder.insert(item.id, at: 0) }
            if let idx = items.firstIndex(where: { $0.id == item.id }) {
                items[idx].pinned = pinning
            }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 3.0) { movingId = nil }
    }
}
#endif
