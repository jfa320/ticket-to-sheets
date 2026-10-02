import {
    createReceipt, buildExports, formatTotal, validationErrors, toDateInputValue, formatDateForItems
} from './receipt-model.mjs';
import {extractReceipt, saveCorrections, validateFile} from './receipt-api.mjs';
import {createCorrectionSaver} from './receipt-corrections.mjs';
import {renderItems, renderWarnings, markInvalidCells} from './receipt-view.mjs';

const byId = id => document.getElementById(id);
const form = byId('uploadForm');
const fileInput = byId('fileInput');
const status = byId('status');
const results = byId('results');
const submitButton = byId('submitButton');
const dropzone = document.querySelector('.dropzone');
const fileSummary = byId('fileSummary');
const fileName = byId('fileName');
const viewFile = byId('viewFile');
const replaceFile = byId('replaceFile');
const filePreviewDialog = byId('filePreviewDialog');
const filePreviewContent = byId('filePreviewContent');
const closeFilePreview = byId('closeFilePreview');
const storeName = byId('storeName');
const dateValue = byId('dateValue');
const itemsBody = byId('itemsBody');
const addItemButton = byId('addItem');
const retryCorrections = byId('retryCorrections');
const copyToast = byId('copyToast');
const undoToast = byId('undoToast');
const undoRemove = byId('undoRemove');
const copyButtons = [byId('copyPipe'), byId('copyTsv')];
let receipt = null;
let correctionSaver = null;
let output = buildExports([]);
let selectedFile = null;
let loading = false;
let copyToastTimer = null;
let undoTimer = null;
let removedItem = null;
let previewUrl = null;

function updateUploadControls() {
    submitButton.disabled = loading || Boolean(validateFile(selectedFile));
    viewFile.disabled = loading || !selectedFile;
    submitButton.textContent = loading ? 'Procesando…' : 'Extraer datos';
    fileInput.disabled = loading;
    form.setAttribute('aria-busy', String(loading));
    dropzone.setAttribute('aria-disabled', String(loading));
    results.inert = loading;
}

function selectFile(file) {
    if (loading) return;
    const error = validateFile(file);
    selectedFile = error ? null : file;
    status.textContent = error || `Archivo listo: ${file.name}`;
    fileSummary.classList.toggle('hidden', Boolean(error));
    fileName.textContent = error ? '' : file.name;
    prepareFilePreview(selectedFile);
    updateUploadControls();
}

function prepareFilePreview(file) {
    if (previewUrl) URL.revokeObjectURL(previewUrl);
    previewUrl = null;
    filePreviewContent.replaceChildren();
    if (!file) return;
    previewUrl = URL.createObjectURL(file);
    const isPdf = file.type === 'application/pdf' || /\.pdf$/i.test(file.name);
    if (isPdf) {
        const frame = document.createElement('iframe');
        frame.src = previewUrl;
        frame.title = `Vista previa de ${file.name}`;
        filePreviewContent.append(frame);
        return;
    }
    const image = document.createElement('img');
    image.src = previewUrl;
    image.alt = `Vista previa de ${file.name}`;
    filePreviewContent.append(image);
}

viewFile.addEventListener('click', () => {
    if (!selectedFile) return;
    if (typeof filePreviewDialog.showModal === 'function') filePreviewDialog.showModal();
    else filePreviewDialog.setAttribute('open', '');
});
closeFilePreview.addEventListener('click', () => filePreviewDialog.close());
filePreviewDialog.addEventListener('click', event => {
    if (event.target === filePreviewDialog) filePreviewDialog.close();
});

replaceFile.addEventListener('click', () => {
    if (!loading) fileInput.click();
});

fileInput.addEventListener('change', () => selectFile(fileInput.files?.[0]));
['dragenter', 'dragover', 'dragleave', 'drop'].forEach(eventName => {
    dropzone.addEventListener(eventName, event => {
        event.preventDefault();
        dropzone.classList.toggle('drag-over', !loading && ['dragenter', 'dragover'].includes(eventName));
        if (eventName === 'drop' && !loading) {
            // Mantener el File en estado evita depender de asignar FileList/DataTransfer.
            fileInput.value = '';
            selectFile(event.dataTransfer.files?.[0]);
        }
    });
});

form.addEventListener('submit', async event => {
    event.preventDefault();
    if (loading) return;
    const error = validateFile(selectedFile);
    if (error) { status.textContent = error; return; }
    const file = selectedFile;
    loading = true;
    updateUploadControls();
    status.textContent = 'Procesando la factura. Esto puede tardar unos segundos…';
    try {
        // Resolver ediciones pendientes antes de sustituir el ticket que las originó.
        if (correctionSaver && !await correctionSaver.flush()) {
            status.textContent = 'Reintentá el guardado de las correcciones antes de cargar otra factura.';
            return;
        }
        clearReceiptState();
        const nextReceipt = createReceipt(await extractReceipt(file));
        receipt = nextReceipt;
        correctionSaver = createCorrectionSaver(receipt, {
            save: saveCorrections,
            onStatus(state, message) {
                byId('learnStatus').textContent = message;
                retryCorrections.classList.toggle('hidden', state !== 'error');
            }
        });
        byId('learnStatus').textContent = '';
        retryCorrections.classList.add('hidden');
        storeName.value = receipt.storeName;
        dateValue.value = toDateInputValue(receipt.date);
        byId('rawOutput').value = receipt.rawText;
        renderTable();
        results.classList.remove('hidden');
        setActiveStep(1);
        if (receipt.warnings.length) {
            requestAnimationFrame(() => byId('warningsTitle').focus());
        }
        status.textContent = receipt.warnings.length
            ? `Listo. Hay ${receipt.warnings.length} advertencia(s) para revisar.`
            : 'Listo. Revisá las filas y copiá la salida.';
    } catch (error) {
        console.error('[facturas] Error al procesar la factura', error);
        status.textContent = `Error: ${error.message}${receipt ? ' Se conserva el ticket anterior.' : ''}`;
    } finally {
        loading = false;
        updateUploadControls();
    }
});

function clearReceiptState() {
    correctionSaver?.dispose();
    correctionSaver = null;
    receipt = null;
    output = buildExports([]);
    removedItem = null;
    clearTimeout(undoTimer);
    undoToast.classList.add('hidden');
    storeName.value = '';
    dateValue.value = '';
    byId('itemCount').textContent = '0';
    byId('totalValue').textContent = formatTotal(0);
    byId('rawOutput').value = '';
    byId('csvOutput').value = '';
    byId('tsvOutput').value = '';
    itemsBody.replaceChildren();
    byId('warningsList').replaceChildren();
    byId('warningsPanel').classList.add('hidden');
    byId('emptyItems').classList.add('hidden');
    document.querySelector('.table-scroll').classList.add('hidden');
    results.classList.add('hidden');
}

function refresh() {
    if (!receipt) return;
    output = buildExports(receipt.items);
    byId('itemCount').textContent = output.count;
    byId('totalValue').textContent = formatTotal(output.total);
    byId('csvOutput').value = output.pipe;
    byId('tsvOutput').value = output.rowsOnly;
    const errors = validationErrors(receipt.items);
    copyButtons.forEach(button => { button.disabled = output.count === 0 || errors.length > 0; });
    renderWarnings([...receipt.warnings, ...errors.map(error => error.message)], byId('warningsPanel'), byId('warningsList'));
    markInvalidCells(errors, itemsBody);
    byId('exportHint').setAttribute('role', errors.length ? 'alert' : 'status');
    byId('exportHint').textContent = errors.length
        ? 'Corregí los campos marcados o desmarcá esas filas antes de copiar.'
        : output.count ? 'Se copian únicamente las filas marcadas para usar.' : 'Seleccioná al menos una fila para copiar.';
}

function renderTable() {
    const hasItems = receipt.items.length > 0;
    byId('emptyItems').classList.toggle('hidden', hasItems);
    document.querySelector('.table-scroll').classList.toggle('hidden', !hasItems);
    renderItems(receipt.items, itemsBody, {
        onEdit(item, field, value) {
            item[field] = value;
            refresh();
            if (['descripcion', 'marca', 'categoria', 'lugarDeCompra'].includes(field)) correctionSaver.schedule();
        },
        onSelect(item, selected) {
            item.usar = selected;
            refresh();
            correctionSaver.schedule();
        },
        onRemove(item) {
            const index = receipt.items.indexOf(item);
            removedItem = {item, index};
            receipt.items.splice(index, 1);
            renderTable();
            correctionSaver.schedule();
            showUndoToast();
        }
    });
    refresh();
}

function addManualItem() {
    if (!receipt || loading) return;
    const baseItem = receipt.items.find(item => String(item.categoria ?? '').trim()) || {};
    const newItem = {
        descripcion: '',
        marca: '',
        lugarDeCompra: receipt.storeName || baseItem.lugarDeCompra || '',
        categoria: baseItem.categoria || '',
        cantidad: '',
        precioUnitario: '',
        fecha: receipt.date || baseItem.fecha || '',
        estado: 'CORRECT',
        firma: '',
        usar: true
    };
    receipt.items.push(newItem);
    renderTable();
    const descriptionCell = itemsBody.lastElementChild?.querySelector('[data-field="descripcion"]');
    descriptionCell?.focus();
}

function updateCommonField(field, value) {
    if (!receipt || loading) return;
    const cleaned = value.trim();
    if (field === 'fecha') receipt.date = cleaned;
    receipt.items.forEach(item => { item[field] = cleaned; });
    itemsBody.querySelectorAll(`[data-field="${field}"]`).forEach(cell => { cell.textContent = cleaned; });
    refresh();
    if (field === 'lugarDeCompra') correctionSaver.schedule();
}

storeName.addEventListener('input', () => updateCommonField('lugarDeCompra', storeName.value));
dateValue.addEventListener('change', () => updateCommonField('fecha', formatDateForItems(dateValue.value)));
retryCorrections.addEventListener('click', () => { void correctionSaver?.flush(); });
addItemButton.addEventListener('click', addManualItem);

byId('copyPipe').addEventListener('click', () => copyText(output.pipe, 'Texto copiado.'));
byId('copyTsv').addEventListener('click', () => copyText(output.rowsOnly, 'Filas copiadas para Google Sheets.'));
undoRemove.addEventListener('click', () => {
    if (!receipt || !removedItem) return;
    receipt.items.splice(removedItem.index, 0, removedItem.item);
    removedItem = null;
    clearTimeout(undoTimer);
    undoToast.classList.add('hidden');
    renderTable();
    correctionSaver.schedule();
});

async function copyText(value, message) {
    if (!receipt || output.count === 0 || validationErrors(receipt.items).length > 0) return;
    try {
        await navigator.clipboard.writeText(value);
        setActiveStep(2);
        showCopyToast(message);
    } catch {
        showCopyToast('No se pudo copiar. Podés seleccionar y copiar el texto de salida.', true);
    }
}

function showCopyToast(message, error = false) {
    clearTimeout(copyToastTimer);
    copyToast.textContent = message;
    copyToast.classList.toggle('copy-toast-error', error);
    copyToast.classList.add('copy-toast-visible');
    copyToastTimer = setTimeout(() => copyToast.classList.remove('copy-toast-visible'), 3000);
}

function showUndoToast() {
    clearTimeout(undoTimer);
    undoToast.classList.remove('hidden');
    undoTimer = setTimeout(() => {
        undoToast.classList.add('hidden');
        removedItem = null;
    }, 5000);
}

function setActiveStep(index) {
    document.querySelectorAll('.flow-step').forEach((step, stepIndex) => {
        step.classList.toggle('flow-step-active', stepIndex === index);
    });
}

updateUploadControls();
