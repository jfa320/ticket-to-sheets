import test from 'node:test';
import assert from 'node:assert/strict';
import {renderItems} from '../../main/resources/static/receipt-view.mjs';

test('category changes use a constrained select and notify the normal editing flow', () => {
    const previous = globalThis.document;
    const node = tag => ({tagName: tag, children: [], dataset: {}, attributes: {}, listeners: {},
        classList: {toggle() {}}, append(child) { this.children.push(child); }, replaceChildren() { this.children = []; },
        setAttribute(key, value) { this.attributes[key] = value; }, addEventListener(key, callback) { this.listeners[key] = callback; },
        querySelector() { return null; }});
    globalThis.document = {createElement: node};
    try {
        const body = node('tbody'), item = {categoria: 'Otros'};
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
