import test from 'node:test';
import assert from 'node:assert/strict';
import {correctionRequest} from '../../main/resources/static/correction-manager.mjs';

test('search encodes private local queries and deletion uses exact key and version', async () => {
    const calls = [];
    const fetcher = async (url, options) => { calls.push({url, options}); return {ok: true, json: async () => ({deleted: true})}; };
    await correctionRequest('GET', {q: 'Local & café', offset: 25, limit: 25}, fetcher);
    assert.equal(new URL(calls[0].url, 'http://localhost').searchParams.get('q'), 'Local & café');
    const key = {store: 'Local', firma: 'leche', version: 'a'.repeat(64)};
    await correctionRequest('DELETE', key, fetcher);
    assert.deepEqual(JSON.parse(calls[1].options.body), key);
    assert.equal(calls[1].options.method, 'DELETE');
});

test('a stale correction fails clearly without resubmitting the write', async () => {
    let writes = 0;
    await assert.rejects(correctionRequest('PUT', {}, async () => {
        writes++; return {ok: false, status: 409, json: async () => ({})};
    }), /cambió.*versión actual/);
    assert.equal(writes, 1);
});

test('invalid JSON and network errors are not shown as saved corrections', async () => {
    await assert.rejects(correctionRequest('PUT', {}, async () => ({ok: true, json: async () => { throw Error(); }})), /inválida/);
    await assert.rejects(correctionRequest('DELETE', {}, async () => { throw Error('offline'); }), /offline/);
});
