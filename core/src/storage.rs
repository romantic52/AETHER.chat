//! Локальное хранилище ядра (SQLite, bundled). Зеркалит Room-схему Android
//! (`messages`/`chats`/`pinned_keys`) — единое хранилище для всех платформ.
//!
//! Текстовые поля (`text`/`reply_to_text`/`reactions`) шифруются at-rest
//! AES-256-GCM ключом сессии (формат `nonce_b64:ct_b64`); метаданные
//! (id/peer/timestamp/status) — открыто, чтобы работали сортировка и фильтры.
//!
//! Реактивность как у Room.Flow: каждый мутирующий вызов дёргает `StoreListener`
//! (`on_messages_changed(peer)` / `on_chats_changed`), платформа на это
//! перечитывает нужный запрос и обновляет UI.

use std::sync::{Arc, Mutex};
use rusqlite::{params, Connection, Row};

#[derive(Debug, uniffi::Error)]
pub enum StorageError {
    Db { msg: String },
}
impl std::fmt::Display for StorageError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            StorageError::Db { msg } => write!(f, "БД: {}", msg),
        }
    }
}
impl std::error::Error for StorageError {}
impl From<rusqlite::Error> for StorageError {
    fn from(e: rusqlite::Error) -> Self {
        StorageError::Db { msg: e.to_string() }
    }
}

/// Сообщение (зеркало `MessageEntity`; авто-id Room наружу не отдаётся).
#[derive(Debug, Clone, uniffi::Record)]
pub struct StoredMessage {
    pub msg_id: String,
    pub peer_id: String,
    pub is_out: bool,
    pub text: String,
    pub timestamp: i64,
    pub reply_to_id: Option<String>,
    pub reply_to_text: Option<String>,
    pub reactions: String,
    pub status: i32,
    pub is_edited: bool,
    pub forwarded_from: Option<String>,
}

/// Чат (зеркало `ChatEntity`). `kind`: 0=личный,1=группа,2=канал,3=Избранное.
#[derive(Debug, Clone, uniffi::Record)]
pub struct Chat {
    pub peer_id: String,
    pub name: String,
    pub kind: i32,
    pub is_pinned: bool,
    pub is_muted: bool,
    pub is_archived: bool,
    pub unread_count: i32,
    pub avatar_file_id: Option<String>,
}

/// Строка списка чатов: чат + только последнее сообщение (зеркало `ChatListEntry`).
#[derive(Debug, Clone, uniffi::Record)]
pub struct ChatListEntry {
    pub chat: Chat,
    pub last_text: Option<String>,
    pub last_timestamp: Option<i64>,
    pub last_is_out: Option<bool>,
}

/// Запиненный ключ (зеркало `PinnedKeyEntity`, TOFU).
#[derive(Debug, Clone, uniffi::Record)]
pub struct PinnedKey {
    pub peer_id: String,
    pub public_key_b64: String,
    pub pinned_at: i64,
    pub verified: bool,
    pub previous_key_b64: Option<String>,
    pub changed_at: Option<i64>,
}

/// Уведомления об изменениях БД (для реактивного UI). Реализует платформа.
#[uniffi::export(callback_interface)]
pub trait StoreListener: Send + Sync {
    fn on_messages_changed(&self, peer_id: String);
    fn on_chats_changed(&self);
}

const MSG_COLS: &str =
    "msg_id, peer_id, is_out, text, timestamp, reply_to_id, reply_to_text, reactions, status, is_edited, forwarded_from";

#[derive(uniffi::Object)]
pub struct Store {
    conn: Mutex<Connection>,
    /// base64-ключ AES для шифрования текстов at-rest; None — хранить открыто.
    key_b64: Option<String>,
    listener: Mutex<Option<Box<dyn StoreListener>>>,
}

#[uniffi::export]
impl Store {
    /// Открыть/создать БД. `enc_key_b64` — base64 32-байтного AES-ключа сессии
    /// (пусто/None — без шифрования полей, для тестов/миграции).
    #[uniffi::constructor]
    pub fn open(path: String, enc_key_b64: Option<String>) -> Result<Arc<Self>, StorageError> {
        let conn = Connection::open(&path)?;
        conn.execute_batch(SCHEMA)?;
        let key = enc_key_b64.filter(|s| !s.is_empty());
        Ok(Arc::new(Self {
            conn: Mutex::new(conn),
            key_b64: key,
            listener: Mutex::new(None),
        }))
    }

    pub fn set_listener(&self, listener: Box<dyn StoreListener>) {
        *self.listener.lock().unwrap() = Some(listener);
    }

    pub fn sqlite_version(&self) -> String {
        let c = self.conn.lock().unwrap();
        c.query_row("SELECT sqlite_version()", [], |r| r.get::<_, String>(0))
            .unwrap_or_default()
    }

    // ---------------- messages ----------------

    pub fn insert_message(&self, m: StoredMessage) -> Result<(), StorageError> {
        {
            let c = self.conn.lock().unwrap();
            c.execute(
                "INSERT INTO messages (msg_id, peer_id, is_out, text, timestamp, reply_to_id, reply_to_text, reactions, status, is_edited, forwarded_from)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)",
                params![
                    m.msg_id,
                    m.peer_id,
                    m.is_out as i64,
                    self.enc(&m.text),
                    m.timestamp,
                    m.reply_to_id,
                    m.reply_to_text.as_ref().map(|s| self.enc(s)),
                    self.enc(&m.reactions),
                    m.status,
                    m.is_edited as i64,
                    m.forwarded_from,
                ],
            )?;
        }
        self.notify_messages(&m.peer_id);
        self.notify_chats();
        Ok(())
    }

    pub fn get_message_by_msg_id(&self, msg_id: String) -> Result<Option<StoredMessage>, StorageError> {
        let c = self.conn.lock().unwrap();
        let mut stmt = c.prepare(&format!("SELECT {} FROM messages WHERE msg_id = ?1 LIMIT 1", MSG_COLS))?;
        let mut rows = stmt.query(params![msg_id])?;
        match rows.next()? {
            Some(row) => Ok(Some(self.row_to_message(row)?)),
            None => Ok(None),
        }
    }

    pub fn delete_message_by_msg_id(&self, msg_id: String) -> Result<(), StorageError> {
        let peer = self.peer_of(&msg_id)?;
        {
            let c = self.conn.lock().unwrap();
            c.execute("DELETE FROM messages WHERE msg_id = ?1", params![msg_id])?;
        }
        if let Some(p) = peer {
            self.notify_messages(&p);
        }
        self.notify_chats();
        Ok(())
    }

    pub fn update_reactions(&self, msg_id: String, reactions: String) -> Result<(), StorageError> {
        let peer = self.peer_of(&msg_id)?;
        {
            let c = self.conn.lock().unwrap();
            c.execute(
                "UPDATE messages SET reactions = ?1 WHERE msg_id = ?2",
                params![self.enc(&reactions), msg_id],
            )?;
        }
        if let Some(p) = peer {
            self.notify_messages(&p);
        }
        Ok(())
    }

    pub fn update_text(&self, msg_id: String, text: String) -> Result<(), StorageError> {
        let peer = self.peer_of(&msg_id)?;
        {
            let c = self.conn.lock().unwrap();
            c.execute(
                "UPDATE messages SET text = ?1, is_edited = 1 WHERE msg_id = ?2",
                params![self.enc(&text), msg_id],
            )?;
        }
        if let Some(p) = peer {
            self.notify_messages(&p);
        }
        self.notify_chats();
        Ok(())
    }

    pub fn update_status(&self, msg_id: String, status: i32) -> Result<(), StorageError> {
        let peer = self.peer_of(&msg_id)?;
        {
            let c = self.conn.lock().unwrap();
            c.execute(
                "UPDATE messages SET status = ?1 WHERE msg_id = ?2",
                params![status, msg_id],
            )?;
        }
        if let Some(p) = peer {
            self.notify_messages(&p);
        }
        self.notify_chats();
        Ok(())
    }

    /// Исходящие в очереди (`is_out=1 AND status=0`), по возрастанию времени.
    pub fn get_pending_outgoing(&self) -> Result<Vec<StoredMessage>, StorageError> {
        let c = self.conn.lock().unwrap();
        let mut stmt = c.prepare(&format!(
            "SELECT {} FROM messages WHERE is_out = 1 AND status = 0 ORDER BY timestamp ASC",
            MSG_COLS
        ))?;
        let rows = stmt.query_map([], |row| self.row_to_message(row).map_err(to_rusqlite))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r?);
        }
        Ok(out)
    }

    pub fn mark_outgoing_read(&self, peer_id: String) -> Result<(), StorageError> {
        {
            let c = self.conn.lock().unwrap();
            c.execute(
                "UPDATE messages SET status = 3 WHERE peer_id = ?1 AND is_out = 1 AND status < 3",
                params![peer_id],
            )?;
        }
        self.notify_messages(&peer_id);
        Ok(())
    }

    pub fn mark_outgoing_delivered(&self, peer_id: String) -> Result<(), StorageError> {
        {
            let c = self.conn.lock().unwrap();
            c.execute(
                "UPDATE messages SET status = 2 WHERE peer_id = ?1 AND is_out = 1 AND status < 2",
                params![peer_id],
            )?;
        }
        self.notify_messages(&peer_id);
        Ok(())
    }

    /// Все сообщения чата по возрастанию времени.
    pub fn get_messages_for_peer(&self, peer_id: String) -> Result<Vec<StoredMessage>, StorageError> {
        let c = self.conn.lock().unwrap();
        let mut stmt = c.prepare(&format!(
            "SELECT {} FROM messages WHERE peer_id = ?1 ORDER BY timestamp ASC",
            MSG_COLS
        ))?;
        let rows = stmt.query_map(params![peer_id], |row| self.row_to_message(row).map_err(to_rusqlite))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r?);
        }
        Ok(out)
    }

    // ---------------- chats ----------------

    pub fn insert_chat(&self, chat: Chat) -> Result<(), StorageError> {
        {
            let c = self.conn.lock().unwrap();
            c.execute(
                "INSERT OR REPLACE INTO chats (peer_id, name, type, is_pinned, is_muted, is_archived, unread_count, avatar_file_id)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
                params![
                    chat.peer_id,
                    chat.name,
                    chat.kind,
                    chat.is_pinned as i64,
                    chat.is_muted as i64,
                    chat.is_archived as i64,
                    chat.unread_count,
                    chat.avatar_file_id,
                ],
            )?;
        }
        self.notify_chats();
        Ok(())
    }

    pub fn set_pinned(&self, peer_id: String, value: bool) -> Result<(), StorageError> {
        self.set_chat_flag("is_pinned", &peer_id, value)
    }
    pub fn set_muted(&self, peer_id: String, value: bool) -> Result<(), StorageError> {
        self.set_chat_flag("is_muted", &peer_id, value)
    }
    pub fn set_archived(&self, peer_id: String, value: bool) -> Result<(), StorageError> {
        self.set_chat_flag("is_archived", &peer_id, value)
    }

    pub fn set_avatar(&self, peer_id: String, avatar_file_id: Option<String>) -> Result<(), StorageError> {
        {
            let c = self.conn.lock().unwrap();
            c.execute(
                "UPDATE chats SET avatar_file_id = ?1 WHERE peer_id = ?2",
                params![avatar_file_id, peer_id],
            )?;
        }
        self.notify_chats();
        Ok(())
    }

    /// Удаляет чат и все его сообщения (как `deleteChatAndMessages`).
    pub fn delete_chat_and_messages(&self, peer_id: String) -> Result<(), StorageError> {
        {
            let c = self.conn.lock().unwrap();
            c.execute("DELETE FROM chats WHERE peer_id = ?1", params![peer_id])?;
            c.execute("DELETE FROM messages WHERE peer_id = ?1", params![peer_id])?;
        }
        self.notify_messages(&peer_id);
        self.notify_chats();
        Ok(())
    }

    pub fn get_all_chats(&self) -> Result<Vec<Chat>, StorageError> {
        let c = self.conn.lock().unwrap();
        let mut stmt = c.prepare(
            "SELECT peer_id, name, type, is_pinned, is_muted, is_archived, unread_count, avatar_file_id FROM chats",
        )?;
        let rows = stmt.query_map([], |row| row_to_chat(row))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r?);
        }
        Ok(out)
    }

    pub fn get_chat(&self, peer_id: String) -> Result<Option<Chat>, StorageError> {
        let c = self.conn.lock().unwrap();
        let mut stmt = c.prepare(
            "SELECT peer_id, name, type, is_pinned, is_muted, is_archived, unread_count, avatar_file_id FROM chats WHERE peer_id = ?1 LIMIT 1",
        )?;
        let mut rows = stmt.query(params![peer_id])?;
        match rows.next()? {
            Some(row) => Ok(Some(row_to_chat(row)?)),
            None => Ok(None),
        }
    }

    /// Список чатов: закреплённые сверху, дальше по свежести последнего сообщения.
    pub fn get_chat_list(&self) -> Result<Vec<ChatListEntry>, StorageError> {
        let c = self.conn.lock().unwrap();
        let mut stmt = c.prepare(
            "SELECT c.peer_id, c.name, c.type, c.is_pinned, c.is_muted, c.is_archived, c.unread_count, c.avatar_file_id,
                    m.text, m.timestamp, m.is_out
             FROM chats c
             LEFT JOIN messages m ON m.id = (
                 SELECT id FROM messages WHERE peer_id = c.peer_id ORDER BY timestamp DESC LIMIT 1
             )
             ORDER BY c.is_pinned DESC, m.timestamp DESC",
        )?;
        let rows = stmt.query_map([], |row| {
            let chat = row_to_chat(row)?;
            let last_text: Option<String> = row.get(8)?;
            let last_timestamp: Option<i64> = row.get(9)?;
            let last_is_out: Option<i64> = row.get(10)?;
            Ok((chat, last_text, last_timestamp, last_is_out))
        })?;
        let mut out = Vec::new();
        for r in rows {
            let (chat, last_text, last_timestamp, last_is_out) = r?;
            out.push(ChatListEntry {
                chat,
                last_text: last_text.map(|s| self.dec(&s)),
                last_timestamp,
                last_is_out: last_is_out.map(|v| v != 0),
            });
        }
        Ok(out)
    }

    pub fn increment_unread(&self, peer_id: String) -> Result<(), StorageError> {
        {
            let c = self.conn.lock().unwrap();
            c.execute(
                "UPDATE chats SET unread_count = unread_count + 1 WHERE peer_id = ?1",
                params![peer_id],
            )?;
        }
        self.notify_chats();
        Ok(())
    }

    pub fn clear_unread(&self, peer_id: String) -> Result<(), StorageError> {
        {
            let c = self.conn.lock().unwrap();
            c.execute("UPDATE chats SET unread_count = 0 WHERE peer_id = ?1", params![peer_id])?;
        }
        self.notify_chats();
        Ok(())
    }

    // ---------------- pinned_keys (TOFU) ----------------

    pub fn pin_get(&self, peer_id: String) -> Result<Option<PinnedKey>, StorageError> {
        let c = self.conn.lock().unwrap();
        let mut stmt = c.prepare(
            "SELECT peer_id, public_key_b64, pinned_at, verified, previous_key_b64, changed_at FROM pinned_keys WHERE peer_id = ?1",
        )?;
        let mut rows = stmt.query(params![peer_id])?;
        match rows.next()? {
            Some(row) => Ok(Some(PinnedKey {
                peer_id: row.get(0)?,
                public_key_b64: row.get(1)?,
                pinned_at: row.get(2)?,
                verified: row.get::<_, i64>(3)? != 0,
                previous_key_b64: row.get(4)?,
                changed_at: row.get(5)?,
            })),
            None => Ok(None),
        }
    }

    pub fn pin_upsert(&self, pin: PinnedKey) -> Result<(), StorageError> {
        let c = self.conn.lock().unwrap();
        c.execute(
            "INSERT OR REPLACE INTO pinned_keys (peer_id, public_key_b64, pinned_at, verified, previous_key_b64, changed_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![
                pin.peer_id,
                pin.public_key_b64,
                pin.pinned_at,
                pin.verified as i64,
                pin.previous_key_b64,
                pin.changed_at,
            ],
        )?;
        Ok(())
    }

    pub fn pin_set_verified(&self, peer_id: String, verified: bool) -> Result<(), StorageError> {
        let c = self.conn.lock().unwrap();
        c.execute(
            "UPDATE pinned_keys SET verified = ?1 WHERE peer_id = ?2",
            params![verified as i64, peer_id],
        )?;
        Ok(())
    }

    pub fn pin_delete(&self, peer_id: String) -> Result<(), StorageError> {
        let c = self.conn.lock().unwrap();
        c.execute("DELETE FROM pinned_keys WHERE peer_id = ?1", params![peer_id])?;
        Ok(())
    }
}

// ---------------- внутреннее ----------------

impl Store {
    fn set_chat_flag(&self, col: &str, peer_id: &str, value: bool) -> Result<(), StorageError> {
        {
            let c = self.conn.lock().unwrap();
            c.execute(
                &format!("UPDATE chats SET {} = ?1 WHERE peer_id = ?2", col),
                params![value as i64, peer_id],
            )?;
        }
        self.notify_chats();
        Ok(())
    }

    fn peer_of(&self, msg_id: &str) -> Result<Option<String>, StorageError> {
        let c = self.conn.lock().unwrap();
        let mut stmt = c.prepare("SELECT peer_id FROM messages WHERE msg_id = ?1 LIMIT 1")?;
        let mut rows = stmt.query(params![msg_id])?;
        match rows.next()? {
            Some(row) => Ok(Some(row.get(0)?)),
            None => Ok(None),
        }
    }

    fn row_to_message(&self, row: &Row) -> Result<StoredMessage, StorageError> {
        Ok(StoredMessage {
            msg_id: row.get(0)?,
            peer_id: row.get(1)?,
            is_out: row.get::<_, i64>(2)? != 0,
            text: self.dec(&row.get::<_, String>(3)?),
            timestamp: row.get(4)?,
            reply_to_id: row.get(5)?,
            reply_to_text: row.get::<_, Option<String>>(6)?.map(|s| self.dec(&s)),
            reactions: self.dec(&row.get::<_, String>(7)?),
            status: row.get(8)?,
            is_edited: row.get::<_, i64>(9)? != 0,
            forwarded_from: row.get(10)?,
        })
    }

    fn notify_messages(&self, peer: &str) {
        if let Some(l) = self.listener.lock().unwrap().as_ref() {
            l.on_messages_changed(peer.to_string());
        }
    }
    fn notify_chats(&self) {
        if let Some(l) = self.listener.lock().unwrap().as_ref() {
            l.on_chats_changed();
        }
    }

    /// Шифрование поля at-rest: `nonce_b64:ct_b64` (или открыто без ключа).
    fn enc(&self, plain: &str) -> String {
        match &self.key_b64 {
            Some(k) => match crate::crypto::aes_encrypt(plain.as_bytes(), k) {
                Ok((n, c)) => format!("{}:{}", n, c),
                Err(_) => plain.to_string(),
            },
            None => plain.to_string(),
        }
    }

    fn dec(&self, stored: &str) -> String {
        match &self.key_b64 {
            Some(k) => match stored.split_once(':') {
                Some((n, c)) => match crate::crypto::aes_decrypt(k, n, c) {
                    Ok(bytes) => String::from_utf8_lossy(&bytes).into_owned(),
                    Err(_) => stored.to_string(),
                },
                None => stored.to_string(),
            },
            None => stored.to_string(),
        }
    }
}

fn row_to_chat(row: &Row) -> rusqlite::Result<Chat> {
    Ok(Chat {
        peer_id: row.get(0)?,
        name: row.get(1)?,
        kind: row.get(2)?,
        is_pinned: row.get::<_, i64>(3)? != 0,
        is_muted: row.get::<_, i64>(4)? != 0,
        is_archived: row.get::<_, i64>(5)? != 0,
        unread_count: row.get(6)?,
        avatar_file_id: row.get(7)?,
    })
}

fn to_rusqlite(e: StorageError) -> rusqlite::Error {
    rusqlite::Error::ToSqlConversionFailure(Box::new(std::io::Error::new(
        std::io::ErrorKind::Other,
        e.to_string(),
    )))
}

const SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  msg_id TEXT NOT NULL,
  peer_id TEXT NOT NULL,
  is_out INTEGER NOT NULL,
  text TEXT NOT NULL,
  timestamp INTEGER NOT NULL,
  reply_to_id TEXT,
  reply_to_text TEXT,
  reactions TEXT NOT NULL DEFAULT '',
  status INTEGER NOT NULL DEFAULT 1,
  is_edited INTEGER NOT NULL DEFAULT 0,
  forwarded_from TEXT
);
CREATE INDEX IF NOT EXISTS idx_messages_peer ON messages(peer_id, timestamp);
CREATE INDEX IF NOT EXISTS idx_messages_msgid ON messages(msg_id);
CREATE TABLE IF NOT EXISTS chats (
  peer_id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  type INTEGER NOT NULL,
  is_pinned INTEGER NOT NULL DEFAULT 0,
  is_muted INTEGER NOT NULL DEFAULT 0,
  is_archived INTEGER NOT NULL DEFAULT 0,
  unread_count INTEGER NOT NULL DEFAULT 0,
  avatar_file_id TEXT
);
CREATE TABLE IF NOT EXISTS pinned_keys (
  peer_id TEXT PRIMARY KEY,
  public_key_b64 TEXT NOT NULL,
  pinned_at INTEGER NOT NULL,
  verified INTEGER NOT NULL DEFAULT 0,
  previous_key_b64 TEXT,
  changed_at INTEGER
);
";

#[cfg(test)]
mod tests {
    use super::*;
    use base64::Engine;

    fn key() -> String {
        base64::engine::general_purpose::URL_SAFE_NO_PAD.encode([9u8; 32])
    }

    fn msg(msg_id: &str, peer: &str, out: bool, text: &str, ts: i64, status: i32) -> StoredMessage {
        StoredMessage {
            msg_id: msg_id.into(),
            peer_id: peer.into(),
            is_out: out,
            text: text.into(),
            timestamp: ts,
            reply_to_id: None,
            reply_to_text: None,
            reactions: String::new(),
            status,
            is_edited: false,
            forwarded_from: None,
        }
    }

    fn open() -> Arc<Store> {
        Store::open(":memory:".into(), Some(key())).unwrap()
    }

    #[test]
    fn message_crud_and_encryption() {
        let s = open();
        s.insert_message(msg("m1", "alice", true, "привет 🔐", 100, 1)).unwrap();
        let got = s.get_message_by_msg_id("m1".into()).unwrap().unwrap();
        assert_eq!(got.text, "привет 🔐");
        assert_eq!(got.peer_id, "alice");

        // в самой БД текст должен быть зашифрован (формат nonce:ct, не plaintext)
        {
            let c = s.conn.lock().unwrap();
            let raw: String = c
                .query_row("SELECT text FROM messages WHERE msg_id='m1'", [], |r| r.get(0))
                .unwrap();
            assert!(raw.contains(':') && !raw.contains("привет"));
        }

        s.update_text("m1".into(), "изменено".into()).unwrap();
        let got = s.get_message_by_msg_id("m1".into()).unwrap().unwrap();
        assert_eq!(got.text, "изменено");
        assert!(got.is_edited);

        s.update_status("m1".into(), 3).unwrap();
        assert_eq!(s.get_message_by_msg_id("m1".into()).unwrap().unwrap().status, 3);

        s.update_reactions("m1".into(), "{\"bob\":\"👍\"}".into()).unwrap();
        assert_eq!(s.get_message_by_msg_id("m1".into()).unwrap().unwrap().reactions, "{\"bob\":\"👍\"}");

        s.delete_message_by_msg_id("m1".into()).unwrap();
        assert!(s.get_message_by_msg_id("m1".into()).unwrap().is_none());
    }

    #[test]
    fn outbox_and_status_markers() {
        let s = open();
        s.insert_message(msg("a", "p1", true, "t1", 1, 0)).unwrap();
        s.insert_message(msg("b", "p1", true, "t2", 2, 0)).unwrap();
        s.insert_message(msg("c", "p1", false, "in", 3, 1)).unwrap();
        let pending = s.get_pending_outgoing().unwrap();
        assert_eq!(pending.len(), 2);
        assert_eq!(pending[0].msg_id, "a"); // по возрастанию времени

        s.mark_outgoing_delivered("p1".into()).unwrap();
        // оба исходящих стали 2; входящее не тронуто
        assert_eq!(s.get_message_by_msg_id("a".into()).unwrap().unwrap().status, 2);
        s.mark_outgoing_read("p1".into()).unwrap();
        assert_eq!(s.get_message_by_msg_id("a".into()).unwrap().unwrap().status, 3);
        // delivered не понижает прочитанное
        s.mark_outgoing_delivered("p1".into()).unwrap();
        assert_eq!(s.get_message_by_msg_id("a".into()).unwrap().unwrap().status, 3);
    }

    #[test]
    fn chat_list_orders_and_unread() {
        let s = open();
        s.insert_chat(Chat {
            peer_id: "p1".into(), name: "Один".into(), kind: 0,
            is_pinned: false, is_muted: false, is_archived: false, unread_count: 0, avatar_file_id: None,
        }).unwrap();
        s.insert_chat(Chat {
            peer_id: "p2".into(), name: "Два".into(), kind: 0,
            is_pinned: true, is_muted: false, is_archived: false, unread_count: 0, avatar_file_id: None,
        }).unwrap();
        s.insert_message(msg("m1", "p1", false, "новее", 200, 1)).unwrap();
        s.insert_message(msg("m2", "p2", false, "старее", 100, 1)).unwrap();

        let list = s.get_chat_list().unwrap();
        assert_eq!(list.len(), 2);
        // закреплённый p2 — первым, несмотря на более старое сообщение
        assert_eq!(list[0].chat.peer_id, "p2");
        assert_eq!(list[0].last_text.as_deref(), Some("старее"));

        s.increment_unread("p1".into()).unwrap();
        s.increment_unread("p1".into()).unwrap();
        assert_eq!(s.get_chat("p1".into()).unwrap().unwrap().unread_count, 2);
        s.clear_unread("p1".into()).unwrap();
        assert_eq!(s.get_chat("p1".into()).unwrap().unwrap().unread_count, 0);

        s.delete_chat_and_messages("p1".into()).unwrap();
        assert!(s.get_chat("p1".into()).unwrap().is_none());
        assert!(s.get_messages_for_peer("p1".into()).unwrap().is_empty());
    }

    #[test]
    fn pinned_keys_tofu() {
        let s = open();
        assert!(s.pin_get("alice".into()).unwrap().is_none());
        s.pin_upsert(PinnedKey {
            peer_id: "alice".into(), public_key_b64: "KEY1".into(), pinned_at: 5,
            verified: false, previous_key_b64: None, changed_at: None,
        }).unwrap();
        let p = s.pin_get("alice".into()).unwrap().unwrap();
        assert_eq!(p.public_key_b64, "KEY1");
        assert!(!p.verified);
        s.pin_set_verified("alice".into(), true).unwrap();
        assert!(s.pin_get("alice".into()).unwrap().unwrap().verified);
        s.pin_delete("alice".into()).unwrap();
        assert!(s.pin_get("alice".into()).unwrap().is_none());
    }
}
