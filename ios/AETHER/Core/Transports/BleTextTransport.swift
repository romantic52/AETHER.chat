import Foundation
import CoreBluetooth

// Прямая доставка текста по Bluetooth LE — резервный путь там, где нет сети.
//
// Протокол ОБЩИЙ с Android, менять его в одиночку нельзя:
//   сервис        ae7a0001-0000-4000-8000-000000000001
//   PREKEY  (read)  ae7a0002-…  → JSON связки отправителю
//   INBOX   (write) ae7a0003-…  ← кадры [флаг][данные], флаг 0x00 — последний
//   полезная нагрузка: {"from": "<user_id>", "envelope": { … }}
//
// Рукопожатие идёт БЕЗ сервера: связку отдаёт сам собеседник. Криптография не
// своя — те же Olm и закрепление ключа, что и в сети; здесь только байты.
//
// Только текст: по GATT крупное ползёт минутами, под медиа нужен L2CAP.
@MainActor
final class BleTextTransport: NSObject {

    struct Prekey {
        let userId: String
        let deviceId: String
        let identityKeyB64: String
        let oneTimeKeyB64: String
    }

    static let serviceUUID = CBUUID(string: "AE7A0001-0000-4000-8000-000000000001")
    static let prekeyUUID = CBUUID(string: "AE7A0002-0000-4000-8000-000000000001")
    static let inboxUUID = CBUUID(string: "AE7A0003-0000-4000-8000-000000000001")

    private static let frameLast: UInt8 = 0x00
    private static let frameMore: UInt8 = 0x01
    /// Текст и только текст: всё крупное обязано ехать другим транспортом.
    private static let maxMessageBytes = 64 * 1024
    private static let connectTimeout: TimeInterval = 15
    private static let ioTimeout: TimeInterval = 10

    /// Наш идентификатор — уходит в конверте как отправитель.
    var myUserId: String = ""
    /// Наша связка тому, кто подключился.
    var ownPrekey: (() throws -> Prekey)?
    /// Запечатать текст связкой собеседника.
    var seal: ((String, Prekey, String) throws -> String)?
    /// Отдать принятый конверт обычному пути входящих.
    var deliver: ((String, String) -> Void)?

    private var peripheralManager: CBPeripheralManager?
    private var central: CBCentralManager?

    /// Накопители кусков: центральный → собранные байты.
    private var inbound: [UUID: Data] = [:]

    // Ожидания активной отправки. Одна за раз: стек GATT не любит parallel.
    private var pendingConnect: CheckedContinuation<Void, Error>?
    private var pendingServices: CheckedContinuation<Void, Error>?
    private var pendingPrekey: CheckedContinuation<String, Error>?
    private var pendingWrite: CheckedContinuation<Void, Error>?
    private var activePeripheral: CBPeripheral?

    // MARK: - Приём

    func startServer() {
        guard peripheralManager == nil else { return }
        peripheralManager = CBPeripheralManager(delegate: self, queue: .main)
    }

    func stopServer() {
        peripheralManager?.removeAllServices()
        peripheralManager = nil
        inbound.removeAll()
    }

    private func publishService() {
        guard let manager = peripheralManager, manager.state == .poweredOn else { return }
        let prekey = CBMutableCharacteristic(type: Self.prekeyUUID,
                                             properties: [.read],
                                             value: nil,
                                             permissions: [.readable])
        let inbox = CBMutableCharacteristic(type: Self.inboxUUID,
                                            properties: [.write],
                                            value: nil,
                                            permissions: [.writeable])
        let service = CBMutableService(type: Self.serviceUUID, primary: true)
        service.characteristics = [prekey, inbox]
        manager.removeAllServices()
        manager.add(service)
    }

    // MARK: - Отправка

    /// Подключиться к найденному устройству, забрать связку, запечатать и отдать.
    func send(peerId: String, peripheralId: UUID, wirePayload: String) async throws {
        if central == nil {
            central = CBCentralManager(delegate: self, queue: .main)
        }
        guard let central else { throw TransportError.unreachable("Bluetooth недоступен") }
        guard central.state == .poweredOn else {
            throw TransportError.unreachable("Bluetooth выключен")
        }
        guard let peripheral = central.retrievePeripherals(withIdentifiers: [peripheralId]).first else {
            throw TransportError.unreachable("устройство больше не видно")
        }

        activePeripheral = peripheral
        peripheral.delegate = self
        defer {
            central.cancelPeripheralConnection(peripheral)
            activePeripheral = nil
        }

        try await withTimeout(Self.connectTimeout) {
            try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
                self.pendingConnect = c
                central.connect(peripheral)
            }
        }
        try await withTimeout(Self.connectTimeout) {
            try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
                self.pendingServices = c
                peripheral.discoverServices([Self.serviceUUID])
            }
        }

        guard let service = peripheral.services?.first(where: { $0.uuid == Self.serviceUUID }),
              let prekeyChar = service.characteristics?.first(where: { $0.uuid == Self.prekeyUUID }),
              let inboxChar = service.characteristics?.first(where: { $0.uuid == Self.inboxUUID })
        else { throw TransportError.unreachable("собеседник не отвечает нужным профилем") }

        let raw = try await withTimeout(Self.ioTimeout) {
            try await withCheckedThrowingContinuation { (c: CheckedContinuation<String, Error>) in
                self.pendingPrekey = c
                peripheral.readValue(for: prekeyChar)
            }
        }
        let peer = try Self.decodePrekey(raw)

        guard let seal else { throw TransportError.failed("нет запечатывания") }
        let envelope = try seal(peerId, peer, wirePayload)
        // from — НАШ идентификатор: получателю нужно знать, от кого конверт,
        // а свой он и так знает.
        let payload: [String: Any] = [
            "from": myUserId,
            "envelope": (try? JSONSerialization.jsonObject(with: Data(envelope.utf8))) ?? [:]
        ]
        let data = try JSONSerialization.data(withJSONObject: payload)
        guard data.count <= Self.maxMessageBytes else {
            throw TransportError.rejected("по Bluetooth уходит только текст")
        }
        try await writeChunked(data, to: peripheral, characteristic: inboxChar)
    }

    private func writeChunked(_ data: Data,
                              to peripheral: CBPeripheral,
                              characteristic: CBCharacteristic) async throws {
        // Один байт уходит под флаг «последний кусок».
        let room = max(20, peripheral.maximumWriteValueLength(for: .withResponse) - 1)
        var offset = 0
        while offset < data.count {
            let size = min(room, data.count - offset)
            let isLast = offset + size >= data.count
            var frame = Data([isLast ? Self.frameLast : Self.frameMore])
            frame.append(data.subdata(in: offset ..< offset + size))

            try await withTimeout(Self.ioTimeout) {
                try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
                    self.pendingWrite = c
                    // Строго с подтверждением и по одному: без этого стек молча
                    // теряет куски, и собеседник получает обрубок.
                    peripheral.writeValue(frame, for: characteristic, type: .withResponse)
                }
            }
            offset += size
        }
    }

    // MARK: - Разбор связки

    private static func decodePrekey(_ raw: String) throws -> Prekey {
        guard let data = raw.data(using: .utf8),
              let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let userId = json["user_id"] as? String,
              let deviceId = json["device_id"] as? String,
              let identity = json["identity_key_b64"] as? String,
              let otk = json["one_time_key_b64"] as? String
        else { throw TransportError.failed("непонятная связка") }
        return Prekey(userId: userId, deviceId: deviceId,
                      identityKeyB64: identity, oneTimeKeyB64: otk)
    }

    private static func encodePrekey(_ prekey: Prekey) throws -> Data {
        try JSONSerialization.data(withJSONObject: [
            "user_id": prekey.userId,
            "device_id": prekey.deviceId,
            "identity_key_b64": prekey.identityKeyB64,
            "one_time_key_b64": prekey.oneTimeKeyB64,
        ])
    }

    /// Таймаут поверх ожидания: зависший GATT не должен держать очередь вечно.
    private func withTimeout<T: Sendable>(
        _ seconds: TimeInterval,
        _ body: @escaping @MainActor @Sendable () async throws -> T
    ) async throws -> T {
        try await withThrowingTaskGroup(of: T.self) { group in
            group.addTask { try await body() }
            group.addTask {
                try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                throw TransportError.timeout
            }
            guard let first = try await group.next() else { throw TransportError.timeout }
            group.cancelAll()
            return first
        }
    }

    private func failPending(_ error: Error) {
        pendingConnect?.resume(throwing: error); pendingConnect = nil
        pendingServices?.resume(throwing: error); pendingServices = nil
        pendingPrekey?.resume(throwing: error); pendingPrekey = nil
        pendingWrite?.resume(throwing: error); pendingWrite = nil
    }
}

// MARK: - Роль периферии: отдаём связку, принимаем текст

extension BleTextTransport: CBPeripheralManagerDelegate {
    nonisolated func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        Task { @MainActor in
            if peripheral.state == .poweredOn { self.publishService() }
        }
    }

    nonisolated func peripheralManager(_ peripheral: CBPeripheralManager,
                                       didReceiveRead request: CBATTRequest) {
        Task { @MainActor in
            guard request.characteristic.uuid == Self.prekeyUUID,
                  let provider = self.ownPrekey,
                  let bundle = try? provider(),
                  let data = try? Self.encodePrekey(bundle) else {
                peripheral.respond(to: request, withResult: .requestNotSupported)
                return
            }
            guard request.offset <= data.count else {
                peripheral.respond(to: request, withResult: .invalidOffset)
                return
            }
            // Длинную связку система забирает частями, отдавая offset.
            request.value = data.subdata(in: request.offset ..< data.count)
            peripheral.respond(to: request, withResult: .success)
        }
    }

    nonisolated func peripheralManager(_ peripheral: CBPeripheralManager,
                                       didReceiveWrite requests: [CBATTRequest]) {
        Task { @MainActor in
            for request in requests {
                guard request.characteristic.uuid == Self.inboxUUID,
                      let value = request.value, !value.isEmpty else {
                    peripheral.respond(to: request, withResult: .requestNotSupported)
                    return
                }
                let id = request.central.identifier
                var buffer = self.inbound[id] ?? Data()
                guard buffer.count + value.count - 1 <= Self.maxMessageBytes else {
                    // Чужой может лить бесконечно: рвём, а не растём в памяти.
                    self.inbound[id] = nil
                    peripheral.respond(to: request, withResult: .insufficientResources)
                    return
                }
                buffer.append(value.dropFirst())
                let isLast = value[value.startIndex] == Self.frameLast
                if isLast {
                    self.inbound[id] = nil
                    self.handleComplete(buffer)
                } else {
                    self.inbound[id] = buffer
                }
            }
            if let first = requests.first {
                peripheral.respond(to: first, withResult: .success)
            }
        }
    }

    private func handleComplete(_ data: Data) {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let from = json["from"] as? String,
              let envelope = json["envelope"],
              let envelopeData = try? JSONSerialization.data(withJSONObject: envelope) else {
            return
        }
        deliver?(from, String(decoding: envelopeData, as: UTF8.self))
    }
}

// MARK: - Роль центрального: подключаемся и отдаём

extension BleTextTransport: CBCentralManagerDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {}

    nonisolated func centralManager(_ central: CBCentralManager,
                                    didConnect peripheral: CBPeripheral) {
        Task { @MainActor in
            self.pendingConnect?.resume(returning: ())
            self.pendingConnect = nil
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager,
                                    didFailToConnect peripheral: CBPeripheral,
                                    error: Error?) {
        Task { @MainActor in
            self.failPending(TransportError.unreachable("не удалось подключиться"))
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager,
                                    didDisconnectPeripheral peripheral: CBPeripheral,
                                    error: Error?) {
        Task { @MainActor in
            self.failPending(TransportError.unreachable("соединение разорвано"))
        }
    }
}

extension BleTextTransport: CBPeripheralDelegate {
    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        Task { @MainActor in
            guard error == nil,
                  let service = peripheral.services?.first(where: { $0.uuid == Self.serviceUUID })
            else {
                self.failPending(TransportError.unreachable("профиль не найден"))
                return
            }
            peripheral.discoverCharacteristics([Self.prekeyUUID, Self.inboxUUID], for: service)
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral,
                                didDiscoverCharacteristicsFor service: CBService,
                                error: Error?) {
        Task { @MainActor in
            if error == nil {
                self.pendingServices?.resume(returning: ())
            } else {
                self.pendingServices?.resume(throwing: TransportError.unreachable("нет характеристик"))
            }
            self.pendingServices = nil
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral,
                                didUpdateValueFor characteristic: CBCharacteristic,
                                error: Error?) {
        Task { @MainActor in
            guard characteristic.uuid == Self.prekeyUUID else { return }
            if let data = characteristic.value, error == nil {
                self.pendingPrekey?.resume(returning: String(decoding: data, as: UTF8.self))
            } else {
                self.pendingPrekey?.resume(throwing: TransportError.failed("связка не прочиталась"))
            }
            self.pendingPrekey = nil
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral,
                                didWriteValueFor characteristic: CBCharacteristic,
                                error: Error?) {
        Task { @MainActor in
            if error == nil {
                self.pendingWrite?.resume(returning: ())
            } else {
                self.pendingWrite?.resume(throwing: TransportError.failed("кусок не записался"))
            }
            self.pendingWrite = nil
        }
    }
}
