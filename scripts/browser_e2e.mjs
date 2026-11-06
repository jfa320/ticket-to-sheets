import assert from 'node:assert/strict';
import {spawn, execFileSync} from 'node:child_process';
import {createRequire} from 'node:module';
import {createServer} from 'node:net';
import {access, copyFile, mkdir, readFile, writeFile} from 'node:fs/promises';
import {createWriteStream} from 'node:fs';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {setTimeout as pause} from 'node:timers/promises';

// Prueba del producto completo; Playwright es una herramienta opcional de desarrollo.
// No instala paquetes ni usa el perfil, las credenciales o los datos personales del usuario.
const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const require = createRequire(import.meta.url);
const {chromium} = require(process.env.APP_PLAYWRIGHT_MODULE || 'playwright');
const origin = 'http://127.0.0.1:18082';
const run = path.join(root, 'logs', `browser-e2e-${new Date().toISOString().replace(/[:.]/g,'-')}`);
const fixtureFiles = path.join(root, 'test-data', 'browser-e2e');
const children = [], streams = [], errors = [];
let browser, page;

async function ensurePortFree(port) {
    await new Promise((resolve,reject) => {
        const server=createServer(); server.once('error',()=>reject(new Error(`El puerto ${port} está ocupado; no se alteró ningún proceso.`)));
        server.listen(port,'127.0.0.1',()=>server.close(resolve));
    });
}
function launch(command,args,cwd,name) {
    const output=createWriteStream(path.join(run,`${name}.log`)); streams.push(output);
    const child=spawn(command,args,{cwd,windowsHide:true,stdio:['ignore','pipe','pipe']});
    child.stdout.pipe(output); child.stderr.pipe(output); child.on('error',error=>errors.push(error)); children.push(child); return child;
}
async function waitFor(url) {
    const deadline=Date.now()+45000;
    while(Date.now()<deadline) {
        if(errors.length)throw errors[0];
        if(children.some(child=>child.exitCode!==null))throw new Error(`Un servicio terminó antes de estar listo. Revisá ${run}`);
        try { if((await fetch(url,{signal:AbortSignal.timeout(1000)})).ok)return; } catch { /* Arranque de los servicios. */ }
        await pause(150);
    }
    throw new Error(`No respondió ${url}. Revisá ${run}`);
}
function stopOwnChildren() {
    // Solo PIDs devueltos por spawn en esta ejecución. Nunca busca ni termina procesos por nombre/puerto.
    for(const child of children.reverse()) {
        if(!child.pid||child.exitCode!==null)continue;
        try {
            if(process.platform==='win32')execFileSync('taskkill.exe',['/PID',String(child.pid),'/T','/F'],{stdio:'ignore',windowsHide:true});
            else child.kill('SIGTERM');
        } catch { /* Un hijo puede haber finalizado durante el cierre. */ }
    }
    for(const stream of streams)stream.end();
}

try {
    await access(path.join(root,'target','facturas-ocr-0.0.1-SNAPSHOT.jar'));
    await ensurePortFree(18081); await ensurePortFree(18082);
    await mkdir(path.join(run,'data'),{recursive:true});
    await copyFile(path.join(root,'target','facturas-ocr-0.0.1-SNAPSHOT.jar'),path.join(run,'app.jar'));
    const java=process.env.APP_JAVA || (process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java'):'java');
    const python=process.env.APP_PYTHON || path.join(root,'.venv',process.platform==='win32'?'Scripts/python.exe':'bin/python');
    // Java 17 en Windows puede generar una ruta AF_UNIX demasiado larga. Se fuerza su fallback TCP local.
    const longTemporary=path.join(run,'java-http-local-temporary-directory');
    await mkdir(longTemporary,{recursive:true});
    launch(java,[`-Djdk.net.unixdomain.tmpdir=${longTemporary}`,'-jar','app.jar','--server.address=127.0.0.1','--server.port=18081',
        '--app.ocr.endpoint=http://127.0.0.1:18082/ocr','--app.ocr.health-endpoint=http://127.0.0.1:18082/health',
        '--app.ocr.read-timeout-ms=30000'],run,'backend');
    launch(python,[path.join(root,'scripts','browser_fixture.py'),'--port','18082','--backend','http://127.0.0.1:18081'],root,'fixture');
    await waitFor(`${origin}/health`); await waitFor('http://127.0.0.1:18081/api/categories');
    browser=await chromium.launch({headless:true,...(process.env.APP_BROWSER_EXECUTABLE?{executablePath:process.env.APP_BROWSER_EXECUTABLE}:process.platform==='win32'?{channel:'msedge'}:{})});
    const context=await browser.newContext({viewport:{width:1440,height:1000},permissions:['clipboard-read','clipboard-write']});
    await context.route('**/*',route=> {
        const url=route.request().url();
        return url.startsWith(origin+'/')||url.startsWith('data:')||url.startsWith('blob:')?route.continue():route.abort();
    });
    page=await context.newPage(); const pageErrors=[]; page.on('pageerror',error=>pageErrors.push(error.message));
    page.on('dialog',dialog=>dialog.accept());
    page.setDefaultTimeout(15000);
    const control=async data=>assert.equal((await context.request.post(`${origin}/__test__/control`,{data})).status(),200);
    const state=async()=> (await context.request.get(`${origin}/__test__/state`)).json();
    const message=async(id,text)=>page.waitForFunction(({id,text})=>document.getElementById(id).textContent.includes(text),{id,text});
    const extract=async filename=> {
        await page.locator('#fileInput').setInputFiles(path.join(fixtureFiles,filename));
        const [result]=await Promise.all([
            page.waitForResponse(value=>value.url()===`${origin}/api/receipts/extract`&&value.request().method()==='POST'),
            page.locator('#submitButton').click()
        ]);
        assert.equal(result.status(),200,`Falló la extracción de ${filename}`);
        await page.locator('#cancelExtraction').waitFor({state:'hidden'}); return result.json();
    };
    await page.goto(origin); await extract('receipt.png');
    assert.equal(await page.locator('#itemsBody tr').count(),2);
    assert.match(await page.locator('#totalValue').innerText(),/1[.,]600/);
    await page.getByRole('button',{name:'Ver fila 1 en la imagen'}).click();
    await page.locator('#ocrReviewCrop').waitFor({state:'visible'});
    assert.ok(await page.locator('#ocrReviewCrop').evaluate(canvas=>canvas.width>1));
    await page.locator('#reviewFilter').selectOption('pending');
    assert.equal(await page.locator('#itemsBody tr').count(),1);
    await page.getByRole('button',{name:'Marcar revisada, fila 1'}).focus();
    await page.keyboard.press('Enter'); assert.equal(await page.locator('#itemsBody tr').count(),0);
    await page.locator('#reviewFilter').selectOption('all');
    await page.locator('#copyTsv').click();
    const copied=await page.evaluate(()=>navigator.clipboard.readText());
    assert.equal(copied.replace(/\r\n/g,'\n'),await page.locator('#tsvOutput').inputValue()); assert.equal(copied.trim().split('\n').length,2);
    assert.ok(copied.trim().split('\n').every(line=>line.split('\t').length===7));
    const png=(await readFile(path.join(fixtureFiles,'receipt.png'))).toString('base64');
    await page.evaluate(async data=> {
        const bytes=Uint8Array.from(atob(data),char=>char.charCodeAt(0));
        await navigator.clipboard.write([new ClipboardItem({'image/png':new Blob([bytes],{type:'image/png'})})]);
        document.activeElement?.blur();
    },png);
    await page.keyboard.press(process.platform==='darwin'?'Meta+v':'Control+v');
    await message('fileName','image.png'); assert.equal(await page.locator('#itemsBody tr').count(),2);
    console.log('OK: imagen, cantidades, recorte y portapapeles TSV real.');

    await control({failCorrections:1});
    await page.locator('#itemsBody tr').first().locator('[data-field="descripcion"]').fill('Leche editada E2E');
    await page.locator('#retryCorrections').waitFor({state:'visible'}); await page.locator('#retryCorrections').click();
    await message('learnStatus','guardadas');
    await page.locator('#itemsBody tr').first().locator('[data-field="categoria"] input').fill('Personal E2E');
    await page.waitForFunction(()=>document.getElementById('learnStatus').textContent.includes('guardadas'));
    await page.waitForFunction(async()=> {
        const response=await fetch('/api/corrections'); const body=await response.json();
        return body.entries.some(value=>value.categoria==='Personal E2E');
    });
    await page.locator('#correctionsOpen').click();
    await page.locator('#learningPanel summary').click();
    const [download]=await Promise.all([page.waitForEvent('download'),page.locator('#learningExport').click()]);
    const backupPath=path.join(run,'aprendizaje.json'); await download.saveAs(backupPath);
    const backup=JSON.parse(await readFile(backupPath,'utf8'));
    assert.equal(backup.format,'ProyectoFacturas-learning'); assert.ok(backup.corrections.length);
    assert.deepEqual(Object.keys(backup).sort(),['brands','corrections','format','schemaVersion']);
    const original=backup.corrections[0].descripcion;
    backup.corrections[0].descripcion='Conflicto importado que debe conservar el valor local';
    backup.corrections.push({store:'FICTICIO E2E',firma:'producto adicional ficticio',descripcion:'Producto importado ficticio',marca:'Genérico',categoria:'Personal E2E',veces:1,ultimaVez:'2026-10-29'});
    backup.brands.push('MarcaFicticiaE2E');
    await page.locator('#learningFile').setInputFiles({name:'aprendizaje.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify(backup))});
    await message('learningPreview','1 correcciones y'); await message('learningPreview','1 diferencias');
    await page.locator('#learningCancel').click();
    const before=(await (await context.request.get(`${origin}/api/learning/backup`)).json()).corrections;
    assert.equal(before.length,backup.corrections.length-1);
    await page.locator('#learningFile').setInputFiles({name:'aprendizaje.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify(backup))});
    await page.locator('#learningRestore').click();
    await message('learningStatus','Restauración completada');
    const restored=await (await context.request.get(`${origin}/api/learning/backup`)).json();
    assert.ok(restored.corrections.some(value=>value.descripcion===original));
    assert.ok(restored.corrections.some(value=>value.descripcion==='Producto importado ficticio'));
    assert.ok(restored.brands.includes('MarcaFicticiaE2E'));
    await page.locator('#correctionsClose').click();
    console.log('OK: corrección con reintento, respaldo, vista previa, cancelación y prioridad de la memoria local.');

    const categories=JSON.parse(await readFile(path.join(root,'src/main/resources/category-defaults.json'),'utf8'));
    categories.categories.push('Categoría ficticia E2E'); categories.productRules=[{category:'Categoría ficticia E2E',keywords:['pan']}];
    await writeFile(path.join(run,'data/categories.json'),JSON.stringify(categories));
    await page.reload(); await extract('receipt.png');
    assert.ok((await page.locator('#categorySuggestions option').evaluateAll(options=>options.map(option=>option.value))).includes('Categoría ficticia E2E'));
    assert.equal(await page.locator('#itemsBody tr').first().locator('[data-field="categoria"] input').inputValue(),'Personal E2E');
    assert.equal(await page.locator('#itemsBody tr').nth(1).locator('[data-field="categoria"] input').inputValue(),'Categoría ficticia E2E');
    console.log('OK: categorías locales, sugerencias y precedencia de correcciones aprendidas.');

    await page.waitForFunction(()=>!document.getElementById('sheetsAppend').disabled);
    await control({loseAppend:true,failStatus:1}); await page.locator('#sheetsAppend').click();
    await page.waitForFunction(()=>!document.getElementById('sheetsAppend').disabled);
    let sheetState=await state(); assert.equal(sheetState.writes,1); assert.equal(sheetState.appendCalls,1);
    const journalBefore=await page.evaluate(()=>JSON.parse(sessionStorage.getItem('facturas.pendingSheetsTransfer')));
    assert.deepEqual(Object.keys(journalBefore).sort(),['destination','fingerprint','requestId']);
    await page.reload(); await extract('receipt.png'); await page.locator('#sheetsAppend').click();
    await page.waitForFunction(()=>!document.getElementById('sheetsAppend').disabled);
    sheetState=await state(); assert.equal(sheetState.writes,1); assert.equal(sheetState.appendCalls,1);
    assert.equal(sheetState.lookupCalls,1);
    const journalAfter=await page.evaluate(()=>JSON.parse(sessionStorage.getItem('facturas.pendingSheetsTransfer')));
    assert.equal(journalAfter.requestId,journalBefore.requestId);
    await page.locator('#sheetsAppend').click(); await message('sheetsAppendStatus','Ya se cargaron 2 ítems');
    assert.equal(await page.locator('#sheetsAppend').isDisabled(),true);
    sheetState=await state(); assert.equal(sheetState.writes,1); assert.equal(sheetState.appendCalls,1); assert.ok(sheetState.lookupCalls>=2);
    console.log('OK: Sheets recupera una respuesta perdida sin duplicar filas, incluso después de recargar.');

    await extract('two-pages.pdf'); assert.equal(await page.locator('#itemsBody tr').count(),4);
    await page.getByRole('button',{name:'Ver fila 3 en la imagen'}).click();
    assert.equal(await page.locator('#ocrReviewPage').inputValue(),'1');
    const native=await extract('digital.pdf');
    assert.equal(native.ocrReview[0].source,'pdf-text'); assert.ok(native.ocrReview[0].imageDataUrl);
    await page.getByRole('button',{name:'Ver fila 1 en la imagen'}).click();
    await page.locator('#ocrReviewCrop').waitFor({state:'visible'});
    assert.ok(await page.locator('#ocrReviewCrop').evaluate(canvas=>canvas.width>1));
    await message('ocrReviewSelectedConfidence','Texto digital');
    await page.screenshot({path:path.join(run,'native-pdf.png'),fullPage:true});
    const previousRows=await page.locator('#itemsBody').innerText();
    await control({ocrDelay:5}); await page.locator('#fileInput').setInputFiles(path.join(fixtureFiles,'receipt.png'));
    await page.locator('#submitButton').click(); await message('extractionPhase','Reconociendo');
    await page.locator('#cancelExtraction').click(); await message('status','cancelada');
    await page.locator('#cancelExtraction').waitFor({state:'hidden'});
    assert.equal(await page.locator('#itemsBody').innerText(),previousRows);
    await control({ocrDelay:0}); await extract('receipt.png');
    assert.deepEqual(pageErrors,[]);
    await page.evaluate(()=>scrollTo(0,0)); await page.screenshot({path:path.join(run,'desktop.png'),fullPage:true});
    await page.setViewportSize({width:390,height:844});
    assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'La página móvil no debe tener desborde horizontal.');
    await page.screenshot({path:path.join(run,'mobile.png'),fullPage:true});
    await page.locator('#correctionsOpen').click(); await page.locator('#learningPanel summary').click();
    await page.screenshot({path:path.join(run,'backup-mobile.png')});
    await page.locator('#correctionsClose').click(); await page.setViewportSize({width:1440,height:1000});
    const seeded=await context.request.post(`${origin}/api/corrections`,{data:{store:'AUXILIAR FICTICIO',
        corrections:Array.from({length:26},(_,i)=>({firma:`producto ficticio ${i}`,descripcion:`Producto ${i}`,marca:'Genérico',categoria:'Prueba'}))}});
    assert.equal(seeded.status(),200); await page.locator('#correctionsOpen').click();
    await page.waitForFunction(()=>!document.getElementById('correctionsFields').disabled);
    const adminInput=page.locator('#correctionsList form').first().getByLabel('Descripción',{exact:true});
    const beforeEdit=await adminInput.inputValue(); await adminInput.fill('Edición pendiente E2E');
    await page.locator('#correctionsNext').click(); await page.locator('#correctionsKeepEditing').click();
    assert.equal(await adminInput.inputValue(),'Edición pendiente E2E');
    await page.keyboard.press('Escape'); await page.locator('#correctionsDiscard').click();
    await page.locator('#correctionsDialog').waitFor({state:'hidden'});
    await page.locator('#correctionsOpen').click();
    await page.waitForFunction(()=>!document.getElementById('correctionsFields').disabled);
    assert.equal(await adminInput.inputValue(),beforeEdit);
    await adminInput.fill('Edición guardada E2E'); await page.locator('#correctionsClose').click();
    await page.locator('#correctionsSaveAll').click(); await page.locator('#correctionsDialog').waitFor({state:'hidden'});
    await page.locator('#correctionsOpen').click();
    await page.waitForFunction(()=>!document.getElementById('correctionsFields').disabled);
    assert.equal(await adminInput.inputValue(),'Edición guardada E2E');
    await page.locator('#correctionsClose').click(); assert.deepEqual(pageErrors,[]);
    console.log('OK: pegado real de imagen, filtros, teclado, PDF multipágina y protección de ediciones administrativas.');
    console.log('OK: PDF digital con recorte, progreso real, cancelación y extracción posterior.');

    // OAuth/Picker simulation: real popup, source/origin validation and modal handoff, no Google credentials.
    let connected=true;
    await page.route('**/api/sheets/config',async route=> {
        const response=await route.fetch(); const config=await response.json();
        await route.fulfill({json:{...config,googleConnected:connected,oauthConfigured:true}});
    });
    await page.route('**/api/sheets/oauth/disconnect',route=> {connected=false;return route.fulfill({json:{message:'Google desconectado.'}});});
    await page.route('**/api/sheets/oauth/connect',route=>route.fulfill({json:{authorizationUrl:'https://accounts.google.com/o/oauth2/v2/auth?state=fixture'}}));
    await page.route('**/api/sheets/oauth/picker',route=>route.fulfill({json:{accessToken:'synthetic-picker-token',developerKey:'synthetic-public-key',appId:'123'}}));
    await context.route('https://accounts.google.com/o/oauth2/v2/auth?state=fixture',route=>route.fulfill({
        contentType:'text/html',body:`<script>location.replace('${origin}/__test__/google-callback');</script>`}));
    await context.route(`${origin}/__test__/google-callback`,route=> {
        connected=true;
        return route.fulfill({contentType:'text/html',body:`<script>window.opener.postMessage({type:'facturas-google-oauth',success:true},'${origin}');window.close();</script>`});
    });
    const ticketBeforeGoogle=await page.locator('#tsvOutput').inputValue();
    await page.locator('#sheetsSettingsOpen').click();
    await page.locator('#sheetsGoogleDisconnect').click(); await message('sheetsConfigStatus','desconectado');
    assert.equal(await page.locator('#sheetsGoogleChoose').isDisabled(),true);
    await page.locator('#sheetsGoogleConnect').click(); await message('sheetsConfigStatus','Google conectado');
    await page.waitForFunction(()=>!document.getElementById('sheetsGoogleChoose').disabled);
    await page.evaluate(()=> {
        class DocsView {setMimeTypes(){return this;}setIncludeFolders(){return this;}setSelectFolderEnabled(){return this;}}
        class PickerBuilder {
            addView(){return this;}setOAuthToken(){return this;}setDeveloperKey(){return this;}setAppId(){return this;}
            setOrigin(){return this;}setLocale(){return this;}setTitle(){return this;}
            setCallback(callback){window.__pickerCallback=callback;return this;}
            build(){return {setVisible(){window.__pickerOpen=true;},dispose(){window.__pickerOpen=false;}};}
        }
        window.google={picker:{DocsView,PickerBuilder,ViewId:{SPREADSHEETS:'sheets'},Action:{PICKED:'picked',CANCEL:'cancel'}}};
    });
    const previousDestination=await page.locator('#sheetsSpreadsheet').inputValue();
    await page.locator('#sheetsGoogleChoose').click(); await page.waitForFunction(()=>window.__pickerOpen);
    assert.equal(await page.locator('#sheetsSettingsDialog').evaluate(dialog=>dialog.open),false);
    await page.evaluate(()=>window.__pickerCallback({action:'cancel'}));
    await message('sheetsConfigStatus','Selección cancelada');
    assert.equal(await page.locator('#sheetsSpreadsheet').inputValue(),previousDestination);
    await page.locator('#sheetsGoogleChoose').click(); await page.waitForFunction(()=>window.__pickerOpen);
    await page.evaluate(()=>window.__pickerCallback({action:'picked',docs:[{id:'synthetic_selected_sheet_123456789',mimeType:'application/vnd.google-apps.spreadsheet',name:'Compras elegidas'}]}));
    await message('sheetsConfigStatus','Planilla elegida: Compras elegidas');
    assert.match(await page.locator('#sheetsSpreadsheet').inputValue(),/synthetic_selected_sheet_123456789/);
    assert.equal(await page.locator('#sheetsSpreadsheet').getAttribute('readonly'),'');
    assert.equal(await page.locator('#tsvOutput').inputValue(),ticketBeforeGoogle);
    assert.equal(await page.evaluate(()=>JSON.stringify({...localStorage,...sessionStorage}).includes('synthetic-picker-token')),false);
    await page.screenshot({path:path.join(run,'google-settings-desktop.png')});
    await page.setViewportSize({width:390,height:844});
    assert.ok(await page.locator('#sheetsSettingsDialog').evaluate(dialog=>dialog.scrollWidth<=dialog.clientWidth));
    await page.screenshot({path:path.join(run,'google-settings-mobile.png')});
    await page.locator('#sheetsSettingsClose').click(); assert.deepEqual(pageErrors,[]);
    console.log('OK: OAuth con popup simulado, desconexión, cancelación/selección de planilla y conservación del ticket en escritorio/móvil.');
    console.log(`E2E completo. Evidencias y registros ficticios: ${run}`);
} catch(error) {
    if(page&&!page.isClosed())await page.screenshot({path:path.join(run,'failure.png'),fullPage:true}).catch(()=>{});
    throw error;
} finally {
    if(browser)await browser.close(); stopOwnChildren();
}
