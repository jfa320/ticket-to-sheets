const FIELDS = ['descripcion', 'marca', 'categoria'];
const clean = value => String(value ?? '').trim().replace(/\s+/g, ' ');
const key = value => clean(value).normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
const fieldsOf = item => Object.fromEntries(FIELDS.map(field => [field, clean(item[field])]));

export function createCorrectionSaver(receipt, {
    save, onStatus = () => {}, delay = 2000, setTimer = setTimeout, clearTimer = clearTimeout
}) {
    // Una base por fila evita colisiones entre firmas repetidas y entre tickets.
    const snapshots = new Map(receipt.items.map(item => [item, {original: fieldsOf(item), byStore: new Map()}]));
    let timer = null;
    let inFlight = null;
    let requested = false;
    let disposed = false;
    const report = (state, message) => { if (!disposed) onStatus(state, message); };

    function pendingBatches() {
        const batches = new Map();
        for (const item of receipt.items) {
            const snapshot = snapshots.get(item);
            const store = clean(item.lugarDeCompra);
            const firma = clean(item.firma);
            if (!snapshot || !store || !firma || item.usar === false) continue;
            // El backend no aprende filas cuya marca original o corregida sea Genérico.
            if (key(snapshot.original.marca) === 'generico' || key(item.marca) === 'generico') continue;
            const current = fieldsOf(item);
            if (!current.descripcion) continue;
            const baseline = snapshot.byStore.get(key(store)) || snapshot.original;
            if (!FIELDS.some(field => current[field] && current[field] !== baseline[field])) continue;
            // El backend conserva campos vacíos; enviamos el contexto completo para
            // que corregir sólo la marca no cree una memoria sin descripción.
            const applied = Object.fromEntries(FIELDS.map(field => [field, current[field] || baseline[field]]));
            const correction = {...applied, firma, marcaOriginal: snapshot.original.marca};
            if (!batches.has(key(store))) batches.set(key(store), {store, corrections: [], acknowledgements: []});
            const batch = batches.get(key(store));
            batch.corrections.push(correction);
            batch.acknowledgements.push({snapshot, storeKey: key(store), applied});
        }
        return [...batches.values()];
    }

    function cancelTimer() {
        if (timer !== null) clearTimer(timer);
        timer = null;
    }

    async function drain() {
        let saved = false;
        try {
            do {
                requested = false;
                const batches = pendingBatches();
                for (const {store, corrections, acknowledgements} of batches) {
                    if (disposed) return true;
                    report('saving', 'Guardando correcciones…');
                    const result = await save({store, corrections});
                    if (disposed) return true;
                    if (result?.saved !== corrections.length) {
                        throw new Error('No se guardaron todas las correcciones. Revisá los datos y reintentá.');
                    }
                    for (const {snapshot, storeKey, applied} of acknowledgements) snapshot.byStore.set(storeKey, applied);
                    saved = true;
                }
            } while (requested && !disposed);
            report(saved ? 'saved' : 'idle', saved ? 'Correcciones guardadas para próximos tickets.' : '');
            return true;
        } catch (error) {
            report('error', error.message || 'No se pudieron guardar las correcciones.');
            return false;
        }
    }

    function flush() {
        cancelTimer();
        if (disposed) return Promise.resolve(true);
        requested = true;
        // Serializar peticiones evita que una respuesta anterior pise una edición nueva.
        if (!inFlight) inFlight = Promise.resolve().then(drain).finally(() => { inFlight = null; });
        return inFlight;
    }

    return {
        schedule() {
            if (disposed) return;
            cancelTimer();
            report('pending', 'Cambios pendientes de guardar…');
            timer = setTimer(flush, delay);
        },
        flush,
        dispose() { disposed = true; cancelTimer(); }
    };
}
