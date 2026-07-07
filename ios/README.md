# AETHER · iOS

Нативная iOS-версия мессенджера AETHER на **SwiftUI** с настоящим **Liquid Glass**
(материал iOS 26), переиспользующая **то же Rust-ядро** (`../core`), что Android и web —
вся крипта и сетевой протокол едины, не дублируются.

> ⚠️ Собрать и запустить iOS-приложение можно **только на macOS** (Xcode). На Windows
> ведётся разработка кода; компиляция/симулятор/публикация — на Mac или в CI на
> macOS-раннере. Для настоящего Liquid Glass нужен **Xcode 26+ (iOS 26 SDK)**; на
> более старых системах интерфейс автоматически деградирует до `.ultraThinMaterial`.

## Архитектура

```
ios/
├─ project.yml               # источник правды проекта (XcodeGen → AETHER.xcodeproj)
├─ build_core_ios.sh         # сборка Rust-ядра в XCFramework + Swift-биндинги (на Mac)
├─ CoreFFI/                   # сюда ложится SmCoreFFI.xcframework (артефакт)
└─ AETHER/
   ├─ App/                   # точка входа, корневой роутер
   ├─ Theme/                 # Brand (палитра) + LiquidGlass (стеклянный слой)
   ├─ Core/                  # CoreClient (мост к Rust-ядру), Session, Keychain
   ├─ Components/            # AetherLogo, Avatar
   ├─ Features/
   │  ├─ Onboarding/         # Welcome (стартовый) + Auth (вход/регистрация)
   │  ├─ Home/               # таб-бар на стекле
   │  ├─ Chats/              # список чатов
   │  ├─ Chat/               # окно диалога
   │  └─ Settings/
   ├─ Resources/             # Info.plist, Assets.xcassets
   └─ CoreFFI/Generated/     # сюда ложится sm_core.swift (биндинги UniFFI)
```

Слои не дублируют логику Android — общий код живёт в `../core` (Rust). Swift-обёртка
`Core/CoreClient.swift` зовёт ровно те же функции ядра (`ApiClient`, `generateKeypair`,
`boxEncrypt`/`boxDecrypt`, `encryptPrivateKey`…), что и Kotlin через UniFFI.

## Сборка на Mac (с нуля)

```bash
# 1. инструменты (один раз)
brew install xcodegen
rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios

# 2. собрать ядро под iOS + сгенерировать Swift-биндинги
cd ios
./build_core_ios.sh
#   → CoreFFI/SmCoreFFI.xcframework
#   → AETHER/CoreFFI/Generated/sm_core.swift

# 3. сгенерировать Xcode-проект и открыть
xcodegen generate
open AETHER.xcodeproj

# 4. в Xcode: выбрать симулятор iPhone (iOS 26) → ⌘R
```

Порядок важен: `build_core_ios.sh` **до** `xcodegen generate` — без сгенерированного
`sm_core.swift` приложение не скомпилируется (нет символов ядра).

## Сборка без своего Mac (CI)

В `../.github/workflows/ios.yml` — пайплайн на macOS-раннере GitHub Actions: ставит
xcodegen, собирает ядро, генерирует проект и собирает приложение. Артефакт `.app`/`.ipa`
можно скачать из запуска. Запуск/«пощупать» собранное — через Appetize.io.

Нужен раннер с Xcode 26 (образ `macos-26`); пока он не доступен — пайплайн соберёт с
фолбэком на `.ultraThinMaterial` (Liquid Glass включится при апгрейде образа).

## Что уже есть

- ✅ Стартовый экран (Welcome) — знак AETHER, слоган, стеклянные кнопки.
- ✅ Вход / регистрация — флоу как на Android: ключи → шифрование приватного ключа → relay.
- ✅ Список чатов, окно диалога, настройки — каркас на Liquid Glass.
- ✅ Мост к Rust-ядру (крипта + relay-API) и сессия с Keychain.

## Дальше

- Подключить `core.fetchInbox` + локальное хранилище ядра (SQLite в Rust) → реальные диалоги.
- Реальная отправка: `boxEncrypt` → `sendMessage`, дешифровка входящих.
- WebSocket-realtime (ws-слой ядра), звонки (WebRTC), видео-кружки, группы.
- Иконка приложения, лаунч-скрин.

Сервер по умолчанию: `https://your-server.example.com` (тот же relay, что Android/web).
