const MAX_FILE_BYTES = 20 * 1024 * 1024;

export function validateFile(file) {
    if (!file) return 'Elegí una factura antes de procesar.';
    const isPdf = file.type === 'application/pdf' || /\.pdf$/i.test(file.name);
    const isImage = file.type?.startsWith('image/') || /\.(png|jpe?g|webp|bmp|gif|tiff?)$/i.test(file.name);
    if (!isPdf && !isImage) return 'Elegí una imagen o un PDF.';
    if (file.size === 0) return 'El archivo está vacío.';
    if (file.size > MAX_FILE_BYTES) return 'El archivo supera el límite de 20 MB.';
    return '';
}

async function request(url, options, errorMessage) {
    let response;
    try {
        response = await fetch(url, options);
    } catch (error) {
        console.error('[facturas] Error de red', {url, error});
        throw new Error('No se pudo conectar con la aplicación. Revisá la conexión y reintentá.');
    }
    const payload = await response.json().catch(() => null);
    if (!response.ok) {
        console.error('[facturas] Error de API', {url, status: response.status, payload});
        throw new Error(`${payload?.message || errorMessage}${payload?.requestId ? ` (id: ${payload.requestId})` : ''}`);
    }
    if (payload === null) {
        console.error('[facturas] Respuesta inválida de API', {url, status: response.status});
        throw new Error('La aplicación devolvió una respuesta inválida.');
    }
    return payload;
}

export function extractReceipt(file) {
    const data = new FormData();
    data.append('file', file);
    return request('/api/receipts/extract', {method: 'POST', body: data}, 'No se pudo procesar la factura.');
}

export function saveCorrections(batch) {
    return request('/api/corrections', {
        method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(batch)
    }, 'No se pudieron guardar las correcciones.');
}
