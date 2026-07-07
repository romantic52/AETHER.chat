import SwiftUI

/// Знак AETHER — тонкое орбитальное кольцо с восходящим лучом («эфир»).
/// Векторный, масштабируется без потерь; используется на стартовом экране.
struct AetherLogo: View {
    var size: CGFloat = 96
    var lineWidth: CGFloat = 2

    var body: some View {
        ZStack {
            // внешнее кольцо
            Circle()
                .stroke(
                    AngularGradient(
                        colors: [.white.opacity(0.15), .white.opacity(0.9), .white.opacity(0.15)],
                        center: .center
                    ),
                    lineWidth: lineWidth
                )

            // внутренняя орбита (наклонный эллипс)
            Ellipse()
                .stroke(Color.white.opacity(0.35), lineWidth: lineWidth * 0.7)
                .frame(width: size * 0.92, height: size * 0.42)
                .rotationEffect(.degrees(-30))

            // восходящий луч — стилизованная «A»
            AetherMark()
                .stroke(Color.white, style: StrokeStyle(lineWidth: lineWidth * 1.3, lineCap: .round, lineJoin: .round))
                .frame(width: size * 0.42, height: size * 0.46)
                .shadow(color: .white.opacity(0.5), radius: 8)
        }
        .frame(width: size, height: size)
    }
}

/// Контур «A» без перекладины — чистый острый штрих.
private struct AetherMark: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: rect.minX, y: rect.maxY))
        p.addLine(to: CGPoint(x: rect.midX, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
        // тонкая перекладина
        p.move(to: CGPoint(x: rect.minX + rect.width * 0.22, y: rect.maxY - rect.height * 0.32))
        p.addLine(to: CGPoint(x: rect.maxX - rect.width * 0.22, y: rect.maxY - rect.height * 0.32))
        return p
    }
}

#Preview {
    ZStack {
        Brand.backdrop.ignoresSafeArea()
        AetherLogo(size: 140)
    }
}
