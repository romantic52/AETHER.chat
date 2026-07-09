import SwiftUI

// Логотип Æther — монограмма «Æ» (A, слитая с угловатой E) в круге с кольцом.
// Если в ассеты добавлен BrandLogo (реальный PNG) — используется он; иначе рисуется вектором.
struct AetherLogo: View {
    var size: CGFloat = 72
    /// true — чёрно-белый фирменный знак (как на референсе); false — на акценте темы.
    var monochrome: Bool = true
    @Environment(\.palette) private var palette

    var body: some View {
        if UIImage(named: "BrandLogo") != nil {
            Image("BrandLogo").resizable().scaledToFit()
                .frame(width: size, height: size)
        } else {
            drawn
        }
    }

    private var ringColor: Color { monochrome ? .white : palette.onAccent }
    private var glyphColor: Color { monochrome ? .white : palette.onAccent }
    private var fillColor: Color { monochrome ? .black : palette.accent }

    private var drawn: some View {
        ZStack {
            Circle().fill(fillColor)
            Circle().stroke(ringColor, lineWidth: size * 0.045)
                .padding(size * 0.055)
            AEMonogram()
                .fill(glyphColor)
                .frame(width: size * 0.6, height: size * 0.52)
        }
        .frame(width: size, height: size)
        .shadow(color: (monochrome ? Color.black : palette.accent).opacity(0.35),
                radius: size * 0.12, y: size * 0.05)
    }
}

// Геометрический вензель «Æ»: слева бол­ьшая A (штрихи + перекладина), справа угловатая E
// из трёх параллелограммных перекладин, разделяющая стойку с A.
struct AEMonogram: Shape {
    func path(in rect: CGRect) -> Path {
        // Рисуем в нормализованном пространстве 100×100, затем масштабируем в rect.
        let s = CGAffineTransform(scaleX: rect.width / 100, y: rect.height / 100)
        var p = Path()

        func poly(_ pts: [(CGFloat, CGFloat)]) {
            var sub = Path()
            sub.move(to: CGPoint(x: pts[0].0, y: pts[0].1))
            for pt in pts.dropFirst() { sub.addLine(to: CGPoint(x: pt.0, y: pt.1)) }
            sub.closeSubpath()
            p.addPath(sub)
        }

        // A — левый штрих (снизу-слева к вершине).
        poly([(2, 100), (20, 100), (46, 8), (34, 8)])
        // A — правый штрих (от вершины вниз к середине, где начинается стойка E).
        poly([(46, 8), (58, 8), (52, 34), (40, 34)])
        // A — перекладина.
        poly([(16, 60), (52, 60), (49, 74), (13, 74)])

        // E — три перекладины (параллелограммы со скошенным правым краем).
        poly([(48, 8), (98, 8), (86, 30), (48, 30)])       // верхняя
        poly([(46, 42), (84, 42), (74, 62), (46, 62)])     // средняя
        poly([(40, 78), (92, 78), (80, 100), (40, 100)])   // нижняя

        return p.applying(s)
    }
}
