// Мультисерверность веб-клиента: обнаружение, доверие, реестр пространств.
//
// Пространство (space) — это пара «сервер + аккаунт на нём». Всё состояние
// принадлежит одному пространству: переписка, ключи, токен. Между
// пространствами не разделяется ничего.
//
// Канон обнаружения и подписи — docs/MULTI_SERVER_DESIGN.md, раздел 8.1.
// Здесь обязана быть та же логика, что в core/src/discovery.rs: разъедутся —
// и «сервер найден» в вебе будет означать не то же, что на iOS.

const AETHER_PROTOCOL_VERSION = 1;

// Порядок опроса. Последний путь — для инстансов, ещё не знающих про
// версионирование: они ответят 404 на первые два.
const DISCOVERY_PATHS = ['/.well-known/aether', '/api/v1/server/info', '/server/info'];

function aetherIsPrivateHost(host) {
    const h = String(host || '').trim().toLowerCase();
    if (h === 'localhost' || h.endsWith('.local') || h.endsWith('.localhost')) return true;
    if (h === '::1' || h.startsWith('fe80:') || h.startsWith('fc') || h.startsWith('fd')) return true;
    const o = h.split('.');
    if (o.length !== 4 || !o.every(x => /^\d{1,3}$/.test(x) && Number(x) <= 255)) return false;
    const n = o.map(Number);
    if (n[0] === 10 || n[0] === 127) return true;
    if (n[0] === 192 && n[1] === 168) return true;
    if (n[0] === 169 && n[1] === 254) return true;
    return n[0] === 172 && n[1] >= 16 && n[1] <= 31;
}

/// Разбор адреса, введённого человеком, в список кандидатов.
///
/// Открытый транспорт предлагается ТОЛЬКО для приватных диапазонов и только
/// явным согласием: включать его для публичного домена нельзя ни по какой
/// просьбе пользователя.
function aetherNormalizeServerInput(input, allowCleartext) {
    const raw = String(input || '').trim();
    if (!raw) return [];
    const stripped = raw.replace(/^aether:\/\//i, '');
    let scheme = null, rest = stripped;
    if (/^https:\/\//i.test(stripped)) { scheme = 'https'; rest = stripped.slice(8); }
    else if (/^http:\/\//i.test(stripped)) { scheme = 'http'; rest = stripped.slice(7); }

    const authority = rest.split(/[/?#]/)[0].replace(/\.+$/, '');
    // Логин в адресе — верный признак подделки вида https://aether.app@evil.example.
    if (!authority || authority.includes(' ') || authority.includes('@')) return [];

    const host = authority.startsWith('[')
        ? authority.slice(1, authority.indexOf(']'))
        : (authority.includes(':') ? authority.slice(0, authority.lastIndexOf(':')) : authority);
    if (!host) return [];
    const priv = aetherIsPrivateHost(host);

    if (scheme === 'https') return [`https://${authority}`];
    if (scheme === 'http') return (allowCleartext && priv) ? [`http://${authority}`] : [];
    const out = [`https://${authority}`];
    if (allowCleartext && priv) out.push(`http://${authority}`);
    return out;
}

function aetherB64UrlFromBytes(bytes) {
    let s = '';
    for (const b of bytes) s += String.fromCharCode(b);
    return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/// Отпечаток ключа сервера — то, что человек сверяет с владельцем.
async function aetherServerFingerprint(publicKeyB64) {
    const raw = publicKeyB64.replace(/-/g, '+').replace(/_/g, '/');
    const bin = atob(raw + '='.repeat((4 - raw.length % 4) % 4));
    const bytes = Uint8Array.from(bin, c => c.charCodeAt(0));
    const digest = await crypto.subtle.digest('SHA-256', bytes);
    return aetherB64UrlFromBytes(new Uint8Array(digest));
}

function aetherFormatFingerprint(fp) {
    return (fp.match(/.{1,4}/g) || []).join(' ');
}

/// Каноническая строка подписи AETHER-SERVER-INFO-1.
///
/// Порядок полей и разделитель фиксированы: любой клиент обязан собрать ровно
/// эти байты, иначе подпись не сойдётся. Ровно то же собирает сервер в
/// server/server_identity.py и ядро в core/src/discovery.rs.
function aetherCanonicalInfo(d) {
    return [
        'AETHER-SERVER-INFO-1',
        d.server_id, d.name, d.api_url, d.websocket_url,
        String(d.registration_mode || '').toLowerCase(),
        String(AETHER_PROTOCOL_VERSION),
        d.signed_at, d.nonce
    ].join('\n');
}

function aetherFreshNonce() {
    const b = new Uint8Array(16);
    crypto.getRandomValues(b);
    return aetherB64UrlFromBytes(b);
}

function aetherUrlHost(url) {
    try { return new URL(url).hostname; } catch { return ''; }
}

/// Опросить один origin и проверить подпись ответа.
async function aetherFetchServerInfo(origin, nonce, ratchetApi) {
    const base = origin.replace(/\/+$/, '');
    let lastError = null;

    for (const path of DISCOVERY_PATHS) {
        let res;
        try {
            res = await fetch(`${base}${path}?nonce=${encodeURIComponent(nonce)}`, { method: 'GET' });
        } catch (e) {
            // Сеть или TLS: следующий путь на том же origin не поможет.
            throw new Error('Нет соединения с сервером');
        }
        if (res.status === 404) { lastError = 'not_found'; continue; }
        if (!res.ok) throw new Error(`Сервер ответил ошибкой ${res.status}`);

        const d = await res.json();
        if (d.protocol !== 'aether') throw new Error('По этому адресу нет сервера Aether');
        const version = Number(d.protocol_version || 0);
        if (!version) throw new Error('Сервер не сообщил версию протокола');
        if (version > AETHER_PROTOCOL_VERSION) {
            throw new Error(`Сервер говорит на протоколе v${version}, клиент понимает v${AETHER_PROTOCOL_VERSION}`);
        }
        if (!d.server_id || !d.api_url || !d.websocket_url || !d.public_key_b64) {
            throw new Error('Ответ сервера неполон');
        }
        // Nonce сверяется ДО подписи: без него подпись доказывает лишь то, что
        // документ когда-то был подписан, а не что это ответ на наш запрос.
        if (d.nonce !== nonce) {
            throw new Error('Сервер вернул чужой nonce — возможен повтор старого ответа');
        }
        if (!ratchetApi.ed25519_verify(d.public_key_b64, aetherCanonicalInfo(d), d.signature_b64)) {
            throw new Error('Подпись сервера не сошлась');
        }

        const originHost = aetherUrlHost(base);
        return {
            origin: base,
            server_id: d.server_id,
            name: d.name || 'Aether Server',
            api_url: d.api_url,
            websocket_url: d.websocket_url,
            registration_mode: String(d.registration_mode || 'closed').toLowerCase(),
            protocol_version: version,
            supports_e2ee: !!d.supports_e2ee,
            supports_data_import: !!d.supports_data_import,
            capabilities: Array.isArray(d.capabilities) ? d.capabilities : [],
            official: !!d.official,
            software: d.software ? `${d.software.name || ''} ${d.software.version || ''}`.trim() : '',
            public_key_b64: d.public_key_b64,
            fingerprint: await aetherServerFingerprint(d.public_key_b64),
            signed_at: d.signed_at,
            // Host подконтролен посреднику: если сервер называет адреса на
            // другом домене, это повод для тревоги, а не для доверия.
            endpoints_match_origin: aetherUrlHost(d.api_url) === originHost
                                 && aetherUrlHost(d.websocket_url) === originHost,
            cleartext: base.startsWith('http://')
        };
    }
    throw new Error(lastError === 'not_found'
        ? 'По этому адресу нет сервера Aether'
        : 'Сервер не ответил');
}

/// Полный проход: нормализовать ввод и опросить кандидатов по очереди.
async function aetherDiscoverServer(input, allowCleartext, ratchetApi) {
    const candidates = aetherNormalizeServerInput(input, allowCleartext);
    if (!candidates.length) throw new Error('Адрес не разобран');
    let last = null;
    for (const origin of candidates) {
        try { return await aetherFetchServerInfo(origin, aetherFreshNonce(), ratchetApi); }
        catch (e) { last = e; }
    }
    throw last || new Error('Сервер не ответил');
}

// --- Реестр серверов ----------------------------------------------------------
//
// Хранится в localStorage обычным JSON: секретов внутри нет, токены и ключи
// живут отдельно, под ключом пространства.

const AETHER_SERVERS_KEY = 'aether.servers.v1';

function aetherLoadServers() {
    try {
        const raw = localStorage.getItem(AETHER_SERVERS_KEY);
        const parsed = raw ? JSON.parse(raw) : null;
        return (parsed && Array.isArray(parsed.servers)) ? parsed : { servers: [], active: null };
    } catch { return { servers: [], active: null }; }
}

function aetherSaveServers(state) {
    localStorage.setItem(AETHER_SERVERS_KEY, JSON.stringify(state));
}

function aetherFindServer(state, serverId) {
    return state.servers.find(s => s.id === serverId) || null;
}

/// Добавить или обновить сервер, поставив TOFU-пин при первом знакомстве.
///
/// Пин ставится ОДИН раз. Дальше он только сверяется: переписывать его молча —
/// значит отменить всю защиту от подмены.
function aetherUpsertServer(state, info, displayName) {
    let record = aetherFindServer(state, info.server_id);
    const now = new Date().toISOString();
    if (!record) {
        record = {
            id: info.server_id,
            kind: info.official ? 'official' : 'custom',
            displayName: displayName || info.name,
            addedAt: now,
            accounts: [],
            pin: {
                serverId: info.server_id,
                publicKey: info.public_key_b64,
                fingerprint: info.fingerprint,
                firstSeenAt: now
            }
        };
        state.servers.push(record);
    }
    record.declaredName = info.name;
    if (displayName) record.displayName = displayName;
    record.origin = info.origin;
    record.apiUrl = info.api_url;
    record.wsUrl = info.websocket_url;
    record.registrationMode = info.registration_mode;
    record.capabilities = info.capabilities;
    record.cleartext = info.cleartext;
    record.lastSeenAt = now;
    aetherSaveServers(state);
    return record;
}

/// Сверка личности сервера с запомненной. Возвращает 'known' | 'changed' | 'new'.
function aetherCheckPin(state, info) {
    const byId = aetherFindServer(state, info.server_id);
    if (byId && byId.pin) {
        return byId.pin.fingerprint === info.fingerprint ? 'known' : 'changed';
    }
    // По этому адресу знаем ДРУГОЙ сервер — значит здесь теперь кто-то другой.
    const byOrigin = state.servers.find(s => s.origin === info.origin && s.id !== info.server_id);
    return byOrigin ? 'changed' : 'new';
}

if (typeof window !== 'undefined') {
    window.aetherNormalizeServerInput = aetherNormalizeServerInput;
    window.aetherIsPrivateHost = aetherIsPrivateHost;
    window.aetherDiscoverServer = aetherDiscoverServer;
    window.aetherServerFingerprint = aetherServerFingerprint;
    window.aetherFormatFingerprint = aetherFormatFingerprint;
    window.aetherCanonicalInfo = aetherCanonicalInfo;
    window.aetherLoadServers = aetherLoadServers;
    window.aetherSaveServers = aetherSaveServers;
    window.aetherUpsertServer = aetherUpsertServer;
    window.aetherCheckPin = aetherCheckPin;
    window.aetherFindServer = aetherFindServer;
}

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { aetherNormalizeServerInput, aetherIsPrivateHost, aetherCanonicalInfo,
                       aetherLoadServers, aetherUpsertServer, aetherCheckPin };
}

// --- Разделение хранилища по пространствам ------------------------------------
//
// Локальные данные уже были разделены по аккаунту (`messages_<логин>`), но НЕ по
// серверу. Одинаковый логин на двух серверах писал бы поверх: переписка,
// ключи Olm, идентификатор устройства — всё смешалось бы. Тот же изъян был на
// iOS в ключах Keychain и чинился там же сменой первичного ключа на пару
// (server_id, логин).
//
// Ключи, которые принадлежат пространству. Порядок важен: `pinned_` проверяется
// как префикс, остальные — как точное начало до логина.
const AETHER_SPACE_KEY_PREFIXES = [
    'messages_', 'ratchet_', 'device_id_', 'sec_settings_', 'chat_settings_',
    'contacts_custom_list_', 'contacts_custom_names_', 'olm_published_v2_',
    'olm_fallback_ts_', 'last_sync_timestamp_', 'salt_', 'pinned_'
];

/// Короткая метка сервера для имён ключей.
///
/// Для сервера, который умеет представляться, это начало его server_id. Для
/// старого инстанса (боевой ещё не обновлён) — отпечаток адреса: он тоже
/// постоянен и различает серверы между собой, чего для разделения достаточно.
function aetherServerTag(serverIdOrOrigin) {
    const v = String(serverIdOrOrigin || '');
    const looksLikeUuid = /^[0-9a-f]{8}-[0-9a-f]{4}-/i.test(v);
    if (looksLikeUuid) return v.replace(/-/g, '').slice(0, 8).toLowerCase();
    // Простой стабильный хеш адреса: FNV-1a. Криптостойкость здесь не нужна —
    // это имя ключа в localStorage, а не секрет.
    let h = 0x811c9dc5;
    for (let i = 0; i < v.length; i++) {
        h ^= v.charCodeAt(i);
        h = (h * 0x01000193) >>> 0;
    }
    return ('0000000' + h.toString(16)).slice(-8);
}

/// Пространство имён: сервер + аккаунт.
function aetherStorageScope(serverIdOrOrigin, userId) {
    return `${aetherServerTag(serverIdOrOrigin)}_${String(userId || '').toLowerCase()}`;
}

/// Перенести данные, лежащие под старым именем (только логин), в пространство.
///
/// Выполняется один раз на пространство. Существующий пользователь не должен
/// заметить перехода: переписка, ключи и идентификатор устройства обязаны
/// остаться на месте. Старые ключи не удаляются сразу — остаются ещё на одну
/// версию на случай отката сборки.
function aetherMigrateSpaceStorage(serverIdOrOrigin, userId) {
    const uid = String(userId || '').toLowerCase();
    if (!uid) return { migrated: 0, skipped: 'нет пользователя' };
    const scope = aetherStorageScope(serverIdOrOrigin, uid);
    const flag = `aether.spacemigrated.${scope}`;
    if (localStorage.getItem(flag) === '1') return { migrated: 0, skipped: 'уже перенесено' };

    let migrated = 0;
    for (const prefix of AETHER_SPACE_KEY_PREFIXES) {
        if (prefix === 'pinned_') {
            // pinned_<логин>_<собеседник>: перенос по префиксу.
            const oldStart = `pinned_${uid}_`;
            for (let i = 0; i < localStorage.length; i++) {
                const key = localStorage.key(i);
                if (!key || !key.startsWith(oldStart)) continue;
                const target = `pinned_${scope}_${key.slice(oldStart.length)}`;
                if (localStorage.getItem(target) === null) {
                    localStorage.setItem(target, localStorage.getItem(key));
                    migrated++;
                }
            }
            continue;
        }
        const oldKey = `${prefix}${uid}`;
        const newKey = `${prefix}${scope}`;
        const value = localStorage.getItem(oldKey);
        if (value !== null && localStorage.getItem(newKey) === null) {
            localStorage.setItem(newKey, value);
            migrated++;
        }
    }
    localStorage.setItem(flag, '1');
    return { migrated, scope };
}

if (typeof window !== 'undefined') {
    window.aetherServerTag = aetherServerTag;
    window.aetherStorageScope = aetherStorageScope;
    window.aetherMigrateSpaceStorage = aetherMigrateSpaceStorage;
}

if (typeof module !== 'undefined' && module.exports) {
    Object.assign(module.exports, { aetherServerTag, aetherStorageScope, aetherMigrateSpaceStorage });
}
