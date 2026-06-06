const form = document.getElementById('uploadForm');
const fileInput = document.getElementById('fileInput');
const statusLabel = document.getElementById('status');
const results = document.getElementById('results');
const storeName = document.getElementById('storeName');
const dateValue = document.getElementById('dateValue');
const itemCount = document.getElementById('itemCount');
const csvOutput = document.getElementById('csvOutput');
const tsvOutput = document.getElementById('tsvOutput');
const rawOutput = document.getElementById('rawOutput');
const submitButton = document.getElementById('submitButton');
updateSubmitButton();

document.getElementById('copyPipe').addEventListener('click', () => copyText(csvOutput.value, 'CSV con | copiado.'));
document.getElementById('copyTsv').addEventListener('click', () => copyText(window.lastRowsOnly || '', 'Texto tabulado sin cabecera copiado.'));
document.getElementById('copyRowsOnly').addEventListener('click', () => copyText(window.lastRowsOnly || '', 'Filas sin cabecera copiadas.'));

fileInput.addEventListener('change', updateSelectedFileState);
fileInput.addEventListener('input', updateSelectedFileState);

form.addEventListener('submit', async (event) => {
    event.preventDefault();

    const file = fileInput.files[0];
    if (!file) {
        statusLabel.textContent = 'Elegí una factura antes de procesar.';
        return;
    }

    const data = new FormData();
    data.append('file', file);

    setLoading(true, 'Procesando PaddleOCR en Docker, esto puede tardar unos segundos...');

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
        storeName.textContent = payload.storeName || '-';
        dateValue.textContent = payload.date || '-';
        itemCount.textContent = payload.itemCount ?? 0;
        csvOutput.value = payload.csv || '';
        tsvOutput.value = payload.tsv || '';
        window.lastRowsOnly = payload.tsvWithoutHeader || '';
        rawOutput.value = payload.rawText || '';
        results.classList.remove('hidden');
        statusLabel.textContent = 'Listo. Copia la salida que prefieras.';
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
    const file = fileInput.files?.[0];
    updateSubmitButton();
    statusLabel.textContent = file ? `Archivo listo: ${file.name}` : 'Esperando archivo...';
}

function updateSubmitButton() {
    submitButton.disabled = !hasSelectedFile();
}

function hasSelectedFile() {
    return Boolean(fileInput.files?.length || fileInput.value);
}

async function copyText(value, successMessage) {
    if (!value) {
        statusLabel.textContent = 'Todavia no hay salida para copiar.';
        return;
    }

    await navigator.clipboard.writeText(value);
    statusLabel.textContent = successMessage;
}

function cleanError(message) {
    return (message || 'No se pudo procesar la factura.').trim();
}
