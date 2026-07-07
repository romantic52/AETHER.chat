# Ревью правок безопасности P0–P2 (Aether)

Сборка: `BUILD SUCCESSFUL`, `EXIT CODE 0`, `=== APK COPIED ===`, строк `e:` нет → `SecureMessenger_latest_debug.apk`.
Серверные правки (`server/main.py`) **ещё не задеплоены** — жду подтверждения после ревью.

---

## P0.1 — Хардкод SSH убран из ssh_cmd.py / deploy_server.py / scp_upload.py

Новые файлы: `.env` (креды, прежние значения), `.gitignore` (включает `.env`), `env_config.py` (загрузка `.env` + чтение env).

```diff
# ssh_cmd.py / scp_upload.py / deploy_server.py
-ip = "YOUR_SERVER_IP"
-password = "<REDACTED>"
-...client.connect('YOUR_SERVER_IP', username='root', password='<REDACTED>', timeout=10)
+from env_config import ssh_credentials
+ip, user, password = ssh_credentials()   # AETHER_SSH_HOST / AETHER_SSH_USER / AETHER_SSH_PASSWORD
```

`deploy_server.py` дополнительно: локальный путь к `server/main.py` теперь от `Path(__file__)`, а не захардкоженный абсолютный.

⚠️ Пароль уже засвечен в истории/файлах — рекомендую сменить пароль root на сервере (или перейти на ssh-ключи) отдельным шагом.

---

## P0.2 — Пароль больше не хранится (EncryptedSharedPreferences + токен)

`MainActivity.kt`:
```diff
-val sharedPrefs = getSharedPreferences("AetherPrefs", MODE_PRIVATE)
-val savedLogin = sharedPrefs.getString("saved_login", null)        // "server|username|password" открытым текстом
+// Миграция: удаляем старый небезопасный "server|username|password"
+val legacyPrefs = getSharedPreferences("AetherPrefs", MODE_PRIVATE)
+if (legacyPrefs.contains("saved_login")) legacyPrefs.edit().remove("saved_login").apply()
+val savedSession = remember { sessionPrefs.load() }                // SessionPrefs = EncryptedSharedPreferences

-if (rememberMe) sharedPrefs.edit().putString("saved_login", prefs).apply()   // с паролем
+// Пароль не сохраняем: только server|username + токен сессии (живёт 30 дней)
+if (rememberMe && token != null) sessionPrefs.save(prefs.substringBefore("|"), id, token)
+else sessionPrefs.clear()

 onLogout = {
+    sessionPrefs.clear()
```

`LoginScreen.kt` — авто-вход теперь по токену, без пароля:
```diff
-fun LoginScreen(savedLogin: String?, ...)
+fun LoginScreen(savedSession: SessionPrefs.Session?, ...)

-// parts = savedLogin.split("|"); password = parts[2]; api.login(username, password)
+api.token = savedSession.token
+val tokenValid = withContext(Dispatchers.IO) { api.heartbeat() }      // POST /users/me/heartbeat
+val kp = SecurePrefs(context, savedSession.username).loadKeys()
+if (tokenValid && kp != null) onLoginSuccess("server|username", kp, api, username, true)
+else error = "Сессия истекла / ключи не найдены — войдите заново"
```

`RelayApi.kt` — добавлен `heartbeat(): Boolean` (проверка валидности токена).

---

## P0.3 — SecurePrefs задействован для приватного ключа

`data/SecurePrefs.kt` переписан (был мёртвый код с pin/chat_log):
- `SecurePrefs(context, userId)` → `saveKeys(kp)` / `loadKeys()` / `clearKeys()` — ключи E2E в EncryptedSharedPreferences (мастер-ключ в Android Keystore).
- `SessionPrefs(context)` → `save(server, username, token)` / `load()` / `clear()`.

`LoginScreen.kt`, ручной вход/регистрация:
```diff
 val kp = if (encrypted_private_key есть) {
     decryptPrivateKey(..., password)            // как раньше (авторитетный источник)
 } else {
-    crypto.generateKeyPair()                    // тихая генерация
+    secure.loadKeys() ?: crypto.generateKeyPair()   // сперва локальные ключи; генерация остаётся до P3.9
 }
+secure.saveKeys(kp)                             // дальше логины идут без расшифровки паролем
```
Регистрация: ключи сразу сохраняются в SecurePrefs + сразу берётся токен (`api.login`).

---

## P1.4 — Авторизация на GET /download/{file_id}

`server/main.py`:
```diff
 @app.get("/download/{file_id}")
 @limiter.limit("100/minute")
-def download_file(request: Request, file_id: str) -> FileResponse:
+def download_file(request: Request, file_id: str, current_user: str = Depends(get_current_user)) -> FileResponse:
+    if not _FILE_ID_RE.match(file_id):           # бонус: защита от path traversal (uuid-формат)
+        raise HTTPException(404, "File not found")
```
Клиент `RelayApi.downloadFile` уже слал Bearer-токен — ничего не сломалось.

## P1.5 — Аватарки: явный публичный неймспейс (выбран на ревью)

`server/main.py`: `POST /avatars` (auth, лимит 5 МБ, файлы в `uploads/avatars/`) и `GET /avatars/{file_id}` — публичный, задокументирован комментарием у `AVATAR_DIR`. Приватные медиа — только `/upload`+`/download`.

Клиент:
- `ServerConfig.avatarUrl()` добавлен; `Avatar.kt`, `ProfileSettingsScreen`, `ProfileScreen`, `SearchScreen` → `/avatars/{id}`.
- `RelayApi.uploadAvatar()` добавлен; `ProfileSettingsScreen`/`ProfileScreen` грузят аватар через него (не через `uploadFile`).
- Попутно починен `SearchScreen`: был захардкожен `http://10.0.2.2:8000/download/...`.

⚠️ Старые аватарки (file_id в `uploads/`) перестанут отображаться — пользователям нужно перезалить фото.

## P1.6 — Лимит размера загрузки

`server/main.py`: общий хелпер `_save_upload()` — считает прочитанные байты, при превышении обрывает запись, удаляет частичный файл и возвращает `413`. `/upload` ≤ 50 МБ, `/avatars` ≤ 5 МБ.

---

## P2.7 — Проверка автора при edit

`MainActivity.kt`, `syncInbox`:
```diff
 if (obj != null && ptype == "edit") {
     val target = obj.optString("target")
     val newText = obj.optString("text")
-    if (target.isNotBlank()) dao.updateText(target, newText)
+    if (target.isNotBlank()) {
+        val original = dao.getMessageByMsgId(target)
+        if (original != null && !original.isOut && original.peerId.equals(msgPeerId, ignoreCase = true)) {
+            dao.updateText(target, newText)
+        }
+    }
     continue
 }
```
Edit применяется только к входящему сообщению из того же чата, что и отправитель контрола (для личных чатов `peerId == senderId`). Чужие edit к моим исходящим (`isOut`) игнорируются.

Замечание на будущее: контролы `reaction`/`read` имеют схожую проблему доверия к отправителю — предлагаю включить в следующий блок.

---

## Формат E2E-конвертов
Не менялся: `sender_pubkey_b64` / `nonce_b64` / `ciphertext_b64` как были.

# Дополнение: P3–P5 (после одобрения P0–P2)

## P3.8 — Минимальная длина пароля ≥ 8
`server/main.py`: `password: str = Field(min_length=8)` в `RegisterRequest` и `LoginRequest`.
`LoginScreen.kt`: проверка `password.length < 8` с ошибкой до запроса.
⚠️ Пользователи со старыми паролями короче 8 символов не смогут войти (422 на логине) — им нужен сброс пароля. Если это нежелательно, можно ослабить `LoginRequest` обратно до 4, оставив 8 только на регистрации.

## P3.9 — Тихая генерация ключа при логине убрана
`LoginScreen.kt`: при пустом `encrypted_private_key` берём локальные ключи из SecurePrefs; если их нет — явная ошибка («Вход без ключа невозможен…») вместо `generateKeyPair()`, который рассинхронизировал пару с серверным публичным ключом.

## P4.10 — Запрет cleartext-трафика
`AndroidManifest.xml`: `usesCleartextTraffic="false"` + `networkSecurityConfig`.
Новый `res/xml/network_security_config.xml`: base-config запрещает HTTP, исключения только для `10.0.2.2`/`localhost`/`127.0.0.1` (отладка).

## P4.11 — CORS
`server/main.py`: `allow_origins=["https://your-server.example.com", "https://YOUR_SERVER_IP"]` вместо `["*"]`.

## P4.12 — Rate-limit за реверс-прокси
`server/main.py`: `Limiter(key_func=_real_client_ip)` — X-Forwarded-For читается только если прямой клиент в `TRUSTED_PROXIES` ({127.0.0.1, ::1}), иначе заголовок игнорируется (защита от подделки).

## P5.13 — Группы скрыты из UI (выбран вариант «скрыть»)
Группового E2E нет: «ключ» группы — случайные байты, попадающие на сервер открытым текстом, механизм раздачи ключа участникам отсутствует, расшифровки групповых конвертов в `syncInbox` нет. Реализация — отдельная фича (раздача ключа под pubkey участников, ротация при выходе и т.д.).
- `RelayApi.searchUsers`: группы (не каналы) отфильтрованы из результатов поиска (`is_channel == false → skip`).
- `CreateGroupScreen`: переключатель «канал/группа» убран — создаются только каналы, добавлена поясняющая надпись.
Каналы (`channel_`) работают как раньше.

## Сборка и деплой P3–P5
- APK: `BUILD SUCCESSFUL`, `EXIT CODE 0`, `APK COPIED`, строк `e:` нет.
- Сервер: задеплоен `deploy_server.py` (креды из `.env`), `systemctl is-active` → `active`, `/health` отвечает.

## Осталось
P6 (TOFU-пиннинг ключей + цифры безопасности) — по договорённости: сначала дизайн-предложение, без кода.
