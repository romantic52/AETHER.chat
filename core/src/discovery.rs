//! Обнаружение Aether-сервера по адресу, введённому человеком.
//!
//! Живёт в ядре, а не в клиенте, намеренно: нормализация адреса, порядок
//! запросов и проверка подписи обязаны быть одинаковыми на iOS, Android и в
//! вебе. Разъедься они — и «сервер найден» на одной платформе означало бы не
//! то же самое, что на другой.
//!
//! Канон документа и подписи — docs/MULTI_SERVER_DESIGN.md, раздел 8.1.

use crate::CoreError;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use std::time::Duration;

/// Версия протокола, которую понимает этот клиент.
pub const SUPPORTED_PROTOCOL: u32 = 1;

const CANON_PREFIX: &str = "AETHER-SERVER-INFO-1";

/// Пути обнаружения, в порядке предпочтения. Последний — для инстансов,
/// которые ещё не знают про версионирование.
const DISCOVERY_PATHS: [&str; 3] = ["/.well-known/aether", "/api/v1/server/info", "/server/info"];

#[derive(uniffi::Record, Clone)]
pub struct ServerInfo {
    /// Origin, по которому сервер реально ответил (схема + хост + порт).
    pub origin: String,
    pub server_id: String,
    pub name: String,
    pub api_url: String,
    pub websocket_url: String,
    pub registration_mode: String,
    pub protocol_version: u32,
    pub supports_e2ee: bool,
    pub supports_data_import: bool,
    pub capabilities: Vec<String>,
    pub max_upload_bytes: i64,
    pub official_claim: bool,
    pub software: String,
    /// Публичный ключ подписи (Ed25519, b64url) — основа TOFU-пина.
    pub public_key_b64: String,
    /// SHA-256 от публичного ключа: то, что показывается человеку.
    pub fingerprint_b64: String,
    pub signed_at: String,
    /// Подпись сошлась. False сюда не доходит — несошедшаяся подпись это ошибка.
    pub signature_valid: bool,
    /// Хост в api_url/websocket_url совпадает с origin, куда мы стучались.
    /// Расхождение — повод для тревоги: Host подконтролен посреднику.
    pub endpoints_match_origin: bool,
    /// Соединение без TLS (локальный режим). Клиент обязан это показать.
    pub cleartext: bool,
}

/// Разбор адреса, введённого человеком, в список кандидатов для проверки.
///
/// `allow_cleartext` добавляет http-кандидатов, и ТОЛЬКО для адресов из
/// приватных диапазонов: включать открытый транспорт для публичного домена
/// нельзя ни по какой просьбе пользователя.
#[uniffi::export]
pub fn normalize_server_input(input: String, allow_cleartext: bool) -> Vec<String> {
    let raw = input.trim();
    if raw.is_empty() {
        return vec![];
    }
    // aether://host → https://host: собственная схема нужна только для ссылок.
    let stripped = raw
        .strip_prefix("aether://")
        .or_else(|| raw.strip_prefix("AETHER://"))
        .unwrap_or(raw);

    let (scheme, rest) = if let Some(r) = stripped.strip_prefix("https://") {
        (Some("https"), r)
    } else if let Some(r) = stripped.strip_prefix("http://") {
        (Some("http"), r)
    } else {
        (None, stripped)
    };

    // Путь и хвост запроса отбрасываем: сервер задаётся origin'ом.
    let authority = rest.split(['/', '?', '#']).next().unwrap_or("").trim_end_matches('.');
    if authority.is_empty() || !valid_authority(authority) {
        return vec![];
    }

    let host = host_of(authority.to_string());
    let private = is_private_host(host.clone());

    match scheme {
        // Явный https — уважаем и не подставляем ничего другого.
        Some("https") => vec![format!("https://{authority}")],
        // Явный http — только если это приватный адрес и режим разрешён.
        Some("http") => {
            if allow_cleartext && private {
                vec![format!("http://{authority}")]
            } else {
                vec![]
            }
        }
        _ => {
            let mut out = vec![format!("https://{authority}")];
            if allow_cleartext && private {
                out.push(format!("http://{authority}"));
            }
            out
        }
    }
}

fn valid_authority(authority: &str) -> bool {
    // Хост[:порт] без пробелов и учётных данных. Логин в адресе — верный
    // признак фишинговой ссылки вида https://aether.app@evil.example.
    if authority.contains(' ') || authority.contains('@') {
        return false;
    }
    let host = host_of(authority.to_string());
    if host.is_empty() {
        return false;
    }
    if let Some((_, port)) = authority.rsplit_once(':') {
        if !host.contains(':') && (port.is_empty() || !port.chars().all(|c| c.is_ascii_digit())) {
            return false;
        }
    }
    host.chars()
        .all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '-' || c == '_' || c == ':')
}

fn host_of(authority: String) -> String {
    // IPv6 в квадратных скобках: [::1]:8443
    if let Some(end) = authority.find(']') {
        if authority.starts_with('[') {
            return authority[1..end].to_string();
        }
    }
    match authority.rsplit_once(':') {
        Some((h, _)) if !h.is_empty() => h.to_string(),
        _ => authority,
    }
}

/// Адрес из локальной сети: RFC1918, link-local, loopback, .local.
/// По нему и только по нему допускается режим открытого транспорта.
#[uniffi::export]
pub fn is_private_host(host: String) -> bool {
    let h = host.trim().to_ascii_lowercase();
    if h == "localhost" || h.ends_with(".local") || h.ends_with(".localhost") {
        return true;
    }
    if h == "::1" || h.starts_with("fe80:") || h.starts_with("fc") || h.starts_with("fd") {
        return true;
    }
    let octets: Vec<&str> = h.split('.').collect();
    if octets.len() != 4 || !octets.iter().all(|o| o.parse::<u8>().is_ok()) {
        return false;
    }
    let n: Vec<u8> = octets.iter().map(|o| o.parse::<u8>().unwrap()).collect();
    match (n[0], n[1]) {
        (10, _) => true,
        (127, _) => true,
        (192, 168) => true,
        (169, 254) => true,
        (172, b) if (16..=31).contains(&b) => true,
        _ => false,
    }
}

fn agent() -> ureq::Agent {
    ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(6))
        .timeout(Duration::from_secs(12))
        .build()
}

/// Опросить один origin. Ошибка — это «здесь не Aether» или «не достучались».
#[uniffi::export]
pub fn fetch_server_info(origin: String, nonce: String) -> Result<ServerInfo, CoreError> {
    let base = origin.trim_end_matches('/').to_string();
    let agent = agent();
    let mut last: Option<CoreError> = None;

    for path in DISCOVERY_PATHS {
        let url = format!("{base}{path}");
        let response = agent.get(&url).query("nonce", &nonce).call();
        match response {
            Ok(r) => {
                let body: serde_json::Value = r.into_json().map_err(|e| CoreError::BadInput {
                    msg: format!("ответ сервера не JSON: {e}"),
                })?;
                return parse_and_verify(&base, &body, &nonce);
            }
            Err(ureq::Error::Status(404, _)) => {
                // Этого пути нет — пробуем следующий.
                last = Some(CoreError::Api { status: 404, msg: "не найдено".into() });
                continue;
            }
            Err(ureq::Error::Status(code, r)) => {
                let msg = r.into_string().unwrap_or_default();
                return Err(CoreError::Api { status: code, msg });
            }
            Err(e) => {
                last = Some(CoreError::Network { msg: e.to_string() });
                // Сеть/TLS: следующий путь на том же origin не поможет.
                break;
            }
        }
    }
    Err(last.unwrap_or(CoreError::Network { msg: "сервер не ответил".into() }))
}

/// Полный проход: нормализовать ввод и опросить кандидатов по очереди.
#[uniffi::export]
pub fn discover_server(
    input: String,
    nonce: String,
    allow_cleartext: bool,
) -> Result<ServerInfo, CoreError> {
    let candidates = normalize_server_input(input, allow_cleartext);
    if candidates.is_empty() {
        return Err(CoreError::bad("адрес не разобран"));
    }
    let mut last = CoreError::Network { msg: "сервер не ответил".into() };
    for origin in candidates {
        match fetch_server_info(origin, nonce.clone()) {
            Ok(info) => return Ok(info),
            Err(e) => last = e,
        }
    }
    Err(last)
}

fn s(v: &serde_json::Value, key: &str) -> String {
    v[key].as_str().unwrap_or_default().to_string()
}

fn parse_and_verify(
    origin: &str,
    body: &serde_json::Value,
    expected_nonce: &str,
) -> Result<ServerInfo, CoreError> {
    if s(body, "protocol") != "aether" {
        return Err(CoreError::bad("по этому адресу нет сервера Aether"));
    }
    let protocol_version = body["protocol_version"].as_u64().unwrap_or(0) as u32;
    if protocol_version == 0 {
        return Err(CoreError::bad("сервер не сообщил версию протокола"));
    }
    if protocol_version > SUPPORTED_PROTOCOL {
        return Err(CoreError::bad(format!(
            "сервер говорит на протоколе v{protocol_version}, приложение понимает v{SUPPORTED_PROTOCOL}"
        )));
    }

    let server_id = s(body, "server_id");
    let name = s(body, "name");
    let api_url = s(body, "api_url");
    let ws_url = s(body, "websocket_url");
    let mode = s(body, "registration_mode").to_ascii_lowercase();
    let signed_at = s(body, "signed_at");
    let nonce = s(body, "nonce");
    let pub_b64 = s(body, "public_key_b64");
    let sig_b64 = s(body, "signature_b64");

    if server_id.is_empty() || api_url.is_empty() || ws_url.is_empty() || pub_b64.is_empty() {
        return Err(CoreError::bad("ответ сервера неполон"));
    }
    // Nonce сверяется ДО подписи: без него подпись доказывает лишь то, что
    // документ когда-то был подписан, а не что он ответ на наш запрос.
    if nonce != expected_nonce {
        return Err(CoreError::crypto("сервер вернул чужой nonce (возможен повтор старого ответа)"));
    }

    let message = [
        CANON_PREFIX,
        &server_id,
        &name,
        &api_url,
        &ws_url,
        &mode,
        &protocol_version.to_string(),
        &signed_at,
        &nonce,
    ]
    .join("\n");

    verify_ed25519(&pub_b64, message.as_bytes(), &sig_b64)?;

    let origin_host = host_of(origin.split("://").nth(1).unwrap_or("").to_string());
    let endpoints_match_origin =
        url_host(&api_url) == origin_host && url_host(&ws_url) == origin_host;

    let caps = body["capabilities"]
        .as_array()
        .map(|a| a.iter().filter_map(|v| v.as_str().map(str::to_string)).collect())
        .unwrap_or_default();

    let fingerprint = fingerprint_of(&pub_b64)?;

    Ok(ServerInfo {
        origin: origin.to_string(),
        server_id,
        name,
        api_url,
        websocket_url: ws_url,
        registration_mode: mode,
        protocol_version,
        supports_e2ee: body["supports_e2ee"].as_bool().unwrap_or(false),
        supports_data_import: body["supports_data_import"].as_bool().unwrap_or(false),
        capabilities: caps,
        max_upload_bytes: body["max_upload_bytes"].as_i64().unwrap_or(0),
        official_claim: body["official"].as_bool().unwrap_or(false),
        software: format!(
            "{} {}",
            s(&body["software"], "name"),
            s(&body["software"], "version")
        )
        .trim()
        .to_string(),
        public_key_b64: pub_b64,
        fingerprint_b64: fingerprint,
        signed_at,
        signature_valid: true,
        endpoints_match_origin,
        cleartext: origin.starts_with("http://"),
    })
}

fn url_host(url: &str) -> String {
    let rest = url.split("://").nth(1).unwrap_or(url);
    let authority = rest.split(['/', '?', '#']).next().unwrap_or("");
    host_of(authority.to_string())
}

fn verify_ed25519(pub_b64: &str, message: &[u8], sig_b64: &str) -> Result<(), CoreError> {
    use ed25519_dalek::{Signature, VerifyingKey};

    let pk_bytes: [u8; 32] = crate::crypto::decode_b64(pub_b64)?
        .try_into()
        .map_err(|_| CoreError::crypto("ключ сервера: ожидалось 32 байта"))?;
    let sig_bytes: [u8; 64] = crate::crypto::decode_b64(sig_b64)?
        .try_into()
        .map_err(|_| CoreError::crypto("подпись сервера: ожидалось 64 байта"))?;
    let key = VerifyingKey::from_bytes(&pk_bytes).map_err(CoreError::crypto)?;
    // verify_strict, а не verify: отвергает неканонические кодировки точек.
    key.verify_strict(message, &Signature::from_bytes(&sig_bytes))
        .map_err(|_| CoreError::Crypto { msg: "подпись сервера не сошлась".into() })
}

/// Отпечаток ключа сервера — то, что человек сверяет с владельцем сервера.
#[uniffi::export]
pub fn server_fingerprint(public_key_b64: String) -> Result<String, CoreError> {
    fingerprint_of(&public_key_b64)
}

fn fingerprint_of(pub_b64: &str) -> Result<String, CoreError> {
    use sha2::{Digest, Sha256};
    let bytes = crate::crypto::decode_b64(pub_b64)?;
    let digest = Sha256::digest(&bytes);
    Ok(URL_SAFE_NO_PAD.encode(digest))
}

/// Отпечаток группами по 4 символа — так его читают вслух и сверяют глазами.
#[uniffi::export]
pub fn format_fingerprint(fingerprint_b64: String) -> String {
    fingerprint_b64
        .chars()
        .collect::<Vec<_>>()
        .chunks(4)
        .map(|c| c.iter().collect::<String>())
        .collect::<Vec<_>>()
        .join(" ")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalizes_common_inputs() {
        assert_eq!(normalize_server_input("chat.example.com".into(), false),
                   vec!["https://chat.example.com"]);
        assert_eq!(normalize_server_input("https://chat.example.com/app?x=1".into(), false),
                   vec!["https://chat.example.com"]);
        assert_eq!(normalize_server_input("aether://chat.example.com".into(), false),
                   vec!["https://chat.example.com"]);
        assert_eq!(normalize_server_input("192.168.1.25:8443".into(), false),
                   vec!["https://192.168.1.25:8443"]);
    }

    #[test]
    fn cleartext_only_for_private_addresses() {
        // Локальный адрес: http допускается вторым кандидатом.
        let lan = normalize_server_input("192.168.1.25".into(), true);
        assert_eq!(lan, vec!["https://192.168.1.25", "http://192.168.1.25"]);
        // Публичный домен: открытый транспорт не предлагается даже по просьбе.
        let public = normalize_server_input("chat.example.com".into(), true);
        assert_eq!(public, vec!["https://chat.example.com"]);
        // Явный http на публичный домен — отказ, а не молчаливый апгрейд.
        assert!(normalize_server_input("http://chat.example.com".into(), true).is_empty());
    }

    #[test]
    fn rejects_credentials_in_authority() {
        // https://aether.app@evil.example ведёт на evil.example.
        assert!(normalize_server_input("https://aether.app@evil.example".into(), false).is_empty());
    }

    #[test]
    fn private_ranges() {
        for h in ["10.0.0.1", "192.168.1.25", "172.16.0.1", "172.31.255.1",
                  "127.0.0.1", "169.254.1.1", "localhost", "roman.local", "::1"] {
            assert!(is_private_host(h.into()), "{h} должен считаться локальным");
        }
        for h in ["8.8.8.8", "172.32.0.1", "chat.example.com", "1.1.1.1"] {
            assert!(!is_private_host(h.into()), "{h} локальным не является");
        }
    }

    #[test]
    fn fingerprint_is_stable_and_grouped() {
        let key = URL_SAFE_NO_PAD.encode([7u8; 32]);
        let fp = server_fingerprint(key.clone()).unwrap();
        assert_eq!(fp, server_fingerprint(key).unwrap());
        assert!(format_fingerprint(fp).contains(' '));
    }
}
