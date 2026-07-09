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

/// Бэкап приватного ключа: PBKDF2 100k + AES-GCM. Формат `salt:iv:ct` (b64url).
/// Шифруется b64url-СТРОКА ключа, не сырые байты (канон Android/web).
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

#[uniffi::export]
pub fn decrypt_private_key(blob: String, password: String) -> Result<String, CoreError> {
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
    fn backup_roundtrip() {
        let kp = generate_keypair();
        let blob = encrypt_private_key(kp.private_b64.clone(), "test-passphrase".into()).unwrap();
        assert_eq!(blob.split(':').count(), 3);
        assert_eq!(decrypt_private_key(blob.clone(), "test-passphrase".into()).unwrap(), kp.private_b64);
        assert!(decrypt_private_key(blob, "wrong".into()).is_err());
    }

    /// Живой вектор с сервера: бэкап testuser, пароль test-passphrase.
    #[test]
    fn backup_live_vector() {
        let blob = "REDACTED_KEY_VECTOR";
        let priv_b64 = decrypt_private_key(blob.into(), "test-passphrase".into()).unwrap();
        let sk = SecretKey::from(key32(&priv_b64, "sk").unwrap());
        let pub_b64 = URL_SAFE_NO_PAD.encode(sk.public_key().as_bytes());
        assert_eq!(pub_b64, "REDACTED_PUBKEY");
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
