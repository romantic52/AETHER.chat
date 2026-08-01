import Foundation

// Запуск: swiftc AETHER/Core/Wire.swift check_wire_compat.swift -o /tmp/aether-wire-check && /tmp/aether-wire-check
@main
enum WireCompatibilityCheck {
    static func main() {
        let plain = Wire.parse("старое сообщение")
        precondition(plain?.type == "text" && plain?.text == "старое сообщение")
        precondition(Wire.parse(#""JSON-строка""#)?.text == "JSON-строка")

        let legacy = Wire.parse(#"{"type":"voice","media":{"fileId":"f","symKey":"k","iv":"n"},"content":"data:audio/webm;base64,SGk=","duration_ms":4500}"#)
        precondition(legacy?.type == "media")
        precondition(legacy?.fileId == "f" && legacy?.symKey == "k" && legacy?.nonce == "n")
        precondition(legacy?.mimeType == "audio/webm" && legacy?.mediaKind == .voice)
        precondition(legacy?.inlineData == Data("Hi".utf8) && legacy?.duration == 4.5)

        let music = Wire.parse(#"{"type":"media","kind":"file","file_id":"m","sym_key":"k","nonce":"n","mime_type":"audio/mpeg"}"#)
        precondition(music?.mediaKind == .audio)

        let camel = Wire.parse(#"{"type":"video","fileId":"v","symKey":"s","iv":"i","mimeType":"video/mp4","fileSize":"42","duration":"3.5"}"#)
        precondition(camel?.fileId == "v" && camel?.fileSize == 42 && camel?.duration == 3.5)

        let edit = Wire.parse(#"{"type":"edit","target_id":"42","content":"новый текст"}"#)
        precondition(edit?.target == "42" && edit?.text == "новый текст")

        print("Wire compatibility: OK")
    }
}
