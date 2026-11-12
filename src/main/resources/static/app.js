import {
    createReceipt, buildExports, buildExportItems, formatTotal, validationErrors, toDateInputValue, formatDateForItems,
    calculateSummary, discountValidationErrors
} from './receipt-model.mjs';
import {saveCorrections, validateFile} from './receipt-api.mjs?v=2';
import {createReceiptExtraction} from './receipt-extraction.mjs?v=2';
import {createCorrectionSaver} from './receipt-corrections.mjs?v=2';
import {renderItems, renderWarnings, renderDiscounts, markInvalidCells} from './receipt-view.mjs?v=2';
import {createOcrReview} from './ocr-review.mjs';
import {createSheetsPanel} from './receipt-sheets.mjs?v=4';
import {createCorrectionManager} from './correction-manager.mjs';
import {copyToClipboard} from './receipt-clipboard.mjs';

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
const ocrReview = createOcrReview();
let receipt = null;
let correctionSaver = null;
let output = buildExports([]);
let selectedFile = null;
let loading = false;
let cancelling = false;
let uploadCancelled = false;
let acknowledgedOutput = null;
let correctionsPending = false;
let unloadWarningAttached = false;
const pageWindow = typeof window === 'undefined' ? null : window;
const extraction = createReceiptExtraction({pageWindow, onProgress(value) {
    if (!loading || cancelling) return;
    const phases = {UPLOADING: 'Recibiendo archivo', PREPARING: 'Preparando archivo', READING_PDF: 'Leyendo PDF',
        PDF_TEXT: 'Leyendo texto digital', OCR: 'Reconociendo texto', PARSING: 'Organizando productos'};
    const phase = phases[value?.phase];
    if (!phase) return;
    const total = Number.isInteger(value.pageCount) && value.pageCount > 0 ? value.pageCount : 0;
    const page = Number.isInteger(value.pageNumber) && value.pageNumber > 0 ? value.pageNumber : 0;
    byId('extractionPhase').textContent = `${total ? `Página ${Math.min(page, total)} de ${total} · ` : ''}${phase}…`;
    const progress = byId('extractionPages');
    if (total) { progress.max = total; progress.value = value.phase === 'PARSING' ? total : Math.max(0, page - 1); }
    else progress.removeAttribute('value');
}});
let copyToastTimer = null;
let undoTimer = null;
let removedItem = null;
let previewUrl = null;
let sheetsBusy = false;
createCorrectionManager({beforeOpen: async () => !correctionSaver || await correctionSaver.flush()});
byId('reviewFilter').addEventListener('change', () => { if (receipt) renderTable(); });
const sheetsPanel = createSheetsPanel({
    getItems: () => receipt ? buildExportItems(receipt.items, receipt.discounts, receipt) : [],
    onBusy(busy) { sheetsBusy = busy; updateUploadControls(); },
    onSuccess() {
        acknowledgedOutput = output.pipe;
        updateUnloadWarning();
        setActiveStep(2);
    }
});

function updateUploadControls() {
    const blocked = loading || sheetsBusy;
    submitButton.disabled = blocked || Boolean(validateFile(selectedFile));
    viewFile.disabled = blocked || !selectedFile;
    submitButton.textContent = loading ? 'Procesando…' : 'Extraer datos';
    fileInput.disabled = blocked;
    byId('readingMode').disabled = blocked;
    form.setAttribute('aria-busy', String(loading));
    dropzone.setAttribute('aria-disabled', String(blocked));
    results.inert = blocked;
    byId('cancelExtraction').classList.toggle('hidden', !loading);
    byId('cancelExtraction').disabled = !loading || cancelling;
    byId('cancelExtraction').textContent = cancelling ? 'Cancelando…' : 'Cancelar extracción';
    byId('extractionProgress').classList.toggle('hidden', !loading);
    sheetsPanel.setProcessing(loading);
}

function selectFile(file) {
    if (loading || sheetsBusy) return;
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
    if (!loading && !sheetsBusy) fileInput.click();
});

fileInput.addEventListener('change', () => selectFile(fileInput.files?.[0]));
document.addEventListener('paste', event => {
    if (event.defaultPrevented || loading || sheetsBusy) return;
    // Mantener el pegado habitual en los campos de edición y los diálogos.
    const target = event.target;
    if (target?.isContentEditable
            || target?.closest?.('input:not([type="file"]), textarea, select, [role="textbox"], dialog')) return;
    const clipboard = event.clipboardData;
    const image = Array.from(clipboard?.items ?? [])
        .find(item => item.kind === 'file' && item.type.startsWith('image/'));
    const file = image?.getAsFile()
        ?? Array.from(clipboard?.files ?? []).find(file => file.type.startsWith('image/'));
    if (!file) return;
    event.preventDefault();
    fileInput.value = '';
    selectFile(file);
});
['dragenter', 'dragover', 'dragleave', 'drop'].forEach(eventName => {
    dropzone.addEventListener(eventName, event => {
        event.preventDefault();
        dropzone.classList.toggle('drag-over', !loading && !sheetsBusy && ['dragenter', 'dragover'].includes(eventName));
        if (eventName === 'drop' && !loading && !sheetsBusy) {
            // Mantener el File en estado evita depender de asignar FileList/DataTransfer.
            fileInput.value = '';
            selectFile(event.dataTransfer.files?.[0]);
        }
    });
});

form.addEventListener('submit', async event => {
    event.preventDefault();
    if (loading || sheetsBusy) return;
    const error = validateFile(selectedFile);
    if (error) { status.textContent = error; return; }
    const file = selectedFile;
    const extractionStarted = Date.now();
    loading = true;
    uploadCancelled = false;
    cancelling = false;
    byId('extractionPhase').textContent = 'Preparando archivo…';
    byId('extractionPages').removeAttribute('value');
    updateUploadControls();
    status.textContent = 'Procesando la factura. Las imágenes grandes y los PDF pueden tardar varios minutos…';
    try {
        // Resolver ediciones pendientes antes de sustituir el ticket que las originó.
        if (correctionSaver && !await correctionSaver.flush()) {
            status.textContent = 'Reintentá el guardado de las correcciones antes de cargar otra factura.';
            return;
        }
        if (uploadCancelled) { const error = new Error('Extracción cancelada.'); error.name = 'AbortError'; throw error; }
        const payload = await extraction.extract(file, {searchBudgetSeconds:byId('readingMode').value === 'detailed' ? 90 : 8});
        if (uploadCancelled) { const error = new Error('Extracción cancelada.'); error.name = 'AbortError'; throw error; }
        const nextReceipt = createReceipt(payload);
        // Conservar el ticket y sus ediciones hasta recibir una respuesta válida.
        clearReceiptState();
        receipt = nextReceipt;
        correctionSaver = createCorrectionSaver(receipt, {
            save: saveCorrections,
            onStatus(state, message) {
                byId('learnStatus').textContent = message;
                retryCorrections.classList.toggle('hidden', state !== 'error');
                correctionsPending = ['pending', 'saving', 'error'].includes(state);
                updateUnloadWarning();
            }
        });
        correctionsPending = false;
        byId('learnStatus').textContent = '';
        retryCorrections.classList.add('hidden');
        storeName.value = receipt.storeName;
        dateValue.value = toDateInputValue(receipt.date);
        byId('rawOutput').value = receipt.rawText;
        ocrReview.setPages(payload.ocrReview);
        renderTable();
        results.classList.remove('hidden');
        setActiveStep(1);
        if (receipt.warnings.length) {
            requestAnimationFrame(() => byId('warningsTitle').focus());
        }
        const elapsed = ((Date.now() - extractionStarted) / 1000).toLocaleString('es-AR', {maximumFractionDigits:1});
        status.textContent = receipt.warnings.length
            ? `Listo en ${elapsed} s. Hay ${receipt.warnings.length} advertencia(s) para revisar.`
            : `Listo en ${elapsed} s. Revisá las filas y copiá la salida.`;
    } catch (error) {
        if (error.name === 'AbortError') {
            status.textContent = `Extracción cancelada.${receipt ? ' Se conserva el ticket anterior.' : ''}`;
            return;
        }
        console.error('[facturas] Error al procesar la factura', error);
        status.textContent = `Error: ${error.message}${receipt ? ' Se conserva el ticket anterior.' : ''}`;
    } finally {
        loading = false;
        updateUploadControls();
    }
});

byId('cancelExtraction').addEventListener('click', async () => {
    if (!loading || cancelling) return;
    cancelling = true; updateUploadControls();
    uploadCancelled = true;
    byId('extractionPhase').textContent = 'Solicitando cancelación…';
    try { await extraction.cancel(); }
    catch (error) { uploadCancelled = false; status.textContent = `${error.message} Podés reintentar la cancelación.`; }
    finally { cancelling = false; updateUploadControls(); }
});

function clearReceiptState() {
    ocrReview.clear();
    correctionSaver?.dispose();
    correctionSaver = null;
    receipt = null;
    acknowledgedOutput = null;
    correctionsPending = false;
    updateUnloadWarning();
    sheetsPanel.resetReceipt();
    output = buildExports([]);
    removedItem = null;
    clearTimeout(undoTimer);
    undoToast.classList.add('hidden');
    storeName.value = '';
    dateValue.value = '';
    byId('itemCount').textContent = '0';
    byId('totalValue').textContent = formatTotal(0);
    byId('discountsList').replaceChildren();
    byId('discountsPanel').classList.add('hidden');
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
    if (receipt) byId('reviewCount').textContent = `${receipt.items.filter(item => item.estado === 'AMBIGUOUS' && !item.revisado).length} fila(s) pendiente(s) de revisión.`;
    if (!receipt) return;
    sheetsPanel.refresh();
    output = buildExports(receipt.items, receipt.discounts, receipt);
    updateUnloadWarning();
    byId('itemCount').textContent = output.count;
    const summary = calculateSummary(receipt.items, receipt.discounts);
    byId('subtotalValue').textContent = formatTotal(summary.subtotal);
    byId('discountTotalValue').textContent = formatTotal(summary.discountTotal);
    byId('totalValue').textContent = formatTotal(summary.total);
    byId('csvOutput').value = output.pipe;
    byId('tsvOutput').value = output.rowsOnly;
    const errors = validationErrors(receipt.items);
    const discountErrors = discountValidationErrors(receipt.discounts);
    const hasErrors = errors.length > 0 || discountErrors.length > 0;
    copyButtons.forEach(button => { button.disabled = output.count === 0 || hasErrors; });
    renderWarnings([...receipt.warnings, ...errors.map(error => error.message), ...discountErrors], byId('warningsPanel'), byId('warningsList'));
    markInvalidCells(errors, itemsBody);
    byId('exportHint').setAttribute('role', hasErrors ? 'alert' : 'status');
    byId('exportHint').textContent = hasErrors
        ? 'Corregí los campos marcados o desmarcá esas filas antes de copiar.'
        : output.count ? 'Se copian las filas y bonificaciones marcadas para usar.' : 'Seleccioná al menos una fila para copiar.';
}

function renderTable() {
    renderDiscounts(receipt.discounts, byId('discountsPanel'), byId('discountsList'), refresh);
    const hasItems = receipt.items.length > 0;
    byId('emptyItems').classList.toggle('hidden', hasItems);
    document.querySelector('.table-scroll').classList.toggle('hidden', !hasItems);
    renderItems(receipt.items, itemsBody, {
        pendingOnly: byId('reviewFilter').value === 'pending',
        canReview: item => ocrReview.canShowSources(item.fuentesOcr),
        onReview: item => ocrReview.showSources(item.fuentesOcr),
        onReviewed(item) {
            if (!item.revisado && validationErrors([{...item, usar: true}]).length) {
                showCopyToast('Corregí los campos inválidos antes de marcar la fila como revisada.', true);
                return;
            }
            item.revisado = !item.revisado; renderTable();
        },
        onEdit(item, field, value) {
            item[field] = value;
            item.revisado = false;
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
    const pending = receipt.items.filter(item => item.estado === 'AMBIGUOUS' && !item.revisado).length;
    byId('reviewCount').textContent = `${pending} fila(s) pendiente(s) de revisión.`;
    byId('reviewEmpty').classList.toggle('hidden', byId('reviewFilter').value !== 'pending' || pending > 0);
    refresh();
}

function addManualItem() {
    if (!receipt || loading || sheetsBusy) return;
    const baseItem = receipt.items.find(item => String(item.categoria ?? '').trim()) || {};
    const newItem = {
        descripcion: '',
        marca: '',
        lugarDeCompra: receipt.storeName || baseItem.lugarDeCompra || '',
        categoria: baseItem.categoria || 'Otros',
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
    if (!receipt || loading || sheetsBusy) return;
    const cleaned = value.trim();
    if (field === 'fecha') receipt.date = cleaned;
    if (field === 'lugarDeCompra') receipt.storeName = cleaned;
    receipt.items.forEach(item => { item[field] = cleaned; item.revisado = false; });
    itemsBody.querySelectorAll(`[data-field="${field}"]`).forEach(cell => { cell.textContent = cleaned; });
    renderTable();
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
    if (!receipt || output.count === 0 || validationErrors(receipt.items).length > 0
            || discountValidationErrors(receipt.discounts).length > 0) return;
    const copiedOutput = output.pipe;
    try {
        await copyToClipboard(value);
        acknowledgedOutput = copiedOutput;
        updateUnloadWarning();
        setActiveStep(2);
        showCopyToast(message);
    } catch {
        showCopyToast('No se pudo copiar. Podés seleccionar y copiar el texto de salida.', true);
    }
}

function updateUnloadWarning() {
    if (!pageWindow) return;
    const hasPendingWork = Boolean(receipt)
        && (acknowledgedOutput === null || acknowledgedOutput !== output.pipe || correctionsPending);
    if (hasPendingWork && !unloadWarningAttached) {
        pageWindow.addEventListener('beforeunload', warnAboutPendingWork);
        unloadWarningAttached = true;
    } else if (!hasPendingWork && unloadWarningAttached) {
        pageWindow.removeEventListener('beforeunload', warnAboutPendingWork);
        unloadWarningAttached = false;
    }
}

function warnAboutPendingWork(event) {
    event.preventDefault();
    event.returnValue = true;
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
