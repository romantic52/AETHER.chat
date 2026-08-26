//! Сверка идентификаторов сообщений между клиентами.
//!
//! У веба своя реализация UUIDv7 (WebCrypto), у ядра своя. Формат
//! стандартный, но «стандартный» — не доказательство: проверяем, что ядро
//! признаёт идентификаторы, созданные вебом, и печатаем свои для обратной
//! сверки в браузере.
//!
//!   cargo run --example check_message_id -- <id> [<id> ...]

use sm_core::message::{is_valid_message_id, message_id_from_payload, new_message_id,
                       payload_with_message_id};

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();

    if args.is_empty() {
        println!("Идентификаторы, созданные ЯДРОМ (сверить в браузере):");
        for _ in 0..5 {
            println!("  {}", new_message_id());
        }
        return;
    }

    let mut bad = 0;
    println!("Проверка ядром идентификаторов, созданных вебом:");
    for id in &args {
        let ok = is_valid_message_id(id.clone());
        // И полный путь: положить в payload и достать обратно.
        let round = payload_with_message_id(r#"{"type":"text"}"#.into(), id.clone())
            .ok()
            .and_then(message_id_from_payload);
        let round_ok = round.as_deref() == Some(id.as_str());
        println!("  {id}  форма: {}  через payload: {}",
                 if ok { "ok" } else { "ОТВЕРГНУТ" },
                 if round_ok { "ok" } else { "ОТВЕРГНУТ" });
        if !ok || !round_ok { bad += 1; }
    }
    if bad > 0 {
        eprintln!("\nНЕ СОШЛОСЬ: {bad} из {}", args.len());
        std::process::exit(1);
    }
    println!("\nВсе идентификаторы веба приняты ядром.");
}
