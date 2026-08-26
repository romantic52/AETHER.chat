import SwiftUI

// «Рядом» — кто из пользователей Aether находится поблизости.
//
// Намеренно НЕ радар с честной геометрией: Bluetooth даёт силу сигнала, а не
// расстояние. Рисовать точные позиции значило бы врать, поэтому здесь список
// с грубыми категориями близости и пометкой «приблизительно».
struct NearbyView: View {
    @EnvironmentObject var session: Session
    @Environment(\.palette) private var palette

    @StateObject private var service = NearbyDiscoveryService.shared
    @StateObject private var privacy = NearbyPrivacy.shared
    @State private var showPrivacy = false

    var body: some View {
        List {
            if !privacy.enabled {
                Section {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Обнаружение выключено")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundStyle(palette.textPrimary)
                        Text("Включите, чтобы находить пользователей Aether поблизости и быть найденным. Работает, пока приложение открыто.")
                            .font(.caption).foregroundStyle(palette.textSecondary)
                        Button("Включить обнаружение") { enable() }
                            .font(.headline)
                            .foregroundStyle(palette.accent)
                            .padding(.top, 2)
                    }
                    .padding(.vertical, 6)
                    .listRowBackground(palette.surface)
                }
            } else {
                if let state = service.radioState {
                    Section {
                        Label(state, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(palette.danger)
                            .listRowBackground(palette.surface)
                    }
                }

                Section {
                    if service.radioState != nil {
                        // Радио недоступно — не изображаем поиск. Крутящийся
                        // индикатор рядом с сообщением «Bluetooth выключен»
                        // означал бы, что приложение всё-таки что-то делает.
                        Text("Поиск не идёт")
                            .foregroundStyle(palette.textSecondary)
                            .listRowBackground(palette.surface)
                    } else if service.peers.isEmpty {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text("Ищем поблизости…").foregroundStyle(palette.textSecondary)
                        }
                        .listRowBackground(palette.surface)
                    } else {
                        ForEach(service.peers.sorted { $0.rssi > $1.rssi }) { peer in
                            peerRow(peer)
                                .listRowBackground(palette.surface)
                        }
                    }
                } header: {
                    Text("Поблизости")
                } footer: {
                    Text("Расстояние определяется по силе сигнала и указано приблизительно. Обнаружение работает, пока приложение открыто: iOS не позволяет надёжно искать устройства других платформ в фоне.")
                }
            }

            Section {
                Button { showPrivacy = true } label: {
                    SettingsLabel("Кто меня видит", icon: "eye.slash", color: .indigo)
                }
                .listRowBackground(palette.surface)

                if privacy.enabled {
                    Button(role: .destructive) { disable() } label: {
                        Label("Стать невидимым", systemImage: "eye.slash.fill")
                    }
                    .listRowBackground(palette.surface)
                }
            } footer: {
                if let until = privacy.visibleUntil {
                    Text("Видимость включена до \(Self.timeText(until)); потом отключится сама.")
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(palette.background.ignoresSafeArea())
        .navigationTitle("Рядом")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showPrivacy) {
            NavigationStack { NearbyPrivacyView() }
        }
        .onAppear { if privacy.enabled { enable() } }
        .onDisappear {
            // Экран закрыт — радио молчит. Держать сканирование ради экрана,
            // на который никто не смотрит, значит зря жечь батарею.
            service.stop()
        }
    }

    private func peerRow(_ peer: NearbyPeer) -> some View {
        HStack(spacing: 12) {
            Circle()
                .fill(peer.known ? palette.accent.opacity(0.2) : palette.surfaceElevated)
                .frame(width: 42, height: 42)
                .overlay(
                    Image(systemName: peer.known ? "person.fill" : "person.fill.questionmark")
                        .foregroundStyle(peer.known ? palette.accent : palette.textSecondary)
                )
            VStack(alignment: .leading, spacing: 2) {
                Text(peer.known ? (peer.displayName ?? peer.identityId ?? "Контакт")
                                : "Пользователь Aether")
                    .foregroundStyle(palette.textPrimary)
                Text(peer.known ? "Контакт · \(peer.proximity.title)" : peer.proximity.title)
                    .font(.caption).foregroundStyle(palette.textSecondary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text(peer.proximity.hint)
                    .font(.caption2).foregroundStyle(palette.textSecondary)
                Text("приблизительно")
                    .font(.system(size: 10)).foregroundStyle(palette.textSecondary.opacity(0.7))
            }
        }
    }

    private func enable() {
        privacy.enabled = true
        let serverId = ServerContext.serverId
        let userId = session.myId.lowercased()
        guard !userId.isEmpty else { return }

        // Ключ обнаружения создаётся один раз на личность и живёт в Keychain.
        let keyName = Keychain.discoveryKey(serverId, userId)
        let key: String
        if let existing = Keychain.string(for: keyName), !existing.isEmpty {
            key = existing
        } else {
            key = nearbyNewDiscoveryKey()
            Keychain.set(key, for: keyName)
        }
        // Ключи знакомых появятся, когда будет обмен ими по E2EE-каналу;
        // пока список пуст, и все находки честно показываются незнакомцами.
        service.start(ownDiscoveryKey: key, knownKeys: [:])
    }

    private func disable() {
        privacy.enabled = false
        service.stop()
    }

    private static func timeText(_ date: Date) -> String {
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        return f.string(from: date)
    }
}
