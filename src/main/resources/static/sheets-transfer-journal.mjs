import {sha256Hex} from './browser-crypto.mjs';

const STORAGE_KEY = 'facturas.pendingSheetsTransfer';

function sessionStorage() {
    try { return globalThis.window?.sessionStorage; } catch { return null; }
}

async function fingerprint(payload) {
    const bytes = new TextEncoder().encode(JSON.stringify({destination: payload.destination, items: payload.items}));
    return sha256Hex(bytes);
}

// Solo conserva el intento en curso: identificador, destino y huella, sin filas ni OCR.
export function createRecoverableSheetsAppend({append, lookup, storage = sessionStorage(), digest = fingerprint}) {
    function read() {
        const raw = storage?.getItem(STORAGE_KEY);
        if (!raw) return null;
        const entry = JSON.parse(raw);
        if (!entry || typeof entry.requestId !== 'string' || !/^[a-f0-9]{64}$/.test(entry.fingerprint)
                || !entry.destination || typeof entry.destination.spreadsheetId !== 'string'
                || typeof entry.destination.sheetName !== 'string' || !Number.isInteger(entry.destination.headerRow)) {
            throw new Error('No se pudo recuperar la carga anterior. Revisá la planilla antes de volver a cargar.');
        }
        return entry;
    }

    return async payload => {
        if (!storage) throw new Error('El navegador no permite guardar el intento de carga. Habilitá el almacenamiento de sesión y reintentá.');
        let previous;
        try { previous = read(); }
        catch { throw new Error('No se pudo recuperar la carga anterior. Revisá la planilla antes de volver a cargar.'); }
        const hash = await digest(payload);
        let requestId = payload.requestId;
        if (previous) {
            const recovered = await lookup({requestId: previous.requestId, destination: previous.destination});
            if (!recovered || typeof recovered.committed !== 'boolean'
                    || (recovered.committed && !recovered.result)) {
                throw new Error('No se pudo confirmar la carga anterior. Reintentá antes de enviar otra.');
            }
            if (recovered.committed) {
                if (previous.fingerprint === hash && recovered.result.appendedRows !== payload.items.length) {
                    throw new Error('La confirmación anterior no coincide con la cantidad de filas. Revisá la planilla antes de continuar.');
                }
                storage.removeItem(STORAGE_KEY);
                if (previous.fingerprint === hash) return recovered.result;
                const error = new Error('La carga anterior ya está guardada. Revisá las filas seleccionadas: una carga nueva enviará todas las filas marcadas.');
                error.previousCommitted = true;
                throw error;
            }
            if (JSON.stringify(previous.destination) !== JSON.stringify(payload.destination)) {
                throw new Error('Hay una carga sin confirmar en otro destino. Volvé a ese destino y reintentá para resolverla.');
            }
            if (previous.fingerprint !== hash) {
                throw new Error('La carga anterior todavía no está confirmada. Restablecé sus datos y reintentá antes de enviar cambios.');
            }
            requestId = previous.requestId;
        }
        try {
            storage.setItem(STORAGE_KEY, JSON.stringify({requestId, destination: payload.destination, fingerprint: hash}));
        } catch {
            throw new Error('No se pudo guardar el intento de carga en esta sesión. No se enviaron filas; reintentá.');
        }
        const result = await append({...payload, requestId});
        storage.removeItem(STORAGE_KEY);
        return result;
    };
}
