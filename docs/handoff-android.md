# AETHER — задача для Android-машины (Windows)

## Контекст (актуально на 21.07)
Сервер, core, web, iOS — всё на проде: multi-device, контроль сессий, 2FA (TOTP),
«удалить всё», список диалогов, скругления UI. Android **отстаёт**:

- Ветка `android` (коммит 45526d8) содержит multi-device В КОДЕ (Kotlin + core
  device-методы), но `android/app/src/main/jniLibs/*/libsm_core.so` — СТАРЫЕ,
  без новых символов. Это блокер: APK упадёт при загрузке UniFFI-биндингов.
- Новее multi-device (сессии/2FA/wipe/диалоги) в ветку `android` ещё НЕ влиты —
  они на `web-secure`. Для Android это отдельная фича (сервер уже поддерживает).

## Задача №1 (обязательно): поднять multi-device на Android
1. `git fetch && git checkout android && git pull`.
2. Пересобрать Rust-ядро под Android из каталога `core/`:
   `cargo ndk -t arm64-v8a -t x86_64 -o ../android/app/src/main/jniLibs build --release`
   (или через `scripts/build_apk.ps1`).
3. `sm_core.kt` в `android/.../uniffi/` НЕ перегенерять — он уже соответствует core.
4. Собрать APK, поставить на устройство.

Проверить:
- Вход в существующий аккаунт узнаёт себя как `primary` (история/сессии живы).
- Свежая установка при живом аккаунте занимает слот `android-xxxx`, без ошибок.
- Отправка 1:1 → на сервере в `messages` копии по числу устройств получателя
  (recipient_device_id + sender_device в конверте).
- Приём различает устройства отправителя (сессии peer::device).

## Задача №2 (опционально): сессии / 2FA / «удалить всё» на Android
Сервер уже отдаёт эндпоинты (см. ниже). В Android нужно: подтянуть новые
core-методы из ветки `web-secure` (list_sessions, kick_device, totp_*, wipe,
login_totp, dialogs) — перегенерить `sm_core.kt` — и сделать Kotlin-обёртки +
экран «Безопасность» (аналог iOS SecurityView / web-настроек).

## Сервер (прод)
- https://<SERVER_HOST> , ssh root@<SERVER_IP> (алиас `aether-vps` по ключу)
- Код: /root/secure_messenger/server/main.py, рестарт: `systemctl restart secure_messenger`
- БД: `sudo -u postgres psql secure_messenger`
- Тест-аккаунт: web_test_rm / Qa-test-7391-web

## Эндпоинты
- Multi-device: GET /users/{id}/devices, /keys/claim/{id}?device_id=,
  /keys/upload {device_id}, /messages {target_device_id}, inbox/ack ?device_id=.
  Старые вызовы без device_id сервер маппит на 'primary'.
- Сессии: GET /sessions/me, DELETE /sessions/device/{id}, PUT /sessions/me/device.
- 2FA: GET /2fa/status, POST /2fa/setup|enable|disable; логин принимает totp_code.
- Прочее: POST /users/me/wipe (по паролю), GET /users/me/dialogs.

## Грабли
- scp/SFTP на VPS отключён — только rsync или ssh+base64.
- fail2ban банит частые ssh-подключения: держать одну сессию (ControlMaster).
- Коммиты без упоминания ИИ.
