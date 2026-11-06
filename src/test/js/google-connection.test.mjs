import test from 'node:test';
import assert from 'node:assert/strict';
import {connectGoogle, selectGoogleSpreadsheet} from '../../main/resources/static/google-connection.mjs';
import {createSheetsApi} from '../../main/resources/static/receipt-sheets.mjs';

function popupHarness() {
    const listeners = new Map();
    const popup = {closed: false, location: {}, close() { this.closed = true; }};
    let poll, timeout, cleared = 0;
    const browser = {location: {origin: 'http://localhost:8080'}, open: () => popup,
        addEventListener: (name, callback) => listeners.set(name, callback),
        removeEventListener: name => listeners.delete(name)};
    return {browser, popup, listeners, get poll() { return poll; }, get timeout() { return timeout; },
        get cleared() { return cleared; }, options: {browser,
            setPoll: callback => {poll = callback; return 1;}, clearPoll: () => cleared++,
            setTimer: callback => {timeout = callback; return 2;}, clearTimer: () => cleared++},
        message(data, overrides = {}) { listeners.get('message')?.({source: popup, origin: browser.location.origin, data, ...overrides}); }};
}
const authorize = () => Promise.resolve({authorizationUrl: 'https://accounts.google.com/o/oauth2/v2/auth?state=synthetic'});

test('OAuth popup accepts only its own callback from the local origin and cleans up', async () => {
    const h = popupHarness(); let done = false;
    const task = connectGoogle({...h.options, api: {connectGoogle: authorize}}).then(() => {done = true;});
    await Promise.resolve();
    assert.match(h.popup.location.href, /^https:\/\/accounts.google.com\//);
    h.message({type: 'facturas-google-oauth', success: true}, {origin: 'https://foreign.example'});
    h.message({type: 'facturas-google-oauth', success: true}, {source: {}});
    h.message({type: 'other', success: true});
    await Promise.resolve(); assert.equal(done, false);
    h.message({type: 'facturas-google-oauth', success: true});
    await task;
    assert.equal(h.popup.closed, true); assert.equal(h.listeners.size, 0); assert.equal(h.cleared, 2);
});

test('OAuth handles blocked popup, cancellation, timeout, and failed start without leaving listeners', async () => {
    await assert.rejects(connectGoogle({browser: {open: () => null}, api: {}}), /emergentes/);
    for (const outcome of ['cancel', 'timeout', 'http', 'foreign-url', 'close']) {
        const h = popupHarness();
        const api = {connectGoogle: outcome === 'http' ? async () => {throw new Error('sin conexión');}
            : outcome === 'foreign-url' ? async () => ({authorizationUrl: 'https://foreign.example/'}) : authorize,
            getConfig: async () => ({googleConnected: false})};
        const task = connectGoogle({...h.options, api});
        const rejected = assert.rejects(task, /autorización|venció|conexión|inválida/);
        if (outcome === 'cancel') h.message({type: 'facturas-google-oauth', success: false});
        if (outcome === 'timeout') h.timeout();
        if (outcome === 'close') {h.popup.closed = true; await h.poll();}
        await rejected;
        assert.equal(h.listeners.size, 0); assert.equal(h.popup.closed, true); assert.equal(h.cleared, 2);
    }
});

function pickerHarness() {
    const config = {}; let callback, timer;
    const picker = {setVisible: value => {config.visible = value;}, dispose: () => {config.disposed = true;}};
    class DocsView {
        constructor(id) {config.view = id;}
        setMimeTypes(value) {config.mime = value; return this;}
        setIncludeFolders() {return this;}
        setSelectFolderEnabled() {return this;}
    }
    class PickerBuilder {
        addView() {return this;}
        setOAuthToken(value) {config.token = value; return this;}
        setDeveloperKey() {return this;}
        setAppId(value) {config.appId = value; return this;}
        setOrigin(value) {config.origin = value; return this;}
        setLocale() {return this;}
        setTitle() {return this;}
        setCallback(value) {callback = value; return this;}
        build() {return picker;}
    }
    return {config, select: data => callback(data), expire: () => timer(), options: {
        pickerApi: {DocsView, PickerBuilder, ViewId: {SPREADSHEETS: 'sheets'}, Action: {PICKED: 'picked', CANCEL: 'cancel'}},
        session: {accessToken: 'short-lived-token', developerKey: 'public-key', appId: '123'}, origin: 'http://localhost:8080',
        setTimer: fn => {timer = fn; return 1;}, clearTimer: () => {config.timerCleared = true;}
    }};
}
test('Picker selects native Sheets only and constructs the destination from its validated ID', async () => {
    const h = pickerHarness(); const promise = selectGoogleSpreadsheet(h.options);
    assert.equal(h.config.mime, 'application/vnd.google-apps.spreadsheet'); assert.equal(h.config.appId, '123');
    const id = 'synthetic_spreadsheet_id_1234567890';
    h.select({action: 'picked', docs: [{id, mimeType: h.config.mime, name: 'Compras', url: 'https://foreign.example/'}]});
    assert.deepEqual(await promise, {id, name: 'Compras', url: `https://docs.google.com/spreadsheets/d/${id}/edit`});
    assert.equal(h.config.disposed, true); assert.equal(h.config.timerCleared, true);
});
test('Picker cancellation changes no destination; wrong types, IDs and timeouts fail', async () => {
    const h = pickerHarness(); const promise = selectGoogleSpreadsheet(h.options);
    h.select({action: 'cancel'}); assert.equal(await promise, null);
    for (const doc of [{id: 'bad', mimeType: h.config.mime}, {id: 'synthetic_spreadsheet_id_1234567890', mimeType: 'application/vnd.ms-excel'}]) {
        const invalid = pickerHarness(); const attempt = selectGoogleSpreadsheet(invalid.options);
        invalid.select({action: 'picked', docs: [doc]}); await assert.rejects(attempt, /nativa/);
    }
    const expired = pickerHarness(); const attempt = selectGoogleSpreadsheet(expired.options);
    expired.expire(); await assert.rejects(attempt, /venció/);
});

test('OAuth API uses same-origin JSON POSTs without persisting credentials in the browser', async () => {
    const calls = [];
    const api = createSheetsApi(async (url, options) => {calls.push([url, options]); return {ok: true, json: async () => ({})};});
    await api.connectGoogle(); await api.pickerSession(); await api.disconnectGoogle();
    assert.deepEqual(calls.map(([url]) => url), ['/api/sheets/oauth/connect', '/api/sheets/oauth/picker', '/api/sheets/oauth/disconnect']);
    for (const [, options] of calls) {assert.equal(options.method, 'POST'); assert.equal(options.headers['Content-Type'], 'application/json'); assert.equal(options.body, '{}');}
});
