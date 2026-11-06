import {extractReceipt, cancelReceipt, cancellationUrl, extractionProgress} from './receipt-api.mjs?v=2';
import {createUuid} from './browser-crypto.mjs';

const STORAGE_KEY = 'facturas.pendingExtraction';

export function createReceiptExtraction({
    extract = extractReceipt, cancel = cancelReceipt,
    pageWindow = globalThis.window ?? null,
    pageNavigator = globalThis.navigator,
    createId = createUuid,
    progress = extractionProgress, onProgress = null,
    setTimer = setTimeout, clearTimer = clearTimeout
} = {}) {
    let storage;
    try { storage = pageWindow?.sessionStorage; } catch { /* Almacenamiento restringido. */ }
    let pendingId = null;
    try { pendingId = storage?.getItem(STORAGE_KEY) || null; } catch { /* Usar memoria. */ }
    let active = null;
    let leaving = false;
    let timer = null;

    function stopProgress(operation) {
        clearTimer(timer); timer = null; operation?.progressController.abort();
    }
    async function poll(operation) {
        if (!onProgress || active !== operation || operation.cancelRequested) return;
        try {
            const value = await progress(operation.id, {signal: operation.progressController.signal});
            if (active === operation && !operation.cancelRequested && !leaving) onProgress(value);
        } catch { /* El POST de extracción es la fuente del resultado, aun si falla la consulta. */ }
        if (active === operation && !operation.cancelRequested && !leaving) timer = setTimer(() => { void poll(operation); }, 750);
    }

    function remember(id) {
        pendingId = id;
        try {
            if (id) storage?.setItem(STORAGE_KEY, id);
            else storage?.removeItem(STORAGE_KEY);
        } catch { /* pagehide todavía puede cancelar usando el estado en memoria. */ }
    }

    async function recoverPending() {
        const id = pendingId;
        if (!id) return;
        await cancel(id);
        if (!leaving && pendingId === id) remember(null);
    }

    // Repetir al cargar cubre beacons perdidos y cancelaciones durante la subida.
    let recovery = recoverPending().catch(() => {});
    pageWindow?.addEventListener('pagehide', () => {
        leaving = true;
        if (pendingId) {
            let sent = false;
            try { sent = pageNavigator?.sendBeacon?.(cancellationUrl(pendingId)) || false; } catch { /* Usar keepalive. */ }
            if (!sent) void cancel(pendingId).catch(() => {});
        }
        active?.controller.abort();
        stopProgress(active);
    });
    pageWindow?.addEventListener('pageshow', event => {
        if (!event.persisted) return;
        leaving = false;
        recovery = recoverPending().catch(() => {});
    });

    return {
        async cancel() {
            const operation = active;
            if (!operation) return false;
            operation.cancelRequested = true; stopProgress(operation);
            try {
                await cancel(operation.id);
                operation.controller.abort();
                if (pendingId === operation.id) remember(null);
                return true;
            } catch (error) {
                if (active === operation) {
                    operation.cancelRequested = false;
                    operation.progressController = new AbortController(); void poll(operation);
                }
                throw error;
            }
        },
        async extract(file, options = {}) {
            await recovery;
            if (pendingId) await recoverPending();
            if (leaving) throw new DOMException('La página se cerró.', 'AbortError');
            const id = createId();
            const controller = new AbortController();
            const operation = {id, controller, progressController: new AbortController(), cancelRequested: false};
            active = operation;
            remember(id);
            void poll(operation);
            try {
                const result = await extract(file, {...options, extractionId: id, signal: controller.signal});
                if (controller.signal.aborted || operation.cancelRequested) throw new DOMException('Extracción cancelada.', 'AbortError');
                if (!leaving && pendingId === id) remember(null);
                return result;
            } finally {
                if (active?.id === id) { active = null; stopProgress(operation); }
            }
        }
    };
}
