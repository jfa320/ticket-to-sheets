# Pruebas de navegador con datos ficticios

El fixture sirve la UI fuente real y delega extracción, render de PDF y memoria a un backend Java aislado. Simula las respuestas OCR y Sheets, sin credenciales ni conexión a Google. Complementa los tests de contratos con motor simulado y las evaluaciones separadas del motor PaddleOCR real.

## Preparar el entorno

Desde la raíz del repositorio, con Python 3.11 y las dependencias OCR instaladas:

```powershell
mvn package -DskipTests
./.venv/Scripts/python.exe scripts/browser_fixture.py
```

El fixture escucha solo en `127.0.0.1:18082`, genera una imagen y un PDF de dos páginas en `test-data/browser-e2e/`, y admite únicamente el backend `127.0.0.1:18081`. En otra terminal PowerShell, desde la raíz:

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
Invoke-RestMethod http://127.0.0.1:18082/__test__/state
```

Tras la recuperación del paso cuatro, `writes=1`, `appendCalls=1`, `lookupCalls=2`. El fixture conserva sus escrituras solo en memoria; sus correcciones se guardan en el workspace Java aislado. Reiniciarlo crea un destino ficticio vacío; usar una sesión nueva del navegador para otro recorrido completo.
