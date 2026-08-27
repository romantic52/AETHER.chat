//! Thin wasm-bindgen adapter. All cryptographic state transitions are in the
//! shared `aether-ratchet-core` crate used by native clients too.

use wasm_bindgen::prelude::*;

fn js_err(message: String) -> JsValue {
    JsValue::from_str(&message)
}

fn json<T: serde::Serialize>(value: &T) -> Result<String, JsValue> {
    serde_json::to_string(value).map_err(|error| JsValue::from_str(&error.to_string()))
}

#[wasm_bindgen]
pub fn account_new() -> Result<String, JsValue> {
    aether_ratchet_core::account_new().map_err(js_err)
}

#[wasm_bindgen]
pub fn account_identity(account_pickle: &str) -> Result<String, JsValue> {
    aether_ratchet_core::account_identity(account_pickle).map_err(js_err)
}

#[wasm_bindgen]
pub fn account_otk_count(account_pickle: &str) -> Result<u32, JsValue> {
    aether_ratchet_core::account_otk_count(account_pickle).map_err(js_err)
}

#[wasm_bindgen]
pub fn account_generate_otks(account_pickle: &str, count: u32) -> Result<String, JsValue> {
    json(&aether_ratchet_core::account_generate_otks(account_pickle, count).map_err(js_err)?)
}

#[wasm_bindgen]
pub fn account_ed25519(account_pickle: &str) -> Result<String, JsValue> {
    aether_ratchet_core::account_ed25519(account_pickle).map_err(js_err)
}

#[wasm_bindgen]
pub fn account_generate_otks_signed(
    account_pickle: &str,
    count: u32,
    user_id: &str,
    device_id: &str,
) -> Result<String, JsValue> {
    json(
        &aether_ratchet_core::account_generate_otks_signed(account_pickle, count, user_id, device_id)
            .map_err(js_err)?,
    )
}

/// Fallback-ключ (P10 / SEC MED-3) — «последний рубеж», когда одноразовые ключи
/// на сервере кончились. Переиспользуемый, поэтому forward secrecy у первой
/// сессии слабее; подписан тем же каноном `AETHER-OTK-1`, что и обычные OTK.
#[wasm_bindgen]
pub fn account_generate_fallback_signed(
    account_pickle: &str,
    user_id: &str,
    device_id: &str,
) -> Result<String, JsValue> {
    json(
        &aether_ratchet_core::account_generate_fallback_signed(account_pickle, user_id, device_id)
            .map_err(js_err)?,
    )
}

#[wasm_bindgen]
pub fn verify_identity(
    user_id: &str,
    device_id: &str,
    identity_key_b64: &str,
    ed25519_key_b64: &str,
    identity_sig_b64: &str,
) -> Result<(), JsValue> {
    aether_ratchet_core::verify_identity(
        user_id,
        device_id,
        identity_key_b64,
        ed25519_key_b64,
        identity_sig_b64,
    )
    .map_err(js_err)
}

#[wasm_bindgen]
#[allow(clippy::too_many_arguments)]
pub fn verify_prekey_bundle(
    user_id: &str,
    device_id: &str,
    identity_key_b64: &str,
    ed25519_key_b64: &str,
    identity_sig_b64: &str,
    otk_id: &str,
    otk_b64: &str,
    otk_sig_b64: &str,
) -> Result<(), JsValue> {
    aether_ratchet_core::verify_prekey_bundle(
        user_id,
        device_id,
        identity_key_b64,
        ed25519_key_b64,
        identity_sig_b64,
        otk_id,
        otk_b64,
        otk_sig_b64,
    )
    .map_err(js_err)
}

#[wasm_bindgen]
pub fn master_public(account_secret_b64: &str) -> Result<String, JsValue> {
    aether_ratchet_core::master_public(account_secret_b64).map_err(js_err)
}

#[wasm_bindgen]
pub fn sign_device(
    account_secret_b64: &str,
    user_id: &str,
    device_id: &str,
    identity_key_b64: &str,
    ed25519_key_b64: &str,
) -> Result<String, JsValue> {
    aether_ratchet_core::sign_device(
        account_secret_b64,
        user_id,
        device_id,
        identity_key_b64,
        ed25519_key_b64,
    )
    .map_err(js_err)
}

#[wasm_bindgen]
pub fn verify_device(
    master_key_b64: &str,
    user_id: &str,
    device_id: &str,
    identity_key_b64: &str,
    ed25519_key_b64: &str,
    device_sig_b64: &str,
) -> Result<(), JsValue> {
    aether_ratchet_core::verify_device(
        master_key_b64,
        user_id,
        device_id,
        identity_key_b64,
        ed25519_key_b64,
        device_sig_b64,
    )
    .map_err(js_err)
}

#[wasm_bindgen]
pub fn create_outbound(
    account_pickle: &str,
    their_identity_b64: &str,
    their_one_time_key_b64: &str,
) -> Result<String, JsValue> {
    aether_ratchet_core::create_outbound(
        account_pickle,
        their_identity_b64,
        their_one_time_key_b64,
    )
    .map_err(js_err)
}

/// Идентификатор сессии — ключ в локальном хранилище сессий (P10 / SEC MED-4).
/// Совпадает у обеих сторон одной сессии.
#[wasm_bindgen]
pub fn session_id(session_pickle: &str) -> Result<String, JsValue> {
    aether_ratchet_core::session_id(session_pickle).map_err(js_err)
}

/// Идентификатор сессии, которую ЗАВЁЛ БЫ входящий prekey-конверт. Совпал с уже
/// имеющейся — конверт принадлежит ей, новую заводить не надо (иначе каждый
/// повторный prekey жёг бы одноразовый ключ).
#[wasm_bindgen]
pub fn prekey_session_id(body_b64: &str) -> Result<String, JsValue> {
    aether_ratchet_core::prekey_session_id(body_b64).map_err(js_err)
}

#[wasm_bindgen]
pub fn encrypt(session_pickle: &str, plaintext: &str) -> Result<String, JsValue> {
    json(&aether_ratchet_core::encrypt(session_pickle, plaintext).map_err(js_err)?)
}

#[wasm_bindgen]
pub fn create_inbound(
    account_pickle: &str,
    their_identity_b64: &str,
    body_b64: &str,
) -> Result<String, JsValue> {
    json(&aether_ratchet_core::create_inbound(
        account_pickle,
        their_identity_b64,
        body_b64,
    )
    .map_err(js_err)?)
}

#[wasm_bindgen]
pub fn decrypt(
    session_pickle: &str,
    message_type: u32,
    body_b64: &str,
) -> Result<String, JsValue> {
    json(&aether_ratchet_core::decrypt(session_pickle, message_type, body_b64).map_err(js_err)?)
}

// --- Вывод ключа резервной копии (формат v2) ----------------------------------
//
// Веб шифрует и расшифровывает саму копию через WebCrypto (AES-GCM) — он это
// умеет хорошо и быстро. Чего браузер не умеет вовсе, так это Argon2id, а
// именно он выводит ключ в формате v2.
//
// Поэтому в WASM ровно одна недостающая операция, а не вся криптография:
// меньше кода в эфире и никакой второй реализации AES.
//
// Тот же крейт argon2, что в ядре, с теми же параметрами из блоба — байты
// совпадут с iOS и Android без отдельной сверки.
#[wasm_bindgen]
pub fn argon2id_key(password: &str, salt: &[u8], m: u32, t: u32, p: u32) -> Result<Vec<u8>, JsValue> {
    use argon2::{Algorithm, Argon2, Params, Version};

    // Чужой блоб может требовать гигабайты памяти. Вкладка не должна ложиться
    // от того, что кто-то прислал странные параметры.
    if m > 1_048_576 || t > 16 || p > 16 {
        return Err(JsValue::from_str("Argon2: недопустимые параметры"));
    }
    let params = Params::new(m, t, p, Some(32))
        .map_err(|e| JsValue::from_str(&format!("параметры Argon2: {e}")))?;
    let argon = Argon2::new(Algorithm::Argon2id, Version::V0x13, params);
    let mut key = vec![0u8; 32];
    argon
        .hash_password_into(password.as_bytes(), salt, &mut key)
        .map_err(|e| JsValue::from_str(&format!("Argon2: {e}")))?;
    Ok(key)
}

/// Проверка подписи Ed25519 — для документа /server/info.
///
/// Возвращает true/false, а не бросает: «подпись не сошлась» это ожидаемый
/// ответ при подмене сервера, а не сбой программы.
#[wasm_bindgen]
pub fn ed25519_verify(public_key_b64: &str, message: &str, sig_b64: &str) -> bool {
    aether_ratchet_core::verify_ed25519_signature(public_key_b64, message, sig_b64).is_ok()
}

/// Описание исчезающего сообщения из нагрузки. `null` — сообщение обычное.
///
/// Разбор общий с native: правила зажима враждебных значений лежат в
/// ratchet-core, чтобы веб не оказался единственным клиентом, у которого
/// «исчезающее» тихо остаётся навсегда.
#[wasm_bindgen]
pub fn ephemeral_from_payload(payload_json: &str) -> Option<String> {
    aether_ratchet_core::ephemeral::ephemeral_spec_json(payload_json)
}
