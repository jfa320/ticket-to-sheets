import {COLUMNS} from './receipt-model.mjs';

export function renderWarnings(warnings, panel, list) {
    list.replaceChildren();
    panel.classList.toggle('hidden', warnings.length === 0);
    warnings.forEach(warning => {
        const item = document.createElement('li');
        item.textContent = warning;
        list.append(item);
    });
}

export function renderItems(items, body, {onEdit, onSelect, onRemove}) {
    body.replaceChildren();
    items.forEach((item, index) => {
        const row = document.createElement('tr');
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
                input.addEventListener('input', () => onEdit(item, field, input.value.trim()));
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
                cell.addEventListener('input', () => onEdit(item, field, cell.textContent.trim()));
            }
            row.append(cell);
        }

        const stateCell = document.createElement('td');
        stateCell.dataset.label = 'Estado';
        const badge = document.createElement('span');
        const [state, label] = item.estado === 'AMBIGUOUS' ? ['ambiguous', 'Ambiguo']
            : item.estado === 'LEARNED' ? ['learned', 'Memorizado'] : ['correct', 'Correcto'];
        badge.className = `status-badge status-${state}`;
        badge.textContent = label;
        stateCell.append(badge);
        row.append(stateCell);

        const actionCell = document.createElement('td');
        actionCell.dataset.label = 'Acciones';
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
