import {COLUMNS, parseLocalizedNumber} from './receipt-model.mjs';
import {createCategorySelect} from './category-suggestions.mjs';

function rowAction(label, rowNumber, icon) {
    const button = document.createElement('button');
    button.type = 'button';
    button.title = label;
    button.setAttribute('aria-label', `${label}, fila ${rowNumber}`);
    // Los trazos SVG son constantes locales; los datos del ticket nunca se insertan como HTML.
    button.innerHTML = `<svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${icon}</svg>`;
    return button;
}

export function renderDiscounts(discounts, panel, list, onChange) {
    list.replaceChildren();
    panel.classList.toggle('hidden', discounts.length === 0);
    discounts.forEach((discount, index) => {
        const row = document.createElement('li');
        row.className = 'discount-row';
        const useLabel = document.createElement('label');
        const use = document.createElement('input');
        use.type = 'checkbox';
        use.checked = discount.usar !== false;
        useLabel.append(use, document.createTextNode(discount.concepto || 'Bonificación'));
        use.addEventListener('change', () => { discount.usar = use.checked; validate(); onChange(); });
        const amountLabel = document.createElement('label');
        amountLabel.append(document.createTextNode('Importe'));
        const amount = document.createElement('input');
        amount.type = 'text';
        amount.inputMode = 'decimal';
        amount.value = discount.importe;
        amount.setAttribute('aria-label', `Importe de ${discount.concepto || `bonificación ${index + 1}`}`);
        const error = document.createElement('span');
        error.id = `discount-error-${index}`;
        error.className = 'discount-error';
        amount.setAttribute('aria-describedby', error.id);
        function validate() {
            const number = parseLocalizedNumber(discount.importe);
            const invalid = discount.usar !== false && (number === null || number > 0);
            amount.setAttribute('aria-invalid', String(invalid));
            error.textContent = invalid ? 'Ingresá un importe negativo válido.' : '';
        }
        amount.addEventListener('input', () => { discount.importe = amount.value.trim(); validate(); onChange(); });
        amountLabel.append(amount);
        row.append(useLabel, amountLabel, error);
        list.append(row);
        validate();
    });
}

export function renderWarnings(warnings, panel, list) {
    list.replaceChildren();
    panel.classList.toggle('hidden', warnings.length === 0);
    warnings.forEach(warning => {
        const item = document.createElement('li');
        item.textContent = warning;
        list.append(item);
    });
}

export function renderItems(items, body, {onEdit, onSelect, onRemove, onReview, canReview, onReviewed, pendingOnly = false}) {
    body.replaceChildren();
    items.forEach((item, index) => {
        if (pendingOnly && (item.estado !== 'AMBIGUOUS' || item.revisado)) return;
        const row = document.createElement('tr');
        function edited(field, value) {
            onEdit(item, field, value);
            row.querySelector('.status-reviewed')?.remove();
            const toggle = row.querySelector('[data-review-toggle]');
            if (toggle) {
                toggle.title = 'Marcar revisada';
                toggle.setAttribute('aria-label', `Marcar revisada, fila ${index + 1}`);
                toggle.setAttribute('aria-pressed', 'false');
            }
        }
        row.classList.toggle('ambiguous-row', item.estado === 'AMBIGUOUS');
        row.classList.toggle('excluded-row', item.usar === false);
        const useCell = document.createElement('td');
        const checkbox = document.createElement('input');
        checkbox.type = 'checkbox';
        checkbox.checked = item.usar !== false;
        checkbox.setAttribute('aria-label', `Usar fila ${index + 1}`);
        useCell.dataset.label = 'Usar';
        checkbox.addEventListener('change', () => {
            row.classList.toggle('excluded-row', !checkbox.checked);
            onSelect(item, checkbox.checked);
        });
        useCell.append(checkbox);
        row.append(useCell);

        for (const [field, label] of COLUMNS) {
            const cell = document.createElement('td');
            cell.dataset.field = field;
            cell.dataset.index = String(index);
            cell.className = 'editable-cell';
            cell.dataset.label = label;
            if (field === 'categoria') {
                const input = createCategorySelect(item[field]);
                input.className = 'editable-input';
                input.setAttribute('aria-label', `${label}, fila ${index + 1}`);
                input.addEventListener('change', () => edited(field, input.value));
                cell.append(input);
            } else {
                cell.textContent = item[field] ?? '';
                cell.contentEditable = 'plaintext-only';
                cell.setAttribute('role', 'textbox');
                cell.setAttribute('aria-label', `${label}, fila ${index + 1}`);
                cell.setAttribute('aria-multiline', 'false');
                if (field === 'cantidad' || field === 'precioUnitario') cell.inputMode = 'decimal';
                cell.addEventListener('keydown', event => {
                    if (event.key === 'Enter') { event.preventDefault(); cell.blur(); }
                });
                cell.addEventListener('input', () => edited(field, cell.textContent.trim()));
            }
            row.append(cell);
        }

        const stateCell = document.createElement('td');
        stateCell.dataset.label = 'Estado';
        const badge = document.createElement('span');
        const [state, label] = item.estado === 'AMBIGUOUS' ? ['ambiguous', 'Ambiguo']
            : item.estado === 'LEARNED' ? ['learned', 'Memorizado']
            : item.estado === 'HISTORY' ? ['history', 'Historial'] : ['correct', 'Correcto'];
        badge.className = `status-badge status-${state}`;
        badge.textContent = label;
        stateCell.append(badge);
        if (item.revisado) {
            const reviewed = document.createElement('span');
            reviewed.className = 'status-badge status-reviewed'; reviewed.textContent = 'Revisada';
            stateCell.append(reviewed);
        }
        if (item.memorizado && item.estado !== 'LEARNED') {
            const learnedBadge = document.createElement('span');
            learnedBadge.className = 'status-badge status-learned';
            learnedBadge.textContent = 'Memorizado';
            stateCell.append(learnedBadge);
        }
        row.append(stateCell);

        const actionCell = document.createElement('td');
        actionCell.dataset.label = 'Acciones';
        const actions = document.createElement('div');
        actions.className = 'row-actions';
        if (onReview) {
            const review = rowAction('Ver en imagen', index + 1,
                '<path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/>');
            review.className = 'row-review';
            review.disabled = !canReview?.(item); review.setAttribute('aria-label', `Ver fila ${index + 1} en la imagen`);
            review.title = review.disabled ? 'No hay coordenadas de imagen para esta fila.' : 'Ver en imagen';
            review.addEventListener('click', () => onReview(item)); actions.append(review);
        }
        if (onReviewed && item.estado === 'AMBIGUOUS') {
            const reviewed = rowAction(item.revisado ? 'Reabrir revisión' : 'Marcar revisada', index + 1,
                '<path d="m5 12 4 4L19 6"/>');
            reviewed.dataset.reviewToggle = 'true';
            reviewed.setAttribute('aria-pressed', String(Boolean(item.revisado)));
            reviewed.addEventListener('click', () => onReviewed(item)); actions.append(reviewed);
        }
        const removeButton = rowAction('Eliminar', index + 1,
            '<path d="M3 6h18M9 6V4h6v2M5 6l1 14h12l1-14M10 10v6M14 10v6"/>');
        removeButton.className = 'remove-row';
        removeButton.setAttribute('aria-label', `Eliminar fila ${index + 1}`);
        removeButton.addEventListener('click', () => onRemove(item));
        actions.append(removeButton);
        actionCell.append(actions);
        row.append(actionCell);
        body.append(row);
    });
}

export function markInvalidCells(errors, body) {
    const invalid = new Map(errors.map(error => [`${error.index}:${error.field}`, error.message]));
    body.ownerDocument.querySelectorAll('[data-validation-message="true"]').forEach(message => message.remove());
    body.querySelectorAll('[data-field]').forEach(cell => {
        const message = invalid.get(`${cell.dataset.index}:${cell.dataset.field}`);
        cell.setAttribute('aria-invalid', String(Boolean(message)));
        cell.title = message || '';
        if (message) {
            const messageId = `validation-error-${cell.dataset.index}-${cell.dataset.field}`;
            const accessibleMessage = body.ownerDocument.createElement('span');
            accessibleMessage.id = messageId;
            accessibleMessage.className = 'sr-only';
            accessibleMessage.dataset.validationMessage = 'true';
            accessibleMessage.textContent = message;
            body.ownerDocument.body.append(accessibleMessage);
            cell.setAttribute('aria-errormessage', messageId);
        } else {
            cell.removeAttribute('aria-errormessage');
        }
    });
}
