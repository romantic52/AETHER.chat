import Foundation
import LocalAuthentication
import SwiftUI

// Блокировка приложения: Face ID / Touch ID с фолбэком на свой PIN.
// Включается в Настройках; при уходе в фон приложение блокируется,
// при возврате — экран LockView поверх всего (контент скрыт и из App Switcher).
@MainActor
final class AppLock: ObservableObject {
    static let shared = AppLock()

    static let enabledKey = "appLockEnabled"

    @Published private(set) var locked = false
    @Published private(set) var biometryAvailable = false
    @Published private(set) var biometryType: LABiometryType = .none

    var enabled: Bool { UserDefaults.standard.bool(forKey: Self.enabledKey) }
    var hasPin: Bool { !(Keychain.string(for: Keychain.kPin) ?? "").isEmpty }

    private init() {
        refreshBiometry()
        if enabled { locked = true }
    }

    func refreshBiometry() {
        let context = LAContext()
        var error: NSError?
        biometryAvailable = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error)
        biometryType = context.biometryType
    }

    // MARK: - Жизненный цикл

    /// Ушли в фон — блокируемся сразу (контент скрыт в App Switcher).
    func appDidEnterBackground() {
        guard enabled else { return }
        locked = true
    }

    /// Попытка разблокировки биометрией (авто при показе LockView и по кнопке).
    func tryBiometrics() async -> Bool {
        guard biometryAvailable else { return false }
        let context = LAContext()
        context.localizedFallbackTitle = "Ввести PIN"
        do {
            let ok = try await context.evaluatePolicy(
                .deviceOwnerAuthenticationWithBiometrics,
                localizedReason: "Разблокировать AETHER"
            )
            return ok
        } catch {
            return false
        }
    }

    /// Проверка PIN (фолбэк). Успех НЕ снимает блокировку сам — LockView сначала
    /// проигрывает анимацию замка и затем зовёт finishUnlock().
    func tryPin(_ pin: String) -> Bool {
        guard let stored = Keychain.string(for: Keychain.kPin), !stored.isEmpty else { return false }
        return pin == stored
    }

    /// Снять блокировку после анимации открытия замка: экран уезжает вверх
    /// (transition .move(.top) в RootView) вслед за «отстегнувшейся» дужкой.
    func finishUnlock() {
        withAnimation(.easeIn(duration: 0.38)) { locked = false }
    }

    // MARK: - Настройка

    /// Включить блокировку: PIN обязателен (фолбэк, если биометрия недоступна/не сработала).
    func enable(pin: String) {
        Keychain.set(pin, for: Keychain.kPin)
        UserDefaults.standard.set(true, forKey: Self.enabledKey)
        refreshBiometry()
    }

    func disable() {
        UserDefaults.standard.set(false, forKey: Self.enabledKey)
        Keychain.set("", for: Keychain.kPin)
        locked = false
    }
}
