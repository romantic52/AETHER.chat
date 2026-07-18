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
assert.match(source, /envelope = await ratchetEnvelopeFor\(targetPeer, wireJson\)/);
assert.match(source, /ratchet: ['"]1['"]/);
assert.match(source, /client_id: clientId/);
assert.match(source, /ackThis = decrypted/);
assert.match(source, /Ignoring non-Ratchet direct message/);
assert.doesNotMatch(source, /nacl\.box\(textBytes/);
assert.match(server, /Direct messages require Olm Double Ratchet/);
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

console.log('security regression checks passed');
