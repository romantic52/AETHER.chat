# AETHER — задача для Android-машины (Windows)

## Контекст
Multi-device v1 реализован на сервере, в core, в web и iOS — всё на проде и работает.
Android-код уже переведён на новый протокол и запушен в ветку `android`
(коммит 45526d8 «Multi-device v1 (Android)»), НО нативные библиотеки ядра
(`android/app/src/main/jniLibs/*/libsm_core.so`) — старые: в них нет новых
символов (list_devices, *_device-методы). Без пересборки APK упадёт при
загрузке UniFFI-биндингов.

## Задача
1. `git fetch && git checkout android && git pull` — свежий код.
2. Пересобрать Rust-ядро под Android (как обычно: `scripts/build_apk.ps1`
   или вручную `cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs build --release`
   из каталога `core/`).
3. Kotlin-биндинги `android/app/src/main/java/uniffi/sm_core/sm_core.kt`
   уже перегенерены и закоммичены — руками не трогать, они соответствуют
   текущему core.
4. Собрать APK, поставить на устройство.

## Что проверить после установки
- Вход в существующий аккаунт: устройство должно УЗНАТЬ себя как `primary`
  (identity совпадает с серверной директорией) — история и сессии живы.
- Свежая установка при живом аккаунте на другом устройстве: должно занять
  слот `android-xxxx`, БЕЗ ошибок про чужой ключ.
- Отправка 1:1: на сервере в `messages` должны появиться КОПИИ по числу
  устройств получателя (recipient_device_id + sender_device в конверте).
- Приём от iOS/web (у отправителя несколько устройств) — расшифровка
  различает устройства отправителя (сессии peer::device).

## Сервер (прод)
- https://YOUR-SERVER-HOST.nip.io , ssh root@YOUR_SERVER_IP (или алиас aether-vps по ключу)
- Код: /root/secure_messenger/server/main.py, рестарт: systemctl restart secure_messenger
- БД: sudo -u postgres psql secure_messenger
- Тестовые аккаунты: web_test_rm / Qa-test-7391-web (устройства primary + web-*)

## Грабли
- scp/SFTP на VPS отключён — только rsync или ssh+base64.
- fail2ban банит частые ssh-подключения: держать одну сессию (ControlMaster).
- Эндпоинты multi-device: GET /users/{id}/devices, /keys/claim/{id}?device_id=,
  /keys/upload {device_id}, /messages {target_device_id}, inbox/ack ?device_id=.
  Старые вызовы без device_id сервер маппит на устройство 'primary'.
- Коммиты без упоминания ИИ.
