export async function learningRequest(action, data, fetcher = fetch) {
    const response = await fetcher(`/api/learning/${action}`, data === undefined ? {} : {
        method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(data)
    });
    const payload = await response.json().catch(() => null);
    if (!response.ok) throw new Error(payload?.message || 'No se pudo completar la operación del respaldo.');
    if (!payload) throw new Error('La respuesta del respaldo no es válida.');
    return payload;
}

export function createLearningBackup({root = document, canAct = () => true, onBusy = () => {}, onRestored = async () => {}} = {}) {
    const el = suffix => root.getElementById(`learning${suffix}`);
    if (!el('File')) return {clear() {}};
    let backup = null, preview = null, busy = false;
    async function limits() {
        const policy = await learningRequest('limits');
        if (!Number.isInteger(policy.maxFileBytes) || policy.maxFileBytes <= 0 || policy.maxFileBytes > 32_000_000)
            throw new Error('No se pudieron comprobar los límites del respaldo. Reintentá.');
        return policy;
    }
    function clear() {
        backup = null; preview = null; el('File').value = ''; el('Preview').replaceChildren();
        el('Restore').disabled = true; el('Cancel').hidden = true;
    }
    async function perform(action) {
        if (busy || !canAct()) return;
        busy = true; onBusy(true);
        try { await action(); }
        catch (error) { el('Status').textContent = error.message; }
        finally { busy = false; onBusy(false); el('Restore').disabled = !preview; }
    }
    el('Export').addEventListener('click', () => { void perform(async () => {
        el('Status').textContent = 'Preparando respaldo…';
        const policy = await limits();
        const document = await learningRequest('backup');
        const blob = new Blob([JSON.stringify(document, null, 2)], {type: 'application/json'});
        if (blob.size > policy.maxFileBytes) throw new Error('El aprendizaje supera el tamaño restaurable. No se descargó un respaldo incompleto.');
        const url = URL.createObjectURL(blob);
        const link = root.createElement('a'); link.href = url;
        link.download = `facturas-aprendizaje-${new Date().toISOString().slice(0, 10)}.json`;
        root.body.append(link); link.click(); link.remove(); setTimeout(() => URL.revokeObjectURL(url), 0);
        el('Status').textContent = 'Respaldo descargado. Incluye correcciones y marcas; no contiene credenciales de Sheets.';
    }); });
    el('File').addEventListener('change', () => {
        const file = el('File').files?.[0];
        clear();
        if (!file) return;
        void perform(async () => {
            const policy = await limits();
            if (file.size > policy.maxFileBytes) throw new Error(`El respaldo supera el límite de ${policy.maxFileBytes / 1_000_000} MB.`);
            el('Status').textContent = 'Revisando respaldo…';
            let candidate;
            try { candidate = JSON.parse(await file.text()); }
            catch { throw new Error('Elegí un archivo JSON de respaldo válido.'); }
            const result = await learningRequest('preview', candidate);
            if (!/^[a-f0-9]{64}$/.test(result.expectedVersion) || !/^[a-f0-9]{64}$/.test(result.backupHash)) {
                throw new Error('La vista previa del respaldo no es válida.');
            }
            backup = candidate; preview = result;
            const summary = root.createElement('p');
            summary.textContent = `${file.name}: ${result.correctionsToAdd} correcciones y ${result.brandsToAdd} marcas nuevas. `
                + `${result.correctionsKept} correcciones locales se conservan (${result.conflicts} diferencias).`;
            const mergePolicy = root.createElement('p'); mergePolicy.className = 'helper';
            mergePolicy.textContent = 'Se agregan los datos nuevos. Tus correcciones actuales tienen prioridad y no se eliminan.';
            const sample = root.createElement('ul');
            for (const entry of Array.isArray(result.sample) ? result.sample : []) {
                const item = root.createElement('li'); item.textContent = `${entry.store}: ${entry.descripcion}`; sample.append(item);
            }
            el('Preview').replaceChildren(summary, mergePolicy, sample); el('Cancel').hidden = false;
            el('Status').textContent = 'Revisá la vista previa y presioná Restaurar aprendizaje para aplicar los cambios.';
        });
    });
    el('Restore').addEventListener('click', () => { void perform(async () => {
        if (!backup || !preview) return;
        el('Status').textContent = 'Restaurando aprendizaje…';
        const result = await learningRequest('restore', {backup, expectedVersion: preview.expectedVersion, backupHash: preview.backupHash});
        clear(); await onRestored();
        el('Status').textContent = `Restauración completada: ${result.correctionsAdded} correcciones y ${result.brandsAdded} marcas agregadas. `
                + `${result.correctionsKept} correcciones locales conservadas.`;
    }); });
    el('Cancel').addEventListener('click', () => { if (!busy) { clear(); el('Status').textContent = 'Restauración cancelada. No se aplicaron cambios.'; } });
    return {clear};
}
