//! Кросс-тест приёма медиа: шифрует картинку AES-GCM, грузит на /upload, шлёт media-payload.
//! Запуск: cargo run --example send_image -- <path.png> [recipient]

use sm_core::api::ApiClient;
use sm_core::crypto::{aes_encrypt, generate_keypair, random_key_b64, b64url_encode};
use sm_core::protocol::seal_direct;

fn main() {
    let base = std::env::var("AETHER_URL").unwrap_or_else(|_| "http://127.0.0.1:8000".to_string());
    let path = std::env::args().nth(1).expect("path");
    let recipient = std::env::args().nth(2).unwrap_or_else(|| "testuser".into());
    let bytes = std::fs::read(&path).expect("read image");
    let mime = if path.ends_with(".png") { "image/png" } else { "image/jpeg" };

    let api = ApiClient::new(base.to_string());
    let kp = generate_keypair();
    let uid = format!("ximg_{}", &random_key_b64()[..6].to_lowercase());
    let enc_priv = sm_core::crypto::encrypt_private_key(kp.private_b64.clone(), "test-passphrase".into()).unwrap();
    api.register(uid.clone(), "test-passphrase".into(), kp.public_b64.clone(), enc_priv).expect("register");

    // Шифруем и грузим.
    let sym = random_key_b64();
    let sealed = aes_encrypt(sym.clone(), bytes).unwrap();
    let file_id = api.upload(sealed.ciphertext).expect("upload");

    let recipient_pub = api.get_public_key(recipient.clone()).unwrap();
    let payload = format!(
        r#"{{"type":"media","file_id":"{file_id}","sym_key":"{sym}","mime_type":"{mime}","nonce":"{}","kind":"image"}}"#,
        sealed.nonce_b64
    );
    let envelope = seal_direct(payload, recipient_pub, kp.public_b64.clone(), kp.private_b64.clone()).unwrap();
    let id = api.send_message(recipient.clone(), envelope, None).unwrap();
    println!("✅ картинка отправлена на {recipient}: file_id={file_id}, msg={id} (от {uid})");
    let _ = b64url_encode;
}
