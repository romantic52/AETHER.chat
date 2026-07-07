//! Криптография ядра. Форматы строго совместимы с web/app.js и
//! android E2ECrypto.kt (см. WIRE_PROTOCOL.md):
//!  - личные сообщения: NaCl crypto_box (X25519 + XSalsa20-Poly1305);
//!  - группы/медиа: AES-256-GCM (IV 12б, тег 128б в конце);
//!  - бэкап ключа: PBKDF2-HMAC-SHA256(100000) + AES-256-GCM, "salt:iv:ct";
//!  - все base64 — url-safe.

use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes256Gcm, Key, Nonce as AesNonce};
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use crypto_box::aead::Aead as BoxAead;
use crypto_box::{PublicKey, SalsaBox, SecretKey};
use pbkdf2::pbkdf2_hmac;
use rand_core::{OsRng, RngCore};
use sha2::Sha256;

const PBKDF2_ITERS: u32 = 100_000;

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Error)]
pub enum CryptoError {
    BadBase64,
    BadKeyLength,
    BadFormat,
    DecryptFailed,
    EncryptFailed,
}

impl std::fmt::Display for CryptoError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{:?}", self)
    }
}
impl std::error::Error for CryptoError {}

/// Пара ключей (base64, url-safe). private/public по 32 байта.
#[derive(Debug, Clone, uniffi::Record)]
pub struct KeyPair {
    pub private_b64: String,
    pub public_b64: String,
}

/// Конверт личного сообщения (формат wire).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Envelope {
    pub sender_pubkey_b64: String,
    pub nonce_b64: String,
    pub ciphertext_b64: String,
}

// --- base64 url-safe ---
fn b64e(data: &[u8]) -> String {
    URL_SAFE_NO_PAD.encode(data)
}
// Терпим к паддингу (Android отдаёт с '=', web — без).
fn b64d(s: &str) -> Result<Vec<u8>, CryptoError> {
    URL_SAFE_NO_PAD
        .decode(s.trim_end_matches('='))
        .map_err(|_| CryptoError::BadBase64)
}

fn arr32(v: &[u8]) -> Result<[u8; 32], CryptoError> {
    v.try_into().map_err(|_| CryptoError::BadKeyLength)
}

// ---------------------------------------------------------------------------
// crypto_box (личные сообщения)
// ---------------------------------------------------------------------------

pub fn generate_keypair() -> KeyPair {
    let sk = SecretKey::generate(&mut OsRng);
    let pk = sk.public_key();
    KeyPair {
        private_b64: b64e(&sk.to_bytes()),
        public_b64: b64e(pk.as_bytes()),
    }
}

pub fn box_encrypt(
    plaintext: &str,
    my_private_b64: &str,
    their_public_b64: &str,
) -> Result<Envelope, CryptoError> {
    let sk = SecretKey::from(arr32(&b64d(my_private_b64)?)?);
    let pk = PublicKey::from(arr32(&b64d(their_public_b64)?)?);
    let salsa = SalsaBox::new(&pk, &sk);

    let mut nonce_bytes = [0u8; 24];
    OsRng.fill_bytes(&mut nonce_bytes);
    let nonce = crypto_box::Nonce::from_slice(&nonce_bytes);

    let ct = BoxAead::encrypt(&salsa, nonce, plaintext.as_bytes())
        .map_err(|_| CryptoError::EncryptFailed)?;

    Ok(Envelope {
        sender_pubkey_b64: b64e(sk.public_key().as_bytes()),
        nonce_b64: b64e(&nonce_bytes),
        ciphertext_b64: b64e(&ct),
    })
}

pub fn box_decrypt(env: &Envelope, my_private_b64: &str) -> Result<String, CryptoError> {
    let sk = SecretKey::from(arr32(&b64d(my_private_b64)?)?);
    let sender_pk = PublicKey::from(arr32(&b64d(&env.sender_pubkey_b64)?)?);
    let salsa = SalsaBox::new(&sender_pk, &sk);

    let nonce_bytes = b64d(&env.nonce_b64)?;
    if nonce_bytes.len() != 24 {
        return Err(CryptoError::BadFormat);
    }
    let nonce = crypto_box::Nonce::from_slice(&nonce_bytes);
    let ct = b64d(&env.ciphertext_b64)?;

    let pt = BoxAead::decrypt(&salsa, nonce, ct.as_ref()).map_err(|_| CryptoError::DecryptFailed)?;
    String::from_utf8(pt).map_err(|_| CryptoError::BadFormat)
}

// ---------------------------------------------------------------------------
// AES-256-GCM (группы, медиа). Возвращает (nonce_b64, ciphertext_b64).
// ---------------------------------------------------------------------------

pub fn aes_encrypt(plaintext: &[u8], key_b64: &str) -> Result<(String, String), CryptoError> {
    let key_bytes = arr32(&b64d(key_b64)?)?;
    let cipher = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(&key_bytes));

    let mut iv = [0u8; 12];
    OsRng.fill_bytes(&mut iv);
    let nonce = AesNonce::from_slice(&iv);

    let ct = cipher
        .encrypt(nonce, plaintext)
        .map_err(|_| CryptoError::EncryptFailed)?;
    Ok((b64e(&iv), b64e(&ct)))
}

pub fn aes_decrypt(
    key_b64: &str,
    nonce_b64: &str,
    ciphertext_b64: &str,
) -> Result<Vec<u8>, CryptoError> {
    let key_bytes = arr32(&b64d(key_b64)?)?;
    let cipher = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(&key_bytes));

    let iv = b64d(nonce_b64)?;
    if iv.len() != 12 {
        return Err(CryptoError::BadFormat);
    }
    let nonce = AesNonce::from_slice(&iv);
    let ct = b64d(ciphertext_b64)?;
    cipher
        .decrypt(nonce, ct.as_ref())
        .map_err(|_| CryptoError::DecryptFailed)
}

// ---------------------------------------------------------------------------
// Бэкап приватного ключа: PBKDF2 + AES-GCM, формат "salt:iv:ct".
// Шифруется base64-СТРОКА приватного ключа (как в Android/web).
// ---------------------------------------------------------------------------

pub fn encrypt_private_key(private_key_b64: &str, password: &str) -> Result<String, CryptoError> {
    let mut salt = [0u8; 16];
    let mut iv = [0u8; 12];
    OsRng.fill_bytes(&mut salt);
    OsRng.fill_bytes(&mut iv);

    let mut key = [0u8; 32];
    pbkdf2_hmac::<Sha256>(password.as_bytes(), &salt, PBKDF2_ITERS, &mut key);

    let cipher = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(&key));
    let nonce = AesNonce::from_slice(&iv);
    let ct = cipher
        .encrypt(nonce, private_key_b64.as_bytes())
        .map_err(|_| CryptoError::EncryptFailed)?;

    Ok(format!("{}:{}:{}", b64e(&salt), b64e(&iv), b64e(&ct)))
}

pub fn decrypt_private_key(blob: &str, password: &str) -> Result<String, CryptoError> {
    let parts: Vec<&str> = blob.split(':').collect();
    if parts.len() != 3 {
        return Err(CryptoError::BadFormat);
    }
    let salt = b64d(parts[0])?;
    let iv = b64d(parts[1])?;
    let ct = b64d(parts[2])?;
    if iv.len() != 12 {
        return Err(CryptoError::BadFormat);
    }

    let mut key = [0u8; 32];
    pbkdf2_hmac::<Sha256>(password.as_bytes(), &salt, PBKDF2_ITERS, &mut key);

    let cipher = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(&key));
    let nonce = AesNonce::from_slice(&iv);
    let pt = cipher
        .decrypt(nonce, ct.as_ref())
        .map_err(|_| CryptoError::DecryptFailed)?;
    String::from_utf8(pt).map_err(|_| CryptoError::BadFormat)
}

// ===========================================================================
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keypair_roundtrip_box() {
        let a = generate_keypair();
        let b = generate_keypair();
        let env = box_encrypt("привет 🔐", &a.private_b64, &b.public_b64).unwrap();
        // получатель b расшифровывает по sender_pubkey из конверта
        let pt = box_decrypt(&env, &b.private_b64).unwrap();
        assert_eq!(pt, "привет 🔐");
    }

    #[test]
    fn box_wrong_recipient_fails() {
        let a = generate_keypair();
        let b = generate_keypair();
        let c = generate_keypair();
        let env = box_encrypt("secret", &a.private_b64, &b.public_b64).unwrap();
        assert!(box_decrypt(&env, &c.private_b64).is_err());
    }

    #[test]
    fn aes_roundtrip() {
        let key = b64e(&[7u8; 32]);
        let (nonce_b64, ct_b64) = aes_encrypt(b"group message", &key).unwrap();
        let pt = aes_decrypt(&key, &nonce_b64, &ct_b64).unwrap();
        assert_eq!(pt, b"group message");
    }

    #[test]
    fn private_key_backup_roundtrip() {
        let kp = generate_keypair();
        let blob = encrypt_private_key(&kp.private_b64, "hunter2pass").unwrap();
        assert_eq!(blob.split(':').count(), 3);
        let restored = decrypt_private_key(&blob, "hunter2pass").unwrap();
        assert_eq!(restored, kp.private_b64);
        // неверный пароль не расшифровывает
        assert!(decrypt_private_key(&blob, "wrong").is_err());
    }

    #[test]
    fn b64_tolerates_padding() {
        // Android отдаёт url-safe с '=', наш декодер должен принять.
        let data = [1u8, 2, 3, 4, 5];
        let padded = base64::engine::general_purpose::URL_SAFE.encode(data);
        assert!(padded.contains('='));
        assert_eq!(b64d(&padded).unwrap(), data);
    }
}
