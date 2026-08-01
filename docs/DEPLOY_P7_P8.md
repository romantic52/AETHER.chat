# Чек-лист деплоя P7 + P8 (HEAD 349aace, ветка web-secure)

## 0. Что именно катим

Диапазон коммитов: `b1689de..349aace` (6 коммитов: P7, P7-фиксы, P8, P8-фиксы, финальная проверка, третий круг).

Артефакты:
- `/Users/rmkhc/Desktop/Progects/AETHER.chat/server/main.py` (+315 строк: миграции, `_verify_upload_signatures`, анти-даунгрейд, `signed_devices`, `claim` под `FOR UPDATE`, `sessions.device_bound_at`, `inbox` с фильтром по receipts и `LIMIT 200`)
- `/Users/rmkhc/Desktop/Progects/AETHER.chat/web/app.js` (+402), `web/index.html` (+28), `web/style.css` (+23)
- `/Users/rmkhc/Desktop/Progects/AETHER.chat/web/vendor/ratchet/aether_ratchet_wasm.js` (25 572 Б) и `aether_ratchet_wasm_bg.wasm` (683 341 Б, было 599 640) — артефакт уже собран и закоммичен, пересобирать wasm не нужно (экспорты `master_public`/`sign_device`/`verify_device`/`account_generate_otks_signed`/`verify_prekey_bundle`/`verify_identity` в нём есть)
- `/Users/rmkhc/Desktop/Progects/AETHER.chat/server/test_prekeys.py` (новый смок-тест)
- iOS — собирается локально, на сервер не катится

Прод (из `docs/PROJECT_CONTEXT_FOR_AI.md`): `<SERVER_IP>`, systemd `secure_messenger.service`, `/root/secure_messenger/server/main.py`, домен `https://<SERVER_HOST>`. scp/SFTP на VPS отключён — только `ssh + base64 + python3` или rsync; ssh-сессии держать по одной.

## 1. Порядок: СНАЧАЛА сервер, ПОТОМ web. Почему

Веб и API — один процесс: `WEB_DIR = <repo>/web` (`server/main.py:42`) и `app.mount("/", StaticFiles(directory=WEB_DIR, html=True))` (`server/main.py:2344`). `StaticFiles` читает файлы с диска на каждый запрос, а Python-код обновляется только после `systemctl restart`. То есть новый `app.js`, положенный на диск, уходит пользователям НЕМЕДЛЕННО, а новый `main.py` — нет. Окно «новый web + старый сервер» нужно исключить.

Чем оно опасно (не косметика, а невосстановимое состояние клиентов):
- Pydantic по умолчанию игнорирует лишние поля → старый сервер молча проглотит `ed25519_key_b64`/`identity_sig_b64`/`otk_signatures`/`master_key_b64`/`device_sig_b64` и сохранит бандл БЕЗ подписей.
- Клиент при этом считает публикацию успешной и ставит флаг разовой перепубликации: web — `localStorage['olm_published_v2_<myId>'] = '1'` (`web/app.js:679-713`), iOS — `store.metaSet("olm_published_v2", "1")` (`ios/AETHER/Core/CoreClient.swift:397`).
- Гварды `serverCount >= 20 && signedPublished` (`web/app.js:680`) и `guard count < 20 || !alreadyPublished` (`CoreClient.swift:377`) означают: устройство больше НИКОГДА само не опубликует подписи. Это прямо инвариант №7 из `P7_SIGNED_PREKEYS_DESIGN.md`.

Обратный порядок (сервер вперёд) безопасен: новый сервер полностью совместим со старым неподписанным клиентом — `signed`/`cross_signed` вычисляются по наличию полей, легаси-путь сохранён (проверяется пунктом 3b в `server/test_prekeys.py`).

## 2. Шаги

### Шаг 0 — прогон локально (до касания прода)

```
cd /Users/rmkhc/Desktop/Progects/AETHER.chat
node web/test_wire.js
node web/test_security.js
cargo test --manifest-path core/Cargo.toml            # store: TOFU-семантика olm_pins/master_pins
cargo test --manifest-path core/ratchet-core/Cargo.toml   # каноны + отказ фантомному устройству
python3 -m uvicorn server.main:app --host 127.0.0.1 --port 8000   # локальный инстанс на чистой БД
AETHER_URL=http://127.0.0.1:8000 python3 server/test_prekeys.py
```
Успех: `ВСЁ ЗЕЛЁНОЕ` и все 7 промежуточных строк («upload подписанного бандла: ok», «битые подписи ... отвергнуты», «связка подписей и cross-signing», «анти-даунгрейд», «легаси-путь», «bind сессии», «один мастер на аккаунт», «claim: подписи и мастер-поля на месте», «директория устройств»).
Провал: любой `AssertionError` — деплой не начинать.

### Шаг 1 — бэкап БД

```
ssh root@<SERVER_IP> 'pg_dump -U sm_user -h 127.0.0.1 secure_messenger | gzip > /root/backup_pre_p7p8_$(date +%F_%H%M).sql.gz && ls -la /root/backup_pre_p7p8_*'
```
(имя БД/пользователь — дефолты из `server/main.py:189-192`, если на проде не переопределены `DB_NAME`/`DB_USER`.)

Признак успеха: файл ненулевого размера. Без бэкапа дальше не идти — миграции необратимы штатными средствами (см. §4).

### Шаг 2 — сервер

Залить `server/main.py` патч-скриптом (`ssh + base64 + python3`, scp не работает), сохранив прежнюю копию:

```
ssh root@<SERVER_IP> 'cp /root/secure_messenger/server/main.py /root/secure_messenger/server/main.py.bak_p6'
# передача base64-чанками, затем:
ssh root@<SERVER_IP> 'python3 -c "import ast;ast.parse(open(\"/root/secure_messenger/server/main.py\").read())" && systemctl restart secure_messenger && sleep 3 && systemctl is-active secure_messenger'
```

Миграции идут в `init_db()` на `@app.on_event("startup")` (`server/main.py:709-711`), отдельного шага нет. Добавляются: `crypto_devices.ed25519_key_b64/identity_sig_b64/master_key_b64/device_sig_b64`, `one_time_keys.sig_b64`, `sessions.device_bound_at`, таблица `signed_devices` (+колонка `cross_signed`). Все `ALTER TABLE ADD COLUMN` идут БЕЗ `IF NOT EXISTS`, под проверкой `information_schema`, которая в этом релизе переписана на `table_schema = current_schema()` — то есть тронуты ВСЕ старые гварды миграций, не только новые.

Проверить сразу после рестарта:
```
ssh root@<SERVER_IP> 'journalctl -u secure_messenger -n 60 --no-pager'
curl -s -o /dev/null -w "%{http_code}\n" https://<SERVER_HOST>/
```
Признаки успеха: `active (running)`, в логе нет `DuplicateColumn`/`UndefinedColumn`/`psycopg2`-трейсбека, статика отдаёт 200.
Признаки провала: сервис в `failed`/рестарт-цикле, `column ... already exists` в логе, 502 от прокси. → немедленно §4.

Схему подтвердить явно:
```
ssh root@<SERVER_IP> "psql -U sm_user -h 127.0.0.1 -d secure_messenger -c \"\\d crypto_devices\" -c \"\\d signed_devices\" -c \"\\d sessions\""
```

Смок-тест против прода — только осознанно: `server/test_prekeys.py` НЕ имеет дефолтного URL (`SystemExit`, если `AETHER_URL` пуст), потому что регистрирует постоянные аккаунты. Если гоняете по проду:
```
AETHER_URL=https://<SERVER_HOST> python3 server/test_prekeys.py
AETHER_URL=https://<SERVER_HOST> python3 server/test_logout.py
```
Останутся мусорные аккаунты `prekey_a_*`, `prekey_b_*` и их устройства `test-*`/`legacy-*` — почистить потом руками или прогонять на стейджинге. Дополнительно: старые клиенты (ещё не обновлённый web в открытых вкладках, старый iOS-билд) должны продолжать переписываться — это и есть главная проверка легаси-пути.

### Шаг 3 — web

Заливать одним заходом всё сразу: `web/app.js`, `web/index.html`, `web/style.css`, `web/vendor/ratchet/aether_ratchet_wasm.js`, `web/vendor/ratchet/aether_ratchet_wasm_bg.wasm`. Разъезд `app.js` и wasm-склейки ломает загрузку модуля.

Wasm — 683 КБ бинарника (≈911 КБ в base64), передавать чанками; после заливки сверить размер и хеш:
```
ssh root@<SERVER_IP> 'ls -l /root/secure_messenger/web/vendor/ratchet/ && sha256sum /root/secure_messenger/web/vendor/ratchet/aether_ratchet_wasm_bg.wasm'
shasum -a 256 /Users/rmkhc/Desktop/Progects/AETHER.chat/web/vendor/ratchet/aether_ratchet_wasm_bg.wasm
```
Хеши обязаны совпасть, размер — 683341.

ГРАБЛИ КЭША (проверить до заливки): middleware ставит `Cache-Control: no-cache` только для `.js`, `.html`, `.css` и `/` (`server/main.py:167-168`). `.wasm` НЕ покрыт, а склейка грузит его как `new URL('aether_ratchet_wasm_bg.wasm', import.meta.url)` (`web/vendor/ratchet/aether_ratchet_wasm.js:709`) — без версионного query. `web/index.html:694` тянет `app.js?v=5.2.0`, и эта версия не менялась с первого коммита, так что от stale-кэша спасает только заголовок. Итог: новый `app.js` придёт свежим, а `.wasm` браузер может взять старый по эвристической свежести → падение с «Не удалось загрузить модуль Double Ratchet» (`web/app.js:519`). `web/sw.js` тут ни при чём — он network-first и чистит все кэши на activate.
Минимизация: перед заливкой добавить `.wasm` в кортеж на `server/main.py:167` и рестартнуть сервер ещё на шаге 2 (это правка сервера, поэтому её место именно там), иначе пользователям придётся жать hard reload.

Проверка после заливки:
```
curl -sI https://<SERVER_HOST>/app.js | grep -i cache-control
curl -sI https://<SERVER_HOST>/vendor/ratchet/aether_ratchet_wasm_bg.wasm | grep -iE 'content-length|cache-control'
```
Затем вручную в браузере (жёсткая перезагрузка, две разные учётки):
1. Логин → в консоли нет ошибок загрузки ratchet-модуля.
2. `PUT /keys/upload` вернул 200 (в Network), после чего `GET /users/<me>/devices` показывает непустые `ed25519_key_b64`, `master_key_b64`, `device_sig_b64`.
3. Отправка сообщения в обе стороны, приём — без баннера смены ключа между двумя обновлёнными вебами.
4. Экран сверки отпечатка (`#safety-modal`) — `AetherSafety#2` совпадает с iOS-экраном на том же пире.

Признаки провала: «Не удалось опубликовать prekeys» (значит, upload вернул 400/409 — смотреть детали: `Signed upload requires ...`, `Master key mismatch with existing devices`, `Signed uploads required for this device`); баннер «Устройство собеседника не подписано его аккаунтом» между двумя ОБНОВЛЁННЫМИ клиентами; сообщения не вскрываются с «Устройство отправителя не подтверждено директорией его аккаунта».

## 3. Признаки успеха деплоя в целом

- `secure_messenger.service` в `active (running)` дольше 10 минут без рестартов.
- В `journalctl` нет 500-х на `/keys/upload`, `/keys/claim/*`, `/users/*/devices`.
- В БД у обновившихся устройств заполнены `ed25519_key_b64` и `master_key_b64`, в `signed_devices` появляются строки с `cross_signed = 1`.
- Один мастер на аккаунт: `SELECT user_id, COUNT(DISTINCT master_key_b64) FROM crypto_devices WHERE master_key_b64 IS NOT NULL GROUP BY user_id HAVING COUNT(DISTINCT master_key_b64) > 1;` — пусто.
- Переписка идёт между: новый web ↔ новый web, новый web ↔ старый клиент (легаси-путь), новый web ↔ новый iOS.

## 4. Откат

Правило: НЕ откатывать сервер в одиночку, если новый web уже залит. Старый сервер не отдаёт поля подписей в `claim`/`devices`, а новый клиент, у которого уже запинен `ed25519` устройства пира, трактует их пропажу как стриппинг и отказывается отправлять (`verifyDeviceOwnership`, `web/app.js:864-911`; тот же гейт в `CoreClient.swift`). Получится тихая заморозка переписки у всех обновившихся.

Порядок отката (обратный деплою):
1. Вернуть web-файлы предыдущей ревизии (`app.js`, `index.html`, `style.css`, `vendor/ratchet/*` — все пять, из `de85483`), проверить хеш wasm.
2. Вернуть сервер: `cp /root/secure_messenger/server/main.py.bak_p6 /root/secure_messenger/server/main.py && systemctl restart secure_messenger`.
3. Колонки и таблицу `signed_devices` НЕ дропать: они аддитивные, старый код их просто не читает. Но старый `upload_keys` обновляет только `identity_key_b64`, оставляя протухшие `ed25519_key_b64`/`master_key_b64` в строке — если позже вернуться к новой версии, у части устройств будет несогласованный бандл. Поэтому при откате «насовсем» гасить поля:
```
UPDATE crypto_devices SET ed25519_key_b64=NULL, identity_sig_b64=NULL, master_key_b64=NULL, device_sig_b64=NULL;
UPDATE one_time_keys SET sig_b64=NULL;
DROP TABLE signed_devices;   -- иначе анти-даунгрейд навсегда блокирует неподписанный upload
```
`sessions.device_bound_at` можно оставить: старый код колонку не читает.
4. Если катастрофа с данными — восстановление из дампа шага 1.
5. После ЛЮБОГО отката и повторного деплоя устройства уже держат `olm_published_v2 = 1` и сами подписи не перезальют. Рычаг форс-перепубликации без правки клиента: обнулить пул OTK — `DELETE FROM one_time_keys WHERE user_id = LOWER('<user>');` — тогда `count < 20` и `ensureRatchetKeys`/`ensureOlmKeys` опубликуют заново. Если менялся формат публикации — по инварианту №7 бампить имя флага (`olm_published_v3`) в обоих клиентах синхронно.

## 5. iOS (собирается локально)

- Изменения: `ios/AETHER/Core/CoreClient.swift` (+379), `Core/Messaging.swift` (+72), `Features/Chat/ChatView.swift` (+59), `Features/Lock/KeyVerificationView.swift` (+52), `Features/Groups/GroupProfileView.swift`.
- iOS зависит от ядра: перед сборкой обязательно пересобрать Rust → XCFramework и биндинги, иначе `olmMasterPublic`/`olmSignDevice`/`olmVerifyDevice` не появятся в `AETHER/Core/Generated`:
```
cd /Users/rmkhc/Desktop/Progects/AETHER.chat/ios
bash build_core_ios.sh          # FAST=1 — только симулятор
xcodegen generate
xcodebuild -project AETHER.xcodeproj -scheme AETHER -configuration Debug -sdk iphonesimulator build
```
- Сборку класть в дефолтный DerivedData, НЕ на Desktop (iCloud вешает `com.apple.FinderInfo` на `.appex`, codesign падает).
- Устанавливать новый iOS-билд ТОЛЬКО после того, как сервер подтверждённо на новой версии. Иначе iOS поставит `olm_published_v2 = 1` против старого сервера и никогда не опубликует подписи — а лечится это только сбросом OTK на сервере (§4.5) или переустановкой.
- Старый iOS-билд на руках не ломается: для пиров он остаётся «знакомым легаси-устройством» (TOFU по curve). Но для НОВОГО чата, начатого после того, как аккаунт опубликовал мастер, старое устройство того же аккаунта попадёт под «неподписанное устройство» — см. §6.
- Сверку отпечатков проверять кросс-платформенно: `AetherSafety#2` считается по мастер-ключам и по инварианту №8 обязан совпадать посимвольно между `web/app.js` (`SAFETY_EMOJI`, `safetyFingerprint`) и `ios/AETHER/Features/Lock/KeyVerificationView.swift`.
- Android не трогаем: ratchet там не подключён, биндинги отстают (`P7_SIGNED_PREKEYS_DESIGN.md`, §5).

## 6. Что сломается у пользователей в момент переключения и как минимизировать

1) **Смешанный набор устройств пира.** Пир с одним обновлённым и одним НЕ обновлённым устройством: новое устройство без подписей при уже запиненном мастере трактуется как подсадка — на исходящих оно пропускается (`error.skipDevice`), остальным копии уходят, в чате появляется баннер «Устройство собеседника не подписано его аккаунтом»; входящие с него отвергаются. Лечится либо обновлением пира, либо явным «Доверять устройству» (пин по curve, TOFU). Минимизация: обновить web и iOS в один заход, не растягивать окно; предупредить активных пользователей.

2) **Fail-closed на директории — САМЫЙ ОСТРЫЙ РИСК.** При `pin != identity` клиент лезет в `GET /users/{id}/devices`, и недоступная/неполная директория означает отказ вскрывать сообщение (`web/app.js`, `openRatchetEnvelope`). Само по себе это ретраится поллингом. Но: web поллит раз в 2 с (`web/app.js:1985`), а после `MAX_DECRYPT_TRIES = 8` неудач сообщение уходит в карантин и ACK'ается — то есть теряется безвозвратно. Итого недоступность `/users/*/devices` дольше примерно 16 секунд при живом поллинге может съесть сообщения в полёте. На iOS мягче: там тот же лимит `maxDecryptRetries = 8`, но вместо тихого ACK сохраняется плейсхолдер (`Messaging.swift:363-371`). Минимизация: рестарт сервера делать ОДИН и максимально коротким (`systemctl restart`, не stop/правка/start), не заливать web во время рестарта, не оставлять состояние, где `/users/*/devices` отвечает 500. Дополнительно: перед рестартом убедиться, что прокси не отдаёт 502 дольше пары секунд.

3) **Тревоги TOFU на первом контакте.** После обновления клиент пинит мастер-ключи заново. Любой пир, переустановивший аккаунт, даст баннер «Изменился ключ аккаунта собеседника»; принятие сбрасывает ВСЕ device-пины и сессии этого пира. Ожидаемо, но выглядит как атака — стоит заранее объяснить пользователям, что баннер после апдейта нормален, и что правильная реакция — сверка отпечатка, а не автоматическое «принять».

4) **Форс-перепубликация чистит пул OTK.** При первой подписанной публикации меняется `ed25519` записи → сервер удаляет все старые OTK устройства (`identity_rotated` в `upload_keys`). Пиры, у которых ещё нет сессии, сделают новый claim — это штатно; но пик claim'ов приходится ровно на момент переключения. Rate-limit на claim в бэклоге (SEC MED-3), так что всплеск ничем не сглажен.

5) **Анти-даунгрейд необратим по устройству.** Как только устройство один раз опубликовало подписанный бандл, `signed_devices` (надгробие переживает даже `kick_device`) навсегда запрещает неподписанный upload → 400 «Signed uploads required for this device». Пользователь со stale-кэшем старого `app.js` получит «Не удалось опубликовать prekeys» и не сможет пользоваться клиентом. Это ещё один аргумент закрыть вопрос кэша `.wasm`/`app.js` до заливки web.

6) **Гонка вкладок в web.** Пины (`olmEdPins`, `olmMasterPins`) лежат в том же `ratchet_<myId>`-блобе с last-writer-wins — несколько открытых вкладок могут затереть свежий пин. Известная проблема, Web Locks в бэклоге. Минимизация на время переключения: просить закрыть лишние вкладки, обновлять в одной.

7) **Смена гейта `kick_device`.** Теперь 12-часовой порог применяется и к «своему» устройству, а возраст берётся как минимум из возраста устройства и `sessions.device_bound_at`, который у ВСЕХ существующих сессий пуст на момент миграции. Практически: сразу после деплоя ни одна старая сессия не сможет выкидывать устройства, пока не переподключится и не пере-биндится (`PUT /sessions/me/device`) и не пройдут 12 часов. Экран «Сессии» будет отдавать 403 — это ожидаемо, не баг.

8) **Инбокс.** `GET /messages/inbox/{user_id}` теперь исключает уже подтверждённые сообщения и ограничен `LIMIT 200` в обеих ветках. Для клиента с большим накопленным хвостом выдача пойдёт порциями по 200 за поллинг — визуально сообщения «догружаются», это не потеря.

Отдельно, мелочь для локальных/APK-сценариев: в репозитории дефолтный адрес сервера — плейсхолдер `https://your-server.example.com` (`web/app.js:3862`, `web/index.html:46`). На проде это не всплывает, так как при открытии с реального домена берётся `window.location.origin`, но если на VPS лежала пропатченная версия с настоящим URL — заливка файлов из репозитория этот патч затрёт. Сверить перед копированием.
