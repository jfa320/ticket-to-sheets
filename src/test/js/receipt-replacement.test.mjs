import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {runInNewContext} from 'node:vm';
import * as model from '../../main/resources/static/receipt-model.mjs';
import {validateFile} from '../../main/resources/static/receipt-api.mjs';
import {createCorrectionSaver} from '../../main/resources/static/receipt-corrections.mjs';
import {createReceiptExtraction} from '../../main/resources/static/receipt-extraction.mjs';

const source = readFileSync(new URL('../../main/resources/static/app.js', import.meta.url), 'utf8');
const file = name => ({name, type: 'image/png', size: 100});
const payload = description => ({storeName: 'Comercio de prueba', date: '7/10/2026', rawText: description,
    warnings: ['Revisar marca'], ocrReview: [{detections: [{text: description}]}],
    items: [{descripcion: description, marca: 'Marca de prueba', categoria: 'Otros',
        lugarDeCompra: 'Comercio de prueba', cantidad: '1', precioUnitario: '100,00',
        fecha: '7/10/2026', firma: description, estado: 'AMBIGUOUS'}]});

test('cancelar mientras se guardan correcciones impide iniciar la extracción nueva', async () => {
    const app=createApp(); await editedReceipt(app); const before=snapshot(app); let saved;
    app.savers[0].flush=()=>new Promise(resolve=>{saved=resolve;});
    app.select('nuevo.png'); const pending=app.submit();
    await app.element('cancelExtraction').dispatch('click'); saved(true); await pending;
    assert.equal(app.extractionCalls(),1); assert.deepEqual(snapshot(app),before);
    assert.match(app.element('status').textContent,/cancelada/);
});

test('cancelar una extracción descarta su respuesta tardía y permite intentar otra', async () => {
    const app=createApp(); await editedReceipt(app); const before=snapshot(app); let finish;
    app.setExtraction(()=>new Promise(resolve=>{finish=resolve;})); app.select('nuevo.png');
    const pending=app.submit(); await new Promise(resolve=>setImmediate(resolve));
    await app.element('cancelExtraction').dispatch('click'); finish(payload('Respuesta descartada')); await pending;
    assert.deepEqual(snapshot(app),before); assert.match(app.element('status').textContent,/cancelada/);
    app.setExtraction(async()=>payload('Reintento correcto')); await app.submit();
    assert.equal(app.items()[0].descripcion,'Reintento correcto');
});

// Ejecutar el controlador real con sus imports inyectados, sin bundler ni dependencias DOM.
function createApp({copyToClipboard = async () => {}} = {}) {
    const elements = new Map();
    function element(id) {
        if (elements.has(id)) return elements.get(id);
        const classes = new Set(['hidden']);
        const listeners = new Map();
        const node = {
            value: '', textContent: '', children: [], disabled: false, inert: false,
            classList: {
                add: name => classes.add(name), remove: name => classes.delete(name),
                contains: name => classes.has(name),
                toggle(name, force = !classes.has(name)) { force ? classes.add(name) : classes.delete(name); }
            },
            addEventListener(type, listener) { listeners.set(type, listener); },
            dispatch(type, event = {}) { return listeners.get(type)?.({preventDefault() {}, target: node, ...event}); },
            setAttribute() {}, removeAttribute() {}, append(child) { node.children.push(child); },
            replaceChildren() { node.children = []; }, querySelectorAll() { return []; }, focus() {}
        };
        elements.set(id, node);
        return node;
    }
    const corrections = [];
    const savers = [];
    const review = {pages: [], clear() { this.pages = []; }, setPages(pages) { this.pages = pages; }};
    let sheetsOptions;
    let sheetsResets = 0;
    let extractionCalls = 0;
    let extract = async () => payload('Producto anterior');
    const dependencies = {
        ...model, validateFile, copyToClipboard,
        createCorrectionManager() {}, loadCategories: async () => {},
        createReceiptExtraction: options => createReceiptExtraction({...options,
            extract: uploaded => { extractionCalls++; return extract(uploaded); },
            cancel: async () => ({cancelled: true}), createId: () => `extraction-${extractionCalls}`}),
        saveCorrections: async batch => { corrections.push(batch); return {saved: batch.corrections.length}; },
        createCorrectionSaver(receipt, options) {
            const saver = createCorrectionSaver(receipt, {...options, setTimer: () => 1, clearTimer() {}});
            const originalDispose = saver.dispose;
            saver.dispose = () => { saver.disposed = true; originalDispose(); };
            savers.push(saver);
            return saver;
        },
        renderItems(items, body, handlers) { body.children = items.map(item => ({item})); body.handlers = handlers; },
        renderDiscounts(discounts, panel, list, onChange) {
            list.children = discounts; list.onChange = onChange;
            panel.classList.toggle('hidden', discounts.length === 0);
        },
        renderWarnings(warnings, panel, list) {
            list.children = [...warnings]; panel.classList.toggle('hidden', warnings.length === 0);
        },
        markInvalidCells() {}, createOcrReview: () => review,
        createSheetsPanel(options) {
            sheetsOptions = options;
            return {refresh() {}, setProcessing() {}, resetReceipt() { sheetsResets++; }};
        },
        document: {getElementById: element, querySelector: element, querySelectorAll: () => [], createElement: () => element(Symbol()),
            addEventListener: (...args) => element('document').addEventListener(...args)},
        URL: {createObjectURL: () => 'blob:synthetic-preview', revokeObjectURL() {}},
        requestAnimationFrame: callback => callback(), setTimeout: () => 1, clearTimeout() {}, console: {error() {}}
    };
    runInNewContext(source.replace(/^import\b[\s\S]*?;\s*/gm, ''), dependencies, {filename: 'app.js'});
    return {
        element, corrections, savers, review,
        items: () => sheetsOptions.getItems(), sheetsResets: () => sheetsResets, extractionCalls: () => extractionCalls,
        setExtraction(handler) { extract = handler; },
        select(name) { element('fileInput').files = [file(name)]; element('fileInput').dispatch('change'); },
        submit: () => element('uploadForm').dispatch('submit'),
        paste: event => element('document').dispatch('paste', event),
        setSheetsBusy: busy => sheetsOptions.onBusy(busy),
        edit(field, value) { element('itemsBody').handlers.onEdit(sheetsOptions.getItems()[0], field, value); }
    };
}

function snapshot(app) {
    return {
        items: JSON.stringify(app.items()), rows: [...app.element('itemsBody').children],
        count: app.element('itemCount').textContent, total: app.element('totalValue').textContent,
        pipe: app.element('csvOutput').value, tsv: app.element('tsvOutput').value,
        raw: app.element('rawOutput').value, warnings: [...app.element('warningsList').children],
        review: app.review.pages, resets: app.sheetsResets()
    };
}

async function editedReceipt(app) {
    app.select('anterior.png');
    await app.submit();
    app.edit('descripcion', 'Descripción corregida');
    app.edit('cantidad', '2');
    app.edit('precioUnitario', '150,50');
}

for (const [name, extract] of [
    ['OCR unavailable', async () => { throw new Error('Iniciá el servicio OCR local.'); }],
    ['OCR busy', async () => { throw new Error('Ya hay una factura en procesamiento.'); }],
    ['invalid response', async () => ({items: null})]
]) {
    test(`${name} preserves the edited receipt, exports, warnings and OCR review`, async () => {
        const app = createApp();
        await editedReceipt(app);
        const before = snapshot(app);
        app.setExtraction(extract);
        app.select('nueva.png');
        assert.deepEqual(snapshot(app), before, 'seleccionar otro archivo conserva el ticket');
        await app.submit();
        assert.deepEqual(snapshot(app), before, 'una extracción fallida conserva todos los resultados');
        assert.equal(app.element('results').classList.contains('hidden'), false);
        assert.equal(app.element('results').inert, false);
        assert.equal(app.element('submitButton').disabled, false);
        assert.match(app.element('status').textContent, /Se conserva el ticket anterior/);
        assert.equal(app.savers.length, 1);
        assert.equal(app.savers[0].disposed, undefined);
        assert.equal(app.corrections[0].corrections[0].descripcion, 'Descripción corregida');
        app.edit('marca', 'Marca corregida después del error');
        assert.equal(await app.savers[0].flush(), true);
        assert.equal(app.corrections.at(-1).corrections[0].marca, 'Marca corregida después del error');
    });
}

test('pending extraction preserves results and a valid response replaces them once', async () => {
    const app = createApp();
    await editedReceipt(app);
    const before = snapshot(app);
    let resolve;
    let started;
    const requestStarted = new Promise(done => { started = done; });
    app.setExtraction(() => { started(); return new Promise(done => { resolve = done; }); });
    app.select('nueva.png');
    const pending = app.submit();
    await requestStarted;
    assert.deepEqual(snapshot(app), before);
    assert.equal(app.element('results').classList.contains('hidden'), false);
    assert.equal(app.element('results').inert, true);
    assert.equal(app.savers[0].disposed, undefined);
    await app.submit();
    assert.equal(app.extractionCalls(), 2, 'no se inicia otra extracción durante la espera');
    const next = payload('Producto nuevo');
    resolve(next);
    await pending;
    assert.equal(app.items()[0].descripcion, 'Producto nuevo');
    assert.equal(app.element('totalValue').textContent, model.formatTotal(100));
    assert.match(app.element('csvOutput').value, /Producto nuevo/);
    assert.doesNotMatch(app.element('tsvOutput').value, /Descripción corregida/);
    assert.equal(app.review.pages, next.ocrReview);
    assert.equal(app.sheetsResets(), before.resets + 1);
    assert.equal(app.savers[0].disposed, true);
    assert.equal(app.savers.length, 2);
    assert.equal(app.element('results').inert, false);
});

test('failure without a previous receipt keeps results hidden and allows retry', async () => {
    const app = createApp();
    app.setExtraction(async () => { throw new Error('OCR apagado'); });
    app.select('primera.png');
    await app.submit();
    assert.equal(app.items().length, 0);
    assert.equal(app.element('results').classList.contains('hidden'), true);
    assert.equal(app.element('submitButton').disabled, false);
    assert.equal(app.element('status').textContent, 'Error: OCR apagado');
});

test('discounts recalculate with edits and survive a failed replacement', async () => {
    const app = createApp();
    app.setExtraction(async () => ({...payload('Producto con bonificación'),
        discounts: [{concepto: 'Oferta', importe: '-10,00'}]}));
    app.select('bonificacion.png');
    await app.submit();
    assert.equal(app.element('totalValue').textContent, model.formatTotal(90));
    assert.equal(app.element('subtotalValue').textContent, model.formatTotal(100));
    assert.equal(app.element('itemCount').textContent, 2);
    assert.equal(app.items()[1].descripcion, 'Descuento');
    assert.equal(app.items()[1].cantidad, '1');
    assert.match(app.element('tsvOutput').value, /Descuento\tGenérico\tComercio de prueba\tOtros\t1\t-10,00\t7\/10\/2026/);
    app.edit('precioUnitario', '200,00');
    assert.equal(app.element('totalValue').textContent, model.formatTotal(190));
    app.element('discountsList').children[0].importe = '-20,00';
    app.element('discountsList').onChange();
    assert.equal(app.element('totalValue').textContent, model.formatTotal(180));
    assert.equal(app.items()[1].precioUnitario, '-20,00');
    assert.match(app.element('tsvOutput').value, /\t-20,00\t/);
    app.setExtraction(async () => { throw new Error('OCR apagado'); });
    app.select('fallo.png');
    await app.submit();
    assert.equal(app.element('totalValue').textContent, model.formatTotal(180));
    assert.equal(app.element('discountsList').children[0].importe, '-20,00');
});

test('replacing a discounted receipt with a legacy response clears the discounts', async () => {
    const app = createApp();
    app.setExtraction(async () => ({...payload('Anterior'), discounts: [{concepto: 'Oferta', importe: '-10,00'}]}));
    app.select('anterior.png');
    await app.submit();
    app.setExtraction(async () => payload('Nuevo'));
    app.select('nuevo.png');
    await app.submit();
    assert.equal(app.element('discountsList').children.length, 0);
    assert.equal(app.element('discountsPanel').classList.contains('hidden'), true);
    assert.equal(app.element('totalValue').textContent, model.formatTotal(100));
});

function imagePaste(image = file('image.png'), overrides = {}) {
    const event = {
        defaultPrevented: false,
        preventDefault() { event.defaultPrevented = true; },
        clipboardData: {items: [
            {kind: 'string', type: 'text/plain'},
            {kind: 'file', type: image.type, getAsFile: () => image}
        ]},
        ...overrides
    };
    return event;
}

test('pasting a clipboard image selects it and waits for manual extraction like a regular upload', async () => {
    const app = createApp();
    const image = file('image.png');
    const uploads = [];
    app.setExtraction(async uploaded => { uploads.push(uploaded); return payload('Producto pegado'); });
    app.element('fileInput').value = 'previous.png';
    const event = imagePaste(image);
    await app.paste(event);
    assert.equal(event.defaultPrevented, true);
    assert.deepEqual(uploads, []);
    assert.equal(app.element('fileInput').value, '');
    assert.equal(app.element('fileName').textContent, image.name);
    assert.equal(app.element('filePreviewContent').children[0].src, 'blob:synthetic-preview');
    assert.equal(app.element('submitButton').disabled, false);
    assert.equal(app.element('viewFile').disabled, false);
    assert.match(app.element('status').textContent, /Archivo listo/);
    assert.equal(app.items().length, 0);
    await app.submit();
    assert.deepEqual(uploads, [image]);
    assert.equal(app.items()[0].descripcion, 'Producto pegado');
    assert.match(app.element('csvOutput').value, /Producto pegado/);
    assert.equal(app.element('results').inert, false);
});

test('clipboard files fallback accepts the first image and ignores PDF and text', async () => {
    const app = createApp();
    const image = file('captura.png');
    const uploads = [];
    app.setExtraction(async uploaded => { uploads.push(uploaded); return payload('Captura'); });
    await app.paste(imagePaste(image, {clipboardData: {
        items: [{kind: 'file', type: 'image/png', getAsFile: () => null}],
        files: [{name: 'documento.pdf', type: 'application/pdf'}, image, file('otra.png')]
    }}));
    assert.deepEqual(uploads, []);
    for (const clipboardData of [undefined, {items: []}, {items: [{kind: 'string', type: 'text/plain'}]},
        {files: [{name: 'documento.pdf', type: 'application/pdf'}]}]) {
        const event = imagePaste(image, {clipboardData});
        await app.paste(event);
        assert.equal(event.defaultPrevented, false);
    }
    assert.equal(uploads.length, 0);
    assert.equal(app.element('fileName').textContent, image.name);
    await app.submit();
    assert.deepEqual(uploads, [image]);
});

test('paste preserves native editing in fields, editable descendants and dialogs', async () => {
    const app = createApp();
    for (const selector of ['input:not([type="file"])', 'textarea', 'select', '[role="textbox"]', 'dialog']) {
        const target = {closest: query => query.includes(selector) ? target : null};
        const event = imagePaste(undefined, {target});
        await app.paste(event);
        assert.equal(event.defaultPrevented, false, selector);
    }
    const editable = imagePaste(undefined, {target: {isContentEditable: true}});
    await app.paste(editable);
    assert.equal(editable.defaultPrevented, false);
    await app.paste(imagePaste(undefined, {defaultPrevented: true}));
    assert.equal(app.extractionCalls(), 0);
});

test('paste validates empty and oversized images without uploading', async () => {
    const app = createApp();
    for (const [size, message] of [[0, /vacío/], [20 * 1024 * 1024 + 1, /20 MB/]]) {
        const event = imagePaste({...file('grande.png'), size});
        await app.paste(event);
        assert.equal(event.defaultPrevented, true);
        assert.match(app.element('status').textContent, message);
        assert.equal(app.element('submitButton').disabled, true);
    }
    assert.equal(app.extractionCalls(), 0);
});

test('paste cannot replace a file while extraction or a Sheets transfer is busy', async () => {
    const app = createApp();
    let finish;
    let started;
    const ready = new Promise(resolve => { started = resolve; });
    app.setExtraction(() => { started(); return new Promise(resolve => { finish = resolve; }); });
    await app.paste(imagePaste(file('primera.png')));
    const pending = app.submit();
    await ready;
    const duringExtraction = imagePaste(file('segunda.png'));
    await app.paste(duringExtraction);
    assert.equal(duringExtraction.defaultPrevented, false);
    assert.equal(app.element('fileName').textContent, 'primera.png');
    assert.equal(app.extractionCalls(), 1);
    finish(payload('Primera'));
    await pending;
    app.setSheetsBusy(true);
    const duringSheets = imagePaste(file('tercera.png'));
    await app.paste(duringSheets);
    assert.equal(duringSheets.defaultPrevented, false);
    assert.equal(app.extractionCalls(), 1);
    assert.equal(app.element('fileName').textContent, 'primera.png');
    app.setSheetsBusy(false);
});

test('a failed pasted extraction saves corrections and preserves the edited receipt for retry', async () => {
    const app = createApp();
    await editedReceipt(app);
    const before = snapshot(app);
    app.setExtraction(async () => { throw new Error('OCR apagado'); });
    await app.paste(imagePaste(file('pegada.png')));
    assert.deepEqual(snapshot(app), before, 'pegar conserva el ticket sin iniciar otra extracción');
    assert.equal(app.extractionCalls(), 1);
    assert.equal(app.corrections.length, 0, 'las correcciones se guardan al presionar Extraer datos');
    await app.submit();
    assert.deepEqual(snapshot(app), before);
    assert.equal(app.corrections[0].corrections[0].descripcion, 'Descripción corregida');
    assert.match(app.element('status').textContent, /Se conserva el ticket anterior/);
    assert.equal(app.element('submitButton').disabled, false);
    app.setExtraction(async () => payload('Reintento'));
    await app.submit();
    assert.equal(app.items()[0].descripcion, 'Reintento');
});

test('copy buttons preserve the edited export and report success after the compatible clipboard writer completes', async () => {
    const copied = [];
    const app = createApp({copyToClipboard: async text => { copied.push(text); }});
    await editedReceipt(app);
    const before = snapshot(app);
    await app.element('copyTsv').dispatch('click');
    assert.deepEqual(copied, [before.tsv]);
    assert.match(app.element('copyToast').textContent, /Filas copiadas para Google Sheets/);
    assert.equal(app.element('copyToast').classList.contains('copy-toast-error'), false);
    await app.element('copyPipe').dispatch('click');
    assert.deepEqual(copied, [before.tsv, before.pipe]);
    assert.equal(app.element('copyToast').textContent, 'Texto copiado.');
    assert.deepEqual(snapshot(app), before);
});

test('clipboard failure preserves the export and reports manual copying without a false success', async () => {
    const app = createApp({copyToClipboard: async () => { throw new Error('clipboard blocked'); }});
    await editedReceipt(app);
    const before = snapshot(app);
    await app.element('copyTsv').dispatch('click');
    assert.match(app.element('copyToast').textContent, /No se pudo copiar/);
    assert.equal(app.element('copyToast').classList.contains('copy-toast-error'), true);
    assert.deepEqual(snapshot(app), before);
});
