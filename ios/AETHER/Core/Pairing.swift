import Foundation
import SwiftUI

/// Привязка второго устройства по QR.
///
/// Как это устроено. Новое устройство создаёт ЭФЕМЕРНУЮ пару ключей, объявляет
/// заявку на сервере и показывает QR со ссылкой
/// `aether://pair?v=1&pid=…&sec=…&pub=…&host=…` — формат общий с Android-клиентом.
/// Доверенное устройство сканирует её, шифрует свой bundle на эфемерный ключ и
/// отправляет подтверждение. Сервер видит только шифротекст: он тут почтовый
/// ящик, а не участник обмена.
struct PairingBundle: Codable {
    var userId: String
    var token: String
    var publicKey: String
    var privateKey: String
}

/// Конверт, который едет через сервер. Публичный ключ отправителя нужен, чтобы
/// новое устройство могло расшифровать: crypto_box требует обе стороны.
private struct PairingEnvelope: Codable {
    var spub: String
    var nonce: String
    var ct: String
}

struct PairingLink {
    var pairingId: String
    var secret: String
    var ephPubB64: String
    var host: String

    /// Разбор ссылки из QR. Формат сверен с Android (pairing/PairingLink.kt).
    static func parse(_ raw: String) -> PairingLink? {
        guard let url = URLComponents(string: raw.trimmingCharacters(in: .whitespaces)),
              url.scheme?.lowercased() == "aether", url.host?.lowercased() == "pair" else { return nil }
        let q = Dictionary(uniqueKeysWithValues: (url.queryItems ?? []).map { ($0.name, $0.value ?? "") })
        guard q["v"] == "1",
              let pid = q["pid"], !pid.isEmpty,
              let sec = q["sec"], !sec.isEmpty,
              let pub = q["pub"], !pub.isEmpty,
              let host = q["host"], host.hasPrefix("http") else { return nil }
        return PairingLink(pairingId: pid, secret: sec, ephPubB64: pub,
                           host: String(host.reversed().drop { $0 == "/" }.reversed()))
    }

    var url: String {
        var c = URLComponents()
        c.scheme = "aether"
        c.host = "pair"
        c.queryItems = [.init(name: "v", value: "1"), .init(name: "pid", value: pairingId),
                        .init(name: "sec", value: secret), .init(name: "pub", value: ephPubB64),
                        .init(name: "host", value: host)]
        return c.string ?? ""
    }
}

enum PairingError: LocalizedError {
    case server(Int)
    case badLink
    case badBundle

    var errorDescription: String? {
        switch self {
        case .server(let code): return "Сервер ответил \(code)"
        case .badLink: return "Ссылка не распознана"
        case .badBundle: return "Не удалось расшифровать данные привязки"
        }
    }
}

@MainActor
final class PairingService: ObservableObject {
    /// Ссылка для QR и эфемерный приватный ключ, которым потом расшифруем ответ.
    struct Draft {
        var link: PairingLink
        var ephPrivB64: String
    }

    // MARK: - Сторона нового устройства

    func start(host: String) async throws -> Draft {
        let kp = generateKeypair()
        let pairingId = UUID().uuidString
        let secret = Data((0..<24).map { _ in UInt8.random(in: 0...255) }).base64EncodedString()

        let body: [String: String] = ["pairing_id": pairingId, "secret": secret,
                                      "eph_pub_b64": kp.publicB64]
        try await post(host: host, path: "/pairing/start", body: body, token: nil)
        return Draft(link: PairingLink(pairingId: pairingId, secret: secret,
                                       ephPubB64: kp.publicB64, host: host),
                     ephPrivB64: kp.privateB64)
    }

    /// nil — подтверждения ещё нет.
    func claim(_ draft: Draft) async throws -> PairingBundle? {
        var c = URLComponents(string: draft.link.host + "/pairing/claim")!
        c.queryItems = [.init(name: "pairing_id", value: draft.link.pairingId),
                        .init(name: "pairing_secret", value: draft.link.secret)]
        var request = URLRequest(url: c.url!)
        request.timeoutInterval = 15
        let (data, response) = try await URLSession.shared.data(for: request)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard code == 200 else { throw PairingError.server(code) }

        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
        if json["status"] as? String != "approved" { return nil }
        guard let b64 = json["encrypted_bundle_b64"] as? String,
              let raw = Data(base64Encoded: b64),
              let env = try? JSONDecoder().decode(PairingEnvelope.self, from: raw),
              let ct = Data(base64Encoded: env.ct) else { throw PairingError.badBundle }

        let plain = try boxDecrypt(nonceB64: env.nonce, ciphertext: ct,
                                   senderPubB64: env.spub, recipientPrivB64: draft.ephPrivB64)
        return try JSONDecoder().decode(PairingBundle.self, from: plain)
    }

    // MARK: - Сторона доверенного устройства

    func approve(_ link: PairingLink, bundle: PairingBundle, token: String) async throws {
        let kp = generateKeypair()
        let plain = try JSONEncoder().encode(bundle)
        let sealed = try boxEncrypt(plaintext: plain, recipientPubB64: link.ephPubB64,
                                    senderPrivB64: kp.privateB64)
        let envelope = PairingEnvelope(spub: kp.publicB64, nonce: sealed.nonceB64,
                                       ct: sealed.ciphertext.base64EncodedString())
        let packed = try JSONEncoder().encode(envelope).base64EncodedString()

        try await post(host: link.host, path: "/pairing/approve", token: token,
                       body: ["pairing_id": link.pairingId,
                              "pairing_secret": link.secret,
                              "encrypted_bundle_b64": packed,
                              "platform": "ios"])
    }

    // MARK: - Общее

    private func post(host: String, path: String, body: [String: String], token: String?) async throws {
        try await post(host: host, path: path, token: token, body: body)
    }

    private func post(host: String, path: String, token: String?, body: [String: String]) async throws {
        var request = URLRequest(url: URL(string: host + path)!)
        request.httpMethod = "POST"
        request.timeoutInterval = 20
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (_, response) = try await URLSession.shared.data(for: request)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else { throw PairingError.server(code) }
    }
}
