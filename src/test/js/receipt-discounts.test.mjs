import test from 'node:test';
import assert from 'node:assert/strict';
import {createReceipt, calculateSummary, buildExportItems, buildExports, discountValidationErrors} from '../../main/resources/static/receipt-model.mjs';
import {buildSheetsItems, createSheetsTransfer} from '../../main/resources/static/receipt-sheets.mjs';

const items = [8800, 8800, 10833.33, 10833.33, 8700, 4125].map(price => ({
    descripcion: 'Producto', marca: 'Marca', lugarDeCompra: 'Pigmento', categoria: 'Perfumeria',
    cantidad: '1', precioUnitario: String(price), fecha: '5/10/2026'
}));
const discounts = [{concepto: 'Bonificación 2x1', importe: '-8800,00'},
    {concepto: 'Bonificación Oferta', importe: '-9491,66'}];

test('six gross product prices and two discounts calculate the receipt net total', () => {
    const receipt = createReceipt({items, discounts, storeName: 'Perfumerias Pigmento', date: '5/10/2026', total: '999999,00'});
    assert.deepEqual(calculateSummary(receipt.items, receipt.discounts),
        {subtotal: 52091.66, discountTotal: -18291.66, total: 33800});
    const exports = buildExports(receipt.items, receipt.discounts, receipt);
    assert.equal(exports.count, 8);
    assert.equal(exports.total, 33800);
    const rows = exports.rowsOnly.split('\n');
    assert.equal(rows.length, 8);
    assert.deepEqual(rows[6].split('\t'), ['Descuento', 'Genérico', 'Perfumerias Pigmento', 'Perfumeria', '1', '-8800,00', '5/10/2026']);
    assert.deepEqual(rows[7].split('\t'), ['Descuento', 'Genérico', 'Perfumerias Pigmento', 'Perfumeria', '1', '-9491,66', '5/10/2026']);
    assert.doesNotMatch(exports.tsv, /Bonificación/);
    assert.equal(exports.rowsOnly.split('\n')[0].split('\t').length, 7);
});

test('direct Sheets transfer sends negative discount rows with only seven columns', async () => {
    const receipt = createReceipt({items, discounts, storeName: 'Perfumerias Pigmento', date: '5/10/2026'});
    const sent = [];
    const transfer = createSheetsTransfer({newRequestId: () => 'test-discounts', append: async payload => {
        sent.push(payload); return {appendedRows: payload.items.length};
    }});
    const exported = buildExportItems(receipt.items, receipt.discounts, receipt);
    const config = {spreadsheetId: 'synthetic_spreadsheet_1234567890', sheetName: 'Octubre', headerRow: 2};
    await transfer.send(config, exported);
    assert.equal(sent[0].items.length, 8);
    assert.equal(sent[0].items[6].precioUnitario, '-8800,00');
    assert.equal(sent[0].items[7].precioUnitario, '-9491,66');
    assert.equal(sent[0].items[6].cantidad, '1');
    assert.equal(sent[0].items[6].fecha, '5/10/2026');
    assert.equal(sent[0].items[6].categoria, 'Perfumeria');
    assert.equal(sent[0].items[6].descripcion, 'Descuento');
    assert.equal(Object.keys(sent[0].items[6]).length, 7);
    await transfer.send(config, exported);
    assert.equal(sent.length, 1, 'el mismo snapshot no duplica bonificaciones');
});

test('excluded or edited discounts and common metadata reach both exports identically', () => {
    const receipt = createReceipt({items, discounts, storeName: 'Comercio corregido', date: '6/10/2026'});
    receipt.discounts[0].usar = false;
    receipt.discounts[1].importe = '-9000,00';
    const exported = buildExportItems(receipt.items, receipt.discounts, receipt);
    const sheetsRows = buildSheetsItems(exported);
    const output = buildExports(receipt.items, receipt.discounts, receipt);
    assert.equal(sheetsRows.length, 7);
    assert.equal(output.count, 7);
    assert.equal(output.total, calculateSummary(receipt.items, receipt.discounts).total);
    assert.deepEqual(output.rowsOnly.split('\n').at(-1).split('\t'), Object.values(sheetsRows.at(-1)));
    assert.equal(sheetsRows.at(-1).lugarDeCompra, 'Comercio corregido');
    assert.equal(sheetsRows.at(-1).fecha, '6/10/2026');
    assert.equal(sheetsRows.at(-1).precioUnitario, '-9000,00');
    receipt.items.length = 0;
    const onlyDiscount = buildSheetsItems(buildExportItems(receipt.items, receipt.discounts, receipt))[0];
    assert.equal(onlyDiscount.categoria, 'Perfumeria', 'conserva la categoría aunque se eliminen los productos');
});

test('invalid selected discount blocks direct Sheets transfer instead of adding a positive row', () => {
    const receipt = createReceipt({items, discounts});
    receipt.discounts[0].importe = '8800,00';
    assert.throws(() => buildSheetsItems(buildExportItems(receipt.items, receipt.discounts)), /negativo/);
    receipt.discounts[0].usar = false;
    assert.equal(buildSheetsItems(buildExportItems(receipt.items, receipt.discounts)).length, 7);
});

test('item edits, exclusion, deletion and discount edits recalculate together', () => {
    const receipt = createReceipt({items, discounts});
    receipt.items[0].cantidad = '2';
    assert.equal(calculateSummary(receipt.items, receipt.discounts).total, 42600);
    receipt.items[0].usar = false;
    assert.equal(calculateSummary(receipt.items, receipt.discounts).total, 25000);
    receipt.discounts[0].usar = false;
    assert.equal(calculateSummary(receipt.items, receipt.discounts).total, 33800);
    receipt.items.splice(0, 1);
    receipt.discounts[1].importe = '-9.000,00';
    assert.equal(calculateSummary(receipt.items, receipt.discounts).total, 34291.66);
});

test('legacy responses, malformed discount amounts and positive OCR readings', () => {
    const receipt = createReceipt({items});
    assert.deepEqual(receipt.discounts, []);
    assert.equal(calculateSummary(receipt.items).total, 52091.66);
    for (const importe of ['9491,66', 'ilegible', '']) {
        const invalid = [{concepto: 'Oferta', importe}];
        assert.equal(discountValidationErrors(invalid).length, 1);
        assert.equal(calculateSummary(items, invalid).discountTotal, 0);
        invalid[0].usar = false;
        assert.deepEqual(discountValidationErrors(invalid), []);
    }
});
