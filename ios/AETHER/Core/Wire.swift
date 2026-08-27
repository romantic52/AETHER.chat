import Foundation

// Сборка/разбор канонических wire-payload'ов (см. WIRE_PROTOCOL.md).
// Сам конверт (шифрование) делает ядро; здесь — только JSON plaintext'а внутри конверта.
// Байт-в-байт совместимо с Android/web: ключи полей и типы строго по канону.

enum Wire {
    // MARK: - Сборка исходящих (→ JSON-строка для CoreClient.sendDirect/sendGroup)

    static func text(_ text: String, replyToId: String? = nil, replyToText: String? = nil, fwdFrom: String? = nil) -> String {
        var obj: [String: Any] = ["type": "text", "text": text]
        if let r = replyToId { obj["reply_to_id"] = r }
        if let rt = replyToText { obj["reply_to_text"] = rt }
        if let f = fwdFrom { obj["fwd_from"] = f }
        return json(obj)
    }

    static func media(fileId: String, symKey: String, mimeType: String, nonce: String,
                      kind: String? = nil, duration: Double? = nil,
                      fileName: String? = nil, fileSize: Int64? = nil,
                      caption: String? = nil, fwdFrom: String? = nil,
                      replyToId: String? = nil, replyToText: String? = nil) -> String {
        var obj: [String: Any] = [
            "type": "media", "file_id": fileId, "sym_key": symKey,
            "mime_type": mimeType, "nonce": nonce,
        ]
        if let k = kind { obj["kind"] = k }
        if let d = duration { obj["duration"] = d }
        if let n = fileName { obj["file_name"] = n }
        if let s = fileSize { obj["file_size"] = s }
        if let c = caption { obj["caption"] = c }
        if let f = fwdFrom { obj["fwd_from"] = f }
        if let r = replyToId { obj["reply_to_id"] = r }
        if let rt = replyToText { obj["reply_to_text"] = rt }
        return json(obj)
    }

    static func edit(target: String, text: String) -> String {
        json(["type": "edit", "target": target, "text": text])
    }

    /// Пустой emoji = снять реакцию.
    static func reaction(target: String, emoji: String) -> String {
        json(["type": "reaction", "target": target, "emoji": emoji])
    }

    /// "delete" уже в каноне как web-native тип (см. WIRE_PROTOCOL.md) — раньше
    /// только молча игнорировался при приёме. Наши клиенты теперь его понимают
    /// в обе стороны (см. Messaging.handleIncoming), поэтому удаление сообщения
    /// долетает и до собеседника, а не остаётся только локальным.
    static func delete(target: String) -> String {
        json(["type": "delete", "target": target])
    }

    static func read() -> String { json(["type": "read"]) }
    static func delivered() -> String { json(["type": "delivered"]) }
    /// Получатель открыл исчезающее. Отметка отправителю, не команда на удаление:
    /// срок жизни принадлежит копии получателя.
    static func ephemeralViewed(target: String) -> String {
        json(["type": "ephemeral_viewed", "target": target])
    }

    private static func json(_ obj: [String: Any]) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys]),
              let s = String(data: data, encoding: .utf8) else { return "{}" }
        return s
    }

    // MARK: - Разбор входящих

    struct Payload {
        let type: String
        let raw: [String: Any]

        var text: String? { raw["text"] as? String }
        var replyToId: String? { raw["reply_to_id"] as? String }
        var replyToText: String? { raw["reply_to_text"] as? String }
        var fwdFrom: String? { raw["fwd_from"] as? String }
        var target: String? { raw["target"] as? String }
        var emoji: String? { raw["emoji"] as? String }

        // media
        var fileId: String? { raw["file_id"] as? String }
        var symKey: String? { raw["sym_key"] as? String }
        var nonce: String? { raw["nonce"] as? String }
        var mimeType: String? { raw["mime_type"] as? String }
        var kind: String? { raw["kind"] as? String }
        var duration: Double? {
            (raw["duration"] as? NSNumber)?.doubleValue
                ?? (raw["duration"] as? String).flatMap(Double.init)
        }
        var fileName: String? { (raw["file_name"] as? String) ?? (raw["filename"] as? String) }
        var fileSize: Int64? {
            (raw["file_size"] as? NSNumber)?.int64Value
                ?? (raw["file_size"] as? String).flatMap(Int64.init)
                ?? (raw["size"] as? NSNumber)?.int64Value
        }
        var caption: String? { raw["caption"] as? String }
        var inlineData: Data? {
            guard let value = raw["inline_data"] as? String,
                  value.hasPrefix("data:"), let comma = value.firstIndex(of: ","),
                  value[..<comma].contains(";base64") else { return nil }
            var encoded = String(value[value.index(after: comma)...])
                .replacingOccurrences(of: "-", with: "+")
                .replacingOccurrences(of: "_", with: "/")
            guard encoded.utf8.count <= 20_000_000 else { return nil }
            encoded += String(repeating: "=", count: (4 - encoded.count % 4) % 4)
            return Data(base64Encoded: encoded)
        }

        /// Отображаемый вид медиа (image|voice|video_note|file), с учётом legacy web-типов.
        var mediaKind: MediaKind {
            let k = kind ?? ""
            if k == "voice" { return .voice }
            if k == "video_msg" || k == "video_note" { return .videoNote }
            // Фото/видео, отправленные «файлом», остаются документами. Музыка —
            // исключение: даже kind:file открываем собственным аудиоплеером.
            if k == "file", !(mimeType ?? "").hasPrefix("audio/") { return .file }
            if k == "image" || (mimeType ?? "").hasPrefix("image/") { return .image }
            if k == "video" || (mimeType ?? "").hasPrefix("video/") { return .video }
            if k == "audio" || (mimeType ?? "").hasPrefix("audio/") { return .audio }
            return .file
        }
    }

    enum MediaKind { case image, video, audio, voice, videoNote, file }

    static func parse(_ jsonString: String) -> Payload? {
        guard let data = jsonString.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data, options: .fragmentsAllowed) else {
            // Самые ранние клиенты шифровали обычную строку без JSON-обёртки.
            return Payload(type: "text", raw: ["type": "text", "text": jsonString])
        }
        if let text = object as? String {
            return Payload(type: "text", raw: ["type": "text", "text": text])
        }
        guard var obj = object as? [String: Any], let oldType = obj["type"] as? String else { return nil }

        // Совместимость с web/Android до единого wire-протокола. После разбора UI
        // видит только современный payload и не размазывает legacy-ветки по экрану.
        if oldType == "text", obj["text"] == nil { obj["text"] = obj["content"] as? String ?? "" }
        if oldType == "edit" {
            obj["target"] = obj["target"] ?? obj["target_id"]
            obj["text"] = obj["text"] ?? obj["content"]
        }
        if oldType == "reaction" || oldType == "delete" {
            obj["target"] = obj["target"] ?? obj["target_id"]
        }

        let legacyMedia = ["image", "video", "audio", "voice", "video_msg", "file"]
        if oldType == "media" || legacyMedia.contains(oldType) {
            let nested = obj["media"] as? [String: Any] ?? [:]
            func first(_ keys: String...) -> Any? {
                for key in keys {
                    if let value = obj[key], !(value is NSNull) { return value }
                    if let value = nested[key], !(value is NSNull) { return value }
                }
                return nil
            }
            var media: [String: Any] = ["type": "media"]
            media["file_id"] = first("file_id", "fileId", "id")
            media["sym_key"] = first("sym_key", "symKey", "key", "key_b64")
            media["nonce"] = first("nonce", "nonce_b64", "iv")
            media["mime_type"] = first("mime_type", "mimeType", "mime")
            media["file_name"] = first("file_name", "fileName", "filename", "name")
            media["file_size"] = first("file_size", "fileSize", "size")
            if let duration = first("duration") {
                media["duration"] = duration
            } else if let ms = first("duration_ms") as? NSNumber {
                media["duration"] = ms.doubleValue / 1_000
            }
            media["caption"] = first("caption") ?? obj["text"]
            media["fwd_from"] = first("fwd_from", "forwarded_from")
            media["reply_to_id"] = first("reply_to_id")
            media["reply_to_text"] = first("reply_to_text")
            if let content = obj["content"] as? String {
                if content.hasPrefix("data:") {
                    media["inline_data"] = content
                    if media["mime_type"] == nil,
                       let end = content.firstIndex(where: { $0 == ";" || $0 == "," }) {
                        let mime = String(content[content.index(content.startIndex, offsetBy: 5)..<end])
                        if mime.count <= 128, mime.contains("/") { media["mime_type"] = mime }
                    }
                }
                else if media["file_id"] == nil { media["file_id"] = content }
            }
            media["kind"] = first("kind") ?? {
                switch oldType {
                case "image": return "image"
                case "video": return "video"
                case "audio": return "audio"
                case "voice": return "voice"
                case "video_msg": return "video_msg"
                case "file": return "file"
                default: return nil
                }
            }()
            obj = media.compactMapValues { $0 }
        }
        return Payload(type: obj["type"] as? String ?? oldType, raw: obj)
    }

    /// Короткое превью для списка чатов.
    static func preview(_ payloadJson: String) -> String {
        // Исчезающее и одноразовое не показываем в списке чатов: строка
        // предпросмотра переживает само сообщение и свела бы весь режим на нет.
        if let spec = ephemeralFromPayload(payloadJson: payloadJson) {
            return spec.kind == "VIEW_ONCE" ? "👁 Одноразовое сообщение" : "⏱ Исчезающее сообщение"
        }
        guard let p = parse(payloadJson) else { return "" }
        switch p.type {
        case "expired": return "⏱ Сообщение истекло"
        case "text": return p.text ?? ""
        case "media":
            switch p.mediaKind {
            case .image: return "📷 Фото"
            case .video: return "🎬 Видео"
            case .audio: return "🎵 Аудио"
            case .voice: return "🎤 Голосовое"
            case .videoNote: return "📹 Кружок"
            case .file: return "📎 " + (p.fileName ?? "Файл")
            }
        default: return ""
        }
    }
}
