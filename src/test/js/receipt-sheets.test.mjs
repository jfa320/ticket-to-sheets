import test from 'node:test';
import assert from 'node:assert/strict';
import {spreadsheetIdFromInput, sheetsConfigErrors, buildSheetsItems, createSheetsTransfer, createSheetsApi} from '../../main/resources/static/receipt-sheets.mjs';

const config = {spreadsheetId: 'synthetic_spreadsheet_id_1234567890', sheetName: 'Septiembre', headerRow: 2};
const item = () => ({descripcion: 'Producto corregido', marca: 'Marca', lugarDeCompra: 'Comercio', categoria: 'Otros',
    cantidad: '0.875', precioUnitario: '1.500,50', fecha: '4/10/2026', usar: true, firma: 'texto privado OCR', estado: 'LEARNED'});

test('configuration accepts Google Sheet URLs and rejects foreign hosts', () => {
    assert.equal(spreadsheetIdFromInput(`https://docs.google.com/spreadsheets/d/${config.spreadsheetId}/edit#gid=1`), config.spreadsheetId);
    for (const url of ['https://evil.example/spreadsheets/d/abc', 'https://docs.google.com.evil.example/spreadsheets/d/abc',
        `https://user@docs.google.com/spreadsheets/d/${config.spreadsheetId}`, 'short']) assert.equal(spreadsheetIdFromInput(url), '');
    assert.deepEqual(sheetsConfigErrors({...config, spreadsheetUrlOrId: config.spreadsheetId}), []);
    assert.equal(sheetsConfigErrors({spreadsheetUrlOrId: '', sheetName: '', headerRow: 0}).length, 3);
});

test('payload contains selected edited rows and seven export fields only', () => {
    const excluded = {...item(), usar: false, descripcion: '', precioUnitario: 'inválido'};
    const rows = buildSheetsItems([item(), excluded]);
    assert.equal(rows.length, 1);
    assert.equal(rows[0].descripcion, 'Producto corregido');
    assert.equal(rows[0].cantidad, '0.875');
    assert.equal(rows[0].precioUnitario, '1.500,50');
    assert.equal(rows[0].fecha, '4/10/2026');
    assert.equal(Object.keys(rows[0]).length, 7);
    assert.equal(rows[0].firma, undefined);
    assert.throws(() => buildSheetsItems([{...item(), fecha: '31/2/2026'}]), /fecha/);
    assert.throws(() => buildSheetsItems([{...item(), cantidad: '-1'}]), /cantidad/);
    assert.throws(() => buildSheetsItems([excluded]), /al menos/);
});

test('double click shares request and retries preserve UUID and destination', async () => {
    let resolve;
    const sent = [];
    const transfer = createSheetsTransfer({newRequestId: () => 'request-one', append: payload => {
        sent.push(payload); return new Promise(done => { resolve = done; });
    }});
    const first = transfer.send(config, [item()]);
    const second = transfer.send(config, [item()]);
    assert.equal(first, second);
    await Promise.resolve();
    assert.equal(sent.length, 1);
    assert.deepEqual(sent[0].destination, config);
    resolve({appendedRows: 1});
    await first;
    await transfer.send(config, [item()]);
    assert.equal(sent.length, 1);
});

test('failed request reuses UUID; changed edits or destination create a new operation', async () => {
    let sequence = 0;
    const sent = [];
    const transfer = createSheetsTransfer({newRequestId: () => `request-${++sequence}`, append: async payload => {
        sent.push(payload); if (sent.length === 1) throw new Error('respuesta perdida'); return {appendedRows: 1};
    }});
    await assert.rejects(transfer.send(config, [item()]), /perdida/);
    assert.equal(transfer.hasSuccess, false);
    assert.equal(transfer.hasUnconfirmed, true);
    assert.equal(transfer.state(config, [{...item(), precioUnitario: '2.000'}]).status, 'ready');
    await transfer.send(config, [item()]);
    assert.equal(transfer.hasUnconfirmed, false);
    assert.equal(sent[0].requestId, sent[1].requestId);
    await transfer.send(config, [{...item(), descripcion: 'Nueva descripción'}]);
    assert.notEqual(sent[2].requestId, sent[1].requestId);
    await transfer.send({...config, sheetName: 'Octubre'}, [item()]);
    assert.notEqual(sent[3].requestId, sent[1].requestId);
});

test('pending transfer cannot reset or switch snapshots', async () => {
    let resolve;
    const transfer = createSheetsTransfer({append: () => new Promise(done => { resolve = done; }), newRequestId: () => 'one'});
    const pending = transfer.send(config, [item()]);
    assert.throws(() => transfer.reset(), /termine/);
    await assert.rejects(transfer.send({...config, sheetName: 'Octubre'}, [item()]), /termine/);
    resolve({appendedRows: 1}); await pending;
    transfer.reset(); assert.equal(transfer.hasSuccess, false);
});

test('API uses local backend only and rejects unconfirmed results', async () => {
    const calls = [];
    const api = createSheetsApi(async (...args) => {
        calls.push(args); return {ok: true, json: async () => ({appendedRows: 1, duplicate: false, sheetName: config.sheetName,
            updatedRange: 'Septiembre!A3:H3', spreadsheetUrl: `https://docs.google.com/spreadsheets/d/${config.spreadsheetId}/edit`})};
    });
    await api.append({requestId: 'one', items: buildSheetsItems([item()]), destination: config});
    assert.equal(calls[0][0], '/api/sheets/append');
    assert.equal(calls[0][1].method, 'POST');
    assert.equal(calls[0][1].headers['Content-Type'], 'application/json');
    const errorApi = createSheetsApi(async () => ({ok: false, json: async () => ({message: 'Falta permiso'})}));
    await assert.rejects(errorApi.check(), /Falta permiso/);
    const invalidApi = createSheetsApi(async () => ({ok: true, json: async () => ({appendedRows: 0})}));
    await assert.rejects(invalidApi.append({items: [item()]}), /confirmación inválida/);
});
