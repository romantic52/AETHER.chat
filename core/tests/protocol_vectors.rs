//! Ридер golden-векторов `protocol_vectors/v1`.
//!
//! Каталог векторов и его README существовали, но читателя со стороны Rust не
//! было: `cargo test --test protocol_vectors` из README падал с «нет такой
//! цели». То есть общие векторы ничего не проверяли, и расхождение ядра с
//! вебом или Android никто бы не заметил до боевого чата.
//!
//! Здесь проверяются два независимых свойства:
//!
//!   1. wire-слой: `wire_decode` понимает канонический вход, а `wire_encode`
//!      возвращает ровно тот объект, который ждут остальные клиенты
//!      (сравнение семантическое — порядок ключей в JSON не важен);
//!   2. deterministic_crypto: шифротексты из векторов расшифровываются ядром
//!      побайтно. Шифрование с фиксированным nonce ядро наружу не отдаёт (и
//!      правильно делает), поэтому проверяется направление расшифровки — оно
//!      ловит любое расхождение в base64url, порядке nonce/тега и AAD.

use std::collections::BTreeMap;
use std::path::PathBuf;

use serde_json::Value;
use sm_core::crypto::{aes_decrypt, b64url_decode, box_decrypt};
use sm_core::protocol::{wire_decode, wire_encode};

fn vectors_path() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("..")
        .join("protocol_vectors")
        .join("v1")
        .join("vectors.json")
}

/// Семантическое сравнение: объекты равны при любом порядке ключей.
/// serde_json::Value уже сравнивается так (Map = BTreeMap по умолчанию не
/// гарантирован, поэтому нормализуем явно).
fn normalize(v: &Value) -> Value {
    match v {
        Value::Object(map) => {
            let sorted: BTreeMap<_, _> = map.iter().map(|(k, x)| (k.clone(), normalize(x))).collect();
            Value::Object(sorted.into_iter().collect())
        }
        Value::Array(items) => Value::Array(items.iter().map(normalize).collect()),
        other => other.clone(),
    }
}

/// wire-типы, которые описывают payload сообщения. Остальные (`ratchet_metadata`,
/// `group_key_wrap`) — уровень конверта, у них своя проверка ниже.
fn is_wire_payload(wire_type: &str) -> bool {
    matches!(wire_type, "text" | "reply" | "reaction" | "edit" | "delete" | "read" | "delivered"
        | "group_payload")
        || wire_type.starts_with("media:")
}

#[test]
fn wire_vectors_roundtrip() {
    let raw = std::fs::read_to_string(vectors_path()).expect("protocol_vectors/v1/vectors.json");
    let doc: Value = serde_json::from_str(&raw).expect("vectors.json — валидный JSON");
    assert_eq!(doc["schema_version"], 1, "неизвестная версия векторов");

    let vectors = doc["vectors"].as_array().expect("vectors — массив");
    assert!(!vectors.is_empty(), "набор векторов пуст");

    let mut checked = 0;
    for v in vectors {
        let id = v["id"].as_str().expect("id");
        let wire_type = v["wire_type"].as_str().expect("wire_type");
        if !is_wire_payload(wire_type) {
            continue;
        }
        let input = v["input_json"].to_string();
        let decoded = wire_decode(input.clone());

        // Кодирование обратно обязано дать канонический объект.
        let encoded: Value = serde_json::from_str(&wire_encode(decoded.clone()))
            .unwrap_or_else(|e| panic!("{id}: wire_encode вернул не-JSON: {e}"));
        assert_eq!(
            normalize(&encoded),
            normalize(&v["expected_encoded_json"]),
            "{id}: encode разошёлся с вектором"
        );

        // Идемпотентность: канонический объект декодируется в то же значение,
        // что и исходный. Иначе второй проход по своему же выходу терял бы поля.
        let redecoded = wire_decode(v["expected_encoded_json"].to_string());
        assert_eq!(decoded, redecoded, "{id}: decode(encode(x)) != decode(x)");

        checked += 1;
    }
    assert!(checked > 0, "ни один wire-вектор не проверен — сломан фильтр типов");
    println!("wire-векторов проверено: {checked}");
}

#[test]
fn deterministic_crypto_vectors() {
    let raw = std::fs::read_to_string(vectors_path()).expect("protocol_vectors/v1/vectors.json");
    let doc: Value = serde_json::from_str(&raw).expect("vectors.json — валидный JSON");

    let mut checked = 0;
    for v in doc["vectors"].as_array().expect("vectors") {
        let id = v["id"].as_str().expect("id");
        let det = match v.get("deterministic_crypto") {
            Some(Value::Object(_)) => &v["deterministic_crypto"],
            _ => continue,
        };
        let algorithm = det["algorithm"].as_str().expect("algorithm");
        let ciphertext = b64url_decode(det["ciphertext_b64url"].as_str().expect("ct").to_string())
            .unwrap_or_else(|e| panic!("{id}: ciphertext не b64url: {e:?}"));
        let expected = det["plaintext_utf8"].as_str().expect("plaintext_utf8");

        let plaintext = match algorithm {
            "AES-256-GCM" => aes_decrypt(
                det["key_b64url"].as_str().expect("key").to_string(),
                det["nonce_b64url"].as_str().expect("nonce").to_string(),
                ciphertext,
            )
            .unwrap_or_else(|e| panic!("{id}: AES-GCM вектор не расшифровался: {e:?}")),
            "crypto_box_curve25519xsalsa20poly1305" => box_decrypt(
                det["nonce_b64url"].as_str().expect("nonce").to_string(),
                ciphertext,
                det["sender_public_b64url"].as_str().expect("sender pub").to_string(),
                det["recipient_private_b64url"].as_str().expect("recipient priv").to_string(),
            )
            .unwrap_or_else(|e| panic!("{id}: crypto_box вектор не расшифровался: {e:?}")),
            other => panic!("{id}: неизвестный алгоритм вектора: {other}"),
        };

        assert_eq!(
            String::from_utf8(plaintext).unwrap_or_else(|e| panic!("{id}: не UTF-8: {e}")),
            expected,
            "{id}: расшифрованный текст разошёлся с вектором"
        );
        checked += 1;
    }
    assert!(checked > 0, "ни одного deterministic_crypto вектора — набор потерян");
    println!("крипто-векторов проверено: {checked}");
}
