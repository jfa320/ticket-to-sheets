import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash, webcrypto} from 'node:crypto';
import {createUuid, sha256Hex} from '../../main/resources/static/browser-crypto.mjs';

test('UUID generation uses the native method when available', () => {
    const crypto = {randomUUID() { assert.equal(this, crypto); return 'native-uuid'; }};
    assert.equal(createUuid(crypto), 'native-uuid');
});

test('HTTP UUID fallback preserves random bytes and sets version 4 and the RFC variant', () => {
    assert.equal(createUuid({getRandomValues: bytes => bytes.fill(0xff)}), 'ffffffff-ffff-4fff-bfff-ffffffffffff');
    assert.equal(createUuid({getRandomValues: bytes => bytes.fill(0)}), '00000000-0000-4000-8000-000000000000');
    const ids = Array.from({length: 1000}, () => createUuid({getRandomValues: bytes => webcrypto.getRandomValues(bytes)}));
    assert.equal(new Set(ids).size, ids.length);
});

test('UUID generation fails clearly if secure random bytes are unavailable', () => {
    assert.throws(() => createUuid({}), /identificadores seguros/);
    assert.throws(() => createUuid(null), /identificadores seguros/);
});

test('native and HTTP SHA-256 match known vectors', async () => {
    const vectors = [
        ['', 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'],
        ['abc', 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'],
        ['a'.repeat(1000000), 'cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0']
    ];
    for (const [text, expected] of vectors) {
        const bytes = new TextEncoder().encode(text);
        assert.equal(await sha256Hex(bytes, webcrypto), expected);
        assert.equal(await sha256Hex(bytes, {}), expected);
    }
});

test('HTTP SHA-256 matches Node for padding boundaries, Unicode and offset byte views', async () => {
    const cases = [new TextEncoder().encode('Descripción, café, azúcar, 🧾'),
        ...[1, 55, 56, 63, 64, 65, 119, 120, 127, 128, 129, 4096].map(length => {
            const bytes = new Uint8Array(length);
            return webcrypto.getRandomValues(bytes);
        })];
    for (const bytes of cases) {
        const expected = createHash('sha256').update(bytes).digest('hex');
        assert.equal(await sha256Hex(bytes, {}), expected);
        const buffer = new Uint8Array(bytes.length + 10);
        buffer.set(bytes, 5);
        assert.equal(await sha256Hex(buffer.subarray(5, -5), {}), expected);
    }
});
