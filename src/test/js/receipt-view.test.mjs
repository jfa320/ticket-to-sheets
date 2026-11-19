import test from 'node:test';
import assert from 'node:assert/strict';
import {renderItems} from '../../main/resources/static/receipt-view.mjs';

function createNode(tag) {
    return {tagName: tag, children: [], dataset: {}, attributes: {}, listeners: {},
        classList: {toggle() {}}, append(child) { this.children.push(child); }, replaceChildren() { this.children = []; },
        setAttribute(key, value) { this.attributes[key] = value; }, addEventListener(key, callback) { this.listeners[key] = callback; },
        querySelector() { return null; }};
}

test('category changes use a constrained select and notify the normal editing flow', () => {
    const previous = globalThis.document;
    globalThis.document = {createElement: createNode};
    try {
        const body = createNode('tbody'), item = {categoria: 'Otros'};
        let edits = 0;
        renderItems([item], body, {onEdit(row, field, value) { row[field] = value; edits++; }});
        const select = body.children[0].children.find(cell => cell.dataset.field === 'categoria').children[0];
        assert.equal(select.tagName, 'select');
        assert.equal(select.attributes['aria-label'], 'Categoria, fila 1');
        assert.equal(select.children.length, 9);
        select.value = 'Kiosco'; select.listeners.change();
        assert.equal(item.categoria, 'Kiosco');
        assert.equal(edits, 1);
    } finally { globalThis.document = previous; }
});

test('the read-only row total follows quantity and price edits even on excluded rows', () => {
    const previous = globalThis.document;
    globalThis.document = {createElement: createNode};
    try {
        const body = createNode('tbody');
        const item = {cantidad: '0.5', precioUnitario: '2.001,25', usar: false};
        renderItems([item], body, {onEdit(row, field, value) { row[field] = value; }});
        const cells = body.children[0].children;
        const totalIndex = cells.findIndex(cell => cell.dataset.label === 'Total');
        const total = cells[totalIndex];
        assert.equal(cells[totalIndex - 1].dataset.label, 'Fecha');
        assert.equal(cells[totalIndex + 1].dataset.label, 'Estado');
        assert.equal(total.dataset.field, undefined);
        assert.equal(total.contentEditable, undefined);
        assert.equal(total.textContent, '$ 1.000,63');

        const quantity = cells.find(cell => cell.dataset.field === 'cantidad');
        const price = cells.find(cell => cell.dataset.field === 'precioUnitario');
        quantity.textContent = '3';
        quantity.listeners.input();
        assert.equal(total.textContent, '$ 6.003,75');
        price.textContent = '0,335';
        price.listeners.input();
        assert.equal(total.textContent, '$ 1,01');
        price.textContent = '';
        price.listeners.input();
        assert.equal(total.textContent, '—');
    } finally { globalThis.document = previous; }
});

test('fractional quantities display a comma on render and after editing', () => {
    const previous = globalThis.document;
    globalThis.document = {createElement: createNode};
    try {
        const body = createNode('tbody');
        const item = {cantidad: '0.8750', precioUnitario: '1000,00'};
        renderItems([item], body, {onEdit(row, field, value) { row[field] = value; }});
        const cells = body.children[0].children;
        const quantity = cells.find(cell => cell.dataset.field === 'cantidad');
        const total = cells.find(cell => cell.dataset.label === 'Total');
        assert.equal(quantity.textContent, '0,8750');

        quantity.textContent = '1.0625';
        quantity.listeners.input();
        quantity.listeners.blur();
        assert.equal(quantity.textContent, '1,0625');
        assert.equal(total.textContent, '$ 1.062,50');
    } finally { globalThis.document = previous; }
});

test('pending filter preserves original row indices and the reviewed OCR warning', () => {
    const previous = globalThis.document;
    const node = () => ({children: [], dataset: {}, className: '', textContent: '', attributes: {}, listeners: {},
        classList: {toggle() {}}, append(child) { this.children.push(child); }, replaceChildren() { this.children = []; },
        setAttribute(key, value) { this.attributes[key] = value; }, addEventListener(key, callback) { this.listeners[key] = callback; }});
    globalThis.document = {createElement: node};
    try {
        const body = node(), items = [{estado:'CORRECT'}, {estado:'AMBIGUOUS',revisado:true}, {estado:'AMBIGUOUS'}];
        let selected = null;
        renderItems(items,body,{pendingOnly:true,onReviewed:item=>{selected=item;}});
        assert.equal(body.children.length,1);
        assert.equal(body.children[0].children[1].dataset.index,'2');
        const actions = body.children[0].children.find(cell=>cell.dataset.label==='Acciones');
        actions.children[0].children[0].listeners.click(); assert.equal(selected,items[2]);
        renderItems(items,body,{});
        const labels = body.children[1].children.find(cell=>cell.dataset.label==='Estado').children.map(b=>b.textContent);
        assert.deepEqual(labels,['Ambiguo','Revisada']);
    } finally {globalThis.document=previous;}
});

test('a learned ambiguous product retains both the warning style and the memory label', () => {
    const previous = globalThis.document;
    const node = () => ({children: [], dataset: {}, className: '', textContent: '',
        classList: {values: new Set(), toggle(name, value) { if (value) this.values.add(name); }},
        append(child) { this.children.push(child); }, replaceChildren() { this.children = []; },
        setAttribute() {}, addEventListener() {}});
    globalThis.document = {createElement: node};
    try {
        const body = node();
        renderItems([{descripcion: 'Producto', estado: 'AMBIGUOUS', memorizado: true}], body, {});
        const row = body.children[0];
        assert.equal(row.classList.values.has('ambiguous-row'), true);
        const badges = row.children.find(cell => cell.dataset.label === 'Estado').children;
        assert.deepEqual(badges.map(badge => badge.textContent), ['Ambiguo', 'Memorizado']);
    } finally { globalThis.document = previous; }
});
