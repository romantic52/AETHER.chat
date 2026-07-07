// Кросс-проверка ядра Rust ↔ протокол клиентов.
//  - box: tweetnacl (та же либа, что в web/app.js)
//  - aes: WebCrypto (как в web)
//  - backup: node crypto в "Java-стиле" (тег в конце) — как Android E2ECrypto
// Использование:
//   node cross_test.mjs verify <rust_emit.json>   — проверить векторы Rust
//   node cross_test.mjs emit   <node_emit.json>   — сделать векторы для Rust
import { webcrypto as wc } from 'crypto';
import crypto from 'crypto';
import fs from 'fs';

// --- base64 url-safe (как b64e/b64d в ядре) ---
const b64e = u8 => Buffer.from(u8).toString('base64').replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');
const b64d = s => new Uint8Array(Buffer.from(s.replace(/-/g,'+').replace(/_/g,'/'), 'base64'));

// --- загрузка tweetnacl с того же CDN, что в web/index.html ---
async function loadNacl() {
  const code = await (await fetch('https://cdnjs.cloudflare.com/ajax/libs/tweetnacl/1.0.3/nacl.min.js')).text();
  const m = { exports: {} };
  new Function('module', 'exports', code)(m, m.exports);
  const nacl = m.exports;
  // в песочнице new Function автоопределение PRNG не сработало — задаём вручную
  nacl.setPRNG((x, n) => { x.set(crypto.randomBytes(n)); });
  return nacl;
}

// --- WebCrypto AES-256-GCM (как в web) ---
async function aesEnc(keyU8, ptU8) {
  const k = await wc.subtle.importKey('raw', keyU8, { name:'AES-GCM' }, false, ['encrypt']);
  const iv = wc.getRandomValues(new Uint8Array(12));
  const ct = await wc.subtle.encrypt({ name:'AES-GCM', iv }, k, ptU8);
  return { nonce: b64e(iv), ct: b64e(new Uint8Array(ct)) };
}
async function aesDec(keyU8, ivU8, ctU8) {
  const k = await wc.subtle.importKey('raw', keyU8, { name:'AES-GCM' }, false, ['decrypt']);
  return new Uint8Array(await wc.subtle.decrypt({ name:'AES-GCM', iv: ivU8 }, k, ctU8));
}

// --- backup в Java-стиле (PBKDF2-SHA256 100k + AES-GCM, тег в конце), формат salt:iv:ct ---
function backupEnc(privB64, pw) {
  const salt = crypto.randomBytes(16), iv = crypto.randomBytes(12);
  const key = crypto.pbkdf2Sync(pw, salt, 100000, 32, 'sha256');
  const c = crypto.createCipheriv('aes-256-gcm', key, iv);
  const enc = Buffer.concat([c.update(Buffer.from(privB64,'utf8')), c.final()]);
  const out = Buffer.concat([enc, c.getAuthTag()]);
  return `${b64e(salt)}:${b64e(iv)}:${b64e(out)}`;
}
function backupDec(blob, pw) {
  const [s,i,c] = blob.split(':');
  const salt = Buffer.from(b64d(s)), iv = Buffer.from(b64d(i)), full = Buffer.from(b64d(c));
  const enc = full.subarray(0, full.length-16), tag = full.subarray(full.length-16);
  const key = crypto.pbkdf2Sync(pw, salt, 100000, 32, 'sha256');
  const d = crypto.createDecipheriv('aes-256-gcm', key, iv);
  d.setAuthTag(tag);
  return Buffer.concat([d.update(enc), d.final()]).toString('utf8');
}

const [,, mode, path] = process.argv;
const nacl = await loadNacl();

if (mode === 'verify') {
  const j = JSON.parse(fs.readFileSync(path, 'utf8'));
  let ok = true;
  // box: открываем Rust-конверт tweetnacl'ом
  const opened = nacl.box.open(b64d(j.box.ct), b64d(j.box.nonce), b64d(j.box.sender_pub), b64d(j.box.recipient_private));
  const boxPt = opened ? new TextDecoder().decode(opened) : null;
  const boxOk = boxPt === j.box.expect; ok &&= boxOk;
  console.log(`box   : ${boxOk?'OK':'FAIL'}  (${boxPt})`);
  // aes
  const aesPt = new TextDecoder().decode(await aesDec(b64d(j.aes.key), b64d(j.aes.nonce), b64d(j.aes.ct)));
  const aesOk = aesPt === j.aes.expect; ok &&= aesOk;
  console.log(`aes   : ${aesOk?'OK':'FAIL'}  (${aesPt})`);
  // backup
  const bkPt = backupDec(j.backup.blob, j.backup.password);
  const bkOk = bkPt === j.backup.expect; ok &&= bkOk;
  console.log(`backup: ${bkOk?'OK':'FAIL'}`);
  console.log(ok ? 'ALL OK' : 'SOME FAILED');
  process.exit(ok ? 0 : 1);
}

if (mode === 'emit') {
  const recip = nacl.box.keyPair(), sender = nacl.box.keyPair();
  const msg = 'node 🔁 rust';
  const nonce = wc.getRandomValues(new Uint8Array(24));
  const ct = nacl.box(new TextEncoder().encode(msg), nonce, recip.publicKey, sender.secretKey);
  const aesKey = wc.getRandomValues(new Uint8Array(32));
  const aes = await aesEnc(aesKey, new TextEncoder().encode('aes node→rust'));
  const fakePriv = b64e(wc.getRandomValues(new Uint8Array(32)));
  const blob = backupEnc(fakePriv, 'pw-xyz789');
  const out = {
    box:    { recipient_private: b64e(recip.secretKey), sender_pub: b64e(sender.publicKey), nonce: b64e(nonce), ct: b64e(ct), expect: msg },
    aes:    { key: b64e(aesKey), nonce: aes.nonce, ct: aes.ct, expect: 'aes node→rust' },
    backup: { blob, password: 'pw-xyz789', expect: fakePriv }
  };
  fs.writeFileSync(path, JSON.stringify(out));
  console.log('emit ->', path);
}
