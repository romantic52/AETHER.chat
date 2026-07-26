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
