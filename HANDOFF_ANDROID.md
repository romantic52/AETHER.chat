# Промт для продолжения работы (Codex / ChatGPT)

Скопируй всё, что ниже разделителя, в новый чат целиком. Промт самодостаточный.

---

Ты продолжаешь работу над проектом **Aether** — самохостящийся E2EE-мессенджер. Предыдущий агент упёрся в квоту. Ниже полный контекст: где код, что уже сделано, что сломано, и что делать дальше. Ничего не выдумывай сверх этого — сначала проверь факты в репозитории, потом действуй.

## 0. Жёсткие правила проекта (нарушать нельзя)

1. **Рабочий код** — `~/Desktop/Progects/AETHER.chat`, ветка `multi-server`.
   Каталог `~/Desktop/Progects/xCode_test/Aether` — **старый чекаут от 9 июля, не трогать вообще**.
2. **Боевой VPS `https://144-31-181-10.nip.io` не трогать.** Никаких деплоев, миграций, запросов, меняющих состояние, без отдельного явного разрешения пользователя. Локальный стенд — сколько угодно.
3. **Не изобретать свою криптографию.** Только vodozemac (Olm/Double Ratchet), X25519, Ed25519, HKDF, HMAC, AES-GCM, Argon2id.
4. **Приватные ключи устройства и master secret никогда не уходят на сервер.**
5. **Схему существующих таблиц не менять** — только добавлять. Одну и ту же БД используют iOS, Android и веб.
6. **Не ломать** уже работающее: авторизацию, чаты, сессии, свои серверы, E2EE, контакты.
7. **Не обещать в интерфейсе того, чего ОС не даёт** (блокировка скриншотов на iOS, RCS, надёжный фоновый BLE).
8. **Интерфейс не делать похожим на Discord.** Первичны чаты и люди, сервер — инфраструктура, а не «комнаты».
9. Сначала **анализируй существующую архитектуру**, потом пиши. Не переписывать с нуля, предпочитать минимально разрушительную интеграцию.
10. Если требование конфликтует с безопасностью — объясни проблему и реализуй **безопасный** вариант.
11. Язык общения с пользователем — **русский**. Сообщения коммитов — русские, в стиле существующих (см. `git log`).

## 1. Где мы сейчас

```
репозиторий: ~/Desktop/Progects/AETHER.chat
ветка:       multi-server
HEAD:        d9e7a6adb9155dd1b702478cecf1291163feef6c
             "Android: gradlew исполняемый" (2026-08-26 22:42)
рабочее дерево: ЧИСТОЕ, стэшей нет
```

Всё закоммичено. Ничего не потеряно.

### Что уже сделано и работает

**Ядро (Rust, `core/`, UniFFI → Swift/Kotlin, wasm → веб):**
- `core/src/discovery.rs` — обнаружение сервера: `normalize_server_input`, `is_private_host`,
  `fetch_server_info`, `discover_server`, `server_fingerprint`, `format_fingerprint`.
  Проверяет подпись Ed25519, эхо nonce, совпадение эндпоинтов с origin.
- `core/src/message.rs` — сквозной идентификатор сообщения: `new_message_id()` (UUIDv7),
  `is_valid_message_id`, `message_id_from_payload`, `payload_with_message_id`;
  исчезающие сообщения: `EphemeralSpec`/`EphemeralTrigger`, `payload_with_ephemeral`,
  `ephemeral_from_payload` (обрезает враждебные ttl/view_limit).
- `core/src/nearby.rs` — приватные вращающиеся идентификаторы:
  `EDI(T) = HKDF-SHA256(DK, salt="AETHER-NEARBY-1", info=LE64(T))[0..16]`,
  маяк 15 байт упакован в 128-битный service UUID, байт неймспейса `0xAE`,
  допуск ±1 эпоха, сравнение тега за постоянное время.
- `core/src/crypto.rs` — резервная копия ключа v2: `v2:argon2id:` (m=65536, t=3, p=1).
  **`encrypt_private_key` намеренно всё ещё пишет v1**, `encrypt_private_key_v2` существует, но не используется.
  Читаются оба поколения. Окно совместимости истекает **2026-11-01**.
- `core/src/store.rs` — таблицы `message_route`, `message_delivery_attempts`,
  `chat_delivery_policy`, `server_storage_policy`, `ephemeral_state`.
  `ephemeral_purge` заменяет содержимое надгробием `{"type":"expired"}`.
- `core/src/api.rs` — `register_invite` добавлен **отдельным методом**, чтобы не менять
  сигнатуру `register`, которой пользуются Android и веб.

**Сервер (Python, `server/`):**
- `_mirror_api_v1()` дублирует все маршруты под `/api/v1` — старые пути продолжают работать.
- Новые модули: `server_identity.py`, `routes_server_info.py`, `routes_admin.py`,
  `registration_policy.py`, `roles.py`, `schema_multiserver.py`, `password_policy.py`.
- Режимы допуска OPEN/APPROVAL/INVITE_ONLY/CLOSED, роли OWNER/ADMIN/MODERATOR/USER.
- `/auth/refresh` с ротацией и обнаружением повторного использования токена.
- Argon2id для паролей + требования к паролям.
- Тесты: `test_server_info.py`, `test_admin_policy.py`, `test_password.py`.

**iOS (Swift) — доведён:** пространства (Сервер × Аккаунт), TOFU-доверие, `TransportRouter`,
политики доставки/хранения, экран «О сообщении», исчезающие сообщения и «просмотр один раз»,
Nearby по Bluetooth (этапы 5а/5б).

**Веб (`web/`) — доведён:** обнаружение серверов и проверка подписи, TOFU-реестр,
разделение хранилища по пространствам, экраны выбора сервера, переключение пространства
без перезагрузки, сквозной `mid` и дедупликация, чтение резервной копии v2.

**Документы (читай их первыми):**
- `docs/MULTI_SERVER_DESIGN.md` — 24 раздела + приложения (А: что намеренно не сделано; В: локальный стенд).
- `docs/TRANSPORT_LAYER_DESIGN.md` — слой доставки; приложение Г: план миграции ключевой резервной копии в 3 шага.

## 2. ГЛАВНОЕ ОТКРЫТИЕ — прочти внимательно, иначе выбросишь неделю чужой работы

Android в ветке `multi-server` — **это снимок от 8 июля**, он сильно устарел.
Последние коммиты, трогавшие `android/` в нашей истории: `f31398f` (2026-07-08), `dabd37b`, `7791c9b`.

**Настоящий, развитый Android лежит в невлитой ветке `origin/round-ui-performance`**
(`ab1b93e3a8495701073763e7d373ecc25cfc0cee`, 2026-08-22).

Проверено содержимым, а не сообщениями коммитов:

| | `multi-server` | `origin/round-ui-performance` |
|---|---|---|
| Kotlin-файлов в `android/` | 51 | 56 |
| `uniffi/sm_core/sm_core.kt` | 5396 строк, 315 функций | 6613 строк, **465 функций** |
| Double Ratchet в Kotlin | **отсутствует** | `RelayApi.kt`, `MessageRepository.kt` |
| `libsm_core.so` | 7.1 МБ | 11.7 МБ |

Биндинги в `round-ui-performance` экспортируют полный Olm/Double Ratchet и мультидевайс:
`olmAccountNew`, `olmAccountIdentity`, `olmAccountGenerateOtks`, `olmAccountOtkCount`,
`olmCreateOutbound`, `olmCreateInbound`, `olmEncrypt`, `olmDecrypt`,
`fetchInboxDevice`, `claimKeysDevice`, `ackMessagesDevice`, `bindSessionDevice`,
`listDevices`, `kickDevice`, `keysState`, `getGroupKey`, `getGroupKeys`.

Чего нет в `multi-server`-версии Android **вообще**.

Коммиты, которые есть в `round-ui-performance` и которых нет у нас:

```
ab1b93e  2026-08-22  Интерфейс: единые формы, кастомизация и оптимизация
ee7cd5f  2026-07-27  Полный прогон: устранены находки ревью,
                     в т.ч. потеря сообщений на вторых устройствах
8688088  2026-07-27  Этап 5: защита identity устройства, надёжность доставки, WIRE_PROTOCOL
a164de6  2026-07-27  Этап 4: вход по QR на десктопе, сканер на Android, sliding expiry сессий
f701508  2026-07-26  Голосовые и кружки: волна в wire, свайп-отмена, 48кГц
f15ad41  2026-07-26  Звонки: рингтон/гудки, имя и аватар, ICE-restart
c55d368  2026-07-26  Цельный Liquid Glass по всем экранам
8d539cd  2026-07-26  Экран «Сессии и безопасность» (сессии/2FA/wipe)
45526d8  2026-07-19  Multi-device v1: устройство = свой Olm-аккаунт
758ed2a  2026-07-12  Android: синхронизировать интерфейс и Double Ratchet с iOS
```

**Засада:** `multi-server` и `origin/round-ui-performance` — **несвязанные истории**.
Два разных «Initial commit» от 2026-07-07 с разными хешами (`7791c9b` и `cbfbfc5`).
`git merge-base` общего предка не даёт. Сводить надо **содержимым**, а не обычным merge.

**Файлы, которые есть только в `multi-server` — все вытеснены более развитыми аналогами,
терять их не жалко (но проверь сам перед удалением):**
- `ui/components/LiquidGlass.kt` → вытеснен `ui/glass/GlassEngine.kt` + `ui/components/GlassBackground.kt`
- `ui/screens/ProfileScreen.kt` → вытеснен `ProfileSettingsScreen.kt` + `ContactProfileScreen.kt`
- `res/layout/activity_main.xml` → легаси-разметка в Compose-приложении

**Файлы только в `round-ui-performance`:** `data/MediaCache.kt`, `data/MessagePreview.kt`,
`pairing/PairingLink.kt`, `ui/components/AetherRows.kt`, `ui/components/AetherSettingsTopBar.kt`,
`ui/screens/PairDeviceScreen.kt`, `ui/screens/SecurityScreen.kt`.

### Хорошая новость про архитектуру

Android из `round-ui-performance` ходит на сервер **через то же Rust-ядро**, что и iOS:
`core.login`, `core.register`, `core.sendMessage`, `core.sendMessageDevice`, `core.fetchInbox`,
`core.fetchInboxDevice`, `core.claimKeys`, `core.claimKeysDevice`, `core.bindSessionDevice`,
`core.listDevices`, `core.kickDevice`, `core.heartbeat`, `core.logout` и т.д.

Своих сырых HTTP-запросов там всего пять:
`$base/upload`, `$base/users/`, `$base/users/me/profile`, `$base/users/search`,
`$base/groups/`, `$base/pairing/approve` — и все эти маршруты наш сервер по-прежнему отдаёт
(маршруты `/api/v1` **зеркалированы**, старые пути не удалялись).

`api/ServerConfig.kt` уже имеет изменяемый `var baseUrl` — то есть мультисерверность
ложится тем же способом, что на iOS (`bindSpace`/`CoreClient` в Swift).
**Переписывать сетевой слой не нужно.**

## 3. Окружение сборки (уже настроено, проверено)

```
Android SDK: /opt/homebrew/share/android-commandlinetools   (brew android-commandlinetools)
             platform-tools + platforms;android-34 + build-tools;34.0.0, 10 лицензий приняты
android/local.properties: sdk.dir=/opt/homebrew/share/android-commandlinetools  (в .gitignore)
android/gradlew: бит исполнения выставлен и закоммичен (d9e7a6a)

JDK: ОБЯЗАТЕЛЬНО Temurin 21 — Gradle 8.5 не понимает Java 25 (в системе стоит и 25, и 21)
     JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home

cargo-ndk: установлен (~/.cargo/bin/cargo-ndk)
NDK:       ОТСУТСТВУЕТ — нужен для пересборки libsm_core.so
           ставить: sdkmanager --install "ndk;26.1.10909125"
```

Команда сборки:

```bash
cd ~/Desktop/Progects/AETHER.chat/android
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew assembleDebug --no-daemon
```

**Статус на момент передачи: базовая сборка ПРОШЛА.**

```
BUILD SUCCESSFUL in 11m 24s
38 actionable tasks: 38 executed
android/app/build/outputs/apk/debug/app-debug.apk   77 МБ, 2026-08-26 23:04
```

Тулчейн подтверждён рабочим. Собран **старый** Android из ветки `multi-server`
(снимок от 8 июля, без Double Ratchet) — это была проверка окружения, а не цель.
Первый прогон занял 11 минут, потому что Gradle тянул зависимости; повторные быстрее.

Замечание в логе, неопасное: `This version only understands SDK XML versions up to 3 but ...
version 4 was encountered` — расхождение версий cmdline-tools, сборке не мешает.

## 4. Что делать дальше (порядок обязателен)

### Шаг 0. Уже выполнен — база собирается
Базовая сборка прошла (`BUILD SUCCESSFUL`, APK 77 МБ, см. раздел 3). Повторять не нужно.
Начинай сразу с шага 1.

### Шаг 1. Перенести Android из `round-ui-performance`
Отдельной веткой от `multi-server`, чтобы откат был дешёвым:

```bash
git switch -c android-parity multi-server
git rm -r --cached android/ -q && rm -rf android/
git checkout origin/round-ui-performance -- android/
# вернуть local.properties (он в .gitignore, но нужен для сборки)
echo 'sdk.dir=/opt/homebrew/share/android-commandlinetools' > android/local.properties
chmod +x android/gradlew
```

Собрать **как есть**, со старым прибилженным `libsm_core.so`. Это уже даёт Android
с Double Ratchet, мультидевайсом, звонками, кружками и голосовыми.
Зафиксировать коммитом. История оттуда не приедет — приедет код одним коммитом;
это осознанное решение, обсуждено с пользователем.

### Шаг 2. Пересобрать ядро под Android
```bash
sdkmanager --install "ndk;26.1.10909125"
cd ~/Desktop/Progects/AETHER.chat/core
cargo ndk -t arm64-v8a -t x86_64 -o ../android/app/src/main/jniLibs build --release
# перегенерировать биндинги UniFFI в android/app/src/main/java/uniffi/sm_core/sm_core.kt
```
После этого в биндингах появятся `discoverServer`, `newMessageId`, `nearby*`,
`encryptPrivateKeyV2`, `registerInvite`, `ephemeral*`. Починить расхождения компиляции.

### Шаг 3. Мультисерверность на Android
По образцу iOS (`Core/ServerRegistry.swift`, `Core/ServerContext.swift`, `Core/ServerDirectory.swift`,
`Core/ServerMigration.swift`, `Features/Servers/*`): пространство = (Сервер, Аккаунт),
обнаружение через `discoverServer`, TOFU-закрепление отпечатка, отдельное хранилище и ключи
на пространство, переключение пространств. `ServerConfig.baseUrl` уже изменяемый.
Интерфейс — **не как Discord**: чаты и люди первичны.

### Шаг 4. Слой доставки на Android
Сквозной `mid` и дедупликация, политики доставки/хранения, экран «О сообщении»,
исчезающие сообщения и «просмотр один раз». По образцу `docs/TRANSPORT_LAYER_DESIGN.md`.

## 5. Отложенные хвосты (не забыть, но не сейчас)

- **Деплой на боевой VPS** — только с отдельного разрешения пользователя.
- **iOS, этапы 6–7** (BLE-рукопожатие и доставка) — нужен второй телефон.
- **Переключение записи резервной копии ключа на v2** — шаг 3 плана из приложения Г,
  делать только после того, как Android и веб научатся читать v2. Окно закрывается **2026-11-01**.
- **Ротация групповых ключей по времени** — не сделана.
- У пользователя **есть исходники на его ПК**; если что-то не сойдётся, версии можно объединить
  из его копии. Разбирать готовый APK **не требуется** — всё найдено в git.

## 6. Как работать

- Перед изменениями — прочитай `docs/MULTI_SERVER_DESIGN.md` и `docs/TRANSPORT_LAYER_DESIGN.md`.
- Коммить часто, мелкими осмысленными шагами, сообщения по-русски.
- Не делай `git push` без явной просьбы.
- Осторожно с `sed`-заменами по всему дереву: предыдущий агент глобальной заменой `\\/` → `\/`
  сломал легитимное регулярное выражение `[\\/\0]` в санитайзере имён файлов.
  Всегда проверяй `git diff` перед коммитом.
- В zsh `"$var:android/path"` ломается — `:a` съедается как модификатор истории.
  Пиши `git show "$var":"android/path"`.
- Отчитывайся честно: если сборка упала — покажи вывод; если шаг пропущен — скажи об этом.

Начни с шага 1 — база уже собирается, окружение проверено.
