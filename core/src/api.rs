//! HTTP-клиент сервера (FastAPI «Secure Messenger Relay»).
//! Все методы блокирующие — Swift зовёт их из актора вне main.

use crate::CoreError;
use serde::Deserialize;
use std::sync::RwLock;
use std::time::Duration;

#[derive(uniffi::Record, Clone)]
pub struct AuthSession {
    pub token: String,
    pub user_id: String,
    pub public_key_b64: String,
    pub encrypted_private_key_b64: String,
}

#[derive(uniffi::Record, Clone, Deserialize)]
pub struct Profile {
    pub user_id: String,
    #[serde(default)]
    pub username: Option<String>,
    #[serde(default)]
    pub display_name: Option<String>,
    #[serde(default)]
    pub avatar_file_id: Option<String>,
    #[serde(default)]
    pub bio: Option<String>,
    #[serde(default)]
    pub last_active: Option<String>,
    #[serde(default)]
    pub public_key_b64: Option<String>,
}

#[derive(uniffi::Record, Clone, Deserialize)]
pub struct InboxItem {
    pub id: String,
    pub sender_id: String,
    pub recipient_id: String,
    /// Конверт как JSON-строка — вскрывается protocol::open_envelope.
    #[serde(deserialize_with = "raw_json")]
    pub envelope: String,
    #[serde(default)]
    pub created_at: Option<String>,
}

fn raw_json<'de, D: serde::Deserializer<'de>>(d: D) -> Result<String, D::Error> {
    let v = serde_json::Value::deserialize(d)?;
    Ok(v.to_string())
}

/// Непустое строковое поле или None (сервер отдаёт null у легаси-записей).
fn opt_str(v: &serde_json::Value, key: &str) -> Option<String> {
    v[key].as_str().filter(|s| !s.is_empty()).map(str::to_string)
}

fn parse_bundle(v: &serde_json::Value) -> PrekeyBundle {
    PrekeyBundle {
        identity_key_b64: v["identity_key_b64"].as_str().unwrap_or_default().to_string(),
        one_time_key_id: v["one_time_key"]["key_id"].as_str().unwrap_or_default().to_string(),
        one_time_key_b64: v["one_time_key"]["key_b64"].as_str().unwrap_or_default().to_string(),
        ed25519_key_b64: opt_str(v, "ed25519_key_b64"),
        identity_sig_b64: opt_str(v, "identity_sig_b64"),
        one_time_key_sig_b64: opt_str(&v["one_time_key"], "sig_b64"),
        master_key_b64: opt_str(v, "master_key_b64"),
        device_sig_b64: opt_str(v, "device_sig_b64"),
    }
}

/// Prekey-bundle пира для установки Olm-сессии (identity + один one-time key).
/// Поля подписи (SEC HIGH-2) — None у легаси-бандлов, загруженных до апдейта;
/// клиент обязан верифицировать их через olm_verify_prekey_bundle перед сессией.
#[derive(uniffi::Record)]
pub struct PrekeyBundle {
    pub identity_key_b64: String,
    pub one_time_key_id: String,
    pub one_time_key_b64: String,
    pub ed25519_key_b64: Option<String>,
    pub identity_sig_b64: Option<String>,
    pub one_time_key_sig_b64: Option<String>,
    /// Cross-signing (P8): мастер-ключ аккаунта пира и подпись им этого устройства.
    pub master_key_b64: Option<String>,
    pub device_sig_b64: Option<String>,
}

/// Крипто-устройство аккаунта (multi-device): свой Olm-аккаунт на устройство.
#[derive(uniffi::Record)]
pub struct DeviceInfo {
    pub device_id: String,
    pub identity_key_b64: String,
    pub ed25519_key_b64: Option<String>,
    pub identity_sig_b64: Option<String>,
    pub master_key_b64: Option<String>,
    pub device_sig_b64: Option<String>,
}

#[derive(uniffi::Object)]
pub struct ApiClient {
    base: String,
    agent: ureq::Agent,
    token: RwLock<Option<String>>,
    user_id: RwLock<Option<String>>,
}

impl ApiClient {
    fn auth(&self) -> Result<String, CoreError> {
        self.token
            .read()
            .unwrap()
            .clone()
            .map(|t| format!("Bearer {t}"))
            .ok_or(CoreError::Api { status: 401, msg: "нет сессии".into() })
    }

    fn me(&self) -> Result<String, CoreError> {
        self.user_id
            .read()
            .unwrap()
            .clone()
            .ok_or(CoreError::Api { status: 401, msg: "нет сессии".into() })
    }

    fn call(&self, req: ureq::Request, body: Option<serde_json::Value>) -> Result<serde_json::Value, CoreError> {
        let res = match body {
            Some(b) => req.send_json(b),
            None => req.call(),
        };
        match res {
            Ok(r) => r
                .into_json::<serde_json::Value>()
                .map_err(|e| CoreError::Network { msg: e.to_string() }),
            Err(ureq::Error::Status(code, r)) => {
                let msg = r.into_string().unwrap_or_default();
                Err(CoreError::Api { status: code, msg })
            }
            Err(e) => Err(CoreError::Network { msg: e.to_string() }),
        }
    }

    fn get(&self, path: &str) -> Result<serde_json::Value, CoreError> {
        let req = self
            .agent
            .get(&format!("{}{}", self.base, path))
            .set("Authorization", &self.auth()?);
        self.call(req, None)
    }

    fn post(&self, path: &str, body: serde_json::Value) -> Result<serde_json::Value, CoreError> {
        let req = self
            .agent
            .post(&format!("{}{}", self.base, path))
            .set("Authorization", &self.auth()?);
        self.call(req, Some(body))
    }

    fn put(&self, path: &str, body: serde_json::Value) -> Result<serde_json::Value, CoreError> {
        let req = self
            .agent
            .put(&format!("{}{}", self.base, path))
            .set("Authorization", &self.auth()?);
        self.call(req, Some(body))
    }

    fn delete(&self, path: &str) -> Result<serde_json::Value, CoreError> {
        let req = self
            .agent
            .delete(&format!("{}{}", self.base, path))
            .set("Authorization", &self.auth()?);
        self.call(req, None)
    }

    fn store_session(&self, v: &serde_json::Value) -> Result<AuthSession, CoreError> {
        let s = AuthSession {
            token: v["token"].as_str().unwrap_or_default().to_string(),
            user_id: v["user_id"].as_str().unwrap_or_default().to_string(),
            public_key_b64: v["public_key_b64"].as_str().unwrap_or_default().to_string(),
            encrypted_private_key_b64: v["encrypted_private_key_b64"].as_str().unwrap_or_default().to_string(),
        };
        if s.token.is_empty() {
            return Err(CoreError::Api { status: 500, msg: format!("нет токена в ответе: {v}") });
        }
        *self.token.write().unwrap() = Some(s.token.clone());
        *self.user_id.write().unwrap() = Some(s.user_id.clone());
        Ok(s)
    }
}

#[uniffi::export]
impl ApiClient {
    #[uniffi::constructor]
    pub fn new(base_url: String) -> Self {
        ApiClient {
            base: base_url.trim_end_matches('/').to_string(),
            agent: ureq::AgentBuilder::new()
                .timeout_connect(Duration::from_secs(10))
                .timeout(Duration::from_secs(30))
                .build(),
            token: RwLock::new(None),
            user_id: RwLock::new(None),
        }
    }

    /// Восстановление сессии из Keychain без повторного логина.
    /// Только токен (Android-совместимый вход): user_id остаётся прежним.
    pub fn set_token(&self, token: String) {
        *self.token.write().unwrap() = Some(token);
    }

    /// Пинг сервера: доступен ли (для выбора/проверки self-hosted сервера).
    pub fn health(&self) -> Result<(), CoreError> {
        self.get("/health")?;
        Ok(())
    }

    pub fn set_session(&self, token: String, user_id: String) {
        *self.token.write().unwrap() = Some(token);
        *self.user_id.write().unwrap() = Some(user_id);
    }

    pub fn register(
        &self,
        user_id: String,
        password: String,
        public_key_b64: String,
        encrypted_private_key_b64: String,
    ) -> Result<AuthSession, CoreError> {
        let req = self.agent.post(&format!("{}/users/register", self.base));
        let v = self.call(
            req,
            Some(serde_json::json!({
                "user_id": user_id,
                "password": password,
                "public_key_b64": public_key_b64,
                "encrypted_private_key_b64": encrypted_private_key_b64,
            })),
        )?;
        // Сервер на /register возвращает {ok, user_id} без токена — устанавливаем сессию логином.
        if v.get("token").and_then(|t| t.as_str()).map_or(true, str::is_empty) {
            return self.login(user_id, password);
        }
        self.store_session(&v)
    }

    pub fn login(&self, user_id: String, password: String) -> Result<AuthSession, CoreError> {
        self.login_totp(user_id, password, None)
    }

    /// Логин с опциональным 2FA-кодом. Сервер отвечает 401 detail=totp_required,
    /// когда на аккаунте включена 2FA — клиент повторяет вызов с кодом.
    pub fn login_totp(&self, user_id: String, password: String, totp_code: Option<String>) -> Result<AuthSession, CoreError> {
        let req = self.agent.post(&format!("{}/users/login", self.base));
        let mut body = serde_json::json!({ "user_id": user_id, "password": password });
        if let Some(code) = totp_code {
            body["totp_code"] = code.into();
        }
        let v = self.call(req, Some(body))?;
        self.store_session(&v)
    }

    pub fn logout(&self) -> Result<(), CoreError> {
        let _ = self.post("/logout", serde_json::json!({}));
        *self.token.write().unwrap() = None;
        *self.user_id.write().unwrap() = None;
        Ok(())
    }

    pub fn heartbeat(&self) -> Result<(), CoreError> {
        self.post("/users/me/heartbeat", serde_json::json!({})).map(|_| ())
    }

    pub fn get_public_key(&self, user_id: String) -> Result<String, CoreError> {
        let v = self.get(&format!("/users/{user_id}/public-key"))?;
        v["public_key_b64"]
            .as_str()
            .map(str::to_string)
            .ok_or(CoreError::Api { status: 404, msg: "нет ключа".into() })
    }

    pub fn update_public_key(&self, public_key_b64: String) -> Result<(), CoreError> {
        self.put("/users/me/public-key", serde_json::json!({ "public_key_b64": public_key_b64 }))
            .map(|_| ())
    }

    // --- Prekey-директория (Double Ratchet / Olm) ---

    /// Опубликовать Olm identity + пачку one-time keys (JSON {key_id: pub_b64}).
    pub fn upload_keys(&self, identity_key_b64: String, one_time_keys_json: String) -> Result<(), CoreError> {
        let otks: serde_json::Value = serde_json::from_str(&one_time_keys_json).map_err(CoreError::bad)?;
        self.put("/keys/upload", serde_json::json!({
            "identity_key_b64": identity_key_b64,
            "one_time_keys": otks,
        }))
        .map(|_| ())
    }

    /// Сколько наших one-time keys ещё лежит на сервере (для пополнения).
    pub fn keys_count(&self) -> Result<u32, CoreError> {
        let v = self.get("/keys/count")?;
        Ok(v["count"].as_u64().unwrap_or(0) as u32)
    }

    /// Забрать prekey-bundle пира (identity + один OTK, сервер его удаляет).
    pub fn claim_keys(&self, user_id: String) -> Result<PrekeyBundle, CoreError> {
        let v = self.post(&format!("/keys/claim/{user_id}"), serde_json::json!({}))?;
        Ok(parse_bundle(&v))
    }

    pub fn update_profile(
        &self,
        username: Option<String>,
        display_name: Option<String>,
        avatar_file_id: Option<String>,
        bio: Option<String>,
    ) -> Result<(), CoreError> {
        let mut body = serde_json::Map::new();
        if let Some(u) = username { body.insert("username".into(), u.into()); }
        if let Some(d) = display_name { body.insert("display_name".into(), d.into()); }
        if let Some(a) = avatar_file_id { body.insert("avatar_file_id".into(), a.into()); }
        if let Some(b) = bio { body.insert("bio".into(), b.into()); }
        self.put("/users/me/profile", serde_json::Value::Object(body)).map(|_| ())
    }

    pub fn get_user_profile(&self, user_id: String) -> Result<Profile, CoreError> {
        let v = self.get(&format!("/users/{user_id}/profile"))?;
        serde_json::from_value(v).map_err(CoreError::bad)
    }

    pub fn search_users(&self, query: String) -> Result<Vec<Profile>, CoreError> {
        let v = self.get(&format!("/users/search?q={}", urlencode(&query)))?;
        let users = v.get("users").cloned().unwrap_or(v);
        serde_json::from_value(users).map_err(CoreError::bad)
    }

    /// Отправка конверта. `envelope_json` — от protocol::seal_*. Возвращает message_id.
    pub fn send_message(
        &self,
        recipient_id: String,
        envelope_json: String,
        client_id: Option<String>,
    ) -> Result<String, CoreError> {
        let envelope: serde_json::Value = serde_json::from_str(&envelope_json).map_err(CoreError::bad)?;
        let mut body = serde_json::json!({
            "sender_id": self.me()?,
            "recipient_id": recipient_id,
            "envelope": envelope,
        });
        if let Some(cid) = client_id {
            body["client_id"] = cid.into();
        }
        let v = self.post("/messages", body)?;
        v["message_id"]
            .as_str()
            .map(str::to_string)
            .ok_or(CoreError::Api { status: 500, msg: format!("нет message_id: {v}") })
    }

    pub fn fetch_inbox(&self, since: Option<String>) -> Result<Vec<InboxItem>, CoreError> {
        let me = self.me()?;
        let path = match since {
            Some(s) => format!("/messages/inbox/{me}?since={}", urlencode(&s)),
            None => format!("/messages/inbox/{me}"),
        };
        let v = self.get(&path)?;
        serde_json::from_value(v["messages"].clone()).map_err(CoreError::bad)
    }

    pub fn ack_messages(&self, message_ids: Vec<String>) -> Result<(), CoreError> {
        if message_ids.is_empty() {
            return Ok(());
        }
        self.post("/messages/ack", serde_json::json!({ "message_ids": message_ids }))
            .map(|_| ())
    }

    pub fn delete_history(&self, peer_id: String) -> Result<(), CoreError> {
        self.delete(&format!("/messages/history/{peer_id}")).map(|_| ())
    }

    // --- Multi-device: device-aware варианты. Старые методы выше остаются
    // для клиентов-однодевайсников (сервер маппит их на device 'primary'). ---

    /// Все крипто-устройства пользователя (для fanout-шифрования).
    pub fn list_devices(&self, user_id: String) -> Result<Vec<DeviceInfo>, CoreError> {
        let v = self.get(&format!("/users/{user_id}/devices"))?;
        let devices = v["devices"].as_array().cloned().unwrap_or_default();
        Ok(devices
            .iter()
            .map(|d| DeviceInfo {
                device_id: d["device_id"].as_str().unwrap_or_default().to_string(),
                identity_key_b64: d["identity_key_b64"].as_str().unwrap_or_default().to_string(),
                ed25519_key_b64: opt_str(d, "ed25519_key_b64"),
                identity_sig_b64: opt_str(d, "identity_sig_b64"),
                master_key_b64: opt_str(d, "master_key_b64"),
                device_sig_b64: opt_str(d, "device_sig_b64"),
            })
            .collect())
    }

    pub fn upload_keys_device(
        &self,
        identity_key_b64: String,
        one_time_keys_json: String,
        device_id: String,
    ) -> Result<(), CoreError> {
        let otks: serde_json::Value = serde_json::from_str(&one_time_keys_json).map_err(CoreError::bad)?;
        self.put("/keys/upload", serde_json::json!({
            "identity_key_b64": identity_key_b64,
            "one_time_keys": otks,
            "device_id": device_id,
        }))
        .map(|_| ())
    }

    /// Публикация подписанного бандла (SEC HIGH-2): ed25519 + подпись identity +
    /// per-OTK подписи (JSON {key_id: sig_b64}, ключи совпадают с one_time_keys_json).
    #[allow(clippy::too_many_arguments)]
    pub fn upload_keys_device_signed(
        &self,
        identity_key_b64: String,
        ed25519_key_b64: String,
        identity_sig_b64: String,
        one_time_keys_json: String,
        otk_signatures_json: String,
        device_id: String,
        master_key_b64: String,
        device_sig_b64: String,
    ) -> Result<(), CoreError> {
        let otks: serde_json::Value = serde_json::from_str(&one_time_keys_json).map_err(CoreError::bad)?;
        let sigs: serde_json::Value = serde_json::from_str(&otk_signatures_json).map_err(CoreError::bad)?;
        self.put("/keys/upload", serde_json::json!({
            "identity_key_b64": identity_key_b64,
            "ed25519_key_b64": ed25519_key_b64,
            "identity_sig_b64": identity_sig_b64,
            "one_time_keys": otks,
            "otk_signatures": sigs,
            "device_id": device_id,
            "master_key_b64": master_key_b64,
            "device_sig_b64": device_sig_b64,
        }))
        .map(|_| ())
    }

    pub fn keys_count_device(&self, device_id: String) -> Result<u32, CoreError> {
        let v = self.get(&format!("/keys/count?device_id={}", urlencode(&device_id)))?;
        Ok(v["count"].as_u64().unwrap_or(0) as u32)
    }

    pub fn claim_keys_device(&self, user_id: String, device_id: String) -> Result<PrekeyBundle, CoreError> {
        let v = self.post(
            &format!("/keys/claim/{user_id}?device_id={}", urlencode(&device_id)),
            serde_json::json!({}),
        )?;
        Ok(parse_bundle(&v))
    }

    /// Отправка адресной копии конверта устройству получателя.
    pub fn send_message_device(
        &self,
        recipient_id: String,
        envelope_json: String,
        client_id: Option<String>,
        target_device_id: String,
    ) -> Result<String, CoreError> {
        let envelope: serde_json::Value = serde_json::from_str(&envelope_json).map_err(CoreError::bad)?;
        let mut body = serde_json::json!({
            "sender_id": self.me()?,
            "recipient_id": recipient_id,
            "envelope": envelope,
            "target_device_id": target_device_id,
        });
        if let Some(cid) = client_id {
            body["client_id"] = cid.into();
        }
        let v = self.post("/messages", body)?;
        v["message_id"]
            .as_str()
            .map(str::to_string)
            .ok_or(CoreError::Api { status: 500, msg: format!("нет message_id: {v}") })
    }

    pub fn fetch_inbox_device(&self, since: Option<String>, device_id: String) -> Result<Vec<InboxItem>, CoreError> {
        let me = self.me()?;
        let dev = urlencode(&device_id);
        let path = match since {
            Some(s) => format!("/messages/inbox/{me}?since={}&device_id={dev}", urlencode(&s)),
            None => format!("/messages/inbox/{me}?device_id={dev}"),
        };
        let v = self.get(&path)?;
        serde_json::from_value(v["messages"].clone()).map_err(CoreError::bad)
    }

    pub fn ack_messages_device(&self, message_ids: Vec<String>, device_id: String) -> Result<(), CoreError> {
        if message_ids.is_empty() {
            return Ok(());
        }
        self.post("/messages/ack", serde_json::json!({ "message_ids": message_ids, "device_id": device_id }))
            .map(|_| ())
    }

    // --- Контроль сессий / 2FA / wipe. Формы ответов гибкие → JSON-строки. ---

    /// Привязать текущую сессию к своему крипто-устройству (адресный выход).
    pub fn bind_session_device(&self, device_id: String) -> Result<(), CoreError> {
        self.put("/sessions/me/device", serde_json::json!({ "device_id": device_id })).map(|_| ())
    }

    /// {devices:[{device_id,device_created_at,sessions,current}], can_kick, kick_min_hours, unbound_sessions}
    pub fn list_sessions(&self) -> Result<String, CoreError> {
        Ok(self.get("/sessions/me")?.to_string())
    }

    /// Выкинуть устройство (сервер применяет правило 12 часов).
    pub fn kick_device(&self, device_id: String) -> Result<(), CoreError> {
        self.delete(&format!("/sessions/device/{device_id}")).map(|_| ())
    }

    pub fn totp_status(&self) -> Result<bool, CoreError> {
        Ok(self.get("/2fa/status")?["enabled"].as_bool().unwrap_or(false))
    }

    /// {secret, otpauth_uri} — новый секрет (2FA ещё не включена).
    pub fn totp_setup(&self) -> Result<String, CoreError> {
        Ok(self.post("/2fa/setup", serde_json::json!({}))?.to_string())
    }

    pub fn totp_enable(&self, code: String) -> Result<(), CoreError> {
        self.post("/2fa/enable", serde_json::json!({ "code": code })).map(|_| ())
    }

    pub fn totp_disable(&self, code: String) -> Result<(), CoreError> {
        self.post("/2fa/disable", serde_json::json!({ "code": code })).map(|_| ())
    }

    /// «Удалить всё» по паролю: чистка сообщений, выход из групп, отзыв сессий.
    pub fn wipe_account(&self, password: String) -> Result<(), CoreError> {
        self.post("/users/me/wipe", serde_json::json!({ "password": password })).map(|_| ())
    }

    // Группы: формы ответов гибкие, отдаём JSON-строки — парсит Swift.
    pub fn create_group(
        &self,
        id: String,
        name: String,
        description: Option<String>,
        is_channel: bool,
        encrypted_key_b64: String,
        linked_group_id: Option<String>,
    ) -> Result<String, CoreError> {
        let mut body = serde_json::json!({
            "id": id, "name": name, "is_channel": is_channel,
            "encrypted_key_b64": encrypted_key_b64,
        });
        if let Some(d) = description { body["description"] = d.into(); }
        if let Some(l) = linked_group_id { body["linked_group_id"] = l.into(); }
        Ok(self.post("/groups", body)?.to_string())
    }

    pub fn add_group_member(
        &self,
        group_id: String,
        user_id: String,
        encrypted_key_b64: String,
        role: Option<String>,
    ) -> Result<(), CoreError> {
        let mut body = serde_json::json!({ "user_id": user_id, "encrypted_key_b64": encrypted_key_b64 });
        if let Some(r) = role { body["role"] = r.into(); }
        self.post(&format!("/groups/{group_id}/members"), body).map(|_| ())
    }

    pub fn get_my_groups(&self) -> Result<String, CoreError> {
        Ok(self.get("/groups/me")?.to_string())
    }

    pub fn get_group_members(&self, group_id: String) -> Result<String, CoreError> {
        Ok(self.get(&format!("/groups/{group_id}/members"))?.to_string())
    }

    pub fn remove_group_member(&self, group_id: String, user_id: String) -> Result<(), CoreError> {
        self.delete(&format!("/groups/{group_id}/members/{user_id}")).map(|_| ())
    }

    pub fn update_group(&self, group_id: String, name: Option<String>, description: Option<String>) -> Result<(), CoreError> {
        let mut body = serde_json::Map::new();
        if let Some(n) = name { body.insert("name".into(), n.into()); }
        if let Some(d) = description { body.insert("description".into(), d.into()); }
        self.put(&format!("/groups/{group_id}"), serde_json::Value::Object(body)).map(|_| ())
    }

    pub fn leave_group(&self, group_id: String) -> Result<(), CoreError> {
        self.post(&format!("/groups/{group_id}/leave"), serde_json::json!({})).map(|_| ())
    }

    pub fn delete_group(&self, group_id: String) -> Result<(), CoreError> {
        self.delete(&format!("/groups/{group_id}")).map(|_| ())
    }

    /// Загрузка сырого шифротекста медиа. Возвращает file_id.
    pub fn upload(&self, data: Vec<u8>) -> Result<String, CoreError> {
        let v = self.multipart("/upload", data, "application/octet-stream", "enc.bin")?;
        v["file_id"]
            .as_str()
            .map(str::to_string)
            .ok_or(CoreError::Api { status: 500, msg: format!("нет file_id: {v}") })
    }

    pub fn download(&self, file_id: String) -> Result<Vec<u8>, CoreError> {
        let req = self
            .agent
            .get(&format!("{}/download/{file_id}", self.base))
            .set("Authorization", &self.auth()?);
        match req.call() {
            Ok(r) => {
                let mut buf = Vec::new();
                use std::io::Read;
                r.into_reader()
                    .take(256 * 1024 * 1024)
                    .read_to_end(&mut buf)
                    .map_err(|e| CoreError::Network { msg: e.to_string() })?;
                Ok(buf)
            }
            Err(ureq::Error::Status(code, r)) => Err(CoreError::Api {
                status: code,
                msg: r.into_string().unwrap_or_default(),
            }),
            Err(e) => Err(CoreError::Network { msg: e.to_string() }),
        }
    }

    /// Аватары публичные (без E2E).
    pub fn upload_avatar(&self, data: Vec<u8>, mime: String) -> Result<String, CoreError> {
        let v = self.multipart("/avatars", data, &mime, "avatar")?;
        v["file_id"]
            .as_str()
            .map(str::to_string)
            .ok_or(CoreError::Api { status: 500, msg: format!("нет file_id: {v}") })
    }

    pub fn avatar_url(&self, file_id: String) -> String {
        format!("{}/avatars/{file_id}", self.base)
    }
}

impl ApiClient {
    fn multipart(&self, path: &str, data: Vec<u8>, mime: &str, filename: &str) -> Result<serde_json::Value, CoreError> {
        let boundary = format!("----aether{}", crate::crypto::random_key_b64());
        let mut body = Vec::with_capacity(data.len() + 512);
        body.extend_from_slice(format!(
            "--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\nContent-Type: {mime}\r\n\r\n"
        ).as_bytes());
        body.extend_from_slice(&data);
        body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());
        let req = self
            .agent
            .post(&format!("{}{}", self.base, path))
            .set("Authorization", &self.auth()?)
            .set("Content-Type", &format!("multipart/form-data; boundary={boundary}"));
        match req.send_bytes(&body) {
            Ok(r) => r
                .into_json::<serde_json::Value>()
                .map_err(|e| CoreError::Network { msg: e.to_string() }),
            Err(ureq::Error::Status(code, r)) => Err(CoreError::Api {
                status: code,
                msg: r.into_string().unwrap_or_default(),
            }),
            Err(e) => Err(CoreError::Network { msg: e.to_string() }),
        }
    }
}

fn urlencode(s: &str) -> String {
    let mut out = String::with_capacity(s.len() * 3);
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => out.push(b as char),
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}
