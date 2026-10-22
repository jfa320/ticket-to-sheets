import test from 'node:test';
import assert from 'node:assert/strict';
import {createRecoverableSheetsAppend} from '../../main/resources/static/sheets-transfer-journal.mjs';

const payload = requestId => ({requestId, configVersion: 'v1',
    destination: {spreadsheetId: 'synthetic_sheet_id_123456', sheetName: 'Octubre', headerRow: 2},
    items: [{descripcion: 'Texto privado de prueba', cantidad: '1', precioUnitario: '1000,00'}]});
function storage() {
    const values = new Map();
    return {getItem: key => values.get(key), setItem: (key, value) => values.set(key, value),
        removeItem: key => values.delete(key), serialized: () => [...values.values()].join('')};
}
const confirmation = {appendedRows: 1, duplicate: true, sheetName: 'Octubre', updatedRange: 'Octubre!A3:H3'};

test('reload recovers a committed upload after a lost response without a second write or stored rows', async () => {
    const session = storage();
    let writes = 0;
    const first = createRecoverableSheetsAppend({storage: session, append: async () => {
        writes++; throw new Error('respuesta perdida');
    }});
    await assert.rejects(first(payload('original-id')), /perdida/);
    assert.doesNotMatch(session.serialized(), /Texto privado|cantidad|precioUnitario|1000/);
    const restored = createRecoverableSheetsAppend({storage: session,
        lookup: async request => { assert.equal(request.requestId, 'original-id'); return {committed: true, result: confirmation}; },
        append: async () => { writes++; }});
    assert.deepEqual(await restored(payload('new-page-id')), confirmation);
    assert.equal(writes, 1);
    assert.equal(session.serialized(), '');
});

test('uncommitted retry after reload reuses the original UUID', async () => {
    const session = storage();
    await assert.rejects(createRecoverableSheetsAppend({storage: session,
        append: async () => { throw new Error('offline'); }})(payload('original-id')));
    const retry = createRecoverableSheetsAppend({storage: session, lookup: async () => ({committed: false, result: null}),
        append: async request => { assert.equal(request.requestId, 'original-id'); return confirmation; }});
    assert.deepEqual(await retry(payload('new-id')), confirmation);
});

test('unavailable recovery and changed rows retain the pending attempt without sending another upload', async () => {
    const session = storage();
    await assert.rejects(createRecoverableSheetsAppend({storage: session,
        append: async () => { throw new Error('offline'); }})(payload('original-id')));
    let writes = 0;
    const retry = createRecoverableSheetsAppend({storage: session, lookup: async () => ({committed: false}),
        append: async () => { writes++; }});
    const edited = payload('new-id'); edited.items[0].precioUnitario = '2000,00';
    await assert.rejects(retry(edited), /Restablecé/);
    assert.equal(writes, 0);
    assert.match(session.serialized(), /original-id/);
    const unavailable = createRecoverableSheetsAppend({storage: session,
        lookup: async () => { throw new Error('sin conexión'); }, append: async () => { writes++; }});
    await assert.rejects(unavailable(payload('new-id')), /conexión/);
    assert.equal(writes, 0);
});

test('a confirmed previous upload with different edits requires review before a new write', async () => {
    const session = storage();
    await assert.rejects(createRecoverableSheetsAppend({storage: session,
        append: async () => { throw new Error('offline'); }})(payload('original-id')));
    let writes = 0;
    const retry = createRecoverableSheetsAppend({storage: session, lookup: async () => ({committed: true, result: confirmation}),
        append: async () => { writes++; return confirmation; }});
    const edited = payload('new-id'); edited.items[0].precioUnitario = '2000,00';
    await assert.rejects(retry(edited), /ya está guardada/);
    assert.equal(writes, 0);
    await retry(edited);
    assert.equal(writes, 1);
});

test('unavailable or corrupt session storage fails before sending rows', async () => {
    let writes = 0;
    const append = async () => { writes++; };
    await assert.rejects(createRecoverableSheetsAppend({storage: null, append})(payload('one')), /almacenamiento/);
    const corrupt = storage(); corrupt.setItem('facturas.pendingSheetsTransfer', '{broken');
    await assert.rejects(createRecoverableSheetsAppend({storage: corrupt, append})(payload('one')), /recuperar/);
    const full = {getItem: () => null, setItem() { throw new Error('quota'); }};
    await assert.rejects(createRecoverableSheetsAppend({storage: full, append})(payload('one')), /No se enviaron/);
    assert.equal(writes, 0);
});

test('a mismatched recovered row count preserves the journal and blocks another write', async () => {
    const session = storage();
    await assert.rejects(createRecoverableSheetsAppend({storage: session,
        append: async () => { throw new Error('offline'); }})(payload('original-id')));
    let writes = 0;
    const retry = createRecoverableSheetsAppend({storage: session,
        lookup: async () => ({committed: true, result: {...confirmation, appendedRows: 2}}),
        append: async () => { writes++; }});
    await assert.rejects(retry(payload('new-id')), /cantidad de filas/);
    assert.equal(writes, 0);
    assert.match(session.serialized(), /original-id/);
});
