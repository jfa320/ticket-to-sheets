const form = document.getElementById('uploadForm');
const fileInput = document.getElementById('fileInput');
const statusLabel = document.getElementById('status');
const results = document.getElementById('results');
const storeName = document.getElementById('storeName');
const dateValue = document.getElementById('dateValue');
const itemCount = document.getElementById('itemCount');
const totalValue = document.getElementById('totalValue');
const csvOutput = document.getElementById('csvOutput');
const tsvOutput = document.getElementById('tsvOutput');
const rawOutput = document.getElementById('rawOutput');
const submitButton = document.getElementById('submitButton');
const itemsBody = document.getElementById('itemsBody');
const itemsEditor = document.getElementById('itemsEditor');
const emptyItems = document.getElementById('emptyItems');
const warningsPanel = document.getElementById('warningsPanel');
const warningsList = document.getElementById('warningsList');
const copyToast = document.getElementById('copyToast');
const dropzone = document.querySelector('.dropzone');
let editableItems = [];
let storeNameForLearn = '';
let originalsByFirma = new Map();
let learnTimer = null;
let copyToastTimer = null;
updateSubmitButton();

storeName.addEventListener('input', () => updateCommonField('lugarDeCompra', storeName.value));
dateValue.addEventListener('change', () => updateCommonField('fecha', formatDateForItems(dateValue.value)));

document.getElementById('copyPipe').addEventListener('click', () => copyText(csvOutput.value, 'Texto copiado.'));
document.getElementById('copyTsv').addEventListener('click', () => copyText(window.lastRowsOnly || '', 'Listo para pegar en Google Sheets.'));
document.getElementById('copyRowsOnly').addEventListener('click', () => copyText(window.lastRowsOnly || '', 'Filas copiadas.'));

fileInput.addEventListener('change', updateSelectedFileState);
fileInput.addEventListener('input', updateSelectedFileState);

['dragenter', 'dragover'].forEach(eventName => {
    dropzone.addEventListener(eventName, event => {
        event.preventDefault();
        dropzone.classList.add('drag-over');
    });
});

['dragleave', 'drop'].forEach(eventName => {
    dropzone.addEventListener(eventName, event => {
        event.preventDefault();
        dropzone.classList.remove('drag-over');
    });
});

dropzone.addEventListener('drop', event => {
    const file = event.dataTransfer.files?.[0];
    if (!file) {
        return;
    }

    const isPdf = file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf');
    if (!file.type.startsWith('image/') && !isPdf) {
        statusLabel.textContent = 'Elegí una imagen o un PDF.';
        return;
    }

    const transfer = new DataTransfer();
    transfer.items.add(file);
    fileInput.files = transfer.files;
    updateSelectedFileState();
});

form.addEventListener('submit', async (event) => {
    event.preventDefault();

    const file = getSelectedFile();
    if (!file) {
        submitButton.disabled = true;
        statusLabel.textContent = 'Elegí una factura antes de procesar.';
        return;
    }

    const data = new FormData();
    data.append('file', file);

    setLoading(true, 'Procesando la factura, esto puede tardar unos segundos...');

    try {
        const response = await fetch('/api/receipts/extract', {
            method: 'POST',
            body: data
        });

        if (!response.ok) {
            const errorPayload = await response.json().catch(() => null);
            throw new Error(errorPayload?.message || 'No se pudo procesar la factura.');
        }

        const payload = await response.json();
        storeName.value = payload.storeName || '';
        dateValue.value = toDateInputValue(payload.date);
        itemCount.textContent = payload.itemCount ?? 0;
        totalValue.textContent = formatTotal(payload.total);
        csvOutput.value = payload.csv || '';
        tsvOutput.value = payload.tsv || '';
        window.lastRowsOnly = payload.tsvWithoutHeader || '';
        rawOutput.value = payload.rawText || '';
        editableItems = (payload.items || []).map(item => ({...item}));
        originalsByFirma = new Map((payload.items || []).map(item => [item.firma, item]));
        storeNameForLearn = payload.storeName || '';
        clearTimeout(learnTimer);
        renderWarnings(payload.warnings || []);
        renderItems();
        results.classList.remove('hidden');
        statusLabel.textContent = payload.warnings?.length
            ? `Listo. Hay ${payload.warnings.length} advertencia(s) para revisar.`
            : 'Listo. Edita las filas si hace falta y copia la salida.';
    } catch (error) {
        statusLabel.textContent = `Error: ${cleanError(error.message)}`;
    } finally {
        setLoading(false);
    }
});

function setLoading(isLoading, message) {
    submitButton.disabled = isLoading || !hasSelectedFile();
    submitButton.textContent = isLoading ? 'Procesando...' : 'Extraer datos';
    if (message) {
        statusLabel.textContent = message;
    }
}

function updateSelectedFileState() {
    const file = getSelectedFile();
    updateSubmitButton();
    statusLabel.textContent = file ? `Archivo listo: ${file.name}` : 'Esperando archivo...';
}

function updateSubmitButton() {
    submitButton.disabled = !getSelectedFile();
}

function hasSelectedFile() {
    return Boolean(getSelectedFile());
}

function getSelectedFile() {
    return fileInput.files?.[0] || null;
}

function toDateInputValue(value) {
    const parts = (value || '').split('/');
    if (parts.length !== 3) {
        return '';
    }
    const [day, month, year] = parts;
    if (!day || !month || !year) {
        return '';
    }
    return `${year.padStart(4, '0')}-${month.padStart(2, '0')}-${day.padStart(2, '0')}`;
}

function formatDateForItems(value) {
    if (!value) {
        return '';
    }
    const [year, month, day] = value.split('-');
    return `${Number(day)}/${Number(month)}/${year}`;
}

async function copyText(value, successMessage) {
    if (!value) {
        statusLabel.textContent = 'Todavia no hay salida para copiar.';
        showCopyToast('Todavía no hay texto para copiar.', true);
        return;
    }

    try {
        await navigator.clipboard.writeText(value);
        statusLabel.textContent = successMessage;
        showCopyToast(successMessage);
    } catch (error) {
        statusLabel.textContent = 'No se pudo copiar el texto.';
        showCopyToast('No se pudo copiar el texto.', true);
    }
}

function showCopyToast(message, isError = false) {
    clearTimeout(copyToastTimer);
    copyToast.textContent = message;
    copyToast.classList.toggle('copy-toast-error', isError);
    copyToast.classList.add('copy-toast-visible');
    copyToastTimer = setTimeout(() => {
        copyToast.classList.remove('copy-toast-visible');
    }, 2200);
}

function cleanError(message) {
    return (message || 'No se pudo procesar la factura.').trim();
}

function renderWarnings(warnings) {
    warningsList.replaceChildren();
    warningsPanel.classList.toggle('hidden', warnings.length === 0);
    warnings.forEach(warning => {
        const item = document.createElement('li');
        item.textContent = warning;
        warningsList.append(item);
    });
}

const editableFields = ['descripcion', 'marca', 'lugarDeCompra', 'cantidad', 'precioUnitario', 'fecha'];

function renderItems() {
    itemsBody.replaceChildren();
    const hasItems = editableItems.length > 0;
    emptyItems.classList.toggle('hidden', hasItems);
    itemsEditor.classList.toggle('empty-editor', !hasItems);
    document.querySelector('.table-scroll').classList.toggle('hidden', !hasItems);
    editableItems.forEach((item, index) => {
        const row = document.createElement('tr');
        if (item.estado === 'AMBIGUOUS') {
            row.classList.add('ambiguous-row');
        }

        const useCell = document.createElement('td');
        const checkbox = document.createElement('input');
        checkbox.type = 'checkbox';
        checkbox.checked = item.usar !== false;
        checkbox.addEventListener('change', () => {
            item.usar = checkbox.checked;
            refreshExports();
        });
        useCell.append(checkbox);
        row.append(useCell);

        ['descripcion', 'marca', 'lugarDeCompra', 'categoria', 'cantidad', 'precioUnitario', 'fecha'].forEach(field => {
            const cell = document.createElement('td');
            cell.textContent = item[field] || '';
            cell.dataset.field = field;
            cell.dataset.index = String(index);
            if (editableFields.includes(field)) {
                cell.contentEditable = 'true';
                cell.classList.add('editable-cell');
                cell.addEventListener('input', () => {
                    item[field] = cell.textContent.trim();
                    refreshExports();
                    scheduleLearn();
                });
            } else {
                cell.classList.add('readonly-cell');
            }
            row.append(cell);
        });

        const stateCell = document.createElement('td');
        const badge = document.createElement('span');
        let badgeClass = 'status-correct';
        let badgeText = 'Correcto';
        if (item.estado === 'AMBIGUOUS') {
            badgeClass = 'status-ambiguous';
            badgeText = 'Ambiguo';
        } else if (item.estado === 'LEARNED') {
            badgeClass = 'status-learned';
            badgeText = 'Memorizado';
        }
        badge.className = `status-badge ${badgeClass}`;
        badge.textContent = badgeText;
        stateCell.append(badge);
        row.append(stateCell);

        const actionCell = document.createElement('td');
        const removeButton = document.createElement('button');
        removeButton.type = 'button';
        removeButton.className = 'remove-row';
        removeButton.textContent = 'Eliminar';
        removeButton.addEventListener('click', () => {
            editableItems.splice(index, 1);
            renderItems();
        });
        actionCell.append(removeButton);
        row.append(actionCell);
        itemsBody.append(row);
    });
    refreshExports();
}

function updateCommonField(field, value) {
    editableItems.forEach(item => {
        item[field] = value.trim();
    });
    if (field === 'lugarDeCompra') {
        storeNameForLearn = value.trim();
    }
    document.querySelectorAll(`[data-field="${field}"]`).forEach(cell => {
        cell.textContent = value.trim();
    });
    refreshExports();
    if (field === 'lugarDeCompra') {
        scheduleLearn();
    }
}

function refreshExports() {
    const selectedItems = editableItems.filter(item => item.usar !== false);
    itemCount.textContent = selectedItems.length;
    totalValue.textContent = formatTotal(calculateTotal(selectedItems));
    const headers = ['Descripción', 'Marca', 'Lugar de compra', 'Categoria', 'Cantidad', 'Precio unitario', 'Fecha'];
    const values = selectedItems.map(item => [
        item.descripcion, item.marca, item.lugarDeCompra, item.categoria,
        item.cantidad, item.precioUnitario, item.fecha
    ]);
    const rows = [headers, ...values];
    csvOutput.value = rows.map(row => row.map(value => escapeDelimited(value, '|')).join('|')).join('\n');
    tsvOutput.value = rows.map(row => row.map(value => escapeDelimited(value, '\t')).join('\t')).join('\n');
    window.lastRowsOnly = values.map(row => row.map(value => escapeDelimited(value, '\t')).join('\t')).join('\n');
}

function calculateTotal(items) {
    return items.reduce((sum, item) => {
        const unitPrice = parseLocalizedNumber(item.precioUnitario);
        const quantity = parseLocalizedNumber(item.cantidad) || 1;
        return sum + unitPrice * quantity;
    }, 0);
}

function parseLocalizedNumber(value) {
    const text = String(value ?? '')
        .trim()
        .replace(/\s/g, '')
        .replace(/\$/g, '');
    if (!text) {
        return 0;
    }
    const normalized = text.includes(',')
        ? text.replaceAll('.', '').replace(',', '.')
        : text;
    const number = Number(normalized);
    return Number.isFinite(number) ? number : 0;
}

function formatTotal(value) {
    const number = typeof value === 'number' ? value : parseLocalizedNumber(value);
    return `$ ${number.toLocaleString('es-AR', {minimumFractionDigits: 2, maximumFractionDigits: 2})}`;
}

function escapeDelimited(value, delimiter) {
    const safe = value == null ? '' : String(value);
    return safe.includes(delimiter) || safe.includes('\n') || safe.includes('"')
        ? `"${safe.replaceAll('"', '""')}"`
        : safe;
}

function scheduleLearn() {
    if (!storeNameForLearn) {
        return;
    }
    clearTimeout(learnTimer);
    learnTimer = setTimeout(sendCorrections, 2000);
}

function sendCorrections() {
    if (!storeNameForLearn) {
        return;
    }
    const corrections = [];
    editableItems.forEach(item => {
        if (!item.firma) {
            return;
        }
        const original = originalsByFirma.get(item.firma);
        if (!original) {
            return;
        }
        if (isGenericBrand(original.marca) || isGenericBrand(item.marca)) {
            return;
        }
        const diffs = {};
        ['descripcion', 'marca', 'categoria'].forEach(field => {
            if ((item[field] || '') !== (original[field] || '')) {
                diffs[field] = item[field] || '';
            }
        });
        if (Object.keys(diffs).length === 0) {
            return;
        }
        diffs.firma = item.firma;
        diffs.marcaOriginal = original.marca || '';
        corrections.push(diffs);
    });

    if (corrections.length === 0) {
        return;
    }

    fetch('/api/corrections', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ store: storeNameForLearn, corrections })
    }).then(response => {
        if (!response.ok) {
            throw new Error('No se pudieron aprender las correcciones.');
        }
        return response.json();
    }).then(() => {
        corrections.forEach(correction => {
            const original = originalsByFirma.get(correction.firma);
            if (!original) {
                return;
            }
            ['descripcion', 'marca', 'categoria'].forEach(field => {
                if (correction[field] !== undefined) {
                    original[field] = correction[field];
                }
            });
        });
        statusLabel.textContent = 'Correcciones aprendidas para próximos tickets.';
    }).catch(error => {
        statusLabel.textContent = `Aviso: ${cleanError(error.message)}`;
    });
}

function isGenericBrand(value) {
    return String(value || '')
        .normalize('NFD')
        .replace(/[\u0300-\u036f]/g, '')
        .trim()
        .toLowerCase() === 'generico';
}
