import {createLearningBackup} from './learning-backup.mjs';

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
    const saves = new WeakMap(); let pendingAction = null, unloadAttached = false;
    const unsaved = root.getElementById('correctionsUnsaved');
    const dirtyForms = () => [...list.querySelectorAll('form[data-dirty="true"]')];
    function beforeUnload(event) { if (dirtyForms().length) { event.preventDefault(); event.returnValue = ''; } }
    function refreshDirty() {
        const needed = dirtyForms().length > 0;
        if (needed === unloadAttached) return;
        globalThis.window?.[needed ? 'addEventListener' : 'removeEventListener']('beforeunload', beforeUnload);
        unloadAttached = needed;
    }
    function navigate(action) {
        if (busy) return;
        if (!dirtyForms().length) { action(); return; }
        pendingAction = action; unsaved.hidden = false; unsaved.focus();
    }
    function dismissGuard() { pendingAction = null; unsaved.hidden = true; }
    root.getElementById('correctionsKeepEditing').addEventListener('click', () => {
        dismissGuard(); dirtyForms()[0]?.querySelector('input')?.focus();
    });
    root.getElementById('correctionsDiscard').addEventListener('click', () => {
        if (busy) return;
        const action = pendingAction;
        dirtyForms().forEach(form => { form.reset(); delete form.dataset.dirty; });
        dismissGuard(); refreshDirty(); action?.();
    });
    root.getElementById('correctionsSaveAll').addEventListener('click', async () => {
        if (busy) return;
        for (const form of dirtyForms()) if (!await saves.get(form)()) return;
        const action = pendingAction; dismissGuard(); refreshDirty(); action?.();
    });
    const backup = createLearningBackup({root, canAct() {
        if (busy || !ready) return false;
        if (list.querySelector('form[data-dirty="true"]')) {
            status.textContent = 'Guardá los cambios de las correcciones antes de usar el respaldo.'; return false;
        }
        return true;
    }, onBusy: value => lock(value), onRestored: () => load('Aprendizaje restaurado.')});
    function lock(value) {
        busy = value;
        el('Fields').disabled = value || !ready;
        list.querySelectorAll('fieldset').forEach(fieldset => { fieldset.disabled = value; });
        el('Previous').disabled = value || offset === 0;
        el('Next').disabled = value || offset + 25 >= total;
        el('Close').disabled = value;
        const backupFields = root.getElementById('learningFields');
        if (backupFields) backupFields.disabled = value || !ready;
        root.getElementById('correctionsUnsavedFields').disabled = value;
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
            input.type = 'text'; input.defaultValue = input.value = entry[key] || ''; input.maxLength = 1000;
            input.placeholder = 'Sin corrección para este campo';
            wrapper.append(root.createTextNode(label), input); fields.append(wrapper); inputs[key] = input;
            input.addEventListener('input', () => { form.dataset.dirty = 'true'; refreshDirty(); });
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
            navigate(async () => {
            lock(true); status.textContent = 'Eliminando corrección…';
            try {
                await correctionRequest('DELETE', {store: entry.store, firma: entry.firma, version: entry.version});
                if (offset > 0 && list.children.length === 1) offset = Math.max(0, offset - 25);
                await load('Corrección eliminada.');
            } catch (error) { status.textContent = error.message; } finally { lock(false); }
            });
        });
        async function saveForm() {
            if (busy || !form.reportValidity()) return false;
            lock(true); status.textContent = 'Guardando cambios…';
            try {
                const updated = await correctionRequest('PUT', {...entry,
                    ...Object.fromEntries(Object.entries(inputs).map(([key, input]) => [key, input.value.trim()]))});
                entry = updated; delete form.dataset.dirty; refreshDirty();
                for (const [key,input] of Object.entries(inputs)) input.defaultValue = entry[key] || '';
                status.textContent = 'Corrección guardada. Se aplicará a próximas extracciones.'; return true;
            } catch (error) { status.textContent = error.message; return false; } finally { lock(false); }
        }
        saves.set(form,saveForm);
        form.addEventListener('submit', event => { event.preventDefault(); void saveForm(); });
        return form;
    }
    async function load(message = '') {
        lock(true); status.textContent = 'Buscando correcciones…';
        try {
            const page = await correctionRequest('GET', {q: el('Query').value.trim(), offset, limit: 25});
            total = page.total; list.replaceChildren(...page.entries.map(card)); refreshDirty();
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
    el('Close').addEventListener('click', () => navigate(() => dialog.close()));
    dialog.addEventListener('cancel', event => {
        if (busy || dirtyForms().length) { event.preventDefault(); if (!busy) navigate(() => dialog.close()); }
    });
    dialog.addEventListener('close', () => { backup.clear(); dismissGuard(); refreshDirty(); });
    el('Search').addEventListener('submit', event => { event.preventDefault(); if (ready) navigate(() => { offset = 0; void load(); }); });
    el('Previous').addEventListener('click', () => { if (offset > 0) navigate(() => { offset -= 25; void load(); }); });
    el('Next').addEventListener('click', () => { if (offset + 25 < total) navigate(() => { offset += 25; void load(); }); });
    return {load};
}
