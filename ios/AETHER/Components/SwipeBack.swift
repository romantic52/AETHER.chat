import SwiftUI
import UIKit

// Возвращает системный свайп-назад на экранах с полностью скрытым навбаром:
// после .toolbar(.hidden, for: .navigationBar) UIKit отключает
// interactivePopGestureRecognizer (нет видимой кнопки «назад»).
//
// ВАЖНО: не переопределять viewDidLoad у UINavigationController в extension —
// это подменяет (а не дополняет) его собственную реализацию и ломает
// навигацию целиком (кнопка назад/жест начинают работать криво).
// Вместо этого — невидимый representable, который точечно вешает делегата
// на жест конкретного навигационного стека.
private struct SwipeBackEnabler: UIViewControllerRepresentable {
    final class Proxy: UIViewController, UIGestureRecognizerDelegate {
        override func didMove(toParent parent: UIViewController?) {
            super.didMove(toParent: parent)
            DispatchQueue.main.async { [weak self] in
                guard let self,
                      let nav = self.findNavigationController(),
                      let gesture = nav.interactivePopGestureRecognizer else { return }
                gesture.delegate = self
                gesture.isEnabled = true
            }
        }

        private func findNavigationController() -> UINavigationController? {
            var vc: UIViewController? = parent
            while let current = vc {
                if let nav = current as? UINavigationController { return nav }
                if let nav = current.navigationController { return nav }
                vc = current.parent
            }
            return nil
        }

        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            (findNavigationController()?.viewControllers.count ?? 0) > 1
        }
    }

    func makeUIViewController(context: Context) -> Proxy { Proxy() }
    func updateUIViewController(_ vc: Proxy, context: Context) {}
}

extension View {
    /// Включает свайп-назад на пушнутом экране со скрытым навбаром.
    func swipeBackEnabled() -> some View {
        background(SwipeBackEnabler().frame(width: 0, height: 0))
    }
}
