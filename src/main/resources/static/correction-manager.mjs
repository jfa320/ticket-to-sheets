export async function correctionRequest(method, data, fetcher = fetch) {
    const query = method === 'GET' ? `?${new URLSearchParams(data)}` : '';
    const response = await fetcher(`/api/corrections${query}`, {method,
        ...(method === 'GET' ? {} : {headers: {'Content-Type': 'application/json'}, body: JSON.stringify(data)})});
    const payload = await response.json().catch(() => null);
    if (!response.ok) throw new Error(response.status === 409
        ? 'La corrección cambió desde que la abriste. Buscá de nuevo para cargar su versión actual.'
        : payload?.message || 'No se pudo actualizar la memoria. Reintentá.');
    if (!payload) throw new Error('La aplicación devolvió una respuesta inválida.');
    return payload;
}

export function createCorrectionManager({root = document, beforeOpen = async () => true} = {}) {
    const el = name => root.getElementById(`corrections${name}`);
    const dialog = el('Dialog'), status = el('Status'), list = el('List');
    let offset = 0, total = 0, busy = false, ready = false;
    function lock(value) {
        busy = value;
        el('Fields').disabled = value || !ready;
        list.querySelectorAll('fieldset').forEach(fieldset => { fieldset.disabled = value; });
        el('Previous').disabled = value || offset === 0;
        el('Next').disabled = value || offset + 25 >= total;
        el('Close').disabled = value;
    }
    function card(entry) {
        const form = root.createElement('form'), fields = root.createElement('fieldset');
        const legend = root.createElement('legend'); legend.textContent = entry.store;
        fields.append(legend);
        const signature = root.createElement('p'); signature.className = 'helper';
        signature.textContent = `Línea OCR: ${entry.firma}`; fields.append(signature);
        const inputs = {};
        for (const [key, label] of [['descripcion', 'Descripción'], ['marca', 'Marca'], ['categoria', 'Categoría']]) {
            const wrapper = root.createElement('label'), input = root.createElement('input');
            input.type = 'text'; input.value = entry[key] || ''; input.maxLength = 1000; input.required = key !== 'categoria';
            wrapper.append(root.createTextNode(label), input); fields.append(wrapper); inputs[key] = input;
        }
        const actions = root.createElement('div'); actions.className = 'correction-actions';
        const save = root.createElement('button'); save.type = 'submit'; save.textContent = 'Guardar cambios';
        const remove = root.createElement('button'); remove.type = 'button'; remove.textContent = 'Eliminar';
        const cancel = root.createElement('button'); cancel.type = 'button'; cancel.textContent = 'Cancelar eliminación'; cancel.hidden = true;
        const detail = root.createElement('p'); detail.className = 'helper';
        detail.textContent = `Usada ${entry.veces} vez/veces · Actualizada ${entry.ultimaVez}`;
        actions.append(save, remove, cancel); fields.append(detail, actions); form.append(fields);
        let deleting = false;
        cancel.addEventListener('click', () => { deleting = false; remove.textContent = 'Eliminar'; cancel.hidden = true; });
        remove.addEventListener('click', async () => {
            if (busy) return;
            if (!deleting) { deleting = true; remove.textContent = 'Confirmar eliminación'; cancel.hidden = false; return; }
            lock(true); status.textContent = 'Eliminando corrección…';
            try {
                await correctionRequest('DELETE', {store: entry.store, firma: entry.firma, version: entry.version});
                if (offset > 0 && list.children.length === 1) offset = Math.max(0, offset - 25);
                await load('Corrección eliminada.');
            } catch (error) { status.textContent = error.message; } finally { lock(false); }
        });
        form.addEventListener('submit', async event => {
            event.preventDefault(); if (busy || !form.reportValidity()) return;
            lock(true); status.textContent = 'Guardando cambios…';
            try {
                const updated = await correctionRequest('PUT', {...entry,
                    ...Object.fromEntries(Object.entries(inputs).map(([key, input]) => [key, input.value.trim()]))});
                entry = updated; status.textContent = 'Corrección guardada. Se aplicará a próximas extracciones.';
            } catch (error) { status.textContent = error.message; } finally { lock(false); }
        });
        return form;
    }
    async function load(message = '') {
        lock(true); status.textContent = 'Buscando correcciones…';
        try {
            const page = await correctionRequest('GET', {q: el('Query').value.trim(), offset, limit: 25});
            total = page.total; list.replaceChildren(...page.entries.map(card));
            status.textContent = `${message} ${total ? `${offset + 1}–${offset + page.entries.length} de ${total} correcciones.` : 'No hay correcciones para esta búsqueda.'}`.trim();
        } catch (error) { status.textContent = error.message; } finally { lock(false); }
    }
    el('Open').addEventListener('click', async () => {
        if (busy) return;
        busy = true; el('Open').disabled = true;
        try {
            ready = await beforeOpen();
            dialog.showModal();
            if (!ready) {
                list.replaceChildren(); total = 0; offset = 0;
                status.textContent = 'No se pudieron guardar las correcciones pendientes del ticket. Cerrá esta ventana y reintentá ese guardado antes de administrar la memoria.';
                return;
            }
            offset = 0; await load();
        } catch (error) { status.textContent = error.message; }
        finally { el('Open').disabled = false; lock(false); }
    });
    el('Close').addEventListener('click', () => { if (!busy) dialog.close(); });
    dialog.addEventListener('cancel', event => { if (busy) event.preventDefault(); });
    el('Search').addEventListener('submit', event => { event.preventDefault(); if (!busy && ready) { offset = 0; void load(); } });
    el('Previous').addEventListener('click', () => { if (!busy && offset > 0) { offset -= 25; void load(); } });
    el('Next').addEventListener('click', () => { if (!busy && offset + 25 < total) { offset += 25; void load(); } });
    return {load};
}
