import SwiftUI

// Действие свайпа строки списка (замена List.swipeActions для LazyVStack).
struct RowAction: Identifiable {
    let id = UUID()
    let label: String
    let icon: String
    let tint: Color
    var role: ButtonRole? = nil
    let action: () -> Void
}

// Кастомная свайп-строка: List.swipeActions недоступен в LazyVStack, а он нужен,
// чтобы получить анимацию «полёта» закрепления (List игнорирует zIndex).
// Свайп влево открывает trailing-кнопки, вправо — leading; полный свайп с края
// запускает первое действие стороны. Тап по открытой строке её закрывает.
struct SwipeRow<Content: View>: View {
    var leading: [RowAction] = []
    var trailing: [RowAction] = []
    var enabled: Bool = true
    var onTap: () -> Void
    @ViewBuilder var content: Content

    @State private var offset: CGFloat = 0
    private let btn: CGFloat = 78

    private var maxTrailing: CGFloat { -btn * CGFloat(trailing.count) }
    private var maxLeading: CGFloat { btn * CGFloat(leading.count) }

    var body: some View {
        ZStack(alignment: .leading) {
            // Кнопки под контентом. Trailing — у правого края, leading — у левого.
            HStack(spacing: 0) {
                ForEach(leading) { a in button(a) }
                Spacer(minLength: 0)
                ForEach(trailing) { a in button(a) }
            }
            content
                .background(Color(.clear))
                .offset(x: offset)
                .contentShape(Rectangle())
                .onTapGesture {
                    if offset != 0 { close() } else { onTap() }
                }
        }
        .clipped()
        .gesture(enabled ? drag : nil)
    }

    private func button(_ a: RowAction) -> some View {
        Button {
            a.action()
            close()
        } label: {
            VStack(spacing: 4) {
                Image(systemName: a.icon).font(.system(size: 18, weight: .semibold))
                Text(a.label).font(.system(size: 11, weight: .medium)).lineLimit(1)
            }
            .foregroundStyle(.white)
            .frame(width: btn)
            .frame(maxHeight: .infinity)
            .background(a.tint)
        }
        .buttonStyle(.plain)
    }

    @State private var settledOffset: CGFloat = 0

    private var drag: some Gesture {
        DragGesture(minimumDistance: 14)
            .onChanged { v in
                // Горизонтальный приоритет: вертикаль отдаём скроллу.
                guard abs(v.translation.width) > abs(v.translation.height) else { return }
                let next = settledOffset + v.translation.width
                offset = min(maxLeading + 40, max(maxTrailing - 40, next))
            }
            .onEnded { v in
                let w = UIScreen.main.bounds.width
                withAnimation(.spring(response: 0.32, dampingFraction: 0.86)) {
                    if v.translation.width < -w * 0.5, let first = trailing.first {
                        first.action(); offset = 0
                    } else if v.translation.width > w * 0.5, let first = leading.first {
                        first.action(); offset = 0
                    } else if offset < maxTrailing * 0.5 {
                        offset = maxTrailing
                    } else if offset > maxLeading * 0.5 {
                        offset = maxLeading
                    } else {
                        offset = 0
                    }
                    settledOffset = offset
                }
            }
    }

    private func close() {
        withAnimation(.spring(response: 0.3, dampingFraction: 0.88)) { offset = 0; settledOffset = 0 }
    }
}
