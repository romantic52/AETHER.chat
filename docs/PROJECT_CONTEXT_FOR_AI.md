# AETHER — контекст проекта для ИИ-ассистента

Это брифинг для погружения в проект. Прочитай целиком перед работой.

## 1. Что это

**AETHER** — self-hostable end-to-end-шифрованный мессенджер. Философия: официальные
серверы существуют, но любой может арендовать VPS и поднять свой сервер парой команд.
Собираются ТОЛЬКО @username + пароль (уникальность + вход). Ноль трекинга, ноль банов.
Клиенты: iOS (готов), Android (в работе), web (PWA), планируются Linux/Windows.

## 2. Где что лежит

- Репозиторий: `github.com/romantic52/AETHER.chat`
  - ветка `main` — iOS + ядро + сервер
  - ветка `android` — Android-клиент (коммит `758ed2a`), ещё НЕ смёржена в main
- Структура: `core/` (Rust-ядро), `ios/`, `android/`, `web/`, `server/`, `docs/`
- Сервер (прод): `YOUR_SERVER_IP`, systemd `secure_messenger.service`,
  файл `/root/secure_messenger/server/main.py`, домен `https://YOUR-SERVER-HOST.nip.io`
- WS: `wss://YOUR-SERVER-HOST.nip.io/ws?token=` — ТОЛЬКО HTTP/1.1

## 3. Архитектура (ГЛАВНОЕ ПРАВИЛО)

**Крипта и протокол живут в ОБЩЕМ Rust-ядре `core/` (crate sm_core).** Ядро через UniFFI
генерит биндинги для Swift (iOS) и Kotlin (Android). Клиенты — тонкие обёртки, крипту
НЕ переписывают. Это гарантирует кросс-платформенную совместимость.

- iOS-обёртка: `ios/AETHER/Core/CoreClient.swift` (актор), движок `Messaging.swift`.
- Android-обёртка: `android/.../data/MessageRepository.kt`, `crypto/E2ECrypto.kt`,
  биндинги `android/.../uniffi/sm_core/sm_core.kt`, нативы `jniLibs/*/libsm_core.so`.

**Любое изменение крипты/протокола делается в `core/`, потом регенерятся биндинги обоих
клиентов.** Нельзя реализовывать шифрование параллельно в Swift/Kotlin — разъедется.

## 4. Крипто-модель

**1:1 переписка — Double Ratchet (Olm/X3DH через vodozemac, аудит Least Authority).**
Forward secrecy + восстановление после компрометации. НИКАКИХ даунгрейдов на статический
box для 1:1 — если у получателя нет prekeys, сообщение видимо падает.

Функции ядра (UniFFI, camelCase в Swift/Kotlin):
- `olmAccountNew() -> String` (pickle аккаунта)
- `olmAccountIdentity(accountPickle) -> String`, `olmAccountEd25519(accountPickle) -> String`
- `olmAccountGenerateOtks(accountPickle, count) -> {accountPickle, identityKeyB64, oneTimeKeysJson}`
- **P7 (подписанные prekeys):** `olmAccountGenerateOtksSigned(accountPickle, count, userId,
  deviceId) -> OlmPublishSigned{+ed25519KeyB64, identitySigB64, otkSignaturesJson}`;
  `olmVerifyPrekeyBundle(...)`, `olmVerifyIdentity(...)`; TOFU-пины в store:
  `olmPinGet/Check/Accept/SetVerified/Delete` (peer_key = "peer" или "peer::device")
- `olmCreateOutbound(accountPickle, theirIdentityB64, theirOneTimeKeyB64) -> sessionPickle`
- `olmEncrypt(sessionPickle, plaintext) -> {sessionPickle, messageType, bodyB64}`
- `olmCreateInbound(accountPickle, theirIdentityB64, bodyB64) -> {accountPickle, sessionPickle, plaintext}`
- `olmDecrypt(sessionPickle, messageType, bodyB64) -> {sessionPickle, plaintext}`
- store: `olmSessionGet/Set(peerId)`, аккаунт в `meta("olm_account")`
- api: `uploadKeys(identity, otksJson)`, `keysCount()`, `claimKeys(userId) -> {identityKeyB64, oneTimeKeyId, oneTimeKeyB64}`

**Wire-конверт ratchet (КАНОН, менять нельзя):**
```json
{ "ratchet": "1", "olm_identity": "<curve25519 b64url>", "type": 0, "body_b64": "<b64url>" }
```
type: 0 = prekey (ставит сессию), 1 = normal. Отправляется полем `envelope` в `POST /messages`.

**Prekey-флоу:** публиковать prekeys при login/register/switchAccount (НЕ только на старте!),
пополнять когда на сервере < 20 OTK. При первом сообщении пиру — claim его бандла (X3DH).

**Группы/каналы:** общий симметричный ключ, обёрнут crypto_box'ом для участников
(`seal_direct`/`wrap_group_key`). Ротация ключа при смене состава. Публичные каналы —
ключ хранится у сервера (осознанный размен ради публичности).

**Локальная база:** SQLCipher (шифрована ключом из Keychain).

## 5. Серверные эндпоинты (prekey + сообщения)

- `PUT /keys/upload {identity_key_b64, one_time_keys:{id:key}, device_id,
  ed25519_key_b64?, identity_sig_b64?, otk_signatures?:{id:sig}}` — P7: сервер
  верифицирует подписи (PyNaCl), анти-даунгрейд после первой подписанной публикации
- `GET /keys/count -> {count, identity_key_b64}`
- `POST /keys/claim/{user_id}?device_id= -> {identity_key_b64, ed25519_key_b64?,
  identity_sig_b64?, one_time_key:{key_id,key_b64,sig_b64?}}` (OTK удаляется атомарно)
- `POST /messages {sender_id, recipient_id, envelope, client_id}` — валидация конверта
  ветвится по `envelope.ratchet` (ratchet: {olm_identity,type,body_b64}; иначе legacy box).
- `GET /messages/inbox/{user_id}?since=`, `POST /messages/ack`
- Регистрация НЕ возвращает токен → ядро после register делает login.

## 6. Текущее состояние

Готово: 1:1 Double Ratchet (iOS+Android wire-совместимы, проверено), группы/каналы,
медиа/ГС/кружки, звонки 1:1 и групповые (mesh-аудио), SQLCipher, Face ID/PIN, TOFU для
box-ключей, темы, RU/EN.

## 7. Известные недостатки (бэклог)

- ~~SEC HIGH-2~~ **ЗАКРЫТ (P7):** prekey-бандлы подписаны Ed25519 (канон
  `AETHER-IDKEY-1`/`AETHER-OTK-1`), TOFU-пин olm-identity в ядре (`olm_pins`),
  анти-даунгрейд на сервере. См. `P7_SIGNED_PREKEYS_DESIGN.md`. Осталось:
  перевести экран сверки (KeyVerificationView) с box-ключей на olm + QR.
- **SEC MED-3:** claim без rate-limit → исчерпание чужих OTK (DoS). Нужен fallback-key +
  лимит. (vodozemac умеет `generate_fallback_key`.)
- **SEC MED-4:** одна Olm-сессия на пира, входящий prekey её затирает → форс-сброс.
  Нужны мультисессии (выбор по session_id).
- **SEC LOW-5:** pickle'ы хранятся плейнтекст-JSON внутри SQLCipher (DiD, если ключ БД утечёт).
- Группы без forward secrecy (статический ключ).
- Нет пушей: APNs/VoIP заблокированы платным Apple Developer ($99/год).
- Ветка `android` не смёржена в `main`.

## 8. Рабочие правила и грабли

- **Коммиты — БЕЗ упоминания ИИ.** Двигать карточки на доске github.com/users/romantic52/projects/1.
- **Деплой сервера:** scp/SFTP на VPS НЕ работает (подсистема отключена) — правки main.py
  катить патч-скриптом через `ssh + base64 + python3`, не через scp. Держать ssh-сессии
  по одной (много быстрых подряд ловят таймаут аутентификации).
- **iOS-подпись:** сборку класть в дефолтный DerivedData, НЕ в `~/Desktop` (iCloud-синк
  вешает `com.apple.FinderInfo` на .appex → codesign падает «resource fork detritus»).
- **Keychain ненадёжен на неподписанных сборках** — токен держим в памяти Session.
- **Бриф Android-порта:** `docs/ANDROID_RATCHET_PORT.md`.

## 9. Синхронизация Android с iOS

Android ДОЛЖЕН использовать последний `core/` из `main` (ratchet.rs + prekey-методы api +
таблица olm_sessions добавлены в коммитах после `d3d4878`). Ветка `android` уже содержит
идентичный `ratchet.rs` и правильный wire-конверт — проверено. При обновлениях ядра:
`git pull` core из main → регенерить Kotlin-биндинги.
