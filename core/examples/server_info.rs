//! Обнаружение сервера глазами клиента — без UI.
//!
//! Тапать по экрану в этом окружении нечем, поэтому весь путь «ввёл адрес →
//! нашёл сервер → проверил подпись» проверяется отсюда, как и остальные
//! примеры в этой папке.
//!
//!   cargo run --example server_info -- 127.0.0.1:8099
//!   cargo run --example server_info -- chat.example.com

use sm_core::discovery;

fn main() {
    let input = std::env::args().nth(1).unwrap_or_else(|| "127.0.0.1:8099".into());
    let nonce = format!("{:x}", std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos());

    let candidates = discovery::normalize_server_input(input.clone(), true);
    println!("ввод: {input}");
    println!("кандидаты: {candidates:?}");

    match discovery::discover_server(input, nonce, true) {
        Ok(info) => {
            println!("\n{}", info.name);
            println!("  origin      {}", info.origin);
            println!("  server_id   {}", info.server_id);
            println!("  протокол    v{}", info.protocol_version);
            println!("  регистрация {}", info.registration_mode);
            println!("  E2EE        {}", if info.supports_e2ee { "да" } else { "нет" });
            println!("  импорт      {}", if info.supports_data_import { "да" } else { "нет" });
            println!("  подпись     {}", if info.signature_valid { "сошлась" } else { "НЕТ" });
            println!("  отпечаток   {}", discovery::format_fingerprint(info.fingerprint_b64));
            println!("  адреса      {}", if info.endpoints_match_origin {
                "совпадают с origin".to_string()
            } else {
                format!("РАСХОДЯТСЯ: api={}", info.api_url)
            });
            if info.cleartext {
                println!("  ВНИМАНИЕ    соединение без TLS (локальный режим)");
            }
        }
        Err(e) => {
            println!("\nне найден: {e}");
            std::process::exit(1);
        }
    }
}
