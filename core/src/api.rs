//! Сетевой слой ядра: HTTP-клиент relay-сервера. Блокирующий (ureq + rustls),
//! вызывается из IO-потока платформы. Единый для всех платформ — каждая платформа
//! больше не дублирует свой HTTP-клиент. Зеркалит endpoints из server/main.py.

use std::collections::HashMap;
use std::io::Read;
use std::sync::{Arc, Mutex};
use serde_json::{json, Value};

#[derive(Debug, Clone, uniffi::Error)]
pub enum ApiError {
    Network { msg: String },
    Http { code: u16, msg: String },
    Parse,
}

impl std::fmt::Display for ApiError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ApiError::Network { msg } => write!(f, "сеть: {}", msg),
            ApiError::Http { code, msg } => write!(f, "HTTP {}: {}", code, msg),
            ApiError::Parse => write!(f, "ошибка разбора ответа"),
        }
    }
}
impl std::error::Error for ApiError {}

#[derive(Debug, Clone, uniffi::Record)]
pub struct UserProfile {
    pub user_id: String,
    pub username: String,
    pub display_name: String,
    pub avatar_file_id: Option<String>,
    pub bio: Option<String>,
    pub last_active: Option<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct LoginResult {
    pub token: String,
    pub user_id: String,
    pub public_key_b64: String,
    pub encrypted_private_key_b64: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct InboxMessage {
    pub id: String,
    pub sender_id: String,
    pub recipient_id: String,
    pub sender_pubkey_b64: String,
    pub nonce_b64: String,
    pub ciphertext_b64: String,
    pub created_at: String,
    pub is_group_envelope: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct GroupInfo {
    pub id: String,
    pub name: String,
    pub is_channel: bool,
    pub encrypted_key_b64: String,
    pub role: String,
    pub linked_group_id: Option<String>,
    pub owner_id: String,
    pub description: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct GroupMember {
    pub user_id: String,
    pub username: String,
    pub display_name: String,
    pub avatar_file_id: Option<String>,
    pub role: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SearchResult {
    pub user_id: String,
    pub username: String,
    pub display_name: String,
    pub avatar_file_id: Option<String>,
    pub is_group: bool,
}

/// HTTP-клиент relay-сервера. Хранит токен сессии (потокобезопасно).
#[derive(uniffi::Object)]
pub struct ApiClient {
    base: String,
    token: Mutex<Option<String>>,
}

#[uniffi::export]
impl ApiClient {
    #[uniffi::constructor]
    pub fn new(base_url: String) -> Arc<Self> {
        Arc::new(Self {
            base: base_url.trim_end_matches('/').to_string(),
            token: Mutex::new(None),
        })
    }

    pub fn set_token(&self, token: String) {
        *self.token.lock().unwrap() = Some(token);
    }

    pub fn health(&self) -> Result<String, ApiError> {
        let resp = ureq::get(&format!("{}/health", self.base)).call().map_err(api_err)?;
        resp.into_string().map_err(|_| ApiError::Parse)
    }

    pub fn register(
        &self,
        user_id: String,
        public_key_b64: String,
        encrypted_private_key_b64: String,
        password: String,
    ) -> Result<(), ApiError> {
        self.post_json("/users/register", json!({
            "user_id": user_id,
            "public_key_b64": public_key_b64,
            "encrypted_private_key_b64": encrypted_private_key_b64,
            "password": password,
        }))?;
        Ok(())
    }

    pub fn login(&self, user_id: String, password: String) -> Result<LoginResult, ApiError> {
        let v = self.post_json("/users/login", json!({ "user_id": user_id, "password": password }))?;
        let token = v["token"].as_str().unwrap_or("").to_string();
        *self.token.lock().unwrap() = Some(token.clone());
        Ok(LoginResult {
            token,
            user_id: v["user_id"].as_str().unwrap_or("").to_string(),
            public_key_b64: v["public_key_b64"].as_str().unwrap_or("").to_string(),
            encrypted_private_key_b64: v["encrypted_private_key_b64"].as_str().unwrap_or("").to_string(),
        })
    }

    pub fn logout(&self) -> Result<(), ApiError> {
        let _ = self.post_empty("/logout");
        *self.token.lock().unwrap() = None;
        Ok(())
    }

    pub fn heartbeat(&self) -> Result<(), ApiError> {
        self.post_empty("/users/me/heartbeat")?;
        Ok(())
    }

    pub fn get_public_key(&self, user_id: String) -> Result<String, ApiError> {
        let v = self.get_json(&format!("/users/{}/public-key", user_id))?;
        Ok(v["public_key_b64"].as_str().unwrap_or("").to_string())
    }

    pub fn update_public_key(&self, public_key_b64: String) -> Result<(), ApiError> {
        self.put_json("/users/me/public-key", json!({ "public_key_b64": public_key_b64 }))?;
        Ok(())
    }

    pub fn update_profile(
        &self,
        username: Option<String>,
        display_name: Option<String>,
        avatar_file_id: Option<String>,
        bio: Option<String>,
    ) -> Result<(), ApiError> {
        let mut body = serde_json::Map::new();
        // пустая строка → JSON null (очистка поля), как в Kotlin RelayApi
        let put = |m: &mut serde_json::Map<String, Value>, k: &str, val: Option<String>| {
            if let Some(s) = val {
                m.insert(k.to_string(), if s.is_empty() { Value::Null } else { Value::String(s) });
            }
        };
        put(&mut body, "username", username);
        put(&mut body, "display_name", display_name);
        put(&mut body, "avatar_file_id", avatar_file_id);
        put(&mut body, "bio", bio);
        self.put_json("/users/me/profile", Value::Object(body))?;
        Ok(())
    }

    pub fn get_user_profile(&self, user_id: String) -> Result<UserProfile, ApiError> {
        let v = self.get_json(&format!("/users/{}/profile", user_id))?;
        Ok(UserProfile {
            user_id: v["user_id"].as_str().unwrap_or("").to_string(),
            username: v["username"].as_str().unwrap_or("").to_string(),
            display_name: v["display_name"].as_str().unwrap_or("").to_string(),
            avatar_file_id: v["avatar_file_id"].as_str().map(|s| s.to_string()),
            bio: v["bio"].as_str().map(|s| s.to_string()),
            last_active: v["last_active"].as_str().map(|s| s.to_string()),
        })
    }

    /// Отправка сообщения. envelope — поля конверта (sender_pubkey_b64/nonce_b64/
    /// ciphertext_b64 или is_group/nonce_b64/ciphertext_b64). Возвращает message_id.
    pub fn send_message(
        &self,
        sender_id: String,
        recipient_id: String,
        envelope: HashMap<String, String>,
        client_id: Option<String>,
    ) -> Result<String, ApiError> {
        let mut env = serde_json::Map::new();
        for (k, val) in envelope {
            env.insert(k, Value::String(val));
        }
        let mut body = json!({
            "sender_id": sender_id,
            "recipient_id": recipient_id,
            "envelope": Value::Object(env),
        });
        if let Some(cid) = client_id {
            body["client_id"] = json!(cid);
        }
        let v = self.post_json("/messages", body)?;
        Ok(v["message_id"].as_str().unwrap_or("").to_string())
    }

    pub fn fetch_inbox(&self, user_id: String) -> Result<Vec<InboxMessage>, ApiError> {
        let v = self.get_json(&format!("/messages/inbox/{}", user_id))?;
        let arr = v["messages"].as_array().cloned().unwrap_or_default();
        let mut out = Vec::with_capacity(arr.len());
        for m in arr {
            let env = &m["envelope"];
            out.push(InboxMessage {
                id: m["id"].as_str().unwrap_or("").to_string(),
                sender_id: m["sender_id"].as_str().unwrap_or("").to_string(),
                recipient_id: m["recipient_id"].as_str().unwrap_or("").to_string(),
                sender_pubkey_b64: env["sender_pubkey_b64"].as_str().unwrap_or("").to_string(),
                nonce_b64: env["nonce_b64"].as_str().unwrap_or("").to_string(),
                ciphertext_b64: env["ciphertext_b64"].as_str().unwrap_or("").to_string(),
                created_at: m["created_at"].as_str().unwrap_or("").to_string(),
                is_group_envelope: env.get("is_group").and_then(|x| x.as_str()) == Some("1"),
            });
        }
        Ok(out)
    }

    pub fn ack_messages(&self, message_ids: Vec<String>) -> Result<(), ApiError> {
        if message_ids.is_empty() {
            return Ok(());
        }
        self.post_json("/messages/ack", json!({ "message_ids": message_ids }))?;
        Ok(())
    }

    pub fn search_users(&self, query: String) -> Result<Vec<SearchResult>, ApiError> {
        let mut req = ureq::get(&format!("{}/users/search", self.base)).query("q", &query);
        if let Some(h) = self.auth_header() {
            req = req.set("Authorization", &h);
        }
        let v: Value = req.call().map_err(api_err)?.into_json().map_err(|_| ApiError::Parse)?;
        let mut out = Vec::new();
        if let Some(users) = v["users"].as_array() {
            for u in users {
                out.push(SearchResult {
                    user_id: u["user_id"].as_str().unwrap_or("").to_string(),
                    username: u["username"].as_str().unwrap_or("").to_string(),
                    display_name: u["display_name"].as_str().unwrap_or("").to_string(),
                    avatar_file_id: u["avatar_file_id"].as_str().map(|s| s.to_string()),
                    is_group: false,
                });
            }
        }
        if let Some(groups) = v["groups"].as_array() {
            for g in groups {
                out.push(SearchResult {
                    user_id: g["id"].as_str().unwrap_or("").to_string(),
                    username: String::new(),
                    display_name: g["name"].as_str().unwrap_or("").to_string(),
                    avatar_file_id: None,
                    is_group: true,
                });
            }
        }
        Ok(out)
    }

    pub fn get_my_groups(&self) -> Result<Vec<GroupInfo>, ApiError> {
        let v = self.get_json("/groups/me")?;
        let arr = v["groups"].as_array().cloned().unwrap_or_default();
        let mut out = Vec::with_capacity(arr.len());
        for g in arr {
            out.push(GroupInfo {
                id: g["id"].as_str().unwrap_or("").to_string(),
                name: g["name"].as_str().unwrap_or("").to_string(),
                is_channel: g["is_channel"].as_bool().unwrap_or(false),
                encrypted_key_b64: g["encrypted_key_b64"].as_str().unwrap_or("").to_string(),
                role: g["role"].as_str().unwrap_or("member").to_string(),
                linked_group_id: g["linked_group_id"].as_str().map(|s| s.to_string()),
                owner_id: g["owner_id"].as_str().unwrap_or("").to_string(),
                description: g["description"].as_str().unwrap_or("").to_string(),
            });
        }
        Ok(out)
    }

    /// Участники группы/канала.
    pub fn get_group_members(&self, group_id: String) -> Result<Vec<GroupMember>, ApiError> {
        let v = self.get_json(&format!("/groups/{}/members", group_id))?;
        let arr = v["members"].as_array().cloned().unwrap_or_default();
        let mut out = Vec::with_capacity(arr.len());
        for m in arr {
            out.push(GroupMember {
                user_id: m["user_id"].as_str().unwrap_or("").to_string(),
                username: m["username"].as_str().unwrap_or("").to_string(),
                display_name: m["display_name"].as_str().unwrap_or("").to_string(),
                avatar_file_id: m["avatar_file_id"].as_str().map(|s| s.to_string()),
                role: m["role"].as_str().unwrap_or("member").to_string(),
            });
        }
        Ok(out)
    }

    /// Удалить участника (только админ; владельца удалить нельзя — решает сервер).
    pub fn remove_group_member(&self, group_id: String, user_id: String) -> Result<(), ApiError> {
        let mut req = ureq::delete(&format!("{}/groups/{}/members/{}", self.base, group_id, user_id));
        if let Some(h) = self.auth_header() {
            req = req.set("Authorization", &h);
        }
        req.call().map_err(api_err)?;
        Ok(())
    }

    /// Изменить имя/описание группы (только админ).
    pub fn update_group(&self, group_id: String, name: Option<String>, description: Option<String>) -> Result<(), ApiError> {
        let mut body = serde_json::Map::new();
        if let Some(n) = name { body.insert("name".into(), Value::String(n)); }
        if let Some(d) = description { body.insert("description".into(), Value::String(d)); }
        self.put_json(&format!("/groups/{}", group_id), Value::Object(body))?;
        Ok(())
    }

    /// Выйти из группы (владелец выйти не может — решает сервер).
    pub fn leave_group(&self, group_id: String) -> Result<(), ApiError> {
        self.post_json(&format!("/groups/{}/leave", group_id), json!({}))?;
        Ok(())
    }

    /// Удалить группу целиком (только владелец).
    pub fn delete_group(&self, group_id: String) -> Result<(), ApiError> {
        let mut req = ureq::delete(&format!("{}/groups/{}", self.base, group_id));
        if let Some(h) = self.auth_header() {
            req = req.set("Authorization", &h);
        }
        req.call().map_err(api_err)?;
        Ok(())
    }

    pub fn create_group(
        &self,
        group_id: String,
        name: String,
        description: String,
        is_channel: bool,
        encrypted_key_b64: String,
        linked_group_id: Option<String>,
    ) -> Result<String, ApiError> {
        let mut body = json!({
            "id": group_id,
            "name": name,
            "description": description,
            "is_channel": is_channel,
            "encrypted_key_b64": encrypted_key_b64,
        });
        if let Some(l) = linked_group_id {
            body["linked_group_id"] = json!(l);
        }
        let v = self.post_json("/groups", body)?;
        Ok(v["group_id"].as_str().unwrap_or("").to_string())
    }

    pub fn add_group_member(
        &self,
        group_id: String,
        user_id: String,
        encrypted_key_b64: String,
        role: String,
    ) -> Result<(), ApiError> {
        self.post_json(
            &format!("/groups/{}/members", group_id),
            json!({ "user_id": user_id, "encrypted_key_b64": encrypted_key_b64, "role": role }),
        )?;
        Ok(())
    }

    /// Загрузка бинарного файла multipart/form-data (поле "file"). path —
    /// `/upload` (приватный, по токену) или `/avatars` (публичный). Возвращает file_id.
    /// Для крупных медиа платформа может оставить потоковую загрузку у себя
    /// (UniFFI копирует Vec<u8> целиком — нет смысла гонять гигабайты через FFI).
    pub fn upload(
        &self,
        path: String,
        filename: String,
        content_type: String,
        data: Vec<u8>,
    ) -> Result<String, ApiError> {
        let boundary = random_boundary();
        let body = build_multipart("file", &filename, &content_type, &data, &boundary);
        let mut req = ureq::post(&format!("{}{}", self.base, path));
        if let Some(h) = self.auth_header() {
            req = req.set("Authorization", &h);
        }
        let v: Value = req
            .set("Content-Type", &format!("multipart/form-data; boundary={}", boundary))
            .send_bytes(&body)
            .map_err(api_err)?
            .into_json()
            .map_err(|_| ApiError::Parse)?;
        Ok(v["file_id"].as_str().unwrap_or("").to_string())
    }

    /// Скачивание бинарного файла (`/download/{file_id}`). Возвращает сырые байты
    /// (платформа дальше расшифровывает AES-GCM).
    pub fn download(&self, file_id: String) -> Result<Vec<u8>, ApiError> {
        let mut req = ureq::get(&format!("{}/download/{}", self.base, file_id));
        if let Some(h) = self.auth_header() {
            req = req.set("Authorization", &h);
        }
        let resp = req.call().map_err(api_err)?;
        let mut buf = Vec::new();
        resp.into_reader()
            .read_to_end(&mut buf)
            .map_err(|_| ApiError::Parse)?;
        Ok(buf)
    }
}

// --- внутренние хелперы (не в FFI) ---
impl ApiClient {
    fn auth_header(&self) -> Option<String> {
        self.token.lock().unwrap().as_ref().map(|t| format!("Bearer {}", t))
    }

    fn get_json(&self, path: &str) -> Result<Value, ApiError> {
        let mut req = ureq::get(&format!("{}{}", self.base, path));
        if let Some(h) = self.auth_header() {
            req = req.set("Authorization", &h);
        }
        req.call().map_err(api_err)?.into_json().map_err(|_| ApiError::Parse)
    }

    fn post_json(&self, path: &str, body: Value) -> Result<Value, ApiError> {
        let mut req = ureq::post(&format!("{}{}", self.base, path));
        if let Some(h) = self.auth_header() {
            req = req.set("Authorization", &h);
        }
        req.send_json(body).map_err(api_err)?.into_json().map_err(|_| ApiError::Parse)
    }

    fn put_json(&self, path: &str, body: Value) -> Result<Value, ApiError> {
        let mut req = ureq::put(&format!("{}{}", self.base, path));
        if let Some(h) = self.auth_header() {
            req = req.set("Authorization", &h);
        }
        req.send_json(body).map_err(api_err)?.into_json().map_err(|_| ApiError::Parse)
    }

    fn post_empty(&self, path: &str) -> Result<Value, ApiError> {
        let mut req = ureq::post(&format!("{}{}", self.base, path));
        if let Some(h) = self.auth_header() {
            req = req.set("Authorization", &h);
        }
        req.call().map_err(api_err)?.into_json().map_err(|_| ApiError::Parse)
    }
}

/// Случайный boundary для multipart-тела.
fn random_boundary() -> String {
    use rand_core::RngCore;
    let mut b = [0u8; 12];
    rand_core::OsRng.fill_bytes(&mut b);
    let hex: String = b.iter().map(|x| format!("{:02x}", x)).collect();
    format!("----smcore{}", hex)
}

/// Собирает тело multipart/form-data с одним файловым полем.
fn build_multipart(field: &str, filename: &str, content_type: &str, data: &[u8], boundary: &str) -> Vec<u8> {
    let mut body = Vec::with_capacity(data.len() + 256);
    body.extend_from_slice(format!("--{}\r\n", boundary).as_bytes());
    body.extend_from_slice(
        format!(
            "Content-Disposition: form-data; name=\"{}\"; filename=\"{}\"\r\n",
            field, filename
        )
        .as_bytes(),
    );
    body.extend_from_slice(format!("Content-Type: {}\r\n\r\n", content_type).as_bytes());
    body.extend_from_slice(data);
    body.extend_from_slice(format!("\r\n--{}--\r\n", boundary).as_bytes());
    body
}

fn api_err(e: ureq::Error) -> ApiError {
    match e {
        ureq::Error::Status(code, resp) => ApiError::Http {
            code,
            msg: resp.into_string().unwrap_or_default(),
        },
        ureq::Error::Transport(t) => ApiError::Network { msg: t.to_string() },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // Сетевые тесты против живого сервера — вручную:
    //   cargo test --release -- --ignored --nocapture
    #[test]
    #[ignore]
    fn health_login_inbox_live() {
        let c = ApiClient::new("https://your-server.example.com".into());
        assert!(c.health().unwrap().contains("ok"));
        let login = c.login("your_test_account".into(), "your_test_password".into()).unwrap();
        assert_eq!(login.token.len(), 64);
        let prof = c.get_user_profile("your_test_account".into()).unwrap();
        assert_eq!(prof.user_id, "your_test_account");
        let inbox = c.fetch_inbox("your_test_account".into()).unwrap();
        println!("inbox: {} сообщений, профиль: {}", inbox.len(), prof.user_id);
    }

    // Проверка multipart upload → download roundtrip против живого сервера.
    #[test]
    #[ignore]
    fn upload_download_live() {
        let c = ApiClient::new("https://your-server.example.com".into());
        c.login("your_test_account".into(), "your_test_password".into()).unwrap();
        let data: Vec<u8> = (0u8..=255).cycle().take(4096).collect();
        let file_id = c
            .upload("/upload".into(), "blob.bin".into(), "application/octet-stream".into(), data.clone())
            .unwrap();
        assert!(!file_id.is_empty(), "сервер не вернул file_id");
        let back = c.download(file_id.clone()).unwrap();
        assert_eq!(back, data, "скачанные байты не совпали с загруженными");
        println!("upload/download OK: file_id={}, {} байт roundtrip", file_id, back.len());
    }
}
