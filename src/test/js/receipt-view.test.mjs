import test from 'node:test';
import assert from 'node:assert/strict';
import {renderItems} from '../../main/resources/static/receipt-view.mjs';

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
