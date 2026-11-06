export async function copyToClipboard(text, {
    pageNavigator = globalThis.navigator, pageDocument = globalThis.document
} = {}) {
    if (typeof pageNavigator?.clipboard?.writeText === 'function') {
        try {
            await pageNavigator.clipboard.writeText(text);
            return;
        } catch { /* Algunos navegadores bloquean la API aun cuando está disponible. */ }
    }
    if (!pageDocument?.body || typeof pageDocument.execCommand !== 'function') {
        throw new Error('El navegador no permite copiar automáticamente.');
    }

    const previousFocus = pageDocument.activeElement;
    const selection = pageDocument.getSelection?.();
    const ranges = selection
        ? Array.from({length: selection.rangeCount}, (_, i) => selection.getRangeAt(i).cloneRange()) : [];
    const textarea = pageDocument.createElement('textarea');
    textarea.value = text;
    textarea.readOnly = true;
    textarea.tabIndex = -1;
    textarea.setAttribute('aria-label', 'Texto para copiar');
    textarea.style.cssText = 'position:fixed;top:0;left:0;opacity:0;font-size:16px;';
    try {
        pageDocument.body.appendChild(textarea);
        textarea.focus({preventScroll: true});
        textarea.select();
        textarea.setSelectionRange(0, text.length);
        // En HTTP debe ejecutarse durante el clic, sin una espera previa por permisos.
        if (!pageDocument.execCommand('copy')) throw new Error('El navegador bloqueó la copia automática.');
    } finally {
        textarea.remove();
        previousFocus?.focus?.({preventScroll: true});
        if (ranges.length) {
            selection.removeAllRanges();
            ranges.forEach(range => selection.addRange(range));
        }
    }
}
