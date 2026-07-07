# Aether — Secure Messenger (E2E)

Лёгкий защищённый мессенджер (Telegram-подобный UX). Сообщения **шифруются на
устройстве отправителя** и **расшифровываются только у получателя** — сервер
видит лишь ciphertext.

## Архитектура

| Компонент | Стек | Назначение |
|-----------|------|------------|
| `core/` | Rust + UniFFI | Общее ядро: крипта, wire-протокол, сетевой клиент, локальное хранилище (SQLite). Используется Android и iOS через сгенерированные биндинги |
| `server/main.py` | FastAPI + PostgreSQL | Relay: хранит публичные ключи и зашифрованные сообщения, не видит plaintext |
| `android/` | Kotlin + Jetpack Compose | Нативное Android-приложение |
| `ios/` | SwiftUI + Liquid Glass | Нативное iOS-приложение (см. [ios/README.md](ios/README.md)); переиспользует то же ядро |
| `web/` | Vanilla JS + WebCrypto/TweetNaCl | Браузерный клиент (PWA), протокол-совместим с остальными клиентами |

Единый протокол между клиентами описан в [WIRE_PROTOCOL.md](WIRE_PROTOCOL.md) —
один аккаунт работает в браузере, на Android и на iOS.

### Криптография
- Личные сообщения: `crypto_box` (Curve25519, XSalsa20-Poly1305).
- Группы: AES-GCM общим ключом, ключ роздан участникам через `crypto_box`.
- Ключи: случайная пара, приватный ключ зашифрован паролем (PBKDF2 100k + AES-GCM)
  и хранится на сервере как резервная копия.
- Медиа: AES-GCM, шифротекст в `/upload`, ключ — внутри зашифрованного сообщения.

## Дорожная карта

Цель — лёгкий нативный мессенджер на всех платформах с общим защищённым ядром
(модель TDLib):

1. **Общее ядро на Rust** (крипта + протокол + хранилище), биндинги через UniFFI.
2. **ПК-приложение** на Compose Multiplatform (полноценное нативное, не веб-обёртка).
3. Web остаётся браузерным клиентом, протокол-совместимым через то же ядро (WASM).

## Запуск сервера

```powershell
cd secure_messenger
pip install -r requirements.txt
.\scripts\run_server.ps1
```

Сервер слушает `0.0.0.0:8765`. Переменные окружения см. в начале `server/main.py`
(строка подключения к БД, `ALLOWED_ORIGINS` и т.д.) — задать под свой деплой.

## Ядро (Rust)

`core/` — общая крипта/протокол/хранилище для Android и iOS. Сборка под Android:
[`core/build_android.ps1`](core/build_android.ps1) (кладёт `.so` в `android/app/src/main/jniLibs`
и генерирует Kotlin-биндинги). Сборка под iOS: [`ios/build_core_ios.sh`](ios/build_core_ios.sh)
(на macOS — собирает XCFramework + Swift-биндинги).

## Android

Откройте папку [`android/`](android/) в Android Studio. Перед первой сборкой
выполните `core/build_android.ps1`, иначе не будет `.so` и Kotlin-биндингов ядра.
Сборка APK из консоли: [`scripts/build_apk.ps1`](scripts/build_apk.ps1)
(через PowerShell + gradlew; bat/cmd ломаются на кодировке).
Эмулятор обращается к серверу по `http://10.0.2.2:8765`.
В приложении задайте URL своего сервера на экране входа.

## iOS

Смотрите [`ios/README.md`](ios/README.md) — сборка возможна только на macOS
(Xcode) либо через готовый пайплайн GitHub Actions ([`.github/workflows/ios.yml`](.github/workflows/ios.yml)).

## Web

Статика в [`web/`](web/), отдаётся сервером. Тесты протокола: `node web/test_wire.js`.

## Безопасность

См. [SECURITY_REVIEW_P0-P2.md](SECURITY_REVIEW_P0-P2.md) и
[P6_TOFU_DESIGN.md](P6_TOFU_DESIGN.md) (TOFU-пиннинг ключей).
