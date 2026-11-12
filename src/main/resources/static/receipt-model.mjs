import {zero, decimal, add, multiply, roundMoney, toNumber} from './receipt-decimal.mjs';
import {canonicalCategory} from './category-suggestions.mjs';

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
        category: canonicalCategory(payload.items.find(item => String(item.categoria ?? '').trim())?.categoria),
        rawText: payload.rawText || '',
        warnings: Array.isArray(payload.warnings) ? [...payload.warnings] : [],
        discounts: Array.isArray(payload.discounts) ? payload.discounts.map(discount => ({...discount, usar: true})) : [],
        items: payload.items.map(item => ({...item, categoria: canonicalCategory(item.categoria), usar: true}))
    };
}

function localizedDecimal(value, {quantity = false} = {}) {
    if (typeof value === 'number') return Number.isFinite(value) ? decimal(String(value)) : null;
    let text = String(value ?? '').trim().replace(/\s/g, '');
    if (!quantity) text = text.replace(/^\$/, '');
    if (!text) return null;
    // El precio usa miles es-AR; la cantidad admite decimales OCR como 0.875 kg.
    if (!quantity && /^[+-]?\d{1,3}(?:\.\d{3})+(?:,\d+)?$/.test(text)) {
        text = text.replaceAll('.', '');
    }
    if (!/^[+-]?(?:\d+(?:[.,]\d+)?|[.,]\d+)$/.test(text)) return null;
    const number = Number(text.replace(',', '.'));
    return Number.isFinite(number) ? decimal(text.replace(',', '.')) : null;
}

export function parseLocalizedNumber(value, options = {}) {
    const amount = localizedDecimal(value, options);
    return amount ? toNumber(amount) : null;
}

function exactTotal(items) {
    return items.filter(item => item.usar !== false).reduce((sum, item) => {
        const price = localizedDecimal(item.precioUnitario);
        const quantity = localizedDecimal(item.cantidad, {quantity: true});
        return add(sum, multiply(price ?? zero(), quantity ?? zero()));
    }, zero());
}

export function calculateTotal(items) {
    return toNumber(exactTotal(items));
}

export function formatTotal(value) {
    return `$ ${value.toLocaleString('es-AR', {minimumFractionDigits: 2, maximumFractionDigits: 2})}`;
}

export function calculateSummary(items, discounts = []) {
    const subtotal = roundMoney(exactTotal(items));
    const discountTotal = roundMoney(discounts.filter(discount => discount.usar !== false).reduce((sum, discount) => {
        const amount = localizedDecimal(discount.importe);
        return add(sum, amount !== null && amount.value <= 0n ? amount : zero());
    }, zero()));
    return {subtotal: toNumber(subtotal), discountTotal: toNumber(discountTotal), total: toNumber(add(subtotal, discountTotal))};
}

export function discountValidationErrors(discounts = []) {
    return discounts.flatMap((discount, index) => {
        if (discount.usar === false) return [];
        const amount = parseLocalizedNumber(discount.importe);
        return amount === null || amount > 0
            ? [`Bonificación ${index + 1}: ingresá un importe negativo válido (ej.: -8.800,00).`] : [];
    });
}

export function escapeDelimited(value, delimiter) {
    const text = String(value ?? '');
    return text.includes(delimiter) || /[\r\n"]/.test(text)
        ? `"${text.replaceAll('"', '""')}"` : text;
}

export function buildExportItems(items, discounts = [], context = {}) {
    const base = items.find(item => item.usar !== false) || items[0] || {};
    return [...items, ...discounts.map(discount => ({
        descripcion: 'Descuento', marca: 'Genérico',
        lugarDeCompra: context.storeName ?? base.lugarDeCompra ?? '',
        categoria: context.category ?? base.categoria ?? 'Otros', cantidad: '1',
        precioUnitario: discount.importe, fecha: context.date ?? base.fecha ?? '',
        usar: discount.usar !== false, kind: 'DISCOUNT'
    }))];
}

export function buildExports(items, discounts = [], context = {}) {
    const selected = buildExportItems(items, discounts, context).filter(item => item.usar !== false);
    const rows = selected.map(item => COLUMNS.map(([field]) => item[field]));
    const withHeader = [COLUMNS.map(([, label]) => label), ...rows];
    const join = (values, delimiter) => values
        .map(row => row.map(value => escapeDelimited(value, delimiter)).join(delimiter)).join('\n');
    return {
        pipe: join(withHeader, '|'), tsv: join(withHeader, '\t'), rowsOnly: join(rows, '\t'),
        count: selected.length, total: calculateSummary(items, discounts).total
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
        if (item.kind === 'DISCOUNT' && parseLocalizedNumber(item.precioUnitario) > 0) {
            errors.push(['precioUnitario', 'Ingresá un importe negativo para el descuento.']);
        }
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
