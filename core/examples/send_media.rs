//! Универсальная отправка медиа: cargo run --example send_media -- <path> <kind> <mime> [recipient] [duration]
use sm_core::api::ApiClient;
use sm_core::crypto::{aes_encrypt, generate_keypair, random_key_b64};
use sm_core::protocol::seal_direct;

fn main() {
    let base = "https://YOUR-SERVER-HOST.nip.io";
    let a: Vec<String> = std::env::args().collect();
    let path = &a[1];
    let kind = a.get(2).cloned().unwrap_or("file".into());
    let mime = a.get(3).cloned().unwrap_or("application/octet-stream".into());
    let recipient = a.get(4).cloned().unwrap_or("testuser".into());
    let duration: f64 = a.get(5).and_then(|s| s.parse().ok()).unwrap_or(0.0);
    let bytes = std::fs::read(path).expect("read");
    let name = std::path::Path::new(path).file_name().unwrap().to_string_lossy().to_string();

    let api = ApiClient::new(base.to_string());
    let kp = generate_keypair();
    let uid = format!("xmed_{}", &random_key_b64()[..6].to_lowercase());
    let enc = sm_core::crypto::encrypt_private_key(kp.private_b64.clone(), "test-passphrase".into()).unwrap();
    api.register(uid.clone(), "test-passphrase".into(), kp.public_b64.clone(), enc).unwrap();

    let sym = random_key_b64();
    let sealed = aes_encrypt(sym.clone(), bytes).unwrap();
    let file_id = api.upload(sealed.ciphertext).unwrap();
    let recipient_pub = api.get_public_key(recipient.clone()).unwrap();

    let mut p = serde_json::json!({
        "type": "media", "file_id": file_id, "sym_key": sym,
        "mime_type": mime, "nonce": sealed.nonce_b64, "kind": kind,
        "file_name": name,
    });
    if duration > 0.0 { p["duration"] = duration.into(); }
    let env = seal_direct(p.to_string(), recipient_pub, kp.public_b64.clone(), kp.private_b64.clone()).unwrap();
    let id = api.send_message(recipient.clone(), env, None).unwrap();
    println!("✅ {kind} → {recipient}: file_id={file_id} msg={id} (от {uid})");
}
