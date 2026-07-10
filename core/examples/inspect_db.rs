// Диагностика: открыть базу с ключом и посчитать содержимое.
// cargo run --example inspect_db -- <path> <key_b64|none>
use sm_core::store::CoreStore;

fn main() {
    let mut args = std::env::args().skip(1);
    let path = args.next().expect("path");
    let key = args.next().filter(|k| k != "none");
    match CoreStore::open(path, key) {
        Ok(store) => {
            let chats = store.get_chat_list().unwrap_or_default();
            println!("OPEN OK, chats: {}", chats.len());
            for c in chats {
                println!("  {} | unread {} | last: {}", c.peer_id, c.unread, &c.last_text.chars().take(40).collect::<String>());
            }
        }
        Err(e) => println!("OPEN FAILED: {e:?}"),
    }
}
