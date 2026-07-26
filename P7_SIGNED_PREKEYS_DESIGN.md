# P7 — Подписанные prekey-бандлы + TOFU на olm-identity (SEC HIGH-2)

Статус: **реализовано** (ядро + сервер + iOS + web). Карта реализации внизу.

## 1. Угроза

До P7 prekey-бандл (`/keys/claim`) состоял из голых `identity_key_b64` + OTK без
какой-либо подписи, а olm-identity нигде не пинился:

1. **Исходящие**: сервер (или взломавший его) мог выдать при claim СВОЙ identity+OTK —
   классический MITM на установке Olm-сессии, невидимый для обеих сторон.
2. **Входящие**: `olm_identity` в ratchet-конверте — самодекларация; спуфинг авторства
   сервером не детектировался (TOFU был только для box-ключей групп).

## 2. Схема

**Подпись бандла (Ed25519 аккаунта vodozemac, версионный канон):**

```
identity: "AETHER-IDKEY-1|{user_id.lower()}|{device_id}|{curve25519_b64}"
OTK:      "AETHER-OTK-1|{user_id.lower()}|{device_id}|{curve25519_b64}|{otk_id}|{otk_b64}"
```

Подпись OTK связывает одноразовый ключ с владельцем (user+device) и его identity —
сервер не может ни подменить ключи, ни выдать чужой честный бандл за запрошенный.
`user_id` нормализуется в lowercase внутри ядра (сервер оперирует lowercase).

**Публикация** (`PUT /keys/upload`, новые опциональные поля):
`ed25519_key_b64`, `identity_sig_b64`, `otk_signatures: {key_id: sig_b64}`.
Сервер верифицирует подписи PyNaCl'ом (защита от битых клиентов; сам он не доверенная
сторона — решающая проверка у получателя). Анти-даунгрейд: раз устройство публиковало
подписи — неподписанный upload больше не принимается (400). Смена identity ИЛИ ed25519
(включая NULL→подписанный) удаляет старые OTK устройства.

**Claim** (`POST /keys/claim/{user}?device_id=`) отдаёт те же поля + `sig_b64` в
`one_time_key`; `GET /users/{id}/devices` — `ed25519_key_b64`/`identity_sig_b64`.

**Верификация у отправителя (ядро, до создания сессии):**
- подпись есть → `olm_verify_prekey_bundle(...)`, не сошлась → отправки нет;
- подписи нет, но у пина уже есть ed25519 → отказ (анти-стриппинг);
- подписи нет и пин без ed25519 → легаси-путь (только TOFU по curve).

**TOFU-пин olm-identity (таблица `olm_pins` в SQLCipher-ядре):**
ключ `peer_key` = то же соглашение, что у `olm_sessions`: `peer` (primary) или
`peer::device`. Хранит curve25519 + ed25519 (+prev_* для баннера), `verified`.
- Первый контакт → пин (TOFU). Совпало → ок (ed25519 дозаполняется при первом
  подписанном claim).
- **Mismatch исходящих** → отправка блокируется ЦЕЛИКОМ (двухфазный fanout: сначала
  claim+верификация всех устройств, потом отправка копий), «принять новый ключ» —
  только явным действием (`olm_pin_accept`), бандл при этом переиспользуется
  (OTK не сжигается повторно).
- **Mismatch входящих** (в конверте только curve) → сообщение отклоняется без ack,
  ретраится поллингом; после принятия нового ключа вскрывается само.

**Форс-перепубликация**: один раз после апдейта клиент публикует подписанный бандл,
даже если OTK на сервере ≥20 (иначе старые неподписанные OTK висели бы неделями);
серверная чистка по смене NULL→ed25519 замещает пул целиком.

## 3. Что осталось за пиром TOFU (принято осознанно)

- Подмена ДО первого контакта — закрывается только сверкой цифр безопасности
  (следующая итерация: перевести KeyVerificationView на olm-ключи, QR).
- SEC MED-3 (rate-limit claim + fallback key) и MED-4 (мультисессии) — бэклог.
- Каналы/группы — отдельная тема (box-TOFU есть, подписи ключей групп нет).

## 4. Совместимость

- Легаси-бандлы (загружены до апдейта) проходят как неподписанные, TOFU по curve
  работает; после первой подписанной публикации устройства действует анти-даунгрейд.
- Wire-конверт ratchet НЕ менялся (канон), только prekey-директория.
- Android: ratchet ещё не подключён (биндинги отстают) — при регенерации `sm_core.kt`
  получит новые методы автоматически; хранение пинов уже в ядре.

# Карта реализации

| Слой | Файл | Что |
|---|---|---|
| Движок | `core/ratchet-core/src/lib.rs` | канон, `account_ed25519`, `account_generate_otks_signed`, `verify_identity`, `verify_prekey_bundle` + тесты |
| UniFFI | `core/src/ratchet.rs` | `olmAccountEd25519`, `olmAccountGenerateOtksSigned` (`OlmPublishSigned`), `olmVerifyIdentity`, `olmVerifyPrekeyBundle` |
| Store | `core/src/store.rs` | таблица `olm_pins`, `OlmPin`/`OlmPinStatus`, `olm_pin_get/check/accept/set_verified/delete` + тест TOFU-семантики |
| API | `core/src/api.rs` | `PrekeyBundle`/`DeviceInfo` + поля подписи, `upload_keys_device_signed` |
| WASM | `web/ratchet-wasm/src/lib.rs` | те же 4 экспорта; артефакт в `web/vendor/ratchet` |
| Сервер | `server/main.py` | миграции (`crypto_devices.ed25519_key_b64/identity_sig_b64`, `one_time_keys.sig_b64`), `_verify_upload_signatures` (PyNaCl), анти-даунгрейд, расширенные upload/claim/devices |
| iOS | `Core/CoreClient.swift` | подписанная публикация (`olm_signed_v1`), `verifyAndPinBundle`, двухфазный `sendDirect`, входящий гейт в `ratchetOpen`, `acceptNewOlmKey` |
| iOS UI | `Core/Messaging.swift`, `Features/Chat/ChatView.swift` | `pendingOlmKeyChange`/`acceptNewOlmKey`, оранжевый баннер смены ключа |
| Web | `web/app.js` | `olmEdPins`, подписанная публикация (`olm_signed_v1_${myId}`), верификация+пин ДО `create_outbound`, confirm-принятие нового ключа |

## Инварианты (не ломать)

1. Канон подписей менять только с bump'ом версии (`AETHER-IDKEY-2`...) и поддержкой
   старой на верификации. Канон продублирован в `server/main.py` — менять синхронно.
2. Перепин — только `olm_pin_accept` (явное действие пользователя). Тихих перепинов нет.
3. Гейт исходящих — ДО `olm_create_outbound`; гейт входящих — ДО расшифровки.
4. `peer_key` пинов = соглашение `sessionKey` (`peer` / `peer::device`) — не расходить.
5. Анти-стриппинг: пин с ed25519 + бандл без подписи = отказ, а не «мягкий» проход.
