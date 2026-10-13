import test from 'node:test';
import assert from 'node:assert/strict';
import {extractReceipt} from '../../main/resources/static/receipt-api.mjs';

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
