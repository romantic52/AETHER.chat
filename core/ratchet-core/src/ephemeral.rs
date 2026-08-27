//! Разбор описания исчезающего сообщения.
//!
//! Живёт здесь, а не в `sm_core`, потому что веб собирается из этого крейта и
//! обязан зажимать враждебные значения ровно так же, как native. Две копии
//! правил рано или поздно разъедутся, и разойдутся они молча — на стороне,
//! где сообщение не исчезнет.
//!
//! Формат в конверте компактный: `mt` — режим, `eph` — параметры.

use serde::{Deserialize, Serialize};

/// Верхняя граница срока жизни. Без неё «ttl = 10 лет» превращает исчезающее
/// сообщение в обычное, но с успокаивающей пометкой в интерфейсе.
const MAX_TTL_SECONDS: i64 = 365 * 24 * 3600;
/// Лимит просмотров: ноль или отрицательное сломали бы арифметику,
/// а миллион не отличим от бесконечности.
const MIN_VIEW_LIMIT: i64 = 1;
const MAX_VIEW_LIMIT: i64 = 1000;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct EphemeralSpecPlain {
    /// EPHEMERAL | VIEW_ONCE
    pub kind: String,
    pub ttl_seconds: i64,
    /// SENT | DELIVERED | FIRST_OPEN | CLOSE | ABSOLUTE
    pub trigger: String,
    pub absolute_ms: Option<i64>,
    pub view_limit: Option<i32>,
}

/// Достать описание из нагрузки. `None` — сообщение обычное.
///
/// Данным из конверта доверия не больше, чем данным с сервера: отправитель
/// мог собрать его вручную.
pub fn ephemeral_spec(payload_json: &str) -> Option<EphemeralSpecPlain> {
    let v: serde_json::Value = serde_json::from_str(payload_json).ok()?;
    let kind = v.get("mt")?.as_str()?.to_string();
    if kind != "EPHEMERAL" && kind != "VIEW_ONCE" {
        return None;
    }
    let eph = v.get("eph");

    let ttl_seconds = eph
        .and_then(|e| e.get("ttl"))
        .and_then(|t| t.as_i64())
        .unwrap_or(0)
        .clamp(0, MAX_TTL_SECONDS);

    let trigger = eph
        .and_then(|e| e.get("trg"))
        .and_then(|t| t.as_str())
        .map(normalize_trigger)
        .unwrap_or("FIRST_OPEN")
        .to_string();

    let absolute_ms = eph.and_then(|e| e.get("at")).and_then(|t| t.as_i64());

    let view_limit = eph
        .and_then(|e| e.get("vl"))
        .and_then(|t| t.as_i64())
        .map(|n| n.clamp(MIN_VIEW_LIMIT, MAX_VIEW_LIMIT) as i32)
        // «Просмотр один раз» без явного лимита — это ровно один просмотр.
        .or(if kind == "VIEW_ONCE" { Some(1) } else { None });

    Some(EphemeralSpecPlain { kind, ttl_seconds, trigger, absolute_ms, view_limit })
}

/// Тот же разбор, но результат — JSON. Нужен там, где границу языка
/// проще пересечь строкой: wasm и UniFFI.
pub fn ephemeral_spec_json(payload_json: &str) -> Option<String> {
    ephemeral_spec(payload_json).and_then(|s| serde_json::to_string(&s).ok())
}

fn normalize_trigger(raw: &str) -> &'static str {
    match raw {
        "s" | "SENT" => "SENT",
        "d" | "DELIVERED" => "DELIVERED",
        "c" | "CLOSE" => "CLOSE",
        "a" | "ABSOLUTE" => "ABSOLUTE",
        _ => "FIRST_OPEN",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plain_message_has_no_spec() {
        assert!(ephemeral_spec(r#"{"type":"text","text":"привет"}"#).is_none());
    }

    #[test]
    fn hostile_ttl_is_clamped() {
        let spec = ephemeral_spec(r#"{"mt":"EPHEMERAL","eph":{"ttl":999999999999}}"#).unwrap();
        assert_eq!(spec.ttl_seconds, MAX_TTL_SECONDS);
    }

    #[test]
    fn negative_ttl_becomes_zero() {
        let spec = ephemeral_spec(r#"{"mt":"EPHEMERAL","eph":{"ttl":-5}}"#).unwrap();
        assert_eq!(spec.ttl_seconds, 0);
    }

    #[test]
    fn view_once_implies_single_view() {
        let spec = ephemeral_spec(r#"{"mt":"VIEW_ONCE"}"#).unwrap();
        assert_eq!(spec.view_limit, Some(1));
    }

    #[test]
    fn zero_view_limit_is_raised_to_one() {
        let spec = ephemeral_spec(r#"{"mt":"EPHEMERAL","eph":{"vl":0}}"#).unwrap();
        assert_eq!(spec.view_limit, Some(1));
    }

    #[test]
    fn unknown_trigger_falls_back_to_first_open() {
        let spec = ephemeral_spec(r#"{"mt":"EPHEMERAL","eph":{"trg":"нечто"}}"#).unwrap();
        assert_eq!(spec.trigger, "FIRST_OPEN");
    }
}
