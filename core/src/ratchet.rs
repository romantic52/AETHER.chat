//! Native UniFFI adapter for the shared AETHER Double Ratchet engine.
//! The actual vodozemac plumbing lives in `core/ratchet-core` and is also used
//! by the browser WASM adapter.

use crate::CoreError;

fn err(message: String) -> CoreError {
    CoreError::Crypto { msg: message }
}

#[derive(uniffi::Record)]
pub struct OlmPublish {
    pub account_pickle: String,
    pub identity_key_b64: String,
    pub one_time_keys_json: String,
}

#[derive(uniffi::Record)]
pub struct OlmPublishSigned {
    pub account_pickle: String,
    pub identity_key_b64: String,
    pub ed25519_key_b64: String,
    pub identity_sig_b64: String,
    pub one_time_keys_json: String,
    pub otk_signatures_json: String,
}

/// Fallback-ключ устройства (P10 / SEC MED-3): выдаётся вместо одноразового,
/// когда те кончились. Переиспользуемый, поэтому forward secrecy слабее — зато
/// исчерпание OTK больше не глушит переписку.
#[derive(uniffi::Record)]
pub struct OlmFallbackPublish {
    pub account_pickle: String,
    pub identity_key_b64: String,
    pub key_id: String,
    pub key_b64: String,
    pub sig_b64: String,
}

#[derive(uniffi::Record)]
pub struct OlmEncrypted {
    pub session_pickle: String,
    pub message_type: u32,
    pub body_b64: String,
}

#[derive(uniffi::Record)]
pub struct OlmDecrypted {
    pub session_pickle: String,
    pub plaintext: String,
}

#[derive(uniffi::Record)]
pub struct OlmInbound {
    pub account_pickle: String,
    pub session_pickle: String,
    pub plaintext: String,
}

#[uniffi::export]
pub fn olm_account_new() -> Result<String, CoreError> {
    aether_ratchet_core::account_new().map_err(err)
}

#[uniffi::export]
pub fn olm_account_identity(account_pickle: String) -> Result<String, CoreError> {
    aether_ratchet_core::account_identity(&account_pickle).map_err(err)
}

#[uniffi::export]
pub fn olm_account_otk_count(account_pickle: String) -> Result<u32, CoreError> {
    aether_ratchet_core::account_otk_count(&account_pickle).map_err(err)
}

#[uniffi::export]
pub fn olm_account_generate_otks(account_pickle: String, count: u32) -> Result<OlmPublish, CoreError> {
    let result = aether_ratchet_core::account_generate_otks(&account_pickle, count).map_err(err)?;
    Ok(OlmPublish {
        account_pickle: result.account_pickle,
        identity_key_b64: result.identity_key_b64,
        one_time_keys_json: result.one_time_keys_json,
    })
}

#[uniffi::export]
pub fn olm_account_ed25519(account_pickle: String) -> Result<String, CoreError> {
    aether_ratchet_core::account_ed25519(&account_pickle).map_err(err)
}

#[uniffi::export]
pub fn olm_account_generate_otks_signed(
    account_pickle: String,
    count: u32,
    user_id: String,
    device_id: String,
) -> Result<OlmPublishSigned, CoreError> {
    let result =
        aether_ratchet_core::account_generate_otks_signed(&account_pickle, count, &user_id, &device_id)
            .map_err(err)?;
    Ok(OlmPublishSigned {
        account_pickle: result.account_pickle,
        identity_key_b64: result.identity_key_b64,
        ed25519_key_b64: result.ed25519_key_b64,
        identity_sig_b64: result.identity_sig_b64,
        one_time_keys_json: result.one_time_keys_json,
        otk_signatures_json: result.otk_signatures_json,
    })
}

#[uniffi::export]
pub fn olm_account_generate_fallback_signed(
    account_pickle: String,
    user_id: String,
    device_id: String,
) -> Result<OlmFallbackPublish, CoreError> {
    let result =
        aether_ratchet_core::account_generate_fallback_signed(&account_pickle, &user_id, &device_id)
            .map_err(err)?;
    Ok(OlmFallbackPublish {
        account_pickle: result.account_pickle,
        identity_key_b64: result.identity_key_b64,
        key_id: result.key_id,
        key_b64: result.key_b64,
        sig_b64: result.sig_b64,
    })
}

/// Идентификатор сессии — ключ строки в таблице `olm_sessions` (мультисессии, P10).
#[uniffi::export]
pub fn olm_session_id(session_pickle: String) -> Result<String, CoreError> {
    aether_ratchet_core::session_id(&session_pickle).map_err(err)
}

/// Идентификатор сессии, которую завёл бы входящий prekey-конверт. Совпал с
/// имеющейся — конверт принадлежит ей, новую сессию заводить не надо.
#[uniffi::export]
pub fn olm_prekey_session_id(body_b64: String) -> Result<String, CoreError> {
    aether_ratchet_core::prekey_session_id(&body_b64).map_err(err)
}

/// Разобранная QR-метка сверки ключей.
#[derive(uniffi::Record)]
pub struct OlmVerifyQr {
    pub user_id: String,
    pub master_key_b64: String,
}

/// Содержимое QR-метки для сверки мастер-ключа (канон `aether:verify?v=2`).
#[uniffi::export]
pub fn olm_verify_qr_build(user_id: String, master_key_b64: String) -> Result<String, CoreError> {
    aether_ratchet_core::verify_qr_build(&user_id, &master_key_b64).map_err(err)
}

/// Разобрать отсканированную метку. Ошибка = это не наш QR либо он испорчен.
#[uniffi::export]
pub fn olm_verify_qr_parse(text: String) -> Result<OlmVerifyQr, CoreError> {
    let parsed = aether_ratchet_core::verify_qr_parse(&text).map_err(err)?;
    Ok(OlmVerifyQr { user_id: parsed.user_id, master_key_b64: parsed.master_key_b64 })
}

#[uniffi::export]
pub fn olm_verify_identity(
    user_id: String,
    device_id: String,
    identity_key_b64: String,
    ed25519_key_b64: String,
    identity_sig_b64: String,
) -> Result<(), CoreError> {
    aether_ratchet_core::verify_identity(
        &user_id,
        &device_id,
        &identity_key_b64,
        &ed25519_key_b64,
        &identity_sig_b64,
    )
    .map_err(err)
}

#[uniffi::export]
#[allow(clippy::too_many_arguments)]
pub fn olm_verify_prekey_bundle(
    user_id: String,
    device_id: String,
    identity_key_b64: String,
    ed25519_key_b64: String,
    identity_sig_b64: String,
    otk_id: String,
    otk_b64: String,
    otk_sig_b64: String,
) -> Result<(), CoreError> {
    aether_ratchet_core::verify_prekey_bundle(
        &user_id,
        &device_id,
        &identity_key_b64,
        &ed25519_key_b64,
        &identity_sig_b64,
        &otk_id,
        &otk_b64,
        &otk_sig_b64,
    )
    .map_err(err)
}

// ---- Cross-signing устройств мастер-ключом аккаунта (P8) ----

#[uniffi::export]
pub fn olm_master_public(account_secret_b64: String) -> Result<String, CoreError> {
    aether_ratchet_core::master_public(&account_secret_b64).map_err(err)
}

/// Ключ шифрования резервной копии истории (P9): AES-256-GCM, выводится из
/// приватного ключа аккаунта — доступен на любом устройстве после входа,
/// сервер вывести его не может.
#[uniffi::export]
pub fn backup_key(account_secret_b64: String) -> Result<String, CoreError> {
    aether_ratchet_core::backup_key(&account_secret_b64).map_err(err)
}

#[uniffi::export]
pub fn olm_sign_device(
    account_secret_b64: String,
    user_id: String,
    device_id: String,
    identity_key_b64: String,
    ed25519_key_b64: String,
) -> Result<String, CoreError> {
    aether_ratchet_core::sign_device(
        &account_secret_b64,
        &user_id,
        &device_id,
        &identity_key_b64,
        &ed25519_key_b64,
    )
    .map_err(err)
}

#[uniffi::export]
pub fn olm_verify_device(
    master_key_b64: String,
    user_id: String,
    device_id: String,
    identity_key_b64: String,
    ed25519_key_b64: String,
    device_sig_b64: String,
) -> Result<(), CoreError> {
    aether_ratchet_core::verify_device(
        &master_key_b64,
        &user_id,
        &device_id,
        &identity_key_b64,
        &ed25519_key_b64,
        &device_sig_b64,
    )
    .map_err(err)
}

#[uniffi::export]
pub fn olm_create_outbound(
    account_pickle: String,
    their_identity_b64: String,
    their_one_time_key_b64: String,
) -> Result<String, CoreError> {
    aether_ratchet_core::create_outbound(
        &account_pickle,
        &their_identity_b64,
        &their_one_time_key_b64,
    )
    .map_err(err)
}

#[uniffi::export]
pub fn olm_encrypt(session_pickle: String, plaintext: String) -> Result<OlmEncrypted, CoreError> {
    let result = aether_ratchet_core::encrypt(&session_pickle, &plaintext).map_err(err)?;
    Ok(OlmEncrypted {
        session_pickle: result.session_pickle,
        message_type: result.message_type,
        body_b64: result.body_b64,
    })
}

#[uniffi::export]
pub fn olm_create_inbound(
    account_pickle: String,
    their_identity_b64: String,
    body_b64: String,
) -> Result<OlmInbound, CoreError> {
    let result = aether_ratchet_core::create_inbound(
        &account_pickle,
        &their_identity_b64,
        &body_b64,
    )
    .map_err(err)?;
    Ok(OlmInbound {
        account_pickle: result.account_pickle,
        session_pickle: result.session_pickle,
        plaintext: result.plaintext,
    })
}

#[uniffi::export]
pub fn olm_decrypt(
    session_pickle: String,
    message_type: u32,
    body_b64: String,
) -> Result<OlmDecrypted, CoreError> {
    let result = aether_ratchet_core::decrypt(&session_pickle, message_type, &body_b64).map_err(err)?;
    Ok(OlmDecrypted {
        session_pickle: result.session_pickle,
        plaintext: result.plaintext,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ratchet_two_way() {
        let bob = olm_account_generate_otks(olm_account_new().unwrap(), 3).unwrap();
        let otks: serde_json::Value = serde_json::from_str(&bob.one_time_keys_json).unwrap();
        let bob_otk = otks.as_object().unwrap().values().next().unwrap().as_str().unwrap().to_owned();

        let alice_account = olm_account_new().unwrap();
        let alice_identity = olm_account_identity(alice_account.clone()).unwrap();
        let alice_session = olm_create_outbound(
            alice_account,
            bob.identity_key_b64,
            bob_otk,
        )
        .unwrap();
        let first = olm_encrypt(alice_session.clone(), "привет боб".into()).unwrap();
        assert_eq!(first.message_type, 0);

        let inbound = olm_create_inbound(
            bob.account_pickle,
            alice_identity,
            first.body_b64,
        )
        .unwrap();
        assert_eq!(inbound.plaintext, "привет боб");

        let reply = olm_encrypt(inbound.session_pickle, "привет алиса".into()).unwrap();
        assert_eq!(reply.message_type, 1);
        assert_eq!(
            olm_decrypt(alice_session, reply.message_type, reply.body_b64)
                .unwrap()
                .plaintext,
            "привет алиса"
        );
    }
}
