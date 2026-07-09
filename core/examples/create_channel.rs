//! Кросс-тест: создаёт канал (is_channel=true), добавляет участника read-only.
use sm_core::api::ApiClient;
use sm_core::crypto::{generate_keypair, random_key_b64, encrypt_private_key};
use sm_core::protocol::wrap_group_key;

fn main() {
    let base = "https://144-31-181-10.nip.io";
    let name = std::env::args().nth(1).unwrap_or("Тестовый канал".into());
    let member = std::env::args().nth(2).unwrap_or("btest2".into());

    let api = ApiClient::new(base.to_string());
    let kp = generate_keypair();
    let uid = format!("xchan_{}", &random_key_b64()[..6].to_lowercase());
    let enc = encrypt_private_key(kp.private_b64.clone(), "pass1234".into()).unwrap();
    api.register(uid.clone(), "pass1234".into(), kp.public_b64.clone(), enc).unwrap();

    let group_key = random_key_b64();
    let channel_id = format!("chn_{}", &random_key_b64()[..8].to_lowercase());
    let owner_wrapped = wrap_group_key(group_key.clone(), kp.public_b64.clone(), kp.public_b64.clone(), kp.private_b64.clone()).unwrap();
    api.create_group(channel_id.clone(), name.clone(), Some("Read-only канал".into()), true, owner_wrapped, None).unwrap();

    let pub_key = api.get_public_key(member.clone()).unwrap();
    let wrapped = wrap_group_key(group_key.clone(), pub_key, kp.public_b64.clone(), kp.private_b64.clone()).unwrap();
    api.add_group_member(channel_id.clone(), member.clone(), wrapped, Some("member".into())).unwrap();

    // Пост от владельца в канал.
    let post = format!(r#"{{"type":"text","text":"Первый пост в канале"}}"#);
    let sealed = sm_core::crypto::aes_encrypt(group_key.clone(), post.into_bytes()).unwrap();
    let env = serde_json::json!({"is_group":"1","nonce_b64":sealed.nonce_b64,"ciphertext_b64":sm_core::crypto::b64url_encode(sealed.ciphertext)});
    api.send_message(channel_id.clone(), env.to_string(), None).unwrap();

    println!("✅ channel_id={channel_id} owner={uid} member={member}");
}
