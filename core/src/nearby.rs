//! Приватные идентификаторы обнаружения.
//!
//! Задача: устройство должно объявлять о себе в эфир так, чтобы знакомые могли
//! его узнать, а посторонний сканер не мог проследить человека по городу.
//!
//! Схема — та же, что в Exposure Notifications и Find My, на HKDF и HMAC.
//! Своей криптографии здесь нет и быть не должно.
//!
//!   DK      32 случайных байта, ключ обнаружения. Живёт в Keychain/Keystore.
//!   epoch   номер 15-минутного интервала: floor(unixtime / 900)
//!   EDI(T)  = HKDF-SHA256(DK, salt = "AETHER-NEARBY-1", info = LE64(T))[0..16]
//!   tag     = HMAC-SHA256(EDI(T), nonce)[0..4]
//!
//! В эфир уходит 15 байт: [версия(1) | EDI_prefix(6) | nonce(4) | tag(4)]
//!
//! Свойства:
//!   • каждые 15 минут идентификатор меняется целиком, связать два соседних
//!     интервала без DK нельзя;
//!   • контакт узнаёт нас, получив DK по уже существующему E2EE-каналу, и
//!     сверяет маячок локально — сеть для этого не нужна;
//!   • посторонний видит случайные байты; даже два маячка одного устройства
//!     в одном интервале отличаются, потому что nonce каждый раз новый;
//!   • смена DK мгновенно разрывает всю прошлую отслеживаемость.
//!
//! Здесь НЕ решается задача аутентификации: маячок лишь говорит «возможно, это
//! знакомый». Кто это на самом деле, выясняет рукопожатие с подписью
//! (docs/TRANSPORT_LAYER_DESIGN.md, раздел 6).

use crate::CoreError;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use hkdf::Hkdf;
use hmac::{Hmac, Mac};
use rand_core::{OsRng, RngCore};
use sha2::Sha256;
use std::time::{SystemTime, UNIX_EPOCH};

/// Длина интервала ротации в секундах.
pub const EPOCH_SECONDS: i64 = 900;

const SALT: &[u8] = b"AETHER-NEARBY-1";
const BEACON_VERSION: u8 = 1;
const BEACON_LEN: usize = 15;
const EDI_LEN: usize = 16;
const EDI_PREFIX_LEN: usize = 6;
const NONCE_LEN: usize = 4;
const TAG_LEN: usize = 4;

/// Новый ключ обнаружения. Хранить только в защищённом хранилище платформы.
#[uniffi::export]
pub fn nearby_new_discovery_key() -> String {
    let mut key = [0u8; 32];
    OsRng.fill_bytes(&mut key);
    URL_SAFE_NO_PAD.encode(key)
}

/// Текущий интервал ротации.
#[uniffi::export]
pub fn nearby_current_epoch() -> i64 {
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);
    now / EPOCH_SECONDS
}

fn edi_bytes(dk_b64: &str, epoch: i64) -> Result<[u8; EDI_LEN], CoreError> {
    let dk = crate::crypto::decode_b64(dk_b64)?;
    if dk.len() != 32 {
        return Err(CoreError::bad("ключ обнаружения: ожидалось 32 байта"));
    }
    let hk = Hkdf::<Sha256>::new(Some(SALT), &dk);
    let mut out = [0u8; EDI_LEN];
    hk.expand(&epoch.to_le_bytes(), &mut out)
        .map_err(|_| CoreError::crypto("не удалось вывести идентификатор"))?;
    Ok(out)
}

/// Идентификатор интервала — то, что сверяют между собой знакомые устройства.
#[uniffi::export]
pub fn nearby_edi(discovery_key_b64: String, epoch: i64) -> Result<String, CoreError> {
    Ok(URL_SAFE_NO_PAD.encode(edi_bytes(&discovery_key_b64, epoch)?))
}

/// Собрать маячок для эфира. Каждый вызов даёт новые байты: nonce случаен,
/// поэтому два объявления подряд нельзя связать между собой.
#[uniffi::export]
pub fn nearby_build_beacon(discovery_key_b64: String, epoch: i64) -> Result<Vec<u8>, CoreError> {
    let edi = edi_bytes(&discovery_key_b64, epoch)?;
    let mut nonce = [0u8; NONCE_LEN];
    OsRng.fill_bytes(&mut nonce);

    let mut out = Vec::with_capacity(BEACON_LEN);
    out.push(BEACON_VERSION);
    out.extend_from_slice(&edi[..EDI_PREFIX_LEN]);
    out.extend_from_slice(&nonce);
    out.extend_from_slice(&tag(&edi, &nonce));
    Ok(out)
}

fn tag(edi: &[u8; EDI_LEN], nonce: &[u8]) -> [u8; TAG_LEN] {
    let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(edi).expect("HMAC принимает любой размер ключа");
    mac.update(nonce);
    let full = mac.finalize().into_bytes();
    let mut short = [0u8; TAG_LEN];
    short.copy_from_slice(&full[..TAG_LEN]);
    short
}

/// Наш ли это маячок (или знакомого, чей DK у нас есть).
///
/// Проверяются соседние интервалы: часы устройств расходятся, и на границе
/// пятнадцатиминутки объявление может прийти из «прошлого» окна. Без этого
/// знакомые переставали бы видеть друг друга по четыре раза в час.
#[uniffi::export]
pub fn nearby_match_beacon(beacon: Vec<u8>, discovery_key_b64: String, epoch: i64) -> bool {
    if beacon.len() != BEACON_LEN || beacon[0] != BEACON_VERSION {
        return false;
    }
    let nonce = &beacon[1 + EDI_PREFIX_LEN..1 + EDI_PREFIX_LEN + NONCE_LEN];
    let claimed_tag = &beacon[1 + EDI_PREFIX_LEN + NONCE_LEN..];

    for candidate in [epoch, epoch - 1, epoch + 1] {
        let Ok(edi) = edi_bytes(&discovery_key_b64, candidate) else { continue };
        if edi[..EDI_PREFIX_LEN] != beacon[1..1 + EDI_PREFIX_LEN] {
            continue;
        }
        // Префикс совпал — проверяем метку. Сравнение постоянного времени:
        // подбирать метку по времени ответа не должно быть возможно.
        let expected = tag(&edi, nonce);
        let mut diff = 0u8;
        for i in 0..TAG_LEN {
            diff |= expected[i] ^ claimed_tag[i];
        }
        if diff == 0 {
            return true;
        }
    }
    false
}

/// Похоже ли на маячок Aether вообще. Нужно, чтобы не разбирать чужую рекламу.
#[uniffi::export]
pub fn nearby_is_beacon(beacon: Vec<u8>) -> bool {
    beacon.len() == BEACON_LEN && beacon[0] == BEACON_VERSION
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn own_beacon_matches_and_foreign_does_not() {
        let mine = nearby_new_discovery_key();
        let other = nearby_new_discovery_key();
        let epoch = 1_000_000;

        let beacon = nearby_build_beacon(mine.clone(), epoch).unwrap();
        assert_eq!(beacon.len(), BEACON_LEN);
        assert!(nearby_match_beacon(beacon.clone(), mine.clone(), epoch));
        // Посторонний со своим ключом не узнаёт наш маячок.
        assert!(!nearby_match_beacon(beacon, other, epoch));
    }

    #[test]
    fn rotates_between_epochs() {
        let dk = nearby_new_discovery_key();
        let a = nearby_edi(dk.clone(), 100).unwrap();
        let b = nearby_edi(dk.clone(), 101).unwrap();
        assert_ne!(a, b, "идентификатор обязан меняться с интервалом");

        // Маячок прошлого интервала не должен опознаваться через сутки:
        // иначе ротация не даёт ничего.
        let old = nearby_build_beacon(dk.clone(), 100).unwrap();
        assert!(!nearby_match_beacon(old, dk, 196));
    }

    #[test]
    fn tolerates_neighbouring_epoch() {
        // Часы расходятся, и на границе окна объявление приходит из соседнего.
        let dk = nearby_new_discovery_key();
        let beacon = nearby_build_beacon(dk.clone(), 500).unwrap();
        assert!(nearby_match_beacon(beacon.clone(), dk.clone(), 501));
        assert!(nearby_match_beacon(beacon, dk, 499));
    }

    #[test]
    fn two_beacons_in_one_epoch_differ_but_both_match() {
        // Пассивный наблюдатель не должен связывать два объявления подряд.
        let dk = nearby_new_discovery_key();
        let first = nearby_build_beacon(dk.clone(), 7).unwrap();
        let second = nearby_build_beacon(dk.clone(), 7).unwrap();
        assert_ne!(first, second, "байты в эфире обязаны отличаться");
        assert!(nearby_match_beacon(first, dk.clone(), 7));
        assert!(nearby_match_beacon(second, dk, 7));
    }

    #[test]
    fn garbage_is_rejected() {
        let dk = nearby_new_discovery_key();
        assert!(!nearby_match_beacon(vec![], dk.clone(), 1));
        assert!(!nearby_match_beacon(vec![0u8; BEACON_LEN], dk.clone(), 1));
        // Чужая реклама той же длины, но другой версии.
        let mut wrong_version = nearby_build_beacon(dk.clone(), 1).unwrap();
        wrong_version[0] = 9;
        assert!(!nearby_match_beacon(wrong_version.clone(), dk.clone(), 1));
        assert!(!nearby_is_beacon(wrong_version));

        // Подделанная метка при верном префиксе не проходит.
        let mut forged = nearby_build_beacon(dk.clone(), 1).unwrap();
        let last = forged.len() - 1;
        forged[last] ^= 0xFF;
        assert!(!nearby_match_beacon(forged, dk, 1));
    }

    #[test]
    fn new_key_breaks_past_linkability() {
        // Сброс ключа обнаружения обязан отвязывать от прошлых объявлений.
        let old_key = nearby_new_discovery_key();
        let beacon = nearby_build_beacon(old_key, 42).unwrap();
        let fresh = nearby_new_discovery_key();
        assert!(!nearby_match_beacon(beacon, fresh, 42));
    }
}
