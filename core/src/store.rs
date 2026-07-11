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

/// TOFU-пин публичного ключа собеседника.
#[derive(uniffi::Record, Clone)]
pub struct KeyPin {
    pub peer_id: String,
    pub public_key_b64: String,
    pub verified: bool,
    pub first_seen: i64,
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
CREATE TABLE IF NOT EXISTS olm_sessions (
    peer_id TEXT PRIMARY KEY,
    session_json TEXT NOT NULL,
    updated_ts INTEGER NOT NULL DEFAULT 0
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

    /// Olm-сессия (Double Ratchet) с пиром: pickle-JSON. Одна сессия на пира —
    /// при входящем prekey перезаписываем на новую (см. ratchet-интеграцию).
    /// ponytail: одна сессия/пир; при одновременной инициации с двух сторон
    /// возможна пересборка — апгрейд до мультисессий, если станет мешать.
    pub fn olm_session_get(&self, peer_id: String) -> Result<Option<String>, CoreError> {
        let c = self.conn.lock().unwrap();
        Ok(c.query_row("SELECT session_json FROM olm_sessions WHERE peer_id=?1",
                       params![peer_id.to_lowercase()], |r| r.get(0)).optional()?)
    }
    pub fn olm_session_set(&self, peer_id: String, session_json: String) -> Result<(), CoreError> {
        let ts = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0);
        self.conn.lock().unwrap().execute(
            "INSERT OR REPLACE INTO olm_sessions (peer_id, session_json, updated_ts) VALUES (?1,?2,?3)",
            params![peer_id.to_lowercase(), session_json, ts])?;
        Ok(())
    }
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
