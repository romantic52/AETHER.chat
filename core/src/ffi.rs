//! FFI-поверхность ядра для Kotlin/Swift (UniFFI). Тонкие обёртки над `crypto`;
//! сама крипта остаётся чистой и тестируется отдельно.

use crate::crypto;
use crate::crypto::{CryptoError, Envelope, KeyPair};

/// Результат AES-шифрования (UniFFI не поддерживает кортежи).
#[derive(Debug, Clone, uniffi::Record)]
pub struct AesCiphertext {
    pub nonce_b64: String,
    pub ciphertext_b64: String,
}

#[uniffi::export]
pub fn generate_keypair() -> KeyPair {
    crypto::generate_keypair()
}

#[uniffi::export]
pub fn box_encrypt(
    plaintext: String,
    my_private_b64: String,
    their_public_b64: String,
) -> Result<Envelope, CryptoError> {
    crypto::box_encrypt(&plaintext, &my_private_b64, &their_public_b64)
}

#[uniffi::export]
pub fn box_decrypt(env: Envelope, my_private_b64: String) -> Result<String, CryptoError> {
    crypto::box_decrypt(&env, &my_private_b64)
}

#[uniffi::export]
pub fn aes_encrypt(plaintext: Vec<u8>, key_b64: String) -> Result<AesCiphertext, CryptoError> {
    let (nonce_b64, ciphertext_b64) = crypto::aes_encrypt(&plaintext, &key_b64)?;
    Ok(AesCiphertext { nonce_b64, ciphertext_b64 })
}

#[uniffi::export]
pub fn aes_decrypt(
    key_b64: String,
    nonce_b64: String,
    ciphertext_b64: String,
) -> Result<Vec<u8>, CryptoError> {
    crypto::aes_decrypt(&key_b64, &nonce_b64, &ciphertext_b64)
}

#[uniffi::export]
pub fn encrypt_private_key(private_key_b64: String, password: String) -> Result<String, CryptoError> {
    crypto::encrypt_private_key(&private_key_b64, &password)
}

#[uniffi::export]
pub fn decrypt_private_key(blob: String, password: String) -> Result<String, CryptoError> {
    crypto::decrypt_private_key(&blob, &password)
}
