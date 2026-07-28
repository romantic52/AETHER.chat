import SwiftUI

// Форма стеклянной панели с прогрессивной деградацией:
//  • iOS 26+ — родной .glassEffect; interactive-деформация выключена по умолчанию.
//  • iOS 17–18 — стабильный .ultraThinMaterial без непрерывных анимаций.
//  • стекло выключено — сплошной surface-цвет темы (без блюра).
struct LiquidGlass<S: Shape>: ViewModifier {
    let shape: S
    var interactive: Bool = false
    var tintOverride: Color? = nil
    /// false — при выключенном стекле НЕ рисовать surface-подложку (контент как есть):
    /// для элементов со своей заливкой (индикатор таб-бара), где подложка — «серое пятно».
    var surfaceWhenOff: Bool = true
    @EnvironmentObject var appearance: AppearanceSettings
    @Environment(\.palette) private var palette

    func body(content: Content) -> some View {
        if !appearance.glassEnabled {
            if surfaceWhenOff {
                content.background(palette.surface, in: shape)
            } else {
                content
            }
        } else {
            #if compiler(>=6.0)
            if #available(iOS 26.0, *) {
                let base: Glass = appearance.glassStyle == .clear ? .clear : .regular
                let tintColor = tintOverride ?? (appearance.glassTint > 0 ? palette.accent.opacity(appearance.glassTint) : nil)
                let tinted = tintColor != nil ? base.tint(tintColor!) : base
                content
                    .glassEffect(
                        interactive && appearance.glassInteractive ? tinted.interactive() : tinted,
                        in: shape
                    )
                    .clipShape(shape)
                    .overlay(shape.stroke(Color.white.opacity(0.14), lineWidth: 0.6))
            } else {
                fallback(content)
            }
            #else
            fallback(content)
            #endif
        }
    }

    private func fallback(_ content: Content) -> some View {
        content
            .background(
                appearance.glassStyle == .clear
                    ? AnyShapeStyle(.thinMaterial)
                    : AnyShapeStyle(.ultraThinMaterial),
                in: shape
            )
            .overlay(shape.fill((tintOverride ?? palette.accent.opacity(appearance.glassTint))))
            .overlay(shape.stroke(Color.white.opacity(0.08), lineWidth: 0.5))
            .clipShape(shape)
    }
}

extension View {
    /// Наложить жидкое стекло в заданной форме.
    func liquidGlass<S: Shape>(_ shape: S, interactive: Bool = false, surfaceWhenOff: Bool = true) -> some View {
        modifier(LiquidGlass(shape: shape, interactive: interactive, surfaceWhenOff: surfaceWhenOff))
    }
    func liquidGlass(cornerRadius: CGFloat = Radius.panel, interactive: Bool = false) -> some View {
        modifier(LiquidGlass(shape: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous), interactive: interactive))
    }
}

// Группировка НЕСКОЛЬКИХ стеклянных элементов, которые визуально накладываются или
// анимированно двигаются друг относительно друга (например, бар + едущий по нему
// индикатор). Без общего GlassEffectContainer каждый .glassEffect() на iOS 26 рендерится
// в свой независимый проход — при анимации это может давать «призрачное» задвоение
// кадра. С контейнером система корректно склеивает (сливает) оба стекла в одну сцену.
struct GlassGroup<Content: View>: View {
    var spacing: CGFloat = 12
    @ViewBuilder var content: Content

    var body: some View {
        #if compiler(>=6.0)
        if #available(iOS 26.0, *) {
            GlassEffectContainer(spacing: spacing) { content }
        } else {
            content
        }
        #else
        content
        #endif
    }
}

// Короткий тактильный отклик без пружины и «желейного» отскока.
struct SquishButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.985 : 1.0)
            .opacity(configuration.isPressed ? 0.88 : 1.0)
            .animation(.easeOut(duration: 0.1), value: configuration.isPressed)
    }
}

extension ButtonStyle where Self == SquishButtonStyle {
    static var squish: SquishButtonStyle { SquishButtonStyle() }
}
