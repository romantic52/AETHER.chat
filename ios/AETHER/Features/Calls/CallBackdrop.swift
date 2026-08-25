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

    /// Три цвета пятен фона. dark/light-варианты, плюс акцент темы в нейтральном
    /// состоянии — чтобы экран звонка не выпадал из выбранной палитры.
    func blobs(_ palette: Palette, dark: Bool) -> [Color] {
        switch self {
        case .neutral:
            return dark
                ? [Palette.rgb(0x6B7280), Palette.rgb(0x39404D), palette.accent.opacity(0.75)]
                : [Palette.rgb(0xB6BECC), Palette.rgb(0xD8DEE7), palette.accent.opacity(0.45)]
        case .connected:
            return dark
                ? [Palette.rgb(0x22C55E), Palette.rgb(0x0F7A50), Palette.rgb(0x6EE7B7)]
                : [Palette.rgb(0x86EFAC), Palette.rgb(0xBBF7D0), Palette.rgb(0x34D399)]
        case .failed:
            return dark
                ? [Palette.rgb(0xEF4444), Palette.rgb(0x8F1D1D), Palette.rgb(0xFB7185)]
                : [Palette.rgb(0xFCA5A5), Palette.rgb(0xFECACA), Palette.rgb(0xF87171)]
        }
    }

    /// Насколько плотно настроение проступает поверх фона темы.
    var strength: Double {
        switch self {
        case .neutral: return 0.55
        case .connected: return 0.7
        case .failed: return 0.8
        }
    }
}

// Живой градиентный фон: два слоя размытых пятен, вращающихся навстречу друг
// другу, плюс «дыхание» масштабом. Анимация идёт на трансформациях уже
// растеризованного слоя (drawingGroup), поэтому размытие не пересчитывается покадрово.
struct CallBackdrop: View {
    var mood: CallMood
    /// Ускоряет дыхание, пока идёт дозвон — экран «звенит» вместе с гудками.
    var pulsing: Bool = false

    @Environment(\.palette) private var palette
    @EnvironmentObject private var appearance: AppearanceSettings

    @State private var spin = false
    @State private var counterSpin = false
    @State private var breathe = false

    var body: some View {
        GeometryReader { geo in
            let side = max(geo.size.width, geo.size.height)
            let colors = mood.blobs(palette, dark: appearance.theme.isDark)

            ZStack {
                palette.background

                LinearGradient(
                    colors: [colors[0].opacity(mood.strength * 0.55), palette.background.opacity(0.0)],
                    startPoint: .top, endPoint: .center
                )

                blobLayer(colors: colors, side: side, seed: 0)
                    .rotationEffect(.degrees(spin ? 360 : 0))
                    .scaleEffect(breathe ? 1.10 : 0.94)

                blobLayer(colors: Array(colors.reversed()), side: side, seed: 1)
                    .rotationEffect(.degrees(counterSpin ? -360 : 0))
                    .scaleEffect(breathe ? 0.96 : 1.12)
                    .opacity(0.75)

                // Мягкое затемнение к низу, чтобы панель управления читалась.
                LinearGradient(
                    colors: [.clear, palette.background.opacity(appearance.theme.isDark ? 0.75 : 0.55)],
                    startPoint: .center, endPoint: .bottom
                )
            }
            .frame(width: geo.size.width, height: geo.size.height)
            .clipped()
        }
        .ignoresSafeArea()
        .animation(.easeInOut(duration: 0.75), value: mood)
        .onAppear(perform: startAnimations)
        .onChange(of: pulsing) { _, _ in restartBreathing() }
    }

    private func blobLayer(colors: [Color], side: CGFloat, seed: Int) -> some View {
        let offsets: [CGSize] = seed == 0
            ? [CGSize(width: -side * 0.22, height: -side * 0.26),
               CGSize(width: side * 0.28, height: -side * 0.06),
               CGSize(width: -side * 0.06, height: side * 0.30)]
            : [CGSize(width: side * 0.24, height: side * 0.22),
               CGSize(width: -side * 0.30, height: side * 0.04),
               CGSize(width: side * 0.02, height: -side * 0.32)]

        return ZStack {
            ForEach(0..<3, id: \.self) { i in
                Circle()
                    .fill(
                        RadialGradient(colors: [colors[i].opacity(mood.strength), colors[i].opacity(0)],
                                       center: .center, startRadius: 0, endRadius: side * 0.42)
                    )
                    .frame(width: side * (0.9 - CGFloat(i) * 0.08), height: side * (0.9 - CGFloat(i) * 0.08))
                    .offset(offsets[i])
            }
        }
        .blur(radius: side * 0.06)
        .drawingGroup()
    }

    private func startAnimations() {
        guard !spin else { return }
        withAnimation(.linear(duration: 34).repeatForever(autoreverses: false)) { spin = true }
        withAnimation(.linear(duration: 52).repeatForever(autoreverses: false)) { counterSpin = true }
        restartBreathing()
    }

    private func restartBreathing() {
        let period = pulsing ? 2.4 : 5.5
        withAnimation(.easeInOut(duration: period).repeatForever(autoreverses: true)) { breathe = true }
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
