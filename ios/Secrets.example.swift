import Foundation

/// ШАБЛОН. Скопировать в AETHER/Core/Secrets.swift и подставить свои значения:
///
///     cp ios/Secrets.example.swift ios/AETHER/Core/Secrets.swift
///
/// Сам AETHER/Core/Secrets.swift в git не попадает. Этот файл лежит в ios/, а не
/// в ios/AETHER/, намеренно: XcodeGen глобит только каталог AETHER, поэтому
/// шаблон не компилируется и не конфликтует с настоящим Secrets.swift.
enum Secrets {
    /// Хост API и WebSocket, без схемы. Например: aether.example.com
    static let host = "<SERVER_HOST>"

    /// STUN/TURN. Учётка выдаётся coturn'ом на сервере.
    static let turnHost = "<TURN_HOST>"
    static let turnPort = 3478
    static let turnUsername = "<TURN_USERNAME>"
    static let turnCredential = "<TURN_CREDENTIAL>"

    static var baseURL: String { "https://\(host)" }
    static var wsBaseURL: String { "wss://\(host)" }
}
