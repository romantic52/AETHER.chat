import SwiftUI
import Combine

// Общий UI-хром: скрытие плавающего таб-бара при пуше на полноэкранные экраны
// (диалог, профиль группы и т.п.), которые рисуют свою нижнюю панель.
@MainActor
final class ChromeState: ObservableObject {
    @Published var tabBarHidden = false
}
