# AETHER Android — порт с полным Double Ratchet (для Sonnet-агента)

Ты портируешь мессенджер **AETHER** на Android (Kotlin/Compose). Крипта уже написана
в общем Rust-ядре `core/` — НЕ переписывай её. Твоя задача: сгенерить Kotlin-биндинги
и повторить оркестрацию iOS-клиента 1-в-1, чтобы Android был **wire-совместим с iOS**
и работал на **полном Double Ratchet без даунгрейдов**.

Эталон логики — `ios/AETHER/Core/CoreClient.swift`, `Messaging.swift`, `Session.swift`.

---

## 0. Железное правило: НИКАКИХ даунгрейдов

- 1:1 отправка идёт **только** через Olm/Double Ratchet.
- **Запрещён** статический `crypto_box` (`seal_direct`) для 1:1 — это легаси iOS-fallback,
  на Android его быть не должно. box остаётся ТОЛЬКО для обёртки групповых ключей.
- Читать входящий легаси-box 1:1 допустимо (переходный период), но НИКОГДА не отправлять
  1:1 через box, даже если у получателя нет prekeys — в этом случае показывать ошибку
  «собеседник не поддерживает защищённое шифрование», а не молча слать box.

## 1. Ядро и биндинги

Ядро уже содержит всё нужное (модуль `core/src/ratchet.rs`, prekey-методы в `api.rs`,
таблицу `olm_sessions` + `meta(olm_account)` в `store.rs`). Собери его под Android и
сгенери Kotlin:

```
cd core
cargo build --release --target aarch64-linux-android   # + armv7, x86_64 по нужде
cargo run --release --bin uniffi-bindgen -- generate --library <libsm_core.so> \
    --language kotlin --out-dir <android>/src/main/kotlin
```

UniFFI даёт те же имена, что в Swift, но camelCase Kotlin:
- `olmAccountNew(): String`
- `olmAccountIdentity(accountPickle): String`
- `olmAccountOtkCount(accountPickle): UInt`
- `olmAccountGenerateOtks(accountPickle, count): OlmPublish` → `{accountPickle, identityKeyB64, oneTimeKeysJson}`
- `olmCreateOutbound(accountPickle, theirIdentityB64, theirOneTimeKeyB64): String` (session pickle)
- `olmEncrypt(sessionPickle, plaintext): OlmEncrypted` → `{sessionPickle, messageType, bodyB64}`
- `olmCreateInbound(accountPickle, theirIdentityB64, bodyB64): OlmInbound` → `{accountPickle, sessionPickle, plaintext}`
- `olmDecrypt(sessionPickle, messageType, bodyB64): OlmDecrypted` → `{sessionPickle, plaintext}`

Store: `olmSessionGet(peerId): String?`, `olmSessionSet(peerId, sessionJson)`, `metaGet/metaSet`.
Api: `uploadKeys(identityKeyB64, oneTimeKeysJson)`, `keysCount(): UInt`, `claimKeys(userId): PrekeyBundle{identityKeyB64, oneTimeKeyId, oneTimeKeyB64}`.

## 2. Wire-формат ratchet-конверта (канон, не менять)

```json
{ "ratchet": "1", "olm_identity": "<curve25519 b64url>", "type": 0, "body_b64": "<b64url>" }
```
- `type`: 0 = prekey (устанавливает сессию), 1 = normal.
- Отправляется в `POST /messages` как поле `envelope`. Сервер валидирует ratchet-конверт
  по ключам `{olm_identity, type, body_b64}` (ветка по `envelope.ratchet` уже на сервере).

## 3. Публикация prekeys (обязательно на ВСЕХ точках активации)

Портируй `ensureOlmKeys()` из iOS. Вызывать **при login, register И switchAccount** —
не только на старте (iOS-баг был именно в этом: второй аккаунт не выкладывал ключи →
ему нельзя было написать).

```
fun ensureOlmKeys() {
  val acct = olmAccount()                 // meta("olm_account") или olmAccountNew()+save
  if (api.keysCount() >= 20u) return
  val pub = olmAccountGenerateOtks(acct, 40u)
  store.metaSet("olm_account", pub.accountPickle)
  api.uploadKeys(pub.identityKeyB64, pub.oneTimeKeysJson)
}
```
Плюс пополнять после расхода OTK на входящей prekey-сессии (см. §5).

## 4. Отправка 1:1 (ratchet, без fallback)

```
fun sendDirect(peerId, wirePayload): String {
  val id = peerId.lowercase()
  var session = store.olmSessionGet(id)
  if (session == null) {
    val bundle = api.claimKeys(id)        // 404/409 -> НЕ box; кинуть ошибку "нет ratchet"
    session = olmCreateOutbound(olmAccount(), bundle.identityKeyB64, bundle.oneTimeKeyB64)
  }
  val enc = olmEncrypt(session, wirePayload)
  store.olmSessionSet(id, enc.sessionPickle)
  val env = """{"ratchet":"1","olm_identity":"${myOlmIdentity()}","type":${enc.messageType},"body_b64":"${enc.bodyB64}"}"""
  return api.sendMessage(peerId, env, clientId)
}
```

## 5. Приём (routing + inbound)

Портируй `open()` + `ratchetOpen()`:
- Если в конверте `ratchet=="1"` → ratchet-путь; иначе группа (по group_key) или легаси-box (только чтение).
- Есть сессия → `olmDecrypt`, сохранить продвинутую сессию.
- Нет сессии + prekey(type 0) → `olmCreateInbound` (расходует OTK): сохранить обновлённый
  `olm_account` в meta + новую сессию, затем `ensureOlmKeys()` для пополнения OTK.
- normal без сессии → ошибка (переспросить позже).

## 6. Security-требования (вшить сразу, это и есть «полная защита без даунгрейдов»)

Реализуй с самого начала, чтобы не догонять как на iOS:

1. **TOFU на Olm-identity (SEC HIGH-2).** При первом claim пира — запинить его
   `identity_key`; при последующем изменении — предупредить (MITM-тревога), как уже
   сделано для crypto_box-ключей. Показать отпечаток olm-identity на экране проверки.
2. **Fallback-key (SEC MED-3).** Используй `Account.generate_fallback_key` (добавь экспорт
   в ядро, если ещё нет): неисчерпаемый последний ключ — исчерпание OTK не должно ронять/
   даунгрейдить связь. Rate-limit claim на сервере (см. server-таск).
3. **Мультисессии на пира (SEC MED-4).** Не затирай сессию входящим prekey — храни
   несколько, выбирай по session_id (стандарт Olm). Таблицу `olm_sessions` расширить
   до `(peer_id, session_id)`.
4. **Никакого box-даунгрейда** (§0).

## 7. Сервер (уже готово, для справки)

- `PUT /keys/upload {identity_key_b64, one_time_keys:{id:key}}`
- `GET /keys/count` → `{count}`
- `POST /keys/claim/{user_id}` → `{identity_key_b64, one_time_key:{key_id,key_b64}}` (OTK удаляется атомарно)
- `POST /messages` принимает ratchet-конверт (валидация ветвится по `ratchet`).
- Для SEC MED-3 добавить rate-limit на `/keys/claim`.

## 8. Definition of done

- [ ] Kotlin-биндинги из общего ядра, prekeys публикуются при login/register/switch.
- [ ] iOS↔Android 1:1: сообщение туда-обратно, оба конца видят Double Ratchet (не box).
- [ ] Проверка ключей: смена olm-identity пира даёт TOFU-тревогу.
- [ ] Исчерпание OTK (fallback-key) не роняет отправку и не даёт даунгрейда.
- [ ] Группы работают на общем ключе (crypto_box-обёртка) как раньше.
- [ ] Ни одного пути отправки 1:1 через статический box.
