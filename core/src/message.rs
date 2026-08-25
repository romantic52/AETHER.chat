//! Идентичность сообщения, не зависящая от транспорта.
//!
//! Ключевое требование слоя доставки: одно и то же сообщение, ушедшее сначала
//! по Bluetooth, а потом (после потери подтверждения) через сервер, обязано
//! иметь ОДИН и тот же идентификатор — иначе получатель не отбросит дубликат.
//!
//! Раньше идентификатор назначал сервер, а клиент подменял свой локальный на
//! серверный. Так делать нельзя по двум причинам:
//!   1. при смене маршрута id менялся бы, и дубликат бы не распознался;
//!   2. рассылка по устройствам получателя создаёт НЕСКОЛЬКО серверных
//!      записей (по одной на устройство), у каждой свой id.
//!
//! Поэтому логический идентификатор:
//!   • создаёт отправитель;
//!   • едет ВНУТРИ шифрованного payload (сервер его не видит);
//!   • одинаков для всех копий и всех транспортов.
//!
//! Серверный id записи остаётся транспортной деталью и на модель не влияет.
//!
//! Формат — UUIDv7 (RFC 9562): 48 бит времени в миллисекундах, дальше
//! случайность. Он сортируется по времени, что удобно и для истории, и для
//! индексов, и при этом остаётся глобально уникальным.

use crate::CoreError;
use rand_core::{OsRng, RngCore};
use std::time::{SystemTime, UNIX_EPOCH};

/// Новый идентификатор сообщения. Единый генератор для iOS, Android и веба:
/// формат обязан совпадать побайтно, иначе дедупликация между платформами
/// сломается на ровном месте.
#[uniffi::export]
pub fn new_message_id() -> String {
    let ms = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0);

    let mut bytes = [0u8; 16];
    // 48 бит времени, big-endian.
    bytes[0] = (ms >> 40) as u8;
    bytes[1] = (ms >> 32) as u8;
    bytes[2] = (ms >> 24) as u8;
    bytes[3] = (ms >> 16) as u8;
    bytes[4] = (ms >> 8) as u8;
    bytes[5] = ms as u8;

    let mut rand = [0u8; 10];
    OsRng.fill_bytes(&mut rand);
    bytes[6..16].copy_from_slice(&rand);

    // Версия 7 в старшие 4 бита седьмого байта.
    bytes[6] = (bytes[6] & 0x0F) | 0x70;
    // Вариант RFC 4122 в старшие 2 бита девятого байта.
    bytes[8] = (bytes[8] & 0x3F) | 0x80;

    format_uuid(&bytes)
}

fn format_uuid(b: &[u8; 16]) -> String {
    let hex: String = b.iter().map(|x| format!("{x:02x}")).collect();
    format!(
        "{}-{}-{}-{}-{}",
        &hex[0..8], &hex[8..12], &hex[12..16], &hex[16..20], &hex[20..32]
    )
}

/// Похоже ли на идентификатор сообщения Aether.
///
/// Нужна на приёме: `mid` приходит из расшифрованного payload, то есть от
/// другой стороны. Данным по Bluetooth доверия не больше, чем данным с
/// сервера, поэтому проверяем форму перед тем, как класть в базу как
/// первичный ключ.
#[uniffi::export]
pub fn is_valid_message_id(id: String) -> bool {
    let b = id.as_bytes();
    if b.len() != 36 {
        return false;
    }
    for (i, c) in b.iter().enumerate() {
        match i {
            8 | 13 | 18 | 23 => {
                if *c != b'-' {
                    return false;
                }
            }
            _ => {
                if !c.is_ascii_hexdigit() {
                    return false;
                }
            }
        }
    }
    true
}

/// Извлечь логический id из расшифрованного payload.
///
/// Отсутствие поля — не ошибка: так шлют клиенты, выпущенные до слоя
/// доставки. Для них идентичностью остаётся серверный id, и это правильное
/// поведение — иначе старые сообщения перестали бы дедуплицироваться.
#[uniffi::export]
pub fn message_id_from_payload(payload_json: String) -> Option<String> {
    let v: serde_json::Value = serde_json::from_str(&payload_json).ok()?;
    let mid = v.get("mid")?.as_str()?.to_string();
    if is_valid_message_id(mid.clone()) {
        Some(mid)
    } else {
        None
    }
}

/// Вписать логический id в payload перед шифрованием.
///
/// Именно перед шифрованием: сервер не должен видеть идентификатор, по
/// которому можно связать копии одного сообщения на разных устройствах.
#[uniffi::export]
pub fn payload_with_message_id(payload_json: String, message_id: String) -> Result<String, CoreError> {
    if !is_valid_message_id(message_id.clone()) {
        return Err(CoreError::bad("некорректный message_id"));
    }
    let mut v: serde_json::Value = serde_json::from_str(&payload_json).map_err(CoreError::bad)?;
    match v.as_object_mut() {
        Some(obj) => {
            obj.insert("mid".into(), serde_json::Value::String(message_id));
            serde_json::to_string(&v).map_err(CoreError::bad)
        }
        None => Err(CoreError::bad("payload не является объектом")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generates_valid_sortable_v7() {
        let a = new_message_id();
        std::thread::sleep(std::time::Duration::from_millis(2));
        let b = new_message_id();
        assert!(is_valid_message_id(a.clone()));
        assert!(is_valid_message_id(b.clone()));
        assert_ne!(a, b);
        // Версия 7 и вариант RFC 4122 стоят на своих местах.
        assert_eq!(a.as_bytes()[14], b'7');
        assert!(matches!(a.as_bytes()[19], b'8' | b'9' | b'a' | b'b'));
        // UUIDv7 сортируется по времени: более поздний строго больше.
        assert!(b > a, "{b} должен быть больше {a}");
    }

    #[test]
    fn rejects_garbage_ids() {
        for bad in ["", "не-uuid", "0192f3c1", &"z".repeat(36)] {
            assert!(!is_valid_message_id(bad.into()), "{bad} не должен проходить");
        }
    }

    #[test]
    fn roundtrips_through_payload() {
        let id = new_message_id();
        let payload = r#"{"type":"text","text":"привет"}"#.to_string();
        let with = payload_with_message_id(payload, id.clone()).unwrap();
        assert_eq!(message_id_from_payload(with.clone()), Some(id));
        // Исходные поля на месте — payload не пересобирается, а дополняется.
        assert!(with.contains("привет"));
    }

    #[test]
    fn old_payload_without_mid_is_not_an_error() {
        // Клиент до слоя доставки: поля нет, и это нормальный случай.
        assert_eq!(message_id_from_payload(r#"{"type":"text"}"#.into()), None);
        // Подсунутый мусор в mid не должен становиться первичным ключом.
        assert_eq!(message_id_from_payload(r#"{"type":"text","mid":"../../etc"}"#.into()), None);
    }
}
