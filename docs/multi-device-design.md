# Multi-device: дизайн (v1)

Цель: один аккаунт на N устройств, каждое со своим Olm-аккаунтом, без ломки
текущих iOS/Android/web клиентов.

## Принцип
Как в Signal/Matrix: **устройство = криптографическая единица**. Никакого шаринга
одного Olm-pickle между устройствами (гонки состояния ратчета). Сообщение
шифруется отдельно для каждого устройства получателя **и** каждого другого
устройства отправителя (self-devices получают копию для синка).

## Совместимость (главное, чтобы не сломать iOS/Android)
- Сервер: всё, что сейчас загружено через `/keys/upload` без device_id, считается
  устройством `device_id = "primary"`. Старые клиенты продолжают работать как есть.
- Старые эндпоинты (`/keys/claim/{user}`, `/messages` без device) остаются и
  маршрутизируют на `primary`. Новые клиенты используют device-aware версии.
- Wire-конверт не меняется; добавляются **опциональные** поля
  `sender_device`, `target_device`, `logical_message_id`. Старый клиент их игнорирует.
- Миграций данных нет: только новая таблица `devices` + backfill primary из users.

## Схема БД (server)
```
devices(user_id, device_id, identity_key_b64, created_at, last_seen,
        PRIMARY KEY (user_id, device_id))
one_time_keys: + колонка device_id DEFAULT 'primary'
messages:     + recipient_device_id DEFAULT 'primary',
              + sender_device_id DEFAULT 'primary',
              + logical_message_id (UUID, один на все копии)
```

## Server API (добавляется, ничего не удаляется)
- `POST /devices/register` → выдаёт device_id (или клиент генерит UUID сам)
- `GET  /users/{id}/devices` → список {device_id, identity_key_b64}
- `POST /keys/claim/{user}/{device}` → prekey конкретного устройства
- `PUT  /keys/upload` — принимает опциональный device_id
- inbox: выдаёт только сообщения своего device_id (+ legacy без device → primary)
- Дедупликация на клиенте по logical_message_id.

## Core (sm_core / ratchet-core) — делать ПЕРВЫМ
UniFFI-функции (те же для WASM):
- `device_new()` → {device_id, account_pickle, identity_key}
- сессии хранятся с ключом `(peer_user, peer_device)` вместо `peer_user`
- `encrypt_for_devices(plaintext, [(user, device, session)])` →
  [(user, device, envelope)] + общий logical_message_id
- История на новое устройство: НЕ переносим в v1 (как Signal). Новое устройство
  видит только новые сообщения. Экспорт истории — отдельная фича потом.

## Порядок работ
1. core: device-aware session store + encrypt_for_devices (+ тесты).
2. server: таблица devices, backfill primary, новые эндпоинты (+ тесты curl).
3. web: первый клиент на новом протоколе (быстрее всего итерировать).
4. iOS, затем Android: переключить на device-aware, primary остаётся их
   device_id — для них это прозрачно.
5. Только после этого: линковка нового устройства по QR (auth-токен + verify).

## Что НЕ делаем в v1 (YAGNI)
- Перенос истории на новое устройство.
- Cross-signing/цепочки доверия устройств (пока identity pin по каждому устройству).
- Ревокация с перешифровкой групп (просто удаляем device и перестаём слать).
