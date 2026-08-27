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

// --- Исчезающие сообщения и «просмотр один раз» -------------------------------
//
// Поведение сообщения (исчезает / один просмотр) и тип содержимого (текст,
// фото, голос) — НЕЗАВИСИМЫ. Бывает VIEW_ONCE+IMAGE, EPHEMERAL+TEXT,
// EPHEMERAL+FILE. Поэтому поведение живёт отдельным полем payload, а не новым
// типом сообщения (docs/TRANSPORT_LAYER_DESIGN.md, разделы 41 и 84).
//
// Спецификация едет ВНУТРИ шифрованного payload рядом с mid: серверу знать,
// что сообщение исчезающее, незачем.
//
// ЧЕСТНОЕ ОГРАНИЧЕНИЕ. Клиент, выпущенный до этого слоя, поля не понимает и
// покажет сообщение обычным — оно у него не исчезнет. Ephemeral работает,
// только когда его поддерживают ОБЕ стороны. Интерфейс не должен обещать
// иначе.

/// Что запускает отсчёт.
#[derive(uniffi::Enum, Clone, Copy, PartialEq, Debug)]
pub enum EphemeralTrigger {
    /// С момента отправки.
    Sent,
    /// С момента подтверждения доставки.
    Delivered,
    /// С первого открытия получателем.
    FirstOpen,
    /// С закрытия просмотра.
    Close,
    /// Абсолютный момент времени.
    Absolute,
}

#[derive(uniffi::Record, Clone, PartialEq, Debug)]
pub struct EphemeralSpec {
    /// NORMAL | EPHEMERAL | VIEW_ONCE
    pub kind: String,
    /// Сколько жить после срабатывания триггера, в секундах.
    pub ttl_seconds: i64,
    pub trigger: EphemeralTrigger,
    /// Абсолютный дедлайн в миллисекундах — только для Absolute.
    pub absolute_ms: Option<i64>,
    /// Сколько просмотров разрешено. Для VIEW_ONCE — 1.
    pub view_limit: Option<i32>,
}

fn trigger_code(t: EphemeralTrigger) -> &'static str {
    match t {
        EphemeralTrigger::Sent => "SENT",
        EphemeralTrigger::Delivered => "DELIVERED",
        EphemeralTrigger::FirstOpen => "FIRST_OPEN",
        EphemeralTrigger::Close => "CLOSE",
        EphemeralTrigger::Absolute => "ABSOLUTE",
    }
}

fn trigger_from(code: &str) -> EphemeralTrigger {
    match code {
        "SENT" => EphemeralTrigger::Sent,
        "DELIVERED" => EphemeralTrigger::Delivered,
        "CLOSE" => EphemeralTrigger::Close,
        "ABSOLUTE" => EphemeralTrigger::Absolute,
        // Незнакомый триггер трактуем как самый строгий из осмысленных:
        // лучше исчезнуть раньше, чем остаться навсегда.
        _ => EphemeralTrigger::FirstOpen,
    }
}

/// Вписать поведение в payload перед шифрованием.
#[uniffi::export]
pub fn payload_with_ephemeral(payload_json: String, spec: EphemeralSpec) -> Result<String, CoreError> {
    let mut v: serde_json::Value = serde_json::from_str(&payload_json).map_err(CoreError::bad)?;
    let obj = v.as_object_mut().ok_or_else(|| CoreError::bad("payload не является объектом"))?;
    if spec.kind == "NORMAL" {
        return serde_json::to_string(&v).map_err(CoreError::bad);
    }
    obj.insert("mt".into(), serde_json::Value::String(spec.kind.clone()));
    let mut eph = serde_json::Map::new();
    eph.insert("ttl".into(), serde_json::Value::from(spec.ttl_seconds));
    eph.insert("trg".into(), serde_json::Value::String(trigger_code(spec.trigger).into()));
    if let Some(at) = spec.absolute_ms {
        eph.insert("at".into(), serde_json::Value::from(at));
    }
    if let Some(limit) = spec.view_limit {
        eph.insert("vl".into(), serde_json::Value::from(limit));
    }
    obj.insert("eph".into(), serde_json::Value::Object(eph));
    serde_json::to_string(&v).map_err(CoreError::bad)
}

/// Прочитать поведение из расшифрованного payload.
///
/// None означает обычное сообщение: так шлют и старые клиенты, и мы сами,
/// когда режим не выбран.
#[uniffi::export]
pub fn ephemeral_from_payload(payload_json: String) -> Option<EphemeralSpec> {
    // Правила зажима живут в ratchet-core: из него же собирается веб, и обе
    // стороны обязаны понимать конверт одинаково. Здесь только перевод в
    // тип, который умеет пересекать границу UniFFI.
    let plain = aether_ratchet_core::ephemeral::ephemeral_spec(&payload_json)?;
    Some(EphemeralSpec {
        kind: plain.kind,
        ttl_seconds: plain.ttl_seconds,
        trigger: trigger_from(&plain.trigger),
        absolute_ms: plain.absolute_ms,
        view_limit: plain.view_limit,
    })
}

#[cfg(test)]
mod ephemeral_tests {
    use super::*;

    fn text() -> String { r#"{"type":"text","text":"секрет"}"#.into() }

    #[test]
    fn roundtrips_and_keeps_content() {
        let spec = EphemeralSpec {
            kind: "EPHEMERAL".into(), ttl_seconds: 60,
            trigger: EphemeralTrigger::FirstOpen, absolute_ms: None, view_limit: None,
        };
        let with = payload_with_ephemeral(text(), spec).unwrap();
        let back = ephemeral_from_payload(with.clone()).unwrap();
        assert_eq!(back.kind, "EPHEMERAL");
        assert_eq!(back.ttl_seconds, 60);
        assert_eq!(back.trigger, EphemeralTrigger::FirstOpen);
        assert!(with.contains("секрет"), "содержимое не должно теряться");
    }

    #[test]
    fn view_once_implies_single_view() {
        let spec = EphemeralSpec {
            kind: "VIEW_ONCE".into(), ttl_seconds: 0,
            trigger: EphemeralTrigger::FirstOpen, absolute_ms: None, view_limit: None,
        };
        let with = payload_with_ephemeral(text(), spec).unwrap();
        assert_eq!(ephemeral_from_payload(with).unwrap().view_limit, Some(1));
    }

    #[test]
    fn normal_payload_stays_untouched() {
        let spec = EphemeralSpec {
            kind: "NORMAL".into(), ttl_seconds: 0,
            trigger: EphemeralTrigger::Sent, absolute_ms: None, view_limit: None,
        };
        let with = payload_with_ephemeral(text(), spec).unwrap();
        assert!(!with.contains("\"mt\""), "обычное сообщение не помечается");
        assert_eq!(ephemeral_from_payload(with), None);
        // Клиент до этого слоя полей не шлёт — и это обычное сообщение.
        assert!(ephemeral_from_payload(text()).is_none());
    }

    #[test]
    fn hostile_values_are_clamped() {
        // Огромный ttl превратил бы исчезающее сообщение в обычное.
        let payload = r#"{"type":"text","mt":"EPHEMERAL","eph":{"ttl":999999999999,"trg":"XX","vl":-5}}"#;
        let spec = ephemeral_from_payload(payload.into()).unwrap();
        assert_eq!(spec.ttl_seconds, 365 * 24 * 3600);
        assert_eq!(spec.trigger, EphemeralTrigger::FirstOpen, "незнакомый триггер — самый строгий");
        assert_eq!(spec.view_limit, Some(1), "отрицательный лимит просмотров недопустим");
    }
}
