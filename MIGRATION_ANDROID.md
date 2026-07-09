# Миграция Android на единое ядро (core v2)

Ядро объединено: за основу взята iOS-ветка (в ней критические фиксы — гонка
ack/потеря групповых сообщений, optimistic send, группы/медиа), Android-поверхность
(StoreListener, wire_encode/decode, эпохи ключей) возвращена. Правки клиента —
механические, почти все в `data/CoreStore.kt` и местах вызова крипты.

## Что осталось как было
- `WsClient` / `WsListener` — без изменений.
- `StoreListener` — те же `onMessagesChanged(peerId)` / `onChatsChanged()`, `setListener(...)`.
- `wireEncode` / `wireDecode` + `WireMessage` — на месте; enum расширен:
  новые варианты `Delete{target}`, `Delivered`; у `Media` добавлены поля
  `duration/fileName/fileSize/caption` (все опциональные — добавь в конструкторы).
- TOFU-пины: `pinGet/pinUpsert/pinSetVerified` (тип теперь `KeyPin`
  с полями `peerId/publicKeyB64/verified/firstSeen`; `pinUpsert(peerId, keyB64, firstSeen)`
  возвращает `Boolean` — «ключ изменился»).

## Переименования Store (было → стало)
| Было (v1)                    | Стало (v2)                                   |
|------------------------------|----------------------------------------------|
| `Store.open(path, encKey)`   | `CoreStore.open(path)` (at-rest ключ — см. ниже) |
| `getAllChats()`              | `getChatList()` (Chat включает last_text/last_ts/unread) |
| `getChat(peer)`              | нет прямого — фильтруй `getChatList()` или добавим по запросу |
| `insertChat(chat)`           | `upsertChat(chat)`                           |
| `getMessageByMsgId(id)`      | `getMessage(id)`                             |
| `deleteMessageByMsgId(id)`   | `markDeleted(id)` (мягкое удаление)          |
| `deleteChatAndMessages(p)`   | `deleteChat(p)`                              |
| `incrementUnread(p)`         | `touchChat(p, isGroup, title, lastText, ts, incUnread=true)` |
| `markOutgoingRead(p)`        | `markOutgoingStatus(p, 3)`                   |
| `markOutgoingDelivered(p)`   | `markOutgoingStatus(p, 2)`                   |
| `setAvatar(p, fid)`          | пока нет — храни в prefs или добавим колонку |
| `getMessagesForPeer(p)`      | `getMessagesForPeer(p, beforeTs=0, limit)` — теперь с пагинацией |

`StoredMessage` (v2): `id, peerId, outgoing, senderId, payloadJson, status, ts,
reactionsJson, edited, deleted`. Текст больше не отдельное поле — весь payload
единым wire-JSON (парсь `wireDecode(payloadJson)`).

## Крипта (было → стало)
| Было                                   | Стало                                              |
|----------------------------------------|-----------------------------------------------------|
| `boxEncrypt(text, myPriv, theirPub) -> Envelope` | `sealDirect(json, theirPub, myPub, myPriv) -> String` (готовый конверт) |
| `boxDecrypt(env, myPriv)`              | `openEnvelope(envelopeJson, myPriv, groupKey?) -> Opened` |
| —                                      | `openEnvelopeMulti(envelopeJson, myPriv, groupKeys)` — эпохи ротации |
| `aesEncrypt(plain, key) -> AesCiphertext` | `aesEncrypt(key, plain) -> Sealed{nonceB64, ciphertext}` |
| `aesDecrypt(key, nonce, ctB64)`        | `aesDecrypt(key, nonceB64, ciphertext: ByteArray)`  |
| `encrypt/decryptPrivateKey`            | без изменений                                        |
| `generateKeypair() -> KeyPair`         | `generateKeypair() -> Keypair` (те же поля)          |

## Новое, что Android получает бесплатно
- **Фикс потери групповых сообщений**: ack и курсор inbox двигаются только для
  успешно обработанных элементов (см. `fetchInbox`+`ack` и meta-ключ `inbox_since`).
- **Эпохи ключей групп**: `setGroupKey` при смене ключа заводит новую эпоху,
  `getGroupKeys` — все ключи для чтения истории, `openEnvelopeMulti` перебирает их.
- Optimistic send: `replaceMessageId(oldLocal, newServer, status)`.
- Медиа-пайплайн: `uploadMedia`/`downloadMedia` (шифрование в ядре), аватарки.
- `ApiClient.health()` — проверка self-hosted сервера; `setToken(token)`.

## At-rest шифрование БД
v1 шифровал тексты ключом из Keystore (`Store.open(path, encKey)`). В v2 пока
не перенесено — карточка «SQLCipher» на доске: сделаем шифрование целиком базы
единообразно для iOS (Keychain) и Android (Keystore). До этого локальная БД
на Android будет открытой — учитывай при релизе.
