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
                      caption: String? = nil, fwdFrom: String? = nil) -> String {
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
        var duration: Double? { (raw["duration"] as? NSNumber)?.doubleValue }
        var fileName: String? { (raw["file_name"] as? String) ?? (raw["filename"] as? String) }
        var fileSize: Int64? { (raw["file_size"] as? NSNumber)?.int64Value ?? (raw["size"] as? NSNumber)?.int64Value }
        var caption: String? { raw["caption"] as? String }

        /// Отображаемый вид медиа (image|voice|video_note|file), с учётом legacy web-типов.
        var mediaKind: MediaKind {
            let k = kind ?? ""
            if k == "voice" { return .voice }
            if k == "video_msg" || k == "video_note" { return .videoNote }
            // Явный kind:"file" главнее mime: «фото файлом» должно рендериться
            // файловым пузырём, а не картинкой (mime у него всё равно image/*).
            if k == "file" { return .file }
            if k == "image" || (mimeType ?? "").hasPrefix("image/") { return .image }
            if k == "video" || (mimeType ?? "").hasPrefix("video/") { return .video }
            return .file
        }
    }

    enum MediaKind { case image, video, voice, videoNote, file }

    static func parse(_ jsonString: String) -> Payload? {
        guard let data = jsonString.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = obj["type"] as? String else { return nil }
        return Payload(type: type, raw: obj)
    }

    /// Короткое превью для списка чатов.
    static func preview(_ payloadJson: String) -> String {
        guard let p = parse(payloadJson) else { return "" }
        switch p.type {
        case "text": return p.text ?? ""
        case "media":
            switch p.mediaKind {
            case .image: return "📷 Фото"
            case .video: return "🎬 Видео"
            case .voice: return "🎤 Голосовое"
            case .videoNote: return "📹 Кружок"
            case .file: return "📎 " + (p.fileName ?? "Файл")
            }
        default: return ""
        }
    }
}
