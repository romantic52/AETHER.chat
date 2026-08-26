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
/// Канон подписи записи устройства мастер-ключом аккаунта (cross-signing, P8).
pub const DEVICE_SIG_VERSION: &str = "AETHER-DEVSIG-1";
/// Домен вывода мастер-ключа из приватного ключа аккаунта.
const MASTER_DERIVE_DOMAIN: &str = "AETHER-MASTER-1";
/// Домен вывода ключа резервной копии истории (P9). Отдельный домен —
/// компрометация бэкап-ключа не должна давать подписи устройств, и наоборот.
const BACKUP_DERIVE_DOMAIN: &str = "AETHER-BACKUP-1";

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

/// Опубликованный fallback-ключ (P10 / SEC MED-3): «последний рубеж» на случай,
/// когда одноразовые ключи на сервере кончились. В отличие от OTK переиспользуем,
/// поэтому даёт более слабую forward secrecy — но альтернатива хуже: без него
/// исчерпание OTK (случайное или намеренное) полностью глушит переписку.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FallbackPublish {
    pub account_pickle: String,
    pub identity_key_b64: String,
    pub key_id: String,
    pub key_b64: String,
    /// Подпись каноном `AETHER-OTK-1` — тем же, что у обычных OTK, поэтому
    /// получатель проверяет бандл прежним `verify_prekey_bundle`.
    pub sig_b64: String,
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

/// Сгенерировать и подписать fallback-ключ (SEC MED-3). Канон подписи — тот же
/// `AETHER-OTK-1`, что у одноразовых: для получателя fallback неотличим от OTK,
/// и проверка бандла остаётся одной кодовой веткой.
///
/// `mark_keys_as_published` здесь НЕ вызывается намеренно: он помечает
/// опубликованными заодно и одноразовые ключи, поэтому вызов до отправки OTK
/// на сервер потерял бы их безвозвратно. Свежесгенерированный fallback и так
/// лежит в неопубликованных — читаем его сразу.
///
/// Аккаунт хранит две приватные части подряд идущих fallback-ключей, поэтому
/// ротация не рвёт сессии по сообщениям, застрявшим в пути на старом ключе.
pub fn account_generate_fallback_signed(
    account_pickle: &str,
    user_id: &str,
    device_id: &str,
) -> Result<FallbackPublish> {
    let mut account = account_from(account_pickle)?;
    account.generate_fallback_key();
    let curve_b64 = account.curve25519_key().to_base64();
    let (key_id, key) = account
        .fallback_key()
        .into_iter()
        .next()
        .ok_or_else(|| err("fallback-ключ не сгенерировался"))?;
    let key_id = key_id.to_base64();
    let key_b64 = key.to_base64();
    let sig_b64 = account
        .sign(otk_canon(user_id, device_id, &curve_b64, &key_id, &key_b64).as_bytes())
        .to_base64();
    Ok(FallbackPublish {
        identity_key_b64: curve_b64,
        key_id,
        key_b64,
        sig_b64,
        account_pickle: account_to(&account)?,
    })
}

fn ed25519_verify(ed_b64: &str, message: &str, sig_b64: &str, what: &str) -> Result<()> {
    let ed = Ed25519PublicKey::from_base64(ed_b64).map_err(err)?;
    let sig = Ed25519Signature::from_base64(sig_b64).map_err(err)?;
    ed.verify(message.as_bytes(), &sig)
        .map_err(|_| format!("olm: подпись {what} не сошлась — возможна подмена ключей сервером"))
}

/// Проверка подписи Ed25519 общего назначения.
///
/// Нужна вебу: он проверяет подпись документа /server/info, а браузерная
/// криптография Ed25519 поддерживается ещё не везде. Примитив вынесен сюда,
/// чтобы не заводить в вебе вторую реализацию — формат ключа и подписи тот
/// же b64url, что во всём проекте.
pub fn verify_ed25519_signature(public_key_b64: &str, message: &str, sig_b64: &str) -> Result<()> {
    // vodozemac разбирает СТАНДАРТНЫЙ base64 (соглашение Matrix), а весь
    // остальной проект — сервер, ядро, клиенты — пишет url-safe. Приводим здесь,
    // чтобы вызывающему не приходилось знать про это расхождение. Для
    // стандартного base64 замена ничего не меняет: символов - и _ в нём нет.
    let key = public_key_b64.replace('-', "+").replace('_', "/");
    let sig = sig_b64.replace('-', "+").replace('_', "/");
    ed25519_verify(&key, message, &sig, "документа")
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

// ---- Мастер-ключ аккаунта: cross-signing устройств (P8) ----
//
// Подписи prekey-бандла (выше) доказывают лишь самосогласованность записи: их
// проверяет тот же ed25519, что лежит в самой записи. Поэтому сервер мог добавить
// пиру ФАНТОМНОЕ устройство с самоподписанным бандлом — для нового device_id TOFU
// всегда даёт «первый контакт», и отправитель молча слал бы копию серверу.
//
// Мастер-ключ выводится из приватного ключа аккаунта (он уже есть на каждом
// устройстве после логина: восстанавливается из encrypted_private_key_b64), поэтому
// не требует нового хранилища и работает для существующих аккаунтов. Пиры пинят
// МАСТЕР — добавление устройства владельцем проверяется подписью, а не доверием.

/// 32 байта, выведенные из приватного ключа аккаунта с разделением доменов.
fn derive_from_account(account_secret_b64: &str, domain: &str) -> Result<[u8; 32]> {
    use sha2::{Digest, Sha512};
    let secret = decode_b64(account_secret_b64)?;
    if secret.len() != 32 {
        return Err(err("приватный ключ аккаунта должен быть 32 байта"));
    }
    let mut hasher = Sha512::new();
    hasher.update(domain.as_bytes());
    hasher.update(b"|");
    hasher.update(&secret);
    let digest = hasher.finalize();
    let mut out = [0u8; 32];
    out.copy_from_slice(&digest[..32]);
    Ok(out)
}

fn master_signing_key(account_secret_b64: &str) -> Result<ed25519_dalek::SigningKey> {
    Ok(ed25519_dalek::SigningKey::from_bytes(&derive_from_account(
        account_secret_b64,
        MASTER_DERIVE_DOMAIN,
    )?))
}

/// Ключ шифрования резервной копии истории (AES-256-GCM). Выводится из
/// приватного ключа аккаунта, поэтому доступен на любом устройстве сразу после
/// входа по паролю, а сервер вывести его не может.
pub fn backup_key(account_secret_b64: &str) -> Result<String> {
    Ok(URL_SAFE_NO_PAD.encode(derive_from_account(
        account_secret_b64,
        BACKUP_DERIVE_DOMAIN,
    )?))
}

fn device_canon(user_id: &str, device_id: &str, curve_b64: &str, ed_b64: &str) -> String {
    format!(
        "{DEVICE_SIG_VERSION}|{}|{}|{}|{}",
        user_id.to_lowercase(),
        device_id,
        curve_b64,
        ed_b64
    )
}

/// Префикс QR-сверки. Версия «2» — та же, что у отпечатка `AetherSafety#2`:
/// сверяется МАСТЕР-ключ аккаунта, которым подписаны все устройства пира.
/// Версия‑1 (box-ключи) в QR не поддерживается намеренно: она не аутентифицирует
/// Double Ratchet, а QR — это как раз тот канал, где хочется сильную гарантию.
const VERIFY_QR_PREFIX: &str = "aether:verify?v=2";

/// Разобранная QR-метка сверки.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VerifyQr {
    pub user_id: String,
    pub master_key_b64: String,
}

/// Проверить, что мастер-ключ — валидная 32-байтная точка ed25519, пригодная как
/// корень доверия. Те же требования, что в `verify_device`: иначе QR мог бы
/// «подтвердить» ключ, который проверка подписи устройства потом отвергнет.
fn checked_master(master_key_b64: &str) -> Result<()> {
    let bytes: [u8; 32] = decode_b64(master_key_b64)?
        .try_into()
        .map_err(|_| err("мастер-ключ: ожидалось 32 байта"))?;
    let key = ed25519_dalek::VerifyingKey::from_bytes(&bytes).map_err(err)?;
    if key.is_weak() {
        return Err(err("мастер-ключ малого порядка — недопустим как корень доверия"));
    }
    Ok(())
}

/// Юзернеймы ограничены сервером до `[A-Za-z0-9_]`, поэтому в QR они уезжают
/// как есть — без percent-кодирования. Проверяем это здесь: символ `&` или `=`
/// в имени позволил бы подсунуть лишний параметр и сбить разбор.
fn checked_user_id(user_id: &str) -> Result<String> {
    let id = user_id.trim().to_lowercase();
    if id.is_empty() || id.len() > 64 || !id.chars().all(|c| c.is_ascii_alphanumeric() || c == '_') {
        return Err(err("недопустимый идентификатор пользователя в QR"));
    }
    Ok(id)
}

/// Собрать содержимое QR-метки для сверки ключей.
pub fn verify_qr_build(user_id: &str, master_key_b64: &str) -> Result<String> {
    checked_master(master_key_b64)?;
    Ok(format!(
        "{VERIFY_QR_PREFIX}&u={}&m={}",
        checked_user_id(user_id)?,
        master_key_b64
    ))
}

/// Разобрать отсканированную QR-метку. Строгий разбор: чужие QR-коды (ссылки,
/// визитки, метки другой версии) должны отваливаться, а не толковаться вольно.
pub fn verify_qr_parse(text: &str) -> Result<VerifyQr> {
    let rest = text
        .trim()
        .strip_prefix(VERIFY_QR_PREFIX)
        .ok_or_else(|| err("это не QR-код сверки Æther"))?;
    let (mut user_id, mut master_key_b64) = (None, None);
    for part in rest.split('&').filter(|p| !p.is_empty()) {
        let (name, value) = part.split_once('=').ok_or_else(|| err("испорченный QR-код"))?;
        match name {
            // Повтор параметра отвергаем: иначе два `m=` дали бы разным
            // реализациям разный ответ, а сверка ключей — не то место для этого.
            "u" if user_id.is_none() => user_id = Some(checked_user_id(value)?),
            "m" if master_key_b64.is_none() => {
                checked_master(value)?;
                master_key_b64 = Some(value.to_owned());
            }
            _ => return Err(err("испорченный QR-код")),
        }
    }
    Ok(VerifyQr {
        user_id: user_id.ok_or_else(|| err("в QR-коде нет пользователя"))?,
        master_key_b64: master_key_b64.ok_or_else(|| err("в QR-коде нет мастер-ключа"))?,
    })
}

/// Публичный мастер-ключ аккаунта (его пинят собеседники).
pub fn master_public(account_secret_b64: &str) -> Result<String> {
    let key = master_signing_key(account_secret_b64)?;
    Ok(URL_SAFE_NO_PAD.encode(key.verifying_key().to_bytes()))
}

/// Подписать запись своего устройства мастер-ключом аккаунта.
pub fn sign_device(
    account_secret_b64: &str,
    user_id: &str,
    device_id: &str,
    curve_b64: &str,
    ed_b64: &str,
) -> Result<String> {
    use ed25519_dalek::ed25519::signature::Signer as _;
    let key = master_signing_key(account_secret_b64)?;
    let sig = key.sign(device_canon(user_id, device_id, curve_b64, ed_b64).as_bytes());
    Ok(URL_SAFE_NO_PAD.encode(sig.to_bytes()))
}

/// Проверить, что устройство пира действительно принадлежит его аккаунту.
pub fn verify_device(
    master_key_b64: &str,
    user_id: &str,
    device_id: &str,
    curve_b64: &str,
    ed_b64: &str,
    device_sig_b64: &str,
) -> Result<()> {
    let master_bytes: [u8; 32] = decode_b64(master_key_b64)?
        .try_into()
        .map_err(|_| err("мастер-ключ: ожидалось 32 байта"))?;
    let sig_bytes: [u8; 64] = decode_b64(device_sig_b64)?
        .try_into()
        .map_err(|_| err("подпись устройства: ожидалось 64 байта"))?;
    let master = ed25519_dalek::VerifyingKey::from_bytes(&master_bytes).map_err(err)?;
    // Ключи малого порядка отвергаем явно: для них подпись куётся без знания
    // секрета. libsodium на сервере так и делает — расхождение реализаций
    // означало бы, что клиент примет корень доверия, который сервер не принял.
    if master.is_weak() {
        return Err(err("мастер-ключ малого порядка — недопустим как корень доверия"));
    }
    master
        // verify_strict (а не verify): отвергает неканонические кодировки точек.
        .verify_strict(
            device_canon(user_id, device_id, curve_b64, ed_b64).as_bytes(),
            &ed25519_dalek::Signature::from_bytes(&sig_bytes),
        )
        .map_err(|_| {
            "olm: устройство не подписано мастер-ключом аккаунта — возможна подсадка устройства сервером"
                .to_owned()
        })
}

/// Канонизировать base64-ключ: decode_b64 намеренно толерантен (принимает оба
/// алфавита и padding), поэтому один и тот же ключ приходит в разных формах.
/// Пины и отпечатки сравниваются как СТРОКИ — без нормализации сервер мог бы
/// переписать кодировку и «сменить» ключ, не сломав ни одной подписи.
pub fn canonical_key_b64(key_b64: &str) -> Result<String> {
    Ok(URL_SAFE_NO_PAD.encode(decode_b64(key_b64)?))
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

/// Идентификатор сессии — ключ строки в локальном хранилище сессий (P10).
pub fn session_id(session_pickle: &str) -> Result<String> {
    Ok(session_from(session_pickle)?.session_id())
}

/// Идентификатор сессии, которую ЗАВЁЛ БЫ входящий prekey-конверт.
///
/// Совпадение с `session_id` уже имеющейся сессии означает, что конверт
/// относится к ней, а не открывает новую: пир просто ещё не получил наш ответ и
/// продолжает слать prekey. Без этой проверки каждый такой конверт создавал бы
/// параллельную сессию и жёг одноразовый ключ (при исчерпании OTK — тем более
/// критично, см. fallback-ключ).
pub fn prekey_session_id(body_b64: &str) -> Result<String> {
    let message = OlmMessage::from_parts(0, &decode_b64(body_b64)?).map_err(err)?;
    match message {
        OlmMessage::PreKey(message) => Ok(message.session_id()),
        OlmMessage::Normal(_) => Err(err("ожидался prekey-конверт")),
    }
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
    fn backup_key_is_deterministic_and_domain_separated() {
        let secret = URL_SAFE_NO_PAD.encode([7u8; 32]);
        let other = URL_SAFE_NO_PAD.encode([8u8; 32]);
        let key = backup_key(&secret).unwrap();
        assert_eq!(backup_key(&secret).unwrap(), key, "вывод детерминирован");
        assert_ne!(backup_key(&other).unwrap(), key, "разные аккаунты — разные ключи");
        // Разделение доменов: бэкап-ключ не совпадает с мастер-ключом.
        assert_ne!(key, master_public(&secret).unwrap());
        assert_eq!(decode_b64(&key).unwrap().len(), 32);
    }

    #[test]
    fn master_cross_signing_binds_devices_to_account() {
        // Приватник аккаунта (в проде — тот же box-ключ, что уже есть на устройствах).
        let bob_secret = URL_SAFE_NO_PAD.encode([7u8; 32]);
        let mallory_secret = URL_SAFE_NO_PAD.encode([9u8; 32]);
        let master = master_public(&bob_secret).unwrap();
        assert_eq!(master_public(&bob_secret).unwrap(), master, "вывод детерминирован");
        assert_ne!(master_public(&mallory_secret).unwrap(), master);

        let device = account_generate_otks_signed(&account_new().unwrap(), 1, "Bob", "ios-1").unwrap();
        let sig = sign_device(&bob_secret, "Bob", "ios-1", &device.identity_key_b64,
                              &device.ed25519_key_b64).unwrap();
        // Честное устройство проходит; user_id регистронезависим.
        verify_device(&master, "bob", "ios-1", &device.identity_key_b64,
                      &device.ed25519_key_b64, &sig).unwrap();

        // Ключевой сценарий: сервер подсаживает своё устройство с полностью
        // самосогласованным (самоподписанным) бандлом — мастер его не подтверждает.
        let evil = account_generate_otks_signed(&account_new().unwrap(), 1, "bob", "evil-1").unwrap();
        let evil_self_sig = sign_device(&mallory_secret, "bob", "evil-1", &evil.identity_key_b64,
                                        &evil.ed25519_key_b64).unwrap();
        assert!(verify_prekey_bundle_ok(&evil), "бандл самосогласован (в этом и была дыра)");
        assert!(verify_device(&master, "bob", "evil-1", &evil.identity_key_b64,
                              &evil.ed25519_key_b64, &evil_self_sig).is_err());

        // Подмена любого поля канона ловится.
        assert!(verify_device(&master, "bob", "ios-2", &device.identity_key_b64,
                              &device.ed25519_key_b64, &sig).is_err());
        assert!(verify_device(&master, "alice", "ios-1", &device.identity_key_b64,
                              &device.ed25519_key_b64, &sig).is_err());
        assert!(verify_device(&master, "bob", "ios-1", &evil.identity_key_b64,
                              &device.ed25519_key_b64, &sig).is_err());
        assert!(verify_device(&master, "bob", "ios-1", &device.identity_key_b64,
                              &evil.ed25519_key_b64, &sig).is_err());
    }

    /// Самосогласованность бандла (то, что проверяет P7) — вспомогательное для теста выше.
    fn verify_prekey_bundle_ok(publish: &PublishSigned) -> bool {
        let otks: serde_json::Value = serde_json::from_str(&publish.one_time_keys_json).unwrap();
        let sigs: serde_json::Value = serde_json::from_str(&publish.otk_signatures_json).unwrap();
        let (id, key) = otks.as_object().unwrap().iter().next().unwrap();
        verify_prekey_bundle(
            "bob", if publish.one_time_keys_json.is_empty() { "" } else { "evil-1" },
            &publish.identity_key_b64, &publish.ed25519_key_b64, &publish.identity_sig_b64,
            id, key.as_str().unwrap(), sigs[id].as_str().unwrap(),
        )
        .is_ok()
    }

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

    /// QR-сверка: метка собирается из мастер-ключа и разбирается обратно, а чужие
    /// и подпорченные коды отвергаются. Разбор строгий намеренно — это канал, по
    /// которому пользователь ПОДТВЕРЖДАЕТ корень доверия.
    #[test]
    fn verify_qr_roundtrip_and_strict_parsing() {
        let secret = URL_SAFE_NO_PAD.encode([7u8; 32]);
        let master = master_public(&secret).unwrap();
        let qr = verify_qr_build("Bob", &master).unwrap();
        assert!(qr.starts_with("aether:verify?v=2"));

        let parsed = verify_qr_parse(&qr).unwrap();
        assert_eq!(parsed.user_id, "bob", "имя нормализовано в нижний регистр");
        assert_eq!(parsed.master_key_b64, master);
        // Пробелы по краям (сканеры их иногда добавляют) не мешают.
        assert_eq!(verify_qr_parse(&format!("  {qr}  ")).unwrap().master_key_b64, master);

        // Чужие QR и другая версия канона.
        for bad in ["https://example.com", "aether:verify?v=1&u=bob", "", "aether:verify"] {
            assert!(verify_qr_parse(bad).is_err(), "принят чужой QR: {bad:?}");
        }
        // Мусор в параметрах, лишние и повторяющиеся поля.
        for bad in [
            format!("aether:verify?v=2&u=bob&m={master}&x=1"),
            format!("aether:verify?v=2&u=bob&m={master}&m={master}"),
            format!("aether:verify?v=2&m={master}"),
            "aether:verify?v=2&u=bob".to_owned(),
            format!("aether:verify?v=2&u=bo b&m={master}"),
            "aether:verify?v=2&u=bob&m=не-ключ".to_owned(),
        ] {
            assert!(verify_qr_parse(&bad).is_err(), "принят испорченный QR: {bad:?}");
        }

        // Мастер малого порядка не должен ни собираться в QR, ни разбираться:
        // для таких ключей подпись куётся без знания секрета.
        let weak = URL_SAFE_NO_PAD.encode([0u8; 32]);
        assert!(verify_qr_build("bob", &weak).is_err());
        assert!(verify_qr_parse(&format!("aether:verify?v=2&u=bob&m={weak}")).is_err());
        // Имя с разделителем не должно проскакивать в метку.
        assert!(verify_qr_build("bo&b", &master).is_err());
    }

    /// SEC MED-3: fallback-ключ подписан тем же каноном, что OTK, и годен для X3DH
    /// повторно — именно этим он и спасает переписку при исчерпании одноразовых.
    #[test]
    fn fallback_key_is_signed_and_reusable() {
        let bob = account_generate_otks_signed(&account_new().unwrap(), 1, "Bob", "ios-1").unwrap();
        let fb = account_generate_fallback_signed(&bob.account_pickle, "Bob", "ios-1").unwrap();
        assert_eq!(fb.identity_key_b64, bob.identity_key_b64, "identity не меняется");

        // Получатель проверяет fallback обычной проверкой бандла.
        verify_prekey_bundle(
            "bob", "ios-1", &fb.identity_key_b64, &bob.ed25519_key_b64,
            &bob.identity_sig_b64, &fb.key_id, &fb.key_b64, &fb.sig_b64,
        )
        .unwrap();
        // Подмена самого ключа при валидной подписи от другого id — ловится.
        assert!(verify_prekey_bundle(
            "bob", "ios-1", &fb.identity_key_b64, &bob.ed25519_key_b64,
            &bob.identity_sig_b64, &fb.key_id, &bob.identity_key_b64, &fb.sig_b64,
        )
        .is_err());

        // Два РАЗНЫХ отправителя строят сессии на одном и том же fallback —
        // одноразовый ключ так бы не смог, в этом весь смысл.
        let mut account = fb.account_pickle.clone();
        for who in ["алиса", "карл"] {
            let sender = account_new().unwrap();
            let session = create_outbound(&sender, &fb.identity_key_b64, &fb.key_b64).unwrap();
            let msg = encrypt(&session, who).unwrap();
            let inbound =
                create_inbound(&account, &account_identity(&sender).unwrap(), &msg.body_b64).unwrap();
            assert_eq!(inbound.plaintext, who);
            account = inbound.account_pickle;
        }

        // Ротация: прошлый fallback остаётся расшифровываемым (аккаунт держит две
        // приватные части), иначе сообщения в пути терялись бы при каждой ротации.
        let rotated = account_generate_fallback_signed(&account, "Bob", "ios-1").unwrap();
        assert_ne!(rotated.key_b64, fb.key_b64, "ключ действительно сменился");
        let late = account_new().unwrap();
        let session = create_outbound(&late, &fb.identity_key_b64, &fb.key_b64).unwrap();
        let msg = encrypt(&session, "успел до ротации").unwrap();
        let inbound = create_inbound(&rotated.account_pickle, &account_identity(&late).unwrap(),
                                     &msg.body_b64).unwrap();
        assert_eq!(inbound.plaintext, "успел до ротации");
    }

    /// SEC MED-4: обе стороны начали переписку одновременно (glare). Раньше
    /// входящий prekey затирал единственную сессию — и сообщения, зашифрованные
    /// собеседником в исходящей, становились невскрываемыми навсегда.
    /// Мультисессии решают это: обе живут, каждая расшифровывает своё.
    #[test]
    fn simultaneous_initiation_keeps_both_sessions() {
        let alice_acc = account_new().unwrap();
        let alice_id = account_identity(&alice_acc).unwrap();
        let bob_pub = account_generate_otks(&account_new().unwrap(), 4).unwrap();
        let bob_id = bob_pub.identity_key_b64.clone();
        let mut bob_acc = bob_pub.account_pickle.clone();
        let mut otks = serde_json::from_str::<serde_json::Value>(&bob_pub.one_time_keys_json)
            .unwrap()
            .as_object()
            .unwrap()
            .values()
            .map(|v| v.as_str().unwrap().to_owned())
            .collect::<Vec<_>>();

        // Алиса завела исходящую сессию и отправила prekey.
        let alice_out = create_outbound(&alice_acc, &bob_id, &otks.pop().unwrap()).unwrap();
        let a1 = encrypt(&alice_out, "от алисы").unwrap();

        // Боб, не увидев его, завёл СВОЮ исходящую (нужен OTK алисы — берём у неё).
        let alice_pub = account_generate_otks(&alice_acc, 2).unwrap();
        let alice_acc = alice_pub.account_pickle.clone();
        let alice_otk = serde_json::from_str::<serde_json::Value>(&alice_pub.one_time_keys_json)
            .unwrap()
            .as_object()
            .unwrap()
            .values()
            .next()
            .unwrap()
            .as_str()
            .unwrap()
            .to_owned();
        let bob_out = create_outbound(&bob_acc, &alice_id, &alice_otk).unwrap();
        let b1 = encrypt(&bob_out, "от боба").unwrap();

        // Боб принимает конверт алисы — заводится ВТОРАЯ сессия у боба.
        let bob_in = create_inbound(&bob_acc, &alice_id, &a1.body_b64).unwrap();
        assert_eq!(bob_in.plaintext, "от алисы");
        bob_acc = bob_in.account_pickle;
        assert_ne!(session_id(&bob_in.session_pickle).unwrap(), session_id(&bob_out).unwrap());

        // Алиса принимает конверт боба — тоже вторая сессия.
        let alice_in = create_inbound(&alice_acc, &bob_id, &b1.body_b64).unwrap();
        assert_eq!(alice_in.plaintext, "от боба");
        assert_eq!(session_id(&alice_in.session_pickle).unwrap(), session_id(&bob_out).unwrap(),
                   "session_id одной сессии совпадает у обеих сторон");

        // Ключевое: алисина ИСХОДЯЩАЯ жива и продолжает работать. Именно её раньше
        // затирал входящий prekey, после чего чат вставал намертво.
        let a2 = encrypt(&a1.session_pickle, "второе от алисы").unwrap();
        let dec = decrypt(&bob_in.session_pickle, a2.message_type, &a2.body_b64).unwrap();
        assert_eq!(dec.plaintext, "второе от алисы");

        // Повторный prekey в ту же сессию распознаётся по session_id и НЕ должен
        // заводить новую (иначе каждый такой конверт жёг бы одноразовый ключ).
        assert_eq!(prekey_session_id(&a1.body_b64).unwrap(), session_id(&bob_in.session_pickle).unwrap());
        let a3 = encrypt(&a2.session_pickle, "третье от алисы").unwrap();
        assert_eq!(a3.message_type, 0, "пир ещё не ответил — всё ещё prekey");
        assert_eq!(prekey_session_id(&a3.body_b64).unwrap(), session_id(&dec.session_pickle).unwrap());
        assert_eq!(decrypt(&dec.session_pickle, a3.message_type, &a3.body_b64).unwrap().plaintext,
                   "третье от алисы");

        // normal-конверт не выдаёт себя за prekey.
        let reply = encrypt(&bob_in.session_pickle, "ответ").unwrap();
        assert_eq!(reply.message_type, 1);
        assert!(prekey_session_id(&reply.body_b64).is_err());
        let _ = bob_acc;
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

