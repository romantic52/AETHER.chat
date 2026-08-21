//! Логинится под указанным аккаунтом и печатает расшифрованный inbox (кто что прислал).
//! Запуск: cargo run --example listen -- <user_id> <password>

use sm_core::api::ApiClient;
use sm_core::protocol::open_envelope;

fn main() {
    let base = std::env::var("AETHER_URL").unwrap_or_else(|_| "http://127.0.0.1:8000".to_string());
    let user = std::env::args().nth(1).expect("user_id");
    let pass = std::env::args().nth(2).unwrap_or_else(|| std::env::var("AETHER_PASS").unwrap_or_else(|_| "changeme".into()));

    let api = ApiClient::new(base.to_string());
    let session = api.login(user.clone(), pass.clone()).expect("login");
    let my_priv = sm_core::crypto::decrypt_private_key(session.encrypted_private_key_b64.clone(), pass).expect("decrypt key");

    let inbox = api.fetch_inbox(None).expect("inbox");
    println!("{} сообщений в inbox {user}:", inbox.len());
    for item in &inbox {
        match open_envelope(item.envelope.clone(), my_priv.clone(), None) {
            Ok(opened) => println!("  от {}: {}", item.sender_id, opened.plaintext),
            Err(e) => println!("  от {}: <не расшифровано: {e:?}>", item.sender_id),
        }
    }
}
