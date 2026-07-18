// E2E Secure Messenger Web Client

// User/API data is never HTML. Keep this at the single boundary used by all
// small static templates below; textContent remains preferred for new UI.
function escapeHtmlSafe(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, ch => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[ch]));
}

function safeMediaUrl(value, kind = 'image') {
    const raw = String(value || '').trim();
    if (!raw) return '';
    if (raw.startsWith('blob:')) return raw;
    const prefix = kind === 'audio' ? 'audio/' : kind === 'video' ? 'video/' : 'image/';
    if (raw.startsWith(`data:${prefix}`) && raw.includes(';base64,')) return raw;
    return '';
}

function isTrustedHttpOrigin(url) {
    if (url.protocol !== 'https:' && url.protocol !== 'http:') return false;
    if (url.origin === window.location.origin) return true;
    try {
        return typeof serverUrl === 'string' && serverUrl && url.origin === new URL(serverUrl).origin;
    } catch (_) {
        return false;
    }
}

function safeDownloadUrl(value) {
    const raw = String(value || '').trim();
    if (!raw || raw.toLowerCase().startsWith('javascript:')) return '';
    if (raw.startsWith('blob:')) return raw;
    if (/^data:(?:image\/(?:png|jpeg|gif|webp)|audio\/[a-z0-9.+-]+|video\/[a-z0-9.+-]+|application\/octet-stream|text\/plain);base64,/i.test(raw)) return raw;
    try {
        const url = new URL(raw, window.location.href);
        return isTrustedHttpOrigin(url) ? url.href : '';
    } catch (_) {
        return '';
    }
}

function safeBlobMime(value, fallback = 'application/octet-stream') {
    const raw = String(value || '').trim().toLowerCase();
    return /^(?:image\/(?:png|jpeg|gif|webp)|audio\/[a-z0-9.+-]+|video\/[a-z0-9.+-]+|application\/(?:octet-stream|pdf)|text\/plain)$/.test(raw)
        ? raw : fallback;
}

function setSafeBackgroundImage(element, value) {
    const raw = String(value || '').trim();
    if (!raw) {
        element.style.backgroundImage = 'none';
        return;
    }
    try {
        const url = new URL(raw, window.location.href);
        const allowed = isTrustedHttpOrigin(url) || url.protocol === 'blob:' ||
            (url.protocol === 'data:' && /^data:image\/(?:png|jpeg|gif|webp);base64,/i.test(raw));
        element.style.backgroundImage = allowed ? `url(${JSON.stringify(url.href)})` : 'none';
    } catch (_) {
        element.style.backgroundImage = 'none';
    }
}

// Хелперы для URL-safe Base64 (совместимого с Python PyNaCl)
function base64UrlEncode(uint8Array) {
    let binary = '';
    const len = uint8Array.byteLength;
    for (let i = 0; i < len; i++) {
        binary += String.fromCharCode(uint8Array[i]);
    }
    const base64 = btoa(binary);
    return base64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function base64UrlDecode(str) {
    str = str.replace(/-/g, '+').replace(/_/g, '/');
    while (str.length % 4) {
        str += '=';
    }
    const binary = atob(str);
    const len = binary.length;
    const bytes = new Uint8Array(len);
    for (let i = 0; i < len; i++) {
        bytes[i] = binary.charCodeAt(i);
    }
    return bytes;
}

// --- Резервная копия приватного ключа: шифрование паролем ---
// Формат байт-в-байт совместим с Android E2ECrypto.encryptPrivateKey:
//   PBKDF2WithHmacSHA256(пароль, salt, 100000, 256) → AES-GCM(iv=12б, тег 128б)
//   результат: "<salt_b64>:<iv_b64>:<ciphertext_b64>" (url-safe base64).
// Шифруется именно base64-строка приватного ключа (как на Android), а не сырые байты.
async function deriveBackupKey(password, salt) {
    const enc = new TextEncoder();
    const km = await window.crypto.subtle.importKey(
        'raw', enc.encode(password), { name: 'PBKDF2' }, false, ['deriveKey']
    );
    return window.crypto.subtle.deriveKey(
        { name: 'PBKDF2', salt: salt, iterations: 100000, hash: 'SHA-256' },
        km, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']
    );
}

async function encryptPrivateKeyB64(privateKeyB64, password) {
    const salt = window.crypto.getRandomValues(new Uint8Array(16));
    const iv = window.crypto.getRandomValues(new Uint8Array(12));
    const key = await deriveBackupKey(password, salt);
    const ct = await window.crypto.subtle.encrypt(
        { name: 'AES-GCM', iv }, key, new TextEncoder().encode(privateKeyB64)
    );
    return `${base64UrlEncode(salt)}:${base64UrlEncode(iv)}:${base64UrlEncode(new Uint8Array(ct))}`;
}

async function decryptPrivateKeyB64(blob, password) {
    const parts = blob.split(':');
    if (parts.length !== 3) throw new Error('Неверный формат зашифрованного ключа');
    const salt = base64UrlDecode(parts[0]);
    const iv = base64UrlDecode(parts[1]);
    const ct = base64UrlDecode(parts[2]);
    const key = await deriveBackupKey(password, salt);
    const pt = await window.crypto.subtle.decrypt({ name: 'AES-GCM', iv }, key, ct);
    return new TextDecoder().decode(pt); // base64-строка приватного ключа
}

// --- Групповое шифрование (AES-GCM, совместимо с Android E2ECrypto.encryptFile) ---
// Групповой ключ — 32 случайных байта. Сообщение шифруется AES-GCM (IV 12б, тег 128б в конце).
async function aesGcmEncrypt(keyBytes, plaintextBytes) {
    const key = await window.crypto.subtle.importKey('raw', keyBytes, { name: 'AES-GCM' }, false, ['encrypt']);
    const iv = window.crypto.getRandomValues(new Uint8Array(12));
    const ct = await window.crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, plaintextBytes);
    return { nonce_b64: base64UrlEncode(iv), ciphertext_b64: base64UrlEncode(new Uint8Array(ct)) };
}

async function aesGcmDecrypt(keyBytes, ivBytes, ctBytes) {
    const key = await window.crypto.subtle.importKey('raw', keyBytes, { name: 'AES-GCM' }, false, ['decrypt']);
    const pt = await window.crypto.subtle.decrypt({ name: 'AES-GCM', iv: ivBytes }, key, ctBytes);
    return new Uint8Array(pt);
}

// Заворачивает групповой ключ box'ом для участника. Формат encrypted_key_b64 —
// JSON-строка {sender_pubkey_b64, nonce_b64, ciphertext_b64}, где зашифрована
// именно base64-строка ключа (как в Android MessageRepository.wrapGroupKeyFor).
function wrapGroupKey(symKeyBytes, recipientPubBytes) {
    const keyB64 = base64UrlEncode(symKeyBytes);
    const ptBytes = new TextEncoder().encode(keyB64);
    const nonce = nacl.randomBytes(nacl.box.nonceLength);
    const ct = nacl.box(ptBytes, nonce, recipientPubBytes, myKeys.secretKey);
    return JSON.stringify({
        sender_pubkey_b64: myKeys.publicB64,
        nonce_b64: base64UrlEncode(nonce),
        ciphertext_b64: base64UrlEncode(ct)
    });
}

// Разворачивает encrypted_key_b64 → сырые 32 байта ключа (или null).
function unwrapGroupKey(encryptedKeyB64) {
    const env = JSON.parse(encryptedKeyB64);
    const senderPub = base64UrlDecode(env.sender_pubkey_b64);
    const nonce = base64UrlDecode(env.nonce_b64);
    const ct = base64UrlDecode(env.ciphertext_b64);
    const ptBytes = nacl.box.open(ct, nonce, senderPub, myKeys.secretKey);
    if (!ptBytes) return null;
    return base64UrlDecode(new TextDecoder().decode(ptBytes));
}

// ──────────────────────────────────────────────────────────────────────────
// Адаптер единого wire-протокола (web ↔ Android). Канон — Android.
// См. WIRE_PROTOCOL.md. toWire вызывается перед шифрованием исходящего payload,
// fromWire — сразу после расшифровки входящего. UI веба продолжает работать со
// своим внутренним форматом (content / target_id / reply_to:{}), а на проводе
// летит канонический формат Android (text / target / reply_to_id+reply_to_text).
// Неизвестные/веб-родные типы (image, voice, video_msg, file, poll, pin, delete,
// poll_vote, webrtc, sync_sent) проходят насквозь без изменений — мультидевайс
// web↔web остаётся рабочим, а Android такие типы игнорирует (см. MessageRepository).
// ──────────────────────────────────────────────────────────────────────────
// Канонический media (Android) → внутренний дисплейный тип веба.
function mediaDisplayType(o) {
    const kind = o.kind || '';
    if (kind === 'voice') return 'voice';
    if (kind === 'video_msg') return 'video_msg';
    if (kind === 'image' || (o.mime_type || '').startsWith('image/')) return 'image';
    return 'file';
}

function toWire(p) {
    if (!p || typeof p !== 'object' || !p.type) return p;
    switch (p.type) {
        case 'text': {
            const w = { type: 'text', text: p.content != null ? p.content : '' };
            if (p.reply_to && p.reply_to.msg_id) {
                w.reply_to_id = p.reply_to.msg_id;
                if (p.reply_to.text != null) w.reply_to_text = p.reply_to.text;
            }
            if (p.fwd_from || p.forwarded_from) w.fwd_from = p.fwd_from || p.forwarded_from;
            if (p.ttl) w.ttl = p.ttl; // веб-расширение, Android игнорирует
            return w;
        }
        case 'edit':
            return { type: 'edit', target: p.target_id, text: p.content != null ? p.content : '' };
        case 'delete':
            return { type: 'delete', target: p.target_id || p.target || '' };
        case 'reaction':
            return { type: 'reaction', target: p.target_id, emoji: p.emoji != null ? p.emoji : '' };
        case 'read_receipt':
            // Канон: read помечает все исходящие прочитанными (per-message target теряется).
            return { type: 'read' };
        case 'image':
        case 'voice':
        case 'video_msg':
        case 'file': {
            // Медиа на /upload (новый формат). Легаси-инлайн (content=dataURL без .media)
            // оставляем как есть — Android его проигнорирует, web↔web ещё прочитает.
            if (!p.media || !p.media.file_id) return p;
            const w = {
                type: 'media',
                file_id: p.media.file_id,
                sym_key: p.media.sym_key,
                mime_type: p.media.mime_type,
                nonce: p.media.nonce
            };
            if (p.media.kind) w.kind = p.media.kind;
            if (p.media.duration) w.duration = p.media.duration;
            if (p.filename) w.filename = p.filename; // веб-расширение (Android игнорирует)
            if (p.size) w.size = p.size;             // веб-расширение
            if (p.fwd_from || p.forwarded_from) w.fwd_from = p.fwd_from || p.forwarded_from;
            return w;
        }
        default:
            return p;
    }
}

function fromWire(o) {
    if (!o || typeof o !== 'object' || !o.type) return o;
    switch (o.type) {
        case 'text': {
            const p = { type: 'text', content: o.text != null ? o.text : '' };
            if (o.reply_to_id) {
                p.reply_to = {
                    msg_id: typeof o.reply_to_id === 'string' || typeof o.reply_to_id === 'number' ? String(o.reply_to_id).slice(0, 256) : '',
                    text: typeof o.reply_to_text === 'string' ? o.reply_to_text.slice(0, 2000) : '',
                    author: '' // имя автора восстанавливается из локального сообщения при рендере
                };
            }
            if (typeof o.fwd_from === 'string' && o.fwd_from.length <= 128) {
                p.fwd_from = o.fwd_from;
                p.forwarded_from = o.fwd_from;
            }
            if (o.ttl) p.ttl = o.ttl;
            return p;
        }
        case 'edit':
            return { type: 'edit', target_id: typeof o.target === 'string' || typeof o.target === 'number' ? String(o.target).slice(0, 256) : '', content: o.text != null ? o.text : '' };
        case 'delete':
            return { type: 'delete', target_id: typeof (o.target || o.target_id) === 'string' || typeof (o.target || o.target_id) === 'number' ? String(o.target || o.target_id).slice(0, 256) : '' };
        case 'reaction':
            return { type: 'reaction', target_id: typeof o.target === 'string' || typeof o.target === 'number' ? String(o.target).slice(0, 256) : '', emoji: typeof o.emoji === 'string' ? o.emoji.slice(0, 32) : '' };
        case 'media': {
            // Нормализуем в дисплейный тип; контент (blob URL) резолвится лениво при рендере.
            const dt = mediaDisplayType(o);
            const p = {
                type: dt,
                media: {
                    file_id: typeof o.file_id === 'string' ? o.file_id.slice(0, 128) : '',
                    sym_key: typeof o.sym_key === 'string' ? o.sym_key.slice(0, 128) : '',
                    nonce: typeof o.nonce === 'string' ? o.nonce.slice(0, 128) : '',
                    mime_type: typeof o.mime_type === 'string' ? o.mime_type.slice(0, 128) : ''
                }
            };
            if (typeof o.kind === 'string') p.media.kind = o.kind.slice(0, 32);
            if (o.duration) p.media.duration = o.duration;
            if (dt === 'file') p.filename = typeof o.filename === 'string' ? o.filename.slice(0, 200) : 'Файл';
            if (o.size) p.size = o.size;
            if (typeof o.fwd_from === 'string' && o.fwd_from.length <= 128) {
                p.fwd_from = o.fwd_from;
                p.forwarded_from = o.fwd_from;
            }
            return p;
        }
        // 'read' и веб-родные типы возвращаем как есть — обрабатываются ниже по коду.
        default:
            return o;
    }
}

// Хелперы для локального шифрования (WebCrypto: PBKDF2 + AES-GCM)
function getSalt(userId) {
    const keyName = `salt_${userId}`;
    let saltHex = localStorage.getItem(keyName);
    if (saltHex) {
        const matches = saltHex.match(/.{1,2}/g);
        return new Uint8Array(matches.map(byte => parseInt(byte, 16)));
    }
    const salt = window.crypto.getRandomValues(new Uint8Array(16));
    const hex = Array.from(salt).map(b => b.toString(16).padStart(2, '0')).join('');
    localStorage.setItem(keyName, hex);
    return salt;
}

async function deriveKey(pin, salt) {
    const encoder = new TextEncoder();
    const pinBytes = encoder.encode(pin);
    const baseKey = await window.crypto.subtle.importKey(
        "raw",
        pinBytes,
        { name: "PBKDF2" },
        false,
        ["deriveKey"]
    );
    return window.crypto.subtle.deriveKey(
        {
            name: "PBKDF2",
            salt: salt,
            iterations: 100000,
            hash: "SHA-256"
        },
        baseKey,
        { name: "AES-GCM", length: 256 },
        false,
        ["encrypt", "decrypt"]
    );
}

async function encryptPayload(payload, pin, salt) {
    const key = await deriveKey(pin, salt);
    const encoder = new TextEncoder();
    const rawData = encoder.encode(JSON.stringify(payload));
    const iv = window.crypto.getRandomValues(new Uint8Array(12));
    const ciphertext = await window.crypto.subtle.encrypt(
        { name: "AES-GCM", iv: iv },
        key,
        rawData
    );
    const combined = new Uint8Array(iv.length + ciphertext.byteLength);
    combined.set(iv, 0);
    combined.set(new Uint8Array(ciphertext), iv.length);
    return btoa(String.fromCharCode.apply(null, combined));
}

async function decryptPayload(combinedB64, pin, salt) {
    try {
        const key = await deriveKey(pin, salt);
        const binary = atob(combinedB64);
        const len = binary.length;
        const combined = new Uint8Array(len);
        for (let i = 0; i < len; i++) {
            combined[i] = binary.charCodeAt(i);
        }
        const iv = combined.slice(0, 12);
        const ciphertext = combined.slice(12);
        const decrypted = await window.crypto.subtle.decrypt(
            { name: "AES-GCM", iv: iv },
            key,
            ciphertext
        );
        const decoder = new TextDecoder();
        return JSON.parse(decoder.decode(decrypted));
    } catch (e) {
        console.error("Local decryption failed:", e);
        return null;
    }
}

// ──────────────────────────────────────────────────────────────────────────
// Медиа-транспорт (единый с Android): шифрование AES-GCM → /upload → file_id,
// на приёме /download → расшифровка → blob URL. Формат байт-в-байт совместим
// с E2ECrypto.encryptFile (ключ 32б, IV 12б, тег 128б в конце, url-safe base64).
// ──────────────────────────────────────────────────────────────────────────
let mediaBlobCache = Object.create(null); // file_id -> objectURL (расшифрованный контент)
const MAX_MEDIA_BYTES = 50 * 1024 * 1024;

// Шифрует байты, грузит в /upload, возвращает {file_id, sym_key, nonce}.
async function uploadEncryptedMedia(bytes) {
    const symKey = window.crypto.getRandomValues(new Uint8Array(32));
    const enc = await aesGcmEncrypt(symKey, bytes); // {nonce_b64, ciphertext_b64}
    const ctBytes = base64UrlDecode(enc.ciphertext_b64);
    const form = new FormData();
    form.append('file', new Blob([ctBytes], { type: 'application/octet-stream' }), 'enc.bin');
    const res = await fetch(`${serverUrl}/upload`, {
        method: 'POST',
        headers: { 'Authorization': `Bearer ${sessionToken}`, 'Bypass-Tunnel-Reminder': 'true' },
        body: form
    });
    if (!res.ok) throw new Error('upload failed: ' + res.status);
    const data = await res.json();
    return { file_id: data.file_id, sym_key: base64UrlEncode(symKey), nonce: enc.nonce_b64 };
}

// Скачивает и расшифровывает медиа в objectURL (с кэшем по file_id).
async function getMediaBlobUrl(media) {
    if (!media || !media.file_id) return null;
    if (mediaBlobCache[media.file_id]) return mediaBlobCache[media.file_id];
    const res = await fetch(`${serverUrl}/download/${pathSegment(media.file_id)}`, {
        headers: { 'Authorization': `Bearer ${sessionToken}`, 'Bypass-Tunnel-Reminder': 'true' }
    });
    if (!res.ok) throw new Error('download failed: ' + res.status);
    const advertisedLength = Number(res.headers.get('content-length') || 0);
    if (advertisedLength > MAX_MEDIA_BYTES) throw new Error('media too large');
    const rawBytes = await res.arrayBuffer();
    if (rawBytes.byteLength > MAX_MEDIA_BYTES) throw new Error('media too large');
    const ctBytes = new Uint8Array(rawBytes);
    const plainBytes = await aesGcmDecrypt(base64UrlDecode(media.sym_key), base64UrlDecode(media.nonce), ctBytes);
    const url = URL.createObjectURL(new Blob([plainBytes], { type: safeBlobMime(media.mime_type) }));
    mediaBlobCache[media.file_id] = url;
    return url;
}

// Единая точка отправки медиа: грузит файл и шлёт нормализованный payload.
// displayType ∈ image|voice|video_msg|file; kind — канон Android (image|voice|video_msg|file|video).
async function sendMediaFile(bytes, mimeType, kind, displayType, extra = {}) {
    let up;
    try {
        up = await uploadEncryptedMedia(bytes);
    } catch (e) {
        console.error('media upload failed', e);
        showStatus('Не удалось загрузить файл', 'error');
        return;
    }
    // Предзаполняем кэш локальным контентом — отправителю не нужно качать заново.
    mediaBlobCache[up.file_id] = URL.createObjectURL(new Blob([bytes], { type: safeBlobMime(mimeType) }));
    const payload = Object.assign({
        type: displayType,
        media: { file_id: up.file_id, sym_key: up.sym_key, nonce: up.nonce, mime_type: mimeType, kind }
    }, extra);
    sendPayloadMessage(payload);
}

// Глобальные переменные состояния приложения
let myId = '';
let myPin = '';
let serverUrl = '';
let myKeys = null;
let serverPublicKeyB64 = '';
let groupKeys = Object.create(null); // group_id -> Uint8Array (32 bytes)
let messages = []; // [{ direction: 'in'|'out', peer: 'id', plaintext: '...', message_id: '...' }]
let chatSettingsCache = Object.create(null); // { peerId: { is_pinned, is_muted, is_archived } }
let isArchiveViewOpen = false;
let selectedPeer = null;
let pollInterval = null;
let pollCounter = 0;
let sessionToken = ''; // Токен сессии сервера
let loginEncPrivKeyB64 = ''; // Зашифрованный паролем бэкап приватного ключа (из ответа /users/login)
let loginEncOlmAccountB64 = '';
let olmAccountPickle = '';
let olmIdentityB64 = '';
let olmSessions = Object.create(null);
let olmIdentityPins = Object.create(null);
// Multi-device: этот браузер — отдельное криптоустройство аккаунта.
// 'primary' — только если аккаунт ещё нигде не имел Olm-ключей (легаси-совместимость).
let myDeviceId = '';
let peerDevicesCache = Object.create(null); // peerId -> {devices, ts}
let ratchetModulePromise = null;
let ratchetQueue = Promise.resolve();
let keyRotationInterval = null;

function authHeaders(extra = {}) {
    return Object.assign({
        'Authorization': `Bearer ${sessionToken}`,
        'Bypass-Tunnel-Reminder': 'true'
    }, extra);
}

function pathSegment(value) {
    return encodeURIComponent(String(value == null ? '' : value));
}

function nullMap(value) {
    return value && typeof value === 'object' && !Array.isArray(value)
        ? Object.assign(Object.create(null), value)
        : Object.create(null);
}

function formatServerError(data, fallback) {
    const detail = data && data.detail;
    if (typeof detail === 'string' && detail.trim()) return detail;
    if (Array.isArray(detail)) {
        const messages = detail.map(item => item && (item.msg || item.message)).filter(Boolean);
        if (messages.length) return messages.join('; ');
    }
    return fallback;
}

function loadRatchetApi() {
    if (!ratchetModulePromise) {
        ratchetModulePromise = import('./vendor/ratchet/aether_ratchet_wasm.js')
            .then(async module => {
                await module.default();
                return module;
            })
            .catch(error => {
                ratchetModulePromise = null;
                throw new Error(`Не удалось загрузить модуль Double Ratchet: ${error.message || error}`);
            });
    }
    return ratchetModulePromise;
}

function runRatchetSerial(task) {
    const next = ratchetQueue.then(task, task);
    ratchetQueue = next.catch(() => {});
    return next;
}

async function getPeerDevices(peerId, force = false) {
    peerId = peerId.toLowerCase();
    const cached = peerDevicesCache[peerId];
    if (!force && cached && Date.now() - cached.ts < 60_000) return cached.devices;
    const res = await fetch(`${serverUrl}/users/${pathSegment(peerId)}/devices`, { headers: authHeaders() });
    if (!res.ok) throw new Error('Не удалось получить список устройств собеседника');
    const devices = (await res.json()).devices || [];
    peerDevicesCache[peerId] = { devices, ts: Date.now() };
    return devices;
}

// Ключ сессии/пина: устройство primary хранится под старым ключом peerId,
// чтобы существующие локальные сессии не потерялись при апгрейде.
function deviceKey(peerId, deviceId) {
    return deviceId === 'primary' ? peerId : `${peerId}::${deviceId}`;
}

async function resolveMyDeviceId() {
    const stored = localStorage.getItem(`device_id_${myId}`);
    if (stored) { myDeviceId = stored; return; }
    const devices = await getPeerDevices(myId, true);
    // Пустой список = аккаунт ещё без Olm — занимаем primary (легаси-путь).
    myDeviceId = devices.length === 0 ? 'primary'
        : 'web-' + Array.from(crypto.getRandomValues(new Uint8Array(5)), b => b.toString(16).padStart(2, '0')).join('');
    localStorage.setItem(`device_id_${myId}`, myDeviceId);
}

function ratchetSlots(peerId) {
    const value = olmSessions[peerId];
    if (!value) return { inbound: null, outbound: null, current: null };
    if (typeof value === 'string') return { inbound: null, outbound: null, current: value };
    return {
        inbound: typeof value.inbound === 'string' ? value.inbound : null,
        outbound: typeof value.outbound === 'string' ? value.outbound : null,
        current: typeof value.current === 'string' ? value.current : null
    };
}

function saveRatchetSlots(peerId, slots) {
    const next = {};
    if (slots.inbound) next.inbound = slots.inbound;
    if (slots.outbound) next.outbound = slots.outbound;
    if (slots.current) next.current = slots.current;
    if (Object.keys(next).length) olmSessions[peerId] = next;
    else delete olmSessions[peerId];
}

async function saveRatchetState(updateServerBackup = false) {
    if (!olmAccountPickle || !myId || !myPin) return;
    const encrypted = await encryptPayload({
        account_pickle: olmAccountPickle,
        account_identity: olmIdentityB64,
        sessions: olmSessions,
        identity_pins: olmIdentityPins
    }, myPin, getSalt(myId));
    localStorage.setItem(`ratchet_${myId}`, encrypted);

    if (updateServerBackup && myDeviceId === 'primary') {
        // Бэкап на сервере один на аккаунт — его владелец только primary,
        // иначе web-устройство затёрло бы pickle телефона.
        const backup = await encryptPrivateKeyB64(olmAccountPickle, myPin);
        const res = await fetch(`${serverUrl}/users/me/olm-backup`, {
            method: 'PUT',
            headers: authHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({ encrypted_olm_account_b64: backup })
        });
        if (!res.ok) {
            console.error('Ratchet account backup failed:', res.status);
            return;
        }
        loginEncOlmAccountB64 = backup;
    }
}

async function prepareRatchetState(password) {
    const api = await loadRatchetApi();

    let localState = null;
    const stored = localStorage.getItem(`ratchet_${myId}`);
    if (stored) localState = await decryptPayload(stored, password, getSalt(myId));

    // Устройство: сохранённое → узнать себя по identity в директории →
    // primary, если аккаунт ещё без ключей → иначе новое web-устройство.
    if (!localStorage.getItem(`device_id_${myId}`) && localState && localState.account_identity) {
        try {
            const devices = await getPeerDevices(myId, true);
            const mine = devices.find(d => d.identity_key_b64 === localState.account_identity);
            if (mine) localStorage.setItem(`device_id_${myId}`, mine.device_id);
        } catch (_) {}
    }
    await resolveMyDeviceId();

    let serverAccount = '';
    // Серверный olm-бэкап принадлежит устройству primary; web-устройство живёт
    // только на своём локальном pickle.
    if (loginEncOlmAccountB64 && myDeviceId === 'primary') {
        serverAccount = await decryptPrivateKeyB64(loginEncOlmAccountB64, password);
    }

    let account = serverAccount || (localState && localState.account_pickle) || api.account_new();
    let identity = api.account_identity(account);
    if (localState && localState.account_pickle) {
        try {
            const localIdentity = api.account_identity(localState.account_pickle);
            if (localIdentity === identity) account = localState.account_pickle;
        } catch (_) {}
    }

    olmAccountPickle = account;
    olmIdentityB64 = api.account_identity(account);
    const sameAccount = localState && localState.account_identity === olmIdentityB64;
    olmSessions = sameAccount && localState.sessions && typeof localState.sessions === 'object'
        ? Object.assign(Object.create(null), localState.sessions) : Object.create(null);
    olmIdentityPins = sameAccount && localState.identity_pins && typeof localState.identity_pins === 'object'
        ? Object.assign(Object.create(null), localState.identity_pins) : Object.create(null);

    await ensureRatchetKeys();
}

async function ensureRatchetKeys() {
    const api = await loadRatchetApi();
    const countRes = await fetch(`${serverUrl}/keys/count?device_id=${pathSegment(myDeviceId)}`, { headers: authHeaders() });
    if (!countRes.ok) throw new Error('Не удалось проверить ключи аккаунта');
    const countData = await countRes.json();
    const serverIdentity = typeof countData.identity_key_b64 === 'string' ? countData.identity_key_b64 : '';
    if (serverIdentity && serverIdentity !== olmIdentityB64 && myDeviceId === 'primary') {
        // Чужой primary (телефон) перезаписывать нельзя — теряем его E2E.
        // Переходим в собственное web-устройство и продолжаем.
        myDeviceId = 'web-' + Array.from(crypto.getRandomValues(new Uint8Array(5)), b => b.toString(16).padStart(2, '0')).join('');
        localStorage.setItem(`device_id_${myId}`, myDeviceId);
    }
    const serverCount = Number(countData.count) || 0;
    let oneTimeKeys = {};
    let accountChanged = false;
    if (serverCount < 20) {
        const publish = JSON.parse(api.account_generate_otks(olmAccountPickle, Math.max(50 - serverCount, 20)));
        olmAccountPickle = publish.account_pickle;
        olmIdentityB64 = publish.identity_key_b64;
        oneTimeKeys = JSON.parse(publish.one_time_keys_json);
        accountChanged = true;
    }

    const uploadRes = await fetch(`${serverUrl}/keys/upload`, {
        method: 'PUT',
        headers: authHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ identity_key_b64: olmIdentityB64, one_time_keys: oneTimeKeys, device_id: myDeviceId })
    });
    if (!uploadRes.ok) throw new Error('Не удалось опубликовать prekeys');
    peerDevicesCache = Object.create(null);
    await saveRatchetState(accountChanged || !loginEncOlmAccountB64);
}

async function ratchetEnvelopeForDevice(peerId, deviceId, plaintext) {
    return runRatchetSerial(async () => {
        const api = await loadRatchetApi();
        const key = deviceKey(peerId, deviceId);
        const slots = ratchetSlots(key);
        let slot = slots.inbound ? 'inbound' : slots.outbound ? 'outbound' : 'current';
        let session = slots[slot];
        if (!session) {
            const claimRes = await fetch(
                `${serverUrl}/keys/claim/${pathSegment(peerId)}?device_id=${pathSegment(deviceId)}`,
                { method: 'POST', headers: authHeaders() });
            if (!claimRes.ok) {
                const data = await claimRes.json().catch(() => ({}));
                throw new Error(formatServerError(data, 'У получателя нет доступных prekeys'));
            }
            const bundle = await claimRes.json();
            session = api.create_outbound(
                olmAccountPickle,
                bundle.identity_key_b64,
                bundle.one_time_key.key_b64
            );
            const pinned = olmIdentityPins[key];
            if (pinned && pinned !== bundle.identity_key_b64) {
                throw new Error('Identity-ключ собеседника изменился');
            }
            olmIdentityPins[key] = bundle.identity_key_b64;
            slot = 'outbound';
        }

        const encrypted = JSON.parse(api.encrypt(session, plaintext));
        slots[slot] = encrypted.session_pickle;
        saveRatchetSlots(key, slots);
        await saveRatchetState();
        return {
            ratchet: '1',
            olm_identity: olmIdentityB64,
            sender_device: myDeviceId,
            type: encrypted.message_type,
            body_b64: encrypted.body_b64
        };
    });
}

async function openRatchetEnvelope(peerId, envelope) {
    return runRatchetSerial(async () => {
        const api = await loadRatchetApi();
        const identity = String(envelope.olm_identity || '');
        if (!identity) throw new Error('Identity-ключ собеседника отсутствует');
        // Устройство отправителя: из конверта (новые клиенты) или по identity
        // в директории устройств (легаси-конверты без sender_device).
        let senderDevice = String(envelope.sender_device || '');
        if (!senderDevice) {
            let devs = await getPeerDevices(peerId);
            let match = devs.find(d => d.identity_key_b64 === identity);
            if (!match) {
                devs = await getPeerDevices(peerId, true);
                match = devs.find(d => d.identity_key_b64 === identity);
            }
            senderDevice = match ? match.device_id : 'primary';
        }
        const key = deviceKey(peerId, senderDevice);
        const pinned = olmIdentityPins[key];
        if (pinned && pinned !== identity) {
            throw new Error('Identity-ключ собеседника изменился');
        }
        olmIdentityPins[key] = identity;

        let plaintext;
        const slots = ratchetSlots(key);
        const candidates = [['inbound', slots.inbound], ['outbound', slots.outbound], ['current', slots.current]];
        let lastError = null;
        for (const [slot, session] of candidates) {
            if (!session) continue;
            try {
                const result = JSON.parse(api.decrypt(session, Number(envelope.type), envelope.body_b64));
                slots[slot] = result.session_pickle;
                saveRatchetSlots(key, slots);
                plaintext = result.plaintext;
                break;
            } catch (error) {
                lastError = error;
            }
        }
        if (plaintext == null) {
            if (Number(envelope.type) !== 0) {
                throw lastError || new Error('Нет сессии для normal Ratchet-сообщения');
            }
            const result = JSON.parse(api.create_inbound(
                olmAccountPickle,
                identity,
                envelope.body_b64
            ));
            olmAccountPickle = result.account_pickle;
            slots.inbound = result.session_pickle;
            saveRatchetSlots(key, slots);
            plaintext = result.plaintext;
            await saveRatchetState(true);
            try { await ensureRatchetKeys(); }
            catch (refillError) { console.error('Prekey refill failed:', refillError); }
            return plaintext;
        }
        await saveRatchetState();
        return plaintext;
    });
}

// Realtime (WebSocket) state — typing-индикатор, presence, мгновенная доставка
let realtimeWs = null;
let wsReconnectTimer = null;
let wsPingTimer = null;
let typingTimeouts = Object.create(null); // peerId -> timeout, очищающий статус "печатает..."
let lastTypingSent = 0;

// Self-destruct: TTL для следующих сообщений в активном чате (секунды, 0 = выкл)
let selfDestructTtl = 0;

// WebRTC State
let peerConnection = null;
let localStream = null;
let currentFacingMode = 'user';
let remoteStream = null;
let isCallIncoming = false;
let callWithPeer = null;
let callTimerInterval = null;
let callSeconds = 0;
let remoteIceCandidatesQueue = [];

// Chat States
let editingMsgId = null;
let replyToMsgId = null;

// Speakerphone and custom contacts helper functions
let speakerphoneOn = false;

function getCustomContactNames() {
    try {
        if (!myId) return Object.create(null);
        const stored = localStorage.getItem(`contacts_custom_names_${myId}`);
        const parsed = stored ? JSON.parse(stored) : {};
        return parsed && typeof parsed === 'object' ? Object.assign(Object.create(null), parsed) : Object.create(null);
    } catch (e) {
        return Object.create(null);
    }
}

function saveCustomContactName(userId, name) {
    if (!userId || !myId) return;
    userId = userId.toLowerCase().trim();
    const names = getCustomContactNames();
    if (name && name.trim()) {
        names[userId] = name.trim();
    } else {
        delete names[userId];
    }
    localStorage.setItem(`contacts_custom_names_${myId}`, JSON.stringify(names));
}

function getCustomContactsList() {
    try {
        if (!myId) return [];
        const stored = localStorage.getItem(`contacts_custom_list_${myId}`);
        return stored ? JSON.parse(stored) : [];
    } catch (e) {
        return [];
    }
}

function addCustomContact(userId, customName) {
    if (!userId || !myId) return;
    userId = userId.toLowerCase().trim();
    const list = getCustomContactsList();
    if (!list.includes(userId)) {
        list.push(userId);
        localStorage.setItem(`contacts_custom_list_${myId}`, JSON.stringify(list));
    }
    if (customName) {
        saveCustomContactName(userId, customName);
    }
}

function removeCustomContact(userId) {
    if (!userId || !myId) return;
    userId = userId.toLowerCase().trim();
    
    // Remove name
    saveCustomContactName(userId, null);
    
    // Remove from custom list
    let list = getCustomContactsList();
    list = list.filter(u => u !== userId);
    localStorage.setItem(`contacts_custom_list_${myId}`, JSON.stringify(list));
}

function getContactDisplayName(userId) {
    if (typeof userId !== 'string') return '';
    userId = userId.toLowerCase().trim();
    if (userId === myId) return 'Избранное';
    const names = getCustomContactNames();
    if (Object.prototype.hasOwnProperty.call(names, userId) && typeof names[userId] === 'string' && names[userId]) return names[userId];
    const prof = profileCache[userId];
    if (prof && typeof prof.display_name === 'string' && prof.display_name) return prof.display_name;
    return userId;
}

// DOM Элементы
const loginScreen = document.getElementById('login-screen');
const chatScreen = document.getElementById('chat-screen');
const loginBtn = document.getElementById('login-btn');
const registerBtn = document.getElementById('register-btn');
const logoutBtn = document.getElementById('logout-btn');
const loginStatus = document.getElementById('login-status');
const serverInput = document.getElementById('server-input');
const changeServerBtn = document.getElementById('change-server-btn');
const usernameInput = document.getElementById('username-input');
const passwordInput = document.getElementById('password-input');

const myIdDisplay = document.getElementById('my-id-display');
const myAvatar = document.getElementById('my-avatar');
const peerInput = document.getElementById('peer-input');
const addPeerBtn = document.getElementById('add-peer-btn'); // may not exist anymore
const contactsContainer = document.getElementById('contacts-container');
const globalUsersContainer = document.getElementById('global-users-container');

const noChatSelected = document.getElementById('no-chat-selected');
const activeChatWindow = document.getElementById('active-chat-window');
const activePeerDisplay = document.getElementById('active-peer-display');
const peerAvatar = document.getElementById('peer-avatar');
const peerStatusEl = document.getElementById('peer-status');
const messagesContainer = document.getElementById('messages-container');
const messageInput = document.getElementById('message-input');
const sendBtn = document.getElementById('send-btn');

// Message ids come from the server/peer. Never interpolate them into a CSS
// selector: a malformed id can make querySelector throw or consume excessive
// parser work. A tiny data-attribute scan is slower only for the already
// rendered chat and keeps the lookup fail-closed.
function findMessageElement(messageId) {
    const wanted = String(messageId == null ? '' : messageId);
    if (!wanted || !messagesContainer) return null;
    return Array.from(messagesContainer.querySelectorAll('.tg-msg-wrapper'))
        .find(element => element.dataset.id === wanted) || null;
}

// Новые UI элементы Telegram-like
const burgerBtn = document.getElementById('burger-btn');
const sideDrawer = document.getElementById('side-drawer');
const drawerOverlay = document.getElementById('drawer-overlay');
const searchToggleBtn = document.getElementById('search-toggle-btn');
const searchBarContainer = document.getElementById('search-bar-container');
const chatAreaView = document.getElementById('chat-area-view');
const backToListBtn = document.getElementById('back-to-list-btn');
const myUsernameDisplay = document.querySelector('.drawer-text span');

// DOM Elements for Delete Modal & Reply Preview
const deleteConfirmModal = document.getElementById('delete-confirm-modal');
const deleteForMeBtn = document.getElementById('delete-for-me-btn');
const deleteForEveryoneBtn = document.getElementById('delete-for-everyone-btn');
const closeDeleteModalBtn = document.getElementById('close-delete-modal-btn');
const replyPreviewContainer = document.getElementById('reply-preview-container');
const replyPreviewAuthor = document.getElementById('reply-preview-author');
const replyPreviewText = document.getElementById('reply-preview-text');
const replyCloseBtn = document.getElementById('reply-close-btn');

// Настройки
const settingsBtn = document.getElementById('settings-btn');
const settingsModal = document.getElementById('settings-modal');
const closeSettingsBtn = document.getElementById('close-settings-btn');
const avatarInput = document.getElementById('avatar-input');
const uploadAvatarBtn = document.getElementById('upload-avatar-btn');
const settingsAvatarPreview = document.getElementById('settings-avatar-preview');
const settingsNameInput = document.getElementById('settings-name-input');
const settingsUsernameInput = document.getElementById('settings-username-input');
const saveSettingsBtn = document.getElementById('save-settings-btn');
const settingsStatus = document.getElementById('settings-status');

// Тема, добавление контакта, профиль, пересылка, шторка прикрепления файлов
const settingsThemeSelect = document.getElementById('settings-theme-select');
const addContactDrawerBtn = document.getElementById('add-contact-drawer-btn');
const addContactModal = document.getElementById('add-contact-modal');
const closeAddContactBtn = document.getElementById('close-add-contact-btn');
const addContactIdInput = document.getElementById('add-contact-id-input');
const addContactNameInput = document.getElementById('add-contact-name-input');
const saveNewContactBtn = document.getElementById('save-new-contact-btn');
const addContactStatus = document.getElementById('add-contact-status');

const profileModal = document.getElementById('profile-modal');
const closeProfileBtn = document.getElementById('close-profile-btn');
const profileAvatarDisplay = document.getElementById('profile-avatar-display');
const profileIdLabel = document.getElementById('profile-id-label');
const profileUsernameLabel = document.getElementById('profile-username-label');
const profileDisplaynameLabel = document.getElementById('profile-displayname-label');
const contactCustomNameInput = document.getElementById('contact-custom-name-input');
const saveContactNameBtn = document.getElementById('save-contact-name-btn');
const deleteContactNameBtn = document.getElementById('delete-contact-name-btn');

const forwardModal = document.getElementById('forward-modal');
const closeForwardBtn = document.getElementById('close-forward-btn');
const forwardContactsList = document.getElementById('forward-contacts-list');
const ctxForward = document.getElementById('ctx-forward');

const attachmentDrawer = document.getElementById('attachment-drawer');
const attachmentGalleryScroll = document.getElementById('attachment-gallery-scroll');
const attachGalleryBtn = document.getElementById('attach-gallery-btn');
const attachFileBtn = document.getElementById('attach-file-btn');
const attachLocationBtn = document.getElementById('attach-location-btn');
const attachContactBtn = document.getElementById('attach-contact-btn');

const callSpeakerBtn = document.getElementById('call-speaker-btn');

let myProfile = { username: '', display_name: '', avatar_data: '' };
let profileCache = Object.create(null); // peerId -> profile data

const tabLogin = document.getElementById('tab-login');
const tabRegister = document.getElementById('tab-register');

// WebRTC Elements
const audioCallBtn = document.getElementById('audio-call-btn');
const videoCallBtn = document.getElementById('video-call-btn');
const callScreen = document.getElementById('call-screen');
const localVideo = document.getElementById('local-video');
const remoteVideo = document.getElementById('remote-video');
const callPeerName = document.getElementById('call-peer-name');
const callStatus = document.getElementById('call-status');
const callIncomingControls = document.getElementById('call-incoming-controls');
const callActiveControls = document.getElementById('call-active-controls');
const callAcceptBtn = document.getElementById('call-accept-btn');
const callDeclineBtn = document.getElementById('call-decline-btn');
const callMuteBtn = document.getElementById('call-mute-btn');
const callEndBtn = document.getElementById('call-end-btn');
const callVideoBtn = document.getElementById('call-video-btn');
const callFlipCameraBtn = document.getElementById('call-flip-camera-btn');
const recordingFlipBtn = document.getElementById('recording-flip-btn');

// Обработчики входа/выхода
if(loginBtn) loginBtn.addEventListener('click', performLogin);
if(registerBtn) registerBtn.addEventListener('click', performRegister);
if(logoutBtn) logoutBtn.addEventListener('click', logout);
if(peerInput) {
    let searchTimeout;
    peerInput.addEventListener('input', (e) => {
        clearTimeout(searchTimeout);
        const q = e.target.value.trim();
        if (!q) {
            renderContactsList();
            return;
        }
        searchTimeout = setTimeout(async () => {
            try {
                const res = await fetch(`${serverUrl}/users/search?q=${encodeURIComponent(q)}`, {
                    headers: { 
                        'Bypass-Tunnel-Reminder': 'true',
                        'Authorization': `Bearer ${sessionToken}`
                    }
                });
                let users = [];
                let remoteGroups = [];
                if (res.ok) {
                    const data = await res.json();
                    users = data.users || [];
                    remoteGroups = data.groups || [];
                }
                const localGroupMatches = Object.values(myGroupsCache).filter(g => 
                    g.name.toLowerCase().includes(q.toLowerCase()) || 
                    g.id.toLowerCase().includes(q.toLowerCase())
                );
                
                // Merge remote and local groups, avoiding duplicates
                const combinedGroupsMap = new Map();
                localGroupMatches.forEach(g => combinedGroupsMap.set(g.id.toLowerCase(), g));
                remoteGroups.forEach(g => {
                    if (!combinedGroupsMap.has(g.id.toLowerCase())) {
                        combinedGroupsMap.set(g.id.toLowerCase(), g);
                    }
                });
                
                renderSearchResults(users, Array.from(combinedGroupsMap.values()));
            } catch (e) {}
        }, 300);
    });
}
if(sendBtn) sendBtn.addEventListener('click', sendMessage);
if(messageInput) {
    messageInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
            e.preventDefault();
            sendMessage();
        }
    });
}

// Обработчики нового UI
if(burgerBtn) burgerBtn.addEventListener('click', () => {
    sideDrawer.classList.remove('hidden');
    drawerOverlay.classList.remove('hidden');
    sideDrawer.classList.add('open');
    drawerOverlay.classList.add('visible');
});
if(drawerOverlay) drawerOverlay.addEventListener('click', () => {
    sideDrawer.classList.remove('open');
    drawerOverlay.classList.remove('visible');
});
if(searchToggleBtn) searchToggleBtn.addEventListener('click', () => {
    searchBarContainer.classList.toggle('hidden');
    if(!searchBarContainer.classList.contains('hidden')) peerInput.focus();
});
if(backToListBtn) backToListBtn.addEventListener('click', () => {
    // Сначала запускаем слайд-аут, чат остаётся видимым во время анимации,
    // и только по её завершении сбрасываем состояние — без мигания пустого экрана.
    chatAreaView.classList.remove('mobile-open');
    renderContactsList();
    const cleanup = () => {
        chatAreaView.removeEventListener('transitionend', cleanup);
        if (!chatAreaView.classList.contains('mobile-open')) {
            selectedPeer = null;
            noChatSelected.classList.remove('hidden');
            activeChatWindow.classList.add('hidden');
            renderContactsList();
        }
    };
    chatAreaView.addEventListener('transitionend', cleanup);
    setTimeout(cleanup, 400); // fallback, если transitionend не сработал
});

if(audioCallBtn) audioCallBtn.addEventListener('click', () => initiateCall(false));
if(videoCallBtn) videoCallBtn.addEventListener('click', () => initiateCall(true));
if(callAcceptBtn) callAcceptBtn.addEventListener('click', acceptCall);
if(callDeclineBtn) callDeclineBtn.addEventListener('click', declineCall);
if(callEndBtn) callEndBtn.addEventListener('click', endCall);
if(callMuteBtn) callMuteBtn.addEventListener('click', toggleMute);
if(callVideoBtn) callVideoBtn.addEventListener('click', toggleVideo);

if(callFlipCameraBtn) {
    callFlipCameraBtn.addEventListener('click', async () => {
        if (!localStream) return;
        const videoTrack = localStream.getVideoTracks()[0];
        if (!videoTrack) return; // Audio only
        
        currentFacingMode = currentFacingMode === 'user' ? 'environment' : 'user';
        try {
            const newStream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: currentFacingMode } });
            const newVideoTrack = newStream.getVideoTracks()[0];
            
            localStream.removeTrack(videoTrack);
            localStream.addTrack(newVideoTrack);
            videoTrack.stop();
            
            const localVideo = document.getElementById('local-video');
            if (localVideo) localVideo.srcObject = localStream;
            
            if (peerConnection) {
                const sender = peerConnection.getSenders().find(s => s.track && s.track.kind === 'video');
                if (sender) sender.replaceTrack(newVideoTrack);
            }
        } catch (e) {
            console.error("Flip camera error:", e);
        }
    });
}

if(recordingFlipBtn) {
    recordingFlipBtn.addEventListener('click', async () => {
        if (!isRecordingVideo || !recordingStream) return;
        const videoTrack = recordingStream.getVideoTracks()[0];
        if (!videoTrack) return;
        
        currentFacingMode = currentFacingMode === 'user' ? 'environment' : 'user';
        try {
            const newStream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: currentFacingMode, width: 320, height: 320 } });
            const newVideoTrack = newStream.getVideoTracks()[0];
            
            recordingStream.removeTrack(videoTrack);
            recordingStream.addTrack(newVideoTrack);
            videoTrack.stop();
            
            const previewVideo = document.getElementById('recording-video-preview');
            if (previewVideo) previewVideo.srcObject = recordingStream;
        } catch (e) {
            console.error("Flip circle camera error:", e);
        }
    });
}

// Настройки
const savedMessagesBtn = document.getElementById('saved-messages-btn');
if(savedMessagesBtn) savedMessagesBtn.addEventListener('click', () => {
    sideDrawer.classList.remove('open');
    drawerOverlay.classList.remove('visible');
    selectContact(myId);
});

if(settingsBtn) settingsBtn.addEventListener('click', () => {
    sideDrawer.classList.remove('open');
    drawerOverlay.classList.remove('visible');
    settingsModal.classList.remove('hidden');
    loadProfileToSettings();
});
if(closeSettingsBtn) closeSettingsBtn.addEventListener('click', () => {
    settingsModal.classList.add('hidden');
    settingsStatus.textContent = '';
});
if(uploadAvatarBtn) uploadAvatarBtn.addEventListener('click', () => avatarInput.click());
if(avatarInput) avatarInput.addEventListener('change', handleAvatarSelect);
if(saveSettingsBtn) saveSettingsBtn.addEventListener('click', saveSettingsToServer);

function loadProfileToSettings() {
    settingsNameInput.value = myProfile.display_name || '';
    settingsUsernameInput.value = myProfile.username || '';
    if (myProfile.avatar_data) {
        settingsAvatarPreview.textContent = '';
        setSafeBackgroundImage(settingsAvatarPreview, myProfile.avatar_data);
    } else {
        settingsAvatarPreview.textContent = myProfile.display_name ? myProfile.display_name.charAt(0).toUpperCase() : myId.charAt(0).toUpperCase();
        settingsAvatarPreview.style.backgroundImage = 'none';
    }
}

function handleAvatarSelect(e) {
    const file = e.target.files[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = function(event) {
        settingsAvatarPreview.textContent = '';
        setSafeBackgroundImage(settingsAvatarPreview, event.target.result);
        myProfile.avatar_data = event.target.result;
    };
    reader.readAsDataURL(file);
}

async function saveSettingsToServer() {
    saveSettingsBtn.disabled = true;
    settingsStatus.textContent = 'Сохранение...';
    settingsStatus.className = 'tg-status';
    
    myProfile.display_name = settingsNameInput.value.trim();
    myProfile.username = settingsUsernameInput.value.trim().toLowerCase();
    
    try {
        const res = await fetch(`${serverUrl}/users/me/profile`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${sessionToken}`, 'Bypass-Tunnel-Reminder': 'true' },
            body: JSON.stringify(myProfile)
        });
        if (res.ok) {
            settingsStatus.textContent = 'Успешно сохранено!';
            settingsStatus.classList.add('success');
            setTimeout(() => {
                settingsModal.classList.add('hidden');
                settingsStatus.textContent = '';
            }, 1000);
            updateMyDrawerProfile();
            renderContactsList();
        } else {
            const data = await res.json().catch(()=>({}));
            settingsStatus.textContent = formatServerError(data, 'Ошибка сохранения');
            settingsStatus.classList.add('error');
        }
    } catch (e) {
        settingsStatus.textContent = 'Ошибка сети';
        settingsStatus.classList.add('error');
    }
    saveSettingsBtn.disabled = false;
}

function updateActiveChatHeader(peerId) {
    const audioCallBtn = document.getElementById('audio-call-btn');
    const videoCallBtn = document.getElementById('video-call-btn');
    const chatInputContainer = document.querySelector('.tg-chat-input');
    let readonlyBanner = document.getElementById('readonly-banner');
    
    if (peerId === myId) {
        activePeerDisplay.textContent = 'Избранное';
        peerAvatar.textContent = '';
        peerAvatar.style.backgroundImage = 'none';
        peerAvatar.innerHTML = '<i class="fas fa-bookmark" style="font-size: 1.2rem; display:flex; align-items:center; justify-content:center; width:100%; height:100%;"></i>';
        if (peerStatusEl) { peerStatusEl.textContent = ''; peerStatusEl.className = 'tg-peer-status'; }
        const delBtn = document.getElementById('delete-chat-btn');
        if (delBtn) delBtn.classList.add('hidden');
        if (groupManageBtn) groupManageBtn.classList.add('hidden');
        if (audioCallBtn) audioCallBtn.classList.add('hidden');
        if (videoCallBtn) videoCallBtn.classList.add('hidden');
        if (chatInputContainer) chatInputContainer.classList.remove('hidden');
        if (readonlyBanner) readonlyBanner.classList.add('hidden');
        return;
    }
    const dName = getContactDisplayName(peerId);
    activePeerDisplay.textContent = dName;
    const prof = profileCache[peerId] || {};
    
    const delBtn = document.getElementById('delete-chat-btn');
    if (delBtn) {
        delBtn.classList.remove('hidden');
        if (myGroupsCache[peerId] && myGroupsCache[peerId].owner_id !== myId) {
            delBtn.title = "Покинуть группу";
            delBtn.innerHTML = '<i class="fas fa-sign-out-alt"></i>';
        } else {
            delBtn.title = "Удалить чат";
            delBtn.innerHTML = '<i class="fas fa-trash-alt"></i>';
        }
    }
    
    if (myGroupsCache[peerId]) {
        peerAvatar.innerHTML = myGroupsCache[peerId].is_channel ? '<i class="fas fa-bullhorn"></i>' : '<i class="fas fa-users"></i>';
        peerAvatar.style.backgroundImage = 'none';
        if (audioCallBtn) audioCallBtn.classList.add('hidden');
        if (videoCallBtn) videoCallBtn.classList.add('hidden');
        
        const isChannel = myGroupsCache[peerId].is_channel;
        const role = myGroupsCache[peerId].role;
        if (isChannel && role !== 'admin') {
            if (chatInputContainer) chatInputContainer.classList.add('hidden');
            if (!readonlyBanner) {
                readonlyBanner = document.createElement('div');
                readonlyBanner.id = 'readonly-banner';
                readonlyBanner.style.padding = '15px';
                readonlyBanner.style.textAlign = 'center';
                readonlyBanner.style.color = 'var(--text-secondary)';
                readonlyBanner.style.background = 'var(--bg-color)';
                readonlyBanner.style.borderTop = '1px solid var(--border-color)';
                readonlyBanner.textContent = 'Только администраторы могут писать сообщения';
                chatInputContainer.parentNode.insertBefore(readonlyBanner, chatInputContainer.nextSibling);
            }
            readonlyBanner.classList.remove('hidden');
        } else {
            if (chatInputContainer) chatInputContainer.classList.remove('hidden');
            if (readonlyBanner) readonlyBanner.classList.add('hidden');
        }
    } else {
        if (prof.avatar_data) {
            peerAvatar.innerHTML = '';
            setSafeBackgroundImage(peerAvatar, prof.avatar_data);
        } else {
            peerAvatar.innerHTML = dName.charAt(0).toUpperCase();
            peerAvatar.style.backgroundImage = 'none';
        }
        if (audioCallBtn) audioCallBtn.classList.remove('hidden');
        if (videoCallBtn) videoCallBtn.classList.remove('hidden');
        if (chatInputContainer) chatInputContainer.classList.remove('hidden');
        if (readonlyBanner) readonlyBanner.classList.add('hidden');
    }
    
    // Online status
    if (peerStatusEl) {
        updatePeerStatus(peerId, prof);
    }
    
    // Manage group button visibility
    if (groupManageBtn) {
        if (myGroupsCache[peerId]) {
            groupManageBtn.classList.remove('hidden');
            // Fetch member count
            fetch(`${serverUrl}/groups/${pathSegment(peerId)}/members`, {
                headers: { 'Authorization': `Bearer ${sessionToken}`, 'Bypass-Tunnel-Reminder': 'true' }
            }).then(r => r.json()).then(data => {
                if (data.count && peerStatusEl) {
                    peerStatusEl.textContent = `${data.count} ${myGroupsCache[peerId].is_channel ? 'подписчиков' : 'участников'}`;
                }
            }).catch(e => console.error(e));
        } else {
            groupManageBtn.classList.add('hidden');
        }
    }
}

function updatePeerStatus(peerId, prof) {
    if (!peerStatusEl) return;

    // Если собеседник прямо сейчас печатает — не затираем индикатор
    if (peerId && typingTimeouts[peerId.toLowerCase()]) {
        peerStatusEl.textContent = 'печатает...';
        peerStatusEl.className = 'tg-peer-status typing';
        return;
    }

    if (myGroupsCache[peerId]) {
        peerStatusEl.textContent = myGroupsCache[peerId].is_channel ? 'Канал' : 'Группа';
        peerStatusEl.className = 'tg-peer-status';
        return;
    }
    
    if (!prof || !prof.last_active) {
        peerStatusEl.textContent = '';
        peerStatusEl.className = 'tg-peer-status';
        return;
    }
    const lastActive = new Date(prof.last_active);
    const now = new Date();
    const diffSec = (now.getTime() - lastActive.getTime()) / 1000;
    if (diffSec < 35) {
        peerStatusEl.textContent = 'в сети';
        peerStatusEl.className = 'tg-peer-status online';
    } else {
        const h = lastActive.getHours().toString().padStart(2, '0');
        const m = lastActive.getMinutes().toString().padStart(2, '0');
        const today = new Date();
        const isToday = lastActive.toDateString() === today.toDateString();
        if (isToday) {
            peerStatusEl.textContent = `был(а) в ${h}:${m}`;
        } else {
            const d = lastActive.getDate().toString().padStart(2, '0');
            const mo = (lastActive.getMonth() + 1).toString().padStart(2, '0');
            peerStatusEl.textContent = `был(а) ${d}.${mo} в ${h}:${m}`;
        }
        peerStatusEl.className = 'tg-peer-status offline';
    }
}

function updateMyDrawerProfile() {
    myIdDisplay.textContent = myProfile.display_name || myId;
    myUsernameDisplay.textContent = myProfile.username ? '@' + myProfile.username : '@secure';
    if (myProfile.avatar_data) {
        myAvatar.textContent = '';
        setSafeBackgroundImage(myAvatar, myProfile.avatar_data);
    } else {
        myAvatar.textContent = (myProfile.display_name || myId).charAt(0).toUpperCase();
        myAvatar.style.backgroundImage = 'none';
    }
}

async function fetchMyProfile() {
    try {
        const res = await fetch(`${serverUrl}/users/${pathSegment(myId)}/profile`, { headers: authHeaders() });
        if (res.ok) {
            const data = await res.json();
            myProfile.username = data.username || '';
            myProfile.display_name = data.display_name || '';
            myProfile.avatar_data = data.avatar_data || '';
            myProfile.public_key_b64 = data.public_key_b64 || '';
            updateMyDrawerProfile();
        }
    } catch (e) {
        console.error("Ошибка загрузки профиля", e);
    }
}


if(tabLogin) tabLogin.addEventListener('click', () => {
    tabLogin.classList.add('active');
    tabRegister.classList.remove('active');
    loginBtn.classList.remove('hidden');
    registerBtn.classList.add('hidden');
});

if (changeServerBtn) changeServerBtn.addEventListener('click', event => {
    event.preventDefault();
    if (serverInputGroup) serverInputGroup.style.display = 'block';
    changeServerBtn.style.display = 'none';
});

if(tabRegister) tabRegister.addEventListener('click', () => {
    tabRegister.classList.add('active');
    tabLogin.classList.remove('active');
    registerBtn.classList.remove('hidden');
    loginBtn.classList.add('hidden');
});

// Строит пару ключей из base64 секретного ключа (Curve25519, совместимо с Android).
function keysFromSecretKeyB64(secretKeyB64) {
    const secretKey = base64UrlDecode(secretKeyB64);
    const kp = nacl.box.keyPair.fromSecretKey(secretKey);
    return {
        secretKey: kp.secretKey,
        publicKey: kp.publicKey,
        publicB64: base64UrlEncode(kp.publicKey)
    };
}

async function prepareLoginState(password) {
    const salt = getSalt(myId);
    
    // Сохраняем пароль в глобальную переменную myPin для последующего шифрования
    myPin = password;

    // Восстанавливаем постоянную пару ключей из зашифрованного бэкапа на сервере
    // (та же модель, что в Android: случайный ключ, бэкап шифруется паролем).
    // Публичный ключ задан при регистрации и больше не меняется — не перезаписываем его.
    if (!loginEncPrivKeyB64) {
        showStatus('Аккаунт без резервной копии ключа. Зарегистрируйтесь заново.', 'error');
        throw new Error('Нет encrypted_private_key_b64 для аккаунта');
    }
    const secretKeyB64 = await decryptPrivateKeyB64(loginEncPrivKeyB64, password);
    myKeys = keysFromSecretKeyB64(secretKeyB64);
    if (serverPublicKeyB64 && myKeys.publicB64 !== serverPublicKeyB64) {
        myKeys = null;
        throw new Error('Резервная копия ключа не совпадает с ключом аккаунта');
    }
    await prepareRatchetState(password);

    // Загружаем только историю сообщений
    const storedMsgsEnc = localStorage.getItem(`messages_${myId}`);
    if (storedMsgsEnc) {
        const decMsgs = await decryptPayload(storedMsgsEnc, password, salt);
        messages = decMsgs ? decMsgs.messages : [];
        if (messages) {
            messages.forEach(m => {
                if (m.peer) m.peer = m.peer.toLowerCase();
                if (m.reactions) m.reactions = nullMap(m.reactions);
                if (m.poll_votes) m.poll_votes = nullMap(m.poll_votes);
            });
        }
    } else {
        messages = [];
        localStorage.removeItem('last_sync_timestamp_' + myId);
    }

    myIdDisplay.textContent = myId;
    myAvatar.textContent = myId.charAt(0).toUpperCase();
    
    await fetchChatSettings();
    await fetchMyProfile();
    await fetchMyGroups();
    
    loginScreen.classList.add('hidden');
    chatScreen.classList.remove('hidden');
    
    showStatus('', '');
    
    // Capacitor Local Notifications Permission request
    if (window.Capacitor?.Plugins?.LocalNotifications) {
        try {
            window.Capacitor.Plugins.LocalNotifications.requestPermissions();
        } catch (e) {}
    }
    
    // Show loading indicator
    contactsContainer.innerHTML = '<div class="chat-loading"><div class="spinner"></div>Загрузка чатов...</div>';

    purgeExpiredSelfDestruct();
    renderContactsList();
    fetchAndRenderGlobalUsers();
    
    // Await first poll to ensure chats are loaded
    await pollInbox();
    renderContactsList();
    
    pollInterval = setInterval(pollInbox, 2000);

    // Поднимаем realtime-соединение (печатает.../presence/мгновенная доставка)
    connectRealtime();
}

function getAuthInputs(forRegistration = false) {
    myId = usernameInput.value.trim().toLowerCase();
    const password = passwordInput.value;
    serverUrl = serverInput.value.trim();
    if (myId.length < 2 || myId.length > 64) { showStatus('Имя пользователя: от 2 до 64 символов', 'error'); return null; }
    if (forRegistration && !/^[a-z0-9_]+$/i.test(myId)) { showStatus('Имя пользователя: только латиница, цифры и _', 'error'); return null; }
    if (!password) { showStatus('Введите пароль', 'error'); return null; }
    if (forRegistration && password.length < 8) { showStatus('Пароль должен быть не менее 8 символов', 'error'); return null; }
    if (!serverUrl) { showStatus('Введите URL-адрес сервера', 'error'); return null; }
    try {
        const url = new URL(serverUrl);
        const local = ['localhost', '127.0.0.1', '::1'].includes(url.hostname);
        if (!['https:', 'http:'].includes(url.protocol) || (url.protocol === 'http:' && !local)) {
            showStatus('Используйте HTTPS (HTTP разрешён только для локальной разработки)', 'error');
            return null;
        }
        serverUrl = url.origin;
    } catch (_) {
        showStatus('Некорректный URL сервера', 'error');
        return null;
    }
    return password;
}

async function performLogin() {
    const password = getAuthInputs(false);
    if (!password) return;

    loginBtn.disabled = true;
    showStatus('Вход в аккаунт...', 'success');

    const loginSuccess = await loginOnServer(password);
    loginBtn.disabled = false;
    
    if (loginSuccess) {
        // Save credentials if Remember Me is checked
        const rememberMeCheckbox = document.getElementById('remember-me-checkbox');
        if (rememberMeCheckbox && rememberMeCheckbox.checked) {
            localStorage.setItem('remember_me', 'true');
            localStorage.setItem('remember_username', myId);
        } else {
            localStorage.removeItem('remember_me');
            localStorage.removeItem('remember_username');
        }
        try {
            await prepareLoginState(password);
        } catch (error) {
            showStatus(`Вход: ${error.message}`, 'error');
        }
    }
}

async function performRegister() {
    const password = getAuthInputs(true);
    if (!password) return;

    registerBtn.disabled = true;
    showStatus('Регистрация аккаунта...', 'success');

    try {
        // crypto_box остаётся для групп; личные чаты используют общий Olm/Double Ratchet.
        const kp = nacl.box.keyPair();
        const publicB64 = base64UrlEncode(kp.publicKey);
        const privateB64 = base64UrlEncode(kp.secretKey);
        const encryptedPrivateKeyB64 = await encryptPrivateKeyB64(privateB64, password);
        const ratchet = await loadRatchetApi();
        const encryptedOlmAccountB64 = await encryptPrivateKeyB64(ratchet.account_new(), password);

        const res = await fetch(`${serverUrl}/users/register`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Bypass-Tunnel-Reminder': 'true' },
            body: JSON.stringify({
                user_id: myId,
                public_key_b64: publicB64,
                encrypted_private_key_b64: encryptedPrivateKeyB64,
                encrypted_olm_account_b64: encryptedOlmAccountB64,
                password: password
            })
        });
        if (!res.ok) {
            const data = await res.json().catch(() => ({}));
            if (res.status === 429) {
                const wait = res.headers.get('Retry-After') || '60';
                throw new Error(formatServerError(data, `Слишком много попыток. Подождите ${wait} сек. и попробуйте снова.`));
            }
            throw new Error(formatServerError(data, 'Ошибка регистрации'));
        }
        
        // Сразу логинимся
        const loginSuccess = await loginOnServer(password);
        if (loginSuccess) {
            const rememberMeCheckbox = document.getElementById('remember-me-checkbox');
            if (rememberMeCheckbox && rememberMeCheckbox.checked) {
                localStorage.setItem('remember_me', 'true');
                localStorage.setItem('remember_username', myId);
            }
            await prepareLoginState(password);
        }
    } catch (e) {
        showStatus(`Регистрация: ${e.message}`, 'error');
    }
    registerBtn.disabled = false;
}

async function logout() {
    const token = sessionToken;
    try { if (realtimeWs) { realtimeWs.onclose = null; realtimeWs.close(); } } catch (_) {}
    if (pollInterval) clearInterval(pollInterval);
    pollInterval = null;
    stopWsPing();
    if (wsReconnectTimer) clearTimeout(wsReconnectTimer);
    wsReconnectTimer = null;

    if (token && serverUrl) {
        try {
            await fetch(`${serverUrl}/logout`, {
                method: 'POST',
                headers: authHeaders(),
                keepalive: true
            });
        } catch (_) {}
    }

    // Clear remember-me settings to prevent auto-login
    localStorage.removeItem('remember_me');
    localStorage.removeItem('remember_username');
    
    sessionToken = '';
    myKeys = null;
    serverPublicKeyB64 = '';
    loginEncOlmAccountB64 = '';
    olmAccountPickle = '';
    olmIdentityB64 = '';
    olmSessions = Object.create(null);
    olmIdentityPins = Object.create(null);
    // Reload the page to cleanly wipe all JS state, caches, and UI variables
    window.location.reload();
}

// Устаревшая функция, теперь логика внутри performRegister

async function loginOnServer(password) {
    try {
        const res = await fetch(`${serverUrl}/users/login`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Bypass-Tunnel-Reminder': 'true' },
            body: JSON.stringify({
                user_id: myId,
                password: password
            })
        });
        if (!res.ok) {
            const data = await res.json().catch(() => ({}));
            if (res.status === 429) {
                const wait = res.headers.get('Retry-After') || '60';
                throw new Error(formatServerError(data, `Слишком много попыток входа. Подождите ${wait} сек.`));
            }
            throw new Error(formatServerError(data, 'Неверный пароль'));
        }
        const data = await res.json();
        if (data.ok && data.token) {
            sessionToken = data.token;
            serverPublicKeyB64 = data.public_key_b64 || '';
            // Бэкап приватного ключа расшифруем в prepareLoginState (нужен пароль)
            loginEncPrivKeyB64 = data.encrypted_private_key_b64 || '';
            loginEncOlmAccountB64 = data.encrypted_olm_account_b64 || '';
            return true;
        }
        return false;
    } catch (e) {
        console.error("Server login error:", e);
        showStatus(`Вход: ${e.message}`, 'error');
        return false;
    }
}

function disableReplyOrEditMode() {
    replyToMsgId = null;
    editingMsgId = null;
    if (replyPreviewContainer) {
        replyPreviewContainer.classList.remove('active');
        setTimeout(() => {
            if (!replyPreviewContainer.classList.contains('active')) {
                replyPreviewContainer.classList.add('hidden');
            }
        }, 250);
    }
    messageInput.value = '';
    if (sendBtn) sendBtn.innerHTML = '<svg viewBox="0 0 24 24" width="24" height="24"><path fill="currentColor" d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z"/></svg>';
    messageInput.dispatchEvent(new Event('input'));
}

function enableReplyMode(msg) {
    editingMsgId = null;
    replyToMsgId = msg.message_id;
    
    const authorName = msg.direction === 'out' ? 'Вы' : getContactDisplayName(msg.peer);
    let previewText = '';
    const type = msg.payload ? msg.payload.type : 'text';
    const content = msg.payload ? msg.payload.content : msg.plaintext;
    if (type === 'text') previewText = content;
    else if (type === 'image') previewText = '📷 Фотография';
    else if (type === 'voice') previewText = '🎤 Голосовое сообщение';
    else if (type === 'video_msg') previewText = '📹 Видеосообщение';
    else if (type === 'file') previewText = '📂 Файл: ' + (msg.payload?.filename || 'Документ');
    
    if (replyPreviewContainer) {
        replyPreviewContainer.classList.remove('hidden');
        void replyPreviewContainer.offsetWidth; // Force layout recalculation
        replyPreviewContainer.classList.add('active');
        replyPreviewAuthor.textContent = `Ответ пользователю: ${authorName}`;
        replyPreviewText.textContent = previewText;
    }
    
    messageInput.focus();
    if (sendBtn) sendBtn.innerHTML = '<svg viewBox="0 0 24 24" width="24" height="24"><path fill="currentColor" d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z"/></svg>';
    messageInput.dispatchEvent(new Event('input'));
}

let isSendingMessage = false;
async function sendMessage() {
    if (isSendingMessage) return;
    if (!selectedPeer) return;
    const text = messageInput.value.trim();
    if (!text) return;
    
    isSendingMessage = true;
    if (sendBtn) sendBtn.disabled = true;
    
    try {
    
    if (editingMsgId) {
        const success = await sendPayloadMessage({ type: 'edit', target_id: editingMsgId, content: text });
        if (success) {
            const target = messages.find(m => m.message_id === editingMsgId);
            if (target) {
                if (target.payload) {
                    target.payload.content = text;
                } else {
                    target.payload = { type: 'text', content: text };
                }
                target.edited = true;
            }
            await saveMessagesLocally();
            selectContact(selectedPeer);
            disableReplyOrEditMode();
        } else {
            alert("Не удалось сохранить изменения.");
        }
    } else {
        let payloadObj = { type: 'text', content: text };
        if (replyToMsgId) {
            const origMsg = messages.find(m => m.message_id === replyToMsgId);
            if (origMsg) {
                const authorName = origMsg.direction === 'out' ? 'Вы' : getContactDisplayName(origMsg.peer);
                let previewText = '';
                const type = origMsg.payload ? origMsg.payload.type : 'text';
                const content = origMsg.payload ? origMsg.payload.content : origMsg.plaintext;
                if (type === 'text') previewText = content;
                else if (type === 'image') previewText = '📷 Фотография';
                else if (type === 'voice') previewText = '🎤 Голосовое сообщение';
                else if (type === 'video_msg') previewText = '📹 Видеосообщение';
                else if (type === 'file') previewText = '📂 Файл: ' + (origMsg.payload?.filename || 'Документ');
                
                payloadObj.reply_to = {
                    msg_id: replyToMsgId,
                    author: authorName,
                    text: previewText
                };
            }
        }
        
        // Очищаем текстовое поле мгновенно
        messageInput.value = '';
        messageInput.dispatchEvent(new Event('input'));
        disableReplyOrEditMode();
        setTimeout(() => {
            if (messageInput) messageInput.focus();
        }, 30);
        
        // Отправляем в фоновом режиме (функция теперь оптимистичная для обычных сообщений)
        sendPayloadMessage(payloadObj);
    }
    } catch (e) {
        console.error("Critical error in sendMessage:", e);
        showStatus("Ошибка отправки сообщения (системная ошибка)", "error");
    } finally {
        isSendingMessage = false;
        if (sendBtn) sendBtn.disabled = false;
    }
}

async function sendPayloadMessage(payloadObj, targetPeer = selectedPeer) {
    if (!targetPeer) return false;
    targetPeer = targetPeer.toLowerCase();
    const clientId = window.crypto.randomUUID();
    
    const isStoreType = ['text', 'image', 'voice', 'video_msg', 'file', 'poll'].includes(payloadObj.type);

    // Самоуничтожение: добавляем TTL к исходящим контентным сообщениям (кроме опросов)
    if (selfDestructTtl > 0 && isStoreType && payloadObj.type !== 'poll' && !payloadObj.ttl) {
        payloadObj.ttl = selfDestructTtl;
    }

    let tempId = null;
    if (isStoreType) {
        tempId = `temp_${clientId}`;
        const tempMsg = {
            direction: 'out',
            peer: targetPeer,
            message_id: tempId,
            payload: payloadObj,
            timestamp: Date.now(),
            status: 'sending'
        };
        messages.push(tempMsg);
        
        // Добавляем на экран, если открыт диалог с этим собеседником
        if (selectedPeer && selectedPeer.toLowerCase() === targetPeer.toLowerCase()) {
            appendMessage(tempMsg, true);
            scrollToBottom();
            renderContactsList();
        }
    }

    // ponytail: saved messages stay local until the server has per-device ratchet fan-out.
    if (targetPeer === myId && isStoreType && tempId) {
        const tempMsg = messages.find(message => message.message_id === tempId);
        if (tempMsg) {
            tempMsg.message_id = clientId;
            tempMsg.status = 'sent';
        }
        await saveMessagesLocally();
        renderContactsList();
        if (selectedPeer === myId) selectContact(myId);
        return true;
    }
    
    const runSend = async () => {
        try {
            let envelope;
            // Канонизация: на провод уходит wire-формат Android, не внутренний веб-payload.
            const wireJson = JSON.stringify(toWire(payloadObj));
            const textBytes = new TextEncoder().encode(wireJson);
            
            if (myGroupsCache[targetPeer.toLowerCase()]) {
                // Group message (Symmetric)
                const gKey = groupKeys[targetPeer.toLowerCase()];
                if (!gKey) throw new Error('Симметричный ключ группы не найден');

                // AES-GCM общим ключом группы (совместимо с Android). is_group="1" —
                // строка, как ожидает Android (env.optString("is_group") == "1").
                const enc = await aesGcmEncrypt(gKey, textBytes);
                envelope = {
                    is_group: "1",
                    nonce_b64: enc.nonce_b64,
                    ciphertext_b64: enc.ciphertext_b64
                };
            } else {
                // Direct message: Olm/X3DH + Double Ratchet, копия каждому
                // устройству получателя (multi-device fanout).
                const devices = await getPeerDevices(targetPeer);
                if (!devices.length) throw new Error('У получателя нет Olm-устройств');
                let firstData = null;
                for (const dev of devices) {
                    const devEnvelope = await ratchetEnvelopeForDevice(targetPeer, dev.device_id, wireJson);
                    const res = await fetch(`${serverUrl}/messages`, {
                        method: 'POST',
                        headers: authHeaders({ 'Content-Type': 'application/json' }),
                        body: JSON.stringify({
                            sender_id: myId,
                            recipient_id: targetPeer,
                            envelope: devEnvelope,
                            client_id: firstData ? crypto.randomUUID() : clientId,
                            target_device_id: dev.device_id
                        })
                    });
                    if (!res.ok) {
                        if (firstData) { console.error('Fanout copy failed for', dev.device_id, res.status); continue; }
                        throw new Error('Ошибка отправки');
                    }
                    if (!firstData) firstData = await res.json();
                }
                var directData = firstData;
            }

            let res = null;
            if (envelope) {
                res = await fetch(`${serverUrl}/messages`, {
                    method: 'POST',
                    headers: authHeaders({ 'Content-Type': 'application/json' }),
                    body: JSON.stringify({
                        sender_id: myId,
                        recipient_id: targetPeer,
                        envelope: envelope,
                        client_id: clientId
                    })
                });
                if (!res.ok) throw new Error('Ошибка отправки');
            }

            const data = envelope ? await res.json() : directData;
            
            if (isStoreType && tempId) {
                // Ищем наше временное сообщение и обновляем его статус и ID
                let tempMsg = messages.find(m => m.message_id === tempId);
                if (tempMsg) {
                    tempMsg.message_id = data.message_id;
                    tempMsg.status = 'sent';
                    
                    // Обновляем элемент DOM на экране
                    if (selectedPeer && selectedPeer.toLowerCase() === targetPeer.toLowerCase()) {
                        const el = findMessageElement(tempId);
                        if (el) {
                            el.dataset.id = data.message_id;
                            const iconEl = el.querySelector('.msg-status-icon');
                            if (iconEl) {
                                iconEl.className = 'msg-status-icon';
                                iconEl.style.color = 'var(--accent-color)';
                                iconEl.style.opacity = '0.8';
                                iconEl.innerHTML = '<i class="fas fa-check"></i>';
                            }
                        }
                    }
                    await saveMessagesLocally();
                    renderContactsList();
                }
            }

            return true;
        } catch (e) {
            console.error("Send message error:", e);
            if (isStoreType && tempId) {
                let tempMsg = messages.find(m => m.message_id === tempId);
                if (tempMsg) {
                    tempMsg.status = 'failed';
                    if (selectedPeer && selectedPeer.toLowerCase() === targetPeer.toLowerCase()) {
                        const el = findMessageElement(tempId);
                        if (el) {
                            const iconEl = el.querySelector('.msg-status-icon');
                            if (iconEl) {
                                iconEl.className = 'msg-status-icon failed';
                                iconEl.style.color = '#ef4444';
                                iconEl.innerHTML = '<i class="fas fa-exclamation-circle"></i>';
                                iconEl.title = "Ошибка отправки. Нажмите, чтобы удалить";
                                
                                // Повесим событие клика на иконку ошибки, чтобы была возможность удалить
                                iconEl.addEventListener('click', (ev) => {
                                    ev.stopPropagation();
                                    if (confirm("Удалить это неотправленное сообщение?")) {
                                        messages = messages.filter(m => m.message_id !== tempId);
                                        el.remove();
                                        saveMessagesLocally();
                                    }
                                });
                            }
                        }
                    }
                }
            }
            return false;
        }
    };

    if (isStoreType) {
        // Обычные сообщения отправляем асинхронно в фоне
        runSend();
        return true;
    } else {
        // Управляющие/системные сообщения блокируем для проверки результата
        return await runSend();
    }
}

async function pollInbox() {
    if (!myId || !serverUrl) return;
    
    pollCounter++;
    if (pollCounter % 3 === 0) fetchAndRenderGlobalUsers();

    // Heartbeat — update online status
    try {
        fetch(`${serverUrl}/users/me/heartbeat`, {
            method: 'POST',
            headers: authHeaders()
        });
    } catch (e) {}

    // Refresh active peer online status
    if (selectedPeer && selectedPeer !== myId) {
        try {
            const profRes = await fetch(`${serverUrl}/users/${pathSegment(selectedPeer)}/profile`, { headers: authHeaders() });
            if (profRes.ok) {
                const profData = await profRes.json();
                if (profileCache[selectedPeer] && typeof profileCache[selectedPeer] === 'object') {
                    profileCache[selectedPeer].last_active = profData.last_active;
                } else {
                    profileCache[selectedPeer] = profData;
                }
                updatePeerStatus(selectedPeer, profileCache[selectedPeer]);
            }
        } catch (e) {}
    }

    try {
        // The server keeps messages until an explicit ACK. Do not use a local
        // timestamp cursor: a failed decrypt must remain retryable.
        const res = await fetch(`${serverUrl}/messages/inbox/${pathSegment(myId)}?device_id=${pathSegment(myDeviceId || 'primary')}`, {
            headers: authHeaders()
        });
        if (!res.ok) return;
        
        const data = await res.json();
        let newAdded = false;
        let playSound = false;
        let lastNotificationMsg = null;
        const ackIds = [];

        for (const item of data.messages) {
            let ackThis = false;
            let decrypted = false;
            try {
                const env = item.envelope;
                // Android шлёт is_group="1" (строка); старый веб слал boolean true.
                const isGroupMsg = env.is_group === "1" || env.is_group === true;
                let plaintext;

                if (env.ratchet === '1' || env.ratchet === 1) {
                    if (isGroupMsg) throw new Error('Ratchet-конверт не может быть групповым');
                    plaintext = await openRatchetEnvelope(item.sender_id.toLowerCase(), env);
                } else if (isGroupMsg) {
                    // Group message — AES-GCM общим ключом группы
                    const nonceBytes = base64UrlDecode(env.nonce_b64);
                    const cipherBytes = base64UrlDecode(env.ciphertext_b64);
                    const groupId = item.recipient_id.toLowerCase();
                    const gKey = groupKeys[groupId];
                    if (!gKey) {
                        console.error('No group key for', groupId);
                        continue;
                    }
                    if (item.sender_id.toLowerCase() === myId) continue;
                    try {
                        const decryptedBytes = await aesGcmDecrypt(gKey, nonceBytes, cipherBytes);
                        plaintext = new TextDecoder().decode(decryptedBytes);
                    } catch (e) {
                        console.error('Group AES-GCM decrypt failed for', groupId, e);
                        continue;
                    }
                } else {
                    // Direct messages are Ratchet-only. Discard an old/static
                    // box envelope instead of silently accepting a downgrade.
                    console.warn('Ignoring non-Ratchet direct message:', item.id);
                    ackThis = true;
                    continue;
                }
                if (plaintext == null) continue;
                decrypted = true;
                ackThis = true;

                let payloadObj;
                try {
                    // fromWire приводит канонический формат Android к внутреннему веб-формату.
                    payloadObj = fromWire(JSON.parse(plaintext));
                } catch(err) {
                    payloadObj = { type: 'text', content: plaintext };
                }

                // Обработка sync_sent
                if (payloadObj.type === 'sync_sent') {
                    const peerId = payloadObj.peer ? payloadObj.peer.toLowerCase() : '';
                    const origMsgId = payloadObj.original_msg_id;
                    const orig = payloadObj.original_payload;
                    
                    // Перехватываем временное отправляющееся сообщение, чтобы обновить его ID без дублирования
                    if (orig) {
                        let tempMsg = messages.find(m => m.message_id && m.message_id.toString().startsWith('temp_') && m.direction === 'out' && m.peer === peerId && JSON.stringify(m.payload) === JSON.stringify(orig));
                        if (tempMsg) {
                            const oldId = tempMsg.message_id;
                            tempMsg.message_id = origMsgId || item.id;
                            tempMsg.status = 'sent';
                            
                            // Обновляем элемент DOM на экране
                            if (selectedPeer && selectedPeer.toLowerCase() === peerId.toLowerCase()) {
                                const el = findMessageElement(oldId);
                                if (el) {
                                    el.dataset.id = tempMsg.message_id;
                                    const iconEl = el.querySelector('.msg-status-icon');
                                    if (iconEl) {
                                        iconEl.className = 'msg-status-icon';
                                        iconEl.style.color = 'var(--accent-color)';
                                        iconEl.style.opacity = '0.8';
                                        iconEl.innerHTML = '<i class="fas fa-check"></i>';
                                    }
                                }
                            }
                            newAdded = true;
                            continue;
                        }
                    }
                    
                    if (orig && orig.type === 'delete') {
                        if (messages.some(m => m.message_id === orig.target_id)) {
                            messages = messages.filter(m => m.message_id !== orig.target_id);
                            newAdded = true;
                            if (selectedPeer === peerId) selectContact(selectedPeer);
                        }
                        continue;
                    }
                    if (orig && orig.type === 'edit') {
                        const target = messages.find(m => m.message_id === orig.target_id);
                        if (target && target.payload) {
                            target.payload.content = orig.content;
                            target.edited = true;
                            newAdded = true;
                            if (selectedPeer === peerId) selectContact(selectedPeer);
                        }
                        continue;
                    }

                    if (orig && orig.type === 'reaction') {
                        const target = messages.find(m => m.message_id === orig.target_id);
                        if (target) {
                            if (!target.reactions) target.reactions = Object.create(null);
                            const emoji = orig.emoji;
                            if (emoji) {
                                target.reactions[myId] = emoji;
                            } else {
                                delete target.reactions[myId];
                            }
                            newAdded = true;
                            if (selectedPeer === peerId) {
                                setTimeout(() => {
                                    updateMessageReactionsUI(orig.target_id, target.reactions);
                                }, 50);
                            }
                        }
                        continue;
                    }
                    
                    if (messages.some(m => m.message_id === item.id || (origMsgId && m.message_id === origMsgId))) continue;
                    
                    const newMsg = {
                        direction: 'out',
                        peer: peerId,
                        message_id: origMsgId || item.id,
                        payload: orig,
                        timestamp: (new Date(item.created_at).getTime()) || Date.now()
                    };
                    messages.push(newMsg);
                    newAdded = true;
                    if (selectedPeer === peerId) selectContact(selectedPeer);
                    renderContactsList(); // Ensure contacts list updates with the new message
                    continue;
                }

                // Обработка Control Messages
                if (payloadObj.type === 'read_receipt') {
                    const targetId = payloadObj.target_id;
                    const target = messages.find(m => m.message_id === targetId);
                    if (target) {
                        target.status = 'read';
                        newAdded = true;
                        const msgEl = findMessageElement(targetId);
                        if (msgEl) {
                            const statusIcon = msgEl.querySelector('.msg-status-icon');
                            if (statusIcon) {
                                statusIcon.innerHTML = '<i class="fas fa-check-double"></i>';
                                statusIcon.style.color = '#4ade80';
                                statusIcon.style.opacity = '0.9';
                                statusIcon.title = 'Просмотрено';
                            }
                        }
                    }
                    continue;
                }

                // Канонический read (Android): помечаем все мои исходящие этому собеседнику прочитанными.
                if (payloadObj.type === 'read') {
                    const readerId = item.sender_id.toLowerCase();
                    let changed = false;
                    for (const m of messages) {
                        if (m.direction === 'out' && m.peer === readerId && m.status !== 'read') {
                            m.status = 'read';
                            changed = true;
                            const msgEl = findMessageElement(m.message_id);
                            if (msgEl) {
                                const statusIcon = msgEl.querySelector('.msg-status-icon');
                                if (statusIcon) {
                                    statusIcon.innerHTML = '<i class="fas fa-check-double"></i>';
                                    statusIcon.style.color = '#4ade80';
                                    statusIcon.style.opacity = '0.9';
                                    statusIcon.title = 'Просмотрено';
                                }
                            }
                        }
                    }
                    if (changed) newAdded = true;
                    continue;
                }

                // Канонический delivered (Android): мои исходящие доставлены (✓✓ серая, до прочтения).
                if (payloadObj.type === 'delivered') {
                    const peerD = item.sender_id.toLowerCase();
                    let changed = false;
                    for (const m of messages) {
                        if (m.direction === 'out' && m.peer === peerD && m.status !== 'read' && m.status !== 'delivered') {
                            m.status = 'delivered';
                            changed = true;
                            const msgEl = findMessageElement(m.message_id);
                            if (msgEl) {
                                const statusIcon = msgEl.querySelector('.msg-status-icon');
                                if (statusIcon) {
                                    statusIcon.innerHTML = '<i class="fas fa-check-double"></i>';
                                    statusIcon.style.color = 'var(--text-muted)';
                                    statusIcon.style.opacity = '0.7';
                                    statusIcon.title = 'Доставлено';
                                }
                            }
                        }
                    }
                    if (changed) newAdded = true;
                    continue;
                }

                if (payloadObj.type === 'delete') {
                    if (messages.some(m => m.message_id === payloadObj.target_id)) {
                        messages = messages.filter(m => m.message_id !== payloadObj.target_id);
                        newAdded = true;
                        if (selectedPeer === item.sender_id.toLowerCase()) selectContact(selectedPeer);
                    }
                    continue;
                }
                
                if (payloadObj.type === 'edit') {
                    const target = messages.find(m => m.message_id === payloadObj.target_id);
                    if (target && target.payload) {
                        target.payload.content = payloadObj.content;
                        target.edited = true;
                        newAdded = true;
                        if (selectedPeer === item.sender_id.toLowerCase()) selectContact(selectedPeer);
                    }
                    continue;
                }

                if (payloadObj.type === 'reaction') {
                    const targetId = payloadObj.target_id;
                    const target = messages.find(m => m.message_id === targetId);
                    if (target) {
                        if (!target.reactions) target.reactions = Object.create(null);
                        const emoji = payloadObj.emoji;
                        const sender = item.sender_id.toLowerCase();
                        if (emoji) {
                            target.reactions[sender] = emoji;
                        } else {
                            delete target.reactions[sender];
                        }
                        newAdded = true;
                        if (selectedPeer === item.sender_id.toLowerCase() || selectedPeer === item.recipient_id.toLowerCase()) {
                            setTimeout(() => {
                                updateMessageReactionsUI(targetId, target.reactions);
                            }, 50);
                        }
                    }
                    continue;
                }

                if (payloadObj.type === 'pin' || payloadObj.type === 'unpin') {
                    const peerForPin = isGroupMsg ? item.recipient_id.toLowerCase() : item.sender_id.toLowerCase();
                    setPinnedId(peerForPin, payloadObj.type === 'pin' ? payloadObj.target_id : null);
                    if (selectedPeer === peerForPin) renderPinnedBar(peerForPin);
                    continue;
                }

                if (payloadObj.type === 'poll_vote') {
                    const target = messages.find(m => m.message_id === payloadObj.target_id);
                    if (target && target.payload && target.payload.type === 'poll') {
                        if (!target.poll_votes) target.poll_votes = Object.create(null);
                        target.poll_votes[item.sender_id.toLowerCase()] = payloadObj.options || (payloadObj.option != null ? [payloadObj.option] : []);
                        newAdded = true;
                        if (selectedPeer === target.peer) {
                            setTimeout(() => updatePollUI(target.message_id), 30);
                        }
                    }
                    continue;
                }

                if (payloadObj.type === 'webrtc') {
                    const msgTime = new Date(item.created_at).getTime();
                    // 60с вместо 15с: запас на медленный поллинг и расхождение часов клиента/сервера
                    if (Date.now() - msgTime > 60000) {
                        console.log("Ignoring old WebRTC call signaling message:", item.id);
                        continue;
                    }
                    await handleWebRTCMessage(item.sender_id.toLowerCase(), payloadObj);
                    continue;
                }

                // Обычные сообщения (text, image, voice, video_msg, file, poll)
                if (messages.some(m => m.message_id === item.id)) continue;

                // For group messages, peer = group ID; for DMs, peer = sender
                const isSelfMsg = !isGroupMsg && item.sender_id.toLowerCase() === myId;
                const msgPeer = isGroupMsg ? item.recipient_id.toLowerCase() : item.sender_id.toLowerCase();

                // «Избранное»: эхо собственного сообщения — сверяем с temp, не дублируем
                if (isSelfMsg) {
                    const mine = messages.find(m => m.direction === 'out' && m.peer === msgPeer &&
                        (m.message_id === item.id ||
                         (m.message_id && m.message_id.toString().startsWith('temp_') &&
                          JSON.stringify(m.payload) === JSON.stringify(payloadObj))));
                    if (mine) {
                        if (mine.message_id !== item.id) {
                            const oldId = mine.message_id;
                            mine.message_id = item.id;
                            mine.status = 'sent';
                            if (selectedPeer === msgPeer) {
                                const el = findMessageElement(oldId);
                                if (el) el.dataset.id = item.id;
                            }
                        }
                        continue;
                    }
                }

                const newMsg = { 
                    direction: isSelfMsg ? 'out' : 'in', 
                    peer: msgPeer, 
                    message_id: item.id,
                    payload: payloadObj,
                    timestamp: (new Date(item.created_at).getTime()) || Date.now(),
                    sender_id: item.sender_id.toLowerCase()
                };

                messages.push(newMsg);
                newAdded = true;

                // Квитанция «доставлено» отправителю (личные, не своё) — у него ✓✓.
                if (!isGroupMsg && !isSelfMsg) {
                    sendPayloadMessage({ type: 'delivered' }, item.sender_id.toLowerCase());
                }

                if (selectedPeer === msgPeer) {
                    appendMessage(newMsg, true);
                    scrollToBottom();
                    // Send E2E read receipt (only for DMs, не для собственных сообщений)
                    if (!isGroupMsg && !isSelfMsg) {
                        sendPayloadMessage({ type: 'read_receipt', target_id: item.id }, item.sender_id.toLowerCase());
                        newMsg.read_sent = true;
                    }
                } else if (!isSelfMsg) {
                    // Only notify if this chat is NOT currently open (и не своё сообщение)
                    playSound = true;
                    lastNotificationMsg = newMsg;
                }
            } catch (e) {
                // Once authenticated decryption succeeded, a malformed
                // payload is a peer/application error, not a retryable crypto
                // failure. ACK it so one poison message cannot block inbox
                // polling forever. Failed decrypts remain pending.
                ackThis = decrypted;
                console.error("Poll parse error:", e);
            } finally {
                if (ackThis && item.id) ackIds.push(item.id);
            }
        }

        if (newAdded) {
            await saveMessagesLocally();
            renderContactsList();
        }

        // Persist first, then acknowledge. A failed write leaves the server
        // copy intact and the next poll can retry it.
        for (let i = 0; i < ackIds.length; i += 500) {
            const batch = ackIds.slice(i, i + 500);
            const ackRes = await fetch(`${serverUrl}/messages/ack`, {
                method: 'POST',
                headers: authHeaders({ 'Content-Type': 'application/json' }),
                body: JSON.stringify({ message_ids: batch, device_id: myDeviceId || 'primary' })
            });
            if (!ackRes.ok) console.error('Message ACK failed:', ackRes.status);
        }

        if (playSound) {
            playNotificationSound();
        }
        if (lastNotificationMsg) {
            let preview = 'Новое сообщение';
            const pay = lastNotificationMsg.payload;
            if (pay.type === 'text') preview = pay.content;
            else if (pay.type === 'image') preview = '📷 Фотография';
            else if (pay.type === 'voice') preview = '🎤 Голосовое сообщение';
            else if (pay.type === 'video_msg') preview = '📹 Видеосообщение';
            else if (pay.type === 'file') preview = '📂 Файл: ' + (pay.filename || 'Документ');
            else if (pay.type === 'poll') preview = '📊 Опрос: ' + (pay.question || '');
            showBrowserNotification(lastNotificationMsg.peer, preview);
        }
    } catch (e) { console.error("Poll failed:", e); }
}

async function saveMessagesLocally() {
    const salt = getSalt(myId);
    const payload = { messages: messages };
    const encrypted = await encryptPayload(payload, myPin, salt);
    localStorage.setItem(`messages_${myId}`, encrypted);
}

function showStatus(text, type) {
    loginStatus.textContent = text;
    loginStatus.className = 'status-msg ' + (type || '');
}

function renderSearchResults(users, groups = []) {
    contactsContainer.innerHTML = '';
    if (users.length === 0 && groups.length === 0) {
        contactsContainer.innerHTML = '<div style="padding: 15px; color: var(--text-secondary); text-align: center;">Ничего не найдено</div>';
        return;
    }
    
    users.filter(u => u.user_id !== myId).forEach(u => {
        profileCache[u.user_id] = u;
        
        const dName = String(u.display_name || u.user_id);
        const item = document.createElement('div');
        item.className = 'tg-contact-item';
        
        let avatarHtml = `<div class="tg-contact-avatar">${escapeHtmlSafe(dName.charAt(0).toUpperCase())}</div>`;
        if (u.avatar_data) {
            avatarHtml = '<div class="tg-contact-avatar" data-avatar-url></div>';
        }
        
        item.innerHTML = `
            ${avatarHtml}
            <div class="tg-contact-info">
                <div class="tg-contact-name">${escapeHtmlSafe(dName)}</div>
                <div style="font-size: 13px; color: var(--text-secondary);">@${escapeHtmlSafe(u.username || '...')}</div>
            </div>
        `;
        if (u.avatar_data) setSafeBackgroundImage(item.querySelector('[data-avatar-url]'), u.avatar_data);
        item.addEventListener('click', () => {
            peerInput.value = '';
            searchBarContainer.classList.add('hidden');
            selectContact(u.user_id);
        });
        contactsContainer.appendChild(item);
    });
    
    groups.forEach(g => {
        const item = document.createElement('div');
        item.className = 'tg-contact-item';
        item.innerHTML = `
            <div class="tg-contact-avatar" style="background: var(--accent-color); color: white; display: flex; align-items: center; justify-content: center; font-size: 20px;">
                ${g.is_channel ? '<i class="fas fa-bullhorn"></i>' : '<i class="fas fa-users"></i>'}
            </div>
            <div class="tg-contact-info">
                <div class="tg-contact-name">${escapeHtmlSafe(g.name)}</div>
                <div class="tg-contact-preview">${g.is_channel ? 'Канал' : 'Группа'} • ${escapeHtmlSafe(g.id)}</div>
            </div>
        `;
        item.addEventListener('click', () => {
            searchBarContainer.classList.add('hidden');
            selectContact(g.id);
        });
        contactsContainer.appendChild(item);
    });
}

let profileFetchPromises = Object.create(null);

async function fetchPeerProfile(peerId) {
    if (!peerId) return null;
    peerId = peerId.toLowerCase().trim();
    if (peerId === myId) {
        profileCache[myId] = myProfile;
        return myProfile;
    }
    
    if (profileCache[peerId] && profileCache[peerId] !== 'pending') {
        return profileCache[peerId];
    }
    
    if (profileFetchPromises[peerId]) {
        return await profileFetchPromises[peerId];
    }
    
    profileFetchPromises[peerId] = new Promise(async (resolve) => {
        try {
            const res = await fetch(`${serverUrl}/users/${pathSegment(peerId)}/profile`, { headers: authHeaders() });
            if (res.ok) {
                const data = await res.json();
                profileCache[peerId] = data;
                renderContactsList(); 
                if (selectedPeer === peerId) {
                    updateActiveChatHeader(peerId);
                }
                delete profileFetchPromises[peerId];
                return resolve(data);
            }
        } catch (e) {}
        
        // If not found or error, provide fallback
        profileCache[peerId] = { display_name: peerId }; 
        delete profileFetchPromises[peerId];
        resolve(profileCache[peerId]);
    });
    
    return await profileFetchPromises[peerId];
}

function selectContact(peerId) {
    if (peerId) peerId = peerId.toLowerCase();
    selectedPeer = peerId;
    disableReplyOrEditMode();
    
    // Clear old message bubbles
    messagesContainer.innerHTML = '';
    
    renderContactsList();
    noChatSelected.classList.add('hidden');
    activeChatWindow.classList.remove('hidden');
    
    updateActiveChatHeader(peerId);
    if (!profileCache[peerId]) fetchPeerProfile(peerId);
    
    chatScreen.classList.add('chat-open');
    
    // Новая логика для мобилок - выезд окна чата
    if (window.innerWidth <= 768 && chatAreaView) {
        chatAreaView.classList.add('mobile-open');
    }
    
    // Send read receipts for any incoming messages from this peer that we haven't marked as read
    let sentAny = false;
    messages.forEach(m => {
        if (m.peer && m.peer.toLowerCase() === peerId && m.direction === 'in' && !m.read_sent) {
            sendPayloadMessage({ type: 'read_receipt', target_id: m.message_id }, peerId);
            m.read_sent = true;
            sentAny = true;
        }
    });
    if (sentAny) {
        saveMessagesLocally();
    }
    
    lastRenderedDate = null;
    messages.filter(m => m.peer && m.peer.toLowerCase() === peerId).forEach(m => appendMessage(m, false));
    scrollToBottom();
    renderPinnedBar(peerId);
    resetChatSearch();
    selfDestructTtl = 0;
    if (typeof updateSdChip === 'function') updateSdChip();
}

function appendMessage(msg, animate = false) {
    maybeInsertDateDivider(msg);
    const wrapper = document.createElement('div');
    wrapper.className = `tg-msg-wrapper ${msg.direction}`;
    if (animate) {
        wrapper.classList.add('msg-anim-appear');
    }
    wrapper.dataset.id = msg.message_id; // Для поиска при редактировании
    
    let contentHtml = '';
    const type = msg.payload ? msg.payload.type : 'text';
    // Для медиа (.media) контент резолвится лениво ниже; content тут пустой плейсхолдер.
    let content = String(msg.payload ? (msg.payload.content || '') : (msg.plaintext || ''));
    
    // Эскейпим текст для безопасности (очень простая защита)
    if (type === 'text') {
        contentHtml = escapeHtmlSafe(content);
    } else if (type === 'image') {
        contentHtml = '<img class="tg-msg-image" alt="Изображение сообщения">';
    } else if (type === 'voice') {
        contentHtml = `
            <div class="tg-voice-player">
                <button class="voice-play-btn" title="Воспроизвести"><i class="fas fa-play"></i></button>
                <div class="voice-progress-container">
                    <input type="range" class="voice-slider" min="0" max="100" value="0">
                    <span class="voice-duration">0:00</span>
                </div>
                <audio preload="metadata" style="display:none;"></audio>
            </div>
        `;
    } else if (type === 'video_msg') {
        contentHtml = `
            <div class="tg-msg-video-msg">
                <video loop playsinline autoplay muted></video>
            </div>
        `;
    } else if (type === 'file') {
        const filename = escapeHtmlSafe(msg.payload.filename || 'Файл');
        const sizeStr = msg.payload.size ? formatBytes(msg.payload.size) : '';
        contentHtml = `
            <div class="tg-file-message" style="display: flex; align-items: center; gap: 12px; padding: 6px 4px; cursor: pointer;">
                <div class="file-icon-btn" style="width: 40px; height: 40px; border-radius: 8px; background: rgba(255,255,255,0.1); display: flex; align-items: center; justify-content: center; font-size: 1.2rem; transition: background 0.2s;">
                    <i class="fas fa-file-arrow-down"></i>
                </div>
                <div class="file-info" style="display: flex; flex-direction: column; gap: 2px;">
                    <span class="file-name" style="font-weight: 500; font-size: 14px; word-break: break-all;">${filename}</span>
                    <span class="file-size" style="font-size: 11px; color: rgba(255,255,255,0.6);">${sizeStr}</span>
                </div>
            </div>
        `;
    } else if (type === 'poll') {
        contentHtml = renderPollHtml(msg);
    }

    const timeStr = new Date(msg.timestamp || Date.now()).toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'});
    const editedHtml = msg.edited ? '<span class="tg-msg-edited">изменено</span>' : '';
    
    let statusIconHtml = '';
    if (msg.direction === 'out') {
        const isTemp = msg.message_id && msg.message_id.toString().startsWith('temp_');
        const currentStatus = isTemp ? 'sending' : (msg.status || 'sent');
        if (currentStatus === 'sending') {
            statusIconHtml = '<span class="msg-status-icon" style="color: var(--text-secondary);"><i class="fas fa-clock"></i></span>';
        } else if (currentStatus === 'failed') {
            statusIconHtml = '<span class="msg-status-icon failed" style="color: #ef4444;" title="Ошибка отправки. Нажмите, чтобы удалить"><i class="fas fa-exclamation-circle"></i></span>';
        } else if (currentStatus === 'read') {
            statusIconHtml = '<span class="msg-status-icon" style="color: #4ade80; opacity: 0.9;" title="Просмотрено"><i class="fas fa-check-double"></i></span>';
        } else if (currentStatus === 'delivered') {
            statusIconHtml = '<span class="msg-status-icon" style="color: var(--text-muted); opacity: 0.7;" title="Доставлено"><i class="fas fa-check-double"></i></span>';
        } else {
            statusIconHtml = '<span class="msg-status-icon" style="color: var(--text-muted); opacity: 0.6;" title="Отправлено"><i class="fas fa-check"></i></span>';
        }
    }
    
    let replyQuoteHtml = '';
    if (msg.payload && msg.payload.reply_to) {
        const replyInfo = msg.payload.reply_to;
        replyQuoteHtml = `
            <div class="reply-quote-bubble" data-reply-id="${escapeHtmlSafe(replyInfo.msg_id)}">
                <div class="reply-quote-author">${escapeHtmlSafe(replyInfo.author)}</div>
                <div class="reply-quote-text">${escapeHtmlSafe(replyInfo.text)}</div>
            </div>
        `;
    }
    
    let forwardHtml = '';
    if (msg.payload && msg.payload.forwarded_from) {
        forwardHtml = `<div class="tg-forward-header" style="font-size: 11px; color: var(--accent-color); font-weight: 500; margin-bottom: 4px; display: flex; align-items: center; gap: 4px;"><i class="fas fa-share" style="font-size: 10px;"></i> Переслано от: ${escapeHtmlSafe(getContactDisplayName(msg.payload.forwarded_from))}</div>`;
    }

    const bubbleExtra = (type === 'text' && isEmojiOnlyText(msg.payload ? msg.payload.content : msg.plaintext)) ? ' emoji-only' : '';
    wrapper.innerHTML = `
        <div class="tg-msg-bubble${bubbleExtra}">
            ${forwardHtml}
            ${replyQuoteHtml}
            ${contentHtml}
            <div class="tg-msg-time">${editedHtml} ${timeStr} ${statusIconHtml}</div>
        </div>
    `;

    // Never put an untrusted media URL in an HTML attribute. Assign it through
    // the DOM only after the element exists and only for an allow-listed scheme.
    if (type === 'image' || type === 'voice' || type === 'video_msg') {
        const mediaEl = wrapper.querySelector(type === 'image' ? 'img.tg-msg-image' : type === 'voice' ? 'audio' : 'video');
        const mediaUrl = safeMediaUrl(content, type === 'voice' ? 'audio' : type === 'video_msg' ? 'video' : 'image');
        if (mediaEl && mediaUrl) mediaEl.src = mediaUrl;
    }
    
    // Ленивая загрузка медиа (единый транспорт с Android): качаем+расшифровываем
    // и подставляем blob URL в <img>/<audio>/<video>. Файлы качаются по клику.
    if (msg.payload && msg.payload.media && type !== 'file') {
        getMediaBlobUrl(msg.payload.media).then(url => {
            if (!url) return;
            const el = wrapper.querySelector('img.tg-msg-image, audio, video');
            if (el) el.src = url;
        }).catch(e => console.error('media load failed', e));
    }

    // Повесим обработчик удаления для сообщений со статусом failed
    const failedIcon = wrapper.querySelector('.msg-status-icon.failed');
    if (failedIcon) {
        failedIcon.addEventListener('click', (e) => {
            e.stopPropagation();
            if (confirm("Удалить это неотправленное сообщение?")) {
                messages = messages.filter(m => m.message_id !== msg.message_id);
                wrapper.remove();
                saveMessagesLocally();
            }
        });
    }
    
    // Select the wrapper and attach interactive events
    if (type === 'voice') {
        const audio = wrapper.querySelector('audio');
        const playBtn = wrapper.querySelector('.voice-play-btn');
        const slider = wrapper.querySelector('.voice-slider');
        const durationSpan = wrapper.querySelector('.voice-duration');
        
        let isSeeking = false;
        
        playBtn.addEventListener('click', () => {
            if (audio.paused) {
                document.querySelectorAll('audio, video').forEach(el => {
                    if (el !== audio) {
                        el.pause();
                        const otherPlayBtn = el.closest('.tg-voice-player')?.querySelector('.voice-play-btn i');
                        if (otherPlayBtn) {
                            otherPlayBtn.className = 'fas fa-play';
                        }
                    }
                });
                audio.play().catch(()=>{});
                playBtn.innerHTML = '<i class="fas fa-pause"></i>';
            } else {
                audio.pause();
                playBtn.innerHTML = '<i class="fas fa-play"></i>';
            }
        });
        
        audio.addEventListener('loadedmetadata', () => {
            const mins = Math.floor(audio.duration / 60);
            const secs = Math.floor(audio.duration % 60).toString().padStart(2, '0');
            durationSpan.textContent = `${mins}:${secs}`;
        });
        
        audio.addEventListener('timeupdate', () => {
            if (!isSeeking && audio.duration) {
                slider.value = (audio.currentTime / audio.duration) * 100;
                const mins = Math.floor(audio.currentTime / 60);
                const secs = Math.floor(audio.currentTime % 60).toString().padStart(2, '0');
                const totalMins = Math.floor(audio.duration / 60);
                const totalSecs = Math.floor(audio.duration % 60).toString().padStart(2, '0');
                durationSpan.textContent = `${mins}:${secs} / ${totalMins}:${totalSecs}`;
            }
        });
        
        audio.addEventListener('ended', () => {
            playBtn.innerHTML = '<i class="fas fa-play"></i>';
            slider.value = 0;
            const mins = Math.floor(audio.duration / 60);
            const secs = Math.floor(audio.duration % 60).toString().padStart(2, '0');
            durationSpan.textContent = `${mins}:${secs}`;
        });
        
        slider.addEventListener('input', () => {
            isSeeking = true;
        });
        
        slider.addEventListener('change', () => {
            if (audio.duration) {
                audio.currentTime = (slider.value / 100) * audio.duration;
            }
            isSeeking = false;
        });
    } else if (type === 'video_msg') {
        const video = wrapper.querySelector('video');
        if (video) {
            video.addEventListener('click', (e) => {
                e.stopPropagation();
                document.querySelectorAll('audio, video').forEach(el => {
                    if (el !== video) {
                        el.pause();
                        const otherPlayBtn = el.closest('.tg-voice-player')?.querySelector('.voice-play-btn i');
                        if (otherPlayBtn) otherPlayBtn.className = 'fas fa-play';
                    }
                });
                
                video.muted = !video.muted;
                if (video.paused) {
                    video.play().catch(()=>{});
                }
            });
        }
    } else if (type === 'file') {
        const fileDiv = wrapper.querySelector('.tg-file-message');
        if (fileDiv) {
            fileDiv.addEventListener('click', async () => {
                const filename = msg.payload.filename || 'download';
                let href = content;
                // Новый формат: качаем+расшифровываем по требованию.
                if (msg.payload.media) {
                    try { href = await getMediaBlobUrl(msg.payload.media); }
                    catch (e) { showStatus('Не удалось скачать файл', 'error'); return; }
                }
                href = safeDownloadUrl(href);
                if (!href) { showStatus('Небезопасный адрес файла', 'error'); return; }
                const link = document.createElement('a');
                link.href = href;
                link.download = String(filename).replace(/[\\/\0]/g, '_').slice(0, 200) || 'download';
                document.body.appendChild(link);
                link.click();
                document.body.removeChild(link);
            });
        }
    }
    
    // Контекстное меню для всех сообщений
    wrapper.addEventListener('contextmenu', (e) => {
        e.preventDefault();
        showContextMenu(e.pageX, e.pageY, msg);
    });
    // Долгое нажатие для мобильных
    {
        let pressTimer;
        wrapper.addEventListener('touchstart', (e) => {
            pressTimer = setTimeout(() => { showContextMenu(e.touches[0].pageX, e.touches[0].pageY, msg); }, 600);
        }, { passive: true });
        wrapper.addEventListener('touchend', () => { clearTimeout(pressTimer); });
        wrapper.addEventListener('touchmove', () => { clearTimeout(pressTimer); });
    }

    // Swipe-to-Reply Gesture on full wrapper width (always swipe left to reply)
    {
        let startX = 0;
        let startY = 0;
        let isSwiping = false;
        
        // Create reply indicator icon
        const replyIndicator = document.createElement('div');
        replyIndicator.className = 'swipe-reply-indicator';
        replyIndicator.innerHTML = '<i class="fas fa-reply"></i>';
        replyIndicator.style.cssText = `
            position: absolute;
            right: 16px;
            top: 50%;
            transform: translateY(-50%) scale(0);
            width: 36px;
            height: 36px;
            border-radius: 50%;
            background: var(--accent-color);
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 15px;
            color: #fff;
            opacity: 0;
            transition: transform 0.15s cubic-bezier(0.34, 1.56, 0.64, 1), opacity 0.15s ease, background 0.15s ease;
            pointer-events: none;
            z-index: 10;
        `;
        wrapper.style.position = 'relative';
        wrapper.appendChild(replyIndicator);
        
        wrapper.addEventListener('touchstart', (e) => {
            startX = e.touches[0].clientX;
            startY = e.touches[0].clientY;
            isSwiping = false;
            const bubble = wrapper.querySelector('.tg-msg-bubble');
            if (bubble) bubble.style.transition = 'none';
        }, { passive: true });
        
        wrapper.addEventListener('touchmove', (e) => {
            const currentX = e.touches[0].clientX;
            const currentY = e.touches[0].clientY;
            const diffX = currentX - startX;
            const diffY = currentY - startY;
            
            // Only swipe left and require horizontal dominance
            if (!isSwiping && diffX < -8 && Math.abs(diffX) > Math.abs(diffY) * 1.2) {
                isSwiping = true;
            }
            
            if (isSwiping) {
                const moveX = Math.max(diffX, -80);
                
                if (moveX < 0) {
                    const bubble = wrapper.querySelector('.tg-msg-bubble');
                    if (bubble) {
                        bubble.style.transform = `translateX(${moveX}px)`;
                    }
                    
                    const progress = Math.min(Math.abs(moveX) / 60, 1);
                    replyIndicator.style.opacity = progress;
                    replyIndicator.style.transform = `translateY(-50%) scale(${progress})`;
                    
                    // Highlight green when swipe threshold is reached
                    if (Math.abs(moveX) >= 60) {
                        replyIndicator.style.background = '#22c55e';
                        replyIndicator.style.transform = `translateY(-50%) scale(1.15)`;
                    } else {
                        replyIndicator.style.background = 'var(--accent-color)';
                    }
                }
            }
        }, { passive: true });
        
        wrapper.addEventListener('touchend', (e) => {
            const bubble = wrapper.querySelector('.tg-msg-bubble');
            if (bubble) {
                bubble.style.transition = 'transform 0.28s cubic-bezier(0.175, 0.885, 0.32, 1.275)';
                bubble.style.transform = 'translateX(0)';
            }
            replyIndicator.style.opacity = '0';
            replyIndicator.style.transform = 'translateY(-50%) scale(0)';
            
            if (isSwiping) {
                const endX = e.changedTouches[0].clientX;
                const diffX = endX - startX;
                if (diffX <= -60) {
                    enableReplyMode(msg);
                }
            }
            isSwiping = false;
        });
    }

    // Reply quote click scroll & flash
    const quoteBubble = wrapper.querySelector('.reply-quote-bubble');
    if (quoteBubble) {
        quoteBubble.addEventListener('click', (e) => {
            e.stopPropagation();
            const replyId = quoteBubble.dataset.replyId;
            const targetMsgEl = findMessageElement(replyId);
            if (targetMsgEl) {
                targetMsgEl.scrollIntoView({ behavior: 'smooth', block: 'center' });
                const targetBubble = targetMsgEl.querySelector('.tg-msg-bubble');
                if (targetBubble) {
                    targetBubble.classList.add('flash-highlight');
                    setTimeout(() => {
                        targetBubble.classList.remove('flash-highlight');
                    }, 1000);
                }
            }
        });
    }

    // Double click reaction on desktop
    wrapper.addEventListener('dblclick', (e) => {
        e.preventDefault();
        toggleDefaultReaction(msg);
    });

    // Double tap reaction on mobile
    let lastTapTime = 0;
    wrapper.addEventListener('touchstart', (e) => {
        wrapper.dataset.tapStartX = e.touches[0].clientX;
        wrapper.dataset.tapStartY = e.touches[0].clientY;
    }, { passive: true });

    wrapper.addEventListener('touchend', (e) => {
        const endX = e.changedTouches[0].clientX;
        const endY = e.changedTouches[0].clientY;
        const startX = wrapper.dataset.tapStartX ? parseFloat(wrapper.dataset.tapStartX) : endX;
        const startY = wrapper.dataset.tapStartY ? parseFloat(wrapper.dataset.tapStartY) : endY;
        const deltaX = Math.abs(endX - startX);
        const deltaY = Math.abs(endY - startY);
        
        if (deltaX < 10 && deltaY < 10) {
            const now = Date.now();
            if (now - lastTapTime < 300) {
                toggleDefaultReaction(msg);
            }
            lastTapTime = now;
        }
    });

    
    // --- Comments Button for Channels ---
    if (myGroupsCache[msg.peer] && myGroupsCache[msg.peer].is_channel && myGroupsCache[msg.peer].linked_group_id) {
        const commentBtn = document.createElement('div');
        commentBtn.className = 'tg-channel-comment-btn';
        commentBtn.innerHTML = '<i class="fas fa-comment"></i> Комментарии';
        commentBtn.style.cssText = 'background: rgba(0,0,0,0.1); padding: 4px 8px; border-radius: 12px; font-size: 11px; margin-top: 6px; cursor: pointer; text-align: center; color: var(--accent-color); font-weight: bold; width: fit-content; align-self: flex-end;';
        commentBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            const linkedId = myGroupsCache[msg.peer].linked_group_id;
            selectContact(linkedId);
            // Optionally, we could pre-fill the input with a reply to this message!
            const plain = String(msg.payload.text || "Медиа");
            document.getElementById('message-input').value = `> К посту: ${plain.substring(0, 20)}...\n`;
        });
        const bubble = wrapper.querySelector('.tg-msg-bubble');
        if (bubble) bubble.appendChild(commentBtn);
    }
    
    messagesContainer.appendChild(wrapper);

    if (type === 'poll') attachPollHandlers(wrapper, msg);
    scheduleSelfDestruct(msg, wrapper);

    // Render reactions initially
    if (msg.reactions && Object.keys(msg.reactions).length > 0) {
        setTimeout(() => {
            updateMessageReactionsUI(msg.message_id, msg.reactions);
        }, 50);
    }

    scrollToBottom();
}

function scrollToBottom() {
    messagesContainer.scrollTop = messagesContainer.scrollHeight;
}

function renderContactsList() {
    contactsContainer.innerHTML = '';
    const contactsSet = new Set();
    messages.forEach(m => {
        if (m.peer) contactsSet.add(m.peer.toLowerCase());
    });
    
    const customList = getCustomContactsList();
    customList.forEach(c => {
        if (c) contactsSet.add(c.toLowerCase());
    });
    
    if (selectedPeer && !contactsSet.has(selectedPeer.toLowerCase())) contactsSet.add(selectedPeer.toLowerCase());

    Object.values(myGroupsCache).forEach(g => {
        if (g && g.id) contactsSet.add(g.id.toLowerCase());
    });
    
    // Sort logic
    const sortedContacts = Array.from(contactsSet).sort((a, b) => {
        const aSet = chatSettingsCache[a] || {};
        const bSet = chatSettingsCache[b] || {};
        
        // Pinned on top
        if (aSet.is_pinned && !bSet.is_pinned) return -1;
        if (!aSet.is_pinned && bSet.is_pinned) return 1;
        
        const aHistory = messages.filter(m => m.peer && m.peer.toLowerCase() === a.toLowerCase());
        const bHistory = messages.filter(m => m.peer && m.peer.toLowerCase() === b.toLowerCase());
        const aLast = aHistory.length > 0 ? aHistory[aHistory.length - 1] : null;
        const bLast = bHistory.length > 0 ? bHistory[bHistory.length - 1] : null;
        const aTime = aLast ? (aLast.timestamp || 0) : Date.now() + 10;
        const bTime = bLast ? (bLast.timestamp || 0) : Date.now() + 10;
        return bTime - aTime;
    });
    
    let archivedCount = 0;
    
    // Back button for Archive view
    if (isArchiveViewOpen) {
        const backBtn = document.createElement('div');
        backBtn.className = 'tg-contact-item';
        backBtn.style.justifyContent = 'center';
        backBtn.innerHTML = `
            <div style="color: var(--accent-color); font-weight: 500; display:flex; align-items:center; gap: 8px;">
                <i class="fas fa-arrow-left"></i> Назад
            </div>
        `;
        backBtn.addEventListener('click', () => {
            isArchiveViewOpen = false;
            renderContactsList();
        });
        contactsContainer.appendChild(backBtn);
    }
    
    sortedContacts.forEach(contactId => {
        const set = chatSettingsCache[contactId] || {};
        if (set.is_archived) {
            archivedCount++;
            if (!isArchiveViewOpen) return; // Hide archived if not in archive view
        } else {
            if (isArchiveViewOpen) return; // Hide normal if in archive view
        }
        
        const prof = profileCache[contactId];
        if (!prof) fetchPeerProfile(contactId);
        
        let dName = getContactDisplayName(contactId);
        
        // Получаем последнее сообщение для превью
        const peerHistory = messages.filter(m => m.peer && m.peer.toLowerCase() === contactId.toLowerCase());
        const lastMsg = peerHistory.length > 0 ? peerHistory[peerHistory.length - 1] : null;
        let lastMsgText = 'Начать общение...';
        if (lastMsg) {
            const type = lastMsg.payload ? lastMsg.payload.type : 'text';
            const content = lastMsg.payload ? lastMsg.payload.content : lastMsg.plaintext;
            let preview = content || '';
            if (type === 'image') preview = '📷 Фотография';
            else if (type === 'voice') preview = '🎤 Голосовое сообщение';
            else if (type === 'video_msg') preview = '📹 Видеосообщение';
            else if (type === 'file') preview = '📂 Файл: ' + (lastMsg.payload?.filename || 'Документ');
            else if (type === 'poll') preview = '📊 Опрос: ' + (lastMsg.payload?.question || '');
            lastMsgText = lastMsg.direction === 'out' ? `Вы: ${preview}` : preview;
        }
        lastMsgText = String(lastMsgText || '');
        if (lastMsgText.length > 25) {
            lastMsgText = lastMsgText.substring(0, 22) + '...';
        }
        const isTyping = !!(typingTimeouts[contactId] && contactId !== myId && !myGroupsCache[contactId]);

        let avatarHtml = `<div class="tg-contact-avatar">${escapeHtmlSafe(dName.charAt(0).toUpperCase())}</div>`;
        let avatarUrl = '';
        if (contactId === myId) {
            avatarHtml = `<div class="tg-contact-avatar"><i class="fas fa-bookmark"></i></div>`;
            dName = 'Избранное';
        } else if (myGroupsCache[contactId]) {
            avatarHtml = `<div class="tg-contact-avatar" style="background: var(--accent-color);">${myGroupsCache[contactId].is_channel ? '<i class="fas fa-bullhorn" style="color:white"></i>' : '<i class="fas fa-users" style="color:white"></i>'}</div>`;
        } else if (prof && prof.avatar_data) {
            avatarHtml = '<div class="tg-contact-avatar" data-avatar-url></div>';
            avatarUrl = prof.avatar_data;
        }

        // Online dot
        let onlineDotHtml = '';
        if (contactId !== myId && !myGroupsCache[contactId] && prof && prof.last_active) {
            const lastAct = new Date(prof.last_active);
            const diffS = (Date.now() - lastAct.getTime()) / 1000;
            if (diffS < 35) {
                onlineDotHtml = '<div class="online-dot"></div>';
            }
        }

        // Count unread messages
        const unreadCount = messages.filter(m => m.peer && m.peer.toLowerCase() === contactId.toLowerCase() && m.direction === 'in' && !m.read_sent).length;
        let badgeHtml = '';
        if (unreadCount > 0 && !set.is_muted) {
            badgeHtml = `<div class="tg-unread-badge">${unreadCount}</div>`;
        } else if (unreadCount > 0 && set.is_muted) {
            badgeHtml = `<div class="tg-unread-badge" style="background: var(--text-muted);">${unreadCount}</div>`;
        }

        let statusIcons = '';
        if (set.is_pinned || set.is_muted) {
            statusIcons = '<div class="tg-chat-status-icons">';
            if (set.is_pinned) statusIcons += '<i class="fas fa-thumbtack"></i>';
            if (set.is_muted) statusIcons += '<i class="fas fa-bell-slash"></i>';
            statusIcons += '</div>';
        }

        // Build wrapper
        const wrapper = document.createElement('div');
        wrapper.className = 'tg-swipe-container';
        
        wrapper.innerHTML = `
            <div class="tg-swipe-actions">
                <button class="tg-action-btn pin" data-chat-action="is_pinned">
                    <i class="fas ${set.is_pinned ? 'fa-thumbtack-slash' : 'fa-thumbtack'}"></i>
                </button>
                <button class="tg-action-btn mute" data-chat-action="is_muted">
                    <i class="fas ${set.is_muted ? 'fa-bell' : 'fa-bell-slash'}"></i>
                </button>
                <button class="tg-action-btn archive" data-chat-action="is_archived">
                    <i class="fas ${set.is_archived ? 'fa-box-open' : 'fa-archive'}"></i>
                </button>
                <button class="tg-action-btn delete" data-chat-action="delete">
                    <i class="fas fa-trash"></i>
                </button>
            </div>
            <div class="tg-contact-item swipeable ${selectedPeer && selectedPeer.toLowerCase() === contactId.toLowerCase() ? 'active' : ''}">
                ${avatarHtml}
                <div class="tg-contact-info">
                    <div class="tg-contact-name">${escapeHtmlSafe(dName)}${statusIcons}</div>
                    <div style="font-size: 13px; color: var(--text-secondary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; margin-top: 2px;">
                        ${isTyping ? '<span class="tg-contact-typing">печатает...</span>' : escapeHtmlSafe(lastMsgText)}
                    </div>
                </div>
                ${badgeHtml}
                ${onlineDotHtml}
            </div>
        `;

        if (avatarUrl) setSafeBackgroundImage(wrapper.querySelector('[data-avatar-url]'), avatarUrl);
        wrapper.querySelectorAll('[data-chat-action]').forEach(button => {
            button.addEventListener('click', event => {
                event.stopPropagation();
                const action = button.dataset.chatAction;
                if (action === 'delete') {
                    selectContact(contactId);
                    if (deleteChatBtn) deleteChatBtn.click();
                } else {
                    toggleChatSetting(contactId, action);
                }
            });
        });
        
        const swipeableItem = wrapper.querySelector('.swipeable');
        swipeableItem.addEventListener('click', (e) => {
            // Prevent select if it was swiped
            if (swipeableItem.style.transform && swipeableItem.style.transform !== 'translateX(0px)') {
                return;
            }
            selectContact(contactId);
        });
        
        initSwipeGestures(wrapper, swipeableItem, contactId);
        contactsContainer.appendChild(wrapper);
    });
    
    // Render Archive folder if needed
    if (!isArchiveViewOpen && archivedCount > 0) {
        const archFolder = document.createElement('div');
        archFolder.className = 'tg-contact-item';
        archFolder.innerHTML = `
            <div class="tg-contact-avatar" style="background: var(--bg-secondary); border: 1px solid var(--border-color); color: var(--text-secondary);">
                <i class="fas fa-archive"></i>
            </div>
            <div class="tg-contact-info">
                <div class="tg-contact-name">Архив</div>
                <div style="font-size: 13px; color: var(--text-secondary); margin-top: 2px;">
                    ${archivedCount} чатов
                </div>
            </div>
        `;
        archFolder.addEventListener('click', () => {
            isArchiveViewOpen = true;
            renderContactsList();
        });
        contactsContainer.insertBefore(archFolder, contactsContainer.firstChild);
    }
}

peerAvatar.addEventListener('click', () => {
    chatScreen.classList.remove('chat-open');
    selectedPeer = null;
});

async function fetchAndRenderGlobalUsers() {
    if (!myId || !serverUrl) return;
    try {
        const res = await fetch(`${serverUrl}/users`, {
            headers: { 'Authorization': `Bearer ${sessionToken}`, 'Bypass-Tunnel-Reminder': 'true' }
        });
        if (!res.ok) return;
        const data = await res.json();
        
        let cacheUpdated = false;
        data.users.forEach(user => {
            const uid = user.user_id.toLowerCase();
            // Don't overwrite our own full profile with local copy if it's already there
            if (uid !== myId) {
                profileCache[uid] = user;
                cacheUpdated = true;
            }
        });
        
        if (cacheUpdated) {
            renderContactsList();
        }

        if (globalUsersContainer) {
            globalUsersContainer.innerHTML = '';
            data.users.filter(u => u.user_id !== myId).forEach(user => {
                const u = user.user_id;
                const dName = String(user.display_name || u);
                const item = document.createElement('div');
                item.className = 'tg-contact-item';
                
                let avatarHtml = `<div class="tg-contact-avatar">${escapeHtmlSafe(dName.charAt(0).toUpperCase())}</div>`;
                if (user.avatar_data) {
                    avatarHtml = '<div class="tg-contact-avatar" data-avatar-url></div>';
                }
                
                let statusText = 'не в сети';
                let statusClass = 'offline';
                if (user.last_active) {
                    const lastAct = new Date(user.last_active);
                    const diffS = (Date.now() - lastAct.getTime()) / 1000;
                    if (diffS < 35) {
                        statusText = 'в сети';
                        statusClass = 'online';
                    }
                }

                item.innerHTML = `
                    ${avatarHtml}
                    <div class="tg-contact-info">
                    <div class="tg-contact-name">${escapeHtmlSafe(dName)}</div>
                        <div style="font-size: 11px; color: ${statusClass === 'online' ? '#22c55e' : 'var(--text-secondary)'};">${statusText}</div>
                    </div>
                `;
                if (user.avatar_data) setSafeBackgroundImage(item.querySelector('[data-avatar-url]'), user.avatar_data);
                item.addEventListener('click', () => selectContact(u));
                globalUsersContainer.appendChild(item);
            });
        }
    } catch (e) {
        console.error("Error fetching global users:", e);
    }
}

// Автоматическое заполнение адреса сервера
const serverInputGroup = document.getElementById('server-input-group');
const savedServer = localStorage.getItem('last_server_url');

// Если мы открыли сайт с реального домена (туннеля), это наш 100% правильный адрес
if (window.location.hostname !== 'localhost' && window.location.hostname !== '127.0.0.1' && window.location.protocol.startsWith('http') && !window.location.hostname.includes('192.168.')) {
    serverInput.value = window.location.origin;
    serverInputGroup.style.display = 'none'; // Скрываем, всё автоматически
} 
// Иначе мы в APK (localhost/file) или локальной сети
else {
    serverInputGroup.style.display = 'block'; // Показываем, чтобы пользователь мог ввести или проверить
    const currentDefaultVps = 'https://your-server.example.com';
    if (savedServer && savedServer !== currentDefaultVps && !savedServer.includes('localhost') && !savedServer.includes('127.0.0.1') && !savedServer.includes('192.168.')) {
        console.log("Resetting legacy server URL in localStorage:", savedServer);
        serverInput.value = currentDefaultVps;
        localStorage.setItem('last_server_url', currentDefaultVps);
    } else if (savedServer) {
        serverInput.value = savedServer;
    } else {
        serverInput.value = currentDefaultVps;
        localStorage.setItem('last_server_url', currentDefaultVps);
    }
}

// Сохраняем при изменении (полезно для APK)
serverInput.addEventListener('input', () => {
    localStorage.setItem('last_server_url', serverInput.value.trim());
});

// --- Форматирование размера файла ---
function formatBytes(bytes, decimals = 2) {
    if (bytes === 0) return '0 Bytes';
    const k = 1024;
    const dm = decimals < 0 ? 0 : decimals;
    const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(dm)) + ' ' + sizes[i];
}

// --- Обработка файлов и изображений ---
const fileUpload = document.getElementById('file-upload');
const attachBtn = document.getElementById('attach-btn');

if(attachBtn && fileUpload) {
    attachBtn.addEventListener('click', () => {
        fileUpload.click();
    });

    fileUpload.addEventListener('change', async (e) => {
        const file = e.target.files[0];
        if (!file) return;
        
        fileUpload.value = '';
        
        // Если это картинка, сжимаем и отправляем как type: 'image'
        if (file.type.startsWith('image/')) {
            const reader = new FileReader();
            reader.onload = (event) => {
                const img = new Image();
                img.onload = () => {
                    const canvas = document.createElement('canvas');
                    const MAX_WIDTH = 1000;
                    const MAX_HEIGHT = 1000;
                    let width = img.width;
                    let height = img.height;

                    if (width > height) {
                        if (width > MAX_WIDTH) {
                            height *= MAX_WIDTH / width;
                            width = MAX_WIDTH;
                        }
                    } else {
                        if (height > MAX_HEIGHT) {
                            width *= MAX_HEIGHT / height;
                            height = MAX_HEIGHT;
                        }
                    }

                    canvas.width = width;
                    canvas.height = height;
                    const ctx = canvas.getContext('2d');
                    ctx.drawImage(img, 0, 0, width, height);

                    // Единый транспорт: шифруем+грузим в /upload (совместимо с Android).
                    canvas.toBlob(async (blob) => {
                        if (!blob) return;
                        const bytes = new Uint8Array(await blob.arrayBuffer());
                        await sendMediaFile(bytes, 'image/jpeg', 'image', 'image');
                    }, 'image/jpeg', 0.7);
                };
                img.src = event.target.result;
            };
            reader.readAsDataURL(file);
        } else {
            // Любой другой файл — шифруем+грузим в /upload.
            const bytes = new Uint8Array(await file.arrayBuffer());
            await sendMediaFile(bytes, file.type || 'application/octet-stream', 'file', 'file', {
                filename: file.name,
                size: file.size
            });
        }
    });
}

// --- Контекстное меню и Редактирование ---
const msgContextMenu = document.getElementById('msg-context-menu');
const ctxEdit = document.getElementById('ctx-edit');
const ctxDelete = document.getElementById('ctx-delete');
let contextTargetMsg = null;

function showContextMenu(x, y, msg) {
    contextTargetMsg = msg;
    
    // Позиционирование
    msgContextMenu.style.left = `${Math.min(Math.max(10, x), window.innerWidth - 220)}px`;
    msgContextMenu.style.top = `${Math.min(Math.max(10, y), window.innerHeight - 180)}px`;
    msgContextMenu.classList.remove('hidden');
    
    // Скрываем "Редактировать" для картинок
    const type = msg.payload ? msg.payload.type : 'text';
    if (type !== 'text') {
        ctxEdit.style.display = 'none';
    } else {
        ctxEdit.style.display = 'flex';
    }
    const ctxPinLabel = document.getElementById('ctx-pin-label');
    if (ctxPinLabel) {
        const cur = getPinnedId(selectedPeer);
        ctxPinLabel.textContent = (cur && cur === msg.message_id) ? 'Открепить' : 'Закрепить';
    }
}

async function toggleDefaultReaction(msg) {
    if (!msg.message_id || msg.message_id.toString().startsWith('temp_')) return; // Cannot react to temporary messages
    const defaultEmoji = localStorage.getItem('settings_default_reaction') || '❤️';
    
    if (!msg.reactions) msg.reactions = Object.create(null);
    
    // Toggle reaction
    const alreadyReacted = msg.reactions[myId] === defaultEmoji;
    const emojiToSend = alreadyReacted ? null : defaultEmoji;
    
    if (emojiToSend) {
        msg.reactions[myId] = emojiToSend;
    } else {
        delete msg.reactions[myId];
    }
    
    await saveMessagesLocally();
    updateMessageReactionsUI(msg.message_id, msg.reactions);
    
    // Send control message to peer
    const targetPeer = msg.peer;
    if (targetPeer) {
        await sendPayloadMessage({
            type: 'reaction',
            target_id: msg.message_id,
            emoji: emojiToSend
        }, targetPeer);
    }
}

// Add event listeners for context menu reactions
document.querySelectorAll('.ctx-reaction-btn').forEach(btn => {
    btn.addEventListener('click', (e) => {
        e.stopPropagation();
        if (contextTargetMsg) {
            const emoji = btn.dataset.emoji;
            // Temporarily set it as default reaction logic to toggle it
            const defaultEmoji = localStorage.getItem('settings_default_reaction');
            localStorage.setItem('settings_default_reaction', emoji);
            toggleDefaultReaction(contextTargetMsg).then(() => {
                if (defaultEmoji) {
                    localStorage.setItem('settings_default_reaction', defaultEmoji);
                } else {
                    localStorage.removeItem('settings_default_reaction');
                }
            });
        }
        msgContextMenu.classList.add('hidden');
    });
});

function updateMessageReactionsUI(msgId, reactions) {
    const msgEl = findMessageElement(msgId);
    if (!msgEl) return;
    
    const bubble = msgEl.querySelector('.tg-msg-bubble');
    if (!bubble) return;
    
    let reactionsEl = bubble.querySelector('.tg-msg-reactions');
    
    // Filter out invalid/empty reactions
    const activeReactions = Object.entries(reactions || {}).filter(([user, emoji]) => !!emoji);
    if (activeReactions.length === 0) {
        if (reactionsEl) reactionsEl.remove();
        return;
    }
    
    // Group by emoji
    const emojiGroups = Object.create(null);
    activeReactions.forEach(([user, emoji]) => {
        emojiGroups[emoji] = (emojiGroups[emoji] || 0) + 1;
    });
    
    if (!reactionsEl) {
        reactionsEl = document.createElement('div');
        reactionsEl.className = 'tg-msg-reactions';
        bubble.appendChild(reactionsEl);
    }

    reactionsEl.replaceChildren();
    Object.entries(emojiGroups).forEach(([emoji, count]) => {
        const item = document.createElement('span');
        item.className = 'tg-reaction-item';
        item.textContent = emoji;
        if (count > 1) {
            const countEl = document.createElement('span');
            countEl.className = 'tg-reaction-count';
            countEl.textContent = ` ${count}`;
            item.appendChild(countEl);
        }
        reactionsEl.appendChild(item);
    });
    
    // Click on reaction badge toggles/removes my reaction
    reactionsEl.onclick = (e) => {
        e.stopPropagation();
        const msg = messages.find(m => m.message_id === msgId);
        if (msg) toggleDefaultReaction(msg);
    };
}

// Закрытие меню при клике куда угодно
document.addEventListener('click', (e) => {
    if (!msgContextMenu.classList.contains('hidden')) {
        msgContextMenu.classList.add('hidden');
    }
});

if (ctxDelete && ctxEdit) {
    ctxDelete.addEventListener('click', () => {
        if (!contextTargetMsg) return;
        
        // Show delete confirmation modal
        if (deleteConfirmModal) {
            deleteConfirmModal.classList.remove('hidden');
            
            // Set button click handlers
            deleteForMeBtn.onclick = async () => {
                deleteConfirmModal.classList.add('hidden');
                messages = messages.filter(m => m.message_id !== contextTargetMsg.message_id);
                await saveMessagesLocally();
                selectContact(selectedPeer);
            };
            
            deleteForEveryoneBtn.onclick = async () => {
                deleteConfirmModal.classList.add('hidden');
                await sendPayloadMessage({ type: 'delete', target_id: contextTargetMsg.message_id });
                messages = messages.filter(m => m.message_id !== contextTargetMsg.message_id);
                await saveMessagesLocally();
                selectContact(selectedPeer);
            };
            
            closeDeleteModalBtn.onclick = () => {
                deleteConfirmModal.classList.add('hidden');
            };
            
            deleteConfirmModal.onclick = (e) => {
                if (e.target === deleteConfirmModal) {
                    deleteConfirmModal.classList.add('hidden');
                }
            };
        }
    });

    ctxEdit.addEventListener('click', () => {
        if (!contextTargetMsg) return;
        
        const oldText = contextTargetMsg.payload ? contextTargetMsg.payload.content : contextTargetMsg.plaintext;
        
        // Enter edit mode
        editingMsgId = contextTargetMsg.message_id;
        replyToMsgId = null;
        messageInput.value = oldText;
        
        if (replyPreviewContainer) {
            replyPreviewContainer.classList.remove('hidden');
            void replyPreviewContainer.offsetWidth; // Force layout recalculation
            replyPreviewContainer.classList.add('active');
            replyPreviewAuthor.textContent = "Редактирование сообщения";
            replyPreviewText.textContent = oldText;
        }
        
        if (sendBtn) {
            sendBtn.innerHTML = '<i class="fas fa-check" style="font-size: 1.2rem;"></i>';
        }
        
        messageInput.focus();
        messageInput.dispatchEvent(new Event('input'));
    });
}

// --- WebRTC Logic ---

// Сигналинг звонков: мгновенно через WebSocket + дублируем через сообщения
// (на случай, если у собеседника нет WS). Приёмник дедуплицирует по sig_id.
const processedCallSignals = new Set();
const WS_SIGNAL_TYPES = { offer: 'webrtc_offer', answer: 'webrtc_answer', candidate: 'webrtc_ice', hangup: 'webrtc_hangup', busy: 'webrtc_busy' };

function sendCallSignal(subtype, data, peer) {
    if (!peer) return;
    data = data || {};
    const sigId = 'sig_' + Date.now() + '_' + Math.random().toString(36).slice(2, 9);
    // Плоский формат WS-сигнала — единый для веб- и нативного клиента
    const wsMsg = { type: WS_SIGNAL_TYPES[subtype], recipient_id: peer, sig_id: sigId };
    if (subtype === 'offer') {
        wsMsg.sdp = data.sdp && data.sdp.sdp ? data.sdp.sdp : data.sdp;
        wsMsg.isVideoCall = !!data.videoEnabled;
    } else if (subtype === 'answer') {
        wsMsg.sdp = data.sdp && data.sdp.sdp ? data.sdp.sdp : data.sdp;
    } else if (subtype === 'candidate' && data.candidate) {
        wsMsg.candidate = data.candidate.candidate;
        wsMsg.sdpMid = data.candidate.sdpMid;
        wsMsg.sdpMLineIndex = data.candidate.sdpMLineIndex;
    }
    try {
        if (realtimeWs && realtimeWs.readyState === 1) {
            realtimeWs.send(JSON.stringify(wsMsg));
        }
    } catch (e) { console.warn('WS signal send failed:', e); }
    // Дублируем через E2E-сообщения (доставка при offline-поллинге)
    sendPayloadMessage(Object.assign({ type: 'webrtc', subtype: subtype, sig_id: sigId }, data), peer);
}

const rtcConfig = {
    iceServers: [
        { urls: 'stun:stun.l.google.com:19302' },
        { urls: 'stun:YOUR_SERVER_IP:3478' },
        {
            urls: [
                'turn:YOUR_SERVER_IP:3478?transport=udp',
                'turn:YOUR_SERVER_IP:3478?transport=tcp'
            ],
            username: 'YOUR_TURN_USERNAME',
            credential: 'YOUR_TURN_SECRET'
        }
    ]
};

function startCallTimer() {
    if (callTimerInterval) clearInterval(callTimerInterval);
    callSeconds = 0;
    document.getElementById('mini-call-timer').textContent = "00:00";
    callTimerInterval = setInterval(() => {
        callSeconds++;
        const mins = Math.floor(callSeconds / 60).toString().padStart(2, '0');
        const secs = (callSeconds % 60).toString().padStart(2, '0');
        const timeStr = `${mins}:${secs}`;
        document.getElementById('mini-call-timer').textContent = timeStr;
        callStatus.textContent = `Разговор: ${timeStr}`;
    }, 1000);
}

async function processRemoteIceCandidatesQueue() {
    if (!peerConnection || !peerConnection.remoteDescription) return;
    console.log(`Processing ${remoteIceCandidatesQueue.length} queued ICE candidates`);
    while (remoteIceCandidatesQueue.length > 0) {
        const candidateData = remoteIceCandidatesQueue.shift();
        try {
            await peerConnection.addIceCandidate(new RTCIceCandidate(candidateData));
        } catch (e) {
            console.error("Error adding queued ICE candidate:", e);
        }
    }
}

async function initiateCall(videoEnabled) {
    if (!selectedPeer) return;
    callWithPeer = selectedPeer;
    isCallIncoming = false;
    
    // Set minimized bar text
    document.getElementById('mini-call-label').textContent = `Звонок: ${callWithPeer}`;
    
    // Show UI
    callScreen.classList.remove('hidden');
    callPeerName.textContent = callWithPeer;
    callStatus.textContent = 'Звоним...';
    callIncomingControls.classList.add('hidden');
    callActiveControls.classList.remove('hidden');
    
    // Set Avatar in call UI
    const prof = profileCache[callWithPeer] || { display_name: callWithPeer };
    const dName = prof.display_name || callWithPeer;
    const callAvatar = document.getElementById('call-avatar');
    if (callAvatar) {
        if (prof.avatar_data) {
            callAvatar.textContent = '';
            setSafeBackgroundImage(callAvatar, prof.avatar_data);
        } else {
            callAvatar.textContent = dName.charAt(0).toUpperCase();
            callAvatar.style.backgroundImage = 'none';
        }
    }
    
    const videoContainer = document.querySelector('.call-video-container');
    const avatarContainer = document.getElementById('call-avatar-container');
    
    if (videoEnabled) {
        callVideoBtn.classList.remove('off');
        localVideo.classList.add('active');
        videoContainer.classList.add('active');
        avatarContainer.classList.add('hidden');
    } else {
        callVideoBtn.classList.add('off');
        localVideo.classList.remove('active');
        videoContainer.classList.remove('active');
        avatarContainer.classList.remove('hidden');
    }
    
    try {
        let videoConstraints = videoEnabled ? { facingMode: currentFacingMode } : false;
        localStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: videoConstraints });
    } catch (err) {
        if (err.name === 'NotFoundError' && videoEnabled) {
            alert("Камера не найдена. Соединение установлено в аудиорежиме.");
            videoEnabled = false;
            callVideoBtn.classList.add('off');
            localVideo.classList.remove('active');
            videoContainer.classList.remove('active');
            avatarContainer.classList.remove('hidden');
            localStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
        } else {
            console.error("Camera error:", err);
            return;
        }
    }
    try {
        localVideo.srcObject = localStream;
        
        peerConnection = new RTCPeerConnection(rtcConfig);
        
        localStream.getTracks().forEach(track => {
            peerConnection.addTrack(track, localStream);
        });
        
        peerConnection.ontrack = (event) => {
            if (event.streams && event.streams[0]) {
                remoteVideo.srcObject = event.streams[0];
                remoteStream = event.streams[0];
                
                const hasVideo = event.streams[0].getVideoTracks().length > 0;
                if (hasVideo) {
                    videoContainer.classList.add('active');
                    avatarContainer.classList.add('hidden');
                }
            }
        };
        
        peerConnection.onicecandidate = (event) => {
            if (event.candidate) {
                sendCallSignal('candidate', { candidate: event.candidate }, callWithPeer);
            }
        };
        
        peerConnection.oniceconnectionstatechange = () => {
            console.log("ICE Connection State:", peerConnection?.iceConnectionState);
            if (peerConnection) {
                if (peerConnection.iceConnectionState === "failed" || peerConnection.iceConnectionState === "closed") {
                    callStatus.textContent = "Соединение разорвано";
                    setTimeout(cleanupCall, 2000);
                } else if (peerConnection.iceConnectionState === "connected") {
                    const timerText = document.getElementById('mini-call-timer').textContent;
                    callStatus.textContent = timerText !== "00:00" ? `Разговор: ${timerText}` : "Разговор...";
                }
            }
        };
        
        const offer = await peerConnection.createOffer();
        await peerConnection.setLocalDescription(offer);
        
        sendCallSignal('offer', { sdp: offer, videoEnabled: videoEnabled }, callWithPeer);
    } catch (e) {
        console.error("Camera/Mic error:", e);
        callStatus.textContent = 'Нет доступа к камере/микрофону';
        setTimeout(endCall, 2000);
    }
}

// --- ИНТЕГРАЦИЯ УВЕДОМЛЕНИЙ И РИНГТОНА ДЛЯ ЗВОНКОВ ---
let ringtoneInterval = null;
function startRingtone() {
    stopRingtone();
    const playTone = () => {
        try {
            const AudioContext = window.AudioContext || window.webkitAudioContext;
            if (!AudioContext) return;
            const ctx = new AudioContext();
            const now = ctx.currentTime;
            
            const osc = ctx.createOscillator();
            const gain = ctx.createGain();
            osc.type = 'sine';
            osc.frequency.setValueAtTime(440, now);
            osc.frequency.exponentialRampToValueAtTime(880, now + 0.4);
            
            gain.gain.setValueAtTime(0, now);
            gain.gain.linearRampToValueAtTime(0.15, now + 0.05);
            gain.gain.exponentialRampToValueAtTime(0.001, now + 0.8);
            
            osc.connect(gain);
            gain.connect(ctx.destination);
            osc.start(now);
            osc.stop(now + 0.8);
        } catch (e) {}
    };
    playTone();
    ringtoneInterval = setInterval(playTone, 1500);
}

function stopRingtone() {
    if (ringtoneInterval) {
        clearInterval(ringtoneInterval);
        ringtoneInterval = null;
    }
}

async function showCallNotification(sender, isVideo) {
    const prof = profileCache[sender] || { display_name: sender };
    const name = prof.display_name || sender;
    const text = isVideo ? "Входящий видеозвонок..." : "Входящий аудиозвонок...";

    const LocalNotifications = window.Capacitor?.Plugins?.LocalNotifications;
    if (LocalNotifications) {
        try {
            const perm = await LocalNotifications.checkPermissions();
            if (perm.display !== 'granted') {
                await LocalNotifications.requestPermissions();
            }
            await LocalNotifications.schedule({
                notifications: [
                    {
                        title: name,
                        body: text,
                        id: 99999,
                        extra: { senderId: sender }
                    }
                ]
            });
            return;
        } catch (e) {
            console.warn("LocalNotifications call warning:", e);
        }
    }

    if (!('Notification' in window) || Notification.permission !== 'granted') return;
    try {
        const notif = new Notification(name, {
            body: text,
            icon: safeDownloadUrl(prof.avatar_data) || 'img/icon.png',
            tag: 'incoming-call'
        });
        notif.onclick = () => {
            window.focus();
            selectContact(sender);
        };
    } catch (e) {
        console.warn("Browser notification warning:", e);
    }
}

async function handleWebRTCMessage(sender, payload) {
    if (sender) sender = sender.toLowerCase();
    // Дедупликация: сигнал мог прийти и по WS, и через сообщение
    if (payload.sig_id) {
        if (processedCallSignals.has(payload.sig_id)) return;
        processedCallSignals.add(payload.sig_id);
        if (processedCallSignals.size > 500) {
            processedCallSignals.delete(processedCallSignals.values().next().value);
        }
    }
    if (payload.subtype === 'offer') {
        if (callWithPeer && callWithPeer !== sender) {
            sendCallSignal('busy', {}, sender);
            return;
        }
        
        callWithPeer = sender;
        isCallIncoming = true;
        
        // Trigger notification and ringtone
        showCallNotification(sender, payload.videoEnabled);
        startRingtone();
        
        // Set minimized bar text
        document.getElementById('mini-call-label').textContent = `Звонок: ${callWithPeer}`;
        
        callScreen.classList.remove('hidden');
        callPeerName.textContent = callWithPeer;
        callStatus.textContent = payload.videoEnabled ? 'Входящий видеозвонок' : 'Входящий аудиозвонок';
        
        // Set Avatar in call UI
        const prof = profileCache[sender] || { display_name: sender };
        const dName = prof.display_name || sender;
        const callAvatar = document.getElementById('call-avatar');
        if (callAvatar) {
            if (prof.avatar_data) {
                callAvatar.textContent = '';
                setSafeBackgroundImage(callAvatar, prof.avatar_data);
            } else {
                callAvatar.textContent = dName.charAt(0).toUpperCase();
                callAvatar.style.backgroundImage = 'none';
            }
        }
        
        const videoContainer = document.querySelector('.call-video-container');
        const avatarContainer = document.getElementById('call-avatar-container');
        
        videoContainer.classList.remove('active');
        avatarContainer.classList.remove('hidden');
        
        callIncomingControls.classList.remove('hidden');
        callActiveControls.classList.add('hidden');
        
        peerConnection = new RTCPeerConnection(rtcConfig);
        
        peerConnection.ontrack = (event) => {
            if (event.streams && event.streams[0]) {
                remoteVideo.srcObject = event.streams[0];
                remoteStream = event.streams[0];
                
                const hasVideo = event.streams[0].getVideoTracks().length > 0;
                if (hasVideo) {
                    videoContainer.classList.add('active');
                    avatarContainer.classList.add('hidden');
                }
            }
        };
        
        peerConnection.onicecandidate = (event) => {
            if (event.candidate) {
                sendCallSignal('candidate', { candidate: event.candidate }, callWithPeer);
            }
        };

        peerConnection.oniceconnectionstatechange = () => {
            console.log("ICE Connection State:", peerConnection?.iceConnectionState);
            if (peerConnection) {
                if (peerConnection.iceConnectionState === "failed" || peerConnection.iceConnectionState === "closed") {
                    callStatus.textContent = "Соединение разорвано";
                    setTimeout(cleanupCall, 2000);
                } else if (peerConnection.iceConnectionState === "connected") {
                    const timerText = document.getElementById('mini-call-timer').textContent;
                    callStatus.textContent = timerText !== "00:00" ? `Разговор: ${timerText}` : "Разговор...";
                }
            }
        };
        
        await peerConnection.setRemoteDescription(new RTCSessionDescription(payload.sdp));
        await processRemoteIceCandidatesQueue();
        
        if (payload.videoEnabled) {
            callVideoBtn.classList.remove('off');
        } else {
            callVideoBtn.classList.add('off');
        }
    } 
    else if (payload.subtype === 'answer' && peerConnection) {
        if (peerConnection.signalingState !== 'have-local-offer') {
            console.warn('Ignoring answer in state:', peerConnection.signalingState);
            return;
        }
        await peerConnection.setRemoteDescription(new RTCSessionDescription(payload.sdp));
        callStatus.textContent = 'Соединение установлено';
        startCallTimer();
        await processRemoteIceCandidatesQueue();
    }
    else if (payload.subtype === 'candidate') {
        if (peerConnection && peerConnection.remoteDescription) {
            try {
                await peerConnection.addIceCandidate(new RTCIceCandidate(payload.candidate));
            } catch (e) {
                console.error("Error adding ICE candidate directly:", e);
            }
        } else {
            console.log("Queueing received ICE candidate (remoteDescription not set yet)");
            remoteIceCandidatesQueue.push(payload.candidate);
        }
    } 
    else if (payload.subtype === 'hangup') {
        cleanupCall();
    }
    else if (payload.subtype === 'busy') {
        callStatus.textContent = 'Абонент занят';
        setTimeout(cleanupCall, 2000);
    }
}

async function acceptCall() {
    if (!peerConnection) return;
    stopRingtone();
    
    callIncomingControls.classList.add('hidden');
    callActiveControls.classList.remove('hidden');
    callStatus.textContent = 'Соединение...';
    
    const videoEnabled = !callVideoBtn.classList.contains('off');
    const videoContainer = document.querySelector('.call-video-container');
    const avatarContainer = document.getElementById('call-avatar-container');
    
    try {
        let videoConstraints = videoEnabled ? { facingMode: currentFacingMode } : false;
        localStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: videoConstraints });
    } catch (err) {
        if (err.name === 'NotFoundError' && videoEnabled) {
            alert("Камера не найдена. Соединение установлено в аудиорежиме.");
            videoEnabled = false;
            callVideoBtn.classList.add('off');
            localStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
        } else {
            console.error("Camera error:", err);
            return;
        }
    }
    try {
        localVideo.srcObject = localStream;
        
        localStream.getTracks().forEach(track => {
            peerConnection.addTrack(track, localStream);
        });

        if (videoEnabled) {
            localVideo.classList.add('active');
            videoContainer.classList.add('active');
            avatarContainer.classList.add('hidden');
        } else {
            localVideo.classList.remove('active');
        }
        
        const answer = await peerConnection.createAnswer();
        await peerConnection.setLocalDescription(answer);
        
        sendCallSignal('answer', { sdp: answer }, callWithPeer);
        
        callStatus.textContent = 'Соединение установлено';
        startCallTimer();
    } catch (e) {
        console.error("Camera/Mic error:", e);
        declineCall();
    }
}

function declineCall() {
    if (callWithPeer) {
        sendCallSignal('hangup', {}, callWithPeer);
    }
    cleanupCall();
}

function endCall() {
    if (callWithPeer) {
        sendCallSignal('hangup', {}, callWithPeer);
    }
    cleanupCall();
}

function cleanupCall() {
    stopRingtone();
    if (peerConnection) {
        peerConnection.close();
        peerConnection = null;
    }
    if (localStream) {
        localStream.getTracks().forEach(track => track.stop());
        localStream = null;
    }
    localVideo.srcObject = null;
    remoteVideo.srcObject = null;
    remoteStream = null;
    callWithPeer = null;
    isCallIncoming = false;
    
    // Clear Call Timer
    if (callTimerInterval) {
        clearInterval(callTimerInterval);
        callTimerInterval = null;
    }
    callSeconds = 0;
    
    // Clear ICE queue
    remoteIceCandidatesQueue = [];
    
    // Hide call minimized bar
    const miniCallBar = document.getElementById('call-minimized-bar');
    if (miniCallBar) {
        miniCallBar.classList.add('hidden');
    }
    
    // Reset layout classes
    document.querySelector('.call-video-container').classList.remove('active');
    document.getElementById('call-avatar-container').classList.remove('hidden');
    localVideo.classList.remove('active');
    
    callScreen.classList.add('hidden');
}

function toggleMute() {
    if (localStream) {
        const audioTrack = localStream.getAudioTracks()[0];
        if (audioTrack) {
            audioTrack.enabled = !audioTrack.enabled;
            const isMuted = !audioTrack.enabled;
            const miniMuteBtn = document.getElementById('mini-mute-btn');
            if (isMuted) {
                callMuteBtn.classList.add('off');
                if (miniMuteBtn) {
                    miniMuteBtn.innerHTML = '<i class="fas fa-microphone-slash"></i>';
                    miniMuteBtn.classList.add('active');
                }
            } else {
                callMuteBtn.classList.remove('off');
                if (miniMuteBtn) {
                    miniMuteBtn.innerHTML = '<i class="fas fa-microphone"></i>';
                    miniMuteBtn.classList.remove('active');
                }
            }
        }
    }
}

async function toggleVideo() {
    if (localStream) {
        const videoTrack = localStream.getVideoTracks()[0];
        if (videoTrack) {
            videoTrack.enabled = !videoTrack.enabled;
            const avatarContainer = document.getElementById('call-avatar-container');
            const videoContainer = document.querySelector('.call-video-container');
            if (videoTrack.enabled) {
                callVideoBtn.classList.remove('off');
                localVideo.classList.add('active');
                videoContainer.classList.add('active');
                avatarContainer.classList.add('hidden');
            } else {
                callVideoBtn.classList.add('off');
                localVideo.classList.remove('active');
                
                // If remote also does not have video active, show avatar
                const remoteHasVideo = remoteStream && remoteStream.getVideoTracks().length > 0 && remoteStream.getVideoTracks()[0].enabled;
                if (!remoteHasVideo) {
                    videoContainer.classList.remove('active');
                    avatarContainer.classList.remove('hidden');
                }
            }
        } else {
            alert("Включение видео во время аудиозвонка пока не поддерживается.");
        }
    }
}

// --- Уведомления и звуки (Web Audio API) ---
function playNotificationSound() {
    try {
        const AudioContext = window.AudioContext || window.webkitAudioContext;
        if (!AudioContext) return;
        const ctx = new AudioContext();
        
        const now = ctx.currentTime;
        
        const osc1 = ctx.createOscillator();
        const gain1 = ctx.createGain();
        osc1.type = 'sine';
        osc1.frequency.setValueAtTime(880, now); // A5
        gain1.gain.setValueAtTime(0, now);
        gain1.gain.linearRampToValueAtTime(0.1, now + 0.05);
        gain1.gain.exponentialRampToValueAtTime(0.001, now + 0.35);
        osc1.connect(gain1);
        gain1.connect(ctx.destination);
        osc1.start(now);
        osc1.stop(now + 0.35);
        
        const osc2 = ctx.createOscillator();
        const gain2 = ctx.createGain();
        osc2.type = 'sine';
        osc2.frequency.setValueAtTime(1320, now + 0.08); // E6
        gain2.gain.setValueAtTime(0, now + 0.08);
        gain2.gain.linearRampToValueAtTime(0.1, now + 0.13);
        gain2.gain.exponentialRampToValueAtTime(0.001, now + 0.45);
        osc2.connect(gain2);
        gain2.connect(ctx.destination);
        osc2.start(now + 0.08);
        osc2.stop(now + 0.45);
        
    } catch (e) {
        console.warn("Web Audio chime failed:", e);
    }
}

if ('Notification' in window && Notification.permission === 'default') {
    document.addEventListener('click', () => {
        if (Notification.permission === 'default') {
            Notification.requestPermission();
        }
    }, { once: true });
}

async function showBrowserNotification(sender, text) {
    const prof = profileCache[sender] || { display_name: sender };
    const name = prof.display_name || sender;
    let bodyText = text;
    if (text.length > 60) bodyText = text.substring(0, 57) + '...';

    // 1. Capacitor Local Notifications (Android APK)
    const LocalNotifications = window.Capacitor?.Plugins?.LocalNotifications;
    if (LocalNotifications) {
        try {
            const perm = await LocalNotifications.checkPermissions();
            if (perm.display !== 'granted') {
                await LocalNotifications.requestPermissions();
            }
            await LocalNotifications.schedule({
                notifications: [
                    {
                        title: name,
                        body: bodyText,
                        id: Math.floor(Math.random() * 100000),
                        extra: {
                            senderId: sender
                        }
                    }
                ]
            });
        } catch (e) {
            console.warn("Capacitor LocalNotification failed:", e);
        }
    }

    // 2. In-app toast notification (always show when in different chat)
    if (!document.hidden) {
        showInAppToast(sender, name, bodyText, prof.avatar_data);
    }

    // 3. Browser Notifications (only when tab is hidden)
    if (document.hidden && 'Notification' in window && Notification.permission === 'granted') {
        try {
            const notif = new Notification(name, {
                body: bodyText,
                icon: safeDownloadUrl(prof.avatar_data) || 'img/icon.png'
            });
            notif.onclick = () => {
                window.focus();
                selectContact(sender);
            };
        } catch (e) {
            console.warn("Browser Notification failed:", e);
        }
    }
}

let toastTimeout = null;
function showInAppToast(senderId, name, text, avatarData) {
    const toast = document.getElementById('in-app-toast');
    const toastAvatar = document.getElementById('toast-avatar');
    const toastName = document.getElementById('toast-name');
    const toastText = document.getElementById('toast-text');
    if (!toast) return;

    // Set content
    toastName.textContent = name;
    toastText.textContent = text;
    if (avatarData) {
        toastAvatar.textContent = '';
        setSafeBackgroundImage(toastAvatar, avatarData);
    } else {
        toastAvatar.style.backgroundImage = 'none';
        toastAvatar.textContent = name.charAt(0).toUpperCase();
    }

    // Clear previous timeout
    if (toastTimeout) clearTimeout(toastTimeout);

    // Remove old classes, force reflow
    toast.classList.remove('show', 'hiding');
    void toast.offsetWidth;

    // Show
    toast.classList.add('show');

    // Click to open chat
    toast.onclick = () => {
        toast.classList.remove('show');
        toast.classList.add('hiding');
        if (toastTimeout) clearTimeout(toastTimeout);
        selectContact(senderId);
    };

    // Auto-hide after 4 seconds
    toastTimeout = setTimeout(() => {
        toast.classList.remove('show');
        toast.classList.add('hiding');
        setTimeout(() => toast.classList.remove('hiding'), 400);
    }, 4000);
}

// --- Голосовые и Видеосообщения (MediaRecorder API) ---
let mediaRecorder = null;
let recordedChunks = [];
let recordingTimerInterval = null;
let recordingSeconds = 0;
let recordingStream = null;
let isRecordingVideo = false;

async function startAudioRecording() {
    try {
        recordingStream = await navigator.mediaDevices.getUserMedia({ audio: true });
        isRecordingVideo = false;
        showRecordingUI("Запись аудио");
        
        recordedChunks = [];
        mediaRecorder = new MediaRecorder(recordingStream);
        mediaRecorder.ondataavailable = (e) => {
            if (e.data && e.data.size > 0) {
                recordedChunks.push(e.data);
            }
        };
        
        mediaRecorder.onstop = async () => {
            clearInterval(recordingTimerInterval);
            stopRecordingUI();
            
            if (recordedChunks.length === 0) return;
            const blob = new Blob(recordedChunks, { type: 'audio/webm' });
            // Единый транспорт: шифруем+грузим в /upload (совместимо с Android).
            const bytes = new Uint8Array(await blob.arrayBuffer());
            await sendMediaFile(bytes, 'audio/webm', 'voice', 'voice');

            cleanupRecordingStream();
        };
        
        mediaRecorder.start();
        startRecordingTimer();
    } catch (e) {
        console.error("Audio recording failed:", e);
        alert("Не удалось получить доступ к микрофону");
    }
}

async function startVideoRecording() {
    try {
        recordingStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: { facingMode: currentFacingMode, width: 320, height: 320 } });
        isRecordingVideo = true;
        showRecordingUI("Запись видео");
        
        const previewContainer = document.getElementById('recording-preview-container');
        const previewVideo = document.getElementById('recording-video-preview');
        previewContainer.classList.remove('hidden');
        previewVideo.srcObject = recordingStream;
        
        recordedChunks = [];
        mediaRecorder = new MediaRecorder(recordingStream, { mimeType: 'video/webm;codecs=vp8,opus' });
        mediaRecorder.ondataavailable = (e) => {
            if (e.data && e.data.size > 0) {
                recordedChunks.push(e.data);
            }
        };
        
        mediaRecorder.onstop = async () => {
            clearInterval(recordingTimerInterval);
            stopRecordingUI();
            
            previewVideo.srcObject = null;
            previewContainer.classList.add('hidden');
            
            if (recordedChunks.length === 0) return;
            const blob = new Blob(recordedChunks, { type: 'video/webm' });
            // Единый транспорт: шифруем+грузим в /upload (совместимо с Android).
            const bytes = new Uint8Array(await blob.arrayBuffer());
            await sendMediaFile(bytes, 'video/webm', 'video_msg', 'video_msg');

            cleanupRecordingStream();
        };
        
        mediaRecorder.start();
        startRecordingTimer();
    } catch (e) {
        if (e.name === 'NotFoundError') {
            alert("Камера не найдена!");
        } else {
            console.error("Video recording failed:", e);
            alert("Не удалось получить доступ к камере или микрофону");
        }
    }
}

function cancelRecording() {
    if (mediaRecorder && mediaRecorder.state !== 'inactive') {
        recordedChunks = [];
        mediaRecorder.stop();
    }
}

function stopAndSendRecording() {
    if (mediaRecorder && mediaRecorder.state !== 'inactive') {
        mediaRecorder.stop();
    }
}

function showRecordingUI(typeLabel) {
    document.querySelector('.tg-chat-input').classList.add('hidden');
    const panel = document.getElementById('recording-panel');
    panel.classList.remove('hidden');
    document.getElementById('recording-type').textContent = typeLabel;
    document.getElementById('recording-timer').textContent = "00:00";
    
    recordingSeconds = 0;
}

function stopRecordingUI() {
    document.getElementById('recording-panel').classList.add('hidden');
    document.querySelector('.tg-chat-input').classList.remove('hidden');
}

function startRecordingTimer() {
    recordingSeconds = 0;
    recordingTimerInterval = setInterval(() => {
        recordingSeconds++;
        const mins = Math.floor(recordingSeconds / 60).toString().padStart(2, '0');
        const secs = (recordingSeconds % 60).toString().padStart(2, '0');
        document.getElementById('recording-timer').textContent = `${mins}:${secs}`;
    }, 1000);
}

function cleanupRecordingStream() {
    if (recordingStream) {
        recordingStream.getTracks().forEach(track => track.stop());
        recordingStream = null;
    }
}

// Binds & Listeners
const micBtn = document.getElementById('mic-btn');
const camBtn = document.getElementById('cam-btn');
const recordingCancelBtn = document.getElementById('recording-cancel-btn');
const recordingStopSendBtn = document.getElementById('recording-stop-send-btn');

if (micBtn) micBtn.addEventListener('click', startAudioRecording);
if (camBtn) camBtn.addEventListener('click', startVideoRecording);
if (recordingCancelBtn) recordingCancelBtn.addEventListener('click', cancelRecording);
if (recordingStopSendBtn) recordingStopSendBtn.addEventListener('click', stopAndSendRecording);

if (replyCloseBtn) {
    replyCloseBtn.addEventListener('click', disableReplyOrEditMode);
}

// Call minimization triggers
const callMinimizeBtn = document.getElementById('call-minimize-btn');
if (callMinimizeBtn) {
    callMinimizeBtn.addEventListener('click', () => {
        callScreen.classList.add('hidden');
        document.getElementById('call-minimized-bar').classList.remove('hidden');
    });
}

const miniCallInfoBtn = document.getElementById('mini-call-info-btn');
if (miniCallInfoBtn) {
    miniCallInfoBtn.addEventListener('click', () => {
        callScreen.classList.remove('hidden');
        document.getElementById('call-minimized-bar').classList.add('hidden');
    });
}

const miniMuteBtn = document.getElementById('mini-mute-btn');
if (miniMuteBtn) {
    miniMuteBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        toggleMute();
    });
}

const miniEndBtn = document.getElementById('mini-end-btn');
if (miniEndBtn) {
    miniEndBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        endCall();
    });
}

// Жест «назад» — строго от левого края экрана (как в Telegram).
// Срабатывает только на мобильном при открытом чате, требует горизонтального
// движения и отменяется при вертикальном скролле. Панель тянется за пальцем,
// по отпусканию: дальше порога — назад, иначе плавно возвращается на место.
let edgeStartX = 0;
let edgeStartY = 0;
let isEdgeSwiping = false;
let edgeTracking = false;
const EDGE_ZONE = 32; // px от левого края, где можно начать жест

if (chatAreaView) {
    chatAreaView.addEventListener('touchstart', (e) => {
        edgeTracking = false;
        isEdgeSwiping = false;
        if (window.innerWidth > 768) return;
        if (!chatAreaView.classList.contains('mobile-open')) return;
        edgeStartX = e.touches[0].clientX;
        edgeStartY = e.touches[0].clientY;
        edgeTracking = edgeStartX <= EDGE_ZONE;
    }, { passive: true });

    chatAreaView.addEventListener('touchmove', (e) => {
        if (!edgeTracking) return;
        const diffX = e.touches[0].clientX - edgeStartX;
        const diffY = e.touches[0].clientY - edgeStartY;
        if (!isEdgeSwiping) {
            if (Math.abs(diffY) > 12 && Math.abs(diffY) >= Math.abs(diffX)) {
                edgeTracking = false; // вертикальный скролл — отменяем жест
                return;
            }
            if (diffX > 10 && Math.abs(diffX) > Math.abs(diffY) * 1.2) {
                isEdgeSwiping = true;
            } else {
                return;
            }
        }
        const move = Math.max(0, diffX);
        chatAreaView.style.transition = 'none';
        chatAreaView.style.transform = `translateX(${move}px)`;
    }, { passive: true });

    chatAreaView.addEventListener('touchend', (e) => {
        if (!edgeTracking) return;
        const diffX = e.changedTouches[0].clientX - edgeStartX;
        const wasSwiping = isEdgeSwiping;
        edgeTracking = false;
        isEdgeSwiping = false;
        // Возвращаем плавность и снимаем ручной transform
        chatAreaView.style.transition = '';
        chatAreaView.style.transform = '';
        if (wasSwiping && diffX > window.innerWidth * 0.33) {
            if (backToListBtn) backToListBtn.click();
        }
    });
}

// Drawer Swipe gestures
let drawerStartX = 0;
let drawerStartY = 0;
let isDrawerSwiping = false;
const chatListView = document.querySelector('.tg-chat-list-view');

if (chatListView && sideDrawer) {
    chatListView.addEventListener('touchstart', (e) => {
        if (chatAreaView && chatAreaView.classList.contains('mobile-open')) return;
        drawerStartX = e.touches[0].clientX;
        drawerStartY = e.touches[0].clientY;
        isDrawerSwiping = false;
    }, { passive: true });

    chatListView.addEventListener('touchmove', (e) => {
        if (chatAreaView && chatAreaView.classList.contains('mobile-open')) return;
        const currentX = e.touches[0].clientX;
        const currentY = e.touches[0].clientY;
        const diffX = currentX - drawerStartX;
        const diffY = currentY - drawerStartY;
        
        // Swipe right from left edge of the screen
        if (drawerStartX < 40 && diffX > 0 && Math.abs(diffX) > Math.abs(diffY) * 1.5) {
            isDrawerSwiping = true;
            const translateVal = Math.min(0, -280 + diffX);
            sideDrawer.style.transform = `translateX(${translateVal}px)`;
            sideDrawer.style.transition = 'none';
            if (drawerOverlay) {
                const opacityVal = Math.min(0.5, (diffX / 280) * 0.5);
                drawerOverlay.style.opacity = opacityVal;
                drawerOverlay.style.pointerEvents = 'auto';
            }
        }
    }, { passive: true });

    chatListView.addEventListener('touchend', (e) => {
        if (!isDrawerSwiping) return;
        isDrawerSwiping = false;
        
        const endX = e.changedTouches[0].clientX;
        const diffX = endX - drawerStartX;
        
        sideDrawer.style.transform = '';
        sideDrawer.style.transition = '';
        if (drawerOverlay) {
            drawerOverlay.style.opacity = '';
            drawerOverlay.style.pointerEvents = '';
        }
        
        if (diffX > 80) {
            sideDrawer.classList.add('open');
            if (drawerOverlay) drawerOverlay.classList.add('visible');
        } else {
            sideDrawer.classList.remove('open');
            if (drawerOverlay) drawerOverlay.classList.remove('visible');
        }
    });
}

// Swipe drawer left to close
if (sideDrawer) {
    sideDrawer.addEventListener('touchstart', (e) => {
        drawerStartX = e.touches[0].clientX;
        drawerStartY = e.touches[0].clientY;
        isDrawerSwiping = false;
    }, { passive: true });

    sideDrawer.addEventListener('touchmove', (e) => {
        if (!sideDrawer.classList.contains('open')) return;
        const currentX = e.touches[0].clientX;
        const currentY = e.touches[0].clientY;
        const diffX = currentX - drawerStartX;
        const diffY = currentY - drawerStartY;
        
        if (diffX < 0 && Math.abs(diffX) > Math.abs(diffY) * 1.5) {
            isDrawerSwiping = true;
            const translateVal = Math.max(-280, diffX);
            sideDrawer.style.transform = `translateX(${translateVal}px)`;
            sideDrawer.style.transition = 'none';
            if (drawerOverlay) {
                const opacityVal = Math.max(0, 0.5 + (diffX / 280) * 0.5);
                drawerOverlay.style.opacity = opacityVal;
            }
        }
    }, { passive: true });

    sideDrawer.addEventListener('touchend', (e) => {
        if (!isDrawerSwiping) return;
        isDrawerSwiping = false;
        
        const endX = e.changedTouches[0].clientX;
        const diffX = endX - drawerStartX;
        
        sideDrawer.style.transform = '';
        sideDrawer.style.transition = '';
        if (drawerOverlay) {
            drawerOverlay.style.opacity = '';
        }
        
        if (diffX < -80) {
            sideDrawer.classList.remove('open');
            if (drawerOverlay) drawerOverlay.classList.remove('visible');
        } else {
            sideDrawer.classList.add('open');
            if (drawerOverlay) drawerOverlay.classList.add('visible');
        }
    });
}

if (messageInput) {
    messageInput.addEventListener('input', () => {
        const text = messageInput.value.trim();
        const sendBtn = document.getElementById('send-btn');
        const micBtn = document.getElementById('mic-btn');
        const camBtn = document.getElementById('cam-btn');
        
        if (editingMsgId) {
            sendBtn.classList.remove('hidden');
            if (micBtn) micBtn.classList.add('hidden');
            if (camBtn) camBtn.classList.add('hidden');
            return;
        }
        
        if (text || replyToMsgId) {
            sendBtn.classList.remove('hidden');
            if (micBtn) micBtn.classList.add('hidden');
            if (camBtn) camBtn.classList.add('hidden');
        } else {
            sendBtn.classList.add('hidden');
            if (micBtn) micBtn.classList.remove('hidden');
            if (camBtn) camBtn.classList.remove('hidden');
        }
    });
}

// Register Capacitor Local Notification Click listener
if (window.Capacitor?.Plugins?.LocalNotifications) {
    try {
        window.Capacitor.Plugins.LocalNotifications.addListener('localNotificationActionPerformed', (notification) => {
            const senderId = notification.notification.extra?.senderId;
            if (senderId) {
                window.focus();
                selectContact(senderId);
            }
        });
    } catch (e) {
        console.warn("Could not register localNotificationActionPerformed listener:", e);
    }
}

// Remember only the username. The password unlocks the local encrypted history
// and is deliberately never persisted in localStorage.
const rememberMeCheckbox = document.getElementById('remember-me-checkbox');
// Remove credentials written by older builds; this client never stores them.
localStorage.removeItem('remember_password_enc');
localStorage.removeItem('remember_password');
if (rememberMeCheckbox) {
    const rememberMe = localStorage.getItem('remember_me') === 'true';
    if (rememberMe) {
        rememberMeCheckbox.checked = true;
        const savedUser = localStorage.getItem('remember_username');
        if (savedUser) {
            usernameInput.value = savedUser;
        }
    }
}

// --- ТЕМЫ ОФОРМЛЕНИЯ ---
function loadAppliedTheme() {
    // 'auto' — без класса: работает :root (Aether, светлая/тёмная по системе).
    // Миграция старых значений: liquid-glass→glass, white→light.
    let savedTheme = localStorage.getItem('settings_theme') || 'auto';
    if (savedTheme === 'liquid-glass') savedTheme = 'glass';
    if (savedTheme === 'white') savedTheme = 'light';
    document.body.className = ''; // Сброс классов темы
    if (savedTheme !== 'auto') {
        document.body.classList.add('theme-' + savedTheme);
    }
    if (settingsThemeSelect) {
        settingsThemeSelect.value = savedTheme;
    }
    loadAppliedCustomColors();
}
if (settingsThemeSelect) {
    settingsThemeSelect.addEventListener('change', () => {
        const selectedTheme = settingsThemeSelect.value;
        localStorage.setItem('settings_theme', selectedTheme);
        loadAppliedTheme();
    });
}

// --- СКОРОСТЬ АНИМАЦИЙ ---
function loadAppliedAnimationSpeed() {
    const savedSpeed = localStorage.getItem('settings_animation_speed') || '0.3s';
    document.documentElement.style.setProperty('--transition-speed', savedSpeed);
    const speedSelect = document.getElementById('settings-animation-speed-select');
    if (speedSelect) {
        speedSelect.value = savedSpeed;
    }
}
const settingsSpeedSelect = document.getElementById('settings-animation-speed-select');
if (settingsSpeedSelect) {
    settingsSpeedSelect.addEventListener('change', () => {
        const selectedSpeed = settingsSpeedSelect.value;
        localStorage.setItem('settings_animation_speed', selectedSpeed);
        loadAppliedAnimationSpeed();
    });
}

// --- РЕАКЦИЯ ПО УМОЛЧАНИЮ ---
function loadAppliedDefaultReaction() {
    const savedReaction = localStorage.getItem('settings_default_reaction') || '❤️';
    const reactionSelect = document.getElementById('settings-default-reaction-select');
    if (reactionSelect) {
        reactionSelect.value = savedReaction;
    }
}
const settingsDefaultReactionSelect = document.getElementById('settings-default-reaction-select');
if (settingsDefaultReactionSelect) {
    settingsDefaultReactionSelect.addEventListener('change', () => {
        const selectedReaction = settingsDefaultReactionSelect.value;
        localStorage.setItem('settings_default_reaction', selectedReaction);
    });
}

// Запускаем при загрузке
loadAppliedTheme();
loadAppliedAnimationSpeed();
loadAppliedDefaultReaction();


// --- СОЗДАНИЕ И ДОБАВЛЕНИЕ КОНТАКТОВ ---
if (addContactDrawerBtn) {
    addContactDrawerBtn.addEventListener('click', () => {
        // Скрываем меню-бургер
        if (sideDrawer) sideDrawer.classList.remove('open');
        if (drawerOverlay) drawerOverlay.classList.remove('visible');
        
        // Показываем модал добавления контакта
        addContactIdInput.value = '';
        addContactNameInput.value = '';
        addContactStatus.textContent = '';
        addContactModal.classList.remove('hidden');
    });
}
if (closeAddContactBtn) {
    closeAddContactBtn.addEventListener('click', () => {
        addContactModal.classList.add('hidden');
    });
}
if (saveNewContactBtn) {
    saveNewContactBtn.addEventListener('click', async () => {
        const contactId = addContactIdInput.value.trim().toLowerCase();
        const contactName = addContactNameInput.value.trim();
        if (!contactId) {
            addContactStatus.textContent = 'Введите ID пользователя';
            addContactStatus.className = 'tg-status error';
            return;
        }
        if (contactId === myId) {
            addContactStatus.textContent = 'Вы не можете добавить себя в контакты';
            addContactStatus.className = 'tg-status error';
            return;
        }
        
        addContactStatus.textContent = 'Проверка пользователя...';
        addContactStatus.className = 'tg-status success';
        
        try {
            // Проверим, существует ли пользователь на сервере
            const res = await fetch(`${serverUrl}/users/${pathSegment(contactId)}/profile`, { headers: authHeaders() });
            if (!res.ok) {
                addContactStatus.textContent = 'Пользователь не найден на сервере';
                addContactStatus.className = 'tg-status error';
                return;
            }
            const data = await res.json();
            profileCache[contactId] = data; // Сохраняем в кэш
            
            // Добавляем контакт
            addCustomContact(contactId, contactName);
            addContactModal.classList.add('hidden');
            
            // Обновляем список контактов и переключаемся на добавленный контакт
            renderContactsList();
            selectContact(contactId);
        } catch (e) {
            addContactStatus.textContent = 'Ошибка проверки пользователя';
            addContactStatus.className = 'tg-status error';
        }
    });
}


// --- ПРОФИЛЬ ПОЛЬЗОВАТЕЛЯ И ПЕРЕИМЕНОВАНИЕ ---
function openUserProfileModal(peerId) {
    if (peerId === myId) return; // Для избранного не нужно
    
    if (myGroupsCache[peerId]) {
        if (groupManageBtn && !groupManageBtn.classList.contains('hidden')) {
            groupManageBtn.click();
        }
        return;
    }
    
    const prof = profileCache[peerId] || {};
    const dName = getContactDisplayName(peerId);
    
    profileIdLabel.textContent = peerId;
    profileUsernameLabel.textContent = prof.username ? '@' + prof.username : '@secure';
    profileDisplaynameLabel.textContent = prof.display_name || peerId;
    
    // Заполняем поле переименования
    const customNames = getCustomContactNames();
    contactCustomNameInput.value = customNames[peerId] || '';
    
    // Аватар в профиле
    if (prof.avatar_data) {
        profileAvatarDisplay.textContent = '';
        setSafeBackgroundImage(profileAvatarDisplay, prof.avatar_data);
    } else {
        profileAvatarDisplay.textContent = dName.charAt(0).toUpperCase();
        profileAvatarDisplay.style.backgroundImage = 'none';
    }
    
    profileModal.classList.remove('hidden');
}
if (peerAvatar) {
    peerAvatar.addEventListener('click', () => {
        if (selectedPeer) openUserProfileModal(selectedPeer);
    });
}
if (activePeerDisplay) {
    activePeerDisplay.addEventListener('click', () => {
        if (selectedPeer) openUserProfileModal(selectedPeer);
    });
}
if (closeProfileBtn) {
    closeProfileBtn.addEventListener('click', () => {
        profileModal.classList.add('hidden');
    });
}
if (saveContactNameBtn) {
    saveContactNameBtn.addEventListener('click', () => {
        if (!selectedPeer) return;
        const newName = contactCustomNameInput.value.trim();
        saveCustomContactName(selectedPeer, newName);
        profileModal.classList.add('hidden');
        
        // Обновляем всё
        updateActiveChatHeader(selectedPeer);
        renderContactsList();
    });
}
if (deleteContactNameBtn) {
    deleteContactNameBtn.addEventListener('click', () => {
        if (!selectedPeer) return;
        saveCustomContactName(selectedPeer, null);
        profileModal.classList.add('hidden');
        
        // Обновляем всё
        updateActiveChatHeader(selectedPeer);
        renderContactsList();
    });
}


// --- ПЕРЕСЫЛКА СООБЩЕНИЙ ---
if (ctxForward) {
    ctxForward.addEventListener('click', () => {
        if (!contextTargetMsg) return;
        openForwardModal(contextTargetMsg);
    });
}
if (closeForwardBtn) {
    closeForwardBtn.addEventListener('click', () => {
        forwardModal.classList.add('hidden');
    });
}
function openForwardModal(msg) {
    forwardContactsList.innerHTML = '';
    
    const contactsSet = new Set();
    messages.forEach(m => contactsSet.add(m.peer));
    const customList = getCustomContactsList();
    customList.forEach(c => contactsSet.add(c));
    
    const sortedContacts = Array.from(contactsSet).filter(c => c !== myId);
    
    if (sortedContacts.length === 0) {
        forwardContactsList.innerHTML = '<div style="text-align: center; padding: 15px; color: var(--text-secondary);">Нет контактов</div>';
    } else {
        sortedContacts.forEach(contactId => {
            const item = document.createElement('div');
            item.className = 'tg-contact-item';
            item.style.padding = '8px 12px';
            item.style.borderRadius = '8px';
            item.style.cursor = 'pointer';
            
            const name = getContactDisplayName(contactId);
            item.innerHTML = `
                <div class="tg-contact-avatar" style="width: 32px; height: 32px; font-size: 14px;">${escapeHtmlSafe(name.charAt(0).toUpperCase())}</div>
                <div class="tg-contact-info" style="margin-left: 10px;">
                    <div class="tg-contact-name" style="font-size: 14px;">${escapeHtmlSafe(name)}</div>
                </div>
            `;
            item.addEventListener('click', async () => {
                forwardModal.classList.add('hidden');
                
                const origPayload = msg.payload || { type: 'text', content: msg.plaintext };
                const forwardPayload = {
                    type: origPayload.type,
                    content: origPayload.content,
                    media: origPayload.media,   // медиа пересылаем по ссылке (тот же file_id/ключ)
                    filename: origPayload.filename,
                    size: origPayload.size,
                    forwarded_from: msg.direction === 'out' ? myId : msg.peer,
                    fwd_from: msg.direction === 'out' ? myId : msg.peer  // канон Android
                };
                
                const success = await sendPayloadMessage(forwardPayload, contactId);
                if (success) {
                    selectContact(contactId);
                } else {
                    alert("Не удалось переслать сообщение.");
                }
            });
            forwardContactsList.appendChild(item);
        });
    }
    forwardModal.classList.remove('hidden');
}


// --- ШТОРКА ПРИКРЕПЛЕНИЯ ФАЙЛОВ ---
const MOCK_IMAGES = [
    "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='200' height='200'><rect width='100%' height='100%' fill='%23ff5e57'/><text x='50%' y='50%' dominant-baseline='middle' text-anchor='middle' fill='white' font-family='sans-serif' font-size='24'>Закат</text></svg>",
    "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='200' height='200'><rect width='100%' height='100%' fill='%231dd1a1'/><text x='50%' y='50%' dominant-baseline='middle' text-anchor='middle' fill='white' font-family='sans-serif' font-size='24'>Лес</text></svg>",
    "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='200' height='200'><rect width='100%' height='100%' fill='%2354a0ff'/><text x='50%' y='50%' dominant-baseline='middle' text-anchor='middle' fill='white' font-family='sans-serif' font-size='24'>Море</text></svg>",
    "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='200' height='200'><rect width='100%' height='100%' fill='%23feca57'/><text x='50%' y='50%' dominant-baseline='middle' text-anchor='middle' fill='white' font-family='sans-serif' font-size='24'>Песок</text></svg>",
    "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='200' height='200'><rect width='100%' height='100%' fill='%23ff9ff3'/><text x='50%' y='50%' dominant-baseline='middle' text-anchor='middle' fill='white' font-family='sans-serif' font-size='24'>Цветы</text></svg>"
];

// Переопределяем скрепку для вызова шторки
if (attachBtn) {
    // Удаляем старый листенер, вешаем новый
    const newAttachBtn = attachBtn.cloneNode(true);
    attachBtn.parentNode.replaceChild(newAttachBtn, attachBtn);
    
    newAttachBtn.addEventListener('click', () => {
        if (!selectedPeer) return;
        
        // Наполняем карусель
        if (attachmentGalleryScroll) {
            attachmentGalleryScroll.innerHTML = '';
            MOCK_IMAGES.forEach(imgSrc => {
                const imgWrap = document.createElement('div');
                imgWrap.className = 'gallery-item-mock';
                const img = document.createElement('img');
                img.alt = 'Предпросмотр изображения';
                img.src = imgSrc;
                imgWrap.appendChild(img);
                imgWrap.addEventListener('click', async () => {
                    attachmentDrawer.classList.add('hidden');
                    // Единый транспорт: dataURL → байты → /upload.
                    const bytes = new Uint8Array(await (await fetch(imgSrc)).arrayBuffer());
                    await sendMediaFile(bytes, 'image/svg+xml', 'image', 'image');
                });
                attachmentGalleryScroll.appendChild(imgWrap);
            });
        }
        
        attachmentDrawer.classList.remove('hidden');
    });
}

// Закрытие шторки по клику на оверлей
const drawerOverlayEl = document.querySelector('.attachment-drawer-overlay');
if (drawerOverlayEl) {
    drawerOverlayEl.addEventListener('click', () => {
        attachmentDrawer.classList.add('hidden');
    });
}

// События кнопок в шторке
if (attachGalleryBtn) {
    attachGalleryBtn.addEventListener('click', () => {
        attachmentDrawer.classList.add('hidden');
        if (fileUpload) {
            fileUpload.setAttribute('accept', 'image/*');
            fileUpload.click();
        }
    });
}
if (attachFileBtn) {
    attachFileBtn.addEventListener('click', () => {
        attachmentDrawer.classList.add('hidden');
        if (fileUpload) {
            fileUpload.removeAttribute('accept');
            fileUpload.click();
        }
    });
}
if (attachLocationBtn) {
    attachLocationBtn.addEventListener('click', async () => {
        attachmentDrawer.classList.add('hidden');
        // Геопозиция: внешний URL карты. Пока веб-локально (инлайн, не через /upload —
        // внешний домен, риск CORS при перезаливке). Cross-platform — TODO.
        const mockLocationUrl = "https://static-maps.yandex.ru/v1?ll=37.6176,55.7558&size=450,300&z=13&pt=37.6176,55.7558,pm2rdm&scale=1.5&apikey=48d799df-ef61-46a2-a9fb-1090eb826f0c";
        await sendPayloadMessage({ type: 'image', content: mockLocationUrl });
    });
}
if (attachContactBtn) {
    attachContactBtn.addEventListener('click', async () => {
        attachmentDrawer.classList.add('hidden');
        // Отправка контакта в чат (карточка контакта)
        const contactCard = `👤 Контакт: ${myProfile.display_name || myId} (@${myProfile.username || 'secure'})`;
        await sendPayloadMessage({ type: 'text', content: contactCard });
    });
}


// --- ГРОМКАЯ СВЯЗЬ (SPEAKERPHONE) ---
if (callSpeakerBtn) {
    callSpeakerBtn.addEventListener('click', async () => {
        speakerphoneOn = !speakerphoneOn;
        if (speakerphoneOn) {
            callSpeakerBtn.classList.add('active');
            callSpeakerBtn.style.color = '#34d399'; // Зеленый акцент громкой связи
            callSpeakerBtn.innerHTML = '<i class="fas fa-volume-up"></i>';
        } else {
            callSpeakerBtn.classList.remove('active');
            callSpeakerBtn.style.color = '#fff';
            callSpeakerBtn.innerHTML = '<i class="fas fa-volume-down"></i>';
        }
        
        try {
            if (remoteVideo && remoteVideo.setSinkId) {
                const devices = await navigator.mediaDevices.enumerateDevices();
                const audioOutputs = devices.filter(device => device.kind === 'audiooutput');
                if (audioOutputs.length > 0) {
                    let targetDevice = null;
                    if (speakerphoneOn) {
                        targetDevice = audioOutputs.find(d => d.label.toLowerCase().includes('speaker') || d.label.toLowerCase().includes('громк') || d.label.toLowerCase().includes('dinam'));
                    } else {
                        targetDevice = audioOutputs.find(d => d.label.toLowerCase().includes('earpiece') || d.label.toLowerCase().includes('телефон') || d.label.toLowerCase().includes('разговор'));
                    }
                    if (!targetDevice && audioOutputs.length > 1) {
                        targetDevice = speakerphoneOn ? audioOutputs[audioOutputs.length - 1] : audioOutputs[0];
                    }
                    if (targetDevice) {
                        await remoteVideo.setSinkId(targetDevice.deviceId);
                        console.log("Speaker output toggled to:", targetDevice.label);
                    } else {
                        await remoteVideo.setSinkId('');
                    }
                }
            }
        } catch (err) {
            console.warn("setSinkId failed:", err);
        }
    });
}


// --- СТАБИЛИЗАЦИЯ КЛАВИАТУРЫ & Скролла ---
// Восстанавливаем фокус на поле ввода при отправке сообщения, при клике на скрепку и в других чат-операциях
if (sendBtn) {
    sendBtn.addEventListener('mousedown', (e) => {
        e.preventDefault(); // Prevents input blurring on click
    });
    sendBtn.addEventListener('click', () => {
        setTimeout(() => {
            if (messageInput) messageInput.focus();
        }, 50);
    });
}

if (messageInput) {
    messageInput.addEventListener('focus', () => {
        setTimeout(scrollToBottom, 100);
        setTimeout(scrollToBottom, 300);
    });
}

window.addEventListener('resize', () => {
    scrollToBottom();
    setTimeout(scrollToBottom, 100);
    setTimeout(scrollToBottom, 300);
});

document.addEventListener('touchend', (e) => {
    // Если пользователь нажал на элементы ввода сообщений, предотвращаем пропадание клавиатуры
    if (e.target === messageInput || e.target.closest('#send-btn') || e.target.closest('#attach-btn')) {
        setTimeout(() => {
            if (messageInput) messageInput.focus();
        }, 50);
    }
});


// --- GROUPS & CHANNELS LOGIC ---

let myGroupsCache = Object.create(null); // group_id -> group_info

async function fetchMyGroups() {
    try {
        const res = await fetch(`${serverUrl}/groups/me`, {
            headers: { 'Authorization': `Bearer ${sessionToken}` }
        });
        if (res.ok) {
            const data = await res.json();
            for (let g of data.groups) {
                myGroupsCache[g.id] = g;
                
                // Расшифровываем групповой ключ (формат с sender_pubkey_b64 внутри,
                // как в Android — отправитель обёртки указан в самом конверте).
                if (!groupKeys[g.id] && g.encrypted_key_b64) {
                    try {
                        const key = unwrapGroupKey(g.encrypted_key_b64);
                        if (key) {
                            groupKeys[g.id] = key;
                        } else {
                            console.error("Failed to decrypt group key for", g.id);
                        }
                    } catch (e) {
                        console.error("Error decrypting group key", e);
                    }
                }
            }
        }
    } catch (e) {
        console.error("fetchMyGroups error", e);
    }
}

async function createGroup(id, name, desc, isChannel, linkedGroupId) {
    // Симметричный ключ группы (32 байта для AES-GCM)
    const symKey = nacl.randomBytes(32);
    groupKeys[id.toLowerCase()] = symKey;

    // Заворачиваем ключ для себя (box на собственный публичный ключ)
    const encryptedKeyB64 = wrapGroupKey(symKey, base64UrlDecode(myKeys.publicB64));

    const res = await fetch(`${serverUrl}/groups`, {
        method: 'POST',
        headers: { 
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${sessionToken}`
        },
        body: JSON.stringify({
            id: id,
            name: name,
            description: desc,
            is_channel: isChannel,
            linked_group_id: linkedGroupId || null,
            encrypted_key_b64: encryptedKeyB64
        })
    });
    
    if (!res.ok) {
        const err = await res.json();
        throw new Error(err.detail || "Failed to create group");
    }
    
    await fetchMyGroups();
    renderContactsList();
}

async function addMemberToGroup(groupId, userId) {
    const symKey = groupKeys[groupId.toLowerCase()];
    if (!symKey) throw new Error("Group key not found locally");
    
    const prof = await fetchPeerProfile(userId);
    if (!prof || !prof.public_key_b64) throw new Error("Пользователь не найден (или не имеет ключа)");
    
    const peerPub = base64UrlDecode(prof.public_key_b64);
    const encryptedKeyB64 = wrapGroupKey(symKey, peerPub);

    const res = await fetch(`${serverUrl}/groups/${pathSegment(groupId)}/members`, {
        method: 'POST',
        headers: { 
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${sessionToken}`
        },
        body: JSON.stringify({
            user_id: userId,
            encrypted_key_b64: encryptedKeyB64,
            role: 'member'
        })
    });
    
    if (!res.ok) {
        const err = await res.json();
        throw new Error(err.detail || "Failed to add member");
    }
}


// --- GROUPS UI LISTENERS ---
const createGroupDrawerBtn = document.getElementById('create-group-drawer-btn');
const createGroupModal = document.getElementById('create-group-modal');
const closeCreateGroupBtn = document.getElementById('close-create-group-btn');
const submitCreateGroupBtn = document.getElementById('submit-create-group-btn');
const groupIsChannelCb = document.getElementById('group-is-channel-checkbox');
const groupLinkContainer = document.getElementById('group-link-group-container');

if (createGroupDrawerBtn) {
    createGroupDrawerBtn.addEventListener('click', () => {
        sideDrawer.classList.remove('open');
        drawerOverlay.classList.remove('open');
        createGroupModal.classList.remove('hidden');
    });
}
if (closeCreateGroupBtn) {
    closeCreateGroupBtn.addEventListener('click', () => createGroupModal.classList.add('hidden'));
}
if (groupIsChannelCb) {
    groupIsChannelCb.addEventListener('change', (e) => {
        groupLinkContainer.style.display = e.target.checked ? 'block' : 'none';
    });
}
if (submitCreateGroupBtn) {
    submitCreateGroupBtn.addEventListener('click', async () => {
        const id = document.getElementById('group-id-input').value.trim();
        const name = document.getElementById('group-name-input').value.trim();
        const desc = document.getElementById('group-desc-input').value.trim();
        const isChannel = groupIsChannelCb.checked;
        const linkedId = document.getElementById('group-linked-id-input').value.trim();
        const status = document.getElementById('create-group-status');
        
        if (!id || !name) {
            status.textContent = 'ID и Название обязательны';
            status.className = 'tg-status error';
            return;
        }
        if (!/^[a-z0-9_]{2,64}$/i.test(id)) {
            status.textContent = 'ID: только латиница, цифры и _ (2–64 символа)';
            status.className = 'tg-status error';
            return;
        }
        
        status.textContent = 'Создание...';
        status.className = 'tg-status';
        try {
            await createGroup(id, name, desc, isChannel, linkedId);
            createGroupModal.classList.add('hidden');
            selectContact(id);
        } catch (e) {
            status.textContent = e.message;
            status.className = 'tg-status error';
        }
    });
}


// --- MANAGE GROUP MEMBERS LOGIC ---
const groupManageBtn = document.getElementById('group-manage-btn');
const groupSettingsModal = document.getElementById('group-settings-modal');
const closeGroupSettingsBtn = document.getElementById('close-group-settings-btn');
const groupSettingsAvatar = document.getElementById('group-settings-avatar');
const groupSettingsName = document.getElementById('group-settings-name');
const groupSettingsDesc = document.getElementById('group-settings-desc');
const groupAdminControls = document.getElementById('group-admin-controls');
const addMemberBtn = document.getElementById('add-member-btn');
const addMemberInput = document.getElementById('add-member-input');
const addMemberStatus = document.getElementById('add-member-status');
const groupMembersList = document.getElementById('group-members-list');
const groupSettingsLeaveBtn = document.getElementById('group-settings-leave-btn');

if (groupManageBtn) {
    groupManageBtn.addEventListener('click', async () => {
        if (!selectedPeer || !myGroupsCache[selectedPeer.toLowerCase()]) return;
        const groupId = selectedPeer.toLowerCase();
        const groupInfo = myGroupsCache[groupId];
        
        groupSettingsModal.classList.remove('hidden');
        addMemberStatus.textContent = '';
        addMemberInput.value = '';
        
        // Populate group info
        groupSettingsAvatar.innerHTML = groupInfo.is_channel ? '<i class="fas fa-bullhorn"></i>' : '<i class="fas fa-users"></i>';
        groupSettingsName.textContent = groupInfo.name || groupId;
        groupSettingsDesc.textContent = groupInfo.description || 'Описание отсутствует';
        
        // Admin controls logic
        if (groupInfo.role === 'admin') {
            groupAdminControls.classList.remove('hidden');
            if (groupInfo.owner_id === myId) {
                groupSettingsLeaveBtn.textContent = 'Удалить';
            } else {
                groupSettingsLeaveBtn.textContent = 'Покинуть';
            }
        } else {
            groupAdminControls.classList.add('hidden');
            groupSettingsLeaveBtn.textContent = 'Покинуть';
        }
        
        groupMembersList.innerHTML = '<p style="color:var(--text-secondary); padding: 10px;">Загрузка...</p>';
        
        try {
            const res = await fetch(`${serverUrl}/groups/${pathSegment(groupId)}/members`, {
                headers: { 'Authorization': `Bearer ${sessionToken}` }
            });
            if (res.ok) {
                const data = await res.json();
                groupMembersList.innerHTML = '';
                data.members.forEach(m => {
                    const div = document.createElement('div');
                    div.style.cssText = 'display:flex; justify-content:space-between; padding: 10px; border-bottom: 1px solid var(--border-color);';
                    div.innerHTML = `<span><b>${escapeHtmlSafe(m.display_name || m.username || m.user_id)}</b> <span style="font-size:12px; color:var(--text-secondary); margin-left:5px;">(${escapeHtmlSafe(m.role)})</span></span> <span style="color:var(--text-secondary); font-size:12px;">${escapeHtmlSafe(m.user_id)}</span>`;
                    groupMembersList.appendChild(div);
                });
            } else {
                groupMembersList.innerHTML = '<p class="tg-status error" style="padding: 10px;">Ошибка загрузки участников</p>';
            }
        } catch (e) {
            groupMembersList.innerHTML = `<p class="tg-status error" style="padding: 10px;">${escapeHtmlSafe(e.message)}</p>`;
        }
    });
}

if (closeGroupSettingsBtn) {
    closeGroupSettingsBtn.addEventListener('click', () => groupSettingsModal.classList.add('hidden'));
}

if (groupSettingsLeaveBtn) {
    groupSettingsLeaveBtn.addEventListener('click', () => {
        groupSettingsModal.classList.add('hidden');
        if (deleteChatBtn) deleteChatBtn.click();
    });
}

if (addMemberBtn) {
    addMemberBtn.addEventListener('click', async () => {
        if (!selectedPeer || !myGroupsCache[selectedPeer.toLowerCase()]) return;
        const groupId = selectedPeer.toLowerCase();
        const userId = addMemberInput.value.trim();
        if (!userId) return;
        
        addMemberStatus.textContent = 'Добавление...';
        addMemberStatus.className = 'tg-status';
        try {
            await addMemberToGroup(groupId, userId);
            addMemberStatus.textContent = 'Успешно добавлен!';
            addMemberStatus.className = 'tg-status success';
            addMemberInput.value = '';
            // refresh list
            groupManageBtn.click();
        } catch (e) {
            addMemberStatus.textContent = e.message;
            addMemberStatus.className = 'tg-status error';
        }
    });
}

// --- DELETE CHAT LOGIC ---
const deleteChatBtn = document.getElementById('delete-chat-btn');
const deleteChatModal = document.getElementById('delete-chat-modal');
const closeDeleteChatModalBtn = document.getElementById('close-delete-chat-modal-btn');
const cancelDeleteChatBtn = document.getElementById('cancel-delete-chat-btn');
const confirmDeleteChatBtn = document.getElementById('confirm-delete-chat-btn');
const deleteChatTitle = document.getElementById('delete-chat-title');
const deleteChatDesc = document.getElementById('delete-chat-desc');

if (deleteChatBtn) {
    deleteChatBtn.addEventListener('click', () => {
        if (!selectedPeer) return;
        
        let isGroup = !!myGroupsCache[selectedPeer];
        let isOwner = isGroup && myGroupsCache[selectedPeer].owner_id === myId;
        
        if (isGroup && !isOwner) {
            deleteChatTitle.textContent = "Покинуть группу?";
            deleteChatDesc.textContent = "Вы действительно хотите покинуть эту группу?";
            confirmDeleteChatBtn.textContent = "Покинуть";
        } else if (isGroup && isOwner) {
            deleteChatTitle.textContent = "Удалить группу?";
            deleteChatDesc.textContent = "Группа будет удалена для всех участников безвозвратно.";
            confirmDeleteChatBtn.textContent = "Удалить для всех";
        } else {
            deleteChatTitle.textContent = "Удалить чат?";
            deleteChatDesc.textContent = "История переписки будет удалена только для вас.";
            confirmDeleteChatBtn.textContent = "Удалить";
        }
        
        deleteChatModal.classList.remove('hidden');
    });
}

if (closeDeleteChatModalBtn) closeDeleteChatModalBtn.addEventListener('click', () => deleteChatModal.classList.add('hidden'));
if (cancelDeleteChatBtn) cancelDeleteChatBtn.addEventListener('click', () => deleteChatModal.classList.add('hidden'));

if (confirmDeleteChatBtn) {
    confirmDeleteChatBtn.addEventListener('click', async () => {
        if (!selectedPeer) return;
        const peer = selectedPeer;
        const isGroup = !!myGroupsCache[peer];
        const isOwner = isGroup && myGroupsCache[peer].owner_id === myId;
        
        confirmDeleteChatBtn.disabled = true;
        confirmDeleteChatBtn.textContent = "Подождите...";
        
        try {
            if (isGroup) {
                if (isOwner) {
                    const res = await fetch(`${serverUrl}/groups/${pathSegment(peer)}`, {
                        method: 'DELETE',
                        headers: { 'Authorization': `Bearer ${sessionToken}`, 'Bypass-Tunnel-Reminder': 'true' }
                    });
                    if (!res.ok) throw new Error("Failed to delete group");
                } else {
                    const res = await fetch(`${serverUrl}/groups/${pathSegment(peer)}/leave`, {
                        method: 'POST',
                        headers: { 'Authorization': `Bearer ${sessionToken}`, 'Bypass-Tunnel-Reminder': 'true' }
                    });
                    if (!res.ok) throw new Error("Failed to leave group");
                }
                delete myGroupsCache[peer];
                delete groupKeys[peer];
            } else {
                const res = await fetch(`${serverUrl}/messages/history/${pathSegment(peer)}`, {
                    method: 'DELETE',
                    headers: { 'Authorization': `Bearer ${sessionToken}`, 'Bypass-Tunnel-Reminder': 'true' }
                });
                if (!res.ok) throw new Error("Failed to delete chat history");
            }
            
            // Cleanup local messages (правильно фильтруем по peer, а не по несуществующему envelope)
            messages = messages.filter(m => !(m.peer && m.peer.toLowerCase() === peer.toLowerCase()));
            setPinnedId(peer, null);
            saveMessagesLocally();
            
            removeCustomContact(peer); // If it was a custom contact
            
            deleteChatModal.classList.add('hidden');
            
            if (selectedPeer === peer) {
                // Clear chat view
                selectedPeer = null;
                activeChatWindow.classList.add('hidden');
                noChatSelected.classList.remove('hidden');
                chatAreaView.classList.remove('active');
            }
            renderContactsList();
        } catch (e) {
            alert("Ошибка: " + e.message);
        } finally {
            confirmDeleteChatBtn.disabled = false;
        }
    });
}


// --- CHAT SETTINGS & SWIPE ACTIONS ---
async function fetchChatSettings() {
    try {
        const raw = localStorage.getItem(`chat_settings_${myId}`);
        const parsed = raw ? JSON.parse(raw) : {};
        chatSettingsCache = parsed && typeof parsed === 'object'
            ? Object.assign(Object.create(null), parsed) : Object.create(null);
    } catch (_) {
        chatSettingsCache = Object.create(null);
    }
}

async function toggleChatSetting(peerId, field) {
    if (!peerId) return;
    peerId = peerId.toLowerCase();
    let current = chatSettingsCache[peerId] || { is_pinned: false, is_muted: false, is_archived: false };
    
    // Toggle
    current[field] = !current[field];
    chatSettingsCache[peerId] = current;
    
    // Update UI immediately
    renderContactsList();
    
    localStorage.setItem(`chat_settings_${myId}`, JSON.stringify(chatSettingsCache));
}

function initSwipeGestures(wrapper, item, contactId) {
    let startX = 0;
    let currentX = 0;
    let isSwiping = false;
    let actionsWidth = 240; // 4 buttons * 60px
    let swiped = false;

    item.addEventListener('touchstart', (e) => {
        // If swiped, we allow swiping right to close OR tapping to close
        startX = e.touches[0].clientX;
        isSwiping = true;
        item.classList.add('swiping');
    }, {passive: true});

    item.addEventListener('touchmove', (e) => {
        if (!isSwiping) return;
        let x = e.touches[0].clientX;
        let deltaX = x - startX;
        
        if (!swiped && deltaX < 0) {
            // Swiping left to open
            let move = Math.max(deltaX, -actionsWidth - 20);
            item.style.transform = `translateX(${move}px)`;
        } else if (swiped) {
            // Swiping right to close, starting from -actionsWidth
            let move = Math.max(-actionsWidth, Math.min(0, -actionsWidth + deltaX));
            item.style.transform = `translateX(${move}px)`;
        }
    }, {passive: true});

    item.addEventListener('touchend', (e) => {
        if (!isSwiping) return;
        isSwiping = false;
        item.classList.remove('swiping');
        
        let endX = e.changedTouches[0].clientX;
        let deltaX = endX - startX;
        
        if (!swiped) {
            if (deltaX < -50) {
                item.style.transform = `translateX(-${actionsWidth}px)`;
                swiped = true;
            } else {
                item.style.transform = `translateX(0px)`;
                swiped = false;
            }
        } else {
            // If swiped is true, any tap (deltaX near 0) or swipe right closes it
            if (deltaX > 10 || Math.abs(deltaX) < 10) {
                item.style.transform = `translateX(0px)`;
                swiped = false;
            } else {
                item.style.transform = `translateX(-${actionsWidth}px)`;
            }
        }
    });
}


// =====================================================================
// REALTIME: WebSocket для индикатора «печатает», presence и мгновенной
// доставки сообщений. Полностью аддитивно — polling продолжает работать
// как fallback, поэтому при недоступности WS ничего не ломается.
// =====================================================================
function wsUrlFromServer() {
    try {
        const u = new URL(serverUrl);
        const proto = u.protocol === 'https:' ? 'wss:' : 'ws:';
        return `${proto}//${u.host}/ws?token=${encodeURIComponent(sessionToken)}`;
    } catch (e) { return null; }
}

function connectRealtime() {
    if (!sessionToken || !serverUrl) return;
    try { if (realtimeWs) { realtimeWs.onclose = null; realtimeWs.close(); } } catch (e) {}
    const url = wsUrlFromServer();
    if (!url) return;
    try {
        realtimeWs = new WebSocket(url);
    } catch (e) { scheduleWsReconnect(); return; }

    realtimeWs.onopen = () => { startWsPing(); };
    realtimeWs.onmessage = (ev) => {
        if (ev.data === 'pong') return;
        let m;
        try { m = JSON.parse(ev.data); } catch (e) { return; }
        handleRealtimeMessage(m);
    };
    realtimeWs.onclose = () => { stopWsPing(); scheduleWsReconnect(); };
    realtimeWs.onerror = () => { try { realtimeWs.close(); } catch (e) {} };
}

function startWsPing() {
    stopWsPing();
    wsPingTimer = setInterval(() => {
        try { if (realtimeWs && realtimeWs.readyState === 1) realtimeWs.send('ping'); } catch (e) {}
    }, 25000);
}
function stopWsPing() { if (wsPingTimer) clearInterval(wsPingTimer); wsPingTimer = null; }

function scheduleWsReconnect() {
    if (wsReconnectTimer) return;
    if (!sessionToken) return; // не переподключаемся после выхода
    wsReconnectTimer = setTimeout(() => {
        wsReconnectTimer = null;
        connectRealtime();
    }, 3000);
}

function handleRealtimeMessage(m) {
    const t = m && m.type;
    if (t === 'typing') {
        showTypingIndicator((m.sender_id || '').toLowerCase());
    } else if (t === 'stop_typing') {
        clearTypingIndicator((m.sender_id || '').toLowerCase());
    } else if (t === 'new_message') {
        pollInbox(); // мгновенная доставка
    } else if (t === 'webrtc_offer' || t === 'webrtc_answer' || t === 'webrtc_ice' || t === 'webrtc_hangup' || t === 'webrtc_busy') {
        // Мгновенный сигналинг звонков через WS (дубль через сообщения отсеется по sig_id)
        const subtypeMap = { webrtc_offer: 'offer', webrtc_answer: 'answer', webrtc_ice: 'candidate', webrtc_hangup: 'hangup', webrtc_busy: 'busy' };
        const payload = { subtype: subtypeMap[t], sig_id: m.sig_id };
        if (t === 'webrtc_offer') {
            payload.sdp = { type: 'offer', sdp: m.sdp };
            payload.videoEnabled = !!(m.isVideoCall || m.videoEnabled);
        } else if (t === 'webrtc_answer') {
            payload.sdp = { type: 'answer', sdp: m.sdp };
        } else if (t === 'webrtc_ice') {
            payload.candidate = { candidate: m.candidate || m.sdp, sdpMid: m.sdpMid, sdpMLineIndex: m.sdpMLineIndex };
        }
        handleWebRTCMessage((m.sender_id || '').toLowerCase(), payload);
    }
}

function sendTypingSignal() {
    if (!selectedPeer || selectedPeer === myId) return;
    if (myGroupsCache[selectedPeer]) return; // индикатор только в личных чатах
    const now = Date.now();
    if (now - lastTypingSent < 2500) return; // throttle
    lastTypingSent = now;
    try {
        if (realtimeWs && realtimeWs.readyState === 1) {
            realtimeWs.send(JSON.stringify({ type: 'typing', recipient_id: selectedPeer }));
        }
    } catch (e) {}
}

function showTypingIndicator(peerId) {
    if (!peerId) return;
    peerId = peerId.toLowerCase();
    if (selectedPeer && selectedPeer.toLowerCase() === peerId && peerStatusEl) {
        peerStatusEl.textContent = 'печатает...';
        peerStatusEl.className = 'tg-peer-status typing';
    }
    if (typingTimeouts[peerId]) clearTimeout(typingTimeouts[peerId]);
    typingTimeouts[peerId] = setTimeout(() => clearTypingIndicator(peerId), 4000);
    renderContactsList();
}

function clearTypingIndicator(peerId) {
    if (!peerId) return;
    peerId = peerId.toLowerCase();
    if (typingTimeouts[peerId]) { clearTimeout(typingTimeouts[peerId]); delete typingTimeouts[peerId]; }
    if (selectedPeer && selectedPeer.toLowerCase() === peerId) {
        updatePeerStatus(selectedPeer, profileCache[selectedPeer]);
    }
    renderContactsList();
}

if (messageInput) {
    messageInput.addEventListener('input', sendTypingSignal);
}


// =====================================================================
// ЗАКРЕПЛЁННЫЕ СООБЩЕНИЯ
// Закреп хранится локально (per chat) и транслируется собеседнику
// контрольным сообщением 'pin'/'unpin', чтобы у обоих был один закреп.
// =====================================================================
function pinStorageKey(peerId) { return `pinned_${myId}_${(peerId || '').toLowerCase()}`; }
function getPinnedId(peerId) {
    if (!peerId) return null;
    try { return localStorage.getItem(pinStorageKey(peerId)) || null; } catch (e) { return null; }
}
function setPinnedId(peerId, msgId) {
    if (!peerId) return;
    try {
        if (msgId) localStorage.setItem(pinStorageKey(peerId), msgId);
        else localStorage.removeItem(pinStorageKey(peerId));
    } catch (e) {}
}
function msgPreviewText(msg) {
    if (!msg) return '';
    const type = msg.payload ? msg.payload.type : 'text';
    const content = msg.payload ? msg.payload.content : msg.plaintext;
    if (type === 'image') return '📷 Фотография';
    if (type === 'voice') return '🎤 Голосовое сообщение';
    if (type === 'video_msg') return '📹 Видеосообщение';
    if (type === 'file') return '📂 Файл: ' + ((msg.payload && msg.payload.filename) || 'Документ');
    if (type === 'poll') return '📊 Опрос: ' + ((msg.payload && msg.payload.question) || '');
    return content || '';
}
function renderPinnedBar(peerId) {
    const bar = document.getElementById('pinned-bar');
    if (!bar) return;
    const textEl = document.getElementById('pinned-bar-text');
    const pid = getPinnedId(peerId);
    const msg = pid ? messages.find(m => m.message_id === pid) : null;
    if (!msg) {
        if (pid) setPinnedId(peerId, null);
        bar.classList.add('hidden');
        return;
    }
    if (textEl) textEl.textContent = msgPreviewText(msg);
    bar.classList.remove('hidden');
}
function pinMessage(peerId, msgId) {
    if (!peerId) return;
    setPinnedId(peerId, msgId);
    renderPinnedBar(peerId);
    // транслируем собеседнику/группе (для «Избранного» не нужно)
    if (peerId !== myId) {
        sendPayloadMessage({ type: msgId ? 'pin' : 'unpin', target_id: msgId || '' }, peerId);
    }
}

(function initPinUI() {
    const bar = document.getElementById('pinned-bar');
    const unpinBtn = document.getElementById('pinned-bar-unpin');
    const ctxPin = document.getElementById('ctx-pin');
    if (bar) {
        bar.addEventListener('click', (e) => {
            if (e.target.closest('#pinned-bar-unpin')) return;
            const pid = getPinnedId(selectedPeer);
            if (!pid) return;
            const el = findMessageElement(pid);
            if (el) {
                el.scrollIntoView({ behavior: 'smooth', block: 'center' });
                const b = el.querySelector('.tg-msg-bubble');
                if (b) { b.classList.add('flash-highlight'); setTimeout(() => b.classList.remove('flash-highlight'), 1000); }
            }
        });
    }
    if (unpinBtn) {
        unpinBtn.addEventListener('click', (e) => { e.stopPropagation(); pinMessage(selectedPeer, null); });
    }
    if (ctxPin) {
        ctxPin.addEventListener('click', () => {
            if (!contextTargetMsg || !selectedPeer) return;
            const cur = getPinnedId(selectedPeer);
            if (cur && cur === contextTargetMsg.message_id) pinMessage(selectedPeer, null);
            else pinMessage(selectedPeer, contextTargetMsg.message_id);
            if (msgContextMenu) msgContextMenu.classList.add('hidden');
        });
    }
})();


// =====================================================================
// ПОИСК ВНУТРИ ЧАТА
// =====================================================================
let chatSearchMatches = [];
let chatSearchIdx = -1;

function clearSearchHighlights() {
    messagesContainer.querySelectorAll('.tg-msg-bubble.search-match, .tg-msg-bubble.search-current')
        .forEach(b => { b.classList.remove('search-match'); b.classList.remove('search-current'); });
}
function resetChatSearch() {
    chatSearchMatches = []; chatSearchIdx = -1;
    const bar = document.getElementById('chat-search-bar');
    const input = document.getElementById('chat-search-input');
    const cnt = document.getElementById('chat-search-count');
    if (input) input.value = '';
    if (cnt) cnt.textContent = '';
    if (bar) bar.classList.add('hidden');
    clearSearchHighlights();
}
function focusSearchMatch() {
    chatSearchMatches.forEach(b => b.classList.remove('search-current'));
    const b = chatSearchMatches[chatSearchIdx];
    if (b) { b.classList.add('search-current'); b.scrollIntoView({ behavior: 'smooth', block: 'center' }); }
    const cnt = document.getElementById('chat-search-count');
    if (cnt) cnt.textContent = chatSearchMatches.length ? `${chatSearchIdx + 1}/${chatSearchMatches.length}` : '0/0';
}
function runChatSearch(q) {
    clearSearchHighlights();
    chatSearchMatches = []; chatSearchIdx = -1;
    const cnt = document.getElementById('chat-search-count');
    q = (q || '').trim().toLowerCase();
    if (!q) { if (cnt) cnt.textContent = ''; return; }
    messagesContainer.querySelectorAll('.tg-msg-wrapper').forEach(w => {
        const bubble = w.querySelector('.tg-msg-bubble');
        if (!bubble) return;
        const txt = (bubble.textContent || '').toLowerCase();
        if (txt.includes(q)) { bubble.classList.add('search-match'); chatSearchMatches.push(bubble); }
    });
    if (chatSearchMatches.length) { chatSearchIdx = 0; focusSearchMatch(); }
    else if (cnt) cnt.textContent = '0/0';
}

(function initChatSearchUI() {
    const btn = document.getElementById('chat-search-btn');
    const bar = document.getElementById('chat-search-bar');
    const input = document.getElementById('chat-search-input');
    const nextBtn = document.getElementById('chat-search-next');
    const prevBtn = document.getElementById('chat-search-prev');
    const closeBtn = document.getElementById('chat-search-close');
    if (btn && bar) {
        btn.addEventListener('click', () => {
            bar.classList.toggle('hidden');
            if (!bar.classList.contains('hidden')) { if (input) input.focus(); }
            else resetChatSearch();
        });
    }
    if (input) {
        let t;
        input.addEventListener('input', () => { clearTimeout(t); t = setTimeout(() => runChatSearch(input.value), 200); });
        input.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                e.preventDefault();
                if (chatSearchMatches.length) { chatSearchIdx = (chatSearchIdx + 1) % chatSearchMatches.length; focusSearchMatch(); }
            }
        });
    }
    if (nextBtn) nextBtn.addEventListener('click', () => { if (chatSearchMatches.length) { chatSearchIdx = (chatSearchIdx + 1) % chatSearchMatches.length; focusSearchMatch(); } });
    if (prevBtn) prevBtn.addEventListener('click', () => { if (chatSearchMatches.length) { chatSearchIdx = (chatSearchIdx - 1 + chatSearchMatches.length) % chatSearchMatches.length; focusSearchMatch(); } });
    if (closeBtn) closeBtn.addEventListener('click', () => resetChatSearch());
})();


// =====================================================================
// ОПРОСЫ
// =====================================================================
function normalizedPollOptions(payload) {
    const raw = payload && payload.options;
    if (!Array.isArray(raw)) return [];
    return raw.slice(0, 10).map(option => String(option == null ? '' : option).slice(0, 100));
}
function pollVotesArr(votes, user) {
    const v = (votes || {})[user];
    if (Array.isArray(v)) return v;
    if (v != null) return [v];
    return [];
}
function pollCounts(msg) {
    const opts = normalizedPollOptions(msg.payload);
    const counts = opts.map(() => 0);
    let totalVoters = 0;
    Object.keys(msg.poll_votes || {}).forEach(u => {
        const arr = pollVotesArr(msg.poll_votes, u);
        if (arr.length) totalVoters++;
        arr.forEach(i => { if (counts[i] !== undefined) counts[i]++; });
    });
    return { counts, totalVoters };
}
function renderPollHtml(msg) {
    const p = msg.payload || {};
    const options = normalizedPollOptions(p);
    const { counts, totalVoters } = pollCounts(msg);
    const mine = pollVotesArr(msg.poll_votes, myId);
    let opts = '';
    options.forEach((o, i) => {
        const c = counts[i];
        const pct = totalVoters ? Math.round(c * 100 / totalVoters) : 0;
        opts += `<div class="poll-option ${mine.includes(i) ? 'voted' : ''}" data-opt="${i}">
            <div class="poll-option-row"><span class="poll-option-text">${mine.includes(i) ? '☑ ' : ''}${escapeHtmlSafe(o)}</span><span class="poll-option-pct">${pct}%</span></div>
            <div class="poll-bar"><div class="poll-bar-fill" style="width:${pct}%"></div></div>
        </div>`;
    });
    const multi = p.multiple ? ' • неск. ответов' : '';
    return `<div class="tg-poll" data-poll="${escapeHtmlSafe(msg.message_id)}">
        <div class="poll-question">📊 ${escapeHtmlSafe(p.question || '')}</div>
        <div class="poll-options">${opts}</div>
        <div class="poll-total">${totalVoters} проголосовал(о)${multi}</div>
    </div>`;
}
function attachPollHandlers(wrapper, msg) {
    wrapper.querySelectorAll('.poll-option').forEach(el => {
        el.addEventListener('click', (e) => {
            e.stopPropagation();
            const idx = parseInt(el.dataset.opt, 10);
            votePoll(msg, idx);
        });
    });
}
function votePoll(msg, optIdx) {
    if (!msg.payload || msg.payload.type !== 'poll') return;
    if (!msg.message_id || msg.message_id.toString().startsWith('temp_')) return; // ждём отправки
    if (!msg.poll_votes) msg.poll_votes = Object.create(null);
    let arr = pollVotesArr(msg.poll_votes, myId);
    if (msg.payload.multiple) {
        if (arr.includes(optIdx)) arr = arr.filter(x => x !== optIdx);
        else arr = arr.concat([optIdx]);
    } else {
        arr = [optIdx];
    }
    msg.poll_votes[myId] = arr;
    saveMessagesLocally();
    updatePollUI(msg.message_id);
    if (msg.peer) {
        sendPayloadMessage({ type: 'poll_vote', target_id: msg.message_id, options: arr }, msg.peer);
    }
}
function updatePollUI(msgId) {
    const msg = messages.find(m => m.message_id === msgId);
    if (!msg) return;
    const wrapper = findMessageElement(msgId);
    if (!wrapper) return;
    const pollEl = wrapper.querySelector('.tg-poll');
    if (!pollEl) return;
    const tmp = document.createElement('div');
    tmp.innerHTML = renderPollHtml(msg);
    pollEl.replaceWith(tmp.firstElementChild);
    attachPollHandlers(wrapper, msg);
}

// ----- Poll create modal -----
(function initPollModal() {
    const modal = document.getElementById('create-poll-modal');
    const openBtn = document.getElementById('attach-poll-btn');
    const closeBtn = document.getElementById('close-poll-modal-btn');
    const optionsList = document.getElementById('poll-options-list');
    const addOptBtn = document.getElementById('poll-add-option-btn');
    const submitBtn = document.getElementById('submit-poll-btn');
    const qInput = document.getElementById('poll-question-input');
    const multiCb = document.getElementById('poll-multiple-cb');
    const statusEl = document.getElementById('poll-status');
    const attachmentDrawer = document.getElementById('attachment-drawer');
    if (!modal) return;

    function makeOptionInput() {
        const inp = document.createElement('input');
        inp.type = 'text';
        inp.className = 'tg-input poll-opt-input';
        inp.placeholder = 'Вариант';
        inp.maxLength = 100;
        inp.style.cssText = 'background: var(--bg-secondary); color: var(--text-primary); border: 1px solid var(--border-color);';
        return inp;
    }
    function resetPoll() {
        if (qInput) qInput.value = '';
        if (multiCb) multiCb.checked = false;
        if (statusEl) statusEl.textContent = '';
        if (optionsList) {
            optionsList.innerHTML = '';
            optionsList.appendChild(makeOptionInput());
            optionsList.appendChild(makeOptionInput());
        }
    }
    if (openBtn) {
        openBtn.addEventListener('click', () => {
            if (attachmentDrawer) attachmentDrawer.classList.add('hidden');
            resetPoll();
            modal.classList.remove('hidden');
        });
    }
    if (closeBtn) closeBtn.addEventListener('click', () => modal.classList.add('hidden'));
    if (addOptBtn) addOptBtn.addEventListener('click', () => {
        const inputs = optionsList.querySelectorAll('.poll-opt-input');
        if (inputs.length >= 10) return;
        optionsList.appendChild(makeOptionInput());
    });
    if (submitBtn) {
        submitBtn.addEventListener('click', () => {
            if (!selectedPeer) return;
            const question = (qInput.value || '').trim();
            const options = Array.from(optionsList.querySelectorAll('.poll-opt-input'))
                .map(i => i.value.trim()).filter(v => v.length);
            if (!question) { statusEl.textContent = 'Введите вопрос'; statusEl.className = 'tg-status error'; return; }
            if (options.length < 2) { statusEl.textContent = 'Нужно минимум 2 варианта'; statusEl.className = 'tg-status error'; return; }
            const payload = {
                type: 'poll',
                question: question,
                options: options,
                multiple: !!(multiCb && multiCb.checked),
                poll_id: 'poll_' + Date.now()
            };
            sendPayloadMessage(payload);
            modal.classList.add('hidden');
        });
    }
})();


// =====================================================================
// САМОУНИЧТОЖАЮЩИЕСЯ СООБЩЕНИЯ
// =====================================================================
function scheduleSelfDestruct(msg, wrapper) {
    if (!msg || !msg.payload || !msg.payload.ttl) return;
    if (!msg.ttl_expires) {
        msg.ttl_expires = Date.now() + msg.payload.ttl * 1000;
        saveMessagesLocally();
    }
    const bubble = wrapper.querySelector('.tg-msg-bubble');
    if (bubble) {
        const timeEl = bubble.querySelector('.tg-msg-time');
        if (timeEl && !timeEl.querySelector('.sd-badge')) {
            const badge = document.createElement('span');
            badge.className = 'sd-badge';
            badge.textContent = '🔥';
            timeEl.prepend(badge);
        }
    }
    const remaining = msg.ttl_expires - Date.now();
    if (remaining <= 0) { destroyMessage(msg); return; }
    setTimeout(() => destroyMessage(msg), remaining);
}
function destroyMessage(msg) {
    const id = msg.message_id;
    if (!id) return;
    messages = messages.filter(m => m.message_id !== id);
    const w = findMessageElement(id);
    if (w) w.remove();
    if (msg.peer && getPinnedId(msg.peer) === id) setPinnedId(msg.peer, null);
    saveMessagesLocally();
    renderContactsList();
    if (selectedPeer === msg.peer) renderPinnedBar(selectedPeer);
}
function purgeExpiredSelfDestruct() {
    const now = Date.now();
    const before = messages.length;
    messages = messages.filter(m => !(m.ttl_expires && m.ttl_expires <= now));
    if (messages.length !== before) saveMessagesLocally();
}

// ----- Self-destruct timer chip + modal -----
function ttlLabel(sec) {
    if (!sec) return 'выкл';
    if (sec < 60) return sec + 'с';
    if (sec < 3600) return (sec / 60) + ' мин';
    if (sec < 86400) return (sec / 3600) + ' ч';
    return (sec / 86400) + ' дн';
}
function updateSdChip() {
    const chip = document.getElementById('self-destruct-chip');
    const label = document.getElementById('sd-chip-label');
    if (!chip) return;
    if (selfDestructTtl > 0) {
        if (label) label.textContent = ttlLabel(selfDestructTtl);
        chip.classList.remove('hidden');
    } else {
        chip.classList.add('hidden');
    }
}
(function initSelfDestructUI() {
    const modal = document.getElementById('self-destruct-modal');
    const openBtn = document.getElementById('attach-timer-btn');
    const closeBtn = document.getElementById('close-sd-modal-btn');
    const chipOff = document.getElementById('sd-chip-off');
    const attachmentDrawer = document.getElementById('attachment-drawer');
    if (openBtn) {
        openBtn.addEventListener('click', () => {
            if (attachmentDrawer) attachmentDrawer.classList.add('hidden');
            if (modal) modal.classList.remove('hidden');
        });
    }
    if (closeBtn) closeBtn.addEventListener('click', () => modal.classList.add('hidden'));
    if (modal) {
        modal.querySelectorAll('.sd-opt').forEach(btn => {
            btn.addEventListener('click', () => {
                selfDestructTtl = parseInt(btn.dataset.ttl, 10) || 0;
                updateSdChip();
                modal.classList.add('hidden');
            });
        });
    }
    if (chipOff) chipOff.addEventListener('click', () => { selfDestructTtl = 0; updateSdChip(); });
})();


// =====================================================================
// РАЗДЕЛИТЕЛИ ДАТ + КРУПНЫЕ ЭМОДЗИ + КНОПКА ВНИЗ + КОПИРОВАНИЕ + ЭМОДЗИ
// =====================================================================
let lastRenderedDate = null;

function dateLabel(ts) {
    const d = new Date(ts), now = new Date();
    const dd = new Date(d.getFullYear(), d.getMonth(), d.getDate());
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    const diffDays = Math.round((today - dd) / 86400000);
    if (diffDays === 0) return 'Сегодня';
    if (diffDays === 1) return 'Вчера';
    const months = ['января','февраля','марта','апреля','мая','июня','июля','августа','сентября','октября','ноября','декабря'];
    let lbl = d.getDate() + ' ' + months[d.getMonth()];
    if (d.getFullYear() !== now.getFullYear()) lbl += ' ' + d.getFullYear();
    return lbl;
}
function maybeInsertDateDivider(msg) {
    const ts = msg.timestamp || Date.now();
    const k = new Date(ts);
    const dayKey = k.getFullYear() + '-' + k.getMonth() + '-' + k.getDate();
    if (dayKey === lastRenderedDate) return;
    lastRenderedDate = dayKey;
    const div = document.createElement('div');
    div.className = 'tg-date-divider';
    div.textContent = dateLabel(ts);
    messagesContainer.appendChild(div);
}

function isEmojiOnlyText(str) {
    if (!str) return false;
    const t = String(str).trim();
    if (!t) return false;
    let stripped;
    try {
        stripped = t.replace(/[\p{Extended_Pictographic}‍️\u{1F3FB}-\u{1F3FF}\u{1F1E6}-\u{1F1FF}]/gu, '');
    } catch (e) { return false; }
    if (stripped.length !== 0) return false;
    let seq;
    try {
        seq = t.match(/(\p{Extended_Pictographic}(️)?(‍\p{Extended_Pictographic}(️)?)*|[\u{1F1E6}-\u{1F1FF}]{2})/gu) || [];
    } catch (e) { return false; }
    return seq.length >= 1 && seq.length <= 3;
}

// ----- Кнопка прокрутки вниз -----
(function initScrollBottom() {
    const btn = document.getElementById('scroll-bottom-btn');
    if (!btn || !messagesContainer) return;
    function update() {
        const dist = messagesContainer.scrollHeight - messagesContainer.scrollTop - messagesContainer.clientHeight;
        if (dist > 200) btn.classList.remove('hidden');
        else btn.classList.add('hidden');
    }
    messagesContainer.addEventListener('scroll', update);
    btn.addEventListener('click', () => { scrollToBottom(); btn.classList.add('hidden'); });
})();

// ----- Копировать текст сообщения -----
(function initCopy() {
    const ctxCopy = document.getElementById('ctx-copy');
    if (!ctxCopy) return;
    ctxCopy.addEventListener('click', async () => {
        if (!contextTargetMsg) return;
        const txt = (contextTargetMsg.payload && contextTargetMsg.payload.type === 'text')
            ? contextTargetMsg.payload.content : msgPreviewText(contextTargetMsg);
        try {
            await navigator.clipboard.writeText(txt || '');
        } catch (e) {
            const ta = document.createElement('textarea');
            ta.value = txt || '';
            document.body.appendChild(ta); ta.select();
            try { document.execCommand('copy'); } catch (_) {}
            document.body.removeChild(ta);
        }
        if (msgContextMenu) msgContextMenu.classList.add('hidden');
    });
})();

// ----- Эмодзи-панель (через шторку вложений) -----
const EMOJI_SET = ['😀','😁','😂','🤣','😊','😇','🙂','🙃','😉','😍','🥰','😘','😗','😋','😛','😜','🤪','😝','🤑','🤗','🤭','🤔','🤐','😐','😑','😶','😏','😒','🙄','😬','😴','😷','🤒','🤕','🤢','🤮','🥵','🥶','😵','🤯','🤠','🥳','😎','🤓','🧐','😕','😟','🙁','😮','😯','😲','😳','🥺','😦','😧','😨','😰','😥','😢','😭','😱','😖','😣','😞','😓','😩','😫','🥱','😤','😡','😠','🤬','😈','👿','💀','💩','🤡','👹','👺','👻','👽','🤖','😺','😸','😹','❤️','🧡','💛','💚','💙','💜','🖤','🤍','💔','❣️','💕','💞','💓','💗','💖','💘','💝','👍','👎','👌','✌️','🤞','🤟','🤘','👏','🙌','🤝','🙏','💪','👀','🔥','⭐','🌟','✨','⚡','💥','💯','🎉','🎊','🎁','🏆','⚽','🍕','☕','🌹','🌸','🚀','🌈','☀️','🌙'];
(function initEmoji() {
    const modal = document.getElementById('emoji-modal');
    const openBtn = document.getElementById('attach-emoji-btn');
    const closeBtn = document.getElementById('close-emoji-modal-btn');
    const grid = document.getElementById('emoji-grid');
    const attachmentDrawer = document.getElementById('attachment-drawer');
    if (!modal || !grid) return;
    EMOJI_SET.forEach(e => {
        const cell = document.createElement('div');
        cell.className = 'emoji-cell';
        cell.textContent = e;
        cell.addEventListener('click', () => insertEmoji(e));
        grid.appendChild(cell);
    });
    if (openBtn) openBtn.addEventListener('click', () => {
        if (attachmentDrawer) attachmentDrawer.classList.add('hidden');
        modal.classList.remove('hidden');
    });
    if (closeBtn) closeBtn.addEventListener('click', () => modal.classList.add('hidden'));
})();
function insertEmoji(e) {
    if (!messageInput) return;
    messageInput.value = (messageInput.value || '') + e;
    messageInput.dispatchEvent(new Event('input'));
    messageInput.focus();
}


// =====================================================================
// КАСТОМИЗАЦИЯ ЦВЕТА — свой акцент и фон поверх любой темы.
// Переменные ставятся inline на <body>, поэтому перебивают классы тем.
// =====================================================================
function loadAppliedCustomColors() {
    const accent = localStorage.getItem('settings_accent_color');
    if (accent) {
        document.body.style.setProperty('--accent-color', accent);
        document.body.style.setProperty('--accent-hover', accent);
    } else {
        document.body.style.removeProperty('--accent-color');
        document.body.style.removeProperty('--accent-hover');
    }
    const bg = localStorage.getItem('settings_bg_color');
    if (bg) document.body.style.setProperty('--bg-color', bg);
    else document.body.style.removeProperty('--bg-color');

    const ai = document.getElementById('settings-accent-input');
    if (ai && accent && /^#[0-9a-fA-F]{6}$/.test(accent)) ai.value = accent;
    const bi = document.getElementById('settings-bg-input');
    if (bi && bg && /^#[0-9a-fA-F]{6}$/.test(bg)) bi.value = bg;
}

(function initColorCustomize() {
    const accentInput = document.getElementById('settings-accent-input');
    const accentReset = document.getElementById('settings-accent-reset');
    const bgInput = document.getElementById('settings-bg-input');
    const bgReset = document.getElementById('settings-bg-reset');
    const swatchWrap = document.getElementById('accent-swatches');
    const SWATCHES = ['#3390ec', '#8774e1', '#ff7a85', '#4dd0e1', '#66bb6a', '#ffb74d', '#f06292', '#ffffff'];
    if (swatchWrap) {
        SWATCHES.forEach(c => {
            const sw = document.createElement('div');
            sw.style.cssText = 'width:22px;height:22px;border-radius:50%;cursor:pointer;background:' + c + ';border:2px solid rgba(255,255,255,0.25);';
            sw.title = c;
            sw.addEventListener('click', () => {
                localStorage.setItem('settings_accent_color', c);
                if (accentInput) accentInput.value = c;
                loadAppliedCustomColors();
            });
            swatchWrap.appendChild(sw);
        });
    }
    if (accentInput) accentInput.addEventListener('input', () => {
        localStorage.setItem('settings_accent_color', accentInput.value);
        loadAppliedCustomColors();
    });
    if (accentReset) accentReset.addEventListener('click', () => {
        localStorage.removeItem('settings_accent_color');
        loadAppliedCustomColors();
    });
    if (bgInput) bgInput.addEventListener('input', () => {
        localStorage.setItem('settings_bg_color', bgInput.value);
        loadAppliedCustomColors();
    });
    if (bgReset) bgReset.addEventListener('click', () => {
        localStorage.removeItem('settings_bg_color');
        loadAppliedCustomColors();
    });
})();
loadAppliedCustomColors();
