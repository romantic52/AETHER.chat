import Foundation
import SwiftUI

// Приватность обнаружения.
//
// Четыре НЕЗАВИСИМЫЕ оси, и объединять их в один флаг «виден/невиден» нельзя
// (docs/TRANSPORT_LAYER_DESIGN.md, раздел 12.2):
//
//   DISCOVERY   может ли человек вообще понять, что я рядом
//   VISIBILITY  что он при этом увидит
//   INTERACTION что он может сделать
//   DELIVERY    может ли прислать сообщение напрямую
//
// Допустимая и осмысленная комбинация: виден всем, профиль скрыт до
// «пользователь Aether», писать нельзя.
//
// Настройки принадлежат УСТРОЙСТВУ, а не пространству: радио одно на всех,
// и «невидим» обязан означать невидим везде.

enum NearbyAudience: String, CaseIterable, Codable {
    case nobody, contacts, everyone

    var title: String {
        switch self {
        case .nobody: return "Никто"
        case .contacts: return "Контакты"
        case .everyone: return "Все пользователи Aether"
        }
    }
}

/// Что видит тот, кто нас нашёл, но контактом не является.
enum StrangerVisibility: String, CaseIterable, Codable {
    case aetherUserOnly, nameAndAvatar, publicProfile

    var title: String {
        switch self {
        case .aetherUserOnly: return "Только «Пользователь Aether»"
        case .nameAndAvatar: return "Имя и аватар"
        case .publicProfile: return "Публичный профиль"
        }
    }
}

@MainActor
final class NearbyPrivacy: ObservableObject {
    static let shared = NearbyPrivacy()

    /// Главный выключатель. Выключен — радио молчит и не отвечает.
    @AppStorage("nearby.enabled") var enabled = false {
        didSet { objectWillChange.send() }
    }
    @AppStorage("nearby.bluetooth") var bluetoothVisible = true {
        didSet { objectWillChange.send() }
    }
    @AppStorage("nearby.network") var networkVisible = false {
        didSet { objectWillChange.send() }
    }

    /// Умолчание — «Контакты», а не «Все»: безопасный дефолт по разделу 12.3.
    @AppStorage("nearby.audience") private var audienceRaw = NearbyAudience.contacts.rawValue
    @AppStorage("nearby.stranger") private var strangerRaw = StrangerVisibility.aetherUserOnly.rawValue

    // Что незнакомцу позволено сделать. Видимость и разрешение — разные вещи.
    @AppStorage("nearby.canOpenProfile") var strangersCanOpenProfile = true
    @AppStorage("nearby.canOpenChat") var strangersCanOpenChat = true
    @AppStorage("nearby.canMessage") var strangersCanMessage = false
    @AppStorage("nearby.canSendFile") var strangersCanSendFile = false
    @AppStorage("nearby.canCall") var strangersCanCall = false

    /// До какого момента действует временная видимость. nil — постоянная.
    @AppStorage("nearby.visibleUntil") private var visibleUntilTs: Double = 0

    private init() {}

    var audience: NearbyAudience {
        get { NearbyAudience(rawValue: audienceRaw) ?? .contacts }
        set { audienceRaw = newValue.rawValue; objectWillChange.send() }
    }

    var strangerVisibility: StrangerVisibility {
        get { StrangerVisibility(rawValue: strangerRaw) ?? .aetherUserOnly }
        set { strangerRaw = newValue.rawValue; objectWillChange.send() }
    }

    /// Временная видимость: включить на срок и откатиться самому.
    func makeVisible(for seconds: TimeInterval?) {
        enabled = true
        visibleUntilTs = seconds.map { Date().timeIntervalSince1970 + $0 } ?? 0
        objectWillChange.send()
    }

    var visibleUntil: Date? {
        visibleUntilTs > 0 ? Date(timeIntervalSince1970: visibleUntilTs) : nil
    }

    /// Действительно ли мы сейчас должны объявлять о себе.
    ///
    /// Проверяет и срок временной видимости: истёк — откатываемся сами, не
    /// дожидаясь, пока человек вспомнит. Это и есть смысл временного режима.
    var shouldAdvertise: Bool {
        guard enabled, audience != .nobody else { return false }
        if let until = visibleUntil, until < Date() {
            return false
        }
        return bluetoothVisible
    }

    /// Снять истёкшую временную видимость. Зовётся сервисом обнаружения.
    func expireTemporaryVisibilityIfNeeded() {
        guard let until = visibleUntil, until < Date() else { return }
        visibleUntilTs = 0
        enabled = false
        objectWillChange.send()
    }
}
