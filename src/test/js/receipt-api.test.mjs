import test from 'node:test';
import assert from 'node:assert/strict';
import {extractReceipt, cancelReceipt} from '../../main/resources/static/receipt-api.mjs';

test('extraction preserves recovery messages for busy and unavailable OCR without retrying uploads', async () => {
    const originalFetch = globalThis.fetch;
    const originalConsoleError = console.error;
    console.error = () => {};
    try {
        for (const [status, message] of [
            [429, 'Ya hay una factura en procesamiento. Esperá a que termine y reintentá.'],
            [503, 'El OCR está preparando los modelos. Esperá a que termine y reintentá.']
        ]) {
            const calls = [];
            globalThis.fetch = async (...args) => {
                calls.push(args);
                return {ok: false, status, json: async () => ({message, requestId: 'synthetic-id'})};
            };
            await assert.rejects(extractReceipt(new Blob(['synthetic image'], {type: 'image/png'})),
                {message: `${message} (id: synthetic-id)`});
            assert.equal(calls.length, 1);
            assert.equal(calls[0][0], '/api/receipts/extract');
            assert.equal(calls[0][1].method, 'POST');
        }
    } finally {
        globalThis.fetch = originalFetch;
        console.error = originalConsoleError;
    }
});

test('extraction sends its id and abort signal, and cancellation uses a keepalive request', async () => {
    const originalFetch = globalThis.fetch;
    const calls = [];
    globalThis.fetch = async (...args) => {
        calls.push(args);
        return {ok: true, json: async () => ({cancelled: true})};
    };
    try {
        const controller = new AbortController();
        await extractReceipt(new Blob(['synthetic']), {extractionId: 'receipt-id', signal: controller.signal});
        await cancelReceipt('receipt-id');
        assert.equal(calls[0][1].headers['X-Extraction-Id'], 'receipt-id');
        assert.equal(calls[0][1].signal, controller.signal);
        assert.equal(calls[1][0], '/api/receipts/cancel?extractionId=receipt-id');
        assert.equal(calls[1][1].method, 'POST');
        assert.equal(calls[1][1].keepalive, true);
    } finally { globalThis.fetch = originalFetch; }
});

test('navigation aborts are preserved without being reported as network errors', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => { throw new DOMException('Navigation', 'AbortError'); };
    try {
        await assert.rejects(extractReceipt(new Blob(['synthetic'])), {name: 'AbortError'});
    } finally { globalThis.fetch = originalFetch; }
});
