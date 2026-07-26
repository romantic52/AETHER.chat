# Единый wire-протокол (Android ↔ десктоп ↔ iOS ↔ web)

Канонический формат задаёт **ядро** (`core/src/protocol.rs`, `wire_encode`/`wire_decode`);
Android, десктоп и iOS используют его напрямую, веб приводится к нему адаптером.
Всё ниже — **расшифрованный** JSON внутри конверта (plaintext, который видит только
получатель). Конверт на сервере — непрозрачный шифртекст.

## Матрица шифрования: кто с кем и чем

| Канал | Шифрование | Конверт | Клиенты |
|-------|-----------|---------|---------|
| Личка (актуальная) | **Olm / Double Ratchet** (vodozemac, X3DH + DR), копия на **каждое устройство** получателя | `{ratchet:"1", olm_identity, sender_device, type:0\|1, body_b64}` | Android, десктоп, iOS |
| Личка (legacy) | `crypto_box` (X25519) на аккаунтный ключ | `{sender_pubkey_b64, nonce_b64, ciphertext_b64}` | **только веб**; сервер принимает лишь при `AETHER_ALLOW_LEGACY_DIRECT`. Ядровые клиенты такие конверты **читают, но не отправляют** |
| Группы и каналы | AES-256-GCM общим ключом группы | `{is_group:"1", nonce_b64, ciphertext_b64}` | все |
| Ключ группы участнику | `crypto_box` на **аккаунтный** X25519-паблик участника | `{sender_pubkey_b64, nonce_b64, ciphertext_b64}` (внутри — base64 ключа) | все |
| Медиа | AES-256-GCM разовым `sym_key`, файл в `/upload` | `media{file_id, sym_key, nonce, mime_type, kind}` | все |
| Бэкап аккаунтного ключа | PBKDF2 100k + AES-GCM паролем, формат `salt:iv:ct` | `encrypted_private_key_b64` у пользователя | все |

Следствие: **веб не участвует в multi-device**. У него нет Olm и `device_id`, поэтому
его личные сообщения ходят только по legacy-каналу и только при включённом флаге
сервера. Группы и медиа у веба общие с остальными.

## Идентичность и устройства

- Аккаунт: X25519-пара. Приватник живёт на устройстве; на сервере — только
  зашифрованный паролем бэкап. **Групповые ключи разворачиваются именно им** —
  поэтому он обязан попадать на каждое новое устройство (см. pairing ниже).
- Устройство: собственный Olm-аккаунт. `crypto_devices(user_id, device_id,
  identity_key_b64)` + `one_time_keys`. `device_id`: `primary` (первая установка),
  `android-<10hex>`, `desktop-<8hex>` (выдаёт сервер при QR-привязке).
- Отправка в личку — клиентский fanout: `GET /users/{peer}/devices` →
  `POST /keys/claim/{peer}?device_id=` → отдельная Olm-копия каждому устройству →
  `POST /messages` с `target_device_id`. Сбой запроса директории — временная
  ошибка (ретрай), а не отправка «только на primary».
- Инбокс и ACK — per-device (`message_receipts`), сессия привязывается к устройству
  (`PUT /sessions/me/device`), «выкинуть» устройство — `DELETE /sessions/device/{id}`
  (анти-вор: с устройства моложе 12 ч выкидывать другие нельзя).
- Сменить `identity_key_b64` чужого слота нельзя: сервер требует, чтобы сессия
  была привязана к тому же `device_id` (иначе 403).

## Привязка устройства по одноразовому QR

Новое устройство не спрашивает пароль: сессию и аккаунтный ключ выдаёт уже
залогиненный телефон по E2E-каналу.

1. Новый клиент: эфемерная X25519-пара → `POST /pairing/start {eph_pub_b64}` →
   `{pairing_id, pairing_secret}` (secret в БД только как sha256-хэш, TTL 120 с).
   QR: `aether://pair?v=1&pid=<pairing_id>&sec=<secret>&pub=<eph_pub_b64>&host=<server>`.
2. Телефон сканирует, показывает экран подтверждения (платформа, сервер, время;
   PIN/биометрия при включённом AppLock) и шлёт
   `POST /pairing/approve {pairing_id, pairing_secret, encrypted_bundle_b64, platform}`.
   Bundle = `crypto_box(phone_eph → desktop_eph)` над
   `{v:1, user_id, public_b64, private_b64}` — аккаунтная пара. Сервер видит только
   шифртекст. Подтверждение с доверенного устройства заменяет TOTP.
3. Сервер выдаёт `device_id` (`<platform>-<8hex>`), создаёт сессию, привязанную к
   нему, и шлёт всем устройствам аккаунта WS-событие
   `device_added {device_id, platform}`.
4. Новый клиент забирает результат `GET /pairing/{pid}/status?sec=` (long-poll 25 с).
   `approved` отдаётся **ровно один раз**: токен и bundle затираются (`claimed`).
5. Дальше — обычный путь устройства: bind сессии → `PUT /keys/upload` со своим
   `device_id` → `GET /users/me/dialogs` (раскладка чатов; **история не переносится**).

Инварианты: одноразовость, TTL ≤ 2 мин с авто-обновлением QR, пароль не участвует,
секрет сверяется constant-time, `pairing_secret` — единственный секрет в query
(короткоживущий), на экране QR — предупреждение «никому не показывайте код».

Сессии продлеваются скользящим окном: `POST /users/me/heartbeat` продлевает
`expires_at` (не чаще раза в сутки) — иначе QR-устройство, у которого нет пароля,
умерло бы навсегда через `SESSION_LIFETIME_DAYS`.

## Канонические типы сообщений

### text
```json
{ "type": "text", "text": "...",
  "reply_to_id": "<msg_id>?", "reply_to_text": "<preview>?",
  "fwd_from": "<user_id>?" }
```

### media (зашифрованный файл загружен в /upload)
```json
{ "type": "media", "file_id": "<uuid>", "sym_key": "<base64 32б AES>",
  "mime_type": "image/jpeg", "kind": "image|voice|video_note|video|file",
  "nonce": "<base64 IV>", "duration": 3.2, "waveform": [0..31],
  "file_name": "...?", "file_size": 12345, "width": 0, "height": 0,
  "caption": "...?", "fwd_from": "<user_id>?" }
```
Файл шифруется AES-GCM ключом `sym_key` (тег в конце), шифротекст грузится в
`POST /upload` → `file_id`. Получатель: `GET /download/{file_id}` → AES-GCM
decrypt ключом `sym_key`, IV из `nonce`.

### edit
```json
{ "type": "edit", "target": "<msg_id>", "text": "<новый текст>" }
```
Применяется только к сообщению того же чата и только от его автора.

### reaction
```json
{ "type": "reaction", "target": "<msg_id>", "emoji": "👍" }
```
Пустой `emoji` — снять реакцию.

### delete
```json
{ "type": "delete", "target": "<msg_id>", "target_id": "<msg_id>",
  "target_text": "<текст>?", "target_ts": 0, "target_is_out": false }
```
`target_text`/`target_ts` — фолбэк-сопоставление для клиентов с другими id.

### read / delivered
```json
{ "type": "read" }
{ "type": "delivered" }
```
Только личные чаты. `delivered` шлёт получатель после записи в локальную БД
(до ACK серверу), `read` — при открытии чата с непрочитанными.

## Соответствие веб ↔ канон (адаптер toWire/fromWire в web/app.js)

| Веб (внутренний payload) | Канон (wire) |
|--------------------------|--------------|
| `{type:'text', content, reply_to:{msg_id,author,text}}` | `{type:'text', text:content, reply_to_id, reply_to_text}` |
| `{type:'edit', target_id, content}` | `{type:'edit', target, text}` |
| `{type:'image'/'voice'/'video_msg'/'file', content:<dataURL>}` | `{type:'media', file_id, sym_key, mime_type, kind}` |
| реакция (через sync_sent) | `{type:'reaction', target, emoji}` |
| read | `{type:'read'}` |

Неизвестные типы клиенты игнорируют молча; неизвестный тип **с контентом**
(`file_id`/`media`/`text`) показывается плашкой «вложение не поддерживается»,
чтобы сообщение не терялось.

## Realtime (WS `/ws?token=...`)

Сервер ретранслирует эфемерные события (plaintext сообщений там нет):
`typing`, `stop_typing`, `presence`, `webrtc_offer|answer|ice|hangup|busy`,
`group_call_start|join|leave`. Плюс серверные: `new_message`, `device_added`.
Ядро (`core/src/ws.rs`) само не переподключается — реконнект с backoff делает
платформа (Android `AetherService`, десктоп `RealtimeClient`).
