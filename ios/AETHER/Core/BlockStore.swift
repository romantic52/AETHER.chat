import Foundation
import SwiftUI

/// Локальный чёрный список — как в Android-клиенте: входящие от заблокированных
/// отбрасываются, но серверу ПОДТВЕРЖДАЮТСЯ, иначе они приходили бы снова при
/// каждом опросе и очередь инбокса не двигалась бы.
///
/// Список локальный намеренно: серверу знать, кого ты заблокировал, незачем.
@MainActor
final class BlockStore: ObservableObject {
    static let shared = BlockStore()
    private let key = "blockedPeers"

    @Published private(set) var blocked: Set<String> = []

    private init() {
        blocked = Set(UserDefaults.standard.stringArray(forKey: key) ?? [])
    }

    func isBlocked(_ peer: String) -> Bool { blocked.contains(peer.lowercased()) }

    func toggle(_ peer: String) {
        let id = peer.lowercased()
        if blocked.contains(id) { blocked.remove(id) } else { blocked.insert(id) }
        UserDefaults.standard.set(Array(blocked), forKey: key)
    }
}
