const SHEET_MIME = 'application/vnd.google-apps.spreadsheet';

// Open synchronously from a click so browsers do not block the OAuth window.
export async function connectGoogle({api, browser = window, setPoll = setInterval, clearPoll = clearInterval,
    setTimer = setTimeout, clearTimer = clearTimeout}) {
    const popup = browser.open('', 'facturas-google-oauth', 'popup,width=560,height=720');
    if (!popup) throw new Error('Permití las ventanas emergentes para conectar Google y volvé a intentar.');
    let poll, timer, listener, checking = false;
    try {
        const completed = new Promise((resolve, reject) => {
            listener = event => {
                if (event.origin !== browser.location.origin || event.source !== popup
                    || event.data?.type !== 'facturas-google-oauth') return;
                if (event.data.success === true) resolve();
                else reject(new Error('No se completó la autorización de Google. Volvé a conectar tu cuenta.'));
            };
            browser.addEventListener('message', listener);
            poll = setPoll(async () => {
                if (!popup.closed || checking) return;
                checking = true;
                try {
                    // The callback may have completed immediately before the popup closed.
                    if ((await api.getConfig()).googleConnected) resolve();
                    else reject(new Error('Se cerró la conexión con Google sin completar la autorización.'));
                } catch (error) { reject(error); }
            }, 750);
            timer = setTimer(() => reject(new Error('La conexión con Google venció. Volvé a iniciarla.')), 600000);
        });
        // Attach the rejection handler before waiting for the HTTP request.
        const started = api.connectGoogle().then(({authorizationUrl}) => {
            const url = new URL(authorizationUrl);
            if (url.origin !== 'https://accounts.google.com' || url.pathname !== '/o/oauth2/v2/auth') {
                throw new Error('La aplicación devolvió una dirección de Google inválida.');
            }
            popup.location.href = url.href;
        });
        await Promise.all([started, completed]);
    } finally {
        browser.removeEventListener('message', listener);
        clearPoll(poll); clearTimer(timer);
        popup.close();
    }
}

let pickerLoading;
export function loadGooglePicker(browser = window, page = document) {
    if (browser.google?.picker) return Promise.resolve(browser.google.picker);
    if (pickerLoading) return pickerLoading;
    pickerLoading = new Promise((resolve, reject) => {
        const script = page.createElement('script');
        const timer = setTimeout(() => fail(), 20000);
        function fail() {
            clearTimeout(timer); script.remove(); pickerLoading = null;
            reject(new Error('No se pudo abrir el selector de Google. Revisá Internet y volvé a intentar.'));
        }
        function loaded() { clearTimeout(timer); resolve(browser.google.picker); }
        script.src = 'https://apis.google.com/js/api.js';
        script.async = true; script.onerror = fail;
        script.onload = () => {
            try { browser.gapi.load('picker', {callback: loaded, onerror: fail, timeout: 15000, ontimeout: fail}); }
            catch { fail(); }
        };
        page.head.append(script);
    });
    return pickerLoading;
}

export function selectGoogleSpreadsheet({session, pickerApi, origin, setTimer = setTimeout, clearTimer = clearTimeout}) {
    return new Promise((resolve, reject) => {
        let picker, timer, finished = false;
        function finish(value, error) {
            if (finished) return;
            finished = true;
            clearTimer(timer); picker?.dispose();
            if (error) reject(error); else resolve(value);
        }
        try {
            const view = new pickerApi.DocsView(pickerApi.ViewId.SPREADSHEETS)
                .setMimeTypes(SHEET_MIME).setIncludeFolders(false).setSelectFolderEnabled(false);
            picker = new pickerApi.PickerBuilder().addView(view).setOAuthToken(session.accessToken)
                .setDeveloperKey(session.developerKey).setAppId(session.appId).setOrigin(origin)
                .setLocale('es').setTitle('Elegí tu planilla de compras').setCallback(data => {
                    if (data.action === pickerApi.Action.CANCEL) { finish(null); return; }
                    if (data.action !== pickerApi.Action.PICKED) return;
                    const doc = data.docs?.[0];
                    if (!doc || !/^[A-Za-z0-9_-]{20,200}$/.test(doc.id) || doc.mimeType !== SHEET_MIME) {
                        finish(null, new Error('Elegí una planilla nativa de Google Sheets.')); return;
                    }
                    finish({id: doc.id, name: String(doc.name || ''), url: `https://docs.google.com/spreadsheets/d/${doc.id}/edit`});
                }).build();
            timer = setTimer(() => finish(null, new Error('El selector de Google venció. Volvé a abrirlo.')), 600000);
            picker.setVisible(true);
        } catch { finish(null, new Error('No se pudo abrir el selector. Revisá la configuración de Google Picker.')); }
    });
}
