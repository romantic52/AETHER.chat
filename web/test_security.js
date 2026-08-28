// Small dependency-free regression checks for the browser trust boundary.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, 'app.js'), 'utf8');
const index = fs.readFileSync(path.join(__dirname, 'index.html'), 'utf8');
const server = fs.readFileSync(path.join(__dirname, '..', 'server', 'main.py'), 'utf8');
assert.doesNotMatch(source, /onclick\s*=\s*["']/i);
assert.doesNotMatch(source, /<(?:img|audio|video)[^>]+\bsrc=["']\$\{/i);
assert.doesNotMatch(source, /messagesContainer\.querySelector\([^\n]*data-id/);
assert.match(source, /escapeHtmlSafe\(lastMsgText\)/);
assert.match(source, /reactionsEl\.replaceChildren\(\)/);
// Исходящий direct обязан идти через ratchet-конверт, а не легаси-box. Имя
// сверяем с текущим (multi-device: конверт собирается на КАЖДОЕ устройство пира).
assert.match(source, /envelope: await ratchetEnvelopeForDevice\(targetPeer, dev\.device_id, wireJson\)/);
assert.match(source, /ratchet: ['"]1['"]/);
assert.match(source, /client_id: clientId/);
assert.match(source, /ackThis = decrypted/);
assert.match(source, /Ignoring non-Ratchet direct message/);
assert.doesNotMatch(source, /nacl\.box\(textBytes/);
assert.match(server, /Direct messages require Olm Double Ratchet/);
// P10 / SEC MED-3: веб публикует fallback-ключ, иначе исчерпание одноразовых
// (случайное или намеренное) глушит все новые переписки с этим устройством.
assert.match(source, /fallback_key: \{/);
// Метка ротации ставится ПОСЛЕ успешного upload: иначе сорвавшаяся публикация
// увела бы следующую попытку на неделю вперёд с ключом, которого сервер не видел.
assert.match(source, /uploadRes\.ok\)[\s\S]{0,400}?localStorage\.setItem\(fallbackTsKey/);
// Сервер обязан требовать подписанный бандл: переиспользуемый неподписанный
// fallback — идеальная точка подмены (один раз подсунул — читаешь начало переписок).
assert.match(server, /Fallback key requires a signed bundle/);
assert.match(index, /script-src ['"]self['"] ['"]wasm-unsafe-eval['"]/);
assert.doesNotMatch(index, /script-src[^;]*['"]unsafe-eval['"]/);

const helperStart = source.indexOf('function escapeHtmlSafe');
const helperEnd = source.indexOf('\n// Хелперы для URL-safe Base64', helperStart);
assert(helperStart >= 0 && helperEnd > helperStart);
const context = {
    serverUrl: 'https://relay.example.test',
    URL,
    window: { location: { href: 'https://example.test/', origin: 'https://example.test' } }
};
vm.runInNewContext(`${source.slice(helperStart, helperEnd)}; this.escapeHtmlSafe = escapeHtmlSafe; this.safeMediaUrl = safeMediaUrl; this.safeDownloadUrl = safeDownloadUrl; this.safeBlobMime = safeBlobMime;`, context);

assert.equal(context.escapeHtmlSafe('<img src=x onerror=alert(1)>'), '&lt;img src=x onerror=alert(1)&gt;');
assert.equal(context.safeMediaUrl('javascript:alert(1)', 'image'), '');
assert.equal(context.safeMediaUrl('data:text/html;base64,PHNjcmlwdD4=', 'image'), '');
assert.equal(context.safeMediaUrl('blob:https://example.test/asset', 'image'), 'blob:https://example.test/asset');
assert.equal(context.safeDownloadUrl('javascript:alert(1)'), '');
assert.equal(context.safeDownloadUrl('data:text/html,<script>alert(1)</script>'), '');
assert.equal(context.safeDownloadUrl('data:text/plain;base64,SGk='), 'data:text/plain;base64,SGk=');
assert.equal(context.safeDownloadUrl('https://relay.example.test/avatars/1'), 'https://relay.example.test/avatars/1');
assert.equal(context.safeDownloadUrl('https://other.example/file'), '');
assert.equal(context.safeBlobMime('text/html'), 'application/octet-stream');
assert.equal(context.safeBlobMime('image/png'), 'image/png');

const pollStart = source.indexOf('function normalizedPollOptions');
const pollEnd = source.indexOf('\nfunction pollVotesArr', pollStart);
assert(pollStart >= 0 && pollEnd > pollStart);
vm.runInNewContext(`${source.slice(pollStart, pollEnd)}; this.normalizedPollOptions = normalizedPollOptions;`, context);
assert.deepEqual(Array.from(context.normalizedPollOptions({ options: {} })), []);
assert.equal(context.normalizedPollOptions({ options: Array(20).fill('<svg/onload=1>') }).length, 10);
assert.equal(context.normalizedPollOptions({ options: ['x'.repeat(200)] })[0].length, 100);

// P10 / SEC MED-4: хранилище Olm-сессий по session_id. Проверяем на живом коде
// из app.js — миграцию прежних форматов, обновление на месте и лимит.
const sessStart = source.indexOf('function isSessionMap');
const sessEnd = source.indexOf('\nasync function saveRatchetState', sessStart);
assert(sessStart >= 0 && sessEnd > sessStart);
const sessCtx = {
    olmSessions: {},
    Date,
    // Подделка ядра: session_id зашит в сам pickle ('sess:<id>:<поколение>'),
    // поэтому шифрование меняет pickle, но НЕ идентификатор сессии — как в vodozemac.
    api: {
        session_id(pickle) {
            if (typeof pickle !== 'string' || !pickle.startsWith('sess:')) throw new Error('битый pickle');
            return pickle.split(':')[1];
        }
    }
};
vm.runInNewContext(`${source.slice(sessStart, sessEnd)};
    this.ratchetSessionsFor = ratchetSessionsFor;
    this.ratchetSessionPut = ratchetSessionPut;
    this.MAX_SESSIONS_PER_PEER = MAX_SESSIONS_PER_PEER;`, sessCtx);

// Миграция слотов: сессии переживают апгрейд С ПРАВИЛЬНЫМ session_id, иначе
// первый же входящий prekey не нашёл бы их и завёл дубль, спалив лишний OTK.
sessCtx.olmSessions['bob::web-1'] = { inbound: 'sess:A:1', outbound: 'sess:B:1', current: 'sess:C:1' };
let list = Array.from(sessCtx.ratchetSessionsFor(sessCtx.api, 'bob::web-1'));
assert.deepEqual(list.map(s => s.sessionId), ['A', 'B', 'C'], 'порядок прежней схемы сохранён');
assert.equal(list[0].session, 'sess:A:1', 'отправка берёт ту же сессию, что и до миграции');

// Легаси-строка (одна сессия) и битый pickle, который не должен ронять миграцию.
sessCtx.olmSessions['carol::web-1'] = 'sess:D:1';
assert.deepEqual(Array.from(sessCtx.ratchetSessionsFor(sessCtx.api, 'carol::web-1')).map(s => s.sessionId), ['D']);
sessCtx.olmSessions['dave::web-1'] = { inbound: 'не-pickle', outbound: 'sess:E:1' };
assert.deepEqual(Array.from(sessCtx.ratchetSessionsFor(sessCtx.api, 'dave::web-1')).map(s => s.sessionId), ['E']);

// Обновление существующей сессии не плодит записей (session_id при шифровании тот же).
sessCtx.ratchetSessionPut(sessCtx.api, 'bob::web-1', 'B', 'sess:B:2');
list = Array.from(sessCtx.ratchetSessionsFor(sessCtx.api, 'bob::web-1'));
assert.equal(list.length, 3, 'обновление сессии не создаёт новую запись');
assert.equal(list[0].sessionId, 'B', 'обновлённая стала самой свежей');
assert.equal(list[0].session, 'sess:B:2');

// Новая сессия рядом со старыми — glare не затирает исходящую (сам SEC MED-4).
sessCtx.ratchetSessionPut(sessCtx.api, 'bob::web-1', 'F', 'sess:F:1');
assert.equal(sessCtx.ratchetSessionsFor(sessCtx.api, 'bob::web-1').length, 4);
assert.equal(sessCtx.olmSessions['bob::web-1'].A.s, 'sess:A:1', 'прежняя сессия жива');

// Лимит: поток prekey-конвертов не должен неограниченно раздувать localStorage.
for (let i = 0; i < 8; i++) sessCtx.ratchetSessionPut(sessCtx.api, 'bob::web-1', `X${i}`, `sess:X${i}:1`);
assert.equal(sessCtx.ratchetSessionsFor(sessCtx.api, 'bob::web-1').length, sessCtx.MAX_SESSIONS_PER_PEER);
assert.equal(sessCtx.olmSessions['carol::web-1'].D.s, 'sess:D:1', 'соседний пир не задет');

// NEW-7: TOFU для КЛЮЧА АККАУНТА, которым заворачивается ГРУППОВОЙ ключ.
// Раньше веб брал его из ответа сервера как есть и нигде не пинил: сервер,
// подставив свой ключ в профиль или в состав группы, получал бы групповой ключ
// в подарок. Android этот ключ пинит (KeyTrustStore), iOS тоже — веб был
// единственным, кто не пинил. Проверяем на живом коде из app.js.
const takStart = source.indexOf('function trustedAccountKey');
const takEnd = source.indexOf('\n// Разворачивает encrypted_key_b64', takStart);
assert(takStart >= 0 && takEnd > takStart, 'trustedAccountKey не найдена в app.js');

const KEY_A = Buffer.alloc(32, 1).toString('base64url');
const KEY_B = Buffer.alloc(32, 2).toString('base64url');
const MY_KEY = Buffer.alloc(32, 9).toString('base64url');

function takContext(confirmAnswer) {
    const ctx = {
        accountKeyPins: Object.create(null),
        myId: 'me',
        myKeys: { publicB64: MY_KEY },
        base64UrlDecode: (s) => new Uint8Array(Buffer.from(s, 'base64url')),
        saveRatchetState: () => Promise.resolve(),
        prompted: 0,
        window: {}
    };
    ctx.window.confirm = () => { ctx.prompted++; return confirmAnswer; };
    vm.runInNewContext(
        `${source.slice(takStart, takEnd)}; this.trustedAccountKey = trustedAccountKey;`, ctx);
    return ctx;
}

// Первый контакт пинится молча — доверять иначе не с чего.
let ctx = takContext(false);
assert.equal(ctx.trustedAccountKey('bob', KEY_A, true), KEY_A);
assert.equal(ctx.accountKeyPins['bob'], KEY_A, 'первый контакт запинен');
assert.equal(ctx.prompted, 0, 'первый контакт не спрашивает пользователя');

// Тот же ключ — молча пропускаем.
assert.equal(ctx.trustedAccountKey('BOB', KEY_A, true), KEY_A, 'регистр id не важен');
assert.equal(ctx.prompted, 0);

// Смена ключа при АВТОМАТИЧЕСКОЙ ротации: отказ без модалки, пин не тронут.
ctx = takContext(true);
ctx.accountKeyPins['bob'] = KEY_A;
assert.throws(() => ctx.trustedAccountKey('bob', KEY_B, false), /изменился/);
assert.equal(ctx.prompted, 0, 'автоматическая ротация не имеет права спрашивать');
assert.equal(ctx.accountKeyPins['bob'], KEY_A, 'пин не перезаписан при отказе');

// Смена ключа, пользователь отказался — ключ группы не выдан, пин прежний.
ctx = takContext(false);
ctx.accountKeyPins['bob'] = KEY_A;
let err = null;
try { ctx.trustedAccountKey('bob', KEY_B, true); } catch (e) { err = e; }
assert(err && err.keyTrust === true, 'отказ помечается как тревога доверия');
assert.equal(ctx.prompted, 1);
assert.equal(ctx.accountKeyPins['bob'], KEY_A, 'пин не перезаписан при отказе');

// Смена ключа, пользователь сверил и принял — перепиниваем.
ctx = takContext(true);
ctx.accountKeyPins['bob'] = KEY_A;
assert.equal(ctx.trustedAccountKey('bob', KEY_B, true), KEY_B);
assert.equal(ctx.accountKeyPins['bob'], KEY_B, 'принятый ключ становится новым пином');

// Себе — собственный ключ, сервер не спрашиваем и ничего не пиним.
ctx = takContext(false);
assert.equal(ctx.trustedAccountKey('me', KEY_B, false), MY_KEY, 'для себя берётся свой ключ');
assert.equal(ctx.accountKeyPins['me'], undefined, 'свой ключ не пинится');

// Кривая форма — это испорченный ответ, а не «другой пользователь».
// Пинить его нельзя, иначе мусор закрепится как доверенный.
ctx = takContext(true);
assert.throws(() => ctx.trustedAccountKey('bob', Buffer.alloc(31, 3).toString('base64url'), true), /32 байта/);
assert.equal(ctx.accountKeyPins['bob'], undefined, 'ключ неверной длины не пинится');
assert.throws(() => ctx.trustedAccountKey('bob', '', true), /нет публичного ключа/);
assert.equal(ctx.accountKeyPins['bob'], undefined);

console.log('NEW-7 (TOFU ключа аккаунта): ok');
console.log('security regression checks passed');
