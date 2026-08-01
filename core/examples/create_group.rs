//! Кросс-тест: создаёт группу (как Android/web), добавляет testuser участником.
//! cargo run --example create_group -- <name> [member2...]
use sm_core::api::ApiClient;
use sm_core::crypto::{generate_keypair, random_key_b64, encrypt_private_key};
use sm_core::protocol::wrap_group_key;

fn main() {
    let base = std::env::var("AETHER_URL").unwrap_or_else(|_| "http://127.0.0.1:8000".to_string());
    let name = std::env::args().nth(1).unwrap_or("Тестовая группа".into());
    let members: Vec<String> = std::env::args().skip(2).collect();
    let members = if members.is_empty() { vec!["testuser".to_string()] } else { members };

    let api = ApiClient::new(base.to_string());
    let kp = generate_keypair();
    let uid = format!("xgrp_{}", &random_key_b64()[..6].to_lowercase());
    let enc = encrypt_private_key(kp.private_b64.clone(), "test-passphrase".into()).unwrap();
    api.register(uid.clone(), "test-passphrase".into(), kp.public_b64.clone(), enc).unwrap();
    println!("owner: {uid}");

    let group_key = random_key_b64();
    let group_id = format!("grp_{}", &random_key_b64()[..8].to_lowercase());
    let owner_wrapped = wrap_group_key(group_key.clone(), kp.public_b64.clone(), kp.public_b64.clone(), kp.private_b64.clone()).unwrap();

    let resp = api.create_group(group_id.clone(), name.clone(), Some("Кросс-тест группы".into()), false, owner_wrapped, None).unwrap();
    println!("create_group resp: {resp}");

    for m in &members {
        let pub_key = api.get_public_key(m.clone()).expect("member pubkey");
        let wrapped = wrap_group_key(group_key.clone(), pub_key, kp.public_b64.clone(), kp.private_b64.clone()).unwrap();
        api.add_group_member(group_id.clone(), m.clone(), wrapped, Some("member".into())).unwrap();
        println!("added member: {m}");
    }

    println!("✅ group_id={group_id} name={name} owner={uid} members={:?}", members);
}
