# Android Studio — Secure Messenger

Нативное приложение Kotlin, **совместимо** с Python-сервером (`server/main.py`).

## Открыть проект

1. Android Studio → **File → Open**
2. Папка: `secure_messenger/android` (не весь Grok_Test)
3. Дождаться **Gradle Sync** (нужен интернет для зависимостей)

## Сервер на ПК

```powershell
cd secure_messenger
.\scripts\run_server.ps1
```

Сервер слушает `0.0.0.0:8765`.

## Серверы и пространства

На экране входа выберите «Пользовательские», введите HTTPS-адрес и нажмите
«Найти сервер». Клиент проверяет подписанный `/server/info` и запоминает
TOFU-отпечаток. При его изменении автоматический вход блокируется до двойного
подтверждения после сверки с владельцем сервера.

Пространство — это пара «сервер + аккаунт». Токены, E2E-ключи и локальные базы
разделены между пространствами; переключатель находится в заголовке списка
чатов. Для эмулятора локальный стенд по-прежнему доступен на
`http://10.0.2.2:8765`; открытый HTTP к другим адресам заблокирован.

## Запуск

1. Запустите сервер на ПК
2. Android Studio → **Run** (зелёный треугольник) на эмуляторе или телефоне
3. PIN `1234`, ID `alice` → **Войти**
4. На втором устройстве/эмуляторе ID `bob`
5. Alice пишет bob → **Отправить**

## E2E на устройстве

- Шифрование и локальное хранилище: общее Rust-ядро через UniFFI
- Приватный ключ: **EncryptedSharedPreferences** (Android)
- Сервер видит только `ciphertext`

## Сборка APK

На macOS сначала синхронизируйте нативные библиотеки и Kotlin-биндинги:

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/26.1.10909125"
./core/build_android.sh
```

**Build → Build Bundle(s) / APK(s) → Build APK(s)**

APK: `android/app/build/outputs/apk/debug/app-debug.apk`

Для TURN-реле добавьте локально в `local.properties` (файл не попадает в git):

```properties
aether.turnHost=<TURN_HOST>:3478
aether.turnUsername=<TURN_USERNAME>
aether.turnCredential=<TURN_CREDENTIAL>
```

Без этих значений звонки используют только публичный STUN.

## Требования

- Android Studio Hedgehog (2023.1+) или новее
- JDK 17
- Android SDK 34

## Kivy vs Android Studio

| | Kivy (`mobile/app.py`) | Android Studio (`android/`) |
|--|------------------------|-----------------------------|
| Сборка | Buildozer / Linux | Android Studio на Windows |
| Ключи | PIN + файл | EncryptedSharedPreferences |
| Рекомендуется | прототип | **да, если есть Android Studio** |
