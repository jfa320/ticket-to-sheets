// Limita también respuestas cuyo cuerpo nunca termina, aunque el transporte ignore abort().
export async function withRequestTimeout(action, {timeoutMs = 8000, signal, setTimer = setTimeout, clearTimer = clearTimeout,
    message = 'La solicitud tardó demasiado. Reintentá la operación.'} = {}) {
    if (signal?.aborted) throw new DOMException('Solicitud cancelada.', 'AbortError');
    const controller = new AbortController(); let timer, abort;
    const deadline = new Promise((resolve, reject) => {
        timer = setTimer(() => {
            const error = new Error(message); error.name = 'TimeoutError'; reject(error); controller.abort();
        }, timeoutMs);
        abort = () => { reject(new DOMException('Solicitud cancelada.', 'AbortError')); controller.abort(); };
        signal?.addEventListener('abort', abort, {once:true});
    });
    try { return await Promise.race([Promise.resolve().then(() => action(controller.signal)), deadline]); }
    finally { clearTimer(timer); signal?.removeEventListener('abort', abort); }
}
