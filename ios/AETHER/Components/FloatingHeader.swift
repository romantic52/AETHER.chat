import SwiftUI

// Плавающая шапка экрана вместо системного навбара. На iOS 27 beta
// toolbarBackground(.hidden) не убирает стеклянную подложку навбара,
// поэтому навбар прячем целиком (.toolbar(.hidden, for: .navigationBar))
// и рисуем заголовок «в воздухе» поверх EdgeDim-градиента — та же схема,
// что у кастомной шапки экрана «Чаты».
struct FloatingHeader: View {
    @Environment(\.palette) private var palette
    var title: LocalizedStringKey
    var large = true
    var leading: AnyView? = nil
    var trailing: AnyView? = nil
    /// false — когда шапка вложена в общий контейнер (например, с поиск-баром),
    /// который сам рисует EdgeDim, чтобы градиент не задваивался.
    var withBackground = true

    var body: some View {
        HStack(spacing: 12) {
            if let leading { leading }
            Text(title)
                .font(large ? .system(size: 30, weight: .bold) : .system(size: 17, weight: .semibold))
                .foregroundStyle(palette.textPrimary)
                .lineLimit(1)
            Spacer()
            if let trailing { trailing }
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 10)
        .background {
            if withBackground {
                EdgeDim(edge: .top).ignoresSafeArea(edges: .top)
            }
        }
    }
}

// Круглая кнопка «назад/закрыть» для плавающей шапки.
struct HeaderIconButton: View {
    @Environment(\.palette) private var palette
    var icon: String
    /// 44pt — минимальная цель нажатия по требованиям Apple. Меньше делать
    /// нельзя: с системной обводкой кнопок сразу видно, что стекло уже
    /// самой кнопки, и она выглядит мелкой.
    var size: CGFloat = 44
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(palette.textPrimary)
                .frame(width: size, height: size)
                // interactive-стекло НЕЛЬЗЯ внутри Button: на iOS 26+ оно вешает
                // собственные жесты и съедает тап — кнопка перестаёт срабатывать.
                .liquidGlass(Circle())
                .contentShape(Circle())
        }
        .buttonStyle(.squish)
    }
}

// Строка поиска для экранов с плавающей шапкой (замена .searchable,
// который живёт в системном навбаре и тянет за собой его подложку).
struct FloatingSearchBar: View {
    @Environment(\.palette) private var palette
    var prompt: LocalizedStringKey = "Поиск"
    @Binding var text: String

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(palette.textSecondary)
                .font(.system(size: 16))
            TextField(prompt, text: $text)
                .foregroundStyle(palette.textPrimary)
                .tint(palette.accent)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)
            if !text.isEmpty {
                Button { text = "" } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(palette.textSecondary)
                }
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .liquidGlass(Capsule())
        .padding(.horizontal, 16)
        .padding(.bottom, 12)
    }
}
