import SwiftUI
import Combine

// Вкладки приложения. Живут в ChromeState, потому что таб-бар теперь рисуется
// внутри корня каждой вкладки (под пушем NavigationStack): чат накрывает бар,
// а при свайпе назад бар уже на месте под уезжающим экраном — как в Telegram.
// .search — вкладка с ролью .search: на iOS 26 система рисует её отдельным
// кружком справа от капсулы дока, как в Telegram.
enum AppTab: Hashable { case contacts, calls, chats, settings, search }

// Общий UI-хром: активная вкладка, доступная и таб-бару, и корням вкладок.
// Транзиентное состояние перетягивания по бару тоже здесь: бар есть в каждом
// корне отдельным экземпляром, а вкладки живут одновременно (переключение —
// видимостью), поэтому жест, начатый на баре одной вкладки, продолжает
// двигать индикатор на баре другой.
@MainActor
final class ChromeState: ObservableObject {
    @Published var tab: AppTab = .chats
    @Published var barPressing = false
    @Published var barLivePosition: CGFloat?
}
