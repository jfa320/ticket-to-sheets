import test from 'node:test';
import assert from 'node:assert/strict';
import {createSheetsApi, createSheetsHistory, historyStatusText} from '../../main/resources/static/receipt-sheets.mjs';

const destination = {spreadsheetId: 'synthetic_spreadsheet_id_1234567890', sheetName: 'Mes prueba', headerRow: 2};
const state = (active = true) => ({active, spreadsheetId: destination.spreadsheetId, updatedAt: '2026-10-04T12:00:00Z',
    rowCount: 38, productCount: 12, sheets: ['Agosto', 'Septiembre'], skippedSheets: ['Resumen'], message: ''});

test('history API pins the destination and sends no receipts or credentials', async () => {
    const calls = [];
    const api = createSheetsApi(async (url, options) => {
        calls.push({url, options}); return {ok: true, json: async () => state()};
    });
    await api.getHistory();
    await api.syncHistory({...destination, serviceAccountEmail: 'synthetic@invalid', items: [{private: true}]});
    await api.removeHistory();
    assert.deepEqual(calls.map(call => [call.url, call.options.method]), [
        ['/api/sheets/history', 'GET'], ['/api/sheets/history/sync', 'POST'], ['/api/sheets/history', 'DELETE']
    ]);
    assert.deepEqual(JSON.parse(calls[1].options.body), destination);
});

test('failed refresh preserves the previously confirmed history', async () => {
    let fail = false;
    const history = createSheetsHistory({api: {getHistory: async () => state(), syncHistory: async () => {
        if (fail) throw new Error('Sin conexión'); return {...state(), productCount: 14};
    }, removeHistory: async () => state(false)}});
    await history.load();
    fail = true;
    await assert.rejects(history.sync(destination), /Sin conexión/);
    assert.equal(history.current.active, true);
    assert.equal(history.current.productCount, 12);
    fail = false;
    await history.sync(destination);
    assert.equal(history.current.productCount, 14);
    await history.remove();
    assert.equal(history.current.active, false);
});

test('concurrent sync or removal cannot race a pending history operation', async () => {
    let complete;
    const history = createSheetsHistory({api: {syncHistory: () => new Promise(resolve => {complete = resolve;})}});
    const pending = history.sync(destination);
    assert.equal(history.busy, true);
    await assert.rejects(history.sync(destination), /termine/);
    await assert.rejects(history.remove(), /termine/);
    complete(state()); await pending;
    assert.equal(history.busy, false);
});

test('invalid successful response does not replace known history', async () => {
    const history = createSheetsHistory({api: {getHistory: async () => state(), syncHistory: async () => ({active: true})}});
    await history.load();
    await assert.rejects(history.sync(destination), /inválido/);
    assert.equal(history.current.productCount, 12);
    assert.equal(history.busy, false);
});

test('status explains counts, source tabs and skipped tabs without showing transactions', () => {
    const text = historyStatusText(state());
    assert.match(text, /12 productos de 38 filas/);
    assert.match(text, /Agosto, Septiembre/);
    assert.match(text, /Resumen/);
    assert.match(historyStatusText({...state(false), message: 'El catálogo pertenece a otra planilla.'}), /otra planilla/);
});
