//! Конверты wire-протокола (см. WIRE_PROTOCOL.md). Plaintext — UTF-8 JSON
//! канонического payload'а; сборка/разбор конвертов только здесь.

use crate::crypto::{self, decode_b64};
use crate::CoreError;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize)]
struct BoxEnvelope {
    sender_pubkey_b64: String,
    nonce_b64: String,
    ciphertext_b64: String,
}

/// Результат вскрытия входящего конверта.
#[derive(uniffi::Record)]
pub struct Opened {
    /// Публичный ключ отправителя (только у личных сообщений).
    pub sender_pub_b64: Option<String>,
    /// UTF-8 JSON wire-payload.
    pub plaintext: String,
    pub is_group: bool,
}

/// Личный конверт: {sender_pubkey_b64, nonce_b64, ciphertext_b64}.
#[uniffi::export]
pub fn seal_direct(
    plaintext_json: String,
    recipient_pub_b64: String,
    sender_pub_b64: String,
    sender_priv_b64: String,
) -> Result<String, CoreError> {
    let sealed = crypto::box_encrypt(plaintext_json.into_bytes(), recipient_pub_b64, sender_priv_b64)?;
    serde_json::to_string(&BoxEnvelope {
        sender_pubkey_b64: sender_pub_b64,
        nonce_b64: sealed.nonce_b64,
        ciphertext_b64: URL_SAFE_NO_PAD.encode(sealed.ciphertext),
    })
    .map_err(CoreError::bad)
}

/// Групповой конверт: {is_group:"1", nonce_b64, ciphertext_b64} (AES-GCM общим ключом).
#[uniffi::export]
pub fn seal_group(plaintext_json: String, group_key_b64: String) -> Result<String, CoreError> {
    let sealed = crypto::aes_encrypt(group_key_b64, plaintext_json.into_bytes())?;
    Ok(serde_json::json!({
        "is_group": "1",
        "nonce_b64": sealed.nonce_b64,
        "ciphertext_b64": URL_SAFE_NO_PAD.encode(sealed.ciphertext),
    })
    .to_string())
}

/// Вскрывает входящий конверт (личный или групповой).
/// Для групповых нужен `group_key_b64`; is_group принимает и строку "1", и boolean true (легаси-веб).
#[uniffi::export]
pub fn open_envelope(
    envelope_json: String,
    my_priv_b64: String,
    group_key_b64: Option<String>,
) -> Result<Opened, CoreError> {
    let env: serde_json::Value = serde_json::from_str(&envelope_json).map_err(CoreError::bad)?;
    let is_group = env.get("is_group").map_or(false, |v| v == "1" || v == &serde_json::Value::Bool(true));
    let nonce = env
        .get("nonce_b64")
        .and_then(|v| v.as_str())
        .ok_or_else(|| CoreError::bad("нет nonce_b64"))?
        .to_string();
    let ct = decode_b64(
        env.get("ciphertext_b64")
            .and_then(|v| v.as_str())
            .ok_or_else(|| CoreError::bad("нет ciphertext_b64"))?,
    )?;

    if is_group {
        let key = group_key_b64.ok_or_else(|| CoreError::Crypto { msg: "нет ключа группы".into() })?;
        let pt = crypto::aes_decrypt(key, nonce, ct)?;
        Ok(Opened {
            sender_pub_b64: None,
            plaintext: String::from_utf8(pt).map_err(CoreError::crypto)?,
            is_group: true,
        })
    } else {
        let sender_pub = env
            .get("sender_pubkey_b64")
            .and_then(|v| v.as_str())
            .ok_or_else(|| CoreError::bad("нет sender_pubkey_b64"))?
            .to_string();
        let pt = crypto::box_decrypt(nonce, ct, sender_pub.clone(), my_priv_b64)?;
        Ok(Opened {
            sender_pub_b64: Some(sender_pub),
            plaintext: String::from_utf8(pt).map_err(CoreError::crypto)?,
            is_group: false,
        })
    }
}

/// Вскрытие группового конверта с перебором известных ключей (эпохи ротации,
/// новые первыми). Личные конверты ключей не требуют. Wire-формат не меняется —
/// ротация обратно совместима со старыми клиентами.
#[uniffi::export]
pub fn open_envelope_multi(
    envelope_json: String,
    my_priv_b64: String,
    group_keys: Vec<String>,
) -> Result<Opened, CoreError> {
    let env: serde_json::Value = serde_json::from_str(&envelope_json).map_err(CoreError::bad)?;
    let is_group = env.get("is_group").map_or(false, |v| v == "1" || v == &serde_json::Value::Bool(true));
    if !is_group {
        return open_envelope(envelope_json, my_priv_b64, None);
    }
    let mut last_err = CoreError::Crypto { msg: "нет ключа группы".into() };
    for key in group_keys {
        match open_envelope(envelope_json.clone(), my_priv_b64.clone(), Some(key)) {
            Ok(opened) => return Ok(opened),
            Err(e) => last_err = e,
        }
    }
    Err(last_err)
}

/// Заворачивает ключ группы для участника: box'ом шифруется b64url-СТРОКА ключа.
/// Результат — JSON-строка для `encrypted_key_b64`.
#[uniffi::export]
pub fn wrap_group_key(
    group_key_b64: String,
    recipient_pub_b64: String,
    sender_pub_b64: String,
    sender_priv_b64: String,
) -> Result<String, CoreError> {
    seal_direct(group_key_b64, recipient_pub_b64, sender_pub_b64, sender_priv_b64)
}

/// Разворачивает `encrypted_key_b64` → b64url-строка 32-байтового ключа группы.
#[uniffi::export]
pub fn unwrap_group_key(encrypted_key_b64: String, my_priv_b64: String) -> Result<String, CoreError> {
    let opened = open_envelope(encrypted_key_b64, my_priv_b64, None)?;
    let key_b64 = opened.plaintext;
    if decode_b64(&key_b64)?.len() != 32 {
        return Err(CoreError::Crypto { msg: "ключ группы не 32 байта".into() });
    }
    Ok(key_b64)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::generate_keypair;

    #[test]
    fn direct_roundtrip() {
        let a = generate_keypair();
        let b = generate_keypair();
        let payload = r#"{"type":"text","text":"привет"}"#;
        let env = seal_direct(payload.into(), b.public_b64.clone(), a.public_b64.clone(), a.private_b64).unwrap();
        let opened = open_envelope(env, b.private_b64, None).unwrap();
        assert_eq!(opened.plaintext, payload);
        assert_eq!(opened.sender_pub_b64.unwrap(), a.public_b64);
        assert!(!opened.is_group);
    }

    #[test]
    fn group_roundtrip() {
        let key = crypto::random_key_b64();
        let env = seal_group(r#"{"type":"text","text":"hi"}"#.into(), key.clone()).unwrap();
        let v: serde_json::Value = serde_json::from_str(&env).unwrap();
        assert_eq!(v["is_group"], "1");
        let opened = open_envelope(env, String::new(), Some(key)).unwrap();
        assert!(opened.is_group);
        assert_eq!(opened.plaintext, r#"{"type":"text","text":"hi"}"#);
    }

    #[test]
    fn legacy_bool_is_group() {
        let key = crypto::random_key_b64();
        let env = seal_group("{}".into(), key.clone()).unwrap();
        let mut v: serde_json::Value = serde_json::from_str(&env).unwrap();
        v["is_group"] = serde_json::Value::Bool(true);
        let opened = open_envelope(v.to_string(), String::new(), Some(key)).unwrap();
        assert!(opened.is_group);
    }

    #[test]
    fn group_key_wrap_roundtrip() {
        let owner = generate_keypair();
        let member = generate_keypair();
        let gkey = crypto::random_key_b64();
        let wrapped = wrap_group_key(gkey.clone(), member.public_b64, owner.public_b64, owner.private_b64).unwrap();
        // encrypted_key_b64 — JSON-строка с конвертом
        assert!(wrapped.contains("sender_pubkey_b64"));
        let unwrapped = unwrap_group_key(wrapped, member.private_b64).unwrap();
        assert_eq!(unwrapped, gkey);
    }
}

// ---- Типизированный wire-слой (общий для всех платформ) ----
// Клиенты могут работать и с сырым JSON (Swift сейчас так), и с типами (Kotlin).

/// Сообщение «на проводе» — расшифрованная полезная нагрузка внутри конверта.
#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
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
        nonce: Option<String>,
        kind: Option<String>,
        duration: Option<f64>,
        file_name: Option<String>,
        file_size: Option<i64>,
        caption: Option<String>,
        fwd_from: Option<String>,
    },
    Edit { target: String, text: String },
    Reaction { target: String, emoji: String },
    Delete { target: String },
    Read,
    Delivered,
    /// Нераспознанный тип — сырой JSON без потерь (forward-compat, канон: игнорировать).
    Unknown { raw: String },
}

/// Сериализация в каноническую JSON-строку (её шифруют и шлют).
#[uniffi::export]
pub fn wire_encode(msg: WireMessage) -> String {
    use serde_json::json;
    let v = match msg {
        WireMessage::Text { text, reply_to_id, reply_to_text, fwd_from } => {
            let mut o = json!({ "type": "text", "text": text });
            if let Some(r) = reply_to_id { o["reply_to_id"] = json!(r); }
            if let Some(r) = reply_to_text { o["reply_to_text"] = json!(r); }
            if let Some(f) = fwd_from { o["fwd_from"] = json!(f); }
            o
        }
        WireMessage::Media { file_id, sym_key, mime_type, nonce, kind, duration, file_name, file_size, caption, fwd_from } => {
            let mut o = json!({ "type": "media", "file_id": file_id, "sym_key": sym_key, "mime_type": mime_type });
            if let Some(n) = nonce { o["nonce"] = json!(n); }
            if let Some(k) = kind { o["kind"] = json!(k); }
            if let Some(d) = duration { o["duration"] = json!(d); }
            if let Some(n) = file_name { o["file_name"] = json!(n); }
            if let Some(sz) = file_size { o["file_size"] = json!(sz); }
            if let Some(c) = caption { o["caption"] = json!(c); }
            if let Some(f) = fwd_from { o["fwd_from"] = json!(f); }
            o
        }
        WireMessage::Edit { target, text } => json!({ "type": "edit", "target": target, "text": text }),
        WireMessage::Reaction { target, emoji } => json!({ "type": "reaction", "target": target, "emoji": emoji }),
        WireMessage::Delete { target } => json!({ "type": "delete", "target": target }),
        WireMessage::Read => json!({ "type": "read" }),
        WireMessage::Delivered => json!({ "type": "delivered" }),
        WireMessage::Unknown { raw } => return raw,
    };
    v.to_string()
}

/// Разбор расшифрованной JSON-строки в типизированное сообщение.
#[uniffi::export]
pub fn wire_decode(json_str: String) -> WireMessage {
    let v: serde_json::Value = match serde_json::from_str(&json_str) {
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
            nonce: s("nonce"),
            kind: s("kind"),
            duration: v.get("duration").and_then(|x| x.as_f64()),
            // Легаси-ключи web: filename/size.
            file_name: s("file_name").or_else(|| s("filename")),
            file_size: v.get("file_size").and_then(|x| x.as_i64())
                .or_else(|| v.get("size").and_then(|x| x.as_i64())),
            caption: s("caption"),
            fwd_from: s("fwd_from"),
        },
        "edit" => WireMessage::Edit {
            target: s("target").unwrap_or_default(),
            text: s("text").unwrap_or_default(),
        },
        "reaction" => WireMessage::Reaction {
            target: s("target").unwrap_or_default(),
            emoji: s("emoji").unwrap_or_default(),
        },
        "delete" => WireMessage::Delete { target: s("target").unwrap_or_default() },
        "read" => WireMessage::Read,
        "delivered" => WireMessage::Delivered,
        _ => WireMessage::Unknown { raw: json_str },
    }
}

#[cfg(test)]
mod wire_tests {
    use super::*;

    #[test]
    fn wire_roundtrip_text() {
        let m = WireMessage::Text {
            text: "привет".into(),
            reply_to_id: Some("m1".into()),
            reply_to_text: None,
            fwd_from: None,
        };
        assert_eq!(wire_decode(wire_encode(m.clone())), m);
    }

    #[test]
    fn wire_media_legacy_keys() {
        let json = r#"{"type":"media","file_id":"f","sym_key":"k","mime_type":"image/jpeg","filename":"a.jpg","size":42}"#;
        match wire_decode(json.into()) {
            WireMessage::Media { file_name, file_size, .. } => {
                assert_eq!(file_name.as_deref(), Some("a.jpg"));
                assert_eq!(file_size, Some(42));
            }
            other => panic!("не media: {other:?}"),
        }
    }

    #[test]
    fn wire_unknown_passthrough() {
        let raw = r#"{"type":"космо-тип","x":1}"#.to_string();
        let m = wire_decode(raw.clone());
        assert_eq!(wire_encode(m), raw);
    }

    #[test]
    fn multi_key_open_uses_older_epoch() {
        let old_key = crate::crypto::random_key_b64();
        let new_key = crate::crypto::random_key_b64();
        let env = seal_group(r#"{"type":"text","text":"старая эпоха"}"#.into(), old_key.clone()).unwrap();
        let opened = open_envelope_multi(env, String::new(), vec![new_key, old_key]).unwrap();
        assert!(opened.is_group);
        assert!(opened.plaintext.contains("старая эпоха"));
    }
}
