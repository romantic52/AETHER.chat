//! Общее защищённое ядро Secure Messenger.
//!
//! Здесь живёт крипта и (в будущем) протокол/хранилище, общие для всех
//! клиентов (Android через JNI/UniFFI, Desktop, позже Web через WASM).
//! Форматы строго совместимы с уже развёрнутым протоколом — см.
//! `WIRE_PROTOCOL.md` и существующие реализации в `web/app.js` и
//! `android/.../crypto/E2ECrypto.kt`.

pub mod crypto;
pub mod ffi;
pub mod protocol;
pub mod api;
pub mod ws;
pub mod storage;

pub use crypto::{
    aes_decrypt, aes_encrypt, box_decrypt, box_encrypt, decrypt_private_key,
    encrypt_private_key, generate_keypair, CryptoError, Envelope, KeyPair,
};

// Генерация FFI-обвязки (Kotlin/Swift биндинги через UniFFI).
uniffi::setup_scaffolding!();
