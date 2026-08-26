//! Локальное хранилище (SQLite в ядре): сообщения, чаты, TOFU-пины, ключи групп.
//! Мгновенный старт: UI читает отсюда сразу, сеть догоняет фоном.

use crate::CoreError;
use rusqlite::{params, Connection, OptionalExtension};
use std::sync::Mutex;

/// Одно сообщение в локальной истории.
#[derive(uniffi::Record, Clone)]
pub struct StoredMessage {
    pub id: String,
    pub peer_id: String,
    /// true — исходящее (я отправитель).
    pub outgoing: bool,
    pub sender_id: String,
    /// UTF-8 JSON wire-payload (text/media/...).
    pub payload_json: String,
    /// 0 sending, 1 sent, 2 delivered, 3 read, -1 error.
    pub status: i32,
    /// unix-миллисекунды.
    pub ts: i64,
    /// JSON-объект {emoji: [user_id,...]} или пусто.
    pub reactions_json: String,
    pub edited: bool,
    pub deleted: bool,
}

#[derive(uniffi::Record, Clone)]
pub struct Chat {
    pub peer_id: String,
    pub is_group: bool,
    pub title: String,
    pub last_text: String,
    pub last_ts: i64,
    pub unread: i32,
    pub pinned: bool,
    pub muted: bool,
    pub archived: bool,
}

/// Состояние исчезающего сообщения.
#[derive(uniffi::Record, Clone)]
pub struct EphemeralState {
    pub message_id: String,
    /// UNOPENED | COUNTDOWN | EXPIRED | PURGED
    pub state: String,
    pub opened_ts: Option<i64>,
    /// Когда содержимое должно перестать быть доступным. None — отсчёт ещё не начат.
    pub expires_ts: Option<i64>,
    pub views: i32,
}

/// Политика доставки одного чата.
#[derive(uniffi::Record, Clone)]
pub struct ChatDeliveryPolicy {
    pub peer_id: String,
    /// AUTO | DIRECT_ONLY | DIRECT_PLUS_BACKUP | SERVER
    pub delivery_mode: String,
    /// Порядок транспортов, заданный пользователем. JSON-массив идентификаторов.
    pub transport_order: Option<String>,
    /// NEVER | RELAY_ONLY | ENCRYPTED_BACKUP | ASK
    pub server_storage: String,
    pub updated_ts: i64,
}

impl ChatDeliveryPolicy {
    /// Умолчание = сегодняшнее поведение. Появление политики само по себе
    /// ничего не должно менять ни в одной существующей установке.
    pub fn default_for(peer_id: &str) -> Self {
        ChatDeliveryPolicy {
            peer_id: peer_id.to_string(),
            delivery_mode: "AUTO".into(),
            transport_order: None,
            server_storage: "ENCRYPTED_BACKUP".into(),
            updated_ts: 0,
        }
    }
}

/// Итоговый маршрут доставки сообщения.
#[derive(uniffi::Record, Clone)]
pub struct MessageRoute {
    pub message_id: String,
    /// Идентификатор транспорта: "server.<server_id>", "nearby.ble", ...
    pub transport: String,
    /// Физический канал, если он отличается от транспорта: bluetooth, wifi_aware, lan.
    pub physical: Option<String>,
    /// Какой сервер участвовал. None — сервер не использовался вообще.
    pub server_id: Option<String>,
    /// Оставил ли сервер копию у себя (а не только передал и забыл).
    pub server_stored: bool,
    pub delivered_ts: Option<i64>,
    pub read_ts: Option<i64>,
}

/// Одна попытка доставки — строка журнала для Message Info и повторов.
#[derive(uniffi::Record, Clone)]
pub struct DeliveryAttempt {
    pub message_id: String,
    pub attempt: i32,
    pub transport: String,
    pub device_id: Option<String>,
    pub started_ts: i64,
    pub finished_ts: Option<i64>,
    /// ok | unreachable | rejected | timeout | error
    pub outcome: Option<String>,
    pub detail: Option<String>,
}

/// TOFU-пин публичного ключа собеседника.
#[derive(uniffi::Record, Clone)]
pub struct KeyPin {
    pub peer_id: String,
    pub public_key_b64: String,
    pub verified: bool,
    pub first_seen: i64,
}

/// TOFU-пин olm-identity устройства пира (Double Ratchet, SEC HIGH-2).
/// peer_key — то же соглашение, что у olm_sessions: "peer" (primary) или "peer::device".
/// ed25519_b64 = None, пока от устройства не видели подписанный бандл.
#[derive(uniffi::Record, Clone)]
pub struct OlmPin {
    pub peer_key: String,
    pub curve25519_b64: String,
    pub ed25519_b64: Option<String>,
    pub verified: bool,
    pub first_seen: i64,
    pub prev_curve25519_b64: Option<String>,
    pub prev_ed25519_b64: Option<String>,
    pub changed_ts: Option<i64>,
}

/// Olm-сессия с устройством пира (мультисессии, P10 / SEC MED-4).
/// `session_id` совпадает у обеих сторон одной сессии — по нему входящий
/// prekey-конверт сопоставляется с уже имеющейся сессией.
#[derive(uniffi::Record, Clone)]
pub struct OlmSession {
    pub session_id: String,
    pub session_json: String,
    pub updated_ts: i64,
}

/// TOFU-пин мастер-ключа аккаунта пира (cross-signing, P8). Один на пользователя:
/// все его устройства проверяются этим ключом, поэтому добавление устройства
/// владельцем не требует нового доверия, а подсадка сервером — не проходит.
#[derive(uniffi::Record, Clone)]
pub struct MasterPin {
    pub peer_id: String,
    pub master_key_b64: String,
    pub verified: bool,
    pub first_seen: i64,
    pub prev_master_key_b64: Option<String>,
    pub changed_ts: Option<i64>,
}

/// Итог TOFU-проверки olm-identity.
#[derive(uniffi::Enum, Clone, PartialEq, Eq, Debug)]
pub enum OlmPinStatus {
    /// Первый контакт — ключ запинен (trust on first use).
    FirstUse,
    /// Совпало с пином (в т.ч. дозаполнен ed25519, которого раньше не видели).
    Match,
    /// Расхождение с пином — смена устройства или атака. Пин НЕ обновлён;
    /// принять новый ключ можно только явным olm_pin_accept.
    Mismatch,
}

/// Уведомления об изменениях БД (реактивный UI, модель Room.Flow) — реализует платформа.
/// Kotlin: Store-слой подписывается и мапит в Flow; Swift может игнорировать (свой поллинг).
#[uniffi::export(callback_interface)]
pub trait StoreListener: Send + Sync {
    fn on_messages_changed(&self, peer_id: String);
    fn on_chats_changed(&self);
}

#[derive(uniffi::Object)]
pub struct CoreStore {
    conn: Mutex<Connection>,
    listener: Mutex<Option<Box<dyn StoreListener>>>,
}

/// Сколько Olm-сессий держим на одно устройство пира. Больше одной нужно из-за
/// одновременной инициации с двух сторон (SEC MED-4); потолок — чтобы поток
/// prekey-конвертов не раздувал базу. Живых сессий в норме одна-две.
const MAX_SESSIONS_PER_PEER: i64 = 5;

const SCHEMA: &str = r#"
CREATE TABLE IF NOT EXISTS messages (
    id TEXT PRIMARY KEY,
    peer_id TEXT NOT NULL,
    outgoing INTEGER NOT NULL,
    sender_id TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    status INTEGER NOT NULL DEFAULT 0,
    ts INTEGER NOT NULL,
    reactions_json TEXT NOT NULL DEFAULT '{}',
    edited INTEGER NOT NULL DEFAULT 0,
    deleted INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_msg_peer_ts ON messages(peer_id, ts);
CREATE TABLE IF NOT EXISTS chats (
    peer_id TEXT PRIMARY KEY,
    is_group INTEGER NOT NULL DEFAULT 0,
    title TEXT NOT NULL DEFAULT '',
    last_text TEXT NOT NULL DEFAULT '',
    last_ts INTEGER NOT NULL DEFAULT 0,
    unread INTEGER NOT NULL DEFAULT 0,
    pinned INTEGER NOT NULL DEFAULT 0,
    muted INTEGER NOT NULL DEFAULT 0,
    archived INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS group_keys (
    group_id TEXT NOT NULL,
    epoch INTEGER NOT NULL DEFAULT 0,
    key_b64 TEXT NOT NULL,
    PRIMARY KEY (group_id, epoch)
);
CREATE TABLE IF NOT EXISTS pins (
    peer_id TEXT PRIMARY KEY,
    public_key_b64 TEXT NOT NULL,
    verified INTEGER NOT NULL DEFAULT 0,
    first_seen INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL);
-- Как сообщение реально было доставлено. Отдельно от messages, потому что
-- маршрут это свойство ДОСТАВКИ, а не сообщения: одно и то же сообщение может
-- уйти по Bluetooth, не подтвердиться и уехать через сервер, оставшись тем же
-- сообщением с тем же id (docs/TRANSPORT_LAYER_DESIGN.md, раздел 2.3).
CREATE TABLE IF NOT EXISTS message_route (
    message_id    TEXT PRIMARY KEY,
    transport     TEXT NOT NULL,
    physical      TEXT,
    server_id     TEXT,
    server_stored INTEGER NOT NULL DEFAULT 0,
    delivered_ts  INTEGER,
    read_ts       INTEGER
);
-- Состояние исчезающих сообщений. Отдельно от messages намеренно: меняется
-- чаще самого сообщения, вычищается фоново, и схему messages трогать нельзя —
-- ту же базу читают работающие клиенты.
CREATE TABLE IF NOT EXISTS ephemeral_state (
    message_id TEXT PRIMARY KEY,
    state      TEXT NOT NULL,      -- UNOPENED | COUNTDOWN | EXPIRED | PURGED
    opened_ts  INTEGER,
    expires_ts INTEGER,
    views      INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_ephemeral_due ON ephemeral_state(expires_ts);
-- Политика доставки чата: как отправлять и что можно отдавать серверу.
-- Отдельно от чата: доставка и хранение — разные вещи (раздел 7 проекта),
-- и обе меняются независимо от того, кто собеседник.
CREATE TABLE IF NOT EXISTS chat_delivery_policy (
    peer_id         TEXT PRIMARY KEY,
    delivery_mode   TEXT NOT NULL DEFAULT 'AUTO',
    transport_order TEXT,
    server_storage  TEXT NOT NULL DEFAULT 'ENCRYPTED_BACKUP',
    updated_ts      INTEGER NOT NULL DEFAULT 0
);
-- Что конкретному серверу вообще разрешено получать, по категориям контента.
-- Пустая таблица означает «разрешено всё» — так поведение существующих
-- установок не меняется от одного лишь появления политики.
CREATE TABLE IF NOT EXISTS server_storage_policy (
    server_id    TEXT NOT NULL,
    content_kind TEXT NOT NULL,
    allowed      INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (server_id, content_kind)
);
-- Журнал попыток: по нему строится Message Info и работает повтор.
CREATE TABLE IF NOT EXISTS message_delivery_attempts (
    message_id  TEXT NOT NULL,
    attempt     INTEGER NOT NULL,
    transport   TEXT NOT NULL,
    device_id   TEXT,
    started_ts  INTEGER NOT NULL,
    finished_ts INTEGER,
    outcome     TEXT,
    detail      TEXT,
    PRIMARY KEY (message_id, attempt)
);
CREATE TABLE IF NOT EXISTS olm_sessions (
    peer_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    session_json TEXT NOT NULL,
    updated_ts INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (peer_id, session_id)
);
CREATE TABLE IF NOT EXISTS master_pins (
    peer_id TEXT PRIMARY KEY,
    master_key_b64 TEXT NOT NULL,
    verified INTEGER NOT NULL DEFAULT 0,
    first_seen INTEGER NOT NULL DEFAULT 0,
    prev_master_key_b64 TEXT,
    changed_ts INTEGER
);
CREATE TABLE IF NOT EXISTS olm_pins (
    peer_key TEXT PRIMARY KEY,
    curve25519_b64 TEXT NOT NULL,
    ed25519_b64 TEXT,
    verified INTEGER NOT NULL DEFAULT 0,
    first_seen INTEGER NOT NULL DEFAULT 0,
    prev_curve25519_b64 TEXT,
    prev_ed25519_b64 TEXT,
    changed_ts INTEGER
);
"#;

fn row_to_msg(row: &rusqlite::Row) -> rusqlite::Result<StoredMessage> {
    Ok(StoredMessage {
        id: row.get(0)?,
        peer_id: row.get(1)?,
        outgoing: row.get::<_, i64>(2)? != 0,
        sender_id: row.get(3)?,
        payload_json: row.get(4)?,
        status: row.get(5)?,
        ts: row.get(6)?,
        reactions_json: row.get(7)?,
        edited: row.get::<_, i64>(8)? != 0,
        deleted: row.get::<_, i64>(9)? != 0,
    })
}

const MSG_COLS: &str =
    "id, peer_id, outgoing, sender_id, payload_json, status, ts, reactions_json, edited, deleted";

#[uniffi::export]
impl CoreStore {
    /// path — файл БД (например, Documents/aether.sqlite). ":memory:" для тестов.
    /// encryption_key_b64 — ключ SQLCipher (32 байта b64url из Keychain/Keystore);
    /// None — открытая база (тесты/отладка). Существующая НЕзашифрованная база
    /// при первом открытии с ключом мигрируется в шифрованную прозрачно.
    #[uniffi::constructor]
    pub fn open(path: String, encryption_key_b64: Option<String>) -> Result<Self, CoreError> {
        if let Some(key) = &encryption_key_b64 {
            Self::migrate_plaintext_if_needed(&path, key)?;
        }
        let conn = Connection::open(&path).map_err(CoreError::store)?;
        if let Some(key) = &encryption_key_b64 {
            // PRAGMA key ДО любого обращения к данным; ключ — hex, чтобы не
            // зависеть от экранирования строк.
            let hex = hex_key(key)?;
            conn.execute_batch(&format!("PRAGMA key = \"x'{hex}'\";"))
                .map_err(CoreError::store)?;
            // Проверка ключа: любое чтение по зашифрованной базе с неверным
            // ключом падает здесь, а не глубже в приложении.
            conn.query_row("SELECT count(*) FROM sqlite_master", [], |_| Ok(()))
                .map_err(|_| CoreError::Store { msg: "БД зашифрована другим ключом".into() })?;
        }
        conn.execute_batch("PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;")
            .map_err(CoreError::store)?;
        // Миграция: старая group_keys(group_id PRIMARY KEY, key_b64) → эпохи.
        let legacy: bool = conn
            .query_row(
                "SELECT COUNT(*) FROM pragma_table_info('group_keys') WHERE name='epoch'",
                [],
                |r| r.get::<_, i64>(0),
            )
            .map(|n| n == 0)
            .unwrap_or(false);
        let had_table: bool = conn
            .query_row(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='group_keys'",
                [],
                |r| r.get::<_, i64>(0),
            )
            .map(|n| n > 0)
            .unwrap_or(false);
        if had_table && legacy {
            conn.execute_batch(
                "ALTER TABLE group_keys RENAME TO group_keys_old;
                 CREATE TABLE group_keys (
                     group_id TEXT NOT NULL,
                     epoch INTEGER NOT NULL DEFAULT 0,
                     key_b64 TEXT NOT NULL,
                     PRIMARY KEY (group_id, epoch)
                 );
                 INSERT INTO group_keys (group_id, epoch, key_b64)
                     SELECT group_id, 0, key_b64 FROM group_keys_old;
                 DROP TABLE group_keys_old;",
            )
            .map_err(CoreError::store)?;
        }
        conn.execute_batch(SCHEMA).map_err(CoreError::store)?;
        Self::migrate_olm_sessions(&conn)?;
        Ok(CoreStore { conn: Mutex::new(conn), listener: Mutex::new(None) })
    }

    /// Подписка на изменения (реактивный UI). Повторный вызов заменяет слушателя.
    pub fn set_listener(&self, listener: Box<dyn StoreListener>) {
        *self.listener.lock().unwrap() = Some(listener);
    }

    fn notify_messages(&self, peer_id: &str) {
        if let Some(l) = self.listener.lock().unwrap().as_ref() {
            l.on_messages_changed(peer_id.to_string());
        }
    }
    fn notify_chats(&self) {
        if let Some(l) = self.listener.lock().unwrap().as_ref() {
            l.on_chats_changed();
        }
    }
    fn peer_of(&self, id: &str) -> Option<String> {
        self.conn.lock().unwrap()
            .query_row("SELECT peer_id FROM messages WHERE id=?1", params![id], |r| r.get(0))
            .optional().ok().flatten()
    }

    // ---- Сообщения ----

    pub fn insert_message(&self, m: StoredMessage) -> Result<(), CoreError> {
        let peer = m.peer_id.clone();
        let c = self.conn.lock().unwrap();
        c.execute(
            "INSERT OR REPLACE INTO messages (id, peer_id, outgoing, sender_id, payload_json, status, ts, reactions_json, edited, deleted)
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10)",
            params![
                m.id, m.peer_id, m.outgoing as i64, m.sender_id, m.payload_json,
                m.status, m.ts, m.reactions_json, m.edited as i64, m.deleted as i64
            ],
        )?;
        drop(c);
        self.notify_messages(&peer);
        Ok(())
    }

    pub fn message_exists(&self, id: String) -> Result<bool, CoreError> {
        let c = self.conn.lock().unwrap();
        let n: i64 = c.query_row("SELECT COUNT(*) FROM messages WHERE id=?1", params![id], |r| r.get(0))?;
        Ok(n > 0)
    }

    pub fn get_message(&self, id: String) -> Result<Option<StoredMessage>, CoreError> {
        let c = self.conn.lock().unwrap();
        Ok(c.query_row(&format!("SELECT {MSG_COLS} FROM messages WHERE id=?1"), params![id], row_to_msg)
            .optional()?)
    }

    /// Страница истории с пиром: последние `limit` сообщений старше `before_ts`
    /// (before_ts=0 → самые свежие). Возвращается в хронологическом порядке (старые→новые).
    pub fn get_messages_for_peer(&self, peer_id: String, before_ts: i64, limit: u32) -> Result<Vec<StoredMessage>, CoreError> {
        let c = self.conn.lock().unwrap();
        let before = if before_ts <= 0 { i64::MAX } else { before_ts };
        let mut stmt = c.prepare(&format!(
            "SELECT {MSG_COLS} FROM messages WHERE peer_id=?1 AND ts<?2 ORDER BY ts DESC LIMIT ?3"
        ))?;
        let rows = stmt.query_map(params![peer_id, before, limit], row_to_msg)?;
        let mut out: Vec<StoredMessage> = rows.filter_map(Result::ok).collect();
        out.reverse();
        Ok(out)
    }

    /// Сообщения для резервной копии: по возрастанию ts, начиная СТРОГО после
    /// (after_ts, after_id) — пара нужна, чтобы сообщения с одинаковой меткой
    /// времени не выпадали из бэкапа и не дублировались.
    pub fn messages_for_backup(
        &self,
        after_ts: i64,
        after_id: String,
        limit: u32,
    ) -> Result<Vec<StoredMessage>, CoreError> {
        let c = self.conn.lock().unwrap();
        let mut stmt = c.prepare(&format!(
            "SELECT {MSG_COLS} FROM messages
             WHERE ts > ?1 OR (ts = ?1 AND id > ?2)
             ORDER BY ts ASC, id ASC LIMIT ?3"
        ))?;
        let rows = stmt.query_map(params![after_ts, after_id, limit], row_to_msg)?;
        Ok(rows.filter_map(Result::ok).collect())
    }

    pub fn update_reactions(&self, id: String, reactions_json: String) -> Result<(), CoreError> {
        self.conn.lock().unwrap()
            .execute("UPDATE messages SET reactions_json=?2 WHERE id=?1", params![id, reactions_json])?;
        if let Some(p) = self.peer_of(&id) { self.notify_messages(&p); }
        Ok(())
    }

    pub fn update_text(&self, id: String, payload_json: String) -> Result<(), CoreError> {
        self.conn.lock().unwrap()
            .execute("UPDATE messages SET payload_json=?2, edited=1 WHERE id=?1", params![id, payload_json])?;
        if let Some(p) = self.peer_of(&id) { self.notify_messages(&p); }
        Ok(())
    }

    /// Обновить payload без пометки «изменено» (для досылки медиа после загрузки).
    pub fn update_payload(&self, id: String, payload_json: String) -> Result<(), CoreError> {
        self.conn.lock().unwrap()
            .execute("UPDATE messages SET payload_json=?2 WHERE id=?1", params![id, payload_json])?;
        if let Some(p) = self.peer_of(&id) { self.notify_messages(&p); }
        Ok(())
    }

    pub fn mark_deleted(&self, id: String) -> Result<(), CoreError> {
        self.conn.lock().unwrap()
            .execute("UPDATE messages SET deleted=1 WHERE id=?1", params![id])?;
        if let Some(p) = self.peer_of(&id) { self.notify_messages(&p); }
        Ok(())
    }

    pub fn update_status(&self, id: String, status: i32) -> Result<(), CoreError> {
        self.conn.lock().unwrap()
            .execute("UPDATE messages SET status=?2 WHERE id=?1", params![id, status])?;
        if let Some(p) = self.peer_of(&id) { self.notify_messages(&p); }
        Ok(())
    }

    /// Заменить локальный id на серверный (после успешной отправки), выставив статус.
    /// Если серверный id уже есть (эхо из inbox) — просто удалить локальную запись.
    // --- Исчезающие сообщения -------------------------------------------------

    pub fn ephemeral_set(&self, e: EphemeralState) -> Result<(), CoreError> {
        let c = self.conn.lock().unwrap();
        c.execute(
            "INSERT INTO ephemeral_state (message_id, state, opened_ts, expires_ts, views)
             VALUES (?1,?2,?3,?4,?5)
             ON CONFLICT(message_id) DO UPDATE SET
                state=excluded.state,
                opened_ts=COALESCE(ephemeral_state.opened_ts, excluded.opened_ts),
                expires_ts=excluded.expires_ts,
                views=excluded.views",
            params![e.message_id, e.state, e.opened_ts, e.expires_ts, e.views],
        )?;
        Ok(())
    }

    pub fn ephemeral_get(&self, message_id: String) -> Result<Option<EphemeralState>, CoreError> {
        let c = self.conn.lock().unwrap();
        let mut st = c.prepare(
            "SELECT message_id, state, opened_ts, expires_ts, views
             FROM ephemeral_state WHERE message_id=?1")?;
        let mut rows = st.query(params![message_id])?;
        if let Some(row) = rows.next()? {
            return Ok(Some(EphemeralState {
                message_id: row.get(0)?,
                state: row.get(1)?,
                opened_ts: row.get(2)?,
                expires_ts: row.get(3)?,
                views: row.get(4)?,
            }));
        }
        Ok(None)
    }

    /// Сообщения, чей срок уже вышел, но содержимое ещё не стёрто.
    pub fn ephemeral_due(&self, now_ms: i64) -> Result<Vec<String>, CoreError> {
        let c = self.conn.lock().unwrap();
        let mut st = c.prepare(
            "SELECT message_id FROM ephemeral_state
             WHERE expires_ts IS NOT NULL AND expires_ts <= ?1 AND state <> 'PURGED'
             ORDER BY expires_ts")?;
        let rows = st.query_map(params![now_ms], |r| r.get::<_, String>(0))?;
        Ok(rows.filter_map(Result::ok).collect())
    }

    /// Стереть содержимое, оставив в истории отметку.
    ///
    /// Строка сообщения не удаляется, а payload заменяется надгробием: в чате
    /// должно остаться «сообщение истекло», а не дырка, из-за которой человек
    /// решит, что ничего и не было. Само содержимое при этом стирается
    /// физически — включая текст, ключи медиа и реакции.
    pub fn ephemeral_purge(&self, message_id: String) -> Result<(), CoreError> {
        let peer = self.peer_of(&message_id);
        let c = self.conn.lock().unwrap();
        c.execute(
            "UPDATE messages SET payload_json = ?2, reactions_json = '{}', edited = 0
             WHERE id = ?1",
            params![message_id, r#"{"type":"expired"}"#],
        )?;
        c.execute(
            "UPDATE ephemeral_state SET state='PURGED', expires_ts=NULL WHERE message_id=?1",
            params![message_id],
        )?;
        drop(c);
        if let Some(p) = peer { self.notify_messages(&p); }
        Ok(())
    }

    // --- Политика доставки ---------------------------------------------------

    /// Политика чата. Записи нет — отдаём умолчание, а не ошибку: чат мог
    /// существовать задолго до появления политик.
    pub fn chat_policy(&self, peer_id: String) -> Result<ChatDeliveryPolicy, CoreError> {
        let c = self.conn.lock().unwrap();
        let mut st = c.prepare(
            "SELECT peer_id, delivery_mode, transport_order, server_storage, updated_ts
             FROM chat_delivery_policy WHERE peer_id=?1",
        )?;
        let mut rows = st.query(params![peer_id.to_lowercase()])?;
        if let Some(row) = rows.next()? {
            return Ok(ChatDeliveryPolicy {
                peer_id: row.get(0)?,
                delivery_mode: row.get(1)?,
                transport_order: row.get(2)?,
                server_storage: row.get(3)?,
                updated_ts: row.get(4)?,
            });
        }
        Ok(ChatDeliveryPolicy::default_for(&peer_id.to_lowercase()))
    }

    pub fn set_chat_policy(&self, p: ChatDeliveryPolicy) -> Result<(), CoreError> {
        let c = self.conn.lock().unwrap();
        c.execute(
            "INSERT INTO chat_delivery_policy
                (peer_id, delivery_mode, transport_order, server_storage, updated_ts)
             VALUES (?1,?2,?3,?4,?5)
             ON CONFLICT(peer_id) DO UPDATE SET
                delivery_mode=excluded.delivery_mode,
                transport_order=excluded.transport_order,
                server_storage=excluded.server_storage,
                updated_ts=excluded.updated_ts",
            params![p.peer_id.to_lowercase(), p.delivery_mode, p.transport_order,
                    p.server_storage, p.updated_ts],
        )?;
        Ok(())
    }

    /// Разрешено ли отдавать серверу контент такой категории.
    /// Отсутствие записи = разрешено: иначе включение функции молча запретило бы всё.
    pub fn server_allows(&self, server_id: String, content_kind: String) -> Result<bool, CoreError> {
        let c = self.conn.lock().unwrap();
        let mut st = c.prepare(
            "SELECT allowed FROM server_storage_policy WHERE server_id=?1 AND content_kind=?2",
        )?;
        let mut rows = st.query(params![server_id, content_kind])?;
        match rows.next()? {
            Some(row) => Ok(row.get::<_, i64>(0)? != 0),
            None => Ok(true),
        }
    }

    pub fn set_server_allows(&self, server_id: String, content_kind: String,
                             allowed: bool) -> Result<(), CoreError> {
        let c = self.conn.lock().unwrap();
        c.execute(
            "INSERT INTO server_storage_policy (server_id, content_kind, allowed)
             VALUES (?1,?2,?3)
             ON CONFLICT(server_id, content_kind) DO UPDATE SET allowed=excluded.allowed",
            params![server_id, content_kind, allowed as i64],
        )?;
        Ok(())
    }

    // --- Маршрут доставки -----------------------------------------------------

    /// Записать, чем сообщение реально ушло. Вызывается ПОСЛЕ подтверждения:
    /// до него маршрут ещё не известен, а попытки лежат в отдельной таблице.
    pub fn set_route(&self, r: MessageRoute) -> Result<(), CoreError> {
        let c = self.conn.lock().unwrap();
        c.execute(
            "INSERT INTO message_route
                (message_id, transport, physical, server_id, server_stored, delivered_ts, read_ts)
             VALUES (?1,?2,?3,?4,?5,?6,?7)
             ON CONFLICT(message_id) DO UPDATE SET
                transport=excluded.transport, physical=excluded.physical,
                server_id=excluded.server_id, server_stored=excluded.server_stored,
                delivered_ts=COALESCE(excluded.delivered_ts, message_route.delivered_ts),
                read_ts=COALESCE(excluded.read_ts, message_route.read_ts)",
            params![r.message_id, r.transport, r.physical, r.server_id,
                    r.server_stored as i64, r.delivered_ts, r.read_ts],
        )?;
        Ok(())
    }

    pub fn route_for(&self, message_id: String) -> Result<Option<MessageRoute>, CoreError> {
        let c = self.conn.lock().unwrap();
        let mut st = c.prepare(
            "SELECT message_id, transport, physical, server_id, server_stored, delivered_ts, read_ts
             FROM message_route WHERE message_id=?1",
        )?;
        let mut rows = st.query(params![message_id])?;
        if let Some(row) = rows.next()? {
            return Ok(Some(MessageRoute {
                message_id: row.get(0)?,
                transport: row.get(1)?,
                physical: row.get(2)?,
                server_id: row.get(3)?,
                server_stored: row.get::<_, i64>(4)? != 0,
                delivered_ts: row.get(5)?,
                read_ts: row.get(6)?,
            }));
        }
        Ok(None)
    }

    /// Добавить попытку. Номер назначается сам — вызывающему не нужно
    /// помнить, сколько их уже было.
    pub fn add_delivery_attempt(&self, message_id: String, transport: String,
                                device_id: Option<String>, started_ts: i64) -> Result<i32, CoreError> {
        let c = self.conn.lock().unwrap();
        let next: i32 = c.query_row(
            "SELECT COALESCE(MAX(attempt), 0) + 1 FROM message_delivery_attempts WHERE message_id=?1",
            params![message_id], |r| r.get(0))?;
        c.execute(
            "INSERT INTO message_delivery_attempts
                (message_id, attempt, transport, device_id, started_ts)
             VALUES (?1,?2,?3,?4,?5)",
            params![message_id, next, transport, device_id, started_ts],
        )?;
        Ok(next)
    }

    pub fn finish_delivery_attempt(&self, message_id: String, attempt: i32, outcome: String,
                                   detail: Option<String>, finished_ts: i64) -> Result<(), CoreError> {
        let c = self.conn.lock().unwrap();
        c.execute(
            "UPDATE message_delivery_attempts SET outcome=?3, detail=?4, finished_ts=?5
             WHERE message_id=?1 AND attempt=?2",
            params![message_id, attempt, outcome, detail, finished_ts],
        )?;
        Ok(())
    }

    pub fn delivery_attempts(&self, message_id: String) -> Result<Vec<DeliveryAttempt>, CoreError> {
        let c = self.conn.lock().unwrap();
        let mut st = c.prepare(
            "SELECT message_id, attempt, transport, device_id, started_ts, finished_ts, outcome, detail
             FROM message_delivery_attempts WHERE message_id=?1 ORDER BY attempt",
        )?;
        let rows = st.query_map(params![message_id], |row| {
            Ok(DeliveryAttempt {
                message_id: row.get(0)?,
                attempt: row.get(1)?,
                transport: row.get(2)?,
                device_id: row.get(3)?,
                started_ts: row.get(4)?,
                finished_ts: row.get(5)?,
                outcome: row.get(6)?,
                detail: row.get(7)?,
            })
        })?;
        Ok(rows.filter_map(Result::ok).collect())
    }

    pub fn replace_message_id(&self, old_id: String, new_id: String, status: i32) -> Result<(), CoreError> {
        let c = self.conn.lock().unwrap();
        let exists: i64 = c.query_row("SELECT COUNT(*) FROM messages WHERE id=?1", params![new_id], |r| r.get(0))?;
        if exists > 0 {
            c.execute("DELETE FROM messages WHERE id=?1", params![old_id])?;
        } else {
            c.execute("UPDATE messages SET id=?2, status=?3 WHERE id=?1", params![old_id, new_id, status])?;
        }
        drop(c);
        if let Some(p) = self.peer_of(&new_id) { self.notify_messages(&p); }
        Ok(())
    }

    /// Пометить все исходящие в чате как read/delivered, но не понижать статус.
    pub fn mark_outgoing_status(&self, peer_id: String, status: i32) -> Result<(), CoreError> {
        self.conn.lock().unwrap().execute(
            "UPDATE messages SET status=?2 WHERE peer_id=?1 AND outgoing=1 AND status>=1 AND status<?2",
            params![peer_id, status],
        )?;
        self.notify_messages(&peer_id);
        Ok(())
    }

    pub fn get_pending_outgoing(&self) -> Result<Vec<StoredMessage>, CoreError> {
        let c = self.conn.lock().unwrap();
        let mut stmt = c.prepare(&format!(
            "SELECT {MSG_COLS} FROM messages WHERE outgoing=1 AND status=0 ORDER BY ts ASC"
        ))?;
        let rows = stmt.query_map([], row_to_msg)?;
        Ok(rows.filter_map(Result::ok).collect())
    }

    // ---- Чаты ----

    pub fn upsert_chat(&self, chat: Chat) -> Result<(), CoreError> {
        // Сохраняем флаги (pinned/muted/archived), если чат уже есть.
        self.conn.lock().unwrap().execute(
            "INSERT INTO chats (peer_id, is_group, title, last_text, last_ts, unread, pinned, muted, archived)
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9)
             ON CONFLICT(peer_id) DO UPDATE SET
               is_group=excluded.is_group,
               title=CASE WHEN excluded.title != '' THEN excluded.title ELSE chats.title END,
               last_text=excluded.last_text,
               last_ts=excluded.last_ts,
               unread=excluded.unread",
            params![
                chat.peer_id, chat.is_group as i64, chat.title, chat.last_text, chat.last_ts,
                chat.unread, chat.pinned as i64, chat.muted as i64, chat.archived as i64
            ],
        )?;
        self.notify_chats();
        Ok(())
    }

    /// Обновить превью и время последнего сообщения; при need_unread — инкремент непрочитанных.
    pub fn touch_chat(&self, peer_id: String, is_group: bool, title: String, last_text: String, last_ts: i64, inc_unread: bool) -> Result<(), CoreError> {
        let c = self.conn.lock().unwrap();
        c.execute(
            "INSERT INTO chats (peer_id, is_group, title, last_text, last_ts, unread)
             VALUES (?1,?2,?3,?4,?5,?6)
             ON CONFLICT(peer_id) DO UPDATE SET
               is_group=excluded.is_group,
               title=CASE WHEN excluded.title != '' THEN excluded.title ELSE chats.title END,
               last_text=excluded.last_text,
               last_ts=MAX(chats.last_ts, excluded.last_ts),
               unread=chats.unread + ?6",
            params![peer_id, is_group as i64, title, last_text, last_ts, if inc_unread { 1 } else { 0 }],
        )?;
        self.notify_chats();
        Ok(())
    }

    pub fn get_chat_list(&self) -> Result<Vec<Chat>, CoreError> {
        let c = self.conn.lock().unwrap();
        let mut stmt = c.prepare(
            "SELECT peer_id, is_group, title, last_text, last_ts, unread, pinned, muted, archived
             FROM chats ORDER BY pinned DESC, last_ts DESC",
        )?;
        let rows = stmt.query_map([], |row| {
            Ok(Chat {
                peer_id: row.get(0)?,
                is_group: row.get::<_, i64>(1)? != 0,
                title: row.get(2)?,
                last_text: row.get(3)?,
                last_ts: row.get(4)?,
                unread: row.get(5)?,
                pinned: row.get::<_, i64>(6)? != 0,
                muted: row.get::<_, i64>(7)? != 0,
                archived: row.get::<_, i64>(8)? != 0,
            })
        })?;
        Ok(rows.filter_map(Result::ok).collect())
    }

    pub fn set_pinned(&self, peer_id: String, v: bool) -> Result<(), CoreError> {
        self.conn.lock().unwrap().execute("UPDATE chats SET pinned=?2 WHERE peer_id=?1", params![peer_id, v as i64])?;
        self.notify_chats();
        Ok(())
    }
    pub fn set_muted(&self, peer_id: String, v: bool) -> Result<(), CoreError> {
        self.conn.lock().unwrap().execute("UPDATE chats SET muted=?2 WHERE peer_id=?1", params![peer_id, v as i64])?;
        self.notify_chats();
        Ok(())
    }
    pub fn set_archived(&self, peer_id: String, v: bool) -> Result<(), CoreError> {
        self.conn.lock().unwrap().execute("UPDATE chats SET archived=?2 WHERE peer_id=?1", params![peer_id, v as i64])?;
        self.notify_chats();
        Ok(())
    }
    pub fn clear_unread(&self, peer_id: String) -> Result<(), CoreError> {
        self.conn.lock().unwrap().execute("UPDATE chats SET unread=0 WHERE peer_id=?1", params![peer_id])?;
        self.notify_chats();
        Ok(())
    }
    pub fn total_unread(&self) -> Result<i64, CoreError> {
        let c = self.conn.lock().unwrap();
        Ok(c.query_row("SELECT COALESCE(SUM(unread),0) FROM chats WHERE muted=0", [], |r| r.get(0))?)
    }
    pub fn delete_chat(&self, peer_id: String) -> Result<(), CoreError> {
        let c = self.conn.lock().unwrap();
        c.execute("DELETE FROM messages WHERE peer_id=?1", params![peer_id])?;
        c.execute("DELETE FROM chats WHERE peer_id=?1", params![peer_id])?;
        self.notify_chats();
        Ok(())
    }

    // ---- Ключи групп (эпохи: ротация при изменении состава) ----

    /// Сохранить ключ группы. Если он совпадает с текущим — no-op; если новый —
    /// становится следующей эпохой (старые ключи остаются читать старые сообщения).
    pub fn set_group_key(&self, group_id: String, key_b64: String) -> Result<(), CoreError> {
        let c = self.conn.lock().unwrap();
        let latest: Option<String> = c
            .query_row(
                "SELECT key_b64 FROM group_keys WHERE group_id=?1 ORDER BY epoch DESC LIMIT 1",
                params![group_id],
                |r| r.get(0),
            )
            .optional()?;
        if latest.as_deref() == Some(key_b64.as_str()) {
            return Ok(());
        }
        c.execute(
            "INSERT INTO group_keys (group_id, epoch, key_b64)
             VALUES (?1, COALESCE((SELECT MAX(epoch)+1 FROM group_keys WHERE group_id=?1), 0), ?2)",
            params![group_id, key_b64],
        )?;
        Ok(())
    }

    /// Актуальный (последней эпохи) ключ — им шифруются новые сообщения.
    pub fn get_group_key(&self, group_id: String) -> Result<Option<String>, CoreError> {
        let c = self.conn.lock().unwrap();
        Ok(c.query_row(
            "SELECT key_b64 FROM group_keys WHERE group_id=?1 ORDER BY epoch DESC LIMIT 1",
            params![group_id],
            |r| r.get(0),
        )
        .optional()?)
    }

    /// Все известные ключи группы, новые эпохи первыми — для расшифровки истории.
    pub fn get_group_keys(&self, group_id: String) -> Result<Vec<String>, CoreError> {
        let c = self.conn.lock().unwrap();
        let mut stmt =
            c.prepare("SELECT key_b64 FROM group_keys WHERE group_id=?1 ORDER BY epoch DESC")?;
        let rows = stmt.query_map(params![group_id], |r| r.get::<_, String>(0))?;
        Ok(rows.filter_map(Result::ok).collect())
    }

    // ---- TOFU-пины ----

    pub fn pin_get(&self, peer_id: String) -> Result<Option<KeyPin>, CoreError> {
        let c = self.conn.lock().unwrap();
        Ok(c.query_row(
            "SELECT peer_id, public_key_b64, verified, first_seen FROM pins WHERE peer_id=?1",
            params![peer_id],
            |row| Ok(KeyPin {
                peer_id: row.get(0)?,
                public_key_b64: row.get(1)?,
                verified: row.get::<_, i64>(2)? != 0,
                first_seen: row.get(3)?,
            }),
        ).optional()?)
    }

    /// Записать/обновить пин. Возвращает true, если ключ ИЗМЕНИЛСЯ (возможная MITM-тревога).
    pub fn pin_upsert(&self, peer_id: String, public_key_b64: String, first_seen: i64) -> Result<bool, CoreError> {
        let existing = self.pin_get(peer_id.clone())?;
        let changed = existing.as_ref().map_or(false, |p| p.public_key_b64 != public_key_b64);
        let c = self.conn.lock().unwrap();
        match existing {
            Some(_) if changed => {
                c.execute("UPDATE pins SET public_key_b64=?2, verified=0, first_seen=?3 WHERE peer_id=?1",
                    params![peer_id, public_key_b64, first_seen])?;
            }
            None => {
                c.execute("INSERT INTO pins (peer_id, public_key_b64, verified, first_seen) VALUES (?1,?2,0,?3)",
                    params![peer_id, public_key_b64, first_seen])?;
            }
            _ => {}
        }
        Ok(changed)
    }
    pub fn pin_set_verified(&self, peer_id: String, verified: bool) -> Result<(), CoreError> {
        self.conn.lock().unwrap().execute("UPDATE pins SET verified=?2 WHERE peer_id=?1", params![peer_id, verified as i64])?;
        Ok(())
    }
    pub fn pin_delete(&self, peer_id: String) -> Result<(), CoreError> {
        self.conn.lock().unwrap().execute("DELETE FROM pins WHERE peer_id=?1", params![peer_id])?;
        Ok(())
    }

    // ---- TOFU-пины мастер-ключа аккаунта (cross-signing, P8) ----

    pub fn master_pin_get(&self, peer_id: String) -> Result<Option<MasterPin>, CoreError> {
        let c = self.conn.lock().unwrap();
        Ok(c.query_row(
            "SELECT peer_id, master_key_b64, verified, first_seen, prev_master_key_b64, changed_ts
             FROM master_pins WHERE peer_id=?1",
            params![peer_id.to_lowercase()],
            |row| Ok(MasterPin {
                peer_id: row.get(0)?,
                master_key_b64: row.get(1)?,
                verified: row.get::<_, i64>(2)? != 0,
                first_seen: row.get(3)?,
                prev_master_key_b64: row.get(4)?,
                changed_ts: row.get(5)?,
            }),
        ).optional()?)
    }

    /// TOFU-гейт мастер-ключа. Mismatch пин НЕ трогает — принять новый мастер
    /// можно только явным master_pin_accept (смена пароля/восстановление аккаунта
    /// пира тоже приводит сюда: событие, которое пользователь должен подтвердить).
    pub fn master_pin_check(
        &self,
        peer_id: String,
        master_key_b64: String,
        now_ts: i64,
    ) -> Result<OlmPinStatus, CoreError> {
        let key = peer_id.to_lowercase();
        // Сравниваем канонические формы: иначе сервер переписал бы кодировку
        // того же ключа и «сменил» мастер, не сломав ни одной подписи.
        let master_key_b64 = canon_key(&master_key_b64)?;
        let existing = self.master_pin_get(key.clone())?;
        let c = self.conn.lock().unwrap();
        match existing {
            None => {
                c.execute(
                    "INSERT INTO master_pins (peer_id, master_key_b64, verified, first_seen)
                     VALUES (?1, ?2, 0, ?3)",
                    params![key, master_key_b64, now_ts],
                )?;
                Ok(OlmPinStatus::FirstUse)
            }
            Some(pin) if pin.master_key_b64 == master_key_b64 => Ok(OlmPinStatus::Match),
            Some(_) => Ok(OlmPinStatus::Mismatch),
        }
    }

    pub fn master_pin_accept(
        &self,
        peer_id: String,
        master_key_b64: String,
        now_ts: i64,
    ) -> Result<(), CoreError> {
        let key = peer_id.to_lowercase();
        let master_key_b64 = canon_key(&master_key_b64)?;
        let existing = self.master_pin_get(key.clone())?;
        let c = self.conn.lock().unwrap();
        match existing {
            Some(old) => {
                c.execute(
                    "UPDATE master_pins SET master_key_b64=?2, verified=0,
                            prev_master_key_b64=?3, changed_ts=?4 WHERE peer_id=?1",
                    params![key, master_key_b64, old.master_key_b64, now_ts],
                )?;
            }
            None => {
                c.execute(
                    "INSERT INTO master_pins (peer_id, master_key_b64, verified, first_seen)
                     VALUES (?1, ?2, 0, ?3)",
                    params![key, master_key_b64, now_ts],
                )?;
            }
        }
        Ok(())
    }

    pub fn master_pin_set_verified(&self, peer_id: String, verified: bool) -> Result<(), CoreError> {
        self.conn.lock().unwrap().execute(
            "UPDATE master_pins SET verified=?2 WHERE peer_id=?1",
            params![peer_id.to_lowercase(), verified as i64],
        )?;
        Ok(())
    }

    // ---- TOFU-пины olm-identity (Double Ratchet, SEC HIGH-2) ----

    pub fn olm_pin_get(&self, peer_key: String) -> Result<Option<OlmPin>, CoreError> {
        let c = self.conn.lock().unwrap();
        Ok(c.query_row(
            "SELECT peer_key, curve25519_b64, ed25519_b64, verified, first_seen,
                    prev_curve25519_b64, prev_ed25519_b64, changed_ts
             FROM olm_pins WHERE peer_key=?1",
            params![peer_key.to_lowercase()],
            |row| Ok(OlmPin {
                peer_key: row.get(0)?,
                curve25519_b64: row.get(1)?,
                ed25519_b64: row.get(2)?,
                verified: row.get::<_, i64>(3)? != 0,
                first_seen: row.get(4)?,
                prev_curve25519_b64: row.get(5)?,
                prev_ed25519_b64: row.get(6)?,
                changed_ts: row.get(7)?,
            }),
        ).optional()?)
    }

    /// TOFU-гейт: сверить olm-identity устройства с пином (и запинить при первом контакте).
    /// ed25519_b64 = None на входящих (в конверте только curve25519) — тогда сверяется
    /// лишь curve-ключ; при Mismatch пин НЕ трогается. Анти-даунгрейд «бандл без подписи,
    /// хотя ed25519 уже запинен» — ответственность вызывающего (см. olm_pin_get).
    pub fn olm_pin_check(
        &self,
        peer_key: String,
        curve25519_b64: String,
        ed25519_b64: Option<String>,
        now_ts: i64,
    ) -> Result<OlmPinStatus, CoreError> {
        let key = peer_key.to_lowercase();
        let curve25519_b64 = canon_key(&curve25519_b64)?;
        let ed25519_b64 = ed25519_b64.map(|k| canon_key(&k)).transpose()?;
        let existing = self.olm_pin_get(key.clone())?;
        let c = self.conn.lock().unwrap();
        match existing {
            None => {
                c.execute(
                    "INSERT INTO olm_pins (peer_key, curve25519_b64, ed25519_b64, verified, first_seen)
                     VALUES (?1, ?2, ?3, 0, ?4)",
                    params![key, curve25519_b64, ed25519_b64, now_ts],
                )?;
                Ok(OlmPinStatus::FirstUse)
            }
            Some(pin) => {
                if pin.curve25519_b64 != curve25519_b64 {
                    return Ok(OlmPinStatus::Mismatch);
                }
                match (&pin.ed25519_b64, &ed25519_b64) {
                    (Some(pinned), Some(seen)) if pinned != seen => Ok(OlmPinStatus::Mismatch),
                    (None, Some(seen)) => {
                        c.execute(
                            "UPDATE olm_pins SET ed25519_b64=?2 WHERE peer_key=?1",
                            params![key, seen],
                        )?;
                        Ok(OlmPinStatus::Match)
                    }
                    _ => Ok(OlmPinStatus::Match),
                }
            }
        }
    }

    /// Явное принятие нового ключа пользователем (после Mismatch). Старый пин уходит
    /// в prev_*, verified сбрасывается. Тихих перепинов в ядре нет — только этот путь.
    pub fn olm_pin_accept(
        &self,
        peer_key: String,
        curve25519_b64: String,
        ed25519_b64: Option<String>,
        now_ts: i64,
    ) -> Result<(), CoreError> {
        let key = peer_key.to_lowercase();
        let curve25519_b64 = canon_key(&curve25519_b64)?;
        let ed25519_b64 = ed25519_b64.map(|k| canon_key(&k)).transpose()?;
        let existing = self.olm_pin_get(key.clone())?;
        let c = self.conn.lock().unwrap();
        match existing {
            Some(old) => {
                // COALESCE: принятие НЕ понижает доверие. Стереть проверенный
                // ed25519 в NULL значило бы разоружить анти-стриппинг (инвариант 5),
                // а такой путь достижим через «залипшую» тревогу о неподписанном
                // устройстве, которое уже успело стать cross-signed.
                c.execute(
                    "UPDATE olm_pins SET curve25519_b64=?2,
                            ed25519_b64=COALESCE(?3, ed25519_b64), verified=0,
                            prev_curve25519_b64=?4, prev_ed25519_b64=?5, changed_ts=?6
                     WHERE peer_key=?1",
                    params![key, curve25519_b64, ed25519_b64,
                            old.curve25519_b64, old.ed25519_b64, now_ts],
                )?;
            }
            None => {
                c.execute(
                    "INSERT INTO olm_pins (peer_key, curve25519_b64, ed25519_b64, verified, first_seen)
                     VALUES (?1, ?2, ?3, 0, ?4)",
                    params![key, curve25519_b64, ed25519_b64, now_ts],
                )?;
            }
        }
        Ok(())
    }

    pub fn olm_pin_set_verified(&self, peer_key: String, verified: bool) -> Result<(), CoreError> {
        self.conn.lock().unwrap().execute(
            "UPDATE olm_pins SET verified=?2 WHERE peer_key=?1",
            params![peer_key.to_lowercase(), verified as i64],
        )?;
        Ok(())
    }

    pub fn olm_pin_delete(&self, peer_key: String) -> Result<(), CoreError> {
        self.conn.lock().unwrap().execute(
            "DELETE FROM olm_pins WHERE peer_key=?1",
            params![peer_key.to_lowercase()],
        )?;
        Ok(())
    }

    /// Снять ВСЁ производное доверие к пиру: device-пины и Olm-сессии всех его
    /// устройств (принятие нового мастер-ключа). Не требует сети и списка
    /// устройств — чистит по префиксу ключа "peer" / "peer::device".
    pub fn peer_trust_reset(&self, peer_id: String) -> Result<(), CoreError> {
        let peer = peer_id.to_lowercase();
        // В user_id разрешён '_', а в LIKE это подстановочный символ: без
        // экранирования сброс доверия к "bob_1" сносил бы пины постороннего "bobX1".
        let escaped: String = peer
            .chars()
            .flat_map(|ch| match ch {
                '\\' | '%' | '_' => vec!['\\', ch],
                ch => vec![ch],
            })
            .collect();
        let prefix = format!("{escaped}::%");
        let c = self.conn.lock().unwrap();
        c.execute(
            r"DELETE FROM olm_pins WHERE peer_key=?1 OR peer_key LIKE ?2 ESCAPE '\'",
            params![peer, prefix],
        )?;
        c.execute(
            r"DELETE FROM olm_sessions WHERE peer_id=?1 OR peer_id LIKE ?2 ESCAPE '\'",
            params![peer, prefix],
        )?;
        Ok(())
    }

    // ---- Meta (курсор inbox и пр.) ----

    pub fn meta_get(&self, key: String) -> Result<Option<String>, CoreError> {
        let c = self.conn.lock().unwrap();
        Ok(c.query_row("SELECT v FROM meta WHERE k=?1", params![key], |r| r.get(0)).optional()?)
    }
    pub fn meta_set(&self, key: String, value: String) -> Result<(), CoreError> {
        self.conn.lock().unwrap()
            .execute("INSERT OR REPLACE INTO meta (k,v) VALUES (?1,?2)", params![key, value])?;
        Ok(())
    }

    /// Все Olm-сессии с устройством пира, свежая первой (SEC MED-4).
    ///
    /// Сессий несколько намеренно. Когда обе стороны начинают переписку
    /// одновременно, каждая заводит свою исходящую — раньше входящий prekey
    /// затирал единственную строку, и всё, что собеседник уже зашифровал в
    /// затёртой сессии, становилось невскрываемым навсегда. Теперь живут обе:
    /// приём перебирает их, отправка берёт самую свежую.
    ///
    /// Порядок: свежая первой, при равном ts — по session_id, чтобы выбор
    /// отправляющей сессии был детерминирован и не «дрожал» между запусками.
    pub fn olm_sessions_for(&self, peer_id: String) -> Result<Vec<OlmSession>, CoreError> {
        let c = self.conn.lock().unwrap();
        let mut st = c.prepare(
            "SELECT session_id, session_json, updated_ts FROM olm_sessions WHERE peer_id=?1
             ORDER BY updated_ts DESC, session_id ASC",
        )?;
        let rows = st.query_map(params![peer_id.to_lowercase()], |r| {
            Ok(OlmSession { session_id: r.get(0)?, session_json: r.get(1)?, updated_ts: r.get(2)? })
        })?;
        Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
    }

    /// Сохранить/обновить сессию. Помимо записи подрезает историю до
    /// `MAX_SESSIONS_PER_PEER`: без лимита пир (или сервер от его имени) мог бы
    /// потоком prekey-конвертов раздувать базу без ограничений.
    pub fn olm_session_put(
        &self,
        peer_id: String,
        session_id: String,
        session_json: String,
    ) -> Result<(), CoreError> {
        let peer = peer_id.to_lowercase();
        let ts = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0);
        let c = self.conn.lock().unwrap();
        c.execute(
            "INSERT INTO olm_sessions (peer_id, session_id, session_json, updated_ts)
             VALUES (?1,?2,?3,?4)
             ON CONFLICT (peer_id, session_id) DO UPDATE
             SET session_json=excluded.session_json, updated_ts=excluded.updated_ts",
            params![peer, session_id, session_json, ts],
        )?;
        c.execute(
            "DELETE FROM olm_sessions WHERE peer_id=?1 AND session_id NOT IN
             (SELECT session_id FROM olm_sessions WHERE peer_id=?1
              ORDER BY updated_ts DESC, session_id ASC LIMIT ?2)",
            params![peer, MAX_SESSIONS_PER_PEER],
        )?;
        Ok(())
    }

    /// Забыть ВСЕ сессии с устройством (принятие нового ключа пира: старые
    /// сессии мертвы, новая установится свежим prekey-обменом).
    pub fn olm_session_delete(&self, peer_id: String) -> Result<(), CoreError> {
        self.conn.lock().unwrap().execute(
            "DELETE FROM olm_sessions WHERE peer_id=?1",
            params![peer_id.to_lowercase()],
        )?;
        Ok(())
    }

    /// Самая свежая сессия. Совместимость с однососессионными вызывающими
    /// (ветка android до порта мультисессий) — новый код ходит через
    /// `olm_sessions_for`, чтобы перебрать все.
    pub fn olm_session_get(&self, peer_id: String) -> Result<Option<String>, CoreError> {
        Ok(self.olm_sessions_for(peer_id)?.into_iter().next().map(|s| s.session_json))
    }

    /// Совместимая запись: session_id вычисляется из самого pickle, поэтому
    /// старый вызывающий не затирает чужую сессию, а обновляет свою.
    pub fn olm_session_set(&self, peer_id: String, session_json: String) -> Result<(), CoreError> {
        let session_id = aether_ratchet_core::session_id(&session_json)
            .map_err(|msg| CoreError::Crypto { msg })?;
        self.olm_session_put(peer_id, session_id, session_json)
    }
}

/// Канонизировать base64-ключ перед сравнением/записью в пин. Декодер намеренно
/// толерантен (оба алфавита, padding), поэтому один и тот же ключ приходит в
/// разных формах — а пины сравниваются строками.
fn canon_key(key_b64: &str) -> Result<String, CoreError> {
    aether_ratchet_core::canonical_key_b64(key_b64).map_err(|m| CoreError::Crypto { msg: m })
}

/// b64url-ключ (32 байта) → hex для PRAGMA key.
fn hex_key(key_b64: &str) -> Result<String, CoreError> {
    let bytes = crate::crypto::decode_b64(key_b64)?;
    if bytes.len() != 32 {
        return Err(CoreError::bad("ключ БД: ожидалось 32 байта"));
    }
    Ok(bytes.iter().map(|b| format!("{b:02x}")).collect())
}

impl CoreStore {
    /// Миграция: olm_sessions(peer_id PRIMARY KEY) → (peer_id, session_id) для
    /// мультисессий (P10). `CREATE TABLE IF NOT EXISTS` существующую таблицу не
    /// трогает, поэтому пересобираем вручную.
    ///
    /// session_id уже установленных сессий вычисляется из самого pickle — иначе
    /// первый же входящий prekey не нашёл бы соответствия и завёл дубль, впустую
    /// спалив одноразовый ключ. Нерасшифруемые (битые) pickle'ы уезжают под
    /// 'legacy': ключ таблицы был peer_id, так что столкнуться они не могут.
    fn migrate_olm_sessions(conn: &Connection) -> Result<(), CoreError> {
        let already: bool = conn
            .query_row(
                "SELECT COUNT(*) FROM pragma_table_info('olm_sessions') WHERE name='session_id'",
                [],
                |r| r.get::<_, i64>(0),
            )
            .map(|n| n > 0)
            .unwrap_or(false);
        if already {
            return Ok(());
        }
        let rows: Vec<(String, String, i64)> = {
            let mut st = conn
                .prepare("SELECT peer_id, session_json, updated_ts FROM olm_sessions")
                .map_err(CoreError::store)?;
            let it = st
                .query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))
                .map_err(CoreError::store)?;
            it.collect::<rusqlite::Result<Vec<_>>>().map_err(CoreError::store)?
        };
        conn.execute_batch(
            "DROP TABLE olm_sessions;
             CREATE TABLE olm_sessions (
                 peer_id TEXT NOT NULL,
                 session_id TEXT NOT NULL,
                 session_json TEXT NOT NULL,
                 updated_ts INTEGER NOT NULL DEFAULT 0,
                 PRIMARY KEY (peer_id, session_id)
             );",
        )
        .map_err(CoreError::store)?;
        for (peer, json, ts) in rows {
            let session_id = aether_ratchet_core::session_id(&json)
                .unwrap_or_else(|_| "legacy".to_owned());
            conn.execute(
                "INSERT OR REPLACE INTO olm_sessions (peer_id, session_id, session_json, updated_ts)
                 VALUES (?1,?2,?3,?4)",
                params![peer, session_id, json, ts],
            )
            .map_err(CoreError::store)?;
        }
        Ok(())
    }

    /// Если по пути лежит НЕзашифрованная база (создана до SQLCipher) —
    /// перешифровать её ключом через sqlcipher_export и подменить файл.
    fn migrate_plaintext_if_needed(path: &str, key_b64: &str) -> Result<(), CoreError> {
        if path == ":memory:" || !std::path::Path::new(path).exists() {
            return Ok(());
        }
        // Пробуем открыть БЕЗ ключа: получилось прочитать schema — база открытая.
        let plain_readable = Connection::open(path)
            .ok()
            .and_then(|c| c.query_row("SELECT count(*) FROM sqlite_master", [], |_| Ok(())).ok())
            .is_some();
        if !plain_readable {
            return Ok(()); // уже зашифрована (или повреждена — узнаем при открытии с ключом)
        }
        let hex = hex_key(key_b64)?;
        let tmp = format!("{path}.enc");
        let _ = std::fs::remove_file(&tmp);
        {
            let conn = Connection::open(path).map_err(CoreError::store)?;
            conn.execute_batch(&format!(
                "ATTACH DATABASE '{tmp}' AS encrypted KEY \"x'{hex}'\";
                 SELECT sqlcipher_export('encrypted');
                 DETACH DATABASE encrypted;"
            ))
            .map_err(CoreError::store)?;
        }
        // WAL/SHM старой базы больше не нужны.
        let _ = std::fs::remove_file(format!("{path}-wal"));
        let _ = std::fs::remove_file(format!("{path}-shm"));
        std::fs::rename(&tmp, path).map_err(CoreError::store)?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn store() -> CoreStore {
        CoreStore::open(":memory:".into(), None).unwrap()
    }

    fn msg(id: &str, peer: &str, ts: i64, outgoing: bool) -> StoredMessage {
        StoredMessage {
            id: id.into(), peer_id: peer.into(), outgoing, sender_id: if outgoing { "me".into() } else { peer.into() },
            payload_json: r#"{"type":"text","text":"hi"}"#.into(), status: if outgoing { 0 } else { 1 },
            ts, reactions_json: "{}".into(), edited: false, deleted: false,
        }
    }

    /// Настоящий pickle сессии — миграция и olm_session_set вычисляют из него
    /// session_id, поэтому подсунуть строку-заглушку тут нельзя.
    fn real_session(text: &str) -> String {
        let bob = aether_ratchet_core::account_generate_otks(
            &aether_ratchet_core::account_new().unwrap(), 1).unwrap();
        let otk: serde_json::Value = serde_json::from_str(&bob.one_time_keys_json).unwrap();
        let otk = otk.as_object().unwrap().values().next().unwrap().as_str().unwrap().to_owned();
        let session = aether_ratchet_core::create_outbound(
            &aether_ratchet_core::account_new().unwrap(), &bob.identity_key_b64, &otk).unwrap();
        // Шифруем, чтобы у разных вызовов гарантированно отличалось содержимое.
        aether_ratchet_core::encrypt(&session, text).unwrap().session_pickle
    }

    /// SEC MED-4: сессий на пира несколько, самая свежая — первой, и лимит держит
    /// таблицу от разрастания.
    #[test]
    fn olm_sessions_are_multi_and_capped() {
        let s = store();
        let a = real_session("a");
        let b = real_session("b");
        let a_id = aether_ratchet_core::session_id(&a).unwrap();
        let b_id = aether_ratchet_core::session_id(&b).unwrap();
        assert_ne!(a_id, b_id);

        // updated_ts в миллисекундах, поэтому подряд идущие записи попадают в одну
        // и ту же метку, и «свежая» становится неотличима от «прошлой» — порядок
        // тогда решает тай-брейк по session_id. Разводим записи по времени там,
        // где проверяется именно свежесть, иначе тест зависит от скорости машины.
        let tick = || std::thread::sleep(std::time::Duration::from_millis(2));

        s.olm_session_put("Bob::ios-1".into(), a_id.clone(), a.clone()).unwrap();
        tick();
        s.olm_session_put("bob::ios-1".into(), b_id.clone(), b.clone()).unwrap();
        let all = s.olm_sessions_for("bob::ios-1".into()).unwrap();
        assert_eq!(all.len(), 2, "входящая сессия не затирает исходящую");
        // Свежая первой — её и возьмёт отправка (olm_session_get).
        assert_eq!(all[0].session_id, b_id);
        assert_eq!(s.olm_session_get("bob::ios-1".into()).unwrap().as_deref(), Some(b.as_str()));

        // Обновление существующей сессии не плодит строк.
        tick();
        s.olm_session_put("bob::ios-1".into(), a_id.clone(), a.clone()).unwrap();
        assert_eq!(s.olm_sessions_for("bob::ios-1".into()).unwrap().len(), 2);

        // Лимит: сверх MAX_SESSIONS_PER_PEER самые старые вытесняются.
        for i in 0..MAX_SESSIONS_PER_PEER + 3 {
            let p = real_session(&format!("s{i}"));
            tick();
            s.olm_session_set("bob::ios-1".into(), p).unwrap();
        }
        let all = s.olm_sessions_for("bob::ios-1".into()).unwrap();
        assert_eq!(all.len() as i64, MAX_SESSIONS_PER_PEER);
        assert!(!all.iter().any(|x| x.session_id == a_id), "вытеснены самые старые");

        // Соседний пир не задет, delete сносит все сессии устройства.
        s.olm_session_set("carol".into(), real_session("c")).unwrap();
        s.olm_session_delete("bob::ios-1".into()).unwrap();
        assert!(s.olm_sessions_for("bob::ios-1".into()).unwrap().is_empty());
        assert_eq!(s.olm_sessions_for("carol".into()).unwrap().len(), 1);
    }

    /// Апгрейд с однососессионной схемы: существующая сессия должна пережить
    /// миграцию С ПРАВИЛЬНЫМ session_id, иначе первый же входящий prekey не нашёл
    /// бы её и завёл дубль, впустую спалив одноразовый ключ.
    #[test]
    fn legacy_session_table_migrates_with_real_session_id() {
        let dir = std::env::temp_dir().join(format!("aether-mig-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("legacy.sqlite");
        let path_s = path.to_string_lossy().to_string();

        let pickle = real_session("до миграции");
        let expected = aether_ratchet_core::session_id(&pickle).unwrap();
        {
            let c = Connection::open(&path).unwrap();
            c.execute_batch(
                "CREATE TABLE olm_sessions (peer_id TEXT PRIMARY KEY,
                                            session_json TEXT NOT NULL,
                                            updated_ts INTEGER NOT NULL DEFAULT 0);",
            )
            .unwrap();
            c.execute("INSERT INTO olm_sessions VALUES ('bob::ios-1', ?1, 42)", params![pickle])
                .unwrap();
            // Битый pickle: session_id не вычислить — строка не должна ронять миграцию.
            c.execute("INSERT INTO olm_sessions VALUES ('carol', 'не-pickle', 7)", []).unwrap();
        }

        let s = CoreStore::open(path_s.clone(), None).unwrap();
        let migrated = s.olm_sessions_for("bob::ios-1".into()).unwrap();
        assert_eq!(migrated.len(), 1);
        assert_eq!(migrated[0].session_id, expected, "session_id восстановлен из pickle");
        assert_eq!(migrated[0].session_json, pickle);
        assert_eq!(migrated[0].updated_ts, 42);
        assert_eq!(s.olm_sessions_for("carol".into()).unwrap()[0].session_id, "legacy");

        // Повторное открытие уже мигрированной базы ничего не ломает.
        drop(s);
        let s = CoreStore::open(path_s, None).unwrap();
        assert_eq!(s.olm_sessions_for("bob::ios-1".into()).unwrap()[0].session_id, expected);
        drop(s);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn olm_pin_tofu_semantics() {
        let s = store();
        // Первый контакт (входящий, только curve) — TOFU.
        assert_eq!(s.olm_pin_check("Bob::ios-1".into(), "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE".into(), None, 10).unwrap(), OlmPinStatus::FirstUse);
        // Повтор — Match; регистр peer_key не важен.
        assert_eq!(s.olm_pin_check("bob::ios-1".into(), "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE".into(), None, 11).unwrap(), OlmPinStatus::Match);
        // Claim принёс ed25519 — дозаполнение без тревоги.
        assert_eq!(s.olm_pin_check("bob::ios-1".into(), "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE".into(), Some("AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM".into()), 12).unwrap(), OlmPinStatus::Match);
        assert_eq!(s.olm_pin_get("bob::ios-1".into()).unwrap().unwrap().ed25519_b64.as_deref(), Some("AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM"));
        // Смена curve или ed — Mismatch, пин не тронут.
        assert_eq!(s.olm_pin_check("bob::ios-1".into(), "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI".into(), None, 13).unwrap(), OlmPinStatus::Mismatch);
        assert_eq!(s.olm_pin_check("bob::ios-1".into(), "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE".into(), Some("BAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ".into()), 14).unwrap(), OlmPinStatus::Mismatch);
        let pin = s.olm_pin_get("bob::ios-1".into()).unwrap().unwrap();
        assert_eq!(pin.curve25519_b64, "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE");
        // Явное принятие нового ключа: старый уходит в prev_*, verified сброшен.
        s.olm_pin_set_verified("bob::ios-1".into(), true).unwrap();
        s.olm_pin_accept("bob::ios-1".into(), "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI".into(), Some("BAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ".into()), 15).unwrap();
        let pin = s.olm_pin_get("bob::ios-1".into()).unwrap().unwrap();
        assert_eq!(pin.curve25519_b64, "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI");
        assert_eq!(pin.prev_curve25519_b64.as_deref(), Some("AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE"));
        assert_eq!(pin.prev_ed25519_b64.as_deref(), Some("AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM"));
        assert!(!pin.verified);
        assert_eq!(pin.changed_ts, Some(15));
    }

    #[test]
    fn peer_trust_reset_clears_devices_but_not_other_peers() {
        let s = store();
        // Пир с легаси-primary (голый peer_id) и обычным устройством.
        s.olm_pin_check("bob".into(), "BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU".into(), None, 1).unwrap();
        s.olm_pin_check("bob::ios-1".into(), "BgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgY".into(), None, 1).unwrap();
        s.olm_pin_check("bobby::ios-9".into(), "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc".into(), None, 1).unwrap();   // другой пир
        let untouched = real_session("s3");
        s.olm_session_set("bob".into(), real_session("s1")).unwrap();
        s.olm_session_set("bob::ios-1".into(), real_session("s2")).unwrap();
        s.olm_session_set("bobby::ios-9".into(), untouched.clone()).unwrap();
        s.master_pin_check("bob".into(), "CAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg".into(), 1).unwrap();

        s.peer_trust_reset("Bob".into()).unwrap();

        assert!(s.olm_pin_get("bob".into()).unwrap().is_none());
        assert!(s.olm_pin_get("bob::ios-1".into()).unwrap().is_none());
        assert!(s.olm_session_get("bob".into()).unwrap().is_none());
        assert!(s.olm_session_get("bob::ios-1".into()).unwrap().is_none());
        // Похожий по префиксу пир не задет, мастер-пин остаётся (его принимают отдельно).
        assert!(s.olm_pin_get("bobby::ios-9".into()).unwrap().is_some());
        assert_eq!(s.olm_session_get("bobby::ios-9".into()).unwrap().as_deref(), Some(untouched.as_str()));
        assert!(s.master_pin_get("bob".into()).unwrap().is_some());
    }

    #[test]
    fn peer_trust_reset_escapes_like_wildcards() {
        // '_' разрешён в user_id и является подстановочным символом LIKE:
        // без экранирования сброс "bob_1" сносил бы доверие постороннему "bobx1".
        let s = store();
        s.olm_pin_check("bob_1::ios".into(), "BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU".into(), None, 1).unwrap();
        s.olm_pin_check("bobx1::ios".into(), "BgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgY".into(), None, 1).unwrap();
        let untouched = real_session("s2");
        s.olm_session_set("bobx1::ios".into(), untouched.clone()).unwrap();

        s.peer_trust_reset("bob_1".into()).unwrap();

        assert!(s.olm_pin_get("bob_1::ios".into()).unwrap().is_none());
        assert!(s.olm_pin_get("bobx1::ios".into()).unwrap().is_some(), "чужой пир не тронут");
        assert_eq!(s.olm_session_get("bobx1::ios".into()).unwrap().as_deref(), Some(untouched.as_str()));
    }

    #[test]
    fn ephemeral_purge_erases_content_but_keeps_the_trace() {
        let s = store();
        s.insert_message(msg("m-eph", "bob", 1000, true)).unwrap();
        s.update_reactions("m-eph".into(), r#"{"❤":["bob"]}"#.into()).unwrap();

        s.ephemeral_set(EphemeralState {
            message_id: "m-eph".into(), state: "COUNTDOWN".into(),
            opened_ts: Some(1000), expires_ts: Some(2000), views: 1,
        }).unwrap();

        // Срок ещё не вышел — ничего не подлежит вычистке.
        assert!(s.ephemeral_due(1999).unwrap().is_empty());
        assert_eq!(s.ephemeral_due(2000).unwrap(), vec!["m-eph".to_string()]);

        s.ephemeral_purge("m-eph".into()).unwrap();

        // Содержимое стёрто, но строка в истории осталась.
        let m = s.get_message("m-eph".into()).unwrap().expect("сообщение осталось в истории");
        assert_eq!(m.payload_json, r#"{"type":"expired"}"#);
        assert_eq!(m.reactions_json, "{}", "реакции тоже стираются");

        let state = s.ephemeral_get("m-eph".into()).unwrap().unwrap();
        assert_eq!(state.state, "PURGED");
        // Повторно в очередь на вычистку не попадает.
        assert!(s.ephemeral_due(9999).unwrap().is_empty());
    }

    #[test]
    fn policy_defaults_preserve_existing_behaviour() {
        let s = store();
        // Чат без записи: умолчание, а не ошибка — политики появились позже чатов.
        let p = s.chat_policy("bob".into()).unwrap();
        assert_eq!(p.delivery_mode, "AUTO");
        assert_eq!(p.server_storage, "ENCRYPTED_BACKUP");

        // Категория без записи: разрешено. Иначе одно лишь появление функции
        // молча запретило бы отправку всего.
        assert!(s.server_allows("cloud".into(), "image".into()).unwrap());

        s.set_chat_policy(ChatDeliveryPolicy {
            peer_id: "BOB".into(),           // регистр не должен создавать вторую запись
            delivery_mode: "DIRECT_ONLY".into(),
            transport_order: Some("[\"nearby.ble\"]".into()),
            server_storage: "NEVER".into(),
            updated_ts: 5,
        }).unwrap();
        let p = s.chat_policy("bob".into()).unwrap();
        assert_eq!(p.delivery_mode, "DIRECT_ONLY");
        assert_eq!(p.server_storage, "NEVER");
        assert_eq!(p.transport_order.as_deref(), Some("[\"nearby.ble\"]"));

        s.set_server_allows("cloud".into(), "image".into(), false).unwrap();
        assert!(!s.server_allows("cloud".into(), "image".into()).unwrap());
        // Запрет одной категории не трогает остальные и другие серверы.
        assert!(s.server_allows("cloud".into(), "text".into()).unwrap());
        assert!(s.server_allows("home".into(), "image".into()).unwrap());
    }

    #[test]
    fn route_and_attempts_survive_transport_change() {
        let s = store();
        let mid = crate::message::new_message_id();

        // Первая попытка — Bluetooth, подтверждения не дождались.
        let a1 = s.add_delivery_attempt(mid.clone(), "nearby.ble".into(), Some("pixel".into()), 100).unwrap();
        s.finish_delivery_attempt(mid.clone(), a1, "timeout".into(), None, 120).unwrap();

        // Вторая — через сервер, успешно. Сообщение ТО ЖЕ: id не менялся.
        let a2 = s.add_delivery_attempt(mid.clone(), "server.cloud".into(), None, 130).unwrap();
        s.finish_delivery_attempt(mid.clone(), a2, "ok".into(), None, 140).unwrap();
        assert_eq!((a1, a2), (1, 2), "номера попыток идут подряд");

        s.set_route(MessageRoute {
            message_id: mid.clone(),
            transport: "server.cloud".into(),
            physical: None,
            server_id: Some("cloud".into()),
            server_stored: true,
            delivered_ts: Some(140),
            read_ts: None,
        }).unwrap();

        let attempts = s.delivery_attempts(mid.clone()).unwrap();
        assert_eq!(attempts.len(), 2);
        assert_eq!(attempts[0].transport, "nearby.ble");
        assert_eq!(attempts[0].outcome.as_deref(), Some("timeout"));
        assert_eq!(attempts[1].outcome.as_deref(), Some("ok"));

        let route = s.route_for(mid.clone()).unwrap().expect("маршрут записан");
        assert_eq!(route.transport, "server.cloud");
        assert!(route.server_stored);

        // Отметка о прочтении не должна затирать время доставки.
        s.set_route(MessageRoute {
            message_id: mid.clone(),
            transport: "server.cloud".into(),
            physical: None,
            server_id: Some("cloud".into()),
            server_stored: true,
            delivered_ts: None,
            read_ts: Some(200),
        }).unwrap();
        let route = s.route_for(mid).unwrap().unwrap();
        assert_eq!(route.delivered_ts, Some(140), "время доставки сохранено");
        assert_eq!(route.read_ts, Some(200));
    }

    #[test]
    fn messages_paginate_chronologically() {
        let s = store();
        for i in 0..10 {
            s.insert_message(msg(&format!("m{i}"), "bob", 1000 + i, false)).unwrap();
        }
        let page = s.get_messages_for_peer("bob".into(), 0, 5).unwrap();
        assert_eq!(page.len(), 5);
        assert_eq!(page.first().unwrap().id, "m5");
        assert_eq!(page.last().unwrap().id, "m9");
        let older = s.get_messages_for_peer("bob".into(), page[0].ts, 5).unwrap();
        assert_eq!(older.last().unwrap().id, "m4");
    }

    #[test]
    fn outgoing_status_not_downgraded() {
        let s = store();
        s.insert_message(msg("o1", "bob", 1, true)).unwrap();
        s.update_status("o1".into(), 3).unwrap();
        s.mark_outgoing_status("bob".into(), 2).unwrap(); // read → delivered не понижаем
        let m = &s.get_messages_for_peer("bob".into(), 0, 10).unwrap()[0];
        assert_eq!(m.status, 3);
    }

    #[test]
    fn tofu_detects_key_change() {
        let s = store();
        assert!(!s.pin_upsert("bob".into(), "KEY1".into(), 1).unwrap());
        assert!(!s.pin_upsert("bob".into(), "KEY1".into(), 2).unwrap());
        assert!(s.pin_upsert("bob".into(), "KEY2".into(), 3).unwrap()); // изменился!
        assert_eq!(s.pin_get("bob".into()).unwrap().unwrap().public_key_b64, "KEY2");
    }

    #[test]
    fn encrypted_db_roundtrip_and_wrong_key() {
        let dir = std::env::temp_dir().join(format!("aether_test_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("enc.sqlite").to_string_lossy().to_string();
        let _ = std::fs::remove_file(&path);
        let key = crate::crypto::random_key_b64();

        {
            let s = CoreStore::open(path.clone(), Some(key.clone())).unwrap();
            s.insert_message(msg("m1", "bob", 1, false)).unwrap();
        }
        // Повторное открытие тем же ключом читает данные.
        {
            let s = CoreStore::open(path.clone(), Some(key.clone())).unwrap();
            assert!(s.message_exists("m1".into()).unwrap());
        }
        // Без ключа база нечитаема (это и есть шифрование at-rest).
        let plain = Connection::open(&path).unwrap();
        assert!(plain.query_row("SELECT count(*) FROM sqlite_master", [], |_| Ok(())).is_err());
        // Неверный ключ — внятная ошибка.
        let wrong = crate::crypto::random_key_b64();
        assert!(CoreStore::open(path.clone(), Some(wrong)).is_err());
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn plaintext_db_migrates_to_encrypted() {
        let dir = std::env::temp_dir().join(format!("aether_mig_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("mig.sqlite").to_string_lossy().to_string();
        let _ = std::fs::remove_file(&path);

        // Старая открытая база с данными.
        {
            let s = CoreStore::open(path.clone(), None).unwrap();
            s.insert_message(msg("old1", "bob", 1, false)).unwrap();
        }
        // Первое открытие с ключом — прозрачная миграция.
        let key = crate::crypto::random_key_b64();
        {
            let s = CoreStore::open(path.clone(), Some(key.clone())).unwrap();
            assert!(s.message_exists("old1".into()).unwrap(), "данные пережили миграцию");
            s.insert_message(msg("new1", "bob", 2, false)).unwrap();
        }
        // Файл теперь зашифрован.
        let plain = Connection::open(&path).unwrap();
        assert!(plain.query_row("SELECT count(*) FROM sqlite_master", [], |_| Ok(())).is_err());
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn group_key_epochs() {
        let s = store();
        s.set_group_key("g1".into(), "KEY_A".into()).unwrap();
        s.set_group_key("g1".into(), "KEY_A".into()).unwrap(); // тот же — эпоха не растёт
        assert_eq!(s.get_group_keys("g1".into()).unwrap(), vec!["KEY_A".to_string()]);
        s.set_group_key("g1".into(), "KEY_B".into()).unwrap(); // ротация
        assert_eq!(s.get_group_key("g1".into()).unwrap().unwrap(), "KEY_B");
        assert_eq!(s.get_group_keys("g1".into()).unwrap(), vec!["KEY_B".to_string(), "KEY_A".to_string()]);
    }

    #[test]
    fn chat_unread_and_pin() {
        let s = store();
        s.touch_chat("bob".into(), false, "Bob".into(), "hi".into(), 100, true).unwrap();
        s.touch_chat("bob".into(), false, "".into(), "hello".into(), 200, true).unwrap();
        s.set_pinned("bob".into(), true).unwrap();
        let chats = s.get_chat_list().unwrap();
        assert_eq!(chats[0].unread, 2);
        assert_eq!(chats[0].title, "Bob");
        assert!(chats[0].pinned);
        s.clear_unread("bob".into()).unwrap();
        assert_eq!(s.get_chat_list().unwrap()[0].unread, 0);
    }
}
