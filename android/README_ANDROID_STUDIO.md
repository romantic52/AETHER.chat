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

## URL сервера в приложении

| Где запускаете | Адрес в поле «Сервер» |
|----------------|------------------------|
| Эмулятор Android Studio | `http://10.0.2.2:8765` |
| Телефон в той же Wi‑Fi | `http://192.168.x.x:8765` (IP вашего ПК) |
| ПК + Kivy на том же ПК | `http://127.0.0.1:8765` |

Узнать IP: `ipconfig` → IPv4.

## Запуск

1. Запустите сервер на ПК
2. Android Studio → **Run** (зелёный треугольник) на эмуляторе или телефоне
3. PIN `1234`, ID `alice` → **Войти**
4. На втором устройстве/эмуляторе ID `bob`
5. Alice пишет bob → **Отправить**

## E2E на устройстве

- Шифрование: **libsodium** (`lazysodium-android`), тот же алгоритм, что PyNaCl `crypto_box`
- Приватный ключ: **EncryptedSharedPreferences** (Android)
- Сервер видит только `ciphertext`

## Сборка APK

**Build → Build Bundle(s) / APK(s) → Build APK(s)**

APK: `android/app/build/outputs/apk/debug/app-debug.apk`

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