import SwiftUI

// 6 тем-палитр (паритет с Android). Графит — дефолт. Каждая палитра описывает
// поверхности, акцент и текст; светлые (День/Сакура/Пастель) корректно инвертируют.
enum ThemePalette: String, CaseIterable, Identifiable, Codable {
    case graphite   // Графит (дефолт, тёмная)
    case forest     // Лес / rose-pine
    case sakura     // Сакура (светлая, розовая)
    case mono       // Моно (нейтральная тёмная)
    case pastel     // Пастель (светлая)
    case day        // День (светлая)

    var id: String { rawValue }

    var title: String {
        switch self {
        case .graphite: return "Графит"
        case .forest:   return "Лес"
        case .sakura:   return "Сакура"
        case .mono:     return "Моно"
        case .pastel:   return "Пастель"
        case .day:      return "День"
        }
    }

    var isDark: Bool {
        switch self {
        case .graphite, .forest, .mono: return true
        case .sakura, .pastel, .day:    return false
        }
    }
}

enum GlassStyleOption: String, CaseIterable, Identifiable {
    case regular
    case clear

    var id: String { rawValue }
    var title: String { self == .regular ? "Обычное" : "Прозрачное" }
}

// Значения одной палитры. Цвета — в sRGB, чтобы совпадать с Android hex.
struct Palette {
    let background: Color      // самый нижний фон
    let surface: Color         // карточки/бары (сплошной, когда стекло выключено)
    let surfaceElevated: Color // приподнятые панели
    let accent: Color          // основной акцент (кнопки, свои пузыри)
    let accentSoft: Color      // мягкий акцент (фон свои пузырей на светлых темах)
    let onAccent: Color        // текст на акценте
    let textPrimary: Color
    let textSecondary: Color
    let bubbleIn: Color        // входящий пузырь
    let bubbleOut: Color       // исходящий пузырь
    let bubbleOutText: Color   // текст исходящего пузыря (тёмный в светлых темах)
    let divider: Color
    let readTick: Color        // зелёные галочки прочтения (#4ADE80)
    let danger: Color

    static func rgb(_ hex: UInt32, _ a: Double = 1) -> Color {
        Color(.sRGB,
              red: Double((hex >> 16) & 0xFF) / 255,
              green: Double((hex >> 8) & 0xFF) / 255,
              blue: Double(hex & 0xFF) / 255,
              opacity: a)
    }
}

extension ThemePalette {
    var palette: Palette {
        let read = Palette.rgb(0x4ADE80)
        switch self {
        case .graphite:
            return Palette(
                background: Palette.rgb(0x0E0F12),
                surface: Palette.rgb(0x16181D),
                surfaceElevated: Palette.rgb(0x1E2127),
                accent: Palette.rgb(0x5B8CFF),
                accentSoft: Palette.rgb(0x2A3550),
                onAccent: .white,
                textPrimary: Palette.rgb(0xF2F4F8),
                textSecondary: Palette.rgb(0x9AA3B2),
                bubbleIn: Palette.rgb(0x1E2127),
                bubbleOut: Palette.rgb(0x2F5BD6),
                bubbleOutText: .white,
                divider: Palette.rgb(0x262A31),
                readTick: read, danger: Palette.rgb(0xEF4444))
        case .forest:
            return Palette(
                background: Palette.rgb(0x191724),
                surface: Palette.rgb(0x1F1D2E),
                surfaceElevated: Palette.rgb(0x26233A),
                accent: Palette.rgb(0xC4A7E7),
                accentSoft: Palette.rgb(0x393552),
                onAccent: Palette.rgb(0x191724),
                textPrimary: Palette.rgb(0xE0DEF4),
                textSecondary: Palette.rgb(0x908CAA),
                bubbleIn: Palette.rgb(0x26233A),
                bubbleOut: Palette.rgb(0x3E3A5E),
                bubbleOutText: Palette.rgb(0xF2F0FF),
                divider: Palette.rgb(0x2A2740),
                readTick: read, danger: Palette.rgb(0xEB6F92))
        case .sakura:
            return Palette(
                background: Palette.rgb(0xFFF1F5),
                surface: Palette.rgb(0xFFFFFF),
                surfaceElevated: Palette.rgb(0xFFE4EC),
                accent: Palette.rgb(0xE86A9A),
                accentSoft: Palette.rgb(0xFAD1DF),
                onAccent: .white,
                textPrimary: Palette.rgb(0x3A2530),
                textSecondary: Palette.rgb(0x9A7080),
                bubbleIn: Palette.rgb(0xFFFFFF),
                bubbleOut: Palette.rgb(0xF7B7CE),
                bubbleOutText: Palette.rgb(0x3A2530),
                divider: Palette.rgb(0xF3D3DE),
                readTick: Palette.rgb(0x2FA968), danger: Palette.rgb(0xE23D5B))
        case .mono:
            return Palette(
                background: Palette.rgb(0x0A0A0A),
                surface: Palette.rgb(0x161616),
                surfaceElevated: Palette.rgb(0x202020),
                accent: Palette.rgb(0xE8E8E8),
                accentSoft: Palette.rgb(0x2C2C2C),
                onAccent: Palette.rgb(0x0A0A0A),
                textPrimary: Palette.rgb(0xF5F5F5),
                textSecondary: Palette.rgb(0x8A8A8A),
                bubbleIn: Palette.rgb(0x202020),
                bubbleOut: Palette.rgb(0x343434),
                bubbleOutText: Palette.rgb(0xF5F5F5),
                divider: Palette.rgb(0x262626),
                readTick: read, danger: Palette.rgb(0xE05555))
        case .pastel:
            return Palette(
                background: Palette.rgb(0xF3F0FF),
                surface: Palette.rgb(0xFFFFFF),
                surfaceElevated: Palette.rgb(0xEBE6FF),
                accent: Palette.rgb(0x8B7BF0),
                accentSoft: Palette.rgb(0xDBD3FA),
                onAccent: .white,
                textPrimary: Palette.rgb(0x2E2A45),
                textSecondary: Palette.rgb(0x847EA8),
                bubbleIn: Palette.rgb(0xFFFFFF),
                bubbleOut: Palette.rgb(0xC9BEF7),
                bubbleOutText: Palette.rgb(0x2E2A45),
                divider: Palette.rgb(0xE2DCF5),
                readTick: Palette.rgb(0x35B36F), danger: Palette.rgb(0xE0556F))
        case .day:
            return Palette(
                background: Palette.rgb(0xF0F2F5),
                surface: Palette.rgb(0xFFFFFF),
                surfaceElevated: Palette.rgb(0xE9EDF2),
                accent: Palette.rgb(0x2F7BF6),
                accentSoft: Palette.rgb(0xD6E4FF),
                onAccent: .white,
                textPrimary: Palette.rgb(0x14181F),
                textSecondary: Palette.rgb(0x69727F),
                bubbleIn: Palette.rgb(0xFFFFFF),
                bubbleOut: Palette.rgb(0xCCE0FF),
                bubbleOutText: Palette.rgb(0x14181F),
                divider: Palette.rgb(0xDCE1E8),
                readTick: Palette.rgb(0x24A25A), danger: Palette.rgb(0xE23D3D))
        }
    }
}

// Единая геометрия основных экранов. Значения повторяют визуальный ритм
// Telegram-iOS, но компоненты остаются собственной SwiftUI-реализацией Aether.
enum AetherUI {
    static let screenInset: CGFloat = 16
    static let listAvatar: CGFloat = 60
    static let listRowHeight: CGFloat = 76
    static let listTextInset: CGFloat = 84
    static let tabBarHeight: CGFloat = 49
    static let composerControl: CGFloat = 40
    static let composerInset: CGFloat = 7
    static let bubbleRadius: CGFloat = 17
    static let connectedRadius: CGFloat = 6

    /// Единая анимация всего цикла отправки сообщения: очистка композера,
    /// вставка пузыря в ленту и скролл к низу идут одной пружиной,
    /// иначе части экрана двигаются вразнобой.
    static let sendAnimation = Animation.spring(response: 0.38, dampingFraction: 0.8)
}

// Наблюдаемые настройки внешнего вида: тема, стекло вкл/выкл + прозрачность, шрифт,
// быстрая реакция. Персистятся в UserDefaults; читаются во всём UI через .environmentObject.
@MainActor
final class AppearanceSettings: ObservableObject {
    @Published var theme: ThemePalette { didSet { defaults.set(theme.rawValue, forKey: "theme") } }
    @Published var glassEnabled: Bool { didSet { defaults.set(glassEnabled, forKey: "glassEnabled") } }
    @Published var glassStyle: GlassStyleOption { didSet { defaults.set(glassStyle.rawValue, forKey: "glassStyle") } }
    @Published var glassTint: Double { didSet { defaults.set(glassTint, forKey: "glassTint") } }
    @Published var glassInteractive: Bool { didSet { defaults.set(glassInteractive, forKey: "glassInteractive") } }
    @Published var glassOnInput: Bool { didSet { defaults.set(glassOnInput, forKey: "glassOnInput") } }
    @Published var quickReaction: String { didSet { defaults.set(quickReaction, forKey: "quickReaction") } }
    @Published var switchTabOnRelease: Bool { didSet { defaults.set(switchTabOnRelease, forKey: "switchTabOnRelease") } }
    @Published var bubbleRadius: Double { didSet { defaults.set(bubbleRadius, forKey: "bubbleRadius") } }
    @Published var bubbleConnected: Double { didSet { defaults.set(bubbleConnected, forKey: "bubbleConnected") } }
    @Published var bubbleTails: Bool { didSet { defaults.set(bubbleTails, forKey: "bubbleTails") } }
    @Published var edgeDimEnabled: Bool { didSet { defaults.set(edgeDimEnabled, forKey: "edgeDimEnabled") } }
    @Published var edgeDimStrength: Double { didSet { defaults.set(edgeDimStrength, forKey: "edgeDimStrength") } }

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        theme = ThemePalette(rawValue: defaults.string(forKey: "theme") ?? "") ?? .graphite
        glassEnabled = defaults.object(forKey: "glassEnabled") as? Bool ?? true
        glassStyle = GlassStyleOption(rawValue: defaults.string(forKey: "glassStyle") ?? "") ?? .regular
        glassTint = defaults.object(forKey: "glassTint") as? Double ?? 0.12
        glassInteractive = defaults.object(forKey: "glassInteractive") as? Bool ?? true
        glassOnInput = defaults.object(forKey: "glassOnInput") as? Bool ?? true
        quickReaction = defaults.string(forKey: "quickReaction") ?? "❤️"
        switchTabOnRelease = defaults.object(forKey: "switchTabOnRelease") as? Bool ?? false
        bubbleRadius = defaults.object(forKey: "bubbleRadius") as? Double ?? 17
        bubbleConnected = defaults.object(forKey: "bubbleConnected") as? Double ?? 6
        bubbleTails = defaults.object(forKey: "bubbleTails") as? Bool ?? true
        edgeDimEnabled = defaults.object(forKey: "edgeDimEnabled") as? Bool ?? true
        edgeDimStrength = defaults.object(forKey: "edgeDimStrength") as? Double ?? 0.25
    }

    var palette: Palette { theme.palette }
    var colorScheme: ColorScheme { theme.isDark ? .dark : .light }
}

// Лёгкое затемнение края экрана вместо сплошных подложек: интерфейс «в воздухе»,
// градиент только помогает читаемости статус-бара/контролов. Сила и вкл/выкл —
// в настройках (edgeDim*); при выключении полностью прозрачен.
// boost — множитель для нижнего края, где под таб-баром/композером нужно чуть плотнее.
struct EdgeDim: View {
    @EnvironmentObject var appearance: AppearanceSettings
    @Environment(\.palette) private var palette
    var edge: VerticalEdge = .top
    var boost: Double = 1

    var body: some View {
        // Цвет — фон текущей темы (не чистый чёрный). Три ступени плотности:
        // у края «мега» (почти непрозрачно на максимуме), затем сильное,
        // затем слабое растворение — как у Telegram. Настройка задаёт общую силу
        // и работает одинаково для верха и низа.
        let a = appearance.edgeDimEnabled ? min(appearance.edgeDimStrength * boost * 1.7, 1.0) : 0
        let base = palette.background
        let stops: [Gradient.Stop] = edge == .top
            ? [
                .init(color: base.opacity(a), location: 0),          // мега у края
                .init(color: base.opacity(a * 0.62), location: 0.34), // сильное
                .init(color: base.opacity(a * 0.26), location: 0.68), // слабое
                .init(color: .clear, location: 1),
            ]
            : [
                .init(color: .clear, location: 0),
                .init(color: base.opacity(a * 0.26), location: 0.32),
                .init(color: base.opacity(a * 0.62), location: 0.66),
                .init(color: base.opacity(a), location: 1),
            ]
        LinearGradient(stops: stops, startPoint: .top, endPoint: .bottom)
            .allowsHitTesting(false)
    }
}

// Быстрый доступ к палитре из окружения.
private struct PaletteKey: EnvironmentKey {
    static let defaultValue: Palette = ThemePalette.graphite.palette
}
extension EnvironmentValues {
    var palette: Palette {
        get { self[PaletteKey.self] }
        set { self[PaletteKey.self] = newValue }
    }
}
