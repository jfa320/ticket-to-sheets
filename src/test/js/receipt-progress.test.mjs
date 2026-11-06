import test from 'node:test';
import assert from 'node:assert/strict';
import {createReceiptExtraction} from '../../main/resources/static/receipt-extraction.mjs';

function storage() {
    const values = new Map();
    return {values, pageWindow: {sessionStorage: {getItem: key => values.get(key), setItem: (key, value) => values.set(key, value),
        removeItem: key => values.delete(key)}, addEventListener() {}}};
}
const tick = () => new Promise(resolve => setImmediate(resolve));

test('manual cancellation confirms the server id before aborting and ignores a late progress response', async () => {
    const context = storage(); const calls = []; const updates = []; let resolveProgress, request;
    const session = createReceiptExtraction({...context, createId: () => 'test-id',
        extract(file, options) { request = options; return new Promise((resolve, reject) => {
            options.signal.addEventListener('abort', () => reject(new DOMException('Cancelled', 'AbortError')));
        }); },
        progress: () => new Promise(resolve => { resolveProgress = resolve; }), onProgress: value => updates.push(value),
        cancel: async id => { calls.push(id); assert.equal(request.signal.aborted, false); }, clearTimer() {}});
    const pending = session.extract('fixture'); const aborted = assert.rejects(pending, {name:'AbortError'});
    await tick(); assert.equal(await session.cancel(), true); await aborted;
    resolveProgress({status:'RUNNING',phase:'OCR',pageNumber:1,pageCount:2}); await tick();
    assert.deepEqual(calls, ['test-id']); assert.deepEqual(updates, []); assert.equal(context.values.size, 0);
});

test('failed cancellation retains the UUID and the user can retry without creating another extraction', async () => {
    const context = storage(); let online = false, uploads = 0;
    const session = createReceiptExtraction({...context, createId: () => 'same-id',
        extract(file, {signal}) { uploads++; return new Promise((resolve, reject) => {
            signal.addEventListener('abort', () => reject(new DOMException('Cancelled','AbortError')));
        }); }, cancel: async () => { if (!online) throw new Error('offline'); }});
    const pending = session.extract('fixture'); const aborted = assert.rejects(pending, {name:'AbortError'}); await tick();
    await assert.rejects(session.cancel(), /offline/);
    assert.equal(context.values.get('facturas.pendingExtraction'), 'same-id');
    online = true; await session.cancel(); await aborted;
    assert.equal(uploads, 1); assert.equal(context.values.size, 0);
});

test('progress failure never discards a successful extraction or schedules polling after completion', async () => {
    const updates = []; let schedules = 0, finish;
    const session = createReceiptExtraction({createId:()=> 'fixture',
        extract: () => new Promise(resolve => { finish=resolve; }), progress: async () => { throw new Error('progress offline'); },
        onProgress: value => updates.push(value), setTimer: () => { schedules++; return 1; }, clearTimer() {}});
    const pending = session.extract('file'); await tick();
    finish({items:['success']}); assert.deepEqual(await pending, {items:['success']});
    assert.deepEqual(updates, []); assert.equal(schedules, 1);
});
