use sm_core::api::ApiClient;
use sm_core::protocol::unwrap_group_key;

fn main() {
    let base = std::env::var("AETHER_URL").unwrap_or_else(|_| "http://127.0.0.1:8000".to_string());
    let api = ApiClient::new(base.to_string());
    let session = api.login(std::env::var("AETHER_PEER").unwrap_or_else(|_| "peer".into()), std::env::var("AETHER_PASS").unwrap_or_else(|_| "changeme".into())).unwrap();
    let my_priv = sm_core::crypto::decrypt_private_key(session.encrypted_private_key_b64.clone(), std::env::var("AETHER_PASS").unwrap_or_else(|_| "changeme".into())).unwrap();

    let groups_json = api.get_my_groups().unwrap();
    let v: serde_json::Value = serde_json::from_str(&groups_json).unwrap();
    for g in v["groups"].as_array().unwrap() {
        let id = g["id"].as_str().unwrap();
        if id != "chn_axusbnz6" { continue; }
        let enc = g["encrypted_key_b64"].as_str().unwrap();
        match unwrap_group_key(enc.to_string(), my_priv.clone()) {
            Ok(key) => println!("✅ unwrap OK for {id}: key={key}"),
            Err(e) => println!("❌ unwrap FAILED for {id}: {e:?}"),
        }
    }
}
