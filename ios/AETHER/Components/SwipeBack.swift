import SwiftUI
import UIKit

// Свайп-назад «как в Telegram»: работает с ЛЮБОГО места экрана, а не только
// от левой кромки. Скрытый навбар (.toolbar(.hidden)) отключает системный
// interactivePopGestureRecognizer — мы (1) включаем его обратно и (2) вешаем
// на весь экран полноэкранный pan с теми же внутренними target/action, что и
// у системного жеста, — он ведёт ту же интерактивную pop-анимацию.
//
// ВАЖНО: не переопределять viewDidLoad у UINavigationController в extension —
// это подменяет (а не дополняет) его собственную реализацию и ломает навигацию.
private struct SwipeBackEnabler: UIViewControllerRepresentable {
    final class Proxy: UIViewController, UIGestureRecognizerDelegate {
        private static let panName = "aetherFullScreenPop"

        // Pan, который решает по ПЕРВЫМ миллиметрам движения: вправо-горизонтально —
        // распознаётся, иначе мгновенно fail (скролл/свайп-ответ забирают жест).
        // Скорость в shouldBegin ненадёжна (в начале жеста почти ноль) — отсюда
        // были частые осечки.
        final class RightPan: UIPanGestureRecognizer {
            override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent) {
                super.touchesMoved(touches, with: event)
                guard state == .possible || state == .began else { return }
                let t = translation(in: view)
                if abs(t.x) > 5 || abs(t.y) > 5 {
                    if !(t.x > 0 && abs(t.x) > abs(t.y)) { state = .failed }
                }
            }
        }

        override func didMove(toParent parent: UIViewController?) {
            super.didMove(toParent: parent)
            DispatchQueue.main.async { [weak self] in
                guard let self,
                      let nav = self.findNavigationController(),
                      let edge = nav.interactivePopGestureRecognizer else { return }
                edge.delegate = self
                edge.isEnabled = true

                // Полноэкранный pan добавляем на nav.view один раз.
                let exists = nav.view.gestureRecognizers?.contains { $0.name == Self.panName } ?? false
                guard !exists, let targets = edge.value(forKey: "targets") else { return }
                let pan = RightPan()
                pan.name = Self.panName
                pan.maximumNumberOfTouches = 1
                pan.setValue(targets, forKey: "targets")
                pan.delegate = self
                nav.view.addGestureRecognizer(pan)
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

        func gestureRecognizerShouldBegin(_ g: UIGestureRecognizer) -> Bool {
            // Направление фильтрует сам RightPan по фактическому движению.
            (findNavigationController()?.viewControllers.count ?? 0) > 1
        }

        // Вертикальный скролл ленты живёт одновременно и выигрывает у пана,
        // если жест не прошёл фильтр направления выше.
        func gestureRecognizer(_ g: UIGestureRecognizer,
                               shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
            false
        }

        // 100% экрана: жесты контента (свайп-ответ на пузыре, скролл медиа)
        // обязаны дождаться провала нашего пана. Он проваливается за первые
        // ~5pt любого не-вправо движения, так что контент почти не ждёт.
        func gestureRecognizer(_ g: UIGestureRecognizer,
                               shouldBeRequiredToFailBy other: UIGestureRecognizer) -> Bool {
            g.name == Self.panName
        }
    }

    func makeUIViewController(context: Context) -> Proxy { Proxy() }
    func updateUIViewController(_ vc: Proxy, context: Context) {}
}

extension View {
    /// Включает свайп-назад (от края и полноэкранный) на пушнутом экране со скрытым навбаром.
    func swipeBackEnabled() -> some View {
        background(SwipeBackEnabler().frame(width: 0, height: 0))
    }
}
