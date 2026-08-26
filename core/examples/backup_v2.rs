//! Кросс-клиентская сверка резервной копии ключа формата v2.
//!
//! Печатает копию, созданную ЯДРОМ, чтобы её можно было открыть в вебе и на
//! Android и убедиться, что байты совпадают. Одна реализация Argon2id на всех,
//! но проверить это надо не рассуждением, а расшифровкой.
//!
//!   cargo run --example backup_v2

use sm_core::crypto::{decrypt_private_key, encrypt_private_key, encrypt_private_key_v2,
                      private_key_backup_version};

fn main() {
    // Фиксированные значения: сверка должна быть воспроизводимой.
    let private_key = "bXktc2VjcmV0LWtleS1mb3ItY3Jvc3MtY2hlY2s".to_string();
    let password = "dolgiy-parol-dlya-sverki".to_string();

    let v1 = encrypt_private_key(private_key.clone(), password.clone()).unwrap();
    let v2 = encrypt_private_key_v2(private_key.clone(), password.clone()).unwrap();

    println!("пароль:        {password}");
    println!("приватный ключ: {private_key}");
    println!();
    println!("v1 (пишется сегодня, поколение {}):", private_key_backup_version(v1.clone()));
    println!("  {v1}");
    println!();
    println!("v2 (Argon2id, поколение {}):", private_key_backup_version(v2.clone()));
    println!("  {v2}");
    println!();

    // Ядро обязано открывать обе своих копии.
    assert_eq!(decrypt_private_key(v1, password.clone()).unwrap(), private_key);
    assert_eq!(decrypt_private_key(v2, password).unwrap(), private_key);
    println!("ядро открывает обе копии");
}
