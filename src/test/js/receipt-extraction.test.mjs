import test from 'node:test';
import assert from 'node:assert/strict';
import {createReceiptExtraction} from '../../main/resources/static/receipt-extraction.mjs';

function page(storage = new Map(), beaconResult = true) {
    const listeners = new Map();
    const beacons = [];
    return {storage, beacons,
        pageWindow: {
            sessionStorage: {getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value),
                removeItem: key => storage.delete(key)},
            addEventListener: (type, listener) => listeners.set(type, listener)
        },
        pageNavigator: {sendBeacon: url => { beacons.push(url); return beaconResult; }},
        dispatch: (type, event = {}) => listeners.get(type)?.(event)
    };
}

test('reload cancels the active request and retries its cancellation before another upload', async () => {
    const oldPage = page();
    let request;
    let started;
    const ready = new Promise(resolve => { started = resolve; });
    const oldSession = createReceiptExtraction({...oldPage, createId: () => 'old-id',
        extract: (file, options) => { request = options; started(); return new Promise((resolve, reject) => {
            options.signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')));
        }); }
    });
    const pending = oldSession.extract('old-file');
    const aborted = assert.rejects(pending, {name: 'AbortError'});
    await ready;
    oldPage.dispatch('pagehide');
    await aborted;
    assert.equal(request.extractionId, 'old-id');
    assert.equal(request.signal.aborted, true);
    assert.deepEqual(oldPage.beacons, ['/api/receipts/cancel?extractionId=old-id']);
    assert.equal(oldPage.storage.get('facturas.pendingExtraction'), 'old-id');

    const newPage = page(oldPage.storage);
    let releaseCancellation;
    const calls = [];
    const newSession = createReceiptExtraction({...newPage, createId: () => 'new-id',
        cancel: id => { calls.push(['cancel', id]); return new Promise(resolve => { releaseCancellation = resolve; }); },
        extract: async (file, options) => { calls.push(['extract', file, options.extractionId]); return {items: []}; }
    });
    const next = newSession.extract('new-file');
    await Promise.resolve();
    assert.deepEqual(calls, [['cancel', 'old-id']], 'espera la cancelación antes de subir otra factura');
    releaseCancellation({cancelled: true});
    assert.deepEqual(await next, {items: []});
    assert.deepEqual(calls, [['cancel', 'old-id'], ['extract', 'new-file', 'new-id']]);
    assert.equal(newPage.storage.has('facturas.pendingExtraction'), false);
    newPage.dispatch('pagehide');
    assert.deepEqual(newPage.beacons, [], 'una factura completada no se cancela');
});

test('dismissed beforeunload does not cancel; pagehide falls back when beacon cannot be queued', async () => {
    const context = page(new Map(), false);
    const cancellations = [];
    let complete;
    let started;
    const ready = new Promise(resolve => { started = resolve; });
    const session = createReceiptExtraction({...context, createId: () => 'active-id',
        cancel: async id => { cancellations.push(id); },
        extract: () => { started(); return new Promise(resolve => { complete = resolve; }); }
    });
    const pending = session.extract('file');
    const aborted = assert.rejects(pending, {name: 'AbortError'});
    await ready;
    context.dispatch('beforeunload');
    assert.deepEqual(cancellations, []);
    assert.deepEqual(context.beacons, []);
    context.dispatch('pagehide');
    assert.deepEqual(cancellations, ['active-id']);
    complete({items: []});
    await aborted;
    assert.equal(context.storage.get('facturas.pendingExtraction'), 'active-id');
});

test('failed recovery preserves the cancellation id and allows a later retry', async () => {
    const context = page(new Map([['facturas.pendingExtraction', 'previous-id']]));
    let available = false;
    let uploads = 0;
    const session = createReceiptExtraction({...context, createId: () => 'next-id',
        cancel: async () => { if (!available) throw new Error('OCR desconectado'); },
        extract: async () => { uploads++; return {items: []}; }
    });
    await assert.rejects(session.extract('file'), /OCR desconectado/);
    assert.equal(uploads, 0);
    assert.equal(context.storage.get('facturas.pendingExtraction'), 'previous-id');
    available = true;
    await session.extract('file');
    assert.equal(uploads, 1);
    assert.equal(context.storage.size, 0);
});

test('restoring the page from browser cache recovers its cancelled extraction', async () => {
    const context = page();
    const cancellations = [];
    let finish;
    let started;
    const ready = new Promise(resolve => { started = resolve; });
    let number = 0;
    const session = createReceiptExtraction({...context, createId: () => `id-${++number}`,
        cancel: async id => { cancellations.push(id); },
        extract: () => { started(); return new Promise(resolve => { finish = resolve; }); }
    });
    const first = session.extract('file');
    const aborted = assert.rejects(first, {name: 'AbortError'});
    await ready;
    context.dispatch('pagehide');
    finish({items: []});
    await aborted;
    context.dispatch('pageshow', {persisted: true});
    await Promise.resolve();
    assert.deepEqual(cancellations, ['id-1']);
    const second = session.extract('other-file');
    // Esperar a que el segundo extract haya instalado su resolución.
    await new Promise(resolve => setImmediate(resolve));
    finish({items: ['new']});
    assert.deepEqual(await second, {items: ['new']});
});
