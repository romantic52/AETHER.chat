// Тест wire-адаптера: извлекает toWire/fromWire из app.js и проверяет
// совместимость с каноническим форматом Android (см. WIRE_PROTOCOL.md).
// Запуск: node web/test_wire.js
const fs = require('fs');
const path = require('path');

const src = fs.readFileSync(path.join(__dirname, 'app.js'), 'utf8');
const start = src.indexOf('function mediaDisplayType(');
const end = src.indexOf('// Хелперы для локального шифрования');
if (start < 0 || end < 0 || end < start) {
    console.error('Не нашёл функции toWire/fromWire в app.js');
    process.exit(1);
}
const code = src.slice(start, end);
eval(code); // определяет toWire и fromWire в текущей области

let failed = 0;
function eq(a, b, name) {
    const ja = JSON.stringify(a), jb = JSON.stringify(b);
    if (ja !== jb) { console.error(`✗ ${name}\n   получено: ${ja}\n   ожидалось: ${jb}`); failed++; }
    else console.log(`✓ ${name}`);
}

// 1. text → канон Android
eq(toWire({ type: 'text', content: 'привет' }),
   { type: 'text', text: 'привет' }, 'text → wire');

// 2. text с ответом → канон (reply_to_id + reply_to_text, author отбрасывается)
eq(toWire({ type: 'text', content: 'ок', reply_to: { msg_id: 'm1', author: 'Вы', text: 'исходное' } }),
   { type: 'text', text: 'ок', reply_to_id: 'm1', reply_to_text: 'исходное' }, 'reply → wire');

// 3. edit → канон
eq(toWire({ type: 'edit', target_id: 'm2', content: 'новый' }),
   { type: 'edit', target: 'm2', text: 'новый' }, 'edit → wire');

// 4. reaction → канон
eq(toWire({ type: 'reaction', target_id: 'm3', emoji: '👍' }),
   { type: 'reaction', target: 'm3', emoji: '👍' }, 'reaction → wire');

// 5. delete → канон
eq(toWire({ type: 'delete', target_id: 'm5' }),
   { type: 'delete', target: 'm5' }, 'delete → wire');

// 6. read_receipt → канонический read
eq(toWire({ type: 'read_receipt', target_id: 'm4' }),
   { type: 'read' }, 'read_receipt → read');

// 7. веб-родной тип проходит насквозь
eq(toWire({ type: 'image', content: 'data:...' }),
   { type: 'image', content: 'data:...' }, 'image passthrough');

// 8. Приём канонического Android-text
eq(fromWire({ type: 'text', text: 'hi from android', reply_to_id: 'x', reply_to_text: 'orig' }),
   { type: 'text', content: 'hi from android', reply_to: { msg_id: 'x', text: 'orig', author: '' } },
   'android text → web');

// 9. Приём канонического edit/delete/reaction
eq(fromWire({ type: 'edit', target: 'y', text: 'upd' }),
   { type: 'edit', target_id: 'y', content: 'upd' }, 'android edit → web');
eq(fromWire({ type: 'delete', target: 'd' }),
   { type: 'delete', target_id: 'd' }, 'android delete → web');
eq(fromWire({ type: 'reaction', target: 'z', emoji: '❤️' }),
   { type: 'reaction', target_id: 'z', emoji: '❤️' }, 'android reaction → web');

// 9. read проходит как есть (обрабатывается отдельной логикой; media — см. тест 13)
eq(fromWire({ type: 'read' }), { type: 'read' }, 'read passthrough');

// 10. Роундтрип text (важно для self-echo мультидевайса)
const rt = { type: 'text', content: 'roundtrip' };
eq(fromWire(toWire(rt)), rt, 'roundtrip text exact');

// 11. fwd_from сохраняется в обе стороны
eq(toWire({ type: 'text', content: 'fwd', fwd_from: 'alice' }),
   { type: 'text', text: 'fwd', fwd_from: 'alice' }, 'fwd_from → wire');

// 12. media: внутренний (image+.media) → канон Android
eq(toWire({ type: 'image', media: { file_id: 'F', sym_key: 'K', nonce: 'N', mime_type: 'image/jpeg', kind: 'image' }, filename: 'p.jpg', size: 123 }),
   { type: 'media', file_id: 'F', sym_key: 'K', mime_type: 'image/jpeg', nonce: 'N', kind: 'image', filename: 'p.jpg', size: 123 },
   'media → wire');

// 13. media: канон Android → внутренний дисплейный тип
eq(fromWire({ type: 'media', file_id: 'F', sym_key: 'K', nonce: 'N', mime_type: 'image/jpeg', kind: 'image' }),
   { type: 'image', media: { file_id: 'F', sym_key: 'K', nonce: 'N', mime_type: 'image/jpeg', kind: 'image' } },
   'wire media → web image');

// 14. media voice/video_msg/file распознаются по kind
eq(fromWire({ type: 'media', file_id: 'F', sym_key: 'K', nonce: 'N', mime_type: 'audio/webm', kind: 'voice' }).type,
   'voice', 'wire media voice kind');
eq(fromWire({ type: 'media', file_id: 'F', sym_key: 'K', nonce: 'N', mime_type: 'application/pdf', kind: 'file' }).type,
   'file', 'wire media file kind');

// 15. Роундтрип media (image) сохраняет media-поля
const m = { type: 'image', media: { file_id: 'F', sym_key: 'K', nonce: 'N', mime_type: 'image/jpeg', kind: 'image' } };
eq(fromWire(toWire(m)), m, 'roundtrip media exact');

// 16. Реальный AES-GCM роундтрип (формат файла идентичен Android E2ECrypto.encryptFile):
//     ключ 32б, IV 12б отдельно, тег 128б в конце.
(async () => {
    const { webcrypto } = require('crypto');
    const subtle = webcrypto.subtle;
    const plain = Buffer.from('секретный файл 📎', 'utf8');
    const rawKey = webcrypto.getRandomValues(new Uint8Array(32));
    const iv = webcrypto.getRandomValues(new Uint8Array(12));
    const key = await subtle.importKey('raw', rawKey, { name: 'AES-GCM' }, false, ['encrypt', 'decrypt']);
    const ct = new Uint8Array(await subtle.encrypt({ name: 'AES-GCM', iv }, key, plain));
    // тег 128 бит (16 байт) в конце — как Java AES/GCM/NoPadding
    if (ct.length !== plain.length + 16) { console.error('✗ AES-GCM длина тега не 16б'); failed++; }
    else console.log('✓ AES-GCM тег 16б в конце (совместимо с Android)');
    const dec = Buffer.from(await subtle.decrypt({ name: 'AES-GCM', iv }, key, ct));
    eq(dec.toString('utf8'), plain.toString('utf8'), 'AES-GCM file roundtrip');

    console.log(failed === 0 ? '\nВСЕ ТЕСТЫ ПРОЙДЕНЫ' : `\n${failed} ТЕСТ(ОВ) УПАЛО`);
    process.exit(failed === 0 ? 0 : 1);
})();
