import test from 'node:test';
import assert from 'node:assert/strict';
import {copyToClipboard} from '../../main/resources/static/receipt-clipboard.mjs';

const text = 'Café\tGenérico\tComercio\tOtros\t1\t1.500,50\t3/11/2026\nPan\tMarca\tComercio\tOtros\t2\t200,00\t3/11/2026';

function documentFixture({result = true, error} = {}) {
    const nodes = [];
    const copied = [];
    const previousRange = {};
    const selection = {rangeCount: 1, restored: [], getRangeAt: () => ({cloneRange: () => previousRange}),
        removeAllRanges() { this.restored = []; }, addRange(range) { this.restored.push(range); }};
    const original = {focus(options) { document.activeElement = original; this.options = options; }};
    const document = {activeElement: original,
        body: {appendChild: node => nodes.push(node)}, getSelection: () => selection,
        createElement: () => {
            const node = {style: {}, setAttribute() {},
                focus: () => { document.activeElement = node; },
                select() { this.selected = true; },
                setSelectionRange(start, end) { this.range = [start, end]; },
                remove() { nodes.splice(nodes.indexOf(node), 1); }};
            return node;
        },
        execCommand(command) {
            assert.equal(command, 'copy');
            const node = nodes[0];
            assert.equal(node.selected, true);
            assert.deepEqual(node.range, [0, text.length]);
            if (error) throw error;
            if (result) copied.push(node.value);
            return result;
        }};
    return {document, original, selection, previousRange, nodes, copied};
}

test('native clipboard receives exact TSV without using a temporary field', async () => {
    const copied = [];
    await copyToClipboard(text, {pageNavigator: {clipboard: {writeText: async value => copied.push(value)}},
        pageDocument: null});
    assert.deepEqual(copied, [text]);
});

test('HTTP fallback copies synchronously during the click and restores focus and selection', async () => {
    const fixture = documentFixture();
    const pending = copyToClipboard(text, {pageNavigator: {}, pageDocument: fixture.document});
    assert.deepEqual(fixture.copied, [text], 'la copia ocurre antes de ceder la activación del clic');
    await pending;
    assert.deepEqual(fixture.nodes, []);
    assert.equal(fixture.document.activeElement, fixture.original);
    assert.deepEqual(fixture.original.options, {preventScroll: true});
    assert.deepEqual(fixture.selection.restored, [fixture.previousRange]);
});

test('a denied native clipboard operation falls back to selecting and copying the exact text', async () => {
    const fixture = documentFixture();
    await copyToClipboard(text, {pageNavigator: {clipboard: {writeText: async () => { throw new Error('NotAllowedError'); }}},
        pageDocument: fixture.document});
    assert.deepEqual(fixture.copied, [text]);
    assert.equal(fixture.nodes.length, 0);
    assert.equal(fixture.document.activeElement, fixture.original);
});

for (const mode of [{result: false}, {error: new Error('blocked')}]) {
    test(`blocked fallback rejects and restores focus without leaving receipt text in the DOM (${mode.error ? 'throw' : 'false'})`, async () => {
        const fixture = documentFixture(mode);
        await assert.rejects(copyToClipboard(text, {pageNavigator: null, pageDocument: fixture.document}));
        assert.deepEqual(fixture.copied, []);
        assert.deepEqual(fixture.nodes, []);
        assert.equal(fixture.document.activeElement, fixture.original);
        assert.deepEqual(fixture.selection.restored, [fixture.previousRange]);
    });
}

test('missing clipboard APIs fail clearly instead of reporting a copied result', async () => {
    await assert.rejects(copyToClipboard(text, {pageNavigator: {}, pageDocument: {body: {}}}), /no permite copiar/);
});
