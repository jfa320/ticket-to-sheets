export const COLUMNS = [
    ['descripcion', 'Descripción'], ['marca', 'Marca'], ['lugarDeCompra', 'Lugar de compra'],
    ['categoria', 'Categoria'], ['cantidad', 'Cantidad'], ['precioUnitario', 'Precio unitario'], ['fecha', 'Fecha']
];

export function createReceipt(payload) {
    if (!payload || !Array.isArray(payload.items)) {
        throw new Error('La respuesta no contiene una lista de productos válida.');
    }
    return {
        storeName: payload.storeName || '',
        date: payload.date || '',
        rawText: payload.rawText || '',
        warnings: Array.isArray(payload.warnings) ? [...payload.warnings] : [],
        items: payload.items.map(item => ({...item, usar: true}))
    };
}

export function parseLocalizedNumber(value, {quantity = false} = {}) {
    if (typeof value === 'number') return Number.isFinite(value) ? value : null;
    let text = String(value ?? '').trim().replace(/\s/g, '');
    if (!quantity) text = text.replace(/^\$/, '');
    if (!text) return null;
    // El precio usa miles es-AR; la cantidad admite decimales OCR como 0.875 kg.
    if (!quantity && /^[+-]?\d{1,3}(?:\.\d{3})+(?:,\d+)?$/.test(text)) {
        text = text.replaceAll('.', '');
    }
    if (!/^[+-]?(?:\d+(?:[.,]\d+)?|[.,]\d+)$/.test(text)) return null;
    const number = Number(text.replace(',', '.'));
    return Number.isFinite(number) ? number : null;
}

export function calculateTotal(items) {
    return items.filter(item => item.usar !== false).reduce((sum, item) => {
        const price = parseLocalizedNumber(item.precioUnitario);
        const quantity = parseLocalizedNumber(item.cantidad, {quantity: true});
        return sum + (price ?? 0) * (quantity ?? 0);
    }, 0);
}

export function formatTotal(value) {
    return `$ ${value.toLocaleString('es-AR', {minimumFractionDigits: 2, maximumFractionDigits: 2})}`;
}

export function escapeDelimited(value, delimiter) {
    const text = String(value ?? '');
    return text.includes(delimiter) || /[\r\n"]/.test(text)
        ? `"${text.replaceAll('"', '""')}"` : text;
}

export function buildExports(items) {
    const selected = items.filter(item => item.usar !== false);
    const rows = selected.map(item => COLUMNS.map(([field]) => item[field]));
    const withHeader = [COLUMNS.map(([, label]) => label), ...rows];
    const join = (values, delimiter) => values
        .map(row => row.map(value => escapeDelimited(value, delimiter)).join(delimiter)).join('\n');
    return {
        pipe: join(withHeader, '|'), tsv: join(withHeader, '\t'), rowsOnly: join(rows, '\t'),
        count: selected.length, total: calculateTotal(selected)
    };
}

export function validationErrors(items) {
    return items.flatMap((item, index) => {
        if (item.usar === false) return [];
        const errors = [];
        if (!String(item.descripcion ?? '').trim()) errors.push(['descripcion', 'Completá la descripción.']);
        const quantity = parseLocalizedNumber(item.cantidad, {quantity: true});
        if (quantity === null || quantity < 0) errors.push(['cantidad', 'Ingresá una cantidad válida, mayor o igual a cero.']);
        if (parseLocalizedNumber(item.precioUnitario) === null) errors.push(['precioUnitario', 'Ingresá un precio válido (ej.: 1.500,50).']);
        if (!toDateInputValue(item.fecha)) errors.push(['fecha', 'Ingresá una fecha válida (día/mes/año).']);
        return errors.map(([field, message]) => ({index, field, message: `Fila ${index + 1}: ${message}`}));
    });
}

export function toDateInputValue(value) {
    const match = /^(\d{1,2})\/(\d{1,2})\/(\d{4})$/.exec(String(value ?? '').trim());
    if (!match) return '';
    const [, day, month, year] = match;
    const date = new Date(0);
    date.setUTCFullYear(Number(year), Number(month) - 1, Number(day));
    if (Number(year) < 1 || date.getUTCFullYear() !== Number(year)
        || date.getUTCMonth() !== Number(month) - 1 || date.getUTCDate() !== Number(day)) return '';
    return `${year}-${month.padStart(2, '0')}-${day.padStart(2, '0')}`;
}

export function formatDateForItems(value) {
    const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
    if (!match) return '';
    const [, year, month, day] = match;
    const date = `${Number(day)}/${Number(month)}/${year}`;
    return toDateInputValue(date) ? date : '';
}
