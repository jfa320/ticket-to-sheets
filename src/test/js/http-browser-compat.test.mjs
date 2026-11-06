import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash, webcrypto} from 'node:crypto';
import {createReceiptExtraction} from '../../main/resources/static/receipt-extraction.mjs';
import {createSheetsTransfer} from '../../main/resources/static/receipt-sheets.mjs';
import {createRecoverableSheetsAppend} from '../../main/resources/static/sheets-transfer-journal.mjs';

const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/;
const config = {spreadsheetId: 'synthetic_spreadsheet_id_1234567890', sheetName: 'Noviembre', headerRow: 2};
const items = [{descripcion: 'Producto ficticio', marca: 'Genérico', lugarDeCompra: 'Comercio ficticio',
    categoria: 'Otros', cantidad: '1', precioUnitario: '1.500,50', fecha: '3/11/2026'}];
const payload = requestId => ({requestId, destination: config, items});
const confirmation = {appendedRows: 1, duplicate: true, sheetName: config.sheetName};

function useHttpCrypto(t) {
    const original = Object.getOwnPropertyDescriptor(globalThis, 'crypto');
    Object.defineProperty(globalThis, 'crypto', {configurable: true,
        value: {getRandomValues: bytes => webcrypto.getRandomValues(bytes)}});
    t.after(() => Object.defineProperty(globalThis, 'crypto', original));
}

function sessionStorage() {
    const values = new Map();
    return {getItem: key => values.get(key), setItem: (key, value) => values.set(key, value),
        removeItem: key => values.delete(key), serialized: () => [...values.values()].join('')};
}

test('extraction generates distinct UUIDs over LAN HTTP without randomUUID', async t => {
    useHttpCrypto(t);
    const ids = [];
    const extraction = createReceiptExtraction({pageWindow: null,
        extract: async (file, options) => { ids.push(options.extractionId); return {items: []}; }});
    await extraction.extract('synthetic-file');
    await extraction.extract('synthetic-file');
    assert.equal(ids.length, 2);
    ids.forEach(id => assert.match(id, uuid));
    assert.notEqual(ids[0], ids[1]);
});

test('Sheets generates a UUID over LAN HTTP and reuses it after a lost response', async t => {
    useHttpCrypto(t);
    const sent = [];
    const transfer = createSheetsTransfer({append: async request => {
        sent.push(request);
        if (sent.length === 1) throw new Error('respuesta perdida');
        return confirmation;
    }});
    await assert.rejects(transfer.send(config, items), /perdida/);
    assert.deepEqual(await transfer.send(config, items), confirmation);
    assert.match(sent[0].requestId, uuid);
    assert.equal(sent[1].requestId, sent[0].requestId);
});

test('HTTP recovery matches an existing native SHA-256 journal without another write', async t => {
    const storage = sessionStorage();
    await assert.rejects(createRecoverableSheetsAppend({storage,
        append: async () => { throw new Error('respuesta perdida'); }})(payload('original-id')), /perdida/);
    const entry = JSON.parse(storage.serialized());
    const expected = createHash('sha256').update(JSON.stringify({destination: config, items})).digest('hex');
    assert.equal(entry.fingerprint, expected);
    assert.doesNotMatch(storage.serialized(), /Producto ficticio|cantidad|precioUnitario/);

    useHttpCrypto(t);
    const retry = createRecoverableSheetsAppend({storage,
        lookup: async request => {
            assert.equal(request.requestId, 'original-id');
            return {committed: true, result: confirmation};
        }, append: async () => assert.fail('Una recuperación confirmada no debe escribir otra vez.')});
    assert.deepEqual(await retry(payload('new-id')), confirmation);
    assert.equal(storage.serialized(), '');
});

test('HTTP journal retains UUID and blocks uncertain or edited retries without storing products', async t => {
    useHttpCrypto(t);
    const storage = sessionStorage();
    await assert.rejects(createRecoverableSheetsAppend({storage,
        append: async () => { throw new Error('respuesta perdida'); }})(payload('original-id')), /perdida/);
    const entry = JSON.parse(storage.serialized());
    const expected = createHash('sha256').update(JSON.stringify({destination: config, items})).digest('hex');
    assert.equal(entry.fingerprint, expected);
    assert.doesNotMatch(storage.serialized(), /Producto ficticio|cantidad|precioUnitario/);
    let writes = 0;
    const append = async request => { writes++; assert.equal(request.requestId, 'original-id'); return confirmation; };
    await assert.rejects(createRecoverableSheetsAppend({storage, append,
        lookup: async () => { throw new Error('sin conexión'); }})(payload('new-id')), /conexión/);
    const retry = createRecoverableSheetsAppend({storage, append, lookup: async () => ({committed: false})});
    await assert.rejects(retry({...payload('new-id'), items: [{...items[0], precioUnitario: '2.000'}]}), /Restablecé/);
    assert.equal(writes, 0);
    assert.equal(JSON.parse(storage.serialized()).requestId, 'original-id');
    assert.deepEqual(await retry(payload('new-id')), confirmation);
    assert.equal(writes, 1);
    assert.equal(storage.serialized(), '');
});
