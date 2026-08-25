# AETHER — мультисерверность, допуск пользователей и перенос данных

Проект системы: официальная инфраструктура + собственные серверы пользователей,
допуск новых участников администратором прямо в чате, явное согласие на перенос
данных. Написан по факту кода репозитория на 25.08.2026 (ветка `web-secure`),
а не по памяти. Читается вместе с `docs/PROJECT_STATE.md` и `WIRE_PROTOCOL.md`.

Документ — проектный. Реализация идёт этапами (раздел 20), код по ходу может
уточнять детали; расхождения фиксировать здесь же.

---

## 0. Разбор существующей архитектуры и что в ней мешает

### 0.1 Что есть сейчас

| Слой | Файл | Как устроено сегодня |
|---|---|---|
| Адрес сервера | `ios/AETHER/Core/Secrets.swift` | `static let host` — **одна константа на всю сборку** |
| HTTP-клиент | `core/src/api.rs`, `ApiClient::new(base_url)` | экземпляр создаётся один раз в `CoreClient.init()` |
| Фасад ядра | `ios/AETHER/Core/CoreClient.swift` | `static let baseURL = Secrets.baseURL`, `private let api`, ленивый `store` |
| Сессия | `ios/AETHER/Core/Session.swift` | мультиаккаунт до 5 штук, ключ реестра — `userId` |
| Секреты | `ios/AETHER/Core/Keychain.swift` | ключи `acct_<userId>_token/pub/priv` + legacy-ключи активного |
| Локальная БД | `CoreClient.accountDatabasePath` | `aether_<userId>.sqlite`, SQLCipher, ключ БД в Keychain |
| Сервер | `server/main.py` (2755 строк) | FastAPI + PostgreSQL, плоские маршруты без префикса версии |
| Realtime | `server/main.py:@app.websocket("/ws")` | токен в query, релей typing/presence/webrtc |
| Роли | только внутри групп (`group_members.role`) | **роли уровня сервера отсутствуют** |

Существенные наблюдения:

1. **Мультиаккаунт уже есть, но он одномерный.** `Session.accounts` — массив
   `userId`, и он неявно предполагает «все аккаунты на одном сервере». Ключ
   `acct_<userId>_token` на двух разных серверах с одинаковым логином `roman`
   схлопнется в одну запись Keychain и подставит токен чужого сервера. Это
   первая вещь, которую надо чинить, и чинится она сменой первичного ключа.
2. **Изоляция данных на 80 % уже готова.** Отдельный `aether_<user>.sqlite` на
   аккаунт, `meta(account_owner)` как владелец файла, SQLCipher. Не хватает
   ровно одного измерения — сервера. Схему БД ядра менять не нужно вообще:
   разделение остаётся файловым.
3. **`ApiClient` уже параметризован base_url** — конструктор принимает адрес.
   Значит мультисерверность в Rust-ядре стоит дёшево: по экземпляру на сервер,
   без правки протокола, крипты и хранилища.
4. **Токен уже не глобальный в ядре** (`RwLock` внутри `ApiClient`), но глобален
   в Swift (`Session.authToken` + legacy-ключи Keychain).
5. **Сервер не имеет понятия «сервер».** Нет `server_id`, нет имени, нет
   собственной ключевой пары, нет политики регистрации, нет ролей. Клиент не
   может отличить один инстанс от другого — а значит, не может и защититься
   от подмены.
6. **20+ мест UI строят URL из `CoreClient.baseURL`** (аватарки, поиск, пуши).
   Это механический рефакторинг, но он обязателен, иначе в пространстве
   собственного сервера будут грузиться аватарки с официального.
7. **API без версии.** Требование ТЗ — `/api/v1/...`. Переименовать нельзя:
   сломаются Android, веб и все установленные iOS-сборки разом.

### 0.2 Принцип минимально разрушительной интеграции

Пять правил, которым подчинён весь дальнейший проект:

1. **Ядро (`core/`) не меняет ни протокол, ни крипту, ни схему SQLite.** Только
   добавляются новые методы и структуры. Кросс-совместимость с Android и вебом
   сохраняется побайтно.
2. **Старые маршруты сервера остаются навсегда.** `/api/v1` появляется как
   второй монтаж того же роутера. Новые возможности живут только в `/api/v1` —
   старый клиент их не видит и не ломается.
3. **Существующий аккаунт пользователя не трогается.** При первом запуске новой
   версии он молча становится аккаунтом на сервере «Aether Cloud»; ни базу, ни
   Keychain, ни переписку не переносим и не перешифровываем.
4. **Сервер добавляет только таблицы.** Ни одного `DROP`, ни одного изменения
   типа колонки. Стиль миграций — существующий: идемпотентный `init_db()` с
   проверками через `information_schema`.
5. **UI не превращается в Discord.** Никаких списков серверов в левой колонке,
   никаких «гильдий». Сервер — это строка-переключатель в шапке чатов и раздел
   в настройках. Главный экран остаётся списком чатов и людей.

### 0.3 Где требования ТЗ конфликтуют с безопасностью

Три конфликта, каждый решается в пользу безопасности; решения расписаны дальше.

| Требование | Проблема | Решение |
|---|---|---|
| §2 «LAN-серверы, отдельный режим локального подключения» | соблазн выключить проверку TLS «для локалки» — это отключение защиты для самого уязвимого сценария (открытый Wi-Fi) | TLS-проверка **не отключается никогда**. Локальный режим = либо явный cleartext-HTTP только для приватных диапазонов адресов с красным предупреждением (`NSAllowsLocalNetworking`), либо TOFU-пин самоподписанного сертификата по SPKI. Раздел 16.4 |
| §9 «Безопасность → [ ] Переносимые ключи шифрования» | категория читается как «отправить ключи на сервер», что прямо запрещено §10 | Категории «отправить ключи» не существует. Есть «архив переписки», ключ которого **не покидает устройство**: он оборачивается парольной фразой пользователя и его же публичным ключом на целевом сервере. Раздел 15 |
| §7/§9 «перенести имя профиля и аватар» | это не может быть E2EE: сервер обязан отдавать их другим пользователям | Категории делятся на **операционные** (сервер видит по необходимости) и **непрозрачные** (сервер получает только шифротекст). В UI у каждой категории написано, что именно увидит администратор. Раздел 9.2 |

---

## 1. Итоговая архитектура

### 1.1 Семь сущностей и их границы

```
SERVER          инфраструктура: server_id, домен, ключ, политика регистрации
   │ 1..N
ACCOUNT         учётная запись НА КОНКРЕТНОМ СЕРВЕРЕ: (server_id, user_id)
   │ 1..N
DEVICE          устройство: device_id, Olm-identity, cross-signing
   │ 1..N
SESSION         авторизованная сессия устройства: access + refresh, отзываема

IDENTITY        криптоличность аккаунта на сервере: X25519 + Olm + master key
                (НЕ переносится между серверами — см. 1.3)

DATA PERMISSIONS  что конкретному серверу разрешено получить; живёт ТОЛЬКО
                  на клиенте, сервер получает лишь производный grant на импорт

IMPORT SESSION  одна операция миграции: манифест, чанки, прогресс, срок жизни
```

Правила, которые нельзя нарушать:

* `SERVER` — корень всего. Всё, что ниже, всегда квалифицировано `server_id`.
* Первичный ключ аккаунта на клиенте — **пара `(server_id, user_id)`**, никогда
  не `username` и никогда не домен. Домен меняется (переезд, смена DNS), логин
  меняется (переименование), `server_id` и `account_no` — нет.
* `IDENTITY` принадлежит паре `(server, account)`. Один и тот же человек на
  двух серверах — это две разные криптоличности. Это не недостаток, а
  требование модели доверия: иначе компрометация чужого сервера отражалась бы
  на официальном аккаунте.
* `DATA PERMISSIONS` — величина клиентская. Сервер не является источником
  правды о том, что ему разрешено; он лишь получает то, что клиент решил дать.

### 1.2 Пространство (Space) — то, что видит пользователь

`Space = (Server, Account)`. Пользователь переключает пространства, а не
«серверы»: на одном сервере у него может быть два аккаунта, и это две строки
в переключателе. Всё состояние приложения — список чатов, контакты, папки,
непрочитанные, WebSocket, ключи, медиа-кеш — привязано к активному Space.

```
Space(cloud, roman)              Space(roman-home, roman123)
├── aether_a71f3c02_roman.sqlite ├── aether_9db4e155_roman123.sqlite
├── Keychain srv.a71f…acct.roman ├── Keychain srv.9db4…acct.roman123
├── ApiClient(https://cloud…)    ├── ApiClient(https://chat.example.com)
├── WsClient(wss://cloud…/ws)    ├── WsClient(wss://chat.example.com/ws)
├── Olm-аккаунт, prekeys         ├── свой Olm-аккаунт, свои prekeys
└── медиа-кеш /Media/a71f3c02/   └── медиа-кеш /Media/9db4e155/
```

Ничего общего между колонками нет. Ни одного байта, ни одного токена.

### 1.3 Почему криптоличность не переезжает

Соблазн — сделать «один Aether-аккаунт, много серверов». Так делать нельзя:

* приватный ключ, попавший на второй сервер, расширяет поверхность атаки на
  первый: скомпрометированный самохост получил бы возможность выдавать себя за
  пользователя перед официальным сервером;
* Double Ratchet требует, чтобы prekey-директория соответствовала ровно одному
  набору сессий; общая identity на два сервера ломает счётчики OTK и
  восстановление после компрометации;
* модель доверия (раздел 11) прямо говорит: пользовательский сервер —
  потенциально недоверенная инфраструктура. Значит, идентичность на нём —
  отдельная.

Что переносится вместо этого — раздел 15: **архив данных**, зашифрованный на
устройстве ключом, который серверу не выдаётся.

### 1.4 Официальный сервер не имеет привилегий на уровне протокола

`Aether Cloud` — обычная запись в реестре серверов с флагом `kind = .official`.
Флаг влияет ровно на две вещи, обе косметические:

* сервер показан первым и подписан «Официальный»;
* его нельзя удалить из списка (можно выйти из аккаунта, запись останется).

Ни одного отдельного кода в клиенте, ни одного маршрута, ни одного заголовка,
доступного только ему. Проверка при код-ревью: `grep -r "official"` в клиенте
должен давать только UI-строки и сортировку.

---

## 2. Структура сущностей и таблиц БД

### 2.1 Сервер (PostgreSQL, `server/main.py`, `init_db()`)

Только новые таблицы. Существующие (`users`, `sessions`, `messages`, `groups`,
`group_members`, `one_time_keys`, `crypto_devices`, `history_backups`, …) не
меняются, кроме двух добавляемых колонок.

```sql
-- Идентичность инстанса. Кладём в существующую server_meta (key, value).
--   aether.server_id            UUID, генерируется один раз при первом старте
--   aether.server_name          человекочитаемое имя ("Roman Home")
--   aether.ed25519_pub_b64      публичный ключ сервера (подпись /server/info)
--   aether.ed25519_priv_b64     приватный ключ, только на сервере
--   aether.registration_mode    OPEN | APPROVAL | INVITE_ONLY | CLOSED
--   aether.data_import_enabled  '0' | '1'
--   aether.import_quota_bytes   квота импорта на аккаунт
--   aether.request_ttl_days     срок жизни заявки (по умолчанию 14)
--   aether.official             '0' | '1'  — только вывеска, привилегий не даёт

CREATE TABLE IF NOT EXISTS server_roles (
    user_id     TEXT PRIMARY KEY,
    role        TEXT NOT NULL CHECK (role IN ('OWNER','ADMIN','MODERATOR','USER')),
    granted_by  TEXT,
    granted_at  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS registration_requests (
    request_id   TEXT PRIMARY KEY,             -- uuid4
    code         TEXT UNIQUE NOT NULL,         -- 'REQ-84A91', показывается человеку
    user_id      TEXT NOT NULL,                -- запрошенный логин, lower
    display_name TEXT,
    message      TEXT,                         -- сообщение администратору, <= 500
    password_hash TEXT NOT NULL,               -- argon2/bcrypt, как в users
    public_key_b64            TEXT NOT NULL,
    encrypted_private_key_b64 TEXT,
    encrypted_olm_account_b64 TEXT,
    status       TEXT NOT NULL DEFAULT 'pending'
                 CHECK (status IN ('pending','approved','rejected','cancelled','expired')),
    created_at   TEXT NOT NULL,
    expires_at   TEXT NOT NULL,
    decided_at   TEXT,
    decided_by   TEXT,                          -- user_id администратора
    reject_reason TEXT,
    claim_token_hash TEXT NOT NULL,             -- sha256; заявитель опрашивает статус без аккаунта
    ip_hash      TEXT,                          -- sha256(ip + серверная соль), для анти-абьюза
    ua_hash      TEXT
);
CREATE INDEX IF NOT EXISTS idx_reqs_status  ON registration_requests(status, created_at);
CREATE UNIQUE INDEX IF NOT EXISTS idx_reqs_pending_user
    ON registration_requests(user_id) WHERE status = 'pending';

CREATE TABLE IF NOT EXISTS invites (
    code_hash   TEXT PRIMARY KEY,               -- sha256(code); сам код нигде не хранится
    label       TEXT,
    created_by  TEXT NOT NULL,
    created_at  TEXT NOT NULL,
    expires_at  TEXT,
    max_uses    INTEGER NOT NULL DEFAULT 1,
    uses        INTEGER NOT NULL DEFAULT 0,
    revoked     INTEGER NOT NULL DEFAULT 0,
    grants_role TEXT NOT NULL DEFAULT 'USER'
);

CREATE TABLE IF NOT EXISTS audit_log (
    id        BIGSERIAL PRIMARY KEY,
    ts        TEXT NOT NULL,
    actor_id  TEXT,
    action    TEXT NOT NULL,        -- registration.approve, role.grant, import.complete, …
    target    TEXT,
    meta_json TEXT,
    ip_hash   TEXT
);
CREATE INDEX IF NOT EXISTS idx_audit_ts ON audit_log(ts DESC);

CREATE TABLE IF NOT EXISTS refresh_tokens (
    token_hash  TEXT PRIMARY KEY,   -- sha256(refresh); сырой токен на сервере не лежит
    user_id     TEXT NOT NULL,
    device_id   TEXT NOT NULL DEFAULT 'primary',
    family_id   TEXT NOT NULL,      -- цепочка ротации, для reuse detection
    issued_at   TEXT NOT NULL,
    expires_at  TEXT NOT NULL,
    used_at     TEXT,
    replaced_by TEXT,
    revoked     INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_refresh_family ON refresh_tokens(family_id);

CREATE TABLE IF NOT EXISTS import_sessions (
    session_id   TEXT PRIMARY KEY,
    user_id      TEXT NOT NULL,
    device_id    TEXT NOT NULL,
    created_at   TEXT NOT NULL,
    expires_at   TEXT NOT NULL,
    status       TEXT NOT NULL DEFAULT 'open'
                 CHECK (status IN ('open','completing','completed','failed','cancelled','expired')),
    manifest_json TEXT NOT NULL,     -- категории, счётчики, размеры, режим шифрования
    categories   TEXT NOT NULL,      -- явный grant: что клиент РАЗРЕШИЛ этой сессии
    total_bytes  BIGINT NOT NULL,
    chunk_count  INTEGER NOT NULL,
    received_bytes BIGINT NOT NULL DEFAULT 0,
    received_chunks INTEGER NOT NULL DEFAULT 0,
    manifest_sha256 TEXT NOT NULL,
    completed_at TEXT
);

CREATE TABLE IF NOT EXISTS import_chunks (
    session_id  TEXT NOT NULL,
    seq         INTEGER NOT NULL,
    category    TEXT NOT NULL,
    nonce_b64   TEXT NOT NULL,
    storage_ref TEXT NOT NULL,       -- путь к файлу шифротекста в uploads/import/
    size        INTEGER NOT NULL,
    sha256      TEXT NOT NULL,
    received_at TEXT NOT NULL,
    PRIMARY KEY (session_id, seq)
);

CREATE TABLE IF NOT EXISTS data_erasure_requests (
    id         BIGSERIAL PRIMARY KEY,
    user_id    TEXT NOT NULL,
    scope      TEXT NOT NULL,        -- 'import' | 'account'
    created_at TEXT NOT NULL,
    status     TEXT NOT NULL DEFAULT 'received',   -- received | done | refused
    handled_at TEXT,
    note       TEXT
);
```

Две добавляемые колонки (через существующий `information_schema`-паттерн):

```sql
ALTER TABLE users    ADD COLUMN approved_by TEXT;      -- кто впустил, NULL для OPEN
ALTER TABLE sessions ADD COLUMN family_id  TEXT;       -- связь access ↔ refresh
```

### 2.2 Клиент (iOS)

Реестр серверов — не SQLite, а отдельный JSON в Application Support
(`servers.json`), потому что он должен читаться **до** открытия любой БД и не
зависеть от того, какая база сейчас активна. Секретов в нём нет.

```swift
struct ServerRecord: Codable, Identifiable {
    var id: String                  // server_id (UUID) — первичный ключ
    var kind: Kind                  // .official | .custom
    var displayName: String         // локальное имя, редактируемое пользователем
    var declaredName: String        // как сервер назвал себя сам
    var origin: String              // https://chat.example.com:8443 (нормализовано)
    var apiURL: String              // объявленный сервером
    var wsURL: String
    var protocolVersion: Int
    var registrationMode: RegistrationMode
    var capabilities: Set<Capability>   // e2ee, dataImport, invites, calls, …
    var pin: ServerPin
    var transport: Transport        // .tls | .lanCleartext | .lanPinnedCert
    var trusted: Bool               // «доверенный сервер», см. 12
    var dataPolicy: ServerDataPolicy
    var accounts: [AccountRef]
    var addedAt: Date
    var lastConnectedAt: Date?
}

struct ServerPin: Codable {          // TOFU-отпечаток, раздел 16
    var serverIdSeen: String
    var ed25519FingerprintB64: String
    var tlsSPKISha256: String?       // только для .lanPinnedCert
    var firstSeenAt: Date
    var lastVerifiedAt: Date
    var history: [PinChange]         // каждая смена ключа остаётся в истории
}

struct AccountRef: Codable {
    var userId: String
    var accountNo: Int64?
    var displayName: String
    var avatarFileId: String
    var dbFileName: String           // aether_<srv8>_<user>.sqlite
    var role: ServerRole             // роль НА ЭТОМ сервере, кеш ответа сервера
    var lastLoginAt: Date?
}

struct ServerDataPolicy: Codable {
    var grants: [DataCategory: CategoryGrant]
    var lastReviewedAt: Date?
    var importHistory: [ImportRecord]   // что и когда реально ушло
}
```

Keychain (`ios/AETHER/Core/Keychain.swift`), новая схема ключей:

```
srv.<serverId>.acct.<userId>.access      access-токен
srv.<serverId>.acct.<userId>.refresh     refresh-токен
srv.<serverId>.acct.<userId>.pub         публичный ключ идентичности
srv.<serverId>.acct.<userId>.priv        приватный ключ идентичности
srv.<serverId>.dbkey                     ключ SQLCipher для баз этого сервера
```

`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` сохраняется. Legacy-ключи
(`session_token`, `acct_<id>_*`) при миграции переносятся под `srv.<официальный
server_id>.…` и **удаляются** — иначе одноимённый аккаунт на чужом сервере
затрёт их (это и есть баг, описанный в 0.1.1).

Локальные БД: `aether_<первые8символовServerId>_<userId>.sqlite`. Существующая
`aether.sqlite` не переименовывается — в её `meta` дописывается
`server_id = <официальный>`, и реестр указывает на неё по имени файла.

---

## 3. Связи между таблицами

```
server_meta (server_id, ed25519, registration_mode, …)   ← синглтон инстанса
     │
     ├─< server_roles.user_id ──────────┐
     │                                  │
users.user_id ────┬─< sessions ─────────┤  sessions.family_id ─ refresh_tokens.family_id
                  ├─< crypto_devices    │
                  ├─< one_time_keys     │
                  ├─< messages(sender/recipient)
                  ├─< group_members ─> groups
                  ├─< history_backups
                  ├─< import_sessions ─< import_chunks
                  └─< data_erasure_requests
                                        │
registration_requests.user_id ──(при approve)──> users.user_id
registration_requests.decided_by ───────────────> server_roles.user_id
invites.created_by ─────────────────────────────> server_roles.user_id
audit_log.actor_id ─────────────────────────────> users.user_id (может быть NULL)
```

Клиент:

```
ServerRecord.id ──1:N──> AccountRef.userId ──1:1──> локальная БД (файл)
        │                        │
        │                        └──1:1──> Keychain srv.<id>.acct.<user>.*
        ├──1:1──> ServerPin           (TOFU)
        └──1:1──> ServerDataPolicy ──1:N──> CategoryGrant ──1:N──> ImportRecord
```

Ключевое ограничение целостности на клиенте: **не существует ни одной таблицы
и ни одного файла, который читается двумя серверами одновременно.** Общие на
устройстве только: ключ блокировки приложения (PIN/Face ID), тема оформления и
реестр серверов.

---

## 4. Состояния авторизации

Клиентский конечный автомат Space. Реализуется как `enum SpaceState` в новом
`ios/AETHER/Core/ServerSession.swift`.

```
                    ┌──────────────┐
                    │ unconfigured │   серверов нет вообще (первый запуск)
                    └──────┬───────┘
                           │ ввод адреса
                    ┌──────▼───────┐   GET /.well-known/aether → /server/info
                    │  discovering │──ошибка──> discoveryFailed(reason)
                    └──────┬───────┘
                           │ ответ разобран и подпись проверена
                    ┌──────▼───────┐
                    │  discovered  │   показываем карточку сервера
                    └──────┬───────┘
                           │ «Продолжить»
                    ┌──────▼───────────┐
                    │ unauthenticated  │◄────────── logout / revoked
                    └──┬────────────┬──┘
             login     │            │  register
                ┌──────▼─────┐  ┌───▼────────────────┐
                │authenticating│  │ registering        │
                └──┬────┬──────┘  └───┬───────┬────┬──┘
        totpRequired│    │ok       OPEN│  APPROVAL│  INVITE_ONLY
                ┌───▼──┐ │            │       │        │
                │ totp │ │            │       │        └─> needsInvite
                └───┬──┘ │            │       │
                    │    │            │  ┌────▼──────────────┐
                    └────┤            │  │ awaitingApproval  │  (request_id, code)
                         │            │  └────┬────┬─────┬───┘
                    ┌────▼────────────▼─┐     │    │     │
                    │   authenticated   │◄────┘    │     │ rejected / expired
                    └──┬────────┬───────┘  approved│     ▼
        access истёк   │        │ сеть пропала     │  registrationDenied(reason)
                 ┌─────▼────┐ ┌─▼──────────┐       │
                 │refreshing│ │ offline    │       └──> «Войти на сервер»
                 └──┬────┬──┘ └─┬──────────┘
                    │    │      │ сеть вернулась
             ok ────┘    │ 401  └──> authenticated
                         ▼
                     revoked  ──> unauthenticated (+ баннер «сессия завершена»)
```

Отдельные терминальные состояния ошибок, каждое со своим экраном:

* `discoveryFailed(.notAether | .unreachable | .tlsInvalid | .protocolTooNew)`
* `serverIdentityChanged(old, new)` — экран из раздела 16, вход заблокирован
* `registrationClosed` — режим CLOSED
* `serverFull` / `rateLimited(retryAfter)`

Инварианты:
* из `awaitingApproval` нельзя попасть в `authenticated` минуя `login` — сервер
  не выдаёт токен вместе с одобрением, потому что одобрение приходит по WS и не
  доказывает владение паролем;
* переход в `revoked` обязан немедленно рвать WebSocket и стирать access/refresh
  из Keychain для этого Space, не трогая остальные;
* `offline` не стирает ничего и не показывает форму входа: чаты этого Space
  читаются локально.

---

## 5. Состояния заявки на регистрацию

```
        POST /api/v1/registration/request
                     │
                 ┌───▼────┐
                 │ pending│───── expires_at прошёл (cron/lazy) ──> expired
                 └─┬──┬──┬┘
   admin approve  │  │  │  заявитель DELETE .../request/{id}
                  │  │  └────────────────────> cancelled
                  │  │ admin reject (+reason)
                  │  └─────────────────────> rejected
                  ▼
              approved ──(создан users.user_id, approved_by=admin)──> вход возможен
```

Правила:

* терминальные состояния (`approved`, `rejected`, `cancelled`, `expired`) не
  меняются — повторное решение по обработанной заявке возвращает `409`, чтобы
  двойной тап двух администраторов не создал двух пользователей;
* переход `pending → approved` выполняется **одной транзакцией**: вставка в
  `users`, обновление статуса, запись в `audit_log`. Уникальность логина
  проверяется внутри транзакции — между подачей и одобрением имя мог занять
  кто-то другой, в этом случае одобрение падает с понятной ошибкой и заявка
  остаётся `pending`;
* `expired` вычисляется лениво при чтении и подчищается фоновой задачей;
  просроченная заявка удаляет `password_hash` и ключи (данные несостоявшегося
  аккаунта не должны лежать вечно);
* заявитель опрашивает статус по `claim_token`, который получил при подаче, —
  без аккаунта и без сессии. Токен одноразово-долгоживущий, хранится хешем.

---

## 6. Модель permissions

### 6.1 Роли на сервере

```
OWNER      всё: настройки сервера, роли, пользователи, заявки, безопасность,
           инвайты, аудит, удаление данных
ADMIN      пользователи, заявки, инвайты, модерация, чтение аудита
MODERATOR  заявки (одобрить/отклонить), ограниченная модерация
USER       обычный пользователь
```

Иерархия строгая: `OWNER > ADMIN > MODERATOR > USER`. Проверка — только на
сервере, зависимостью `require_role(min_role)`; роль читается из `server_roles`
по `current_user`, **никогда** из тела запроса, заголовка или JWT-клейма,
присланного клиентом. Клиентский UI лишь прячет кнопки — это удобство, а не
защита.

Бутстрап первого OWNER (три способа, по убыванию предпочтительности):
1. `AETHER_OWNER_BOOTSTRAP=<одноразовый токен>` в окружении сервера; первый
   `POST /api/v1/admin/bootstrap` с этим токеном назначает OWNER и токен гаснет;
2. `AETHER_OWNER_USER=<username>` — назначается при первом старте, если таблица
   ролей пуста;
3. если ни того ни другого — **первый зарегистрировавшийся** становится OWNER,
   и сервер пишет об этом в лог и в аудит. Для самохоста это нормальный
   сценарий («поднял, зарегистрировался, я владелец»), поэтому он допустим, но
   он же требует, чтобы `registration_mode` по умолчанию был не `OPEN`:
   **дефолт нового инстанса — `APPROVAL`**.

Дополнительные ограничения:
* последнего OWNER нельзя разжаловать и удалить (`409 last_owner`);
* ADMIN не может назначать/снимать OWNER и ADMIN — только MODERATOR/USER;
* никто не может повысить сам себя;
* все изменения ролей → `audit_log`.

### 6.2 Права доступа к данным (клиентская модель)

Это отдельная и независимая модель: она про то, **что клиент отдаёт серверу**,
а не про то, что пользователь может делать на сервере.

```swift
enum DataCategory: String, CaseIterable {
    // Аккаунт
    case profileName, avatar, bio, profileSettings
    // Контакты
    case contacts, blocked
    // Чаты
    case dialogList, messageHistory, pinnedMessages
    // Медиа
    case photos, videos, documents, voice
    // Настройки
    case uiSettings, notificationSettings, folders
    // Безопасность
    case archiveKeyWrap          // НЕ приватные ключи, см. 15.4
}

enum GrantState { case denied, granted }
enum Scope {
    case none
    case lastDays(Int)           // 7 / 30 / 365
    case all
    case selectedChats([String])
}
struct CategoryGrant {
    var state: GrantState = .denied      // ДЕФОЛТ — запрещено
    var scope: Scope = .none
    var grantedAt: Date?
    var revokedAt: Date?
    var lastExportedAt: Date?
}
```

Глобальная политика (Settings → Privacy & Data → Custom Servers):

```
○ Запрещена                              никаких переносов, кнопки заблокированы
● Разрешать только после подтверждения   ДЕФОЛТ: каждый перенос — явный экран
○ Разрешена для доверенных серверов      без экрана, НО только для категорий,
                                         уже разрешённых этому серверу раньше
```

Три инварианта, которые обязаны проверяться в коде и в тестах:

1. **Deny by default.** Новый сервер получает все категории в `.denied`. Любой
   код, создающий `ServerDataPolicy`, обязан использовать конструктор без
   аргументов, дающий пустые гранты.
2. **Trusted ≠ carte blanche.** Режим «доверенные» разрешает автоматический
   повтор только для `(категория, scope)`, где `grantedAt != nil` и не было
   `revokedAt`. Новая категория всегда требует экрана подтверждения.
3. **Подключение ≠ согласие.** Успешный логин на сервер не создаёт ни одного
   гранта. Единственный источник грантов — экран «Что перенести?».

---

## 7. Модель миграции данных

### 7.1 Поток

```
[1] Пользователь вошёл на пользовательский сервер (первый раз)
[2] Экран «Перенос данных»: «Не переносить» / «Настроить перенос»
        └─ «Не переносить» → политика остаётся пустой, экран не повторяется,
           точка входа остаётся в Настройках. Ничего не отправлено.
[3] Предупреждение о третьей стороне (первое включение) → Отмена / Продолжить
[4] Экран «Что перенести?» — категории и их scope
[5] Сводка: что уйдёт, сколько это байт, что сервер увидит открыто
[6] Клиент собирает пакет локально:
        выборка из локальной БД → сериализация → сжатие → шифрование AES-256-GCM
[7] POST /api/v1/data-import/session   (манифест + granted categories)
[8] POST /api/v1/data-import/chunk     (N раз, резюмируемо)
[9] POST /api/v1/data-import/complete  (манифест-хеш; сервер сверяет счётчики)
[10] Сервер применяет ОПЕРАЦИОННЫЕ категории (профиль/аватар), непрозрачные
     кладёт как есть в хранилище архива
[11] Клиент пишет ImportRecord в политику: что, когда, сколько, чем зашифровано
```

### 7.2 Два класса категорий

| Класс | Категории | Что видит сервер |
|---|---|---|
| **Операционные** | `profileName`, `avatar`, `bio`, `profileSettings` | **всё содержимое** — иначе он не сможет показать вас другим пользователям |
| **Непрозрачные** | `contacts`, `blocked`, `dialogList`, `messageHistory`, `pinnedMessages`, `photos`, `videos`, `documents`, `voice`, `uiSettings`, `notificationSettings`, `folders` | только шифротекст, размер, категорию и время загрузки |

Это различие показывается в UI **у каждой строки**, а не в сноске:

```
Аккаунт
[●] Имя профиля        сервер увидит открыто
[●] Аватар             сервер увидит открыто
[ ] Bio                сервер увидит открыто

Чаты
[ ] История сообщений  только шифротекст
```

Обещать E2EE там, где его нет, — хуже, чем не переносить вовсе.

### 7.3 Резюмируемость и идемпотентность

`import_chunks` с первичным ключом `(session_id, seq)`: повторная отправка чанка
после обрыва — `ON CONFLICT DO NOTHING`, ответ `200` с текущим прогрессом.
`GET /api/v1/data-import/session/{id}` возвращает `received_chunks` и битовую
карту недостающих `seq` — клиент досылает только их.

Сессия импорта живёт 24 часа, потом `expired` и чанки удаляются. Незавершённая
сессия никогда не «применяется» частично: операционные категории применяются
только на `complete`.

---

## 8. REST API

Все новые маршруты — под `/api/v1`. Существующие плоские маршруты остаются и
дублируются в `/api/v1` **тем же роутером** (`app.include_router(router)` +
`app.include_router(router, prefix="/api/v1")`), поэтому Android, веб и старые
сборки iOS продолжают работать без правок.

### 8.1 Обнаружение (без авторизации)

```
GET  /.well-known/aether            → 302/200, тот же документ, что ниже
GET  /api/v1/server/info?nonce=<b64>
```

```json
{
  "protocol": "aether",
  "protocol_version": 1,
  "server_id": "9db4e155-2c1a-4f0b-9a71-6f2e0b2a55c1",
  "name": "Roman Home",
  "api_url": "https://chat.example.com/api/v1",
  "websocket_url": "wss://chat.example.com/ws",
  "registration_mode": "approval",
  "supports_data_import": true,
  "supports_e2ee": true,
  "capabilities": ["e2ee", "ratchet", "groups", "channels", "calls",
                   "data_import", "invites", "multi_device"],
  "max_upload_bytes": 52428800,
  "software": {"name": "aether-server", "version": "0.3.0"},
  "public_key_b64": "<ed25519 pub, url-safe base64 без паддинга>",
  "signed_at": "2026-08-25T20:41:00Z",
  "nonce": "<эхо клиентского nonce>",
  "signature_b64": "<ed25519 подпись канонической строки>"
}
```

Каноническая строка подписи (строго этот порядок, `\n` между полями):

```
AETHER-SERVER-INFO-1
<server_id>
<name>
<api_url>
<websocket_url>
<registration_mode>
<protocol_version>
<signed_at>
<nonce>
```

Клиент обязан: проверить подпись публичным ключом из того же документа (это
даёт целостность, а доверие даёт TOFU-пин — раздел 16), сверить `nonce` с
отправленным (защита от replay), отвергнуть `signed_at`, ушедший от локального
времени больше чем на 5 минут (с оговоркой на кривые часы — тогда предупреждение,
а не отказ).

**И ещё одно, обязательное.** Сервер собирает `api_url` из переменной окружения
`AETHER_PUBLIC_URL`, а когда её нет — из заголовка `Host`. `Host` подконтролен
тому, кто делает запрос, поэтому клиент **обязан сверить, что хост в `api_url` и
`websocket_url` совпадает с origin, куда он сам постучался**. Не совпало —
относиться как к смене идентификатора сервера (раздел 16.2), а не молча идти по
названному адресу. Иначе подпись «заверяла» бы строку, которую подставил
посторонний. Владельцу самохоста за реверс-прокси правильнее задать
`AETHER_PUBLIC_URL` явно.

### 8.2 Аутентификация

```
POST   /api/v1/auth/register        { user_id, password, public_key_b64,
                                      encrypted_private_key_b64,
                                      encrypted_olm_account_b64, invite_code? }
POST   /api/v1/auth/login           { user_id, password, totp_code?, device_id? }
POST   /api/v1/auth/refresh         { refresh_token }        → новая пара, ротация
POST   /api/v1/auth/logout          Bearer                    → отзыв access+refresh
GET    /api/v1/users/me             Bearer → профиль + role + server_id
```

`login` → `{ access_token, expires_in, refresh_token, refresh_expires_in,
user_id, public_key_b64, encrypted_private_key_b64, encrypted_olm_account_b64,
role }`. Старый `/users/login` продолжает отдавать поле `token` — это тот же
access-токен, чтобы легаси-клиенты не заметили разницы.

Ошибки регистрации отражают режим сервера:
`403 registration_closed`, `403 invite_required`, `403 invite_invalid`,
`202 approval_required` (+ тело заявки — см. ниже), `409 username_taken`.

### 8.3 Заявки (заявитель)

```
POST   /api/v1/registration/request        { user_id, display_name, password,
                                             message?, public_key_b64,
                                             encrypted_private_key_b64,
                                             encrypted_olm_account_b64 }
       → 201 { request_id, code: "REQ-84A91", status: "pending",
               claim_token, expires_at }
GET    /api/v1/registration/request/{id}?claim_token=…
       → { status, code, created_at, decided_at?, reject_reason? }
DELETE /api/v1/registration/request/{id}?claim_token=…       → cancelled
```

### 8.4 Заявки (администратор)

```
GET    /api/v1/admin/registration/requests?status=pending&limit=50&cursor=…
POST   /api/v1/admin/registration/requests/{id}/approve   { role?: "USER" }
POST   /api/v1/admin/registration/requests/{id}/reject    { reason?: string }
GET    /api/v1/admin/registration/requests/count           → { pending: 3 }
```

### 8.5 Администрирование

```
GET    /api/v1/admin/overview          пользователи, заявки, сессии, диск
GET    /api/v1/admin/users?query=&cursor=
POST   /api/v1/admin/users/{id}/disable | /enable
DELETE /api/v1/admin/users/{id}
GET    /api/v1/admin/roles
PUT    /api/v1/admin/roles/{user_id}   { role }
GET    /api/v1/admin/invites
POST   /api/v1/admin/invites           { label?, expires_at?, max_uses? } → { code }
DELETE /api/v1/admin/invites/{code_id}
GET    /api/v1/admin/sessions?user_id=
DELETE /api/v1/admin/sessions/{session_ref}
GET    /api/v1/admin/audit?cursor=&action=
GET    /api/v1/admin/settings
PUT    /api/v1/admin/settings          { name?, registration_mode?,
                                         data_import_enabled?, import_quota_bytes? }
GET    /api/v1/admin/storage
```

### 8.6 Перенос данных

```
GET    /api/v1/data-import/capabilities
       → { enabled, categories: [...], max_chunk_bytes, quota_bytes,
           used_bytes, session_ttl_seconds }
POST   /api/v1/data-import/session     { manifest, categories, total_bytes,
                                         chunk_count, manifest_sha256,
                                         key_wrap: { … } }   → { session_id, expires_at }
GET    /api/v1/data-import/session/{id} → прогресс + список недостающих seq
POST   /api/v1/data-import/chunk        multipart: session_id, seq, category,
                                         nonce_b64, sha256, file=<шифротекст>
POST   /api/v1/data-import/complete    { session_id, manifest_sha256 }
DELETE /api/v1/data-import/session/{id} отмена, чанки удаляются
GET    /api/v1/data-export             выгрузка своих данных (право пользователя)
DELETE /api/v1/data                    { scope: "import" | "account" }
       → 202 + запись в data_erasure_requests
```

### 8.7 Коды ошибок

Единый конверт: `{"error": {"code": "...", "message": "...", "retry_after": ?}}`.
Коды, которые клиент разбирает по-особому: `registration_closed`,
`invite_required`, `approval_required`, `approval_pending`, `username_taken`,
`totp_required`, `totp_invalid`, `token_expired`, `token_reused`,
`insufficient_role`, `quota_exceeded`, `import_disabled`, `rate_limited`,
`server_identity_mismatch`.

---

## 9. WebSocket-события

Транспорт не меняется: тот же `/ws?token=`. Меняется только набор типов
сообщений, которые сервер шлёт клиенту. Все новые события — плоский JSON с
полем `type`, как и существующие; неизвестные типы клиенты обязаны молча
игнорировать (это уже правило проекта).

Заявителю:

```json
{"type":"registration.request.approved","request_id":"…","code":"REQ-84A91",
 "server_id":"…","server_name":"Roman Home","decided_at":"…"}
{"type":"registration.request.rejected","request_id":"…","reason":"…"}
```

Заявитель не авторизован, поэтому обычный `/ws?token=` ему недоступен. Отдельный
канал: `wss://host/ws/registration?request_id=…&claim_token=…` — сокет умеет
ровно одно, шлёт только события этой заявки и закрывается после терминального
статуса. Если сокет не поднялся — клиент опрашивает `GET …/request/{id}` с
backoff (30 с → 5 мин), это основной, а не запасной путь.

Администраторам (рассылается всем сессиям с ролью MODERATOR+):

```json
{"type":"registration.request.created","request_id":"…","code":"REQ-84A91",
 "user_id":"roman123","display_name":"Roman","message":"Привет, это я",
 "created_at":"…","pending_total":3}
{"type":"registration.request.decided","request_id":"…","status":"approved",
 "decided_by":"roman","pending_total":2}
```

`registration.request.decided` нужен, чтобы у второго администратора карточка
сама превратилась в «✓ Авторизован @roman», а не осталась активной.

Прочее:

```json
{"type":"server.notification","level":"info|warning","text":"…"}
{"type":"data_import.progress","session_id":"…","received":12,"total":40}
{"type":"data_import.completed","session_id":"…","status":"completed"}
{"type":"session.revoked","reason":"logout_all|admin|token_reuse"}
{"type":"server.settings.changed","registration_mode":"invite_only"}
```

Существующие `new_message`, `typing`, `presence`, `webrtc_*`, `group_call_*`
остаются как есть.

---

## 10. Административные permission checks

Серверная зависимость (`server/main.py`):

```python
ROLE_ORDER = {"USER": 0, "MODERATOR": 1, "ADMIN": 2, "OWNER": 3}

def role_of(user_id: str) -> str:
    # ЕДИНСТВЕННЫЙ источник правды — таблица server_roles.
    ...

def require_role(minimum: str):
    def dep(current_user: str = Depends(get_current_user)) -> str:
        if ROLE_ORDER[role_of(current_user)] < ROLE_ORDER[minimum]:
            audit("access.denied", actor=current_user, target=minimum)
            raise HTTPException(403, "insufficient_role")
        return current_user
    return dep
```

| Маршрут | Минимальная роль | Дополнительные проверки |
|---|---|---|
| `GET /admin/overview` | MODERATOR | — |
| `GET /admin/registration/requests` | MODERATOR | — |
| `POST …/{id}/approve` | MODERATOR | заявка `pending`; логин свободен; транзакция; аудит |
| `POST …/{id}/reject` | MODERATOR | заявка `pending`; аудит |
| `GET /admin/users` | ADMIN | — |
| `POST /admin/users/{id}/disable` | ADMIN | цель не OWNER; цель ≠ сам |
| `DELETE /admin/users/{id}` | OWNER | цель не последний OWNER; аудит; отзыв сессий |
| `PUT /admin/roles/{id}` | ADMIN для → MODERATOR/USER; OWNER для → ADMIN/OWNER | нельзя повысить себя; нельзя снять последнего OWNER |
| `GET/POST/DELETE /admin/invites` | ADMIN | — |
| `GET /admin/sessions`, `DELETE …` | ADMIN | сессии OWNER — только OWNER |
| `GET /admin/audit` | ADMIN | только чтение, аудит неизменяем |
| `PUT /admin/settings` | OWNER | смена `registration_mode` → `server.settings.changed` по WS |
| `GET /admin/storage` | ADMIN | — |

Правила, действующие поверх таблицы:

* **роль никогда не приходит от клиента.** Ни в теле, ни в заголовке. Поле
  `role` в ответе `/users/me` — исключительно для того, чтобы UI знал, какие
  разделы рисовать;
* каждое административное действие пишет `audit_log` (actor, action, target,
  ip_hash) **в той же транзакции**, что и само действие: не записалось в
  аудит — не произошло;
* `403` не раскрывает существования ресурса: список чужих заявок и список
  пользователей для USER — одинаковый `insufficient_role`;
* rate limit на административные ручки тоже есть (защита от перебора id).

---

## 11. UX-поток: официальный сервер

Экран входа. Две вкладки сверху — это выбор **инфраструктуры**, а не режим
приложения:

```
              AETHER

   ┌──────────────┬───────────────────┐
   │ Наши серверы │  Пользовательские │
   └──────────────┴───────────────────┘

   Логин
   [________________________]

   Пароль
   [________________________]

   [        Войти        ]

   Вход ●────────○ Регистрация

   Войти по QR с другого устройства
```

* Переключатель «Вход / Регистрация» — снизу, как в ТЗ; текущий segmented
  Picker из `WelcomeView` переезжает вниз и превращается в этот переключатель.
* Вкладка «Наши серверы» ничего не «обнаруживает»: адрес официального сервера
  зашит (`Secrets.host`), карточка сервера не показывается, поток ровно такой
  же, как сегодня. Ни один существующий пользователь не должен заметить, что в
  приложении вообще появились серверы.
* 2FA-поле появляется по `totp_required`, как сейчас.
* Вход по QR остаётся здесь же и работает в рамках выбранной вкладки.

---

## 12. UX-поток: пользовательский сервер

### 12.1 Добавление

```
Пользовательские
──────────────────────────────────
Адрес сервера
[ 192.168.1.25                  ]

  Можно ввести IP, домен или ссылку
  aether://

[        Найти сервер         ]

──────────────────────────────────
Ваши серверы
  Roman Home   192.168.1.25    ●
  Dev          dev.example.com ○
```

Нормализация ввода (реализуется в ядре, чтобы все клиенты вели себя одинаково —
`core/src/discovery.rs`, функция `normalize_server_input`):

| Ввод | Кандидаты, по порядку |
|---|---|
| `chat.example.com` | `https://chat.example.com` |
| `https://chat.example.com` | как есть |
| `aether://chat.example.com` | `https://chat.example.com` |
| `192.168.1.25:8443` | `https://192.168.1.25:8443` |
| `192.168.1.25` | `https://192.168.1.25` → при отказе предложить локальный режим |

Для каждого кандидата: `GET /.well-known/aether`, при `404` — `GET
/api/v1/server/info`, при `404` — `GET /server/info` (совместимость со старыми
инстансами, которые ещё не обновились: они ответят `404` на всё, и мы честно
скажем «это не Aether-сервер»).

### 12.2 Карточка найденного сервера

```
        Roman Home
      Сервер найден

  Aether Server · Protocol v1
  chat.example.com

  Регистрация
  Требуется подтверждение администратора

  Шифрование
  Поддерживается

  Отпечаток сервера
  4f2a 91c7 ee03 b1d5 …            (тап — полностью)

  Управляется третьей стороной

  [        Продолжить        ]
```

### 12.3 Вход и регистрация на нём

```
        Roman Home
  Пользовательский сервер

  Логин     [______________]
  Пароль    [______________]

  [        Войти        ]

  [   Создать аккаунт   ]     ← вид зависит от registration_mode
```

| Режим | Кнопка | Что происходит |
|---|---|---|
| `OPEN` | «Создать аккаунт» | обычная регистрация, сразу вход |
| `APPROVAL` | «Подать заявку» | форма заявки → экран ожидания |
| `INVITE_ONLY` | «У меня есть код» | поле invite-кода → обычная регистрация |
| `CLOSED` | скрыта | подпись «Регистрация на этом сервере отключена» |

Форма заявки и экран ожидания:

```
  Username        [__________]        Заявка отправлена
  Display name    [__________]
  Password        [__________]        Ожидается подтверждение
                                      администратора сервера.
  Сообщение администратору
  [________________________]          Request ID
                                      REQ-84A91
  [   Отправить заявку   ]
                                      [ Проверить статус ]
                                      [ Отменить заявку  ]
```

Экран ожидания не блокирует приложение: пользователь возвращается к своим
чатам на других серверах, а одобрение приходит уведомлением.

```
  Доступ разрешён

  Администратор Roman Home
  одобрил вашу регистрацию.

  [   Войти на сервер   ]
```

### 12.4 Переключатель пространств

В шапке списка чатов, вместо статичного заголовка «Чаты»:

```
Aether Cloud ▼
```

Тап открывает лист (`.sheet` с `.presentationDetents([.medium])`), а не
боковую панель:

```
  Aether Cloud                    ✓
  Официальный · @roman
  ─────────────────────────────────
  Ваши серверы

  Roman Home
  192.168.1.25 · @roman123          ●
  Dev
  dev.example.com · не в сети       ○
  Friends
  chat.example.org · @roman         ●
  ─────────────────────────────────
  +  Добавить сервер
  ⚙  Управление серверами
```

Никакой вертикальной колонки иконок слева. Список чатов, вкладки, композер —
всё остаётся ровно там, где было. Меняется только содержимое.

---

## 13. UX-поток администратора

### 13.1 Заявки прямо в списке чатов

Когда у активного Space есть необработанные заявки и роль ≥ MODERATOR, над
закреплёнными чатами появляется системная строка:

```
Чаты
┌──────────────────────────────────┐
│ ⌾  Запросы сервера            3  │
│    Roman Home · новые заявки     │
└──────────────────────────────────┘
Alice          Привет!
Development    Новый build готов
Family         Фото
```

Технически это **виртуальный чат**, а не запись в БД ядра:

* `peerId = "system:server-requests"` — двоеточие невозможно в username
  (`^[A-Za-z0-9_]+$`), поэтому коллизия с реальным чатом исключена;
* строка вставляется в `ChatsListView` перед закреплёнными, схема сортировки,
  свайпы, папки и архив её не касаются;
* исчезает сама, когда `pending == 0`;
* не участвует в поиске и не попадает в счётчик непрочитанных сообщений
  (у неё свой бейдж с числом заявок).

### 13.2 Экран заявок

Выглядит как обычный чат Aether (тот же фон, те же обои, та же шапка), но
сообщения — системные карточки:

```
                 Сегодня

  ┌──────────────────────────────────┐
  │ Новый запрос на регистрацию      │
  │                                  │
  │ Username     roman123            │
  │ Имя          Roman               │
  │ Сообщение    «Привет, это я»     │
  │ Request      REQ-84A91           │
  │ Создан       22:41               │
  │                                  │
  │ [ Авторизовать ]  [ Отклонить ]  │
  └──────────────────────────────────┘

  ┌──────────────────────────────────┐
  │ anna_dev · Anna                  │
  │ «Работаю с Иваном»               │
  │ [ Авторизовать ]  [ Отклонить ]  │
  └──────────────────────────────────┘
```

После решения карточка **остаётся в истории** и превращается в строку статуса:

```
  ✓ Авторизован вами · 22:47
  ✕ Отклонён вами · 22:48        (+ причина, если указана)
```

Кнопка «Авторизовать» работает прямо здесь: оптимистично гасит кнопки, шлёт
`POST …/approve`, при ошибке возвращает их и показывает причину. Второй
администратор видит превращение карточки через `registration.request.decided`.

«Отклонить» открывает маленький лист с необязательной причиной — она уходит
заявителю, поэтому в подсказке написано, что текст увидит человек.

### 13.3 Полноценная панель

Settings → «Управление сервером» (пункт виден только при роли ≥ MODERATOR):

```
  Server Administration
  Roman Home · chat.example.com

  Overview      пользователи 12 · заявки 3 · сессии 18 · диск 4.2 ГБ
  Users
  Requests
  Invites
  Roles
  Sessions
  Security
  Storage
  Logs
  Settings
```

Панель нужна для всего, что не сводится к «впустить человека»: роли, инвайты,
режим регистрации, аудит. Обычное одобрение через неё делать не требуется —
именно в этом смысл раздела 13.1.

---

## 14. UX-поток переноса данных

### 14.1 Первый вход на пользовательский сервер

Показывается **один раз**, сразу после первого успешного входа, до того как
пользователь увидит пустой список чатов:

```
        Перенос данных

  Вы подключились к пользовательскому серверу

        Roman Home
      chat.example.com

  Хотите разрешить перенос зашифрованных
  данных Aether на этот сервер?

  По умолчанию ничего не переносится.

  [  Не переносить  ]   [ Настроить перенос ]
```

«Не переносить» — не «отложить»: политика остаётся пустой, экран больше не
всплывает, точка входа живёт в Настройках.

### 14.2 Предупреждение о третьей стороне (первое включение)

```
  Этот сервер управляется третьей стороной.

  Aether не контролирует его администратора,
  хранилище, резервные копии или журналы.

  Передаваться будут только выбранные вами данные.

  Данные шифруются на устройстве до отправки.
  Имя профиля и аватар — исключение: сервер обязан
  показывать их другим пользователям, поэтому
  видит их открыто.

  [ Отмена ]        [ Продолжить ]
```

### 14.3 Выбор категорий

```
  Что перенести?

  Аккаунт
  [●] Имя профиля            сервер увидит открыто
  [●] Аватар                 сервер увидит открыто
  [ ] Bio                    сервер увидит открыто
  [ ] Настройки профиля      шифротекст

  Контакты
  [ ] Контакты               шифротекст
  [ ] Заблокированные        шифротекст

  Чаты
  [ ] Список диалогов        шифротекст
  [ ] История сообщений  ›   шифротекст
  [ ] Закреплённые           шифротекст

  Медиа
  [ ] Фотографии         ›
  [ ] Видео              ›
  [ ] Документы          ›
  [ ] Голосовые          ›

  Настройки
  [ ] Настройки интерфейса
  [ ] Настройки уведомлений
  [ ] Папки / категории

  Безопасность
  [ ] Ключ доступа к архиву  ›   что это такое

                    [ Далее ]
```

Строки со стрелкой открывают выбор объёма:

```
  История сообщений          Медиа
  ○ Не переносить            ○ Не переносить
  ○ Последние 7 дней         ○ Только последние 30 дней
  ○ Последние 30 дней        ○ Только выбранные чаты
  ○ Последний год            ○ Всё
  ○ Вся история
  ○ Выбрать чаты вручную
```

### 14.4 Сводка перед отправкой

Обязательный экран — последний момент, когда можно отказаться:

```
  Проверьте перед отправкой

  Roman Home · chat.example.com
  Управляется третьей стороной

  Открыто увидит сервер
    Имя профиля, аватар

  Получит только шифротекст
    История сообщений (30 дней) · 1 240 сообщений
    Фотографии (30 дней) · 86 файлов · 214 МБ

  Не будет передано
    Приватные ключи устройства
    Мастер-ключ аккаунта
    Ключи сессий Double Ratchet

  Всего к отправке: 231 МБ

  [ Назад ]              [ Перенести ]
```

### 14.5 Прогресс и разрешения

Прогресс — обычная строка в шапке чатов («Перенос данных · 43 %»), не модальное
окно: перенос 200 МБ по мобильной сети не должен блокировать мессенджер.
Возобновляется после обрыва автоматически.

Экран `Settings → Servers → Roman Home → Data access`:

```
  Roman Home

  Разрешено
  ✓ Имя профиля
  ✓ Аватар
  ✓ Настройки интерфейса

  Запрещено
  ✕ Контакты
  ✕ История сообщений
  ✕ Медиа
  ✕ Ключи

  Последний перенос: 25 августа, 214 МБ

  [ Изменить разрешения ]
  [ Отозвать доступ ]
  [ Удалить локальные данные сервера ]
  [ Запросить удаление данных с сервера ]
```

Отзыв доступа (§14 ТЗ) делает ровно три вещи и говорит правду о четвёртой:

1. останавливает будущую синхронизацию (грант → `.denied`, `revokedAt`);
2. удаляет локально сохранённое разрешение;
3. предлагает отправить запрос на удаление ранее загруженного;
4. показывает честную формулировку:

```
  Aether может отправить серверу запрос на удаление данных.

  Если сервер принадлежит третьей стороне, Aether не может
  гарантировать удаление его резервных копий или внешних копий.
```

Никаких «данные удалены навсегда». Ответ сервера показывается как есть:
«Запрос принят 25.08, статус: выполнен/не подтверждён».

---

## 15. Безопасная схема шифрования мигрируемых данных

### 15.1 Что не отправляется никогда

Жёсткий список. Ни один флаг, ни одна галочка, ни один режим «доверенного
сервера» не открывает его:

* приватный ключ идентичности устройства (`identity_private_key`);
* мастер-ключ аккаунта (cross-signing);
* pickle-состояния Olm-сессий и их секреты;
* приватная часть prekey/fallback-ключей;
* ключ SQLCipher локальной базы;
* access/refresh-токены **других** серверов;
* PIN и биометрические данные.

Проверяется не только глазами: сериализатор экспорта работает по белому списку
полей, а не «весь объект минус исключения». Всё, чего нет в белом списке, в
пакет не попадает физически. На это пишется тест в `core/`, который падает при
появлении неизвестного поля в экспортируемой структуре.

### 15.2 Конверт `AETHER-IMPORT-1`

Цепочка ровно такая, как требует §10 ТЗ:

```
Device → decrypt locally → re-encrypt locally → encrypted upload → Custom Server
```

```
IK  = 32 случайных байта                      ключ импорт-сессии, генерируется на устройстве
CK_c = HKDF-SHA256(IK, salt = session_id, info = "AETHER-IMPORT-1|" + category)
                                              ключ на КАТЕГОРИЮ: компрометация одного
                                              архива не вскрывает остальные

Чанк:
  plaintext = zstd(  serialize(category, [записи…])  )
  nonce     = 12 случайных байт (уникален в пределах CK_c)
  AAD       = "AETHER-IMPORT-1" ‖ server_id ‖ account_id ‖ session_id ‖ category ‖ seq ‖ total
  ct        = AES-256-GCM(CK_c, nonce, plaintext, AAD)
  на сервер уходит: { session_id, seq, category, nonce_b64, sha256(ct), ct }
```

AAD связывает чанк с конкретным сервером, аккаунтом, сессией и позицией.
Пересадить чанк в другую сессию или на другой сервер невозможно: GCM-тег не
сойдётся. Это и есть защита от replay на уровне данных.

### 15.3 Где живёт `IK`

Серверу `IK` не передаётся ни в каком виде. Он сохраняется двумя обёртками:

```
wrap_pass = AES-256-GCM( PBKDF2-HMAC-SHA256(passphrase, salt16, 200_000), IK )
            ↑ парольная фраза переноса; хранится ТОЛЬКО у пользователя,
              показывается как код восстановления при создании

wrap_self = box_encrypt( IK, эфемерная пара → публичный ключ аккаунта
                         на ЦЕЛЕВОМ сервере )
            ↑ тот же конверт, что уже используется для групповых ключей:
              { sender_pubkey_b64, nonce_b64, ciphertext_b64 } (WIRE_PROTOCOL.md).
              Отправитель — одноразовая пара, создаваемая и выбрасываемая на
              устройстве, поэтому конверт не аутентифицирует ничего лишнего.
              Распечатать может только владелец приватного ключа аккаунта;
              сервер держит лишь encrypted_private_key_b64 (PBKDF2 от пароля),
              то есть уровень доверия ровно тот же, что у существующих
              резервных копий истории (history_backups) — не хуже
```

Обе обёртки уходят в `key_wrap` при создании сессии. Обе бесполезны серверу без
пароля пользователя. Восстановление на новом устройстве: вход в аккаунт →
приватный ключ расшифрован паролем → `wrap_self` открыт → архив читается.

### 15.4 Категория «Ключ доступа к архиву»

Именно это, и ничего больше, скрывается за строкой «Безопасность» в списке
категорий. В UI она называется «Ключ доступа к архиву», а не «Переносимые ключи
шифрования», и под ней написано:

```
  Сохранить на сервере обёрнутый ключ, которым зашифрован ваш архив,
  чтобы открыть его после переустановки приложения.

  Ключ обёрнут вашим паролем. Сервер открыть его не может.
  Приватные ключи устройства при этом не передаются.
```

Выключенная галочка означает: архив на сервере лежит, но без парольной фразы
переноса его не открыть даже вам. Это честный компромисс, и он проговорён.

### 15.5 Операционные категории

`profileName`, `avatar`, `bio`, `profileSettings` уходят открыто обычными
существующими маршрутами (`PUT /users/me/profile`, `POST /avatars`), а не через
импорт-сессию. Смешивать их с шифрованным потоком нельзя: это создало бы
ложное впечатление, что они тоже защищены.

### 15.6 Ограничения размера и квоты

* чанк ≤ 4 МБ (`max_chunk_bytes` в capabilities, сервер проверяет);
* сессия ≤ `import_quota_bytes` (по умолчанию 2 ГБ на аккаунт);
* число чанков ≤ 20 000;
* превышение → `413` / `quota_exceeded`, клиент показывает, что урезать.

---

## 16. Защита от подмены пользовательского сервера

### 16.1 Что запоминается при первом подключении

```
server_id                UUID инстанса
origin                   https://chat.example.com:8443
ed25519_fingerprint      SHA-256 публичного ключа сервера, base64url
first_seen_at
tls_spki_sha256          только для локального режима с самоподписанным сертификатом
```

Пин привязан к **origin**, а сверяется по `server_id` + отпечатку ключа. Это
позволяет корректно обработать все три ситуации:

| Что случилось | Признак | Поведение |
|---|---|---|
| Сервер переехал на новый домен | тот же `server_id`, тот же ключ, другой origin | молча обновляем origin, пишем в историю |
| Сервер переустановлен владельцем | другой `server_id` и/или другой ключ | **экран предупреждения**, вход заблокирован |
| Подмена / MITM | то же | тот же экран |

Отличить переустановку от атаки клиент не может — и не должен делать вид, что
может. Решение принимает человек, но приняв его осознанно.

### 16.2 Экран предупреждения

```
              Внимание

  Идентификатор сервера изменился.

  Это может означать переустановку сервера,
  смену владельца или попытку подмены.

  chat.example.com

  Старый отпечаток
  4f2a 91c7 ee03 b1d5 · с 12 июля

  Новый отпечаток
  a10e 77bd 2c94 f0a3 · сейчас

  Данные, которые вы уже разрешили передавать,
  до подтверждения передаваться не будут.

  [ Отмена ]  [ Проверить ]  [ Доверять новому серверу ]
```

* «Проверить» показывает полные отпечатки крупно и QR — чтобы сверить с
  владельцем сервера по другому каналу;
* «Доверять новому серверу» требует второго подтверждения и **сбрасывает все
  гранты данных этого сервера в `.denied`**: новый ключ — новая сторона, старое
  согласие к ней не относится;
* пока решение не принято, сервер в переключателе помечен красным, WebSocket не
  поднимается, импорт заблокирован;
* смена пина никогда не происходит молча — все изменения остаются в
  `ServerPin.history` и видны в «О сервере».

### 16.3 Проверка на каждом подключении, а не только при добавлении

`/server/info` с новым `nonce` дёргается при каждом холодном старте Space и не
реже раза в сутки. Дешёвая проверка, ловит подмену не только в момент
добавления.

### 16.4 TLS и локальные серверы

**Проверка TLS не отключается автоматически ни при каких условиях.** Никакого
«разрешить недоверенный сертификат» одной кнопкой в общем потоке.

Три транспорта, явно выбираемые пользователем:

| Транспорт | Когда | Что показывается |
|---|---|---|
| `.tls` (по умолчанию) | всё, что доступно по валидному HTTPS | ничего особенного |
| `.lanCleartext` | только литеральный адрес из приватных диапазонов (10/8, 172.16/12, 192.168/16, 169.254/16, `.local`) | **красное** «Соединение с этим сервером не шифруется. Содержимое сообщений защищено E2EE, но адреса, размеры и время видны в вашей сети» |
| `.lanPinnedCert` | самоподписанный сертификат | показать SPKI-отпечаток, TOFU-пин, при смене — тот же экран, что 16.2 |

`.lanCleartext` требует `NSAllowsLocalNetworking` в `Info.plist` — это ровно тот
ключ ATS, который Apple даёт для локальной сети, и он не ослабляет защиту для
публичных адресов. Проверку «адрес действительно приватный» делает клиент, а не
пользователь.

`.lanPinnedCert` требует кастомного верификатора в `ureq`/`rustls` внутри ядра —
это отдельный этап (20, этап 9); до него самоподписанные серверы предлагается
поднимать за Caddy/`nip.io`, как это уже сделано для официального.

### 16.5 Что защита не покрывает

Честно: TOFU не спасает, если подмена произошла **в момент первого
подключения**. Единственная защита от этого — сверка отпечатка с владельцем
сервера по другому каналу, и клиент прямо предлагает это сделать в карточке
сервера (кнопка «Сверить отпечаток» + QR).

---

## 17. Offline, ошибки, повторы

### 17.1 Состояния подключения к Space

```
online ⇄ degraded (HTTP работает, WS падает) ⇄ offline
                              │
                        unauthorized (401/403) → refreshing → revoked
```

* `offline` — не ошибка и не экран. Чаты читаются из локальной SQLCipher-базы,
  композер работает, исходящие копятся со статусом `0 sending` (механика уже
  есть). В шапке — «Roman Home · нет сети»;
* `degraded` — WebSocket не поднялся: работает существующий поллинг инбокса,
  реалтайм-события деградируют до опроса. Пользователю показывается «нет
  мгновенных уведомлений», приложение не ломается;
* **сбой одного Space не влияет на остальные**. Упавший самохост не должен
  подвешивать официальный аккаунт: каждый Space держит свои таймеры, свой
  backoff и свою очередь.

### 17.2 Повторы

| Операция | Стратегия |
|---|---|
| Discovery | 3 попытки, 1 с → 3 с → 9 с, потом ручная кнопка «Повторить» |
| Login/register | без автоповтора (это ввод пароля), только явная кнопка |
| Опрос статуса заявки | 30 с первые 10 минут, дальше 5 мин, при `offline` — пауза |
| WebSocket | экспоненциальный backoff 1→2→4→8→30 с с джиттером ±20 %, сброс при успехе |
| Refresh токена | одна попытка на 401; повторный 401 → `revoked` |
| Чанк импорта | 5 попыток с backoff; при `409 already_received` — считать успехом |
| Approve/reject заявки | без автоповтора: повтор мог бы одобрить дважды. Кнопки возвращаются, показывается ошибка |
| Запрос удаления данных | 3 попытки, потом «не доставлено, попробовать позже» |

### 17.3 Классификация ошибок для пользователя

Пять человеческих формулировок, за которыми стоит вся техника:

| Класс | Текст | Действие |
|---|---|---|
| Сеть | «Нет соединения с Roman Home» | Повторить |
| Не Aether | «По этому адресу нет сервера Aether» | Проверить адрес |
| Сертификат | «Не удалось проверить сертификат сервера» | Подробности, без кнопки «всё равно продолжить» |
| Подмена | «Идентификатор сервера изменился» | Экран 16.2 |
| Правила сервера | «Регистрация на этом сервере отключена» / «Нужен код приглашения» | по ситуации |

Отдельно: **никогда не показывать сырой текст ошибки сервера как есть**.
Пользовательский сервер — недоверенная сторона, его `detail` может содержать
что угодно, включая попытку выдать себя за интерфейс Aether. Сообщение сервера
показывается в отдельном блоке с подписью «сообщение сервера», визуально
отличном от системного текста.

### 17.4 Частичные состояния

* заявка подана, приложение снесли → `request_id` + `claim_token` лежат в
  Keychain (`srv.<id>.pending_request`), восстанавливаются при переустановке;
  потеряли — есть код `REQ-…` и кнопка «У меня есть Request ID»;
* импорт прерван → сессия жива 24 часа, при следующем запуске приложение
  предлагает «Продолжить перенос», а не начинает заново;
* сервер удалён из приложения при живой импорт-сессии → сессия отменяется
  запросом `DELETE`, при неуспехе — просто истечёт.

---

## 18. Миграции существующей БД

### 18.1 Сервер

Стиль существующего `init_db()` сохраняется: всё идемпотентно, всё через
`CREATE TABLE IF NOT EXISTS` и проверки `information_schema`. Никаких `DROP`,
никаких изменений типов.

```
[S1] server_meta: сгенерировать aether.server_id (uuid4), ed25519-пару,
     проставить registration_mode. Дефолты:
       - существующий инстанс (в users уже есть записи) → 'OPEN'
         (менять поведение работающего сервера молча нельзя)
       - пустой инстанс → 'APPROVAL' (безопасный дефолт для нового самохоста)
[S2] server_roles: создать. Если таблица пуста и users непуста —
     назначить OWNER по AETHER_OWNER_USER, иначе оставить пустой и
     писать в лог «владелец не назначен, см. /api/v1/admin/bootstrap»
[S3] registration_requests, invites, audit_log, refresh_tokens,
     import_sessions, import_chunks, data_erasure_requests: создать
[S4] users.approved_by, sessions.family_id: добавить, если нет
[S5] uploads/import/: создать каталог, права 700
```

Откат: новые таблицы можно удалить, старый код их не знает. Единственное
необратимое — `server_id`; он должен генерироваться **один раз** и никогда не
перегенерироваться, иначе все клиенты увидят подмену. Ключ пишется с
`ON CONFLICT DO NOTHING`.

### 18.2 Клиент (iOS)

```
[C1] Первый запуск новой версии, servers.json отсутствует:
       - создать запись Aether Cloud: kind=.official, origin=Secrets.baseURL,
         server_id — из GET /server/info; если сервер ещё не обновлён и
         отдаёт 404, использовать временный id "official-legacy" и заменить
         его на настоящий при первом успешном запросе
[C2] Перенести legacy-Keychain:
       session_token / session_user_id / identity_public_key / identity_private_key
         → srv.<official>.acct.<user>.access / .pub / .priv
       acct_<id>_token / _pub / _priv  → srv.<official>.acct.<id>.*
       после успешного переноса legacy-ключи УДАЛИТЬ
[C3] savedAccounts (UserDefaults) → ServerRecord.accounts официального сервера
[C4] Локальные базы не трогать. В meta каждой дописать server_id.
       aether.sqlite остаётся именем файла активного аккаунта официального
       сервера — переименование = риск потерять историю, выгоды ноль
[C5] db_encryption_key → srv.<official>.dbkey (значение то же самое!),
       иначе существующие базы перестанут открываться
[C6] Флаг миграции в UserDefaults: aether.multiServerMigrated = 2
```

Критично: **`db_encryption_key` не пересоздаётся**. Инцидент 10.07 (описан в
`CoreClient.openStoreRecovering`) — ровно про это. Миграция копирует значение
под новое имя и оставляет старое на месте ещё одну версию, на случай отката.

Проверка миграции: сборка на живом iPhone поверх существующей установки с
реальной перепиской, до и после — список чатов и последние сообщения должны
совпадать. Это обязательный пункт приёмки этапа 3.

---

## 19. Файлы проекта, которые нужно изменить

### Сервер (`server/`)

| Файл | Что делаем |
|---|---|
| `main.py` | **тела существующих функций не трогаем.** В конец файла — 15-строчное зеркалирование маршрутов в `/api/v1` (проход по `app.router.routes` + `add_api_route`); в `init_db()` — таблицы из 2.1; `require_role`, `role_of`, `audit()`; проверка `registration_mode` в `/users/register`; выдача refresh-токена в `/users/login` |
| `server/server_identity.py` *(новый)* | генерация/чтение `server_id` и ed25519, подпись `/server/info` |
| `server/routes_server_info.py` *(новый)* | `/.well-known/aether`, `/api/v1/server/info` |
| `server/routes_registration.py` *(новый)* | заявки: подача, статус, отмена |
| `server/routes_admin.py` *(новый)* | заявки для админа, пользователи, роли, инвайты, сессии, аудит, настройки, storage |
| `server/routes_data_import.py` *(новый)* | capabilities, session, chunk, complete, export, erasure |
| `server/ws_events.py` *(новый)* | адресная рассылка админам, канал `/ws/registration` |
| `scripts/deploy_server.sh` | добавить новые файлы в выкладку, прогон миграций |
| `docs/DEPLOY_P7_P8.md` | раздел про `AETHER_OWNER_*`, `registration_mode`, бутстрап |

`main.py` — самое рискованное место (2755 строк, один файл, общий сразу для
iOS, Android и веба). Поэтому **разбиения на роутеры не делаем**: старые
маршруты остаются там, где стоят, и получают алиасы под `/api/v1`
программно — проходом по `app.router.routes` после объявления всех
эндпоинтов:

```python
# Один и тот же обработчик доступен и по легаси-пути, и по /api/v1/<path>.
# Старые клиенты (Android, веб, установленные сборки iOS) не замечают ничего.
from fastapi.routing import APIRoute, APIWebSocketRoute

def _mirror_api_v1() -> None:
    for r in list(app.router.routes):
        if isinstance(r, APIRoute) and not r.path.startswith("/api/v1"):
            app.add_api_route("/api/v1" + r.path, r.endpoint,
                              methods=list(r.methods), name=r.name + "_v1",
                              response_model=r.response_model,
                              include_in_schema=False)
        elif isinstance(r, APIWebSocketRoute) and not r.path.startswith("/api/v1"):
            app.add_api_websocket_route("/api/v1" + r.path, r.endpoint,
                                        name=(r.name or "ws") + "_v1")

_mirror_api_v1()   # ВЫЗЫВАТЬ ПОСЛЕ всех @app.* и до include_router новых модулей
```

Новые возможности объявляются обычными `APIRouter` в отдельных файлах и
монтируются только под `/api/v1` — в легаси-пространстве их нет.
Проверка этапа: `server/test_prekeys.py`, `server/test_logout.py` и
кросс-примеры из `core/examples/` проходят без правок.

### Ядро (`core/`)

| Файл | Что делаем |
|---|---|
| `core/src/discovery.rs` *(новый)* | `normalize_server_input`, `fetch_server_info`, проверка подписи, `ServerInfo` как uniffi-Record |
| `core/src/api.rs` | `refresh()`, `server_info()`, `registration_request*()`, `admin_*()`, `data_import_*()`; `api_prefix` в конструкторе (сервер сам говорит `api_url`) |
| `core/src/import.rs` *(новый)* | сборка экспорта по белому списку, `AETHER-IMPORT-1`, чанкование, HKDF |
| `core/src/crypto.rs` | добавить `hkdf_sha256` и обёртки `wrap_pass`/`wrap_self`; PBKDF2 (`encrypt_private_key`), AES-GCM (`aes_encrypt`) и `box_encrypt` уже есть, sealed-box нет — используем существующий конверт групповых ключей |
| `core/Cargo.toml` | `hkdf`, `zstd`; позже `rustls` для пиннинга |
| `core/examples/server_info.rs`, `register_request.rs`, `approve_request.rs`, `import_data.rs` *(новые)* | кросс-тесты без UI — обязательная методика проекта |
| `WIRE_PROTOCOL.md` | раздел «Server info, подпись, импорт-конверт» |

Существующие модули (`protocol.rs`, `store.rs`, `ratchet.rs`, `ws.rs`) —
**без изменений**. Схема локальной SQLite не меняется.

### iOS (`ios/AETHER/`)

| Файл | Что делаем |
|---|---|
| `Core/ServerRegistry.swift` *(новый)* | `ServerRecord`, `ServerPin`, `AccountRef`, чтение/запись `servers.json`, миграция C1–C6 |
| `Core/ServerDirectory.swift` *(новый)* | обнаружение, нормализация, TOFU-сверка, добавление/удаление сервера |
| `Core/ServerSession.swift` *(новый)* | `SpaceState` (раздел 4), refresh, backoff, offline |
| `Core/DataPermissions.swift` *(новый)* | `DataCategory`, `CategoryGrant`, глобальная политика, ревокация |
| `Core/DataImportEngine.swift` *(новый)* | сбор, шифрование через ядро, чанки, резюм, прогресс |
| `Core/ServerAdminClient.swift` *(новый)* | заявки и админ-ручки |
| `Core/Session.swift` | ключ аккаунта → `(serverId, userId)`; `activeServer`; `switchSpace`; Keychain по новой схеме |
| `Core/CoreClient.swift` | `static let baseURL` → инстансный `baseURL`; `bind(server:account:)` с пересозданием `ApiClient` и выбором БД |
| `Core/Keychain.swift` | пространство имён `srv.<id>.acct.<user>.*`, миграция legacy |
| `Core/Messaging.swift` | перезапуск движка при смене Space; WS-URL из активного сервера; обработка новых событий |
| `Core/GlobalSearch.swift`, `Core/PushRegistrar.swift`, `Components/Avatar*.swift`, `Features/Chats/ChatsListView.swift`, `Features/Chats/ContactsView.swift`, `Features/Groups/GroupProfileView.swift` | заменить `CoreClient.baseURL` на адрес активного Space (механическая правка ~20 мест) |
| `Features/Onboarding/WelcomeView.swift` | две вкладки «Наши серверы / Пользовательские», переключатель Вход/Регистрация снизу |
| `Features/Onboarding/AddServerView.swift` *(новый)* | ввод адреса, карточка найденного сервера, отпечаток |
| `Features/Onboarding/ServerAuthView.swift` *(новый)* | вход/регистрация на пользовательском сервере по его политике |
| `Features/Onboarding/RegistrationRequestView.swift` *(новый)* | форма заявки, экран ожидания, статус |
| `Features/Servers/SpaceSwitcher.swift` *(новый)* | лист переключения пространств |
| `Features/Servers/ServersListView.swift` *(новый)* | «Ваши серверы», свайпы, контекстное меню |
| `Features/Servers/ServerDetailView.swift` *(новый)* | о сервере, отпечаток, транспорт, удаление |
| `Features/Servers/ServerTrustAlertView.swift` *(новый)* | экран смены идентификатора (16.2) |
| `Features/Admin/ServerRequestsChatView.swift` *(новый)* | системный чат заявок |
| `Features/Admin/ServerAdminView.swift` *(новый)* | панель администратора |
| `Features/DataTransfer/DataTransferIntroView.swift`, `DataCategoriesView.swift`, `DataTransferSummaryView.swift`, `DataAccessView.swift` *(новые)* | поток переноса (14.1–14.5) |
| `Features/Settings/SettingsView.swift` | секция «Серверы», «Privacy & Data → Custom Servers», пункт «Управление сервером» по роли |
| `Features/Chats/ChatsListView.swift` | заголовок → `SpaceSwitcher`, виртуальная строка «Запросы сервера» |
| `App/RootView.swift` | фазы: нет серверов → онбординг; есть, но не авторизован → вход в Space |
| `ios/project.yml` | новые группы файлов |
| `ios/AETHER/Info.plist` (через `project.yml`) | `NSAllowsLocalNetworking` |
| `Localizable.xcstrings` | все новые строки RU/EN |

### Android / веб

В этой итерации не трогаем. Протокол спроектирован так, что они продолжают
работать против того же сервера по плоским маршрутам. Порт мультисерверности
на Android — отдельная задача, `docs/handoff-android.md` дополняется разделом
со ссылкой сюда.

---

## 20. Поэтапный план реализации

Каждый этап заканчивается проверяемым результатом и коммитом. Порядок выбран
так, чтобы после любого этапа приложение оставалось рабочим.

### Этап 0 — фундамент (без видимых изменений)
* Этот документ, раздел в `WIRE_PROTOCOL.md`.
* `server/main.py` → зеркалирование существующих маршрутов в `/api/v1`
  (без переноса кода), каркас для новых роутеров.
* **Проверка:** все существующие тесты и кросс-примеры из `core/examples/`
  проходят; iOS-сборка работает без единой правки клиента; `/users/login` и
  `/api/v1/users/login` отвечают одинаково.

### Этап 1 — идентичность сервера
* `server_id`, ed25519, `/server/info`, `/.well-known/aether`, подпись, nonce.
* Миграции S1–S5.
* `core/src/discovery.rs` + `core/examples/server_info.rs`.
* **Проверка:** пример из `core/examples` находит официальный сервер, проверяет
  подпись, ловит подмену подписи.

### Этап 2 — роли, политика регистрации, аудит
* `server_roles`, `require_role`, бутстрап OWNER, `audit_log`.
* `registration_mode` в `/users/register`, инвайты.
* **Проверка:** USER получает 403 на всех админ-ручках; OWNER работает;
  `CLOSED` действительно закрывает регистрацию; всё видно в аудите.

### Этап 3 — мультисерверность на клиенте (без нового UI)
* `ServerRegistry`, миграция C1–C6, Keychain по новым ключам, `CoreClient.bind`,
  замена `CoreClient.baseURL` во всех местах.
* Официальный сервер — единственная запись; UI внешне не меняется.
* **Проверка (обязательная, на живом iPhone):** обновление поверх существующей
  установки не теряет переписку, чаты и аккаунты; переключение аккаунтов
  работает как раньше.

### Этап 4 — добавление и вход на пользовательский сервер
* `WelcomeView` с двумя вкладками, `AddServerView`, `ServerAuthView`.
* TOFU-пин и экран смены идентификатора.
* **Проверка:** поднять второй инстанс сервера, добавить, зарегистрироваться,
  переписаться внутри него; официальный аккаунт при этом не задет.

### Этап 5 — переключатель пространств и управление серверами
* `SpaceSwitcher` в шапке чатов, `ServersListView`, `ServerDetailView`.
* Полная перезагрузка Messaging при смене Space.
* **Проверка:** быстрое переключение туда-сюда не путает чаты, не течёт по
  памяти, не смешивает непрочитанные.

### Этап 6 — заявки: подача и ожидание
* `registration_requests`, ручки заявителя, `/ws/registration`, поллинг.
* `RegistrationRequestView`, восстановление заявки после переустановки.
* **Проверка:** заявка подана, статус виден, отмена работает, срок истекает.

### Этап 7 — допуск администратором прямо в чате
* Админ-ручки заявок, WS-события админам.
* Виртуальный чат «Запросы сервера», карточки, approve/reject в один тап.
* **Проверка:** два устройства — админ видит заявку в списке чатов мгновенно,
  одобряет, заявитель тут же получает «Доступ разрешён» и входит.

### Этап 8 — модель разрешений и согласие
* `DataPermissions`, глобальная политика, экран согласия при первом входе,
  экран «Data access», отзыв доступа.
* **Проверка:** ни один байт данных не уходит без явного гранта; отзыв виден
  в разрешениях; тест «подключение ≠ согласие».

### Этап 9 — перенос данных
* `core/src/import.rs`, `DataImportEngine`, серверные ручки импорта.
* Категории, scope, сводка, прогресс, резюмируемость.
* **Проверка:** перенос 30 дней истории и медиа на второй сервер; обрыв сети
  посередине и корректное продолжение; проверка, что в шифротексте нет
  plaintext (тест-скрипт по дампу чанков).

### Этап 10 — панель администратора и остальное
* `ServerAdminView` (Overview/Users/Requests/Invites/Roles/Sessions/Security/
  Storage/Logs/Settings).
* Запрос удаления данных, экспорт своих данных.
* Локальный режим `.lanPinnedCert` (кастомный верификатор в ядре).
* Локализация RU/EN всего нового.
* **Проверка:** полный сценарий самохоста от `docker compose up` до второго
  впущенного пользователя.

### Порядок и риски

| Этап | Риск | Митигация |
|---|---|---|
| 0 | правка `main.py` ломает всех клиентов сразу | тела функций не трогаются вообще, только добавление алиасов; прогон кросс-тестов до деплоя; выкладка патч-скриптом через ssh+base64 (scp на этом VPS висит) |
| 3 | потеря локальной переписки при миграции Keychain | `db_encryption_key` копируется, не пересоздаётся; legacy-ключи живут ещё версию; приёмка на живом устройстве |
| 5 | утечки памяти и гонки при смене Space | Messaging полностью пересоздаётся по `accountGeneration`-подобному ключу, как уже сделано для смены аккаунта |
| 7 | двойное одобрение двумя админами | одна транзакция + `409` на терминальном статусе |
| 9 | случайная утечка секретов в экспорт | белый список полей + падающий тест на неизвестное поле |

---

## Приложение A. Чек-лист безопасности (§22 ТЗ)

| Требование | Где закрыто |
|---|---|
| клиентское шифрование чувствительных данных | 15.2 |
| E2EE для сообщений | уже есть (Olm/Double Ratchet), не трогаем |
| безопасное хранение токенов | 2.2, Keychain `AfterFirstUnlockThisDeviceOnly` |
| ротация access/refresh | 2.1 `refresh_tokens`, 8.2, reuse detection по `family_id` |
| отдельный токен на сервер | 1.2, 2.2 — токены физически в разных ключах Keychain |
| CSRF | API токеновый, куки не используются; `/ws` — токен в query по существующей схеме |
| rate limiting | существующий `slowapi` + лимиты на заявки, инвайты, импорт, админ-ручки |
| brute-force protection | лимиты login (есть) + лимит заявок на ip_hash |
| проверка TLS, валидация сертификата | 16.4 — не отключается автоматически никогда |
| защита от replay | nonce в `/server/info`, AAD в чанках импорта, одноразовые OTK (есть) |
| timestamps / nonce | `signed_at` + `nonce`, окно 5 минут |
| session revocation | `/logout` (есть) + `session.revoked` по WS + админский отзыв |
| device management | `crypto_devices`, `/sessions/me` (есть), расширяется в панели |
| audit log | 2.1 `audit_log`, запись в одной транзакции с действием |
| сервер не доверяет role от клиента | 10, `role_of()` только из БД |
| input validation / schema validation | pydantic-модели на всех новых ручках, как в существующем коде |
| ограничение размера upload | 15.6, `max_chunk_bytes`, квоты |
| защита WebSocket-аутентификации | токен проверяется при подключении (есть), `/ws/registration` — отдельный ограниченный канал с `claim_token` |
| никакого автоматического импорта | 6.2 инвариант 3, 14.1 |
| principle of least privilege | deny-by-default гранты, минимальные роли, отдельные токены |

## Приложение B. Что сознательно НЕ делается

* **Единая криптоличность на все серверы** — противоречит модели доверия (1.3).
* **Федерация / переписка между серверами** — вне объёма. Серверы независимы;
  чат между пользователями разных серверов не существует. Если понадобится —
  это отдельный протокол, а не расширение импорта.
* **Серверное хранение permissions как источника правды** — сервер не может
  быть арбитром того, что ему разрешено.
* **Автоматическое доверие официальному серверу в коде клиента** — §1.4.
* **Кнопка «принять недоверенный сертификат»** — 16.4.
* **Гарантия удаления данных с чужого сервера** — 14.5, обещать нельзя.

---

## Приложение В. Локальная разработка сервера

Боевой VPS до готовности этапа 2 не трогаем, поэтому серверная часть
разрабатывается и проверяется на машине разработчика.

```bash
brew install postgresql@16 && brew services start postgresql@16
export PATH="/opt/homebrew/opt/postgresql@16/bin:$PATH"
psql -h 127.0.0.1 -d postgres -c "CREATE ROLE sm_user LOGIN PASSWORD 'sm_pass'"
psql -h 127.0.0.1 -d postgres -c "CREATE DATABASE secure_messenger OWNER sm_user"

python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
DB_HOST=127.0.0.1 .venv/bin/python -m uvicorn server.main:app --port 8099
```

Проверка:

```bash
AETHER_URL=http://127.0.0.1:8099 .venv/bin/python server/test_server_info.py
AETHER_URL=http://127.0.0.1:8099 .venv/bin/python server/test_logout.py
AETHER_URL=http://127.0.0.1:8099 .venv/bin/python server/test_prekeys.py
```

`.venv/` в git не попадает. Свежая база стартует в режиме `APPROVAL` —
это и есть проверка дефолта из раздела 18.1.
