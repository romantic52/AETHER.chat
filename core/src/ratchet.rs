//! Double Ratchet для 1:1 (Olm через vodozemac, аудит Least Authority).
//! X3DH-установка сессии по prekey + асимметричный/симметричный ратчет →
//! forward secrecy + восстановление после компрометации (DH-исцеление).
//!
//! Слой чистый (stateless): всё состояние ходит как pickle-строки. Персистентность
//! и склейка с wire-конвертами — в store.rs / protocol.rs / Swift. Крипту не пишем
//! руками — только плумбинг ключей и состояния.

use crate::crypto::{decode_b64, encode_b64};
use crate::CoreError;
use vodozemac::olm::{Account, AccountPickle, OlmMessage, Session, SessionConfig, SessionPickle};
use vodozemac::Curve25519PublicKey;

fn cfg() -> SessionConfig {
    SessionConfig::version_2()
}

fn err(e: impl std::fmt::Display) -> CoreError {
    CoreError::Crypto { msg: format!("olm: {e}") }
}

// ---- сериализация pickle через serde_json (БД уже шифрует SQLCipher) ----

fn account_from(pickle: &str) -> Result<Account, CoreError> {
    let p: AccountPickle = serde_json::from_str(pickle).map_err(err)?;
    Ok(Account::from_pickle(p))
}

fn account_to(acc: &Account) -> Result<String, CoreError> {
    serde_json::to_string(&acc.pickle()).map_err(err)
}

fn session_from(pickle: &str) -> Result<Session, CoreError> {
    let p: SessionPickle = serde_json::from_str(pickle).map_err(err)?;
    Ok(Session::from_pickle(p))
}

fn session_to(s: &Session) -> Result<String, CoreError> {
    serde_json::to_string(&s.pickle()).map_err(err)
}

fn identity(b64: &str) -> Result<Curve25519PublicKey, CoreError> {
    Curve25519PublicKey::from_base64(b64).map_err(err)
}

// ---- UniFFI-записи ----

/// Публикуемые ключи: обновлённый аккаунт + identity + пачка one-time keys.
#[derive(uniffi::Record)]
pub struct OlmPublish {
    pub account_pickle: String,
    pub identity_key_b64: String,
    /// JSON-объект {keyId: pubkey_b64} для загрузки на сервер.
    pub one_time_keys_json: String,
}

/// Результат шифрования: продвинутая сессия + сам конверт.
#[derive(uniffi::Record)]
pub struct OlmEncrypted {
    pub session_pickle: String,
    /// 0 = prekey (устанавливает сессию), 1 = обычное.
    pub message_type: u32,
    pub body_b64: String,
}

/// Результат расшифровки обычным потоком.
#[derive(uniffi::Record)]
pub struct OlmDecrypted {
    pub session_pickle: String,
    pub plaintext: String,
}

/// Результат установки входящей сессии из prekey-сообщения.
#[derive(uniffi::Record)]
pub struct OlmInbound {
    /// Аккаунт после списания использованного one-time key.
    pub account_pickle: String,
    pub session_pickle: String,
    pub plaintext: String,
}

// ---- Аккаунт ----

/// Новый Olm-аккаунт (identity + Ed25519). Возвращает pickle.
#[uniffi::export]
pub fn olm_account_new() -> Result<String, CoreError> {
    account_to(&Account::new())
}

/// Curve25519 identity-ключ аккаунта (b64) — публикуется на сервер.
#[uniffi::export]
pub fn olm_account_identity(account_pickle: String) -> Result<String, CoreError> {
    Ok(account_from(&account_pickle)?.curve25519_key().to_base64())
}

/// Сколько one-time keys ещё хранится локально (для решения о пополнении).
#[uniffi::export]
pub fn olm_account_otk_count(account_pickle: String) -> Result<u32, CoreError> {
    Ok(account_from(&account_pickle)?.stored_one_time_key_count() as u32)
}

/// Сгенерировать `count` one-time keys, пометить опубликованными, вернуть их
/// публичные части для заливки на сервер.
#[uniffi::export]
pub fn olm_account_generate_otks(account_pickle: String, count: u32) -> Result<OlmPublish, CoreError> {
    let mut acc = account_from(&account_pickle)?;
    acc.generate_one_time_keys(count as usize);
    let map: serde_json::Map<String, serde_json::Value> = acc
        .one_time_keys()
        .into_iter()
        .map(|(id, key)| (id.to_base64(), serde_json::Value::String(key.to_base64())))
        .collect();
    acc.mark_keys_as_published();
    Ok(OlmPublish {
        identity_key_b64: acc.curve25519_key().to_base64(),
        one_time_keys_json: serde_json::Value::Object(map).to_string(),
        account_pickle: account_to(&acc)?,
    })
}

// ---- Сессии ----

/// Исходящая сессия к пиру по его identity + claimed one-time key.
/// Первое сообщение, зашифрованное этой сессией, будет prekey (type 0).
#[uniffi::export]
pub fn olm_create_outbound(
    account_pickle: String,
    their_identity_b64: String,
    their_one_time_key_b64: String,
) -> Result<String, CoreError> {
    let acc = account_from(&account_pickle)?;
    let session = acc
        .create_outbound_session(cfg(), identity(&their_identity_b64)?, identity(&their_one_time_key_b64)?)
        .map_err(err)?;
    session_to(&session)
}

/// Зашифровать сообщение существующей сессией (ратчет продвигается).
#[uniffi::export]
pub fn olm_encrypt(session_pickle: String, plaintext: String) -> Result<OlmEncrypted, CoreError> {
    let mut session = session_from(&session_pickle)?;
    let msg = session.encrypt(plaintext.as_bytes()).map_err(err)?;
    let (message_type, body) = msg.to_parts();
    Ok(OlmEncrypted {
        session_pickle: session_to(&session)?,
        message_type: message_type as u32,
        body_b64: encode_b64(&body),
    })
}

/// Установить входящую сессию из prekey-сообщения (type 0) и расшифровать его.
#[uniffi::export]
pub fn olm_create_inbound(
    account_pickle: String,
    their_identity_b64: String,
    body_b64: String,
) -> Result<OlmInbound, CoreError> {
    let mut acc = account_from(&account_pickle)?;
    let their_identity = identity(&their_identity_b64)?;
    let msg = OlmMessage::from_parts(0, &decode_b64(&body_b64)?).map_err(err)?;
    let prekey = match msg {
        OlmMessage::PreKey(m) => m,
        OlmMessage::Normal(_) => return Err(err("ожидался prekey-конверт")),
    };
    let res = acc.create_inbound_session(cfg(), their_identity, &prekey).map_err(err)?;
    Ok(OlmInbound {
        account_pickle: account_to(&acc)?,
        session_pickle: session_to(&res.session)?,
        plaintext: String::from_utf8(res.plaintext).map_err(err)?,
    })
}

/// Расшифровать сообщение существующей сессией (type 0 prekey ИЛИ 1 normal —
/// после установки сессии пир может ещё слать prekey, пока не увидит наш ответ).
#[uniffi::export]
pub fn olm_decrypt(
    session_pickle: String,
    message_type: u32,
    body_b64: String,
) -> Result<OlmDecrypted, CoreError> {
    let mut session = session_from(&session_pickle)?;
    let msg = OlmMessage::from_parts(message_type as usize, &decode_b64(&body_b64)?).map_err(err)?;
    let pt = session.decrypt(&msg).map_err(err)?;
    Ok(OlmDecrypted {
        session_pickle: session_to(&session)?,
        plaintext: String::from_utf8(pt).map_err(err)?,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    // Полный обмен: X3DH-установка сессии по prekey + ратчет в обе стороны.
    #[test]
    fn ratchet_two_way() {
        let bob_acc0 = olm_account_new().unwrap();
        let bob_pub = olm_account_generate_otks(bob_acc0, 3).unwrap();
        let bob_id = bob_pub.identity_key_b64.clone();
        let otks: serde_json::Value = serde_json::from_str(&bob_pub.one_time_keys_json).unwrap();
        let bob_otk = otks.as_object().unwrap().values().next().unwrap().as_str().unwrap().to_string();

        let alice_acc = olm_account_new().unwrap();
        let alice_id = olm_account_identity(alice_acc.clone()).unwrap();

        // Алиса → Боб (prekey).
        let a_sess = olm_create_outbound(alice_acc, bob_id, bob_otk).unwrap();
        let m1 = olm_encrypt(a_sess.clone(), "привет боб".into()).unwrap();
        assert_eq!(m1.message_type, 0);

        let inb = olm_create_inbound(bob_pub.account_pickle, alice_id, m1.body_b64).unwrap();
        assert_eq!(inb.plaintext, "привет боб");

        // Боб → Алиса (normal), ратчет продвинулся.
        let m2 = olm_encrypt(inb.session_pickle, "привет алиса".into()).unwrap();
        assert_eq!(m2.message_type, 1);
        let dec = olm_decrypt(a_sess, m2.message_type, m2.body_b64).unwrap();
        assert_eq!(dec.plaintext, "привет алиса");
    }
}
