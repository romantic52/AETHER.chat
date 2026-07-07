import SwiftUI

/// Круглый аватар-заглушка: инициал на стеклянном круге с детерминированным
/// оттенком по имени (как на Android до загрузки картинки).
struct Avatar: View {
    var name: String
    var size: CGFloat = 48

    private var initial: String {
        String(name.trimmingCharacters(in: .whitespaces).prefix(1)).uppercased()
    }

    private var hue: Double {
        let sum = name.unicodeScalars.reduce(0) { $0 + Int($1.value) }
        return Double(sum % 360) / 360.0
    }

    var body: some View {
        Text(initial.isEmpty ? "?" : initial)
            .font(.system(size: size * 0.42, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(
                Circle().fill(
                    LinearGradient(
                        colors: [
                            Color(hue: hue, saturation: 0.45, brightness: 0.55),
                            Color(hue: hue, saturation: 0.5, brightness: 0.32)
                        ],
                        startPoint: .topLeading, endPoint: .bottomTrailing
                    )
                )
            )
            .overlay(Circle().stroke(Brand.border, lineWidth: 0.5))
    }
}
