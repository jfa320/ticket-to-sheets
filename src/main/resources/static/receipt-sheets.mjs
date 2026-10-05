import {COLUMNS, validationErrors, toDateInputValue, formatDateForItems} from './receipt-model.mjs';
import {MONTH_NAMES, createMonthlyDestination} from './monthly-sheets.mjs?v=2';

export function spreadsheetIdFromInput(value) {
    const text = String(value ?? '').trim();
    if (/^[A-Za-z0-9_-]{20,200}$/.test(text)) return text;
    try {
        const url = new URL(text);
        if (url.protocol !== 'https:' || url.hostname !== 'docs.google.com' || url.username || url.password || url.port) return '';
        return /^\/spreadsheets\/d\/([A-Za-z0-9_-]{20,200})(?:\/.*)?$/.exec(url.pathname)?.[1] || '';
    } catch { return ''; }
}

export function sheetsConfigErrors(config) {
    const errors = [];
    if (!spreadsheetIdFromInput(config.spreadsheetUrlOrId)) {
        errors.push({field: 'spreadsheetUrlOrId', message: 'Ingresá el enlace de Google Sheets o el ID de la planilla.'});
    }
    const year = Number(config.year);
    if (!Number.isInteger(year) || year < 1900 || year > 9999) {
        errors.push({field: 'year', message: 'Ingresá el año de la planilla entre 1900 y 9999.'});
    }
    const row = Number(config.headerRow);
    if (!Number.isInteger(row) || row < 1 || row > 1000) {
        errors.push({field: 'headerRow', message: 'Ingresá una fila de encabezados entre 1 y 1000.'});
    }
    return errors;
}

export function buildSheetsItems(items) {
    const errors = validationErrors(items);
    if (errors.length) throw new Error(errors[0].message);
    const selected = items.filter(item => item.usar !== false);
    if (!selected.length) throw new Error('Seleccioná al menos una fila para cargar.');
    return selected.map(item => {
        const row = Object.fromEntries(COLUMNS.map(([field]) => {
            let value = item[field];
            // Conservar el formato es-AR del editor: 1.500 significa mil quinientos.
            if (field === 'precioUnitario' && typeof value === 'number') value = String(value).replace('.', ',');
            if (field === 'fecha') value = formatDateForItems(toDateInputValue(value));
            return [field, String(value ?? '').trim()];
        }));
        return row;
    });
}

export function sheetsSnapshotKey(config, items, destination = config) {
    return JSON.stringify({
        spreadsheetId: config.spreadsheetId || spreadsheetIdFromInput(config.spreadsheetUrlOrId),
        sheetName: String(destination.sheetName ?? '').trim(), headerRow: Number(config.headerRow),
        items: buildSheetsItems(items)
    });
}

export function createSheetsTransfer({append, newRequestId = () => crypto.randomUUID()} = {}) {
    const attempts = new Map();
    let pending = null;
    return {
        get busy() { return Boolean(pending); },
        get hasSuccess() { return [...attempts.values()].some(attempt => attempt.status === 'success'); },
        get hasUnconfirmed() { return [...attempts.values()].some(attempt => attempt.status === 'error'); },
        state(config, items, destination = config) {
            const key = sheetsSnapshotKey(config, items, destination);
            return {...(attempts.get(key) || {status: 'ready'}), key};
        },
        send(config, items, destination = config) {
            const key = sheetsSnapshotKey(config, items, destination);
            const previous = attempts.get(key);
            if (previous?.status === 'success') return Promise.resolve(previous.result);
            if (pending) {
                if (pending.key === key) return pending.promise;
                return Promise.reject(new Error('Esperá a que termine la carga actual.'));
            }
            const attempt = previous || {requestId: newRequestId()};
            attempt.status = 'pending';
            attempt.error = null;
            attempts.set(key, attempt);
            const payload = {requestId: attempt.requestId, items: buildSheetsItems(items), destination: {
                spreadsheetId: config.spreadsheetId || spreadsheetIdFromInput(config.spreadsheetUrlOrId),
                sheetName: String(destination.sheetName).trim(), headerRow: Number(config.headerRow)
            }, configVersion: config.configVersion};
            const promise = Promise.resolve().then(() => append(payload)).then(result => {
                attempt.status = 'success';
                attempt.result = result;
                return result;
            }, error => {
                attempt.status = 'error';
                attempt.error = error;
                throw error;
            }).finally(() => { pending = null; });
            pending = {key, promise};
            return promise;
        },
        reset() {
            if (pending) throw new Error('Esperá a que termine la carga actual.');
            attempts.clear();
        }
    };
}

export function createSheetsApi(fetchImpl = (...args) => fetch(...args)) {
    async function request(path, method = 'GET', body) {
        let response;
        try {
            response = await fetchImpl(`/api/sheets/${path}`, {
                method, ...(body === undefined ? {} : {
                    headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)
                })
            });
        } catch {
            throw new Error('No se pudo conectar con la aplicación. Reintentá la operación.');
        }
        const payload = await response.json().catch(() => null);
        if (!response.ok) throw new Error(payload?.message || 'No se pudo completar la operación con Google Sheets.');
        if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
            throw new Error('La aplicación devolvió una respuesta inválida. Reintentá la operación.');
        }
        return payload;
    }
    return {
        getConfig: () => request('config'),
        saveConfig: config => request('config', 'PUT', config),
        check: () => request('check', 'POST', {}),
        getHistory: () => request('history'),
        syncHistory: config => request('history/sync', 'POST', {
            spreadsheetId: config.spreadsheetId, headerRow: config.headerRow, configVersion: config.configVersion
        }),
        removeHistory: () => request('history', 'DELETE'),
        async append(payload) {
            const result = await request('append', 'POST', payload);
            if (result.appendedRows !== payload.items.length || typeof result.updatedRange !== 'string'
                || typeof result.duplicate !== 'boolean' || typeof result.sheetName !== 'string'
                || !spreadsheetIdFromInput(result.spreadsheetUrl)) {
                throw new Error('La aplicación devolvió una confirmación inválida. Reintentá la misma carga.');
            }
            return result;
        }
    };
}

export function historyStatusText(history) {
    if (!history?.active) return history?.message || 'No se está usando un historial de compras.';
    const parts = [`Historial activo: ${history.productCount} productos de ${history.rowCount} filas.`];
    if (history.sheets.length) parts.push(`Pestañas: ${history.sheets.join(', ')}.`);
    const updated = new Date(history.updatedAt);
    if (history.updatedAt && !Number.isNaN(updated.getTime())) {
        parts.push(`Actualizado: ${new Intl.DateTimeFormat('es-AR', {
            dateStyle: 'short', timeStyle: 'short', timeZone: 'America/Buenos_Aires'
        }).format(updated)}.`);
    }
    if (history.skippedSheets.length) parts.push(`Pestañas sin formato compatible: ${history.skippedSheets.join(', ')}.`);
    return parts.join(' ');
}

export function createSheetsHistory({api}) {
    let current = null;
    let pending = null;
    function run(operation, request) {
        if (pending) {
            if (operation === 'load' && pending.operation === 'load') return pending.promise;
            return Promise.reject(new Error('Esperá a que termine la operación con el historial.'));
        }
        const promise = Promise.resolve().then(request).then(result => {
            if (typeof result?.active !== 'boolean' || !Number.isInteger(result.rowCount) || result.rowCount < 0
                || !Number.isInteger(result.productCount) || result.productCount < 0
                || !Array.isArray(result.sheets) || !result.sheets.every(name => typeof name === 'string')
                || !Array.isArray(result.skippedSheets) || !result.skippedSheets.every(name => typeof name === 'string')) {
                throw new Error('La aplicación devolvió un estado de historial inválido. Reintentá la operación.');
            }
            current = {...result, sheets: [...result.sheets], skippedSheets: [...result.skippedSheets]};
            return current;
        }).finally(() => { pending = null; });
        pending = {operation, promise};
        return promise;
    }
    return {
        get current() { return current; },
        get busy() { return Boolean(pending); },
        load: () => run('load', () => api.getHistory()),
        sync: config => {
            const destination = {spreadsheetId: config.spreadsheetId, headerRow: config.headerRow,
                configVersion: config.configVersion};
            return run('sync', () => api.syncHistory(destination));
        },
        remove: () => run('remove', () => api.removeHistory())
    };
}

export function createSheetsPanel({getItems, onBusy, onSuccess, api = createSheetsApi()}) {
    const byId = id => document.getElementById(id);
    const fields = {
        spreadsheetUrlOrId: byId('sheetsSpreadsheet'), year: byId('sheetsYear'), headerRow: byId('sheetsHeaderRow')
    };
    const errorFields = {
        spreadsheetUrlOrId: byId('sheetsSpreadsheetError'), year: byId('sheetsYearError'), headerRow: byId('sheetsHeaderRowError')
    };
    const configStatus = byId('sheetsConfigStatus');
    const appendStatus = byId('sheetsAppendStatus');
    const appendButton = byId('sheetsAppend');
    const destinationSelect = byId('sheetsDestinationSelect');
    const destinationStatus = byId('sheetsDestinationStatus');
    const monthMappings = byId('sheetsMonthMappings');
    const settingsDialog = byId('sheetsSettingsDialog');
    const monthlyDestination = createMonthlyDestination();
    const transfer = createSheetsTransfer({append: payload => api.append(payload)});
    const history = createSheetsHistory({api});
    const historyStatus = byId('sheetsHistoryStatus');
    let historyMessage = '';
    let historyError = false;
    let saved = null;
    let checked = false;
    let availableSheets = [];
    let suggestedMonthSheets = {};
    let busy = false;
    let processing = false;
    let loadingConfig = true;
    const readMonthSheets = () => Object.fromEntries([...monthMappings.querySelectorAll('[data-month-sheet]')]
        .map(select => [select.dataset.monthSheet, select.value]).filter(([, value]) => value));
    const draft = () => ({spreadsheetUrlOrId: fields.spreadsheetUrlOrId.value.trim(),
        year: Number(fields.year.value), headerRow: Number(fields.headerRow.value), monthSheets: readMonthSheets()});
    const normalizedConfig = config => JSON.stringify({
        spreadsheetId: config?.spreadsheetId || spreadsheetIdFromInput(config?.spreadsheetUrlOrId),
        year: Number(config?.year), headerRow: Number(config?.headerRow),
        monthSheets: Object.fromEntries(Object.entries(config?.monthSheets || {}).filter(([, value]) => value))
    });
    const matchesSaved = () => Boolean(saved?.spreadsheetId) && normalizedConfig(draft()) === normalizedConfig(saved);

    function feedback(element, message, error = false) {
        element.textContent = message;
        element.classList.toggle('sheets-error', error);
        element.setAttribute('role', error ? 'alert' : 'status');
    }

    function setBusy(value) {
        busy = value;
        onBusy(value);
        refresh();
    }

    function showAccount(config) {
        byId('sheetsAccountStatus').textContent = config.credentialsConfigured
            ? 'Cuenta de servicio configurada en la aplicación.' : config.message || 'Falta configurar la cuenta de servicio de Google.';
        byId('sheetsServiceAccount').textContent = config.serviceAccountEmail || '';
        byId('sheetsShareHint').classList.toggle('hidden', !config.serviceAccountEmail);
        const link = byId('sheetsDestinationLink');
        const validId = spreadsheetIdFromInput(config.spreadsheetId);
        link.classList.toggle('hidden', !validId);
        if (validId) link.href = `https://docs.google.com/spreadsheets/d/${validId}/edit`;
        else link.removeAttribute('href');
    }

    function renderMonthMappings(values = saved?.monthSheets || {}) {
        monthMappings.replaceChildren();
        const legend = document.createElement('legend');
        legend.textContent = 'Asociar pestañas por mes';
        monthMappings.append(legend);
        MONTH_NAMES.forEach((name, index) => {
            const month = String(index + 1);
            const label = document.createElement('label');
            label.className = 'sheets-month-field';
            const caption = document.createElement('span');
            caption.textContent = name;
            const select = document.createElement('select');
            select.dataset.monthSheet = month;
            const automatic = document.createElement('option');
            automatic.value = '';
            automatic.textContent = suggestedMonthSheets[month]
                ? `Detectar por nombre (${suggestedMonthSheets[month]})` : 'Detectar por nombre';
            select.append(automatic);
            const selected = String(values?.[month] || '');
            if (selected && !availableSheets.includes(selected)) {
                const unavailable = document.createElement('option');
                unavailable.value = selected;
                unavailable.textContent = `${selected} (sin comprobar)`;
                unavailable.disabled = true;
                select.append(unavailable);
            }
            for (const sheetName of availableSheets) {
                const option = document.createElement('option');
                option.value = sheetName;
                option.textContent = sheetName;
                select.append(option);
            }
            select.value = selected;
            select.addEventListener('change', refresh);
            label.append(caption, select);
            monthMappings.append(label);
        });
        monthMappings.disabled = busy || processing || loadingConfig || !checked;
    }

    function refreshHistory() {
        const blocked = busy || processing || loadingConfig || history.busy;
        byId('sheetsHistorySync').disabled = blocked || !matchesSaved() || !saved?.credentialsConfigured || !checked;
        const removeButton = byId('sheetsHistoryRemove');
        removeButton.classList.toggle('hidden', !history.current?.active);
        removeButton.disabled = blocked || !history.current?.active;
        byId('sheetsHistoryPanel').setAttribute('aria-busy', String(history.busy));
        const parts = [];
        if (historyMessage) parts.push(historyMessage);
        if (history.current) {
            parts.push(historyStatusText(history.current));
            if (history.current.active && saved?.spreadsheetId && history.current.spreadsheetId !== saved.spreadsheetId) {
                parts.push('El historial activo corresponde a otra planilla. Actualizalo para usar la planilla de destino.');
            }
        } else if (!historyMessage) parts.push('Consultando el historial…');
        if (!checked && !history.current?.active && !historyError && !history.busy) {
            parts.push('Comprobá la conexión para actualizar el historial.');
        }
        feedback(historyStatus, parts.join(' '), historyError);
    }

    async function loadHistory() {
        try {
            await history.load();
            historyMessage = '';
            historyError = false;
        } catch (error) {
            historyMessage = `No se pudo consultar el historial: ${error.message}`;
            historyError = true;
        } finally { refreshHistory(); }
    }

    function destinationState() {
        return monthlyDestination.resolve(saved, availableSheets, getItems(), suggestedMonthSheets);
    }

    function refreshDestination() {
        const blocked = busy || processing || loadingConfig;
        let target = {sheetName: '', label: '', message: 'Configurá y comprobá el acceso a Google Sheets.'};
        if (saved) target = destinationState();
        const auto = destinationSelect.querySelector('option[value=""]');
        if (auto) auto.textContent = target.mode === 'automatic' && target.sheetName
            ? `Automático · ${target.label}` : 'Automático según la fecha del ticket';
        const selected = target.mode === 'manual' ? target.sheetName : '';
        destinationSelect.value = selected;
        destinationSelect.disabled = blocked || !matchesSaved() || !saved?.credentialsConfigured || !checked || !availableSheets.length;
        feedback(destinationStatus, target.message, Boolean(saved && checked && !target.sheetName));
        appendButton.textContent = transfer.busy ? 'Cargando…'
            : target.label ? `Cargar en ${target.label}` : 'Cargar en Google Sheets';
        return target;
    }

    function refresh() {
        const blocked = busy || processing || loadingConfig;
        byId('sheetsConfigFields').disabled = blocked;
        byId('sheetsSave').disabled = blocked;
        byId('sheetsCheck').disabled = blocked || !matchesSaved() || sheetsConfigErrors(draft()).length > 0;
        byId('sheetsConfigForm').setAttribute('aria-busy', String(busy || loadingConfig));
        monthMappings.disabled = blocked || !checked;
        refreshHistory();
        const target = refreshDestination();
        appendButton.disabled = true;
        if (transfer.busy) {
            appendButton.textContent = 'Cargando…';
            feedback(appendStatus, 'Enviando los ítems seleccionados. Esperá la confirmación…');
            return;
        }
        if (!matchesSaved()) {
            feedback(appendStatus, 'Guardá los cambios de configuración antes de cargar.');
            return;
        }
        if (!saved?.credentialsConfigured || !checked) {
            feedback(appendStatus, 'Abrí el engranaje para configurar la cuenta de servicio y comprobar la conexión.');
            return;
        }
        let state;
        try { state = target.sheetName ? transfer.state(saved, getItems(), target) : {status: 'ready'}; }
        catch (error) { feedback(appendStatus, error.message); return; }
        if (state.status === 'success') {
            const count = state.result.appendedRows;
            feedback(appendStatus, `Ya se ${count === 1 ? 'cargó 1 ítem' : `cargaron ${count} ítems`} en ${state.result.sheetName}, rango ${state.result.updatedRange}.`);
            return;
        }
        appendButton.disabled = blocked || !target.sheetName;
        appendButton.textContent = state.status === 'error' ? `Reintentar carga en ${target.label || 'Google Sheets'}`
            : target.label ? `Cargar en ${target.label}` : 'Cargar en Google Sheets';
        if (!target.sheetName) {
            feedback(appendStatus, target.message);
            return;
        }
        const warning = transfer.hasUnconfirmed
            ? 'Hay una carga anterior sin confirmar que podría haberse guardado. Volvé a los datos y destino anteriores y reintentá antes de enviar cambios para evitar duplicados. Esta nueva carga enviará todas las filas seleccionadas. '
            : transfer.hasSuccess ? 'Esta factura ya tuvo una carga. Los cambios enviarán todas las filas seleccionadas como una carga nueva. ' : '';
        let count = 0;
        try { count = buildSheetsItems(getItems()).length; }
        catch (error) { appendButton.disabled = true; feedback(appendStatus, error.message); return; }
        feedback(appendStatus, state.status === 'error'
            ? `${state.error.message} Reintentá la misma carga para confirmar si se guardó.`
            : `${warning}Se ${count === 1 ? 'cargará 1 ítem' : `cargarán ${count} ítems`} en ${target.label}.`, state.status === 'error' || transfer.hasUnconfirmed);
        if (!count) appendButton.disabled = true;
    }

    function markErrors(errors) {
        for (const [field, element] of Object.entries(fields)) {
            const message = errors.find(error => error.field === field)?.message || '';
            element.setAttribute('aria-invalid', String(Boolean(message)));
            errorFields[field].textContent = message;
        }
    }

    async function checkCurrentConfig() {
        const current = await api.getConfig();
        if (normalizedConfig(current) !== normalizedConfig(saved) || current.configVersion !== saved.configVersion) {
            throw new Error('La configuración cambió desde otra ventana. Volvé a guardar y comprobarla.');
        }
        const check = await api.check();
        if (check.spreadsheetId !== saved.spreadsheetId || check.headerRow !== saved.headerRow
            || check.configVersion !== saved.configVersion) {
            throw new Error('La configuración cambió durante la comprobación. Volvé a comprobarla.');
        }
        availableSheets = Array.isArray(check.availableSheets) ? check.availableSheets : [];
        suggestedMonthSheets = check.suggestedMonthSheets || {};
        checked = true;
        renderMonthMappings(saved.monthSheets || {});
        const unavailable = Array.isArray(check.unavailableSheets) ? check.unavailableSheets : [];
        const incompatibleMappings = Object.entries(saved.monthSheets || {})
            .filter(([, sheetName]) => !availableSheets.includes(sheetName)).map(([month]) => MONTH_NAMES[Number(month) - 1]);
        const omitted = unavailable.length ? ` Se omitieron pestañas incompatibles: ${unavailable.join(', ')}.` : '';
        const fixMappings = incompatibleMappings.length
            ? ` Revisá las asociaciones de ${incompatibleMappings.join(', ')} en esta configuración.` : '';
        feedback(configStatus, `Conexión comprobada: ${check.spreadsheetTitle}. ${availableSheets.length} pestañas compatibles para cargar.${omitted}${fixMappings}`);
    }

    byId('sheetsSettingsOpen').addEventListener('click', () => {
        if (typeof settingsDialog.showModal === 'function') settingsDialog.showModal();
        else settingsDialog.setAttribute('open', '');
    });
    byId('sheetsSettingsClose').addEventListener('click', () => settingsDialog.close());
    settingsDialog.addEventListener('click', event => {
        if (event.target === settingsDialog) settingsDialog.close();
    });
    destinationSelect.addEventListener('change', () => {
        monthlyDestination.choose(destinationSelect.value);
        refresh();
    });

    byId('sheetsConfigForm').addEventListener('submit', async event => {
        event.preventDefault();
        if (busy || processing || loadingConfig) return;
        const config = draft();
        const errors = sheetsConfigErrors(config);
        markErrors(errors);
        if (errors.length) { fields[errors[0].field].focus(); return; }
        setBusy(true);
        checked = false;
        availableSheets = [];
        suggestedMonthSheets = {};
        feedback(configStatus, 'Guardando configuración…');
        try {
            saved = await api.saveConfig(config);
            monthlyDestination.reset();
            showAccount(saved);
            renderMonthMappings(saved.monthSheets || {});
            if (saved.legacySheetName) {
                feedback(configStatus, `Configuración guardada. La pestaña anterior “${saved.legacySheetName}” quedó sin asociación mensual; comprobá la planilla y elegí el mes que corresponda.`);
            } else {
                feedback(configStatus, 'Configuración guardada. Comprobando planilla y pestañas compatibles…');
            }
            if (saved.credentialsConfigured) {
                try { await checkCurrentConfig(); }
                catch (error) { feedback(configStatus, `La configuración se guardó, pero no se pudo comprobar la planilla: ${error.message}`, true); }
            }
            await loadHistory();
        } catch (error) { feedback(configStatus, error.message, true); }
        finally { setBusy(false); }
    });

    byId('sheetsCheck').addEventListener('click', async () => {
        if (busy || processing || !matchesSaved()) return;
        setBusy(true);
        checked = false;
        feedback(configStatus, 'Comprobando acceso, encabezados y pestañas…');
        try { await checkCurrentConfig(); }
        catch (error) { feedback(configStatus, error.message, true); }
        finally { await loadHistory(); setBusy(false); }
    });

    byId('sheetsHistorySync').addEventListener('click', async () => {
        if (byId('sheetsHistorySync').disabled || busy || processing || history.busy
            || !matchesSaved() || !checked || !saved?.credentialsConfigured) return;
        const config = {...saved};
        historyMessage = 'Leyendo los productos de las pestañas compatibles de la planilla…';
        historyError = false;
        setBusy(true);
        const promise = history.sync(config);
        refreshHistory();
        try {
            await promise;
            historyMessage = 'Historial actualizado. Se usará en la próxima extracción.';
        } catch (error) {
            historyError = true;
            historyMessage = `No se pudo actualizar el historial: ${error.message}${history.current?.active ? ' Se mantiene el historial anterior.' : ''}`;
        } finally { setBusy(false); }
    });

    byId('sheetsHistoryRemove').addEventListener('click', async () => {
        if (byId('sheetsHistoryRemove').disabled || busy || processing || history.busy || !history.current?.active) return;
        historyMessage = 'Dejando de usar el historial…';
        historyError = false;
        setBusy(true);
        const promise = history.remove();
        refreshHistory();
        try {
            await promise;
            historyMessage = 'El historial dejó de usarse. Las próximas extracciones usarán las reglas habituales.';
        } catch (error) {
            historyError = true;
            historyMessage = `No se pudo dejar de usar el historial: ${error.message} Se mantiene el historial anterior.`;
        } finally { setBusy(false); }
    });

    appendButton.addEventListener('click', async () => {
        if (appendButton.disabled || busy || processing || !matchesSaved()) return;
        const target = destinationState();
        if (!target.sheetName) { refresh(); return; }
        const config = {...saved};
        const items = getItems().map(item => ({...item}));
        setBusy(true);
        try {
            const attempt = transfer.send(config, items, target);
            refresh();
            await attempt;
            onSuccess();
        } catch { /* El estado del intento conserva el error y el identificador para reintentar. */ }
        finally { setBusy(false); }
    });

    Object.values(fields).forEach(field => field.addEventListener('input', () => {
        markErrors([]);
        if (!matchesSaved()) checked = false;
        refresh();
    }));

    queueMicrotask(async () => {
        void loadHistory();
        try {
            saved = await api.getConfig();
            fields.spreadsheetUrlOrId.value = saved.spreadsheetUrl || saved.spreadsheetId || '';
            fields.year.value = saved.year || '';
            fields.headerRow.value = saved.headerRow || 2;
            showAccount(saved);
            renderMonthMappings(saved.monthSheets || {});
            feedback(configStatus, saved.spreadsheetId
                ? saved.year ? 'Configuración guardada. Comprobando conexión…'
                    : `Configuración anterior detectada${saved.legacySheetName ? ` (pestaña “${saved.legacySheetName}”)` : ''}. Indicá el año de la planilla y guardá; después asociá la pestaña al mes que corresponda.`
                : 'Configurá la planilla, el año y la fila de encabezados.');
            if (saved.spreadsheetId && Number.isInteger(saved.year) && saved.credentialsConfigured) {
                try { await checkCurrentConfig(); }
                catch (error) { feedback(configStatus, `No se pudo comprobar la planilla: ${error.message}`, true); }
            }
        } catch (error) {
            byId('sheetsAccountStatus').textContent = 'No se pudo consultar la configuración.';
            feedback(configStatus, error.message, true);
        } finally { loadingConfig = false; refresh(); }
    });

    return {
        refresh,
        setProcessing(value) { processing = value; refresh(); },
        resetReceipt() { transfer.reset(); monthlyDestination.reset(); refresh(); }
    };
}
