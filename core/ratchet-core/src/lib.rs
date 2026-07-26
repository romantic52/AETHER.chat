//! Pure AETHER Olm/Double Ratchet engine shared by native and web adapters.
//! Networking and storage deliberately live in the platform clients.

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use serde::{Deserialize, Serialize};
use vodozemac::olm::{Account, AccountPickle, OlmMessage, Session, SessionConfig, SessionPickle};
use vodozemac::{Curve25519PublicKey, Ed25519PublicKey, Ed25519Signature};

pub type Result<T> = std::result::Result<T, String>;

/// Версионные префиксы канона подписей prekey-бандла. Менять только с bump'ом версии.
pub const IDENTITY_SIG_VERSION: &str = "AETHER-IDKEY-1";
pub const OTK_SIG_VERSION: &str = "AETHER-OTK-1";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Publish {
    pub account_pickle: String,
    pub identity_key_b64: String,
    pub one_time_keys_json: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PublishSigned {
    pub account_pickle: String,
    pub identity_key_b64: String,
    pub ed25519_key_b64: String,
    pub identity_sig_b64: String,
    pub one_time_keys_json: String,
    pub otk_signatures_json: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Encrypted {
    pub session_pickle: String,
    pub message_type: u32,
    pub body_b64: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Decrypted {
    pub session_pickle: String,
    pub plaintext: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Inbound {
    pub account_pickle: String,
    pub session_pickle: String,
    pub plaintext: String,
}

fn cfg() -> SessionConfig {
    SessionConfig::version_2()
}

fn err(e: impl std::fmt::Display) -> String {
    format!("olm: {e}")
}

fn decode_b64(s: &str) -> Result<Vec<u8>> {
    let normalized: String = s
        .trim()
        .trim_end_matches('=')
        .chars()
        .map(|c| match c {
            '+' => '-',
            '/' => '_',
            c => c,
        })
        .collect();
    URL_SAFE_NO_PAD.decode(normalized.as_bytes()).map_err(err)
}

fn account_from(pickle: &str) -> Result<Account> {
    let p: AccountPickle = serde_json::from_str(pickle).map_err(err)?;
    Ok(Account::from_pickle(p))
}

fn account_to(account: &Account) -> Result<String> {
    serde_json::to_string(&account.pickle()).map_err(err)
}

fn session_from(pickle: &str) -> Result<Session> {
    let p: SessionPickle = serde_json::from_str(pickle).map_err(err)?;
    Ok(Session::from_pickle(p))
}

fn session_to(session: &Session) -> Result<String> {
    serde_json::to_string(&session.pickle()).map_err(err)
}

fn identity(b64: &str) -> Result<Curve25519PublicKey> {
    Curve25519PublicKey::from_base64(b64).map_err(err)
}

pub fn account_new() -> Result<String> {
    account_to(&Account::new())
}

pub fn account_identity(account_pickle: &str) -> Result<String> {
    Ok(account_from(account_pickle)?.curve25519_key().to_base64())
}

pub fn account_otk_count(account_pickle: &str) -> Result<u32> {
    Ok(account_from(account_pickle)?.stored_one_time_key_count() as u32)
}

pub fn account_generate_otks(account_pickle: &str, count: u32) -> Result<Publish> {
    let mut account = account_from(account_pickle)?;
    account.generate_one_time_keys(count as usize);
    let keys: serde_json::Map<String, serde_json::Value> = account
        .one_time_keys()
        .into_iter()
        .map(|(id, key)| (id.to_base64(), serde_json::Value::String(key.to_base64())))
        .collect();
    account.mark_keys_as_published();
    Ok(Publish {
        identity_key_b64: account.curve25519_key().to_base64(),
        one_time_keys_json: serde_json::Value::Object(keys).to_string(),
        account_pickle: account_to(&account)?,
    })
}

pub fn account_ed25519(account_pickle: &str) -> Result<String> {
    Ok(account_from(account_pickle)?.ed25519_key().to_base64())
}

/// Канон подписи identity: связывает ed25519-ключ с (user, device, curve25519).
/// user_id нормализуется в lowercase — сервер оперирует lowercase-идентификаторами.
fn identity_canon(user_id: &str, device_id: &str, curve_b64: &str) -> String {
    format!(
        "{IDENTITY_SIG_VERSION}|{}|{}|{}",
        user_id.to_lowercase(),
        device_id,
        curve_b64
    )
}

/// Канон подписи OTK: привязывает одноразовый ключ к владельцу и его identity —
/// сервер не может ни подменить ключи, ни выдать чужой бандл за запрошенный.
fn otk_canon(user_id: &str, device_id: &str, curve_b64: &str, otk_id: &str, otk_b64: &str) -> String {
    format!(
        "{OTK_SIG_VERSION}|{}|{}|{}|{}|{}",
        user_id.to_lowercase(),
        device_id,
        curve_b64,
        otk_id,
        otk_b64
    )
}

pub fn account_generate_otks_signed(
    account_pickle: &str,
    count: u32,
    user_id: &str,
    device_id: &str,
) -> Result<PublishSigned> {
    let mut account = account_from(account_pickle)?;
    account.generate_one_time_keys(count as usize);
    let curve_b64 = account.curve25519_key().to_base64();
    let ed_b64 = account.ed25519_key().to_base64();
    let identity_sig_b64 = account
        .sign(identity_canon(user_id, device_id, &curve_b64).as_bytes())
        .to_base64();
    let mut keys = serde_json::Map::new();
    let mut sigs = serde_json::Map::new();
    for (id, key) in account.one_time_keys() {
        let id_b64 = id.to_base64();
        let key_b64 = key.to_base64();
        let sig = account
            .sign(otk_canon(user_id, device_id, &curve_b64, &id_b64, &key_b64).as_bytes())
            .to_base64();
        keys.insert(id_b64.clone(), serde_json::Value::String(key_b64));
        sigs.insert(id_b64, serde_json::Value::String(sig));
    }
    account.mark_keys_as_published();
    Ok(PublishSigned {
        identity_key_b64: curve_b64,
        ed25519_key_b64: ed_b64,
        identity_sig_b64,
        one_time_keys_json: serde_json::Value::Object(keys).to_string(),
        otk_signatures_json: serde_json::Value::Object(sigs).to_string(),
        account_pickle: account_to(&account)?,
    })
}

fn ed25519_verify(ed_b64: &str, message: &str, sig_b64: &str, what: &str) -> Result<()> {
    let ed = Ed25519PublicKey::from_base64(ed_b64).map_err(err)?;
    let sig = Ed25519Signature::from_base64(sig_b64).map_err(err)?;
    ed.verify(message.as_bytes(), &sig)
        .map_err(|_| format!("olm: подпись {what} не сошлась — возможна подмена ключей сервером"))
}

pub fn verify_identity(
    user_id: &str,
    device_id: &str,
    curve_b64: &str,
    ed_b64: &str,
    identity_sig_b64: &str,
) -> Result<()> {
    ed25519_verify(
        ed_b64,
        &identity_canon(user_id, device_id, curve_b64),
        identity_sig_b64,
        "identity",
    )
}

pub fn verify_prekey_bundle(
    user_id: &str,
    device_id: &str,
    curve_b64: &str,
    ed_b64: &str,
    identity_sig_b64: &str,
    otk_id: &str,
    otk_b64: &str,
    otk_sig_b64: &str,
) -> Result<()> {
    verify_identity(user_id, device_id, curve_b64, ed_b64, identity_sig_b64)?;
    ed25519_verify(
        ed_b64,
        &otk_canon(user_id, device_id, curve_b64, otk_id, otk_b64),
        otk_sig_b64,
        "one-time key",
    )
}

pub fn create_outbound(
    account_pickle: &str,
    their_identity_b64: &str,
    their_one_time_key_b64: &str,
) -> Result<String> {
    let account = account_from(account_pickle)?;
    let session = account
        .create_outbound_session(
            cfg(),
            identity(their_identity_b64)?,
            identity(their_one_time_key_b64)?,
        )
        .map_err(err)?;
    session_to(&session)
}

pub fn encrypt(session_pickle: &str, plaintext: &str) -> Result<Encrypted> {
    let mut session = session_from(session_pickle)?;
    let message = session.encrypt(plaintext.as_bytes()).map_err(err)?;
    let (message_type, body) = message.to_parts();
    Ok(Encrypted {
        session_pickle: session_to(&session)?,
        message_type: message_type as u32,
        body_b64: URL_SAFE_NO_PAD.encode(body),
    })
}

pub fn create_inbound(
    account_pickle: &str,
    their_identity_b64: &str,
    body_b64: &str,
) -> Result<Inbound> {
    let mut account = account_from(account_pickle)?;
    let message = OlmMessage::from_parts(0, &decode_b64(body_b64)?).map_err(err)?;
    let prekey = match message {
        OlmMessage::PreKey(message) => message,
        OlmMessage::Normal(_) => return Err(err("ожидался prekey-конверт")),
    };
    let result = account
        .create_inbound_session(cfg(), identity(their_identity_b64)?, &prekey)
        .map_err(err)?;
    Ok(Inbound {
        account_pickle: account_to(&account)?,
        session_pickle: session_to(&result.session)?,
        plaintext: String::from_utf8(result.plaintext).map_err(err)?,
    })
}

pub fn decrypt(session_pickle: &str, message_type: u32, body_b64: &str) -> Result<Decrypted> {
    let mut session = session_from(session_pickle)?;
    let message = OlmMessage::from_parts(message_type as usize, &decode_b64(body_b64)?).map_err(err)?;
    let plaintext = session.decrypt(&message).map_err(err)?;
    Ok(Decrypted {
        session_pickle: session_to(&session)?,
        plaintext: String::from_utf8(plaintext).map_err(err)?,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn signed_prekeys_verify_and_catch_substitution() {
        let bob = account_generate_otks_signed(&account_new().unwrap(), 2, "Bob", "primary").unwrap();
        let otks: serde_json::Value = serde_json::from_str(&bob.one_time_keys_json).unwrap();
        let sigs: serde_json::Value = serde_json::from_str(&bob.otk_signatures_json).unwrap();
        let (otk_id, otk_b64) = otks
            .as_object()
            .unwrap()
            .iter()
            .map(|(k, v)| (k.clone(), v.as_str().unwrap().to_owned()))
            .next()
            .unwrap();
        let otk_sig = sigs[&otk_id].as_str().unwrap();

        // Честный бандл проходит; user_id регистронезависим.
        verify_prekey_bundle(
            "bob", "primary", &bob.identity_key_b64, &bob.ed25519_key_b64,
            &bob.identity_sig_b64, &otk_id, &otk_b64, otk_sig,
        )
        .unwrap();

        // Подмена любого элемента канона ловится.
        let mallory = account_generate_otks_signed(&account_new().unwrap(), 1, "bob", "primary").unwrap();
        assert!(verify_identity("alice", "primary", &bob.identity_key_b64, &bob.ed25519_key_b64, &bob.identity_sig_b64).is_err());
        assert!(verify_identity("bob", "ios-x", &bob.identity_key_b64, &bob.ed25519_key_b64, &bob.identity_sig_b64).is_err());
        assert!(verify_identity("bob", "primary", &mallory.identity_key_b64, &bob.ed25519_key_b64, &bob.identity_sig_b64).is_err());
        assert!(verify_identity("bob", "primary", &bob.identity_key_b64, &mallory.ed25519_key_b64, &bob.identity_sig_b64).is_err());
        assert!(verify_prekey_bundle(
            "bob", "primary", &bob.identity_key_b64, &bob.ed25519_key_b64,
            &bob.identity_sig_b64, &otk_id, &mallory.identity_key_b64, otk_sig,
        )
        .is_err());

        // Подписанные OTK пригодны для обычного X3DH.
        let alice = account_new().unwrap();
        let session = create_outbound(&alice, &bob.identity_key_b64, &otk_b64).unwrap();
        let msg = encrypt(&session, "проверка").unwrap();
        let inbound = create_inbound(&bob.account_pickle, &account_identity(&alice).unwrap(), &msg.body_b64).unwrap();
        assert_eq!(inbound.plaintext, "проверка");
    }

    #[test]
    fn ratchet_two_way() {
        let bob = account_generate_otks(&account_new().unwrap(), 3).unwrap();
        let otks: serde_json::Value = serde_json::from_str(&bob.one_time_keys_json).unwrap();
        let bob_otk = otks.as_object().unwrap().values().next().unwrap().as_str().unwrap();

        let alice_account = account_new().unwrap();
        let alice_identity = account_identity(&alice_account).unwrap();
        let alice_session = create_outbound(
            &alice_account,
            &bob.identity_key_b64,
            bob_otk,
        )
        .unwrap();
        let first = encrypt(&alice_session, "привет боб").unwrap();
        assert_eq!(first.message_type, 0);

        let inbound = create_inbound(
            &bob.account_pickle,
            &alice_identity,
            &first.body_b64,
        )
        .unwrap();
        assert_eq!(inbound.plaintext, "привет боб");

        let reply = encrypt(&inbound.session_pickle, "привет алиса").unwrap();
        assert_eq!(reply.message_type, 1);
        assert_eq!(
            decrypt(&alice_session, reply.message_type, &reply.body_b64)
                .unwrap()
                .plaintext,
            "привет алиса"
        );
    }
}
