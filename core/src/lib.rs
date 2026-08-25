//! AETHER core: крипта, wire-протокол, HTTP API, локальное хранилище, WebSocket.
//! Единственный источник правды для всего, что касается E2E и сети.
//! Канон протокола — WIRE_PROTOCOL.md в корне репозитория.

uniffi::setup_scaffolding!();

pub mod api;
pub mod crypto;
pub mod discovery;
pub mod message;
pub mod protocol;
pub mod ratchet;
pub mod store;
pub mod ws;

/// Общая ошибка ядра, пробрасывается в Swift как исключение.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("crypto: {msg}")]
    Crypto { msg: String },
    #[error("api {status}: {msg}")]
    Api { status: u16, msg: String },
    #[error("network: {msg}")]
    Network { msg: String },
    #[error("store: {msg}")]
    Store { msg: String },
    #[error("ws: {msg}")]
    Ws { msg: String },
    #[error("bad input: {msg}")]
    BadInput { msg: String },
}

impl CoreError {
    pub fn crypto(e: impl std::fmt::Display) -> Self {
        CoreError::Crypto { msg: e.to_string() }
    }
    pub fn bad(e: impl std::fmt::Display) -> Self {
        CoreError::BadInput { msg: e.to_string() }
    }
    pub fn store(e: impl std::fmt::Display) -> Self {
        CoreError::Store { msg: e.to_string() }
    }
}

impl From<rusqlite::Error> for CoreError {
    fn from(e: rusqlite::Error) -> Self {
        CoreError::Store { msg: e.to_string() }
    }
}
