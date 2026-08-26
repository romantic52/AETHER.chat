//! Криптопримитивы: crypto_box (Curve25519/XSalsa20-Poly1305, совместимо с tweetnacl),
//! AES-256-GCM, PBKDF2-бэкап приватного ключа. Всё b64 — url-safe без паддинга.

use crate::CoreError;
use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes256Gcm, Nonce};
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use crypto_box::aead::{AeadCore, OsRng};
use crypto_box::{PublicKey, SalsaBox, SecretKey};
use sha2::Sha256;

/// url-safe base64 без паддинга (канон протокола).
#[uniffi::export]
pub fn b64url_encode(data: Vec<u8>) -> String {
    URL_SAFE_NO_PAD.encode(data)
}

/// Принимает оба алфавита и опциональный паддинг (легаси-ключи на сервере бывают с `=`).
#[uniffi::export]
pub fn b64url_decode(s: String) -> Result<Vec<u8>, CoreError> {
    decode_b64(&s)
}

pub(crate) fn decode_b64(s: &str) -> Result<Vec<u8>, CoreError> {
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
    URL_SAFE_NO_PAD
        .decode(normalized.as_bytes())
        .map_err(|e| CoreError::BadInput { msg: format!("base64: {e}") })
}

fn key32(b64: &str, what: &str) -> Result<[u8; 32], CoreError> {
    let bytes = decode_b64(b64)?;
    bytes
        .try_into()
        .map_err(|_| CoreError::BadInput { msg: format!("{what}: ожидалось 32 байта") })
}

#[derive(uniffi::Record)]
pub struct Keypair {
    pub public_b64: String,
    pub private_b64: String,
}

/// Результат симметричного/асимметричного шифрования: nonce + шифротекст (tag в конце).
#[derive(uniffi::Record)]
pub struct Sealed {
    pub nonce_b64: String,
    pub ciphertext: Vec<u8>,
}

#[uniffi::export]
pub fn generate_keypair() -> Keypair {
    let sk = SecretKey::generate(&mut OsRng);
    Keypair {
        public_b64: URL_SAFE_NO_PAD.encode(sk.public_key().as_bytes()),
        private_b64: URL_SAFE_NO_PAD.encode(sk.to_bytes()),
    }
}

/// crypto_box: nonce 24б, XSalsa20-Poly1305 (tweetnacl box).
#[uniffi::export]
pub fn box_encrypt(
    plaintext: Vec<u8>,
    recipient_pub_b64: String,
    sender_priv_b64: String,
) -> Result<Sealed, CoreError> {
    let pk = PublicKey::from(key32(&recipient_pub_b64, "public key")?);
    let sk = SecretKey::from(key32(&sender_priv_b64, "private key")?);
    let sbox = SalsaBox::new(&pk, &sk);
    let nonce = SalsaBox::generate_nonce(&mut OsRng);
    let ct = sbox
        .encrypt(&nonce, plaintext.as_slice())
        .map_err(CoreError::crypto)?;
    Ok(Sealed {
        nonce_b64: URL_SAFE_NO_PAD.encode(nonce),
        ciphertext: ct,
    })
}

#[uniffi::export]
pub fn box_decrypt(
    nonce_b64: String,
    ciphertext: Vec<u8>,
    sender_pub_b64: String,
    recipient_priv_b64: String,
) -> Result<Vec<u8>, CoreError> {
    let pk = PublicKey::from(key32(&sender_pub_b64, "public key")?);
    let sk = SecretKey::from(key32(&recipient_priv_b64, "private key")?);
    let sbox = SalsaBox::new(&pk, &sk);
    let nonce_bytes = decode_b64(&nonce_b64)?;
    if nonce_bytes.len() != 24 {
        return Err(CoreError::bad("box nonce: ожидалось 24 байта"));
    }
    sbox.decrypt(nonce_bytes.as_slice().into(), ciphertext.as_slice())
        .map_err(|_| CoreError::Crypto { msg: "box_decrypt: не расшифровалось".into() })
}

/// AES-256-GCM, nonce 12б, tag 128 бит в конце шифротекста (WebCrypto/Android-совместимо).
#[uniffi::export]
pub fn aes_encrypt(key_b64: String, plaintext: Vec<u8>) -> Result<Sealed, CoreError> {
    let key = key32(&key_b64, "aes key")?;
    let cipher = Aes256Gcm::new(&key.into());
    let mut nonce = [0u8; 12];
    use rand_core::RngCore;
    OsRng.fill_bytes(&mut nonce);
    let ct = cipher
        .encrypt(Nonce::from_slice(&nonce), plaintext.as_slice())
        .map_err(CoreError::crypto)?;
    Ok(Sealed {
        nonce_b64: URL_SAFE_NO_PAD.encode(nonce),
        ciphertext: ct,
    })
}

#[uniffi::export]
pub fn aes_decrypt(
    key_b64: String,
    nonce_b64: String,
    ciphertext: Vec<u8>,
) -> Result<Vec<u8>, CoreError> {
    let key = key32(&key_b64, "aes key")?;
    let cipher = Aes256Gcm::new(&key.into());
    let nonce = decode_b64(&nonce_b64)?;
    if nonce.len() != 12 {
        return Err(CoreError::bad("aes nonce: ожидалось 12 байт"));
    }
    cipher
        .decrypt(Nonce::from_slice(&nonce), ciphertext.as_slice())
        .map_err(|_| CoreError::Crypto { msg: "aes_decrypt: не расшифровалось".into() })
}

/// Случайный симметричный ключ (32 байта) в b64url — для групп и медиа.
#[uniffi::export]
pub fn random_key_b64() -> String {
    use rand_core::RngCore;
    let mut key = [0u8; 32];
    OsRng.fill_bytes(&mut key);
    URL_SAFE_NO_PAD.encode(key)
}

fn backup_key(password: &str, salt: &[u8]) -> [u8; 32] {
    let mut key = [0u8; 32];
    pbkdf2::pbkdf2_hmac::<Sha256>(password.as_bytes(), salt, 100_000, &mut key);
    key
}

// --- Резервная копия приватного ключа -----------------------------------------
//
// Два поколения формата, и переход между ними ОБЯЗАН быть поэтапным, потому
// что этот блоб общий для iOS, Android и веба:
//
//   v1  `salt:iv:ct`                          PBKDF2-SHA256 100k + AES-GCM
//   v2  `v2:argon2id:m:t:p:salt:iv:ct`        Argon2id + AES-GCM
//
// Сейчас ядро ЧИТАЕТ оба формата, но ПИШЕТ только v1. Это шаг 1 перехода: он
// не даёт выигрыша сам по себе, зато после него обновлённые клиенты смогут
// открыть копию нового формата. Переключать запись можно лишь тогда, когда
// v2 научатся читать все клиенты, иначе аккаунт, созданный на одном
// устройстве, не откроется на другом — человек окажется заперт вне своей же
// переписки. Приложение Г в docs/TRANSPORT_LAYER_DESIGN.md.
//
// Параметры Argon2id лежат ВНУТРИ блоба: перенастройка стоимости не должна
// делать старые копии нечитаемыми.

const V2_PREFIX: &str = "v2:argon2id:";
/// 64 МиБ, 3 прохода — столько же, сколько на сервере.
const V2_MEMORY_KIB: u32 = 65_536;
const V2_TIME: u32 = 3;
const V2_LANES: u32 = 1;

fn argon2_key(password: &str, salt: &[u8], m: u32, t: u32, p: u32) -> Result<[u8; 32], CoreError> {
    use argon2::{Algorithm, Argon2, Params, Version};
    let params = Params::new(m, t, p, Some(32))
        .map_err(|e| CoreError::Crypto { msg: format!("параметры Argon2: {e}") })?;
    let argon = Argon2::new(Algorithm::Argon2id, Version::V0x13, params);
    let mut key = [0u8; 32];
    argon
        .hash_password_into(password.as_bytes(), salt, &mut key)
        .map_err(|e| CoreError::Crypto { msg: format!("Argon2: {e}") })?;
    Ok(key)
}

/// Бэкап приватного ключа: PBKDF2 100k + AES-GCM. Формат `salt:iv:ct` (b64url).
/// Шифруется b64url-СТРОКА ключа, не сырые байты (канон Android/web).
///
/// ПИШЕТ ФОРМАТ v1 НАМЕРЕННО — см. комментарий выше. Не переключать на v2, пока
/// Android и веб не научатся его читать.
#[uniffi::export]
pub fn encrypt_private_key(private_key_b64: String, password: String) -> Result<String, CoreError> {
    use rand_core::RngCore;
    let mut salt = [0u8; 16];
    OsRng.fill_bytes(&mut salt);
    let key = backup_key(&password, &salt);
    let sealed = aes_encrypt(URL_SAFE_NO_PAD.encode(key), private_key_b64.into_bytes())?;
    Ok(format!(
        "{}:{}:{}",
        URL_SAFE_NO_PAD.encode(salt),
        sealed.nonce_b64,
        URL_SAFE_NO_PAD.encode(sealed.ciphertext)
    ))
}

/// Копия в формате v2 (Argon2id). Пока НЕ используется при регистрации:
/// существует, чтобы шаг 3 перехода сводился к замене одного вызова, и чтобы
/// формат можно было проверить тестами уже сейчас.
#[uniffi::export]
pub fn encrypt_private_key_v2(private_key_b64: String, password: String) -> Result<String, CoreError> {
    use rand_core::RngCore;
    let mut salt = [0u8; 16];
    OsRng.fill_bytes(&mut salt);
    let key = argon2_key(&password, &salt, V2_MEMORY_KIB, V2_TIME, V2_LANES)?;
    let sealed = aes_encrypt(URL_SAFE_NO_PAD.encode(key), private_key_b64.into_bytes())?;
    Ok(format!(
        "{V2_PREFIX}{}:{}:{}:{}:{}",
        V2_MEMORY_KIB,
        V2_TIME,
        V2_LANES,
        URL_SAFE_NO_PAD.encode(salt),
        format_args!("{}:{}", sealed.nonce_b64, URL_SAFE_NO_PAD.encode(sealed.ciphertext))
    ))
}

/// Какого поколения резервная копия. Нужно интерфейсу, чтобы объяснить
/// человеку, почему старый клиент её не открыл.
#[uniffi::export]
pub fn private_key_backup_version(blob: String) -> u32 {
    if blob.starts_with(V2_PREFIX) { 2 } else { 1 }
}

#[uniffi::export]
pub fn decrypt_private_key(blob: String, password: String) -> Result<String, CoreError> {
    if let Some(rest) = blob.strip_prefix(V2_PREFIX) {
        return decrypt_v2(rest, &password);
    }
    let parts: Vec<&str> = blob.split(':').collect();
    if parts.len() != 3 {
        return Err(CoreError::bad("неверный формат зашифрованного ключа"));
    }
    let salt = decode_b64(parts[0])?;
    let key = backup_key(&password, &salt);
    let ct = decode_b64(parts[2])?;
    let pt = aes_decrypt(URL_SAFE_NO_PAD.encode(key), parts[1].to_string(), ct)
        .map_err(|_| CoreError::Crypto { msg: "неверный пароль".into() })?;
    String::from_utf8(pt).map_err(CoreError::crypto)
}

/// Разбор v2: m:t:p:salt:iv:ct. Параметры берутся из самого блоба, а не из
/// констант — иначе перенастройка стоимости сделала бы старые копии мусором.
fn decrypt_v2(rest: &str, password: &str) -> Result<String, CoreError> {
    let parts: Vec<&str> = rest.split(':').collect();
    if parts.len() != 6 {
        return Err(CoreError::bad("неверный формат зашифрованного ключа (v2)"));
    }
    let parse = |s: &str, what: &str| -> Result<u32, CoreError> {
        s.parse::<u32>().map_err(|_| CoreError::bad(format!("Argon2: неверный {what}")))
    };
    let m = parse(parts[0], "объём памяти")?;
    let t = parse(parts[1], "число проходов")?;
    let p = parse(parts[2], "число потоков")?;
    // Чужой блоб может требовать гигабайты памяти. Верхняя граница защищает от
    // отказа в обслуживании на собственном устройстве.
    if m > 1_048_576 || t > 16 || p > 16 {
        return Err(CoreError::bad("Argon2: недопустимые параметры"));
    }
    let salt = decode_b64(parts[3])?;
    let ct = decode_b64(parts[5])?;
    let key = argon2_key(password, &salt, m, t, p)?;
    let pt = aes_decrypt(URL_SAFE_NO_PAD.encode(key), parts[4].to_string(), ct)
        .map_err(|_| CoreError::Crypto { msg: "неверный пароль".into() })?;
    String::from_utf8(pt).map_err(CoreError::crypto)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn box_roundtrip() {
        let a = generate_keypair();
        let b = generate_keypair();
        let sealed = box_encrypt("привет".as_bytes().to_vec(), b.public_b64.clone(), a.private_b64.clone()).unwrap();
        let pt = box_decrypt(sealed.nonce_b64, sealed.ciphertext, a.public_b64, b.private_b64).unwrap();
        assert_eq!(pt, "привет".as_bytes());
    }

    #[test]
    fn aes_roundtrip() {
        let key = random_key_b64();
        let sealed = aes_encrypt(key.clone(), b"data".to_vec()).unwrap();
        assert_eq!(aes_decrypt(key, sealed.nonce_b64, sealed.ciphertext).unwrap(), b"data");
    }

    #[test]
    fn reads_both_backup_generations() {
        let kp = generate_keypair();
        let password = "dolgiy-parol-777".to_string();

        // v1 — то, что пишется сегодня и лежит у всех существующих аккаунтов.
        let v1 = encrypt_private_key(kp.private_b64.clone(), password.clone()).unwrap();
        assert_eq!(private_key_backup_version(v1.clone()), 1);
        assert_eq!(decrypt_private_key(v1.clone(), password.clone()).unwrap(), kp.private_b64);

        // v2 — Argon2id. Читается тем же вызовом, без флагов и настроек.
        let v2 = encrypt_private_key_v2(kp.private_b64.clone(), password.clone()).unwrap();
        assert_eq!(private_key_backup_version(v2.clone()), 2);
        assert!(v2.starts_with("v2:argon2id:"));
        assert_eq!(decrypt_private_key(v2.clone(), password.clone()).unwrap(), kp.private_b64);

        // Неверный пароль отвергается в обоих поколениях.
        assert!(decrypt_private_key(v1, "drugoi-parol-1".into()).is_err());
        assert!(decrypt_private_key(v2, "drugoi-parol-1".into()).is_err());
    }

    #[test]
    fn registration_still_writes_the_old_format() {
        // Ключевая проверка шага 1: пишем ПО-ПРЕЖНЕМУ v1, иначе аккаунт,
        // созданный обновлённым клиентом, не откроется на Android и в вебе.
        let kp = generate_keypair();
        let blob = encrypt_private_key(kp.private_b64, "dolgiy-parol-777".into()).unwrap();
        assert_eq!(private_key_backup_version(blob.clone()), 1);
        assert_eq!(blob.split(':').count(), 3, "старый формат — ровно три части");
    }

    #[test]
    fn v2_parameters_come_from_the_blob() {
        // Стоимость записана внутри копии: её перенастройка не должна делать
        // ранее созданные копии нечитаемыми.
        let kp = generate_keypair();
        let password = "dolgiy-parol-777".to_string();
        let blob = encrypt_private_key_v2(kp.private_b64.clone(), password.clone()).unwrap();
        let rest = blob.strip_prefix("v2:argon2id:").unwrap();
        let parts: Vec<&str> = rest.split(':').collect();
        assert_eq!(parts.len(), 6);
        assert_eq!(parts[0], "65536", "объём памяти записан в блоб");
        assert_eq!(parts[1], "3");

        // Подменяем параметры на другие — расшифровка обязана сломаться,
        // потому что ключ выведется иначе. Значит параметры действительно
        // читаются из блоба, а не берутся из констант.
        let tampered = format!("v2:argon2id:{}:{}:{}:{}:{}:{}",
                               32768, parts[1], parts[2], parts[3], parts[4], parts[5]);
        assert!(decrypt_private_key(tampered, password).is_err());
    }

    #[test]
    fn hostile_v2_blob_is_rejected() {
        let password = "dolgiy-parol-777".to_string();
        // Требование гигабайтов памяти — отказ в обслуживании собственного
        // устройства. Отвергаем до попытки выделить память.
        let greedy = "v2:argon2id:99999999:3:1:AAAAAAAAAAAAAAAAAAAAAA:AAAAAAAAAAAAAAAA:AAAA";
        assert!(decrypt_private_key(greedy.into(), password.clone()).is_err());
        // Неполный блоб.
        assert!(decrypt_private_key("v2:argon2id:65536:3:1".into(), password.clone()).is_err());
        // Мусор в числах.
        let bad = "v2:argon2id:x:3:1:AAAAAAAAAAAAAAAAAAAAAA:AAAAAAAAAAAAAAAA:AAAA";
        assert!(decrypt_private_key(bad.into(), password).is_err());
    }

    #[test]
    fn backup_roundtrip() {
        let kp = generate_keypair();
        let blob = encrypt_private_key(kp.private_b64.clone(), "test-passphrase".into()).unwrap();
        assert_eq!(blob.split(':').count(), 3);
        assert_eq!(decrypt_private_key(blob.clone(), "test-passphrase".into()).unwrap(), kp.private_b64);
        assert!(decrypt_private_key(blob, "wrong".into()).is_err());
    }

    /// Сверка с бэкапом, который выдал САМ СЕРВЕР: проверяет, что наш PBKDF2+AES
    /// разбирает чужой конверт, а не только свой собственный — своё умеет
    /// backup_roundtrip выше. Вектор в код не кладётся: он содержит рабочий
    /// приватный ключ, и в репозитории это равносильно его публикации. Задаётся
    /// снаружи, без переменных тест пропускается:
    ///   AETHER_BACKUP_BLOB=... AETHER_BACKUP_PASS=... AETHER_BACKUP_PUB=... cargo test
    #[test]
    fn backup_live_vector() {
        let (blob, pass, expect_pub) = match (
            std::env::var("AETHER_BACKUP_BLOB"),
            std::env::var("AETHER_BACKUP_PASS"),
            std::env::var("AETHER_BACKUP_PUB"),
        ) {
            (Ok(b), Ok(p), Ok(k)) => (b, p, k),
            _ => return,
        };
        let priv_b64 = decrypt_private_key(blob, pass).unwrap();
        let sk = SecretKey::from(key32(&priv_b64, "sk").unwrap());
        let pub_b64 = URL_SAFE_NO_PAD.encode(sk.public_key().as_bytes());
        assert_eq!(pub_b64, expect_pub);
    }

    #[test]
    fn b64_accepts_both_alphabets() {
        assert_eq!(
            decode_b64("ZR_p1Yykiocxu87StCf2J2U7QmtgOlepAiVXHIRv2Uo=").unwrap().len(),
            32
        );
        assert_eq!(decode_b64("+/8").unwrap(), decode_b64("-_8").unwrap());
    }
}
