import SwiftUI

/// Слой Liquid Glass для AETHER.
///
/// На iOS 26+ используется НАСТОЯЩИЙ системный Liquid Glass (`glassEffect`,
/// `GlassEffectContainer`, `buttonStyle(.glass)`) — тот самый материал, что в
/// iOS 26. На более старых системах прозрачно деградирует до `.ultraThinMaterial`,
/// чтобы приложение оставалось рабочим и визуально близким.
///
/// Требует сборки Xcode 26+ (iOS 26 SDK), иначе символы `glassEffect` недоступны.

// MARK: - Базовый стеклянный фон под произвольную форму

struct LiquidGlassBackground<S: Shape>: ViewModifier {
    var shape: S
    var tinted: Bool = false
    var interactive: Bool = false

    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.glassEffect(glass, in: shape)
        } else {
            content
                .background(.ultraThinMaterial, in: shape)
                .overlay(shape.stroke(Brand.border, lineWidth: 0.5))
        }
    }

    @available(iOS 26.0, *)
    private var glass: Glass {
        var g: Glass = .regular
        if tinted { g = g.tint(Brand.glassTint.opacity(0.25)) }
        if interactive { g = g.interactive() }
        return g
    }
}

extension View {
    /// Стеклянная подложка под любую форму (по умолчанию — капсула).
    func liquidGlass<S: Shape>(
        in shape: S = Capsule(),
        tinted: Bool = false,
        interactive: Bool = false
    ) -> some View {
        modifier(LiquidGlassBackground(shape: shape, tinted: tinted, interactive: interactive))
    }

    /// Стеклянная карточка со скруглением.
    func glassCard(cornerRadius: CGFloat = 22) -> some View {
        modifier(LiquidGlassBackground(shape: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)))
    }
}

// MARK: - Контейнер стекла (объединяет соседние стеклянные элементы)

/// На iOS 26 группирует стеклянные вью, чтобы они «перетекали» друг в друга
/// (морфинг капель). На старых системах — обычный layout-проход.
struct GlassGroup<Content: View>: View {
    var spacing: CGFloat = 12
    @ViewBuilder var content: () -> Content

    var body: some View {
        if #available(iOS 26.0, *) {
            GlassEffectContainer(spacing: spacing) { content() }
        } else {
            content()
        }
    }
}

// MARK: - Стеклянная кнопка

struct GlassButtonStyle: ButtonStyle {
    var prominent: Bool = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 16, weight: .semibold))
            .foregroundStyle(prominent ? Color.black : Brand.textPrimary)
            .padding(.vertical, 14)
            .padding(.horizontal, 24)
            .frame(maxWidth: .infinity)
            .modifier(GlassButtonSurface(prominent: prominent))
            .opacity(configuration.isPressed ? 0.7 : 1)
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(.spring(duration: 0.25), value: configuration.isPressed)
    }
}

private struct GlassButtonSurface: ViewModifier {
    var prominent: Bool
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.glassEffect(
                prominent ? Glass.regular.tint(.white).interactive()
                          : Glass.regular.interactive(),
                in: Capsule()
            )
        } else {
            content
                .background(prominent ? AnyShapeStyle(Color.white) : AnyShapeStyle(.ultraThinMaterial),
                            in: Capsule())
                .overlay(Capsule().stroke(Brand.border, lineWidth: 0.5))
        }
    }
}

extension ButtonStyle where Self == GlassButtonStyle {
    static var glass: GlassButtonStyle { GlassButtonStyle(prominent: false) }
    static var glassProminent: GlassButtonStyle { GlassButtonStyle(prominent: true) }
}
