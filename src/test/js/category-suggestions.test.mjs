import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {CATEGORIES, canonicalCategory, createCategorySelect} from '../../main/resources/static/category-suggestions.mjs';
import {createReceipt, buildExports} from '../../main/resources/static/receipt-model.mjs';

test('the frontend and backend use exactly the nine categories from the sheet', async () => {
    const config = JSON.parse(await readFile(new URL('../../main/resources/category-defaults.json', import.meta.url), 'utf8'));
    assert.deepEqual(CATEGORIES, ['Limpieza', 'Supermercado', 'Farmacia', 'Kiosco', 'Carne', 'Otros',
        'Verduleria', 'Perfumeria', 'Panaderia']);
    assert.deepEqual(CATEGORIES, config.categories);
});

test('legacy and accented values map to available categories', () => {
    assert.equal(canonicalCategory('Ferretería'), 'Otros');
    assert.equal(canonicalCategory('Inventada'), 'Otros');
    assert.equal(canonicalCategory('Lácteos'), 'Supermercado');
    assert.equal(canonicalCategory('Almacén'), 'Supermercado');
    assert.equal(canonicalCategory(' PERFUMERÍA '), 'Perfumeria');
});

const root = {createElement: tag => ({tagName: tag, children: [], append(child) { this.children.push(child); }})};

test('a category editor is a native select with only the permitted options', () => {
    const select = createCategorySelect('Ferreteria', {root});
    assert.equal(select.tagName, 'select');
    assert.equal(select.value, 'Otros');
    assert.deepEqual(select.children.map(option => option.value), CATEGORIES);
});

test('correction selectors keep an empty option for fields without a learned override', () => {
    const select = createCategorySelect('', {root, allowEmpty: true});
    assert.equal(select.value, '');
    assert.deepEqual(select.children.map(option => option.value), ['', ...CATEGORIES]);
});

test('receiving an old category updates the model and export without altering amounts or input', () => {
    const original = {descripcion: 'Plafon', categoria: 'Ferreteria', cantidad: '1', precioUnitario: '9200,00'};
    const receipt = createReceipt({items: [original]});
    assert.equal(receipt.items[0].categoria, 'Otros');
    assert.equal(receipt.category, 'Otros');
    assert.equal(original.categoria, 'Ferreteria');
    const exported = buildExports(receipt.items);
    assert.match(exported.rowsOnly, /\tOtros\t1\t9200,00/);
    assert.equal(exported.total, 9200);
});
