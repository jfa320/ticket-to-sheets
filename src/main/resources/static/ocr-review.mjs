const SVG = 'http://www.w3.org/2000/svg';

export function normalizeReview(pages) {
    if (!Array.isArray(pages)) return [];
    return pages.filter(page => page && typeof page === 'object').map((page, index) => {
        const width = Number.isInteger(page.width) && page.width > 0 ? page.width : 0;
        const height = Number.isInteger(page.height) && page.height > 0 ? page.height : 0;
        const imageDataUrl = width * height > 0 && width * height <= 4000000 &&
            typeof page.imageDataUrl === 'string' && page.imageDataUrl.length <= 1500000 &&
            /^data:image\/jpeg;base64,[A-Za-z0-9+/]+={0,2}$/.test(page.imageDataUrl) ? page.imageDataUrl : null;
        const source = page.source === 'pdf-text' ? 'pdf-text' : 'ocr';
        const detections = (Array.isArray(page.detections) ? page.detections : []).filter(item =>
            item && typeof item.text === 'string' && item.text.trim()).map((item, position) => {
            const confidence = typeof item.confidence === 'number' && Number.isFinite(item.confidence) &&
                item.confidence >= 0 && item.confidence <= 1 ? item.confidence : null;
            const box = Array.isArray(item.box) && item.box.length === 4 && item.box.every(point =>
                Array.isArray(point) && point.length >= 2 && point.slice(0, 2).every(Number.isFinite))
                ? item.box.map(([x, y]) => [width ? Math.max(0, Math.min(width, x)) : x,
                    height ? Math.max(0, Math.min(height, y)) : y]) : null;
            const bounds = box ? {left: Math.min(...box.map(p => p[0])), top: Math.min(...box.map(p => p[1])),
                right: Math.max(...box.map(p => p[0])), bottom: Math.max(...box.map(p => p[1]))} : null;
            return {id: position, text: item.text, confidence,
                doubtful: source === 'ocr' && (confidence === null || confidence < 0.8),
                box: bounds && bounds.right > bounds.left && bounds.bottom > bounds.top ? box : null, bounds};
        });
        return {pageNumber: Number.isInteger(page.pageNumber) && page.pageNumber > 0 ? page.pageNumber : index + 1,
            source, width, height, imageDataUrl, detections};
    });
}

export function cropCoordinates(bounds, page, naturalWidth, naturalHeight) {
    if (!bounds || page.width <= 0 || page.height <= 0 || naturalWidth <= 0 || naturalHeight <= 0) return null;
    const padding = Math.max(4, (bounds.bottom - bounds.top) * 0.3);
    const left = Math.max(0, bounds.left - padding), top = Math.max(0, bounds.top - padding);
    const right = Math.min(page.width, bounds.right + padding), bottom = Math.min(page.height, bounds.bottom + padding);
    if (right <= left || bottom <= top) return null;
    return {x: left * naturalWidth / page.width, y: top * naturalHeight / page.height,
        width: (right - left) * naturalWidth / page.width, height: (bottom - top) * naturalHeight / page.height};
}

export function createOcrReview(root = document) {
    const el = suffix => root.getElementById(`ocrReview${suffix}`);
    const panel = el('Panel'), picker = el('Page'), all = el('ShowAll'), zoom = el('Zoom');
    const stage = el('ImageStage'), viewport = el('ImageViewport'), list = el('Detections');
    let pages = [], page = null, selected = null, image = null, generation = 0;
    let buttons = new Map(), polygons = new Map();
    const confidenceLabel = item => item.confidence === null ? 'Sin confianza informada' :
        `Confianza OCR: ${item.confidence.toLocaleString('es-AR', {minimumFractionDigits: 2, maximumFractionDigits: 2})}`;

    function drawCrop() {
        const canvas = el('Crop');
        canvas.classList.add('hidden'); canvas.width = canvas.height = 1;
        if (!selected) return;
        if (!page.imageDataUrl || !selected.box) {
            el('CropStatus').textContent = 'No hay una imagen y coordenadas disponibles para ampliar esta zona.';
            return;
        }
        if (!image?.complete || !image.naturalWidth) { el('CropStatus').textContent = 'Cargando imagen…'; return; }
        const crop = cropCoordinates(selected.bounds, page, image.naturalWidth, image.naturalHeight);
        if (!crop) return;
        const scale = Math.min(Number(zoom.value), 2000 / crop.width, 800 / crop.height);
        canvas.width = Math.max(1, Math.round(crop.width * scale));
        canvas.height = Math.max(1, Math.round(crop.height * scale));
        canvas.style.width = `${canvas.width}px`;
        const context = canvas.getContext('2d');
        if (!context) { el('CropStatus').textContent = 'El navegador no permite mostrar el recorte.'; return; }
        context.drawImage(image, crop.x, crop.y, crop.width, crop.height, 0, 0, canvas.width, canvas.height);
        canvas.setAttribute('aria-label', `Recorte de: ${selected.text}`);
        canvas.classList.remove('hidden');
        el('CropStatus').textContent = 'Compará el recorte con el texto. Podés desplazarte dentro de la ampliación.';
    }

    function select(item, reveal = false) {
        selected = item;
        buttons.forEach((button, id) => button.setAttribute('aria-pressed', String(id === item.id)));
        polygons.forEach((polygon, id) => polygon.setAttribute('aria-pressed', String(id === item.id)));
        el('Selection').classList.remove('hidden');
        el('SelectedText').textContent = item.text;
        el('SelectedConfidence').textContent = page.source === 'pdf-text' ? 'Texto digital del PDF; no requiere reconocimiento OCR.' : confidenceLabel(item);
        if (reveal) polygons.get(item.id)?.scrollIntoView({block: 'nearest', inline: 'nearest'});
        drawCrop();
    }

    function renderPage() {
        const version = ++generation;
        selected = null; image = null; buttons = new Map(); polygons = new Map();
        stage.replaceChildren(); list.replaceChildren();
        el('Selection').classList.add('hidden');
        el('Crop').width = el('Crop').height = 1;
        el('Crop').classList.add('hidden');
        page = pages[Number(picker.value)];
        if (!page) { viewport.classList.add('hidden'); return; }
        const visible = page.detections.filter(item => all.checked || item.doubtful || page.source === 'pdf-text');
        el('PageStatus').textContent = page.source === 'pdf-text' ? 'Página con texto digital. No hay una imagen OCR para resaltar.'
            : `${visible.length} zona(s) visibles de ${page.detections.length}. La imagen corresponde a la lectura procesada.`;
        el('Empty').textContent = page.detections.length ? 'No hay zonas dudosas en esta página. Podés mostrar todas las zonas.' : 'No hay zonas detectadas para revisar.';
        el('Empty').classList.toggle('hidden', visible.length > 0);
        viewport.classList.toggle('hidden', !page.imageDataUrl);
        let overlay = null;
        if (page.imageDataUrl) {
            image = new Image();
            image.alt = `Página ${page.pageNumber} procesada para reconocer texto`;
            image.style.height = '100%'; stage.style.aspectRatio = `${page.width} / ${page.height}`;
            image.onload = () => { if (version === generation) drawCrop(); };
            image.onerror = () => {
                if (version !== generation) return;
                page = {...page, imageDataUrl: null}; viewport.classList.add('hidden');
                el('PageStatus').textContent = 'No se pudo cargar la imagen. El texto detectado sigue disponible.';
                drawCrop();
            };
            overlay = root.createElementNS(SVG, 'svg');
            overlay.setAttribute('viewBox', `0 0 ${page.width} ${page.height}`);
            overlay.setAttribute('preserveAspectRatio', 'none'); overlay.setAttribute('role', 'group');
            overlay.setAttribute('aria-label', 'Zonas detectadas. También disponibles en la lista.');
            stage.append(image, overlay); image.src = page.imageDataUrl;
        }
        for (const item of visible) {
            const row = root.createElement('li'), button = root.createElement('button');
            button.type = 'button'; button.className = 'ocr-detection-button'; button.setAttribute('aria-pressed', 'false');
            const text = root.createElement('span'), confidence = root.createElement('span');
            text.textContent = item.text; confidence.className = 'ocr-detection-confidence';
            confidence.textContent = page.source === 'pdf-text' ? 'Texto digital' : confidenceLabel(item);
            button.append(text, confidence); button.addEventListener('click', () => select(item, true));
            buttons.set(item.id, button); row.append(button); list.append(row);
            if (overlay && item.box) {
                const polygon = root.createElementNS(SVG, 'polygon');
                polygon.setAttribute('points', item.box.map(point => point.join(',')).join(' '));
                polygon.setAttribute('class', 'ocr-zone'); polygon.setAttribute('role', 'button'); polygon.setAttribute('tabindex', '0');
                polygon.setAttribute('aria-label', `${item.text}. ${confidenceLabel(item)}`); polygon.setAttribute('aria-pressed', 'false');
                polygon.addEventListener('click', () => select(item));
                polygon.addEventListener('keydown', event => {
                    if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); select(item); }
                });
                polygons.set(item.id, polygon); overlay.append(polygon);
            }
        }
        if (visible.length) select(visible[0]);
    }

    function setPages(value) {
        pages = normalizeReview(value);
        picker.replaceChildren(); all.checked = false; panel.open = false;
        for (const [index, item] of pages.entries()) {
            const option = root.createElement('option'); option.value = String(index);
            option.textContent = `Página ${item.pageNumber}`; picker.append(option);
        }
        picker.value = '0'; picker.disabled = pages.length < 2;
        const doubtful = pages.flatMap(item => item.detections).filter(item => item.doubtful).length;
        el('Count').textContent = `${doubtful} zona(s) para revisar`;
        panel.classList.toggle('hidden', pages.length === 0); renderPage();
    }
    picker.addEventListener('change', renderPage); all.addEventListener('change', renderPage); zoom.addEventListener('change', drawCrop);
    return {setPages, clear: () => setPages([])};
}
