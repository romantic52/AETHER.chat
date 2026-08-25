import SwiftUI

// Настроение звонка = цвет фона. Серый пока идёт дозвон, зелёный на связи,
// красный на сбросе/недозвоне. Оттенки берутся из палитры темы,
// поэтому фон одинаково уместен и в тёмных, и в светлых темах.
enum CallMood: Equatable {
    case neutral   // подготовка / вызов / входящий
    case connected // разговор
    case failed    // сброс, отказ, недозвон

    static func from(state: CallManager.State, result: CallManager.Record.Result?) -> CallMood {
        switch state {
        case .active: return .connected
        case .ended:
            switch result {
            case .completed, .none: return .neutral
            default: return .failed
            }
        default: return .neutral
        }
    }

    /// Три опорных цвета настроения: основной, тёмный и подсветка.
    private func keys(_ palette: Palette, dark: Bool) -> (Color, Color, Color) {
        switch self {
        case .neutral:
            return dark
                ? (Palette.rgb(0x6B7280), Palette.rgb(0x2F3540), palette.accent)
                : (Palette.rgb(0xB6BECC), Palette.rgb(0xDCE1E9), palette.accent)
        case .connected:
            return dark
                ? (Palette.rgb(0x22C55E), Palette.rgb(0x0B5F3E), Palette.rgb(0x7DF3C0))
                : (Palette.rgb(0x6EE7B7), Palette.rgb(0xBBF7D0), Palette.rgb(0x22C55E))
        case .failed:
            return dark
                ? (Palette.rgb(0xEF4444), Palette.rgb(0x6E1616), Palette.rgb(0xFB7185))
                : (Palette.rgb(0xFCA5A5), Palette.rgb(0xFEE2E2), Palette.rgb(0xF87171))
        }
    }

    /// 16 цветов сетки 4×4, сверху вниз: густое настроение вверху и по центру,
    /// внизу — чистый фон темы, чтобы панель управления читалась.
    func mesh(_ palette: Palette, dark: Bool) -> [Color] {
        let (main, deep, glow) = keys(palette, dark: dark)
        let bg = palette.background
        func m(_ c: Color, _ t: Double) -> Color { .callMix(c, bg, t) }
        return [
            m(deep, 0.45), m(main, 0.30), m(glow, 0.40), m(deep, 0.50),
            m(main, 0.22), main,          glow,          m(main, 0.28),
            m(deep, 0.35), m(glow, 0.30), m(main, 0.20), m(deep, 0.40),
            bg,            m(deep, 0.72), m(main, 0.78), bg,
        ]
    }
}

// Текучий фон: сетка 4×4, у которой углы прибиты к углам экрана, точки на
// рёбрах ездят вдоль своего ребра, а четыре внутренние плавают свободно —
// каждая по своей синусоиде со своей частотой и фазой. Отсюда ощущение
// переливающейся жидкости, а не нескольких кругов, наложенных друг на друга.
// На iOS 17 (без MeshGradient) — те же независимо плавающие размытые пятна.
struct CallBackdrop: View {
    var mood: CallMood
    /// Ускоряет течение, пока идёт дозвон.
    var pulsing: Bool = false

    @Environment(\.palette) private var palette
    @EnvironmentObject private var appearance: AppearanceSettings

    @State private var previous: CallMood?
    @State private var changedAt = Date.distantPast

    var body: some View {
        let dark = appearance.theme.isDark
        let target = mood.mesh(palette, dark: dark)
        let source = (previous ?? mood).mesh(palette, dark: dark)

        TimelineView(.animation) { context in
            // Смена настроения перетекает вручную: цвета пересчитываются каждый
            // кадр, поэтому обычная .animation по ним не работает.
            let shift = min(max(context.date.timeIntervalSince(changedAt) / 0.9, 0), 1)
            let eased = shift * shift * (3 - 2 * shift)
            let colors = zip(source, target).map { Color.callMix($0, $1, eased) }
            let t = context.date.timeIntervalSinceReferenceDate * (pulsing ? 0.17 : 0.11)

            ZStack {
                palette.background
                flow(colors: colors, t: t)
                LinearGradient(colors: [.clear, palette.background.opacity(dark ? 0.55 : 0.4)],
                               startPoint: .center, endPoint: .bottom)
            }
        }
        .ignoresSafeArea()
        .onChange(of: mood) { old, _ in
            previous = old
            changedAt = .now
        }
    }

    @ViewBuilder
    private func flow(colors: [Color], t: Double) -> some View {
        if #available(iOS 18.0, *) {
            MeshGradient(width: 4, height: 4, points: Self.points(t), colors: colors, smoothsColors: true)
        } else {
            FloatingBlobs(colors: colors, t: t)
        }
    }

    /// Углы фиксированы (иначе по краям вылезает фон), рёберные точки скользят
    /// только вдоль своего ребра, внутренние — свободно. Амплитуды подобраны так,
    /// чтобы соседние точки не пересекались: на пересечении сетка рвётся.
    private static func points(_ t: Double) -> [SIMD2<Float>] {
        func w(_ base: Double, _ amplitude: Double, _ frequency: Double, _ phase: Double) -> Float {
            Float(base + sin(t * frequency + phase) * amplitude)
        }
        let a = 1.0 / 3.0, b = 2.0 / 3.0
        return [
            SIMD2(0, 0),
            SIMD2(w(a, 0.09, 1.10, 0.0), 0),
            SIMD2(w(b, 0.09, 0.90, 1.7), 0),
            SIMD2(1, 0),

            SIMD2(0, w(a, 0.08, 0.80, 2.4)),
            SIMD2(w(a, 0.14, 1.30, 0.6), w(a, 0.12, 1.00, 3.1)),
            SIMD2(w(b, 0.14, 1.05, 2.2), w(a, 0.12, 1.25, 0.9)),
            SIMD2(1, w(a, 0.08, 1.15, 4.0)),

            SIMD2(0, w(b, 0.08, 1.00, 1.2)),
            SIMD2(w(a, 0.15, 0.95, 4.4), w(b, 0.13, 1.20, 2.0)),
            SIMD2(w(b, 0.15, 1.35, 3.0), w(b, 0.13, 0.85, 5.2)),
            SIMD2(1, w(b, 0.08, 0.90, 2.8)),

            SIMD2(0, 1),
            SIMD2(w(a, 0.09, 1.20, 5.0), 1),
            SIMD2(w(b, 0.09, 1.00, 0.4), 1),
            SIMD2(1, 1),
        ]
    }
}

// Фолбэк для iOS 17: пять пятен, каждое по своей траектории Лиссажу.
private struct FloatingBlobs: View {
    let colors: [Color]
    let t: Double

    private let picks = [5, 6, 9, 10, 1]

    var body: some View {
        GeometryReader { geo in
            let side = max(geo.size.width, geo.size.height)
            ZStack {
                ForEach(0..<5, id: \.self) { i in
                    let f = Double(i)
                    Circle()
                        .fill(colors[picks[i]])
                        .frame(width: side * (0.62 + 0.09 * sin(f * 1.3)),
                               height: side * (0.62 + 0.09 * cos(f * 0.9)))
                        .offset(x: side * 0.30 * sin(t * (0.9 + f * 0.13) + f * 1.1),
                                y: side * 0.28 * cos(t * (0.7 + f * 0.17) + f * 1.7))
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
            .blur(radius: side * 0.12)
            .clipped()
        }
    }
}

private extension Color {
    /// Линейная смесь двух цветов. Нужна и для перетекания настроения, и для
    /// разбавления опорных цветов фоном темы: Color.mix появился только в iOS 18.
    static func callMix(_ a: Color, _ b: Color, _ t: Double) -> Color {
        guard t > 0.001 else { return a }
        guard t < 0.999 else { return b }
        var r1: CGFloat = 0, g1: CGFloat = 0, b1: CGFloat = 0, a1: CGFloat = 0
        var r2: CGFloat = 0, g2: CGFloat = 0, b2: CGFloat = 0, a2: CGFloat = 0
        UIColor(a).getRed(&r1, green: &g1, blue: &b1, alpha: &a1)
        UIColor(b).getRed(&r2, green: &g2, blue: &b2, alpha: &a2)
        let k = CGFloat(t)
        return Color(.sRGB,
                     red: r1 + (r2 - r1) * k,
                     green: g1 + (g2 - g1) * k,
                     blue: b1 + (b2 - b1) * k,
                     opacity: a1 + (a2 - a1) * k)
    }
}

// Пульсирующие круги вокруг аватара, пока идёт вызов: три волны с разной фазой.
struct CallPulse: View {
    var color: Color
    var active: Bool
    var size: CGFloat

    @State private var animate = false

    var body: some View {
        ZStack {
            ForEach(0..<3, id: \.self) { i in
                Circle()
                    .stroke(color.opacity(0.35), lineWidth: 1.5)
                    .frame(width: size, height: size)
                    .scaleEffect(animate ? 1.55 : 1.0)
                    .opacity(animate ? 0 : 0.9)
                    .animation(
                        active
                            ? .easeOut(duration: 2.4).repeatForever(autoreverses: false).delay(Double(i) * 0.8)
                            : .default,
                        value: animate
                    )
            }
        }
        .opacity(active ? 1 : 0)
        .onAppear { animate = active }
        .onChange(of: active) { _, on in animate = on }
    }
}
