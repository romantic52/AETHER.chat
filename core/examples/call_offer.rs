//! Отправляет webrtc_offer по WS на получателя (тест входящего звонка).
//! cargo run --example call_offer -- [recipient] [video]
use sm_core::api::ApiClient;
use sm_core::ws::{WsClient, WsListener};
use std::sync::Arc;

struct L;
impl WsListener for L {
    fn on_open(&self) { println!("ws open"); }
    fn on_event(&self, json: String) { println!("ws event: {json}"); }
    fn on_close(&self) { println!("ws close"); }
}

fn main() {
    let base = "https://144-31-181-10.nip.io";
    let recipient = std::env::args().nth(1).unwrap_or("btest2".into());
    let video = std::env::args().nth(2).map(|s| s == "video").unwrap_or(false);

    let api = ApiClient::new(base.to_string());
    let kp = sm_core::crypto::generate_keypair();
    let uid = format!("xcall_{}", &sm_core::crypto::random_key_b64()[..6].to_lowercase());
    let enc = sm_core::crypto::encrypt_private_key(kp.private_b64.clone(), "pass1234".into()).unwrap();
    let session = api.register(uid.clone(), "pass1234".into(), kp.public_b64.clone(), enc).unwrap();
    println!("caller {uid}, token {}…", &session.token[..8]);

    let ws = Arc::new(WsClient::new());
    let url = format!("wss://144-31-181-10.nip.io/ws?token={}", session.token);
    ws.connect(url, Box::new(L)).unwrap();
    std::thread::sleep(std::time::Duration::from_secs(2));

    let offer = serde_json::json!({
        "type": "webrtc_offer",
        "recipient_id": recipient,
        "sig_id": "sig_test_123",
        "sdp": "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n",
        "isVideoCall": video,
    });
    ws.send_raw(offer.to_string()).unwrap();
    println!("→ webrtc_offer отправлен на {recipient} (video={video})");
    std::thread::sleep(std::time::Duration::from_secs(4));
}
