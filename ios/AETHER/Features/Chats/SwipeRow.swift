import SwiftUI

// Действие свайпа строки (замена List.swipeActions для LazyVStack).
struct RowAction: Identifiable {
    let id = UUID()
    let label: String
    let icon: String
    let tint: Color
    let action: () -> Void
}

// Свайп-строка для LazyVStack. Ключевые решения против прошлых багов:
//  • simultaneousGesture + приоритет горизонтали → вертикальный скролл списка
//    не конфликтует со свайпом (раньше жест «застревал»);
//  • .clipped() по границам строки → кнопки не вылезают за пределы чата;
//  • settled-состояние → строку можно тянуть повторно после открытия;
//  • полный свайп с края запускает первое действие стороны.
struct SwipeRow<Content: View>: View {
    var leading: [RowAction] = []
    var trailing: [RowAction] = []
    var onTap: () -> Void
    @ViewBuilder var content: Content
    @Environment(\.palette) private var palette

    @State private var offset: CGFloat = 0
    @State private var settled: CGFloat = 0
    @State private var horizontal = false
    private let bw: CGFloat = 74

    private var maxT: CGFloat { -bw * CGFloat(trailing.count) }
    private var maxL: CGFloat { bw * CGFloat(leading.count) }

    var body: some View {
        ZStack {
            HStack(spacing: 0) {
                ForEach(leading) { actionButton($0) }
                Spacer(minLength: 0)
                ForEach(trailing) { actionButton($0) }
            }
            content
                .background(palette.background)
                .offset(x: offset)
        }
        .clipped()
        .contentShape(Rectangle())
        .onTapGesture { if offset != 0 { close() } else { onTap() } }
        .simultaneousGesture(dragGesture)
    }

    private func actionButton(_ a: RowAction) -> some View {
        Button {
            a.action()
            offset = 0; settled = 0
        } label: {
            VStack(spacing: 4) {
                Image(systemName: a.icon).font(.system(size: 19, weight: .semibold))
                Text(a.label).font(.system(size: 11, weight: .medium)).lineLimit(1)
            }
            .foregroundStyle(.white)
            .frame(width: bw)
            .frame(maxHeight: .infinity)
            .background(a.tint)
        }
        .buttonStyle(.plain)
    }

    private var dragGesture: some Gesture {
        DragGesture(minimumDistance: 10)
            .onChanged { v in
                if !horizontal {
                    // Активируем свайп только на явно горизонтальном движении —
                    // диагональ и вертикаль отдаём скроллу.
                    guard abs(v.translation.width) > abs(v.translation.height) * 1.4 else { return }
                    horizontal = true
                }
                offset = max(maxT - 28, min(maxL + 28, settled + v.translation.width))
            }
            .onEnded { v in
                defer { horizontal = false }
                guard horizontal else { return }
                let w = UIScreen.main.bounds.width
                let end = settled + v.translation.width
                withAnimation(.spring(response: 0.3, dampingFraction: 0.82)) {
                    if v.translation.width < -w * 0.55, let a = trailing.first {
                        a.action(); offset = 0; settled = 0
                    } else if v.translation.width > w * 0.55, let a = leading.first {
                        a.action(); offset = 0; settled = 0
                    } else if end < maxT * 0.55 {
                        offset = maxT; settled = maxT
                    } else if end > maxL * 0.55 {
                        offset = maxL; settled = maxL
                    } else {
                        offset = 0; settled = 0
                    }
                }
            }
    }

    private func close() {
        withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) { offset = 0; settled = 0 }
    }
}
