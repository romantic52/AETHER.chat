import Foundation
import CoreBluetooth

// Обнаружение рядом по Bluetooth LE.
//
// ЧЕСТНО ПРО ОГРАНИЧЕНИЕ ПЛАТФОРМЫ. iOS не даёт приложению объявлять в эфир
// произвольные данные — только имя и список UUID сервисов. Поэтому маячок
// упакован в 128-битный UUID (формат в ядре, чтобы Android совпал побайтно).
// Из этого же следует, что сканировать приходится без фильтра по UUID: наш
// UUID меняется каждые 15 минут, заранее его не знает никто. А сканирование
// без фильтра работает только пока приложение открыто.
//
// Значит обнаружение iPhone ↔ Android надёжно работает, когда приложение на
// экране. Обещать «найду тебя в кармане» нельзя, и интерфейс не обещает.

/// Найденный рядом. Пока это только маячок: кто за ним стоит, выясняет
/// рукопожатие с подписью, а не сам факт находки.
struct NearbyPeer: Identifiable, Equatable {
    /// Идентификатор находки в пределах сеанса. НЕ идентичность человека.
    let id: String
    /// Опознан как знакомый: его ключ обнаружения у нас есть.
    var known: Bool
    var identityId: String?
    var displayName: String?
    var rssi: Int
    var lastSeen: Date

    /// Грубая близость. Bluetooth не даёт расстояния — только «сильнее/слабее»,
    /// поэтому и категории грубые, а в интерфейсе стоит пометка «приблизительно».
    enum Proximity: String {
        case veryClose, near, far, distant

        var title: String {
            switch self {
            case .veryClose: return "Очень близко"
            case .near: return "Рядом"
            case .far: return "Недалеко"
            case .distant: return "Далеко"
            }
        }

        /// Условный диапазон — именно условный, отсюда и слово «около».
        var hint: String {
            switch self {
            case .veryClose: return "около 1 м"
            case .near: return "около 1–5 м"
            case .far: return "около 5–15 м"
            case .distant: return "дальше 15 м"
            }
        }
    }

    var proximity: Proximity {
        switch rssi {
        case (-55)...: return .veryClose
        case (-70)..<(-55): return .near
        case (-85)..<(-70): return .far
        default: return .distant
        }
    }
}

@MainActor
final class NearbyDiscoveryService: NSObject, ObservableObject {
    static let shared = NearbyDiscoveryService()

    @Published private(set) var peers: [NearbyPeer] = []
    @Published private(set) var scanning = false
    @Published private(set) var advertising = false
    /// Состояние радио словами, которые можно показать человеку.
    @Published private(set) var radioState: String?

    private var central: CBCentralManager?
    private var peripheral: CBPeripheralManager?
    private var rotationTask: Task<Void, Never>?
    private var pruneTask: Task<Void, Never>?

    /// Ключи обнаружения знакомых: их EDI мы умеем узнавать.
    /// Свой ключ здесь же — чтобы не принимать собственные объявления за чужие.
    private var knownKeys: [String: String] = [:]   // identityId → discoveryKey
    private var ownKey: String?

    /// Находка живёт столько без новых объявлений, потом исчезает из списка.
    private let peerTTL: TimeInterval = 20

    private override init() { super.init() }

    // MARK: - Управление

    func start(ownDiscoveryKey: String, knownKeys: [String: String]) {
        self.ownKey = ownDiscoveryKey
        self.knownKeys = knownKeys
        if central == nil {
            central = CBCentralManager(delegate: self, queue: .main)
        }
        if peripheral == nil {
            peripheral = CBPeripheralManager(delegate: self, queue: .main)
        }
        startRotation()
        startPruning()
    }

    /// Остановить по-настоящему: прекратить объявления, прекратить ответы на
    /// сканирование, забыть найденных. «Невидим» не должен означать «спрятали
    /// список в интерфейсе».
    func stop() {
        rotationTask?.cancel(); rotationTask = nil
        pruneTask?.cancel(); pruneTask = nil
        peripheral?.stopAdvertising()
        central?.stopScan()
        advertising = false
        scanning = false
        peers.removeAll()
    }

    // MARK: - Объявление о себе

    private func startRotation() {
        rotationTask?.cancel()
        rotationTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.refreshAdvertisement()
                // Пересобираем чаще интервала ротации: так объявление сменится
                // сразу на границе окна, а не через четверть часа после.
                try? await Task.sleep(nanoseconds: 60 * 1_000_000_000)
            }
        }
    }

    private func refreshAdvertisement() async {
        let privacy = NearbyPrivacy.shared
        privacy.expireTemporaryVisibilityIfNeeded()
        guard privacy.shouldAdvertise, let ownKey else {
            peripheral?.stopAdvertising()
            advertising = false
            return
        }
        guard peripheral?.state == .poweredOn else { return }

        do {
            let beacon = try nearbyBuildBeacon(discoveryKeyB64: ownKey,
                                               epoch: nearbyCurrentEpoch())
            let uuidString = try nearbyBeaconToUuid(beacon: beacon)
            guard let uuid = UUID(uuidString: uuidString) else { return }
            peripheral?.stopAdvertising()
            // В эфир уходит ТОЛЬКО ротируемый UUID: ни имени, ни логина, ни
            // постоянного идентификатора. Локальное имя не указываем намеренно.
            peripheral?.startAdvertising([
                CBAdvertisementDataServiceUUIDsKey: [CBUUID(nsuuid: uuid)]
            ])
            advertising = true
        } catch {
            advertising = false
        }
    }

    // MARK: - Поиск чужих

    private func startScanning() {
        guard NearbyPrivacy.shared.enabled, central?.state == .poweredOn else { return }
        // Без фильтра по сервисам: наш UUID меняется каждые 15 минут, заранее
        // его не знает никто. Плата за это — работа только на переднем плане.
        central?.scanForPeripherals(withServices: nil,
                                    options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
        scanning = true
    }

    private func startPruning() {
        pruneTask?.cancel()
        pruneTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 3 * 1_000_000_000)
                guard let self else { return }
                let cutoff = Date().addingTimeInterval(-self.peerTTL)
                let before = self.peers.count
                self.peers.removeAll { $0.lastSeen < cutoff }
                if self.peers.count != before { self.objectWillChange.send() }
            }
        }
    }

    fileprivate func handle(advertisement: [String: Any], rssi: Int) {
        guard let uuids = advertisement[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] else { return }
        let epoch = nearbyCurrentEpoch()

        for uuid in uuids {
            guard let beacon = nearbyUuidToBeacon(uuid: uuid.uuidString) else { continue }

            // Собственные объявления отбрасываем: иначе телефон нашёл бы сам себя.
            if let ownKey, nearbyMatchBeacon(beacon: beacon, discoveryKeyB64: ownKey, epoch: epoch) {
                continue
            }

            var identityId: String?
            for (identity, key) in knownKeys
            where nearbyMatchBeacon(beacon: beacon, discoveryKeyB64: key, epoch: epoch) {
                identityId = identity
                break
            }

            // Ключ находки — по байтам маячка. Он меняется вместе с ротацией,
            // и это правильно: связывать находки между интервалами мы не должны
            // ни у себя, ни в интерфейсе.
            let key = beacon.map { String(format: "%02x", $0) }.joined()
            upsert(key: key, identityId: identityId, rssi: rssi)
        }
    }

    private func upsert(key: String, identityId: String?, rssi: Int) {
        if let idx = peers.firstIndex(where: { $0.id == key }) {
            peers[idx].rssi = rssi
            peers[idx].lastSeen = Date()
            peers[idx].identityId = identityId ?? peers[idx].identityId
            peers[idx].known = peers[idx].identityId != nil
        } else {
            peers.append(NearbyPeer(id: key, known: identityId != nil,
                                    identityId: identityId, displayName: nil,
                                    rssi: rssi, lastSeen: Date()))
        }
    }
}

extension NearbyDiscoveryService: CBCentralManagerDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        Task { @MainActor in
            switch central.state {
            case .poweredOn:
                radioState = nil
                startScanning()
            case .poweredOff:
                radioState = "Bluetooth выключен"
                scanning = false
            case .unauthorized:
                radioState = "Aether не разрешён доступ к Bluetooth"
                scanning = false
            case .unsupported:
                radioState = "Устройство не поддерживает Bluetooth LE"
            default:
                radioState = nil
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager,
                                    didDiscover peripheral: CBPeripheral,
                                    advertisementData: [String: Any],
                                    rssi RSSI: NSNumber) {
        Task { @MainActor in
            handle(advertisement: advertisementData, rssi: RSSI.intValue)
        }
    }
}

extension NearbyDiscoveryService: CBPeripheralManagerDelegate {
    nonisolated func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        Task { @MainActor in
            if peripheral.state == .poweredOn { await refreshAdvertisement() }
            else { advertising = false }
        }
    }
}
