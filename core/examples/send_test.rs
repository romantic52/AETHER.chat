//! Кросс-тест: эмулирует Android/web-отправителя. Регистрирует временный аккаунт,
//! шлёт зашифрованный текст на testuser через канонический wire-протокол.
//! Запуск (нативно, на Mac): cargo run --example send_test -- "текст"
//! Проверяет, что iOS-приложение (залогинено как testuser) расшифрует и покажет сообщение.

use sm_core::api::ApiClient;
use sm_core::crypto::generate_keypair;
use sm_core::protocol::seal_direct;

fn main() {
    let base = "https://YOUR-SERVER-HOST.nip.io";
    let text = std::env::args().nth(1).unwrap_or_else(|| "Привет с Android! ✓✓".to_string());
    let recipient = std::env::args().nth(2).unwrap_or_else(|| "testuser".to_string());

    let api = ApiClient::new(base.to_string());
    let kp = generate_keypair();
    let uid = format!("xtest_{}", &sm_core::crypto::random_key_b64()[..8].to_lowercase());
    let enc_priv = sm_core::crypto::encrypt_private_key(kp.private_b64.clone(), "test-passphrase".into()).unwrap();

    let session = api
        .register(uid.clone(), "test-passphrase".into(), kp.public_b64.clone(), enc_priv)
        .expect("register");
    println!("registered {} (token {}…)", session.user_id, &session.token[..8]);

    let recipient_pub = api.get_public_key(recipient.clone()).expect("get pubkey");
    println!("{recipient} pubkey: {recipient_pub}");

    let payload = format!(r#"{{"type":"text","text":"{text}"}}"#);
    let envelope = seal_direct(payload, recipient_pub, kp.public_b64.clone(), kp.private_b64.clone()).unwrap();

    let msg_id = api.send_message(recipient.clone(), envelope, None).expect("send");
    println!("✅ отправлено на {recipient}, message_id = {msg_id}");
    println!("   от отправителя {uid}");
}
