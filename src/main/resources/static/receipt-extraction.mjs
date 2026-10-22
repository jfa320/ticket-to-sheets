import {extractReceipt, cancelReceipt, cancellationUrl} from './receipt-api.mjs?v=2';

const STORAGE_KEY = 'facturas.pendingExtraction';

export function createReceiptExtraction({
    extract = extractReceipt, cancel = cancelReceipt,
    pageWindow = globalThis.window ?? null,
    pageNavigator = globalThis.navigator,
    createId = () => globalThis.crypto.randomUUID()
} = {}) {
    let storage;
    try { storage = pageWindow?.sessionStorage; } catch { /* Almacenamiento restringido. */ }
    let pendingId = null;
    try { pendingId = storage?.getItem(STORAGE_KEY) || null; } catch { /* Usar memoria. */ }
    let active = null;
    let leaving = false;

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
    });
    pageWindow?.addEventListener('pageshow', event => {
        if (!event.persisted) return;
        leaving = false;
        recovery = recoverPending().catch(() => {});
    });

    return {
        async extract(file) {
            await recovery;
            if (pendingId) await recoverPending();
            if (leaving) throw new DOMException('La página se cerró.', 'AbortError');
            const id = createId();
            const controller = new AbortController();
            active = {id, controller};
            remember(id);
            try {
                const result = await extract(file, {extractionId: id, signal: controller.signal});
                if (controller.signal.aborted) throw new DOMException('Extracción cancelada.', 'AbortError');
                if (!leaving && pendingId === id) remember(null);
                return result;
            } finally {
                if (active?.id === id) active = null;
            }
        }
    };
}
