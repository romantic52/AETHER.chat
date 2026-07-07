import SwiftUI

/// Палитра AETHER. Зеркалит android Color.kt: near-black фон, бело-стеклянные
/// полупрозрачные слои. Единый бренд для всех платформ.
enum Brand {
    /// #050505 — почти чёрный фон приложения.
    static let bg = Color(red: 0.02, green: 0.02, blue: 0.02)
    static let bgSecondary = Color.white.opacity(0.03)

    static let textPrimary = Color.white
    static let textSecondary = Color.white.opacity(0.6)
    static let textMuted = Color.white.opacity(0.4)

    static let accent = Color.white.opacity(0.92)
    static let border = Color.white.opacity(0.08)

    static let msgIn = Color.white.opacity(0.05)
    static let msgOut = Color.white.opacity(0.15)

    /// Лёгкий цветной отлив для интерактивного стекла (кнопки/акценты).
    static let glassTint = Color(red: 0.55, green: 0.7, blue: 1.0)

    /// Фоновый градиент стартовых экранов — чёрный с холодным свечением.
    static let backdrop = LinearGradient(
        colors: [
            Color(red: 0.05, green: 0.06, blue: 0.10),
            Color(red: 0.02, green: 0.02, blue: 0.02),
            Color.black
        ],
        startPoint: .top,
        endPoint: .bottom
    )
}

extension Font {
    /// Логотип/крупные заголовки AETHER — широкий, разрежённый.
    static func aetherDisplay(_ size: CGFloat) -> Font {
        .system(size: size, weight: .light, design: .default)
    }
}
