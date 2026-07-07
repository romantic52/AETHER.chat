//! Wire-протокол: типы сообщений и сборка/разбор расшифрованной JSON-нагрузки.
//! Канонический формат — см. WIRE_PROTOCOL.md. Это общий для всех платформ слой:
//! и Android, и Desktop, и Web (через WASM) кодируют/декодируют сообщения здесь,
//! а не дублируют склейку JSON у себя.

use serde_json::{json, Value};

/// Сообщение «на проводе» — расшифрованная полезная нагрузка внутри конверта.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum WireMessage {
    Text {
        text: String,
        reply_to_id: Option<String>,
        reply_to_text: Option<String>,
        fwd_from: Option<String>,
    },
    Media {
        file_id: String,
        sym_key: String,
        mime_type: String,
        kind: Option<String>,
        nonce: Option<String>,
        fwd_from: Option<String>,
    },
    Edit { target: String, text: String },
    Reaction { target: String, emoji: String },
    Read,
    /// Нераспознанный тип — пропускаем сырой JSON без потери (forward-compat).
    Unknown { raw: String },
}

/// Сериализация сообщения в каноническую JSON-строку (её потом шифруют и шлют).
#[uniffi::export]
pub fn wire_encode(msg: WireMessage) -> String {
    let v: Value = match msg {
        WireMessage::Text { text, reply_to_id, reply_to_text, fwd_from } => {
            let mut o = json!({ "type": "text", "text": text });
            if let Some(r) = reply_to_id { o["reply_to_id"] = json!(r); }
            if let Some(r) = reply_to_text { o["reply_to_text"] = json!(r); }
            if let Some(f) = fwd_from { o["fwd_from"] = json!(f); }
            o
        }
        WireMessage::Media { file_id, sym_key, mime_type, kind, nonce, fwd_from } => {
            let mut o = json!({ "type": "media", "file_id": file_id, "sym_key": sym_key, "mime_type": mime_type });
            if let Some(k) = kind { o["kind"] = json!(k); }
            if let Some(n) = nonce { o["nonce"] = json!(n); }
            if let Some(f) = fwd_from { o["fwd_from"] = json!(f); }
            o
        }
        WireMessage::Edit { target, text } => json!({ "type": "edit", "target": target, "text": text }),
        WireMessage::Reaction { target, emoji } => json!({ "type": "reaction", "target": target, "emoji": emoji }),
        WireMessage::Read => json!({ "type": "read" }),
        WireMessage::Unknown { raw } => return raw,
    };
    v.to_string()
}

/// Разбор расшифрованной JSON-строки в типизированное сообщение.
#[uniffi::export]
pub fn wire_decode(json_str: String) -> WireMessage {
    let v: Value = match serde_json::from_str(&json_str) {
        Ok(v) => v,
        Err(_) => return WireMessage::Unknown { raw: json_str },
    };
    let t = v.get("type").and_then(|x| x.as_str()).unwrap_or("");
    let s = |k: &str| v.get(k).and_then(|x| x.as_str()).map(|x| x.to_string());
    match t {
        "text" => WireMessage::Text {
            text: s("text").unwrap_or_default(),
            reply_to_id: s("reply_to_id"),
            reply_to_text: s("reply_to_text"),
            fwd_from: s("fwd_from"),
        },
        "media" => WireMessage::Media {
            file_id: s("file_id").unwrap_or_default(),
            sym_key: s("sym_key").unwrap_or_default(),
            mime_type: s("mime_type").unwrap_or_default(),
            kind: s("kind"),
            nonce: s("nonce"),
            fwd_from: s("fwd_from"),
        },
        "edit" => WireMessage::Edit { target: s("target").unwrap_or_default(), text: s("text").unwrap_or_default() },
        "reaction" => WireMessage::Reaction { target: s("target").unwrap_or_default(), emoji: s("emoji").unwrap_or_default() },
        "read" => WireMessage::Read,
        _ => WireMessage::Unknown { raw: json_str },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn roundtrip(m: WireMessage) {
        let json = wire_encode(m.clone());
        assert_eq!(wire_decode(json), m);
    }

    #[test]
    fn text_roundtrip() {
        roundtrip(WireMessage::Text { text: "привет".into(), reply_to_id: None, reply_to_text: None, fwd_from: None });
        roundtrip(WireMessage::Text { text: "ответ".into(), reply_to_id: Some("id1".into()), reply_to_text: Some("prev".into()), fwd_from: Some("bob".into()) });
    }

    #[test]
    fn media_reaction_edit_read_roundtrip() {
        roundtrip(WireMessage::Media { file_id: "f1".into(), sym_key: "k1".into(), mime_type: "image/jpeg".into(), kind: Some("image".into()), nonce: Some("n1".into()), fwd_from: None });
        roundtrip(WireMessage::Reaction { target: "m1".into(), emoji: "👍".into() });
        roundtrip(WireMessage::Edit { target: "m2".into(), text: "новый".into() });
        roundtrip(WireMessage::Read);
    }

    #[test]
    fn decodes_canonical_json() {
        // Точные строки канона (как шлёт Android MessageRepository)
        match wire_decode(r#"{"type":"reaction","target":"abc","emoji":"🔥"}"#.into()) {
            WireMessage::Reaction { target, emoji } => { assert_eq!(target, "abc"); assert_eq!(emoji, "🔥"); }
            other => panic!("ожидался Reaction, получили {:?}", other),
        }
        match wire_decode(r#"{"type":"text","text":"hi","reply_to_id":"x"}"#.into()) {
            WireMessage::Text { text, reply_to_id, .. } => { assert_eq!(text, "hi"); assert_eq!(reply_to_id, Some("x".into())); }
            other => panic!("ожидался Text, получили {:?}", other),
        }
    }

    #[test]
    fn unknown_passthrough() {
        let raw = r#"{"type":"poll","q":"?"}"#;
        assert_eq!(wire_decode(raw.into()), WireMessage::Unknown { raw: raw.into() });
        // Unknown кодируется обратно как есть
        assert_eq!(wire_encode(WireMessage::Unknown { raw: raw.into() }), raw);
    }
}
