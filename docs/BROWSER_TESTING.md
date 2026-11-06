# Pruebas de navegador con datos ficticios

El fixture sirve la UI fuente real y delega extracción, render de PDF y memoria a un backend Java aislado. Simula las respuestas OCR y Sheets, sin credenciales ni conexión a Google. Complementa los tests de contratos con motor simulado y las evaluaciones separadas del motor PaddleOCR real.

## Ejecución automática

Requiere Java 17, Node, Python 3.11 con Pillow, Playwright disponible como herramienta de desarrollo y Microsoft Edge en Windows (Chromium de Playwright en otros sistemas). No agrega npm ni un bundler al frontend. Si Playwright no está en la resolución habitual de Node, indicá la ruta absoluta del paquete ya instalado:

```powershell
$env:APP_PLAYWRIGHT_MODULE = 'C:/herramientas/playwright/node_modules/playwright'
mvn package -DskipTests
node scripts/browser_e2e.mjs
# O incluirlo en todas las comprobaciones locales:
./scripts/check.ps1 -Browser
```

`APP_PYTHON`, `APP_JAVA` y `APP_BROWSER_EXECUTABLE` permiten elegir los ejecutables; Python usa `.venv` por defecto. El script exige que 18081/18082 estén libres, crea un workspace nuevo en `logs/browser-e2e-<fecha>/` y arranca sus propios procesos sin ventanas. No copia datos personales ni usa el perfil del navegador. Bloquea las peticiones externas del navegador y detiene únicamente los procesos que inició.

El recorrido comprueba extracción, cantidades, recortes, portapapeles real TSV con siete columnas, correcciones y reintento, descarga del respaldo, vista previa/cancelación/restauración, conflictos con prioridad local, categorías configuradas y recuperación de Sheets después de recargar sin duplicar filas. También comprueba un PDF digital con recorte y la cancelación durante el OCR, conservando la tabla anterior y permitiendo una extracción nueva. Las capturas de escritorio/móvil y logs quedan en el workspace ignorado. Un fallo devuelve código distinto de cero y guarda `failure.png` cuando el navegador llegó a abrirse.

El flujo OAuth usa una ventana emergente real con respuesta de Google simulada, prueba desconexión, selección y cancelación de planilla, el cierre temporal del diálogo modal para usar Picker y la conservación de las filas del ticket. Las capturas `google-settings-desktop.png` y `google-settings-mobile.png` documentan el panel. No autentica cuentas reales ni prueba permisos remotos; eso requiere el alta de `docs/GOOGLE_SHEETS.md`.

## Preparar el entorno

El ejecutor automático también prueba pegado real de una imagen desde el portapapeles, filtro de pendientes, marcar revisión con teclado y recorte de la tercera fila de un PDF de dos páginas. En el administrador comprueba que paginar/Escape conservan cambios, que descartar recupera el valor guardado y que guardar antes de cerrar persiste la edición. Los tests siguen usando OCR y Sheets simulados; el rendimiento y precisión del motor real se miden con `scripts/benchmark_local.py`.

Desde la raíz del repositorio, con Python 3.11 y las dependencias OCR instaladas:

```powershell
mvn package -DskipTests
./.venv/Scripts/python.exe scripts/browser_fixture.py
```

El fixture escucha solo en `127.0.0.1:18082`, genera una imagen, un PDF escaneado de dos páginas y `digital.pdf` con texto en `test-data/browser-e2e/`, y admite únicamente el backend `127.0.0.1:18081`. En otra terminal PowerShell, desde la raíz:

```powershell
$fixtureRoot = (Get-Location).Path
$fixtureWorkspace = Join-Path $fixtureRoot 'logs/e2e-workspace'
New-Item -ItemType Directory -Force -Path "$fixtureWorkspace/data" | Out-Null
# No copiar la configuración ni memoria personal a este workspace.
Copy-Item -LiteralPath "$fixtureRoot/target/facturas-ocr-0.0.1-SNAPSHOT.jar" -Destination "$fixtureWorkspace/app.jar"
Set-Location -LiteralPath $fixtureWorkspace
java "-Djdk.net.unixdomain.tmpdir=$fixtureWorkspace/java-internal-loopback-sockets-windows-temporary-directory" -jar ./app.jar --server.port=18081 --app.ocr.endpoint=http://127.0.0.1:18082/ocr --app.ocr.health-endpoint=http://127.0.0.1:18082/health
```

La carpeta aislada no debe contener tokens, historial ni configuración de Google. Ambos procesos se detienen con Ctrl+C en sus terminales. La aplicación habitual en 8080 no interviene.

## Recorrido verificable

1. Abrir `http://127.0.0.1:18082/`, cargar `test-data/browser-e2e/receipt.png` y extraer. Deben aparecer dos filas y un total de 1600; la primera queda ambigua por confianza del precio.
2. Usar **Ver en imagen**: el recorte contiene descripción, multiplicador y precio. Filtrar pendientes, marcar revisada y volver a Todas. Una edición reabre la revisión y actualiza las siete columnas exportadas.
3. Simular una falla de guardado con el control siguiente, editar la descripción y esperar el debounce. Verificar aviso, **Reintentar guardado** y confirmación. Administrar la entrada, buscarla, guardar una edición, iniciar eliminación y cancelarla.
4. Simular una respuesta perdida al cargar y una falla de consulta. Cargar, reintentar y verificar que se mantiene una sola escritura. Copiar la salida, recargar la página, volver a elegir la imagen y extraer. La descripción aprendida vuelve junto con **Ambiguo** y **Memorizado**. Reintentar recupera el resultado guardado.
5. Cargar `two-pages.pdf`: cuatro filas, total 3200, dos páginas. **Ver en imagen** de la tercera fila abre la página dos. La advertencia de total impreso es esperable: las dos páginas sintéticas repiten el mismo ticket.

Controles opcionales, desde otra terminal; afectan exclusivamente al fixture:

```powershell
Invoke-RestMethod http://127.0.0.1:18082/__test__/control -Method Post -ContentType application/json -Body '{"failCorrections":1}'
Invoke-RestMethod http://127.0.0.1:18082/__test__/control -Method Post -ContentType application/json -Body '{"loseAppend":true,"failStatus":1}'
Invoke-RestMethod http://127.0.0.1:18082/__test__/control -Method Post -ContentType application/json -Body '{"ocrDelay":5}'
Invoke-RestMethod http://127.0.0.1:18082/__test__/state
```

Tras la recuperación del paso cuatro, `writes=1`, `appendCalls=1`, `lookupCalls=2`. El fixture conserva sus escrituras solo en memoria; sus correcciones se guardan en el workspace Java aislado. Reiniciarlo crea un destino ficticio vacío; usar una sesión nueva del navegador para otro recorrido completo.
