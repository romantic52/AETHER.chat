//! Отправляет квитанции delivered+read на указанного получателя (эмуляция другого клиента).
//! Запуск: cargo run --example receipt -- <my_user> <my_pass> <recipient>

use sm_core::api::ApiClient;
use sm_core::protocol::seal_direct;

fn main() {
    let base = "https://144-31-181-10.nip.io";
    let user = std::env::args().nth(1).expect("user_id");
    let pass = std::env::args().nth(2).unwrap_or_else(|| "pass1234".into());
    let recipient = std::env::args().nth(3).expect("recipient");

    let api = ApiClient::new(base.to_string());
    let session = api.login(user.clone(), pass.clone()).expect("login");
    let my_priv = sm_core::crypto::decrypt_private_key(session.encrypted_private_key_b64.clone(), pass).unwrap();
    let recipient_pub = api.get_public_key(recipient.clone()).unwrap();

    for kind in ["delivered", "read"] {
        let payload = format!(r#"{{"type":"{kind}"}}"#);
        let env = seal_direct(payload, recipient_pub.clone(), session.public_key_b64.clone(), my_priv.clone()).unwrap();
        let id = api.send_message(recipient.clone(), env, None).unwrap();
        println!("→ {kind} отправлено на {recipient} ({id})");
    }
}
