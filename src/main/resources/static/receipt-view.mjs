import {COLUMNS, parseLocalizedNumber} from './receipt-model.mjs';

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
            if (toggle) { toggle.textContent = 'Marcar revisada'; toggle.setAttribute('aria-pressed', 'false'); }
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
                const input = document.createElement('input');
                input.type = 'text';
                input.className = 'editable-input';
                input.value = item[field] ?? '';
                input.setAttribute('aria-label', `${label}, fila ${index + 1}`);
                input.addEventListener('input', () => edited(field, input.value.trim()));
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
        if (onReview) {
            const review = document.createElement('button');
            review.type = 'button'; review.textContent = 'Ver en imagen'; review.className = 'row-review';
            review.disabled = !canReview?.(item); review.setAttribute('aria-label', `Ver fila ${index + 1} en la imagen`);
            review.title = review.disabled ? 'No hay coordenadas de imagen para esta fila.' : 'Comparar texto, cantidad y precio con el recorte';
            review.addEventListener('click', () => onReview(item)); actionCell.append(review);
        }
        if (onReviewed && item.estado === 'AMBIGUOUS') {
            const reviewed = document.createElement('button');
            reviewed.type = 'button'; reviewed.textContent = item.revisado ? 'Reabrir revisión' : 'Marcar revisada';
            reviewed.dataset.reviewToggle = 'true';
            reviewed.setAttribute('aria-pressed', String(Boolean(item.revisado)));
            reviewed.addEventListener('click', () => onReviewed(item)); actionCell.append(reviewed);
        }
        const removeButton = document.createElement('button');
        removeButton.type = 'button';
        removeButton.className = 'remove-row';
        removeButton.textContent = 'Eliminar';
        removeButton.setAttribute('aria-label', `Eliminar fila ${index + 1}`);
        removeButton.addEventListener('click', () => onRemove(item));
        actionCell.append(removeButton);
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
