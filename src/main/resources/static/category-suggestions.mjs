export const CATEGORIES = Object.freeze([
    'Limpieza', 'Supermercado', 'Farmacia', 'Kiosco', 'Carne', 'Otros', 'Verduleria', 'Perfumeria', 'Panaderia'
]);

const key = value => String(value ?? '').normalize('NFD').replace(/\p{M}/gu, '').trim().toLowerCase();

export function canonicalCategory(value) {
    const normalized = key(value);
    return CATEGORIES.find(category => key(category) === normalized)
        || (['lacteos', 'almacen', 'galletitas'].includes(normalized) ? 'Supermercado' : 'Otros');
}

export function createCategorySelect(value, {root = document, allowEmpty = false} = {}) {
    const select = root.createElement('select');
    const selected = allowEmpty && !String(value ?? '').trim() ? '' : canonicalCategory(value);
    for (const category of allowEmpty ? ['', ...CATEGORIES] : CATEGORIES) {
        const option = root.createElement('option');
        option.value = category;
        option.textContent = category || 'Sin corrección para este campo';
        option.defaultSelected = category === selected;
        select.append(option);
    }
    select.value = selected;
    return select;
}
