import SwiftUI

// Форма стеклянной панели с прогрессивной деградацией:
//  • iOS 26+ — родной .glassEffect; interactive-деформация выключена по умолчанию.
//  • iOS 17–18 — стабильный .ultraThinMaterial без непрерывных анимаций.
//  • стекло выключено — сплошной surface-цвет темы (без блюра).
struct LiquidGlass<S: Shape>: ViewModifier {
    let shape: S
    var interactive: Bool = false
    var tintOverride: Color? = nil
    /// true — не подмешивать оттенок темы вообще. Для дока: системный бар
    /// нейтральный, а наш при glassTint 12% отдавал синевой и выглядел чужеродно.
    var neutral: Bool = false
    /// false — при выключенном стекле НЕ рисовать surface-подложку (контент как есть):
    /// для элементов со своей заливкой (индикатор таб-бара), где подложка — «серое пятно».
    var surfaceWhenOff: Bool = true
    /// Идентификатор стекла внутри GlassEffectContainer. Без него контейнер не знает,
    /// что бар и едущий по нему индикатор — связанные формы, и рендерит два
    /// независимых прохода блюра: стёкла не сливаются, картинка мутная.
    var glassID: String? = nil
    var namespace: Namespace.ID? = nil
    @EnvironmentObject var appearance: AppearanceSettings
    @Environment(\.palette) private var palette

    func body(content: Content) -> some View {
        if !appearance.glassEnabled {
            if surfaceWhenOff {
                content.background(tintOverride ?? palette.surface, in: shape)
            } else {
                content
            }
        } else {
            #if compiler(>=6.0)
            if #available(iOS 26.0, *) {
                native(content)
            } else {
                fallback(content)
            }
            #else
            fallback(content)
            #endif
        }
    }

    // Нативное стекло iOS 26+. Ни clipShape, ни ручной обводки: форму задаёт
    // `in: shape`, а блик по краю Liquid Glass рисует сам и реагирует на контент
    // под собой — статичная белая линия поверх его забивала.
    @available(iOS 26.0, *)
    @ViewBuilder
    private func native(_ content: Content) -> some View {
        let base: Glass = appearance.glassStyle == .clear ? .clear : .regular
        let tintColor = neutral ? nil
            : (tintOverride ?? (appearance.glassTint > 0 ? palette.accent.opacity(appearance.glassTint) : nil))
        let tinted = tintColor != nil ? base.tint(tintColor!) : base
        let glassed = content.glassEffect(
            interactive && appearance.glassInteractive ? tinted.interactive() : tinted,
            in: shape
        )
        if let glassID, let namespace {
            glassed.glassEffectID(glassID, in: namespace)
        } else {
            glassed
        }
    }

    private func fallback(_ content: Content) -> some View {
        // Тинт кладём ПОД контент вместе с материалом. Раньше он шёл .overlay,
        // то есть поверх иконок и подписей — при ненулевом glassTint вкладки
        // затягивало цветной плёнкой.
        content
            .background {
                shape.fill(appearance.glassStyle == .clear
                            ? AnyShapeStyle(.thinMaterial)
                            : AnyShapeStyle(.ultraThinMaterial))
                if !neutral {
                    shape.fill(tintOverride ?? palette.accent.opacity(appearance.glassTint))
                }
            }
            .overlay(shape.stroke(Color.white.opacity(0.08), lineWidth: 0.5))
            .clipShape(shape)
    }
}

extension View {
    /// Наложить жидкое стекло в заданной форме. glassID + namespace — только для
    /// стёкол внутри одного GlassGroup, которые должны сливаться при движении.
    func liquidGlass<S: Shape>(_ shape: S, interactive: Bool = false, tint: Color? = nil,
                               neutral: Bool = false, surfaceWhenOff: Bool = true,
                               glassID: String? = nil, namespace: Namespace.ID? = nil) -> some View {
        modifier(LiquidGlass(shape: shape, interactive: interactive, tintOverride: tint,
                             neutral: neutral, surfaceWhenOff: surfaceWhenOff,
                             glassID: glassID, namespace: namespace))
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
