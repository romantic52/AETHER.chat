# AETHER — единый слой доставки: Nearby, транспорты, приватные режимы

Проект коммуникационного слоя, в котором обычные сообщения, прямая связь между
устройствами, Bluetooth/Nearby, локальное и сетевое обнаружение, Secret
Sessions, исчезающие сообщения и View Once живут в ОДНОМ чате с одним
человеком. Написан по факту кода репозитория на 26.08.2026 (ветка
`multi-server`), а не по памяти.

Читается вместе с `docs/MULTI_SERVER_DESIGN.md` (пространства и серверы),
`docs/PROJECT_STATE.md` и `WIRE_PROTOCOL.md`.

---

## 0. Разбор существующей архитектуры

### 0.1 Что уже есть и что из этого переиспользуется

| Слой | Где | Состояние | Годится ли |
|---|---|---|---|
| Модель сообщения | `core/src/store.rs`, таблица `messages` | `id, peer_id, outgoing, sender_id, payload_json, status, ts, reactions_json, edited, deleted` | **расширяем**, не переписываем |
| Типы контента | `ios/AETHER/Core/Wire.swift` | text / media(image,video,audio,voice,videoNote,file) / edit / reaction / delete / read / delivered | **годится как content type** |
| Шифрование 1:1 | `core/src/ratchet.rs` + `ratchet-core` (vodozemac) | Double Ratchet, X3DH, fallback-ключи, мультисессии | **готово, транспорт-независимо** |
| Мультиустройство | `CoreClient.sendDirect`, `crypto_devices`, cross-signing P8 | **fan-out уже реализован**: своя Olm-сессия и своя копия конверта каждому устройству получателя | **пункт 23 ТЗ закрыт на 80%** |
| Сверка личности | `olm_verify_qr_build/parse`, `olm_verify_identity` | числа безопасности + QR | **готово, пункт 51** |
| Отложенные сообщения | `ios/AETHER/Core/ScheduledMessages.swift` | локальные, в UserDefaults, отправка «при первой возможности» | **есть, но вне модели сообщений** |
| P2P-инфраструктура | `ios/AETHER/Core/WebRTCClient.swift` | ICE/STUN/TURN для звонков, `didOpen dataChannel` — пустая заглушка | частично: нужен сигналинг, т.е. сервер |
| Серверная доставка | `server/main.py` `/messages`, `/messages/inbox`, `/messages/ack` | очередь конвертов + ACK per-device | **годится как один из транспортов** |
| Пространства | `ServerRegistry`, `ServerContext` (этапы 3–5) | сервер + аккаунт, свои базы и ключи | **транспорты вкладываются сюда** |

### 0.2 Где ровно проходит шов

Главная находка разбора. Отправка сегодня выглядит так
(`CoreClient.sendDirect`, `core/src/api.rs`):

```
Wire.text(...)                       → payload JSON
    ↓
peerDevices() + claim + verify       → сессии на КАЖДОЕ устройство получателя
    ↓
sealDirect(...)                      → готовый шифрованный конверт (строка)
    ↓
api.sendMessage(recipient, envelope) → POST /messages          ← ЕДИНСТВЕННАЯ
                                                                  привязка к серверу
```

Шифрование уже выдаёт **готовый конверт, ничего не знающий о транспорте**.
К серверу его привязывает ровно одна последняя строка. Значит `TransportRouter`
вставляется точно в этот шов, а крипта, ратчет, мультиустройство и хранилище
не трогаются вообще. Это и есть минимально разрушительный путь.

### 0.3 Четыре препятствия, которые надо снять

**1. `message_id` назначает сервер.** Клиент кладёт сообщение с локальным id,
получает от сервера настоящий и **подменяет его**:

```swift
await core.replaceMessageId(old: localId, new: realId, status: 1)   // Messaging.swift:836
```

Это прямо противоречит требованию §4: один и тот же `message_id` обязан
пережить смену маршрута. Если Bluetooth не подтвердил доставку и сообщение
ушло через сервер, id менять нельзя — иначе получатель не сможет отбросить
дубликат.

Хорошая новость: сервер **уже умеет** принимать клиентский id —
`client_id` валидируется как UUID и становится `messages.id`, а повтор
гасится через `ON CONFLICT DO NOTHING`. То есть инфраструктура для сквозного
id готова, ею просто не пользуются.

**2. Статус — одно число.** `status: 0 отправляется, 1 отправлено,
2 доставлено, 3 прочитано`. Нет места для «ждёт получателя рядом»,
«ищу устройство», «не удалось». И нет ни следа маршрута.

**3. `peer_id` — это `user_id` на текущем сервере.** Чат привязан к
пространству. Для Nearby нужен человек, найденный **до** того, как известно,
есть ли он на нашем сервере вообще. Нужен слой identity, независимый от
сервера.

**4. Нет ни одного локального транспорта.** BLE, Wi-Fi Aware, локальная сеть —
ничего. Info.plist не запрашивает Bluetooth, фоновые режимы — `audio`,
`processing`, `fetch`.

---

## 1. Ограничения платформ — проверено по документации

Это не теория: от этих ограничений зависит, что вообще можно построить.
Проверено 26.08.2026, перед реализацией сверить ещё раз.

### 1.1 iOS

**Wi-Fi Aware — главная новость и основа Direct Wi-Fi.**
В iOS 26 Apple открыла фреймворк `WiFiAware` для приложений из App Store:
одноранговые соединения по Wi-Fi без точки доступа и интернета, **включая
устройства Android** (Wi-Fi Aware давно есть в Android). Появилось это по
требованию DMA, но доступно глобально, а не только в ЕС.
([Apple Developer Forums](https://developer.apple.com/forums/thread/790195),
[9to5Mac](https://9to5mac.com/2025/06/20/ios-26-to-let-third-party-apps-build-their-own-airdrop-alternative/),
[heise](https://www.heise.de/en/news/Peer-to-peer-WLAN-by-order-of-the-EU-Apple-integrates-Wi-Fi-Aware-10446649.html))

Это закрывает пункт 69 ТЗ (большие файлы мимо Bluetooth) и делает
кросс-платформенный Direct реальным, а не мечтой. Перед реализацией
обязательно проверить требования к entitlement и privacy manifest.

**Bluetooth LE — работает, но фон сильно урезан.**
* Реклама в фоне не несёт локального имени, а service UUID уезжает в
  «overflow area». Прочитать её может только устройство Apple, которое **явно
  сканирует именно этот UUID**. Android такую рекламу не увидит.
* Сканирование в фоне возможно только с явным списком service UUID и работает
  медленнее.

**Вывод, который надо честно отразить в UI:** обнаружение iPhone ↔ Android по
Bluetooth надёжно работает, **пока приложение открыто**. Обещать
«найду тебя в кармане» нельзя.

**SMS/RCS — недоступны.** Третьесторонние приложения не могут ни программно
отправлять SMS, ни читать входящие, ни стать приложением по умолчанию.
Единственный путь — `MFMessageComposeViewController`: системный лист, где
**отправку подтверждает человек**, а ответы приложению не видны.
([Apple Developer Documentation](https://developer.apple.com/documentation/messageui/mfmessagecomposeviewcontroller))

Значит на iOS «SMS-транспорт» — это не транспорт, а передача черновика в
системные Сообщения. В архитектуре он и должен называться честно.

**Прочее:** `MultipeerConnectivity` и `NWBrowser`/`NWListener` с
`includePeerToPeer` — только между устройствами Apple, для кросс-платформы не
годятся. Локальная сеть требует `NSLocalNetworkUsageDescription` (у нас уже
есть, добавлен для звонков) и Bonjour-сервисов в Info.plist.

### 1.2 Android

* BLE: `BLUETOOTH_SCAN` / `BLUETOOTH_ADVERTISE` / `BLUETOOTH_CONNECT` (API 31+),
  `NEARBY_WIFI_DEVICES` (API 33+), фоновая работа — только через foreground
  service с типом `connectedDevice`.
* Wi-Fi Aware (API 26+, зависит от железа) и Wi-Fi Direct — есть.
* SMS: приложение может получить роль `ROLE_SMS` и стать приложением по
  умолчанию — тогда отправка и приём SMS доступны по-настоящему.
* RCS: публичного API для сторонних приложений нет. Обещать нельзя.
* Защита экрана: `FLAG_SECURE` реально блокирует снимок и запись.

### 1.3 Что из этого следует для проекта

| Возможность | iOS | Android | Вывод |
|---|---|---|---|
| BLE обнаружение (приложение открыто) | да | да | **основа Nearby** |
| BLE обнаружение в фоне, кросс-платформа | нет | да | фон честно помечаем как ненадёжный |
| Direct Wi-Fi, кросс-платформа | да (iOS 26+, WiFiAware) | да | **основной канал для медиа** |
| Обнаружение в локальной сети | да | да | третий путь, когда есть общий Wi-Fi |
| SMS настоящий | нет | да (роль SMS) | адаптер только для Android |
| SMS через системный лист | да | да | «поделиться в Сообщения», не транспорт |
| RCS | нет | нет | **из проекта убираем до появления API** |
| Блокировка снимка экрана | частично | да (`FLAG_SECURE`) | обещаем только обнаружение на iOS |

Пункт 24 ТЗ выполнен так: SMS/RCS остаются **точками расширения** в интерфейсе
`TransportAdapter`, но ни одной строки кода под них сейчас не пишется и ни
одного обещания в UI не делается.

---

## 2. Итоговая архитектура

### 2.1 Общая схема

```
                    AetherMessage (тип, контент, policy)
                              │
                              ▼
                     MessagePolicyEngine        ← что вообще разрешено этому
                              │                   сообщению (§42, §43, §44)
                              ▼
                     EncryptionService          ← СУЩЕСТВУЮЩИЙ Olm/X3DH,
                              │                   fan-out по устройствам
                              ▼
                     ┌────────────────┐
                     │ MessageEnvelope│  шифротекст + адресат + message_id
                     └────────┬───────┘
                              ▼
                      TransportRouter           ← НОВОЕ, вставляется в шов 0.2
                              │
        ┌─────────────┬───────┴───────┬──────────────┐
        ▼             ▼               ▼              ▼
   NearbyTransport  AetherCloud   CustomServer   (SmsHandoff)
        │            Transport      Transport      (будущие)
   ┌────┴─────┐
   ▼          ▼
 BLE      Direct Wi-Fi
 (BleLink) (WiFiAwareLink / LanLink)
```

Ключевое: `TransportRouter` **не знает, что внутри конверта**. Он получает
непрозрачный payload, адресата, `message_id` и ограничения политики.

**Уточнение, найденное при реализации этапа 1.** В первом наброске роутер
получал уже запечатанный конверт. Так не выходит: Olm шифрует ПОД КАЖДОЕ
устройство получателя, а набор достижимых устройств у каждого транспорта
свой — по Bluetooth рядом лежит один телефон, через сервер доступны все.
Значит запечатывать может только сам транспорт, зная свой набор адресатов.
Роутер по-прежнему в содержимое не заглядывает: для него это непрозрачная
строка. Шов проходит между «что отправить» и «кому именно из устройств»,
а не между «текст» и «шифротекст».

### 2.2 Компоненты

| Компонент | Ответственность | Зависит от | Состояние |
|---|---|---|---|
| `AetherMessageEngine` | жизненный цикл сообщения: создать, зашифровать, поставить в очередь, отметить статус | Policy, Encryption, Queue | заменяет часть `Messaging.swift` |
| `MessagePolicyEngine` | решает: можно ли серверу, можно ли копировать, когда истекает, сколько просмотров | настройки чата, глобальные, per-message | новое |
| `EncryptionService` | **существующий** `CoreClient.sealDirect/sealGroup` + fan-out | ratchet-core | без изменений |
| `TransportRouter` | выбор маршрута и порядок попыток | реестр адаптеров, Policy | новое |
| `TransportAdapter` | общий интерфейс транспорта | — | новое |
| `DeliveryQueue` | персистентная очередь исходящих с попытками | store | новое |
| `RetryEngine` | backoff, дедлайны, переход между маршрутами | Queue, Router | новое |
| `ReceiptManager` | sent / delivered / read + метаданные маршрута | store | расширяет существующее |
| `NearbyDiscoveryService` | реклама и сканирование, объединение BLE + сеть | адаптеры платформы | новое |
| `NearbyIdentityResolver` | сопоставление найденного маячка с identity | DiscoveryKey, контакты | новое |
| `SecretSessionManager` | предложение, согласование, смена правил, выход | Policy, Encryption | новое |
| `EphemeralManager` | таймеры, обратный отсчёт, вычистка | store, Policy | новое |
| `DeviceManager` | **существующий** `crypto_devices`, cross-signing | api | без изменений |
| `ContactDiscovery` | приватное сопоставление адресной книги | сервер | новое, отдельный этап |

### 2.3 Формула, которую нельзя нарушать (§84)

```
ТИП СООБЩЕНИЯ ≠ ТРАНСПОРТ ≠ ХРАНЕНИЕ ≠ ШИФРОВАНИЕ ≠ ЛИЧНОСТЬ ≠ СЕРВЕР
```

Проверка при код-ревью: в кодовой базе не должно появиться ни одного типа с
именем вида `BluetoothMessage`, `SecretChat`, `EphemeralTransport`. Есть
`AetherMessage` с полями `type`, `content`, `policy` — и отдельно маршрут,
который выбирается в момент доставки и может смениться.

---

## 3. Модель данных

### 3.1 Сообщение

Существующая таблица `messages` расширяется, **без переписывания**:

```sql
-- Уже есть: id, peer_id, outgoing, sender_id, payload_json, status, ts,
--           reactions_json, edited, deleted
ALTER TABLE messages ADD COLUMN msg_type TEXT NOT NULL DEFAULT 'NORMAL';
   -- NORMAL | EPHEMERAL | VIEW_ONCE | SECRET | SCHEDULED | SYSTEM
ALTER TABLE messages ADD COLUMN policy_json TEXT;      -- см. 3.2
ALTER TABLE messages ADD COLUMN route_json TEXT;       -- как реально доставлено
ALTER TABLE messages ADD COLUMN scheduled_at INTEGER;  -- для SCHEDULED
ALTER TABLE messages ADD COLUMN expires_at INTEGER;    -- вычисленный дедлайн
```

`status` остаётся числом ради совместимости (0 отправляется, 1 отправлено,
2 доставлено, 3 прочитано), но дополняется:

```
4  WAITING_FOR_NEARBY    получатель не рядом, сервер запрещён политикой
5  FAILED
6  EXPIRED
7  CANCELLED
```

Старые клиенты видят незнакомые числа и показывают их как «отправляется» —
совместимость не ломается.

### 3.2 Политика сообщения

```jsonc
{
  "delivery_mode": "AUTO",        // AUTO | DIRECT_ONLY | DIRECT_PLUS_BACKUP | SERVER
  "server_storage": "RELAY_ONLY", // NEVER | RELAY_ONLY | ENCRYPTED_BACKUP | ASK
  "copy_allowed": true,
  "forward_allowed": true,
  "export_allowed": true,
  "download_allowed": true,
  "screenshot_policy": "ALLOW",   // ALLOW | PROTECT_IF_SUPPORTED | DETECT
  "expire_after": null,           // секунды
  "expire_trigger": "SENT",       // SENT | DELIVERED | FIRST_OPEN | CLOSE
  "view_limit": null,             // 1 для VIEW_ONCE
  "preview_in_notification": false
}
```

Политика вычисляется каскадом, и **более строгое всегда побеждает**:

```
глобальные настройки → политика чата → Secret Session → per-message override
```

### 3.3 Новые таблицы клиента

```sql
-- Попытки доставки: по ним строится Message Info и работает retry.
CREATE TABLE message_delivery_attempts (
    message_id   TEXT NOT NULL,
    attempt      INTEGER NOT NULL,
    transport    TEXT NOT NULL,     -- nearby.ble | nearby.wifi | server.<server_id>
    device_id    TEXT,              -- устройство получателя, если известно
    started_ts   INTEGER NOT NULL,
    finished_ts  INTEGER,
    outcome      TEXT,              -- ok | unreachable | rejected | timeout | error
    detail       TEXT,
    PRIMARY KEY (message_id, attempt)
);

-- Итог: чем реально доставлено и что увидел сервер.
CREATE TABLE message_route (
    message_id      TEXT PRIMARY KEY,
    transport       TEXT NOT NULL,
    physical        TEXT,            -- bluetooth | wifi_aware | lan | tcp
    server_id       TEXT,            -- NULL, если сервер не участвовал
    server_stored   INTEGER NOT NULL DEFAULT 0,
    delivered_ts    INTEGER,
    read_ts         INTEGER
);

-- Политика доставки чата (§5) и порядок транспортов (§6).
CREATE TABLE chat_delivery_policy (
    peer_id          TEXT PRIMARY KEY,
    delivery_mode    TEXT NOT NULL DEFAULT 'AUTO',
    transport_order  TEXT,            -- JSON-массив идентификаторов
    server_storage   TEXT NOT NULL DEFAULT 'ENCRYPTED_BACKUP',
    updated_ts       INTEGER NOT NULL
);

-- Что серверу вообще позволено получать (§43).
CREATE TABLE server_storage_policy (
    server_id     TEXT NOT NULL,
    content_kind  TEXT NOT NULL,      -- text | image | video | file | voice
    allowed       INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (server_id, content_kind)
);

-- Найденные рядом.
CREATE TABLE nearby_peers (
    peer_key     TEXT PRIMARY KEY,    -- локальный ключ находки
    identity_id  TEXT,                -- NULL, пока незнакомец
    display      TEXT,                -- что он разрешил показать
    proximity    TEXT,                -- very_close | near | far | distant
    last_seen_ts INTEGER NOT NULL,
    via_ble      INTEGER NOT NULL DEFAULT 0,
    via_network  INTEGER NOT NULL DEFAULT 0,
    relationship TEXT NOT NULL DEFAULT 'UNKNOWN'  -- §66
);

CREATE TABLE nearby_sessions (
    session_id   TEXT PRIMARY KEY,
    peer_key     TEXT NOT NULL,
    established  INTEGER NOT NULL,
    link_kind    TEXT NOT NULL,       -- ble | wifi_aware | lan
    verified     INTEGER NOT NULL DEFAULT 0,
    expires_ts   INTEGER
);

CREATE TABLE nearby_permissions (
    identity_id  TEXT PRIMARY KEY,
    can_profile  INTEGER NOT NULL DEFAULT 0,
    can_chat     INTEGER NOT NULL DEFAULT 0,
    can_message  INTEGER NOT NULL DEFAULT 0,
    can_file     INTEGER NOT NULL DEFAULT 0,
    can_call     INTEGER NOT NULL DEFAULT 0
);

-- Secret Sessions.
CREATE TABLE secret_sessions (
    session_id   TEXT PRIMARY KEY,
    peer_id      TEXT NOT NULL,
    owner_id     TEXT NOT NULL,
    state        TEXT NOT NULL,       -- §77
    policy_json  TEXT NOT NULL,
    created_ts   INTEGER NOT NULL,
    accepted_ts  INTEGER,
    ended_ts     INTEGER
);

-- Состояние исчезающих: живёт отдельно от самого сообщения, потому что
-- меняется чаще и вычищается фоново.
CREATE TABLE ephemeral_state (
    message_id   TEXT PRIMARY KEY,
    state        TEXT NOT NULL,       -- UNOPENED | OPENED | COUNTDOWN | EXPIRED | PURGED
    opened_ts    INTEGER,
    expires_ts   INTEGER,
    views        INTEGER NOT NULL DEFAULT 0
);
```

Ключи и секреты в этих таблицах не лежат: приватные ключи остаются в Keychain
(iOS) и Keystore (Android), как сейчас. `DiscoveryKey` (раздел 5) — тоже.

### 3.4 Сервер

Минимальные добавления, ничего не ломающие:

```sql
ALTER TABLE messages ADD COLUMN relay_only INTEGER NOT NULL DEFAULT 0;
   -- 1 = удалить сразу после ACK, не хранить (§46)
ALTER TABLE message_receipts ADD COLUMN route TEXT;
   -- каким транспортом получатель реально забрал
```

---

## 4. Transport API

```swift
protocol TransportAdapter {
    var id: TransportId { get }                 // "nearby.ble", "server.<uuid>"
    var capabilities: TransportCapabilities { get }

    /// Достижим ли получатель ПРЯМО СЕЙЧАС. Дёшево и без побочных эффектов.
    func canReach(_ recipient: RecipientRef) async -> Reachability

    /// Отправить конверт. Возвращает подтверждение или бросает TransportError.
    func send(_ envelope: SealedEnvelope, deadline: Date) async throws -> DeliveryProof

    func cancel(messageId: String) async

    /// Оценка качества маршрута для выбора: задержка, ширина, цена.
    func estimatedQuality(for: PayloadHint) -> RouteQuality
}

struct TransportCapabilities {
    let maxPayloadBytes: Int
    let supportsLargeMedia: Bool
    let supportsReceipts: Bool
    let requiresServer: Bool          // ← по нему работает DIRECT_ONLY
    let leaksMetadataToServer: Bool
    let isMetered: Bool
}

enum Reachability {
    case reachable(quality: RouteQuality)
    case reachableAfterDiscovery(estimate: TimeInterval)
    case unreachable(reason: String)
}
```

`requiresServer` — не украшение: именно по этому флагу `DIRECT_ONLY` отсекает
маршруты, а не по списку имён. Добавили новый транспорт — он автоматически
попадает в нужную половину.

### 4.1 Выбор маршрута

```
1. Собрать разрешённые транспорты:
     policy.delivery_mode + server_storage + capabilities.requiresServer
2. Отбросить те, где canReach = unreachable
3. Отсортировать: сначала явный порядок пользователя (§6),
   иначе по estimatedQuality с учётом размера контента (§69, §70)
4. Идти по списку до первого DeliveryProof
5. Ни на одном шаге message_id НЕ меняется
```

---

## 5. Приватные идентификаторы обнаружения

Требование §17: пассивный сканер не должен отслеживать человека. Своей крипты
не изобретаем — берём схему, уже проверенную в Exposure Notifications и
Find My, на HKDF и HMAC.

```
DiscoveryKey  DK        32 случайных байта на identity, хранится в Keychain/Keystore
epoch         T         номер 15-минутного интервала, T = floor(unixtime / 900)

EDI(T)  = HKDF-SHA256(DK, salt = "AETHER-NEARBY-1", info = LE64(T))[0..16]
tag     = HMAC-SHA256(EDI(T), advertising_nonce)[0..4]

В эфир уходит:  [ версия(1) | EDI_prefix(6) | nonce(4) | tag(4) ]  = 15 байт
```

Свойства:

* Каждые 15 минут идентификатор меняется целиком. Связать два соседних
  интервала без `DK` нельзя.
* **Контакт узнаёт вас**: `DK` передаётся контактам по уже существующему
  E2EE-каналу. Получив его, устройство контакта заранее считает `EDI` на
  текущее и соседние окна и просто ищет совпадение — сеть при этом не нужна.
* **Незнакомец не узнаёт ничего**: без `DK` маячок неотличим от случайного.
  Он видит только «здесь кто-то из Aether», и то лишь если владелец включил
  публичную видимость.
* Смена `DK` (кнопка «сбросить идентификатор») мгновенно разрывает всю
  прошлую отслеживаемость.

Для режима «видим всем» (§13) рекламируется **отдельный** маячок с
одноразовым `session_id`, не выводимым из `DK`. Он не связывает две встречи
одного человека между собой.

Никогда не транслируются: username, постоянный `user_id`, телефон, почта,
аватар, `server_id`.

---

## 6. Рукопожатие Nearby

Два слоя, и это не избыточность (§50):

```
LINK-слой (защищает метаданные и адрес)      Noise-подобный X25519 ECDH
        ↓                                     на существующем box_encrypt
CONTENT-слой (защищает содержимое)            существующий Olm/Double Ratchet
```

Порядок:

```
1. DEVICE_HELLO      → версия протокола, эфемерный X25519 pub, nonce
2. DEVICE_HELLO_ACK  ← эфемерный X25519 pub, nonce
3. общий секрет = ECDH; ключ канала = HKDF(секрет, транскрипт обоих hello)
4. DEVICE_AUTH       → под ключом канала: Olm identity key, device_id,
                       Ed25519-подпись транскрипта
5. проверка подписи существующим olm_verify_device + сверка с TOFU-пином
6. CAPABILITY_EXCHANGE (только после аутентификации — §70)
7. MESSAGE_ENVELOPE  → обычный Olm-конверт, ничем не отличается от серверного
8. DELIVERY_ACK
```

Подпись покрывает **транскрипт обоих hello** — это и есть защита от MITM:
посредник не сможет подставить свои эфемерные ключи, подпись не сойдётся.

Штатное шифрование Bluetooth единственной защитой не считаем — пункт §50
выполнен буквально: даже полностью скомпрометированный link не даёт доступа к
содержимому, потому что внутри лежит тот же Olm-конверт.

Защита от повтора: `message_id` + `nonce` рукопожатия, окно приёма ограничено;
конверт, уже лежащий в базе, отбрасывается дедупликацией (§4 ТЗ).

---

## 7. Состояния

### 7.1 Сообщение

```
CREATED → ENCRYPTED → QUEUED ──→ DISCOVERING → CONNECTING → SENDING → SENT
                         │            │                        │        │
                         │            └── не нашли ────────────┘        ▼
                         │                    │                    DELIVERED
                         │                    ▼                         │
                         │            WAITING_FOR_NEARBY                ▼
                         │            (только DIRECT_ONLY)            READ
                         ├──→ FAILED (исчерпаны маршруты и попытки)
                         ├──→ EXPIRED (истёк дедлайн политики)
                         └──→ CANCELLED (отменено пользователем)
```

Инвариант: переход в `SENDING` по НОВОМУ маршруту не создаёт новое сообщение,
только новую запись в `message_delivery_attempts`.

### 7.2 Прямой транспорт

```
IDLE → DISCOVERING → FOUND → AUTHENTICATING → CONNECTED → TRANSFERRING
                        │           │                          │
                        │           └── подпись не сошлась ────┤
                        ▼                                      ▼
                   LOST/TIMEOUT                          ACKNOWLEDGED
                                                               │
                                                        DISCONNECTED
```

### 7.3 Secret Session

```
PROPOSED → WAITING → ACCEPTED → ACTIVE ⇄ POLICY_CHANGE_PENDING
    │         │                     │
    │         └── REJECTED          └──→ ENDED
    └── отозвано инициатором
```

### 7.4 Исчезающее сообщение

```
UNOPENED → OPENED → COUNTDOWN → EXPIRED → PURGED
```

`PURGED` отделён от `EXPIRED` намеренно: сначала контент становится
недоступен, потом фоновая вычистка физически затирает запись и файл.

---

## 8. Протокол

Сообщения протокола общие для всех транспортов — по Bluetooth и через сервер
едет одно и то же. Версионирование обязательно (§79):

```jsonc
{
  "v": 1,                    // protocol_version
  "schema": 1,               // message_schema_version
  "type": "MESSAGE_ENVELOPE",
  "message_id": "0192f3c1-...",   // UUIDv7, создаёт ОТПРАВИТЕЛЬ
  ...
}
```

| Тип | Направление | Назначение |
|---|---|---|
| `DEVICE_HELLO` / `_ACK` | link | эфемерные ключи, транскрипт |
| `DEVICE_AUTH` | link | identity + подпись транскрипта |
| `CAPABILITY_EXCHANGE` | link | что умеет устройство (после аутентификации) |
| `MESSAGE_ENVELOPE` | любой | шифротекст + `message_id` |
| `DELIVERY_ACK` | любой | доставлено на устройство |
| `READ_RECEIPT` | любой | прочитано |
| `MESSAGE_DELETE` | любой | удалить у обеих сторон |
| `SECRET_SESSION_OFFER` | любой | предложение с полным текстом правил |
| `SECRET_SESSION_ACCEPT` / `_REJECT` | любой | ответ |
| `SECRET_POLICY_UPDATE` | любой | изменение правил, требует подтверждения |
| `EPHEMERAL_OPENED` | любой | сообщение открыто, запустить отсчёт у отправителя |

**Данным по Bluetooth доверия не больше, чем данным с сервера** (§78). Любое
сообщение протокола проходит те же проверки: подпись, версия, схема, лимиты
размера, дедупликация по `message_id`. Неизвестные типы игнорируются молча —
это уже правило проекта.

### 8.1 Дедупликация

```
получен MESSAGE_ENVELOPE
  → message_id уже есть в messages?
      да  → отбросить, но отправить DELIVERY_ACK (отправитель мог не получить прошлый)
      нет → расшифровать, сохранить, ACK
```

Без второй половины («ACK даже на дубликат») отправитель будет бесконечно
перебирать маршруты, а получатель — молча их отбрасывать.

---

## 9. Secret Sessions

Не отдельный контакт и не отдельный чат — **режим внутри существующего
диалога** (§26). В базе это запись в `secret_sessions`, а сообщения остаются
в общей таблице с `msg_type = 'SECRET'` и `policy_json` от сессии.

### 9.1 Согласование

Получатель видит правила **до** принятия (§28) — предложение приходит с
полным текстом политики, а не со ссылкой на неё:

```
Roman предлагает Secret Session

Копирование        Запрещено
Пересылка          Запрещено
Сохранение медиа   Запрещено
Хранение           Только устройства
Сообщения          Удалять через 1 минуту после просмотра

[ Принять ]   [ Отклонить ]
```

### 9.2 Владелец и изменение правил

Инициатор — `secret_session_owner`, он задаёт политику. Но:

* изменение существенных параметров (время жизни, хранение, разрешение копий)
  переводит сессию в `POLICY_CHANGE_PENDING` и **требует повторного согласия**;
* до согласия действует **прежняя, более строгая** трактовка;
* незаметно ослабить правила нельзя — вторая сторона видит карточку изменения:

```
Roman изменил Secret Session
Время жизни: 1 час → 30 секунд
[ Принять ]  [ Покинуть Secret Session ]
```

### 9.3 Что мы обещаем и чего не обещаем

Честность здесь важнее маркетинга (§31, §32).

| Правило | Что делает Aether | Чего не гарантирует |
|---|---|---|
| Копирование запрещено | убирает пункты меню, отключает выделение | человек перепишет руками |
| Пересылка запрещена | нет действия «переслать» | пересказ словами |
| Сохранение медиа запрещено | нет «сохранить», файл не попадает в галерею | съёмка вторым телефоном |
| Защита экрана | Android — `FLAG_SECURE` (реально блокирует); iOS — обнаружение снимка и скрытие в переключателе приложений | на iOS **не блокирует** запись экрана |
| Хранение только на устройствах | конверт не уходит на сервер вообще | резервные копии ОС на устройстве получателя |

Текст в интерфейсе — ровно такой:

```
Защита экрана включена

Aether блокирует или обнаруживает съёмку экрана там,
где это поддерживает ваше устройство.
```

Никакого «невозможно скопировать».

---

## 10. Исчезающие сообщения и View Once

### 10.1 Не привязаны к Secret Session (§34)

`EPHEMERAL` доступен и в обычном чате. Тип сообщения и режим чата
независимы — см. формулу §84.

### 10.2 Триггеры (§35)

| Триггер | Отсчёт начинается | Кто считает |
|---|---|---|
| `SENT` | по отправке | отправитель и получатель независимо |
| `DELIVERED` | по `DELIVERY_ACK` | оба |
| `FIRST_OPEN` | по первому открытию у получателя | получатель, отправителю уходит `EPHEMERAL_OPENED` |
| `CLOSE` | по закрытию просмотра | получатель |
| абсолютное время | `expires_at` | оба |

Пресеты: 10 сек, 30 сек, 1 мин, 5 мин, 1 час, 24 часа, 7 дней, свой.

### 10.3 Скрытие содержимого

До открытия — размытая плашка (§36). Содержимое **не появляется** в:
предпросмотре уведомления, строке чата в списке, результатах поиска, снимке
переключателя приложений. Это не косметика: `preview_in_notification: false`
проверяется в `NotificationsManager` и в построении `last_text` для списка
чатов.

Для уведомлений (§57) по умолчанию:

```
Alice
Новое защищённое сообщение
```

### 10.4 View Once

Отдельный тип `VIEW_ONCE` с `view_limit: 1`. Подходит для фото, видео,
голосовых, текста, превью файла. После просмотра — `👁 Просмотрено`,
содержимое стирается по той же цепочке `EXPIRED → PURGED`.

«Удерживайте для просмотра» (§39) — режим отображения, **не криптографическая
гарантия**; так и подписано в настройке.

---

## 11. Отложенные сообщения

Уже существуют локально (`ScheduledMessages.swift`), но живут мимо модели
сообщений. Переносим их внутрь: `msg_type = 'SCHEDULED'`, `scheduled_at`.

Ответы на вопросы §40, принятые осознанно:

* **Где планируется:** локально. Серверу шифрованный payload заранее не
  отдаём — это отдало бы содержимое стороне, которая по политике могла его
  вообще не видеть.
* **Если устройство офлайн:** сообщение уходит при первой возможности после
  срока. Гарантировать минуту iOS не даёт, и в UI это уже написано честно —
  формулировку из существующего файла сохраняем.
* **Серверное планирование** появится только вместе с явной галочкой
  «разрешить серверу хранить отложенное» и только для `server_storage`
  ≠ `NEVER`.

---

## 12. Nearby: обнаружение и приватность

### 12.1 Экран

Отдельный раздел (§11), но **не радар с честной геометрией** — Bluetooth даёт
только грубую близость:

```
                РЯДОМ
        [ Bluetooth ] [ Сеть ]

               ●
          Alice · Контакт
              Очень близко

     ●                    ●
 Пользователь        Max · Контакт
   Aether              ~5–15 м
   Рядом              приблизительно

              ◎
              ВЫ
```

Категории: очень близко / рядом / недалеко / далеко. Любая метровая оценка
подписана «приблизительно».

### 12.2 Разделение прав (§56)

Четыре независимые оси. Объединять их в один `isVisible` нельзя:

```
DISCOVERY          может ли он вообще понять, что я рядом
PROFILE VISIBILITY что он при этом увидит
INTERACTION        что он может сделать
DELIVERY           может ли прислать сообщение напрямую
```

Пример допустимой комбинации: виден всем, профиль скрыт до «пользователь
Aether», писать нельзя. Так и должно работать.

### 12.3 Настройки

```
Кто может обнаружить меня
  ○ Никто     ● Контакты     ○ Все     ○ Выбранные

Что видят незнакомые
  ● Только «Пользователь Aether»
  ○ Имя и аватар     ○ Публичный профиль     ○ Настроить

Что незнакомец может
  [●] Открыть профиль   [●] Открыть чат   [ ] Сразу написать
  [ ] Прислать файл     [ ] Позвонить     [ ] Запрос в контакты
```

**Умолчания (§82):** обнаружение — «Контакты»; незнакомцам — только факт
использования Aether; сообщения от незнакомых — в «Запросы»; предпросмотр
защищённого контента — выключен; выгрузка на сервер при `DIRECT_ONLY` —
никогда.

### 12.4 Невидимость (§54) и временная видимость (§55)

`Невидим` означает по-настоящему: остановить рекламу, не отвечать на
сканирование, снять сетевое присутствие. Не «спрятать список в интерфейсе».

Временная видимость — 15 мин / 1 час / 8 часов / до отключения, с
автоматическим откатом по таймеру.

### 12.5 Запросы от незнакомцев (§16)

По умолчанию сообщение незнакомца **не попадает в чаты**, а идёт в отдельный
раздел «Запросы рядом» с действиями Принять / Скрыть / Заблокировать.

### 12.6 Сетевое обнаружение (§18, §19)

Полностью opt-in, точность выбирает пользователь: город / ~5 км / ~1 км /
~100 м / точно. **Клиенту не приходит скрытая точная координата ради
красивого UI** — сервер отдаёт ровно ту точность, которую разрешил владелец.
Это требование к данным на проводе, а не к отрисовке.

### 12.7 Объединение источников (§20)

Если включены оба канала, находки склеиваются по identity — но только для тех,
кого мы и так вправе опознать. Для незнакомца два маячка (BLE и сетевой)
остаются двумя независимыми находками: связать их значило бы выдать больше,
чем он разрешил.

---

## 13. Модель угроз

| Угроза | Последствие | Что делаем | Остаточный риск |
|---|---|---|---|
| Пассивное BLE-слежение | маршрут человека по городу | ротация EDI каждые 15 мин, отдельный маячок для публичного режима, ручной сброс `DK` | наблюдатель внутри окна видит одно устройство 15 минут |
| Подмена личности в Nearby | выдать себя за контакта | подпись Ed25519 транскрипта + сверка с TOFU-пином | первое знакомство — TOFU; лечится сверкой чисел безопасности |
| MITM при рукопожатии | чтение метаданных канала | транскрипт обоих hello под подписью | содержимое всё равно защищено Olm |
| Повтор сообщения | дубликат | nonce + дедупликация по `message_id` | — |
| Дублирование при смене маршрута | два сообщения в истории | сквозной UUIDv7, ACK и на дубликат | — |
| Вредоносный пользовательский сервер | сбор метаданных, отказ в доставке | `DIRECT_ONLY`, `RELAY_ONLY`, политика категорий, отдельные пространства | сервер всегда видит, кто с кем и когда, если используется |
| Кража устройства | доступ ко всему | SQLCipher, ключи в Keychain/Keystore, PIN/Face ID | разблокированное устройство в чужих руках |
| Восстановление истёкших сообщений из базы | чтение «удалённого» | `PURGED` физически затирает запись и файл, WAL-чекпойнт | судебная экспертиза носителя |
| Утечка через уведомления | содержимое на локскрине | для защищённых типов предпросмотр выключен по умолчанию | — |
| Утечка через резервные копии | контент в бэкапе ОС | защищённые вложения помечаются как исключаемые из бэкапа | бэкап устройства получателя нам неподконтролен |
| Снимок экрана | утечка «одноразового» | Android `FLAG_SECURE`, iOS — обнаружение | съёмка вторым телефоном; **честно сказано в UI** |
| Спам от незнакомцев | заваливание | «Запросы» по умолчанию, лимиты, блокировка | — |
| Преследование по геолокации | слежка | точность выбирает владелец, сеть opt-in, невидимость | доверенный контакт видит то, что вы ему разрешили |
| Скомпрометированный аккаунт | чтение переписки | ратчет, отзыв устройств, cross-signing | — |

---

## 14. Файлы проекта, которые нужно изменить

### Ядро (`core/`)

| Файл | Что |
|---|---|
| `src/store.rs` | миграции 3.1 и 3.3, новые запросы; **схема существующих таблиц не ломается** |
| `src/message.rs` *(новый)* | `AetherMessage`, `MessagePolicy`, каскад политик, UUIDv7 |
| `src/transport.rs` *(новый)* | `SealedEnvelope`, `TransportId`, `DeliveryProof`, дедупликация |
| `src/nearby.rs` *(новый)* | EDI/HKDF, разбор маячка, транскрипт рукопожатия |
| `src/api.rs` | `client_id` обязателен; `relay_only`; `route` в ACK |
| `src/crypto.rs` | `hkdf_sha256` (нужен и здесь, и для переноса данных) |
| `examples/nearby_handshake.rs`, `examples/dedup.rs` *(новые)* | кросс-тесты без UI |
| `WIRE_PROTOCOL.md` | раздел про конверт, EDI и рукопожатие |

### iOS (`ios/AETHER/`)

| Файл | Что |
|---|---|
| `Core/TransportRouter.swift` *(новый)* | выбор маршрута, порядок, отсечка по `requiresServer` |
| `Core/TransportAdapter.swift` *(новый)* | протокол + реестр |
| `Core/Transports/ServerTransport.swift` *(новый)* | обёртка над существующим `api.sendMessage` |
| `Core/Transports/BleTransport.swift` *(новый)* | CoreBluetooth: реклама, скан, GATT-канал |
| `Core/Transports/WiFiAwareTransport.swift` *(новый)* | iOS 26 `WiFiAware`, крупные вложения |
| `Core/Nearby/NearbyDiscoveryService.swift` *(новый)* | объединение BLE и сети, близость |
| `Core/Nearby/NearbyIdentityResolver.swift` *(новый)* | сопоставление EDI с контактами |
| `Core/DeliveryQueue.swift`, `Core/RetryEngine.swift` *(новые)* | очередь и повторы |
| `Core/MessagePolicyEngine.swift` *(новый)* | каскад политик |
| `Core/SecretSessionManager.swift`, `Core/EphemeralManager.swift` *(новые)* | режимы |
| `Core/Messaging.swift` | отправка через Router; **убрать `replaceMessageId`** |
| `Core/ScheduledMessages.swift` | переезд в модель сообщений |
| `Core/NotificationsManager.swift` | скрытие предпросмотра защищённых типов |
| `Features/Nearby/NearbyView.swift` *(новый)* | экран «Рядом» |
| `Features/Nearby/NearbyRequestsView.swift` *(новый)* | запросы от незнакомцев |
| `Features/Chat/MessageInfoView.swift` *(новый)* | Message Info (§10) |
| `Features/Chat/ChatView.swift` | режимы композера, long press на отправку, индикаторы маршрута |
| `Features/Chat/SecretSessionSheet.swift` *(новый)* | предложение и согласование |
| `Features/Settings/NearbyPrivacyView.swift` *(новый)* | §13–15 |
| `Features/Settings/DeliverySettingsView.swift` *(новый)* | §5, §6, §43 |
| `App/HomeView.swift` | вкладка «Рядом» |
| `project.yml` | `NSBluetoothAlwaysUsageDescription`, Bonjour-сервисы, фоновые режимы, entitlement Wi-Fi Aware |

### Android (`android/`)

Порт после стабилизации iOS. Общее ядро (`sm_core`) даёт EDI, дедупликацию и
конверты бесплатно; платформенными остаются BLE-адаптер, Wi-Fi Aware и
foreground service.

### Сервер (`server/`)

| Файл | Что |
|---|---|
| `main.py` | `relay_only` (удаление после ACK), `route` в ACK, presence для сетевого Nearby |
| `routes_nearby.py` *(новый)* | сетевое обнаружение с загрублением координат **на сервере** |

---

## 15. Поэтапная реализация

Каждый этап заканчивается проверяемым результатом. После любого этапа
приложение остаётся рабочим, старые сообщения — читаемыми.

| Этап | Содержание | Чем проверяется |
|---|---|---|
| **0. Фундамент** | сквозной UUIDv7 вместо `replaceMessageId`, дедупликация, `message_delivery_attempts`, `message_route` | переписка со старой сборкой не ломается; повтор конверта не создаёт дубликат |
| **1. Router и серверный адаптер** | `TransportAdapter`, `TransportRouter`, `ServerTransport` как единственный адаптер | поведение приложения не изменилось ни в чём; тесты отправки зелёные |
| **2. Политики** | `MessagePolicyEngine`, `chat_delivery_policy`, `server_storage_policy`, экран настроек доставки | `DIRECT_ONLY` без локальных транспортов честно даёт `WAITING_FOR_NEARBY`, а не тихий уход на сервер |
| **2а. Квитанции под политикой** ✔ | квитанции о доставке и прочтении проходят через роутер и политику чата; при запрете сервера просто не отправляются | сделано: при `DIRECT_ONLY` открытие чата не оставляет на сервере ничего, при `AUTO` квитанция уходит как прежде |
| **3. Message Info** | `message_route`, экран §10, компактные индикаторы §25 | видно маршрут, участие сервера и факт хранения |
| **4. Исчезающие и View Once** | типы, таймеры, размытие, `ephemeral_state`, вычистка, приватность уведомлений | сообщение исчезает по каждому из триггеров; нет утечки в превью и поиске |
| **5. Nearby: обнаружение** | EDI/HKDF в ядре, BLE-реклама и скан, экран «Рядом», приватность §13–15 | два устройства находят друг друга; чужой сканер не связывает два окна |
| **6. Nearby: доставка** | рукопожатие §6, `BleTransport`, `WAITING_FOR_NEARBY`, запросы от незнакомцев | сообщение уходит без сети; сервер в логах не участвует |
| **7. Direct Wi-Fi** | `WiFiAwareTransport`, выбор маршрута по размеру (§69) | фото и видео идут быстрым каналом, iPhone ↔ Android |
| **8. Secret Sessions** | согласование, смена правил, ограничения интерфейса, защита экрана | правила видны до принятия; ослабление требует согласия |
| **9. Отложенные** | переезд в модель сообщений, `SCHEDULED` | существующие отложенные не теряются |
| **10. Сетевое Nearby** | presence, загрубление на сервере, вкладка «Сеть» | клиенту не приходит точность выше разрешённой |

Порядок выбран так, что первые четыре этапа не требуют ни одной платформенной
возможности и не могут сломаться из-за Bluetooth: сначала модель, потом
маршрутизация, и только потом радио.

### Проверки после каждого этапа

```
сборка iOS · сборка Android · существующая переписка · вход и сессии ·
подключение к серверам · миграции базы · обратная совместимость конвертов
```

---

## Приложение А. Что сознательно не делается

* **RCS** — публичного API для сторонних приложений нет ни на одной из
  платформ. Остаётся точкой расширения без единой строки кода.
* **SMS на iOS как транспорт** — невозможно; будет только «передать черновик
  в Сообщения», и назван этот пункт будет именно так.
* **Честный геометрический радар для Bluetooth** — RSSI не даёт расстояния,
  рисовать точные позиции значит врать.
* **Обещание блокировки снимков экрана на iOS** — система этого не даёт.
* **Собственный шифр** — используем только vodozemac, HKDF, HMAC, AES-GCM,
  X25519, Ed25519.
* **Единый `isVisible`** — четыре оси прав не схлопываются в один флаг.
* **Отдельные чаты под каждый транспорт** — прямо запрещено §2.

---

## Приложение Г. Пароли: что сделано и что осталось

Разбор показал, что самый вероятный путь взлома — не криптография, а пароль.
Всё сходится в нём: он же открывает резервную копию приватного ключа.

**Сделано.** Пароли на сервере переведены на Argon2id (64 МиБ, 3 прохода).
PBKDF2 остался только для ПРОВЕРКИ старых хешей; при первом успешном входе
хеш молча пересчитывается. Никого не выкидывает, менять пароль не просят.
Добавлена политика для новых учётных записей: не короче 10 символов, без
самых частых, без однообразных и подряд идущих, без имени пользователя внутри.
Порог на клиенте выровнен с серверным — раньше кнопка оживала на четырёх
символах, а сервер требовал восемь.

**Осталось: резервная копия приватного ключа.** Она по-прежнему шифруется
PBKDF2 со 100 000 итераций (`core/src/crypto.rs`, `encrypt_private_key`).
Это тот же слабый вывод ключа, и именно этот блоб лежит на сервере — при
утечке дампа его перебирают офлайн, без ограничения частоты.

Почему не переведено сразу: блоб общий для всех клиентов. Аккаунт, созданный
обновлённым iOS, не откроется на Android и в вебе, пока они не научатся читать
новый формат. Порядок обязан быть таким:

1. ✔ **сделано:** ядро читает оба формата, продолжая писать старый.
   v1 — `salt:iv:ct` (PBKDF2 100k), v2 — `v2:argon2id:m:t:p:salt:iv:ct`.
   Параметры Argon2id лежат внутри блоба, поэтому перенастройка стоимости не
   сделает ранее созданные копии нечитаемыми. Запись v2 доступна отдельным
   вызовом `encrypt_private_key_v2` и пока не используется;
2. дождаться, пока обновятся Android и веб;
3. переключить запись на Argon2id (замена одного вызова в
   `CoreClient.register`) и пересчитывать блоб при входе, как это уже
   делается с серверным хешем.

Шаг 1 безопасен и не даёт выигрыша сам по себе; выигрыш появляется на шаге 3.
Делать шаг 3 раньше времени — значит запереть людей вне их же переписки на
других устройствах.
