# Единый wire-протокол (web ↔ Android)

Канонический формат — **Android** (`MessageRepository`). Веб приводится к нему.
Всё ниже — это **расшифрованный** JSON внутри конверта (plaintext, который видит
только получатель). Сам конверт на сервере — `{nonce_b64, ciphertext_b64, ...}`.

## Статус унификации

| Слой | Состояние |
|------|-----------|
| Идентичность (ключи) | ✅ Единая: случайный ключ + бэкап `encrypted_private_key_b64` (PBKDF2 100k + AES-GCM, `salt:iv:ct`, url-safe base64). Проверено кросс-импл. |
| Личные сообщения (шифр) | ✅ `crypto_box` (Curve25519), конверт `{sender_pubkey_b64, nonce_b64, ciphertext_b64}`. |
| Группы (шифр) | ✅ AES-GCM общим ключом, конверт `{is_group:"1", nonce_b64, ciphertext_b64}`; ключ обёрнут box'ом `{sender_pubkey_b64, nonce_b64, ciphertext_b64}` (внутри — base64-строка ключа). Проверено кросс-импл. |
| Wire-полезная нагрузка (поля) | ✅ Адаптер `toWire`/`fromWire` в web/app.js (text/edit/reaction/reply/read/fwd). Android игнорирует неизвестные типы. Покрыто `web/test_wire.js`. |
| Медиа-транспорт | ✅ Веб шифрует AES-GCM → `POST /upload` → `media{file_id,sym_key,nonce,mime_type,kind}` (как Android). Приём: `GET /download` → расшифровка → blob URL (ленивый кэш). filename/size — веб-расширение. Нужна проверка на устройстве. |

## Канонические типы сообщений (Android)

### text
```json
{ "type": "text", "text": "...",
  "reply_to_id": "<msg_id>?", "reply_to_text": "<preview>?",
  "fwd_from": "<user_id>?" }
```

### media (зашифрованный файл загружен в /upload)
```json
{ "type": "media", "file_id": "<uuid>", "sym_key": "<base64 32б AES>",
  "mime_type": "image/jpeg", "kind": "image|voice|video_msg|file?",
  "nonce": "<base64 IV>?", "fwd_from": "<user_id>?" }
```
Файл шифруется AES-GCM ключом `sym_key` (см. `E2ECrypto.encryptFile`), сырой
шифротекст (тег в конце) грузится в `POST /upload` → `file_id`. Получатель:
`GET /download/{file_id}` → AES-GCM decrypt ключом `sym_key`, IV из `nonce`.

### edit
```json
{ "type": "edit", "target": "<msg_id>", "text": "<новый текст>" }
```

### reaction
```json
{ "type": "reaction", "target": "<msg_id>", "emoji": "👍" }
```
Пустой `emoji` — снять реакцию.

### read
```json
{ "type": "read" }
```
Только личные чаты. Помечает исходящие отправителю как прочитанные.

## Соответствие веб ↔ канон (адаптер toWire/fromWire)

| Веб (внутренний payload) | Канон (wire) |
|--------------------------|--------------|
| `{type:'text', content, reply_to:{msg_id,author,text}}` | `{type:'text', text:content, reply_to_id, reply_to_text}` |
| `{type:'edit', target_id, content}` | `{type:'edit', target, text}` |
| `{type:'image'/'voice'/'video_msg'/'file', content:<dataURL>}` | `{type:'media', file_id, sym_key, mime_type, kind}` |
| реакция (через sync_sent) | `{type:'reaction', target, emoji}` |
| read | `{type:'read'}` |

**План реализации (следующие шаги, с тестами в браузере + пересборкой APK):**

1. **Адаптер текста/ответа/редактирования** в вебе: чистые функции `toWire(payload)`
   и `fromWire(obj)`, вклинить в точку `JSON.stringify(payloadObj)` (отправка) и
   `JSON.parse(plaintext)` (приём). UI веба не трогаем (внутренний payload прежний).
2. **Медиа на upload**: веб шифрует AES-GCM (`aesGcmEncrypt`), грузит в `/upload`,
   шлёт `{type:'media', file_id, sym_key, mime_type, kind}`; на приёме скачивает,
   расшифровывает, рендерит как сейчас (blob URL вместо dataURL).
3. **Реакции/read/delete** через канон; согласовать с веб-механизмом `sync_sent`
   (мультидевайс-эхо) — Android его не шлёт, веб должен и принимать канон, и
   сохранять своё эхо.

## Заметки о мультидевайсе
Веб использует `sync_sent` / `original_payload` для синхронизации между вкладками
одного аккаунта. Android этого не делает. При унификации `fromWire` должен
корректно игнорировать незнакомые типы (`sync_sent`) от не-веб клиентов и не падать.
