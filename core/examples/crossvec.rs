//! Кросс-совместимость ядра с развёрнутым протоколом (web tweetnacl / Android Java).
//!   cargo run --example crossvec -- emit  <out.json>   — Rust шифрует, печатает векторы
//!   cargo run --example crossvec -- verify <in.json>    — Rust расшифровывает чужие векторы
//! JSON-сторона на Node: core/cross_test.mjs.

use sm_core::{aes_decrypt, aes_encrypt, box_decrypt, box_encrypt, decrypt_private_key,
              encrypt_private_key, generate_keypair, Envelope};
use std::fs;

// крошечный JSON без зависимостей: только строковые поля
fn jget<'a>(s: &'a str, key: &str) -> &'a str {
    let pat = format!("\"{}\"", key);
    let i = s.find(&pat).expect("key not found");
    let after = &s[i + pat.len()..];
    let c = after.find(':').unwrap();
    let rest = &after[c + 1..];
    let q1 = rest.find('"').unwrap();
    let rest2 = &rest[q1 + 1..];
    let q2 = rest2.find('"').unwrap();
    &rest2[..q2]
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let mode = args.get(1).map(|s| s.as_str()).unwrap_or("emit");
    let path = args.get(2).cloned().unwrap_or_else(|| "crossvec.json".into());

    match mode {
        "emit" => {
            let a = generate_keypair(); // отправитель
            let b = generate_keypair(); // получатель
            let env = box_encrypt("cross 🔐 проверка", &a.private_b64, &b.public_b64).unwrap();
            use base64::Engine;
            let aes_key = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode([3u8; 32]);
            let (anonce, act) = aes_encrypt("aes cross 🚀".as_bytes(), &aes_key).unwrap();
            let backup = encrypt_private_key(&a.private_b64, "pw-123456").unwrap();
            let json = format!(
                "{{\"box\":{{\"recipient_private\":\"{}\",\"sender_pub\":\"{}\",\"nonce\":\"{}\",\"ct\":\"{}\",\"expect\":\"cross 🔐 проверка\"}},\
                  \"aes\":{{\"key\":\"{}\",\"nonce\":\"{}\",\"ct\":\"{}\",\"expect\":\"aes cross 🚀\"}},\
                  \"backup\":{{\"blob\":\"{}\",\"password\":\"pw-123456\",\"expect\":\"{}\"}}}}",
                b.private_b64, env.sender_pubkey_b64, env.nonce_b64, env.ciphertext_b64,
                aes_key, anonce, act,
                backup, a.private_b64
            );
            fs::write(&path, &json).unwrap();
            println!("emit -> {}", path);
        }
        "verify" => {
            let s = fs::read_to_string(&path).unwrap();
            let mut ok = true;

            let env = Envelope {
                sender_pubkey_b64: jget(&s, "sender_pub").to_string(),
                nonce_b64: jget(&s, "nonce").to_string(),
                ciphertext_b64: jget(&s, "ct").to_string(),
            };
            let rp = jget(&s, "recipient_private");
            match box_decrypt(&env, rp) {
                Ok(pt) => { let e = jget(&s, "expect"); let g = pt == e; ok &= g;
                    println!("box   : {}  ({})", if g {"OK"} else {"FAIL"}, pt); }
                Err(e) => { ok = false; println!("box   : FAIL ({:?})", e); }
            }

            // AES блок (повторно ищем во втором объекте — берём последний "key"/"nonce"/"ct")
            let aes_obj = &s[s.find("\"aes\"").unwrap()..];
            let key = jget(aes_obj, "key");
            let an = jget(aes_obj, "nonce");
            let act = jget(aes_obj, "ct");
            match aes_decrypt(key, an, act) {
                Ok(pt) => { let g = String::from_utf8_lossy(&pt) == jget(aes_obj, "expect"); ok &= g;
                    println!("aes   : {}  ({})", if g {"OK"} else {"FAIL"}, String::from_utf8_lossy(&pt)); }
                Err(e) => { ok = false; println!("aes   : FAIL ({:?})", e); }
            }

            let bk = &s[s.find("\"backup\"").unwrap()..];
            match decrypt_private_key(jget(bk, "blob"), jget(bk, "password")) {
                Ok(pt) => { let g = pt == jget(bk, "expect"); ok &= g;
                    println!("backup: {}", if g {"OK"} else {"FAIL"}); }
                Err(e) => { ok = false; println!("backup: FAIL ({:?})", e); }
            }

            println!("{}", if ok { "ALL OK" } else { "SOME FAILED" });
            if !ok { std::process::exit(1); }
        }
        _ => eprintln!("usage: crossvec emit|verify <file>"),
    }
}
