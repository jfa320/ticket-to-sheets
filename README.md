# Extractor local de facturas con Spring Boot

App local para subir una foto o PDF de un ticket, correr OCR gratis con PaddleOCR en Docker y devolver filas para copiar o cargar directamente en un Google Sheet configurable.

## Lectura rápida

La opción predeterminada **Rápida** busca resolver cada ticket en menos de 10 segundos. Hace una lectura completa, intenta otra orientación solo cuando no obtiene texto útil y relee un importe dudoso únicamente si queda margen para confirmarlo. Conserva las marcas de revisión cuando no puede confirmar una lectura. **Detallada** mantiene la búsqueda de variantes con un presupuesto de 90 segundos para fotos difíciles.

El presupuesto rápido es de 8 segundos de OCR; no es un límite absoluto sobre una llamada nativa ya iniciada. La pantalla muestra el tiempo total observado. Los PDFs de varias páginas, las imágenes difíciles o un equipo ocupado pueden superar el objetivo. La preparación inicial de los modelos ocurre antes de que el OCR se anuncie listo.

El motor usa hasta cuatro hilos de CPU y aceleración MKL-DNN. `OCR_CPU_THREADS` permite elegir entre 1 y 16; `OCR_ENABLE_MKLDNN=false` permite usar el motor compatible. Si la carga o el precalentamiento optimizado falla, se intenta el compatible automáticamente. Son parámetros del [motor PaddleOCR 2.x](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version2.x/ppocr/blog/inference_args.md).

En lectura rápida, un JPEG validado se envía directamente al OCR: evita convertirlo a PNG y conserva su orientación EXIF. Las otras imágenes y páginas renderizadas de PDF mantienen la preparación habitual. `APP_OCR_SEARCH_BUDGET_SECONDS` configura el presupuesto predeterminado del backend; el selector web envía 8 o 90 segundos por solicitud.

## Que hace

- conserva los atributos de los productos al expandir abreviaturas: tipo, sabor, presentación y unidades. La incertidumbre de cantidades y precios también se conserva al aplicar o recuperar memoria.
- calcula totales con decimales exactos y el mismo redondeo en Java y JavaScript, incluidas cantidades fraccionarias y bonificaciones.

- permite abrir el recorte de una fila con “Ver en imagen”, filtrar filas ambiguas pendientes y marcarlas como revisadas. Editar una fila reabre su revisión; el estado OCR y la memoria permanecen visibles.
- ofrece “Administrar correcciones aprendidas”: búsqueda por comercio/texto, edición y eliminación individual. Los cambios afectan futuras extracciones. Si otra edición modificó la misma entrada, pide recargarla antes de guardar.
- permite descargar y restaurar un respaldo de correcciones y marcas desde ese diálogo, con vista previa y prioridad para los datos locales.
- muestra fase y página durante la extracción y permite cancelarla conservando el ticket anterior. También muestra recortes de PDFs con texto digital.
- permite configurar categorías y reglas por producto/comercio desde un JSON local, manteniendo la edición libre por fila.

### Validación completa local

```powershell
./scripts/check.ps1
# Para elegir el entorno Python:
./scripts/check.ps1 -Python ./.venv/Scripts/python.exe
# Además, validar Compose y construir las imágenes:
./scripts/check.ps1 -Docker
# Con Playwright y navegador disponibles, sumar el recorrido E2E aislado:
./scripts/check.ps1 -Browser
```

Requiere Java 17/Maven, Node y Python **3.11** con `ocr/requirements.txt`. No instala dependencias. Falla ante comandos faltantes, errores o pruebas Python omitidas. Tanto `check.ps1` como `local.ps1` usan `-Python`, luego `APP_PYTHON`, luego `.venv` y finalmente Python del PATH. Una selección explícita inválida o una versión distinta de 3.11 produce un error claro. Docker es opcional y `-Docker` falla si no está instalado. También se comprueban los módulos locales incluidos por el Dockerfile; esta comprobación no ejecuta Docker.

Para preparar el entorno con una instalación local de Python 3.11:

```powershell
py -3.11 -m venv .venv
./.venv/Scripts/python.exe -m pip install -r ocr/requirements.txt
./scripts/check.ps1
```

Para medir precisión y tiempos, ver [la guía de evaluación](docs/OCR_EVALUATION.md). El [entorno de pruebas de navegador](docs/BROWSER_TESTING.md) permite verificar el flujo con datos ficticios y un destino Sheets simulado.

### Extracción y revisión

- permite pegar una imagen del portapapeles con `Ctrl+V` (`⌘V` en Mac) sobre la página para dejarla seleccionada, igual que al elegir un archivo, y después presionar `Extraer datos`. Mantiene los mismos límites de carga. En campos editables y diálogos se conserva el pegado habitual; durante una extracción o carga a Sheets no se cambia el archivo
- extrae texto de imagen o PDF con PaddleOCR
- prueba primero la orientación con imágenes de hasta un megapíxel y 700 píxeles de lado corto, y concentra las mejoras en la más legible. Prefiere las lecturas completas a esas sondas; reutiliza lecturas de imágenes con píxeles idénticos
- prioriza variantes que asocian productos e importes, reduciendo el puntaje de precios sueltos en tickets inclinados
- conserva el color y el texto tenue como candidato OCR; compara el original con versiones recortadas y mejoradas
- compara también variantes con inclinación/perspectiva corregidas y con contraste local o sombras compensadas, conservando el original como alternativa
- relee hasta ocho zonas de baja confianza de la variante elegida, con recortes ampliados y dos tratamientos; acepta cambios cuando ambas lecturas coinciden y mejoran la confianza, conservando las cajas para evitar duplicados
- combina lecturas de distintas variantes para corregir zonas débiles de cada renglón, cuando dos imágenes distintas coinciden con mayor confianza y sus coordenadas son compatibles
- resalta las zonas dudosas sobre la imagen procesada y permite ampliar cada recorte a 2×, 4× u 8×, con navegación por página en PDF
- procesa los tickets largos en bloques solapados para conservar el detalle, reunificando coordenadas y detecciones repetidas del mismo renglón
- detecta fecha y lugar de compra; admite fechas sin etiqueta, con `/`, `-` o `.`, además de las etiquetadas
- reconoce el encabezado de PedidosYa `Entregado mié 30 de sept · 22:04 hs`, incluso separado por el OCR, con meses completos o abreviados; si falta el año usa el año actual (revisarlo en pedidos antiguos)
- solo en PedidosYa, agrega el cargo o tarifa de servicio como una fila con cantidad 1 e importe leído del comprobante, incluido en el total y en la exportación; si falta el precio, avisa para revisión
- avisa cuando el año de la fecha fue inferido; mantiene la regla del año actual y permite corregirlo antes de exportar
- corrige confusiones `O`/`0` y sufijos OCR en presentaciones explícitas, como `1ke` → `1kg` o `8ogf` → `80gr`, conservando importes, cantidades y firma original
- prioriza variantes OCR que conservan la fecha de entrega de Market y excluye compensaciones, cupones y botones de las filas de productos
- arma filas con estas columnas: `Descripción|Marca|Lugar de compra|Categoria|Cantidad|Precio unitario|Fecha`
- usa las cajas OCR para interpretar columnas de descripcion, cantidad, precio unitario e importe cuando el ticket viene en formato tabular
- muestra un `Total calculado` debajo de las filas, sumando cantidad por precio unitario de los items extraidos (nunca el total del OCR); en Pigmento resta las bonificaciones reconocidas por separado
- interpreta el formato de Pigmento con códigos y precios en filas separadas, excluye la alícuota `(21.00)` y los bloques de pago/pie, conserva unidades repetidas y expande abreviaturas de Nivea, Pantene y Colgate
- muestra en Pigmento subtotal, bonificaciones editables y total final; los descuentos necesitan un importe negativo legible. Las bonificaciones marcadas se copian y cargan en la planilla como filas `Descuento`, marca `Genérico`, cantidad `1`, precio negativo y comercio/categoría/fecha del ticket. Al quitar productos, revisá qué bonificaciones siguen correspondiendo
- descarta lineas de subtotal, total y monto a pagar; usa `Genérico` cuando no reconoce la marca
- conserva productos con `iva` dentro de su nombre, incluso si el OCR une marca y descripción, y sigue descartando etiquetas fiscales de IVA
- corrige marcas OCR con Levenshtein: menos de 30% usa `Genérico`, de 30% a 70% deja la marca dudosa para revisar y más de 70% aplica la marca automáticamente
- genera dos salidas:
  - formato con `|` para guardar o copiar
  - formato tabulado sin encabezado para pegar directo en Google Sheets
- muestra una tabla editable para corregir o eliminar filas antes de copiar
- conserva el ticket actual y sus ediciones mientras procesa otro archivo; si la nueva extracción falla, mantiene el total, las salidas y la revisión OCR anteriores
- avisa antes de cerrar o recargar la página si el ticket no se copió ni se cargó correctamente, o si después hubo cambios pendientes
- cancela la extracción en curso al recargar o salir de la página; descarta el resultado anterior y permite procesar otra factura. Si PaddleOCR está dentro de una llamada nativa, espera su finalización y no inicia más pasadas ni páginas del trabajo cancelado
- permite guardar un destino de Google Sheets y cargar las filas seleccionadas con un botón, con autorización OAuth de Google y selección de archivo
- marca filas ambiguas y muestra advertencias completas cuando una línea no pudo confirmarse
- conserva la baja confianza de productos, cantidades y precios de PedidosYa; una fila puede mostrar **Ambiguo** y **Memorizado** a la vez, porque aprender el nombre no confirma el precio del ticket nuevo
- aprende de tus correcciones (marca, categoría y descripción) y las aplica en el próximo ticket del mismo comercio
- usa el historial de Google Sheets o Excel para completar productos conocidos, incluso con errores OCR y abreviaturas; las coincidencias dudosas se muestran para revisión
- muestra tambien el texto OCR crudo para depurar

## Requisitos

- Docker Desktop

## Levantar con Docker Compose

1. Abre Docker Desktop.
2. Desde la carpeta del proyecto ejecuta:

```bash
docker compose up --build -d
```

3. En el primer arranque el contenedor OCR descarga y prepara los modelos. Compose comprueba `ocrReady` y mantiene la app esperando hasta que OCR está listo; esa primera preparación puede tardar.
4. Abre la app en:

```text
http://localhost:8080
```

El puerto de la app se publica solo en `127.0.0.1:8080`, accesible desde esta PC. El servicio OCR no se expone al host en el flujo completo; solo Spring Boot lo llama dentro de la red Docker usando `http://paddleocr:5000`. En ejecución nativa, Spring Boot y OCR también escuchan en `127.0.0.1` por defecto.

Se admite una extracción a la vez, incluso entre distintas pestañas. Si otra ya está en curso, la aplicación indica que esperes a que termine y reintentes. El OCR conserva esa protección aunque una conexión se corte mientras sigue procesando la imagen.

## Ver logs

```bash
docker compose logs -f app paddleocr
```

## Apagar

```bash
docker compose down
```

## Validacion local

En Windows, con Java/Maven y Python con las dependencias OCR instaladas, el arranque local seguro es:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/local.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/local.ps1 -Action Stop
# Abrir el navegador al terminar el arranque en 8080:
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/local.ps1 -OpenBrowser
```

El arranque espera `ocrReady`, conserva los logs en `logs/` y ejecuta una copia del JAR en `logs/runtime/`, separada de `target/`. Así una nueva compilación no bloquea ni altera el archivo en ejecución. Detiene primero las instancias antiguas que ejecutaban directamente `target/` y comprueba la respuesta HTTP antes de anunciar que la app está lista. Reutiliza un OCR que todavía está arrancando y detecta si los procesos recién lanzados terminan con error. La espera de modelos es de hasta 300 segundos; se puede ampliar, por ejemplo, con `-OcrStartupTimeoutSec 600`.

El arranque usa siempre 8080 por defecto, aunque una ejecución anterior haya usado otro puerto. Si está ocupado por un proceso ajeno, informa el conflicto sin cambiar de puerto ni detener ese proceso. `-Port` permite elegir otro explícitamente. El puerto de la instancia se guarda en `logs/app.port` únicamente para que el apagado la encuentre. `-OpenBrowser` abre la URL correcta cuando la app está lista. Los accesos directos pueden delegar la apertura al script con esta opción.

La URL de retorno de Google usa el puerto elegido (`http://localhost:<puerto>/api/sheets/oauth/callback`), salvo que se indique `APP_GOOGLE_REDIRECT_URI`. Para conectar Google en otro puerto, registrá esa URL y su origen en la configuración de Google siguiendo [la guía de Sheets](docs/GOOGLE_SHEETS.md). Si configurás `APP_GOOGLE_REDIRECT_URI`, su puerto debe coincidir con el de la app.

En Windows, las rutas de modelos PaddleOCR usan el nombre corto de la caché cuando el perfil contiene acentos, sin cambiar `HOME` ni `USERPROFILE`. Se reutilizan los modelos descargados. Si Windows no dispone de un nombre corto utilizable, el OCR informa el problema en el log.

Para permitir acceso desde otro dispositivo de la red local, iniciá la app nativa con:

```powershell
$env:SERVER_ADDRESS = '0.0.0.0'
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/local.ps1
```

Abrí `http://<IP-local-de-la-PC>:8080` desde un dispositivo de la misma red y permití TCP 8080 en el firewall solo desde esa subred. El OCR permanece en `127.0.0.1:5000`. La extracción y los reintentos de Sheets funcionan por HTTP local sin depender de `crypto.randomUUID` ni de `crypto.subtle`. Los botones de copiar usan una alternativa por selección de texto si el navegador restringe el portapapeles moderno en HTTP. Si también bloquea esa alternativa, se informa el error y se puede seleccionar y copiar el texto de salida manualmente.

```bash
mvn test
python -m unittest discover -s ocr/tests
node --test src/test/js/*.test.mjs
```

Las comprobaciones del script de arranque Windows, con procesos simulados, se ejecutan con:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/local.tests.ps1
```

Los tests Java incluyen fixtures sintéticos en `src/test/resources/receipt-evaluation` y reportan aciertos exactos por campo. El banco cubre texto OCR multipágina, cantidad/precio separados, líneas de precio sueltas y detecciones geométricas, sin versionar tickets reales.

Los tests Python cubren conservación de texto tenue, geometría, iluminación, variantes de imagen, bloques solapados, separación de renglones, elección por confianza y el contrato Flask con el motor OCR simulado. Requieren las dependencias de `ocr/requirements.txt`; no descargan modelos. La precisión del reconocimiento real debe comprobarse también con tickets locales.

Para comparar el texto OCR antes y después de las nuevas correcciones, se incluye `ocr/evaluate.py`. Mide errores de caracteres, coincidencias de líneas e importes y tiempo por caso usando el backend local. La [guía de evaluación](docs/OCR_EVALUATION.md) explica cómo preparar transcripciones, generar informes y comparar los mismos archivos. Los tickets y resultados se guardan en `test-data/`, ignorado por Git.

La búsqueda adaptativa está habilitada por defecto (`OCR_ADAPTIVE_SEARCH=true`). Prueba el original en distintas orientaciones antes de aplicar todos los tratamientos, prioriza la orientación más legible y compara primero correcciones sobre la imagen completa para conservar texto en sombra. Se detiene si tres imágenes distintas coinciden en productos, importes, fechas y total con confianza alta; no usa ese total para calcular la compra. Los precios sueltos y las lecturas incompletas impiden esa parada anticipada.

`OCR_SEARCH_BUDGET_SECONDS=90` limita el tiempo dedicado a buscar variantes y releer zonas dudosas **por imagen o página**. Cuando la relectura está activa, se reservan hasta 12 segundos (15% del presupuesto) para que las variantes no consuman toda esa etapa. Se priorizan fechas y precios dudosos. El presupuesto se comprueba entre pasadas: una llamada PaddleOCR ya iniciada termina antes de devolver la mejor lectura disponible, por lo que no es un timeout estricto. La duración y cantidad de pasadas aparecen en los logs. Para una evaluación exhaustiva, definí `OCR_ADAPTIVE_SEARCH=false`; así se comparan todas las variantes, sin presupuesto ni reutilización de lecturas. En ejecución nativa, configurá estas variables antes de iniciar Python; con Compose pueden definirse al levantar los servicios.

Las correcciones de imagen se habilitan por defecto. Se pueden desactivar definiendo `OCR_IMAGE_CORRECTIONS=false` al levantar Compose. El original y las alternativas anteriores siguen disponibles en ambos modos.

La relectura selectiva también está habilitada por defecto y se controla por separado con `OCR_TARGETED_RETRY=false`. Agrega como máximo 16 llamadas OCR pequeñas por imagen, después de elegir la mejor variante. Si acepta alguna mejora, el nombre de la variante termina en `+retry`. La guía incluye una comparación específica con esta opción activada y desactivada.

También puede revisar precios con confianza entre 0,80 y 0,97 y fechas incompletas. Estos campos exigen coincidencia de dos lecturas de detalle con confianza mínima de 0,98; un precio además debe mejorar al menos 0,02 respecto de la lectura original. La relectura conserva las cajas y nunca modifica un importe para que coincida con el total.
En variantes de encuadre completo, los recortes de detalle usan los píxeles originales y vuelven a las coordenadas de la variante elegida. Las variantes recortadas o con perspectiva conservan su propia imagen para mantener alineadas las zonas.

La combinación de variantes está habilitada por defecto; `OCR_VARIANT_FUSION=false` permite desactivarla. Reutiliza lecturas ya realizadas, sin llamadas adicionales al motor. Solo modifica zonas con confianza inferior a 0,80 cuando al menos dos imágenes distintas coinciden, alcanzan 0,85 y mejoran la confianza en al menos 0,10. Conserva las cajas, los renglones repetidos y el texto anterior ante contradicciones. Una mejora aceptada agrega `+fusion` al nombre de la variante, antes del posible `+retry`.

## Desarrollo local sin Docker para Spring Boot

Si queres correr Spring Boot desde Maven y dejar solo el OCR en Docker:

```bash
docker compose -f compose.yaml -f compose.local-ocr.yaml up --build -d paddleocr
mvn spring-boot:run
```

El archivo adicional publica el OCR solo en `127.0.0.1:5000`, para que el backend nativo pueda llamarlo. No es necesario para el flujo completo de Compose. Para detener el OCR de este modo usá `docker compose -f compose.yaml -f compose.local-ocr.yaml down`.

Si el OCR todavía prepara los modelos, la extracción informa ese estado; si está apagado, pide iniciar el servicio local. Los chequeos de disponibilidad tienen un timeout independiente de 2 segundos para conexión y lectura (`app.ocr.health-timeout-ms`), con hasta tres intentos y pausas breves. La lectura OCR conserva su timeout de cinco minutos por llamada. Una lectura que perdió su respuesta no se repite automáticamente, porque podría seguir ejecutándose en Python.

## Flujo recomendado

1. Subi una foto bien centrada del ticket.
2. Si la foto tiene mucho fondo, mano o monitor alrededor, intenta que el ticket ocupe la mayor parte de la imagen.
3. Revisa las filas ambiguas y las advertencias; edita o elimina lo que corresponda.
4. Si configuraste la conexión, usá `Cargar en Google Sheets` para enviar las filas seleccionadas y corregidas.
5. También podés usar `Copiar tabla para Google Sheets` y pegar las filas sin encabezado manualmente.
6. Si algun item sale raro, revisa el bloque `Texto OCR crudo`.

En **Revisar zonas dudosas en la imagen**, elegí una zona resaltada o su texto en la lista para comparar la lectura con el recorte. Se muestran inicialmente las zonas con confianza menor a 0,80 o sin confianza informada; **Mostrar todas las zonas** permite revisar también las demás. Usá **Ampliación** para elegir 2×, 4× u 8× y desplazate dentro del recorte. En documentos de varias páginas, cambiá de página con el selector. Las zonas también se pueden seleccionar con Tab y Enter o Espacio.

La imagen corresponde a la variante procesada por el OCR, por lo que puede estar girada, recortada o ajustada respecto del archivo original. La vista previa se genera en memoria y viaja en la respuesta local; no se guarda como historial. Si no hay imagen disponible, el texto sigue visible. Las páginas leídas como texto digital de un PDF se identifican como tales y no muestran confianza OCR ni recortes. Las correcciones de productos se hacen en la tabla editable.

## Conexión con Google Sheets

La [guía de configuración](docs/GOOGLE_SHEETS.md) explica el alta inicial del cliente OAuth y Google Picker en Google Cloud. Después abrí el engranaje, pulsá **Conectar con Google**, aceptá los permisos y usá **Elegir planilla de Google**. Guardá el año y la fila de encabezados y comprobá la conexión. Podés asociar meses a pestañas con nombres personalizados. Solo se solicita `drive.file`, para los archivos autorizados con el selector.

La plantilla de compras usa encabezados en la fila 2: las siete columnas exportadas en A:G, `Precio total` en H y `Comentarios` en I. La carga calcula H como cantidad × precio unitario, conserva I y los resúmenes laterales y almacena números y fechas como valores de hoja de cálculo. La fecha de las filas seleccionadas propone el mes de destino; podés elegir otra pestaña para cargar tickets atrasados sin cambiar sus fechas.

El destino se guarda en `data/sheets-config.json`. La conexión persistente se guarda en `data/google-oauth-tokens.json`; ambos archivos están ignorados por Git y excluidos del contexto de build de Docker. El token de acceso permanece en memoria del backend y se entrega temporalmente al navegador solo para Google Picker; el token de renovación y el secreto OAuth permanecen en el backend. **Desconectar Google** borra la conexión local y solicita revocar la autorización. Cada carga lleva un identificador para reconocer reintentos y evitar duplicados de la misma operación. Si se pierde la respuesta, la sesión conserva el identificador, destino y huella del intento, sin guardar productos, imágenes ni texto OCR. Tras recargar, al volver a enviar los mismos datos se comprueba primero si ya quedaron guardados. Si los datos cambiaron, la pantalla pide revisar la carga anterior antes de continuar. Hace falta permitir almacenamiento de sesión en el navegador.

## Aprendizaje de correcciones

En fotos inclinadas, el parser ajusta las posiciones de las cajas OCR antes de asociar nombres y precios. El listado termina en subtotal/total/recibido para no convertir el pie fiscal o el pago en productos. Admite decimales OCR como `3400,C0` y conserva la cantidad fraccionaria cuando el importe de línea es legible aunque falle el precio unitario. Si reconstruye un multiplicador sin `×`, la fila queda para revisión. Una diferencia con un total OCR legible se informa como advertencia; el total de la app siempre se calcula con las filas y descuentos reconocidos.

También reconoce por estructura los tickets que imprimen primero el código de barras y los importes, y debajo la descripción. Admite código/importes separados en varias líneas, cantidades como `2X1500.00`, separadores argentinos o internacionales y alícuotas entre paréntesis. Conserva productos repetidos y las zonas OCR de todo el bloque para **Ver en imagen**. El contador `Artículos: 18.00` cierra el listado y no se convierte en un producto.

Cuando el OCR pega un código EAN de 8 o 13 dígitos a la cantidad (`40000000000012X1500.00`), los separa antes de interpretar el multiplicador. Las reglas genéricas no admiten cadenas largas como cantidades. Si el OCR pierde el separador decimal del unitario pero conserva el importe final, usa ese importe y la cantidad legible y pide revisión.

Si cantidad × precio unitario no coincide con el importe de línea, conserva ese importe y marca la fila para revisión. Si falta el precio de un bloque, conserva el producto con el precio vacío para completarlo o excluirlo antes de cargarlo en Sheets. Los códigos con importes sin descripción y las líneas descriptivas sin precio dentro del listado generan una advertencia de **Extracción parcial**. Revisá esas advertencias incluso si el total coincide: la suma por sí sola no confirma las asociaciones entre productos y precios.

Además de las correcciones manuales, podés usar las compras que ya registraste en Google Sheets. Tras guardar y comprobar el destino, pulsá **Actualizar historial**. Se leen las cuatro primeras columnas de las pestañas compatibles del mismo archivo: descripción, marca, comercio y categoría. El catálogo se guarda en `data/sheets-history.json`, ignorado por Git y persistido en el volumen local de Docker.

Las próximas extracciones consultan ese catálogo sin conectarse a Google. Una coincidencia exacta o una aproximación clara del texto original, dentro del mismo comercio y sin contradicciones, completa descripción, marca y categoría; aparece como **Historial**. El cruce admite palabras reordenadas, errores OCR, abreviaturas comunes y unidades equivalentes como `500gr`/`500 g`. Un nombre descriptivo sin marca también puede completarse cuando identifica un único producto. Si compiten marcas/categorías, cambia el tamaño o sabor, o el precio requiere revisión, se conserva la fila y se muestran sugerencias cuando corresponda. Cantidad, precio y fecha siempre vienen del ticket nuevo; las correcciones manuales **Memorizadas** tienen prioridad. No se entrena nuevamente PaddleOCR.

También podés incorporar un Excel local sin configurar Google. Desde la raíz del proyecto:

```powershell
python scripts/import_purchase_history.py "C:\ruta\Compras.xlsx"
```

El importador lee todas las pestañas con descripción, marca, comercio y categoría, deduplica productos y guarda únicamente esos campos en `data/local-history.json`, ignorado por Git. Omite totales, descuentos y filas incompletas. No modifica el Excel ni guarda sus precios o fechas de compra. Reiniciá el backend después de importar. Este catálogo funciona sin conexión y se combina con el historial de Google Sheets; cambiar el destino de Sheets no lo desactiva. `--dry-run` permite comprobar la importación sin guardar datos; `--output` elige otra ruta y `APP_LOCAL_HISTORY_PATH` indica al backend dónde leerla.

Actualizá el historial de Sheets cuando quieras incorporar nuevas compras o correcciones de esa planilla. **Dejar de usar historial** desactiva ambos catálogos. Cambiar de archivo o de fila de encabezados desactiva el catálogo de Sheets para ese destino hasta sincronizarlo; cambiar de pestaña mensual dentro del mismo archivo permite seguir usando el catálogo. La [guía de Sheets](docs/GOOGLE_SHEETS.md) detalla límites y manejo de errores.

- Al editar la descripción, la marca o la categoría de una fila, la corrección se guarda en `data/corrections.json` automáticamente 600 ms después de dejar de tipear, incluso si la marca original era `Genérico`. Corregir solo el nombre también se memoriza aunque todavía no conozcas la marca. La pantalla confirma el guardado y permite reintentar si falla.
- Si cambiás explícitamente una marca mal detectada a `Genérico`, esa corrección también se recuerda. Una marca genérica de origen sigue sin borrar una marca real ya aprendida al editar otros campos.
- La memoria se consulta por **comercio + linea del ticket**; un patron aprendido en un supermercado no se aplica en otro.
- En el proximo ticket del mismo comercio, la fila equivalente se pre-completa con lo aprendido y se marca como `Memorizado`.
- Las filas que hoy se descartan pero coinciden con algo aprendido se recuperan con una advertencia "Recuperado de memoria".
- Solo se aprenden marca, categoria y descripcion. Precio y fecha siempre vienen del ticket actual.
- `ALMACEN` / `ALMACÉN` es el comodín de productos cargados manualmente por el supermercado: se exporta siempre como `Almacén`, marca `Genérico`, con el precio de su propia fila. Se conservan las apariciones repetidas y no se reemplaza con nombres o marcas del historial o de la memoria.

## Respaldo del aprendizaje

En **Administrar correcciones aprendidas**, **Descargar respaldo** genera un JSON con correcciones y marcas. Para recuperarlo, seleccioná el archivo, revisá la cantidad de datos nuevos y diferencias, y elegí **Restaurar aprendizaje**. Cancelar la vista previa no escribe archivos. Las entradas locales tienen prioridad; la restauración agrega las nuevas sin eliminar ni reemplazar las existentes. Si la memoria cambia después de la vista previa, hay que seleccionar y revisar el respaldo otra vez.

El formato tiene versión y admite archivos de hasta 16 MB y 10.000 entradas por lista. El servidor publica los límites y valida también la exportación; el navegador comprueba los bytes del archivo final antes de descargarlo. Si se superan, informa el problema sin producir un respaldo incompleto. Se valida antes de escribir; no incluye credenciales, destino de Sheets, precios, fechas de compra ni imágenes. Conservá el respaldo como información personal: contiene nombres de productos y comercios. Una restauración interrumpida recupera el estado anterior al reiniciar, mediante un diario local ignorado por Git.

Si hay ediciones administrativas sin guardar, buscar, paginar, eliminar o cerrar ofrece **Guardar y continuar**, **Descartar y continuar** o **Seguir editando**. Escape conserva la misma protección y recargar la página muestra el aviso habitual del navegador. Las confirmaciones de cancelación tienen un tiempo máximo de espera; si falla la red, se conserva el UUID para reintentar sin crear otro trabajo.

Las correcciones pueden ser parciales: dejá un campo vacío para conservar el valor del ticket nuevo. Hay que mantener al menos una corrección de descripción, marca o categoría; para quitar toda la entrada, usá **Eliminar**.

## Categorías locales

Copiá `src/main/resources/category-defaults.json` a `data/categories.json` y editá esa copia. No hace falta cambiar Java ni reiniciar: el backend relee los cambios en la siguiente extracción; recargá la página para actualizar las sugerencias de la tabla. En Docker el archivo usa el volumen `./data:/app/data`. `APP_CATEGORIES_PATH` permite elegir otra ruta.

`categories` define sugerencias, `stores` asocia comercios con categorías y `fallback` se usa cuando no hay coincidencia. `productRules` admite reglas en orden; alcanza con que una palabra o frase completa de `keywords` aparezca en la descripción. `store` es opcional y restringe la regla a ese comercio normalizado. Ejemplo:

```json
"productRules": [
  {"store": "Local Prueba", "category": "Lácteos", "keywords": ["leche", "yogur"]}
]
```

La primera regla aplicable tiene prioridad sobre la categoría del comercio. Memoria e historial siguen completando las categorías que ya aprendieron. La tabla acepta categorías escritas libremente; las sugerencias no limitan el campo. Un archivo inválido muestra un error y debe corregirse para continuar extrayendo.

## Límites de archivos

- El tamaño máximo de carga es 20 MB.
- Las imágenes se validan antes de decodificarlas: máximo 20 megapíxeles y 10.000 píxeles por lado.
- Los PDF admiten hasta 20 páginas. Cada página se limita a 20 megapíxeles al renderizarse a 300 DPI y se procesa de a una para no mantener todas las imágenes en memoria.
- Cada PDF tiene un presupuesto total de 300 segundos, configurable con `app.pdf.processing-budget-seconds` o `APP_PDF_PROCESSING_BUDGET_SECONDS` (1–3600). El tiempo restante se comparte entre páginas y se comunica al OCR sin aumentar su presupuesto por página. Es cooperativo: una llamada nativa ya iniciada puede sobrepasarlo. Al agotarse se responde HTTP 408 sin devolver productos parciales; dividí el PDF o aumentá el presupuesto. El ticket anterior se conserva en pantalla.
- El formato se comprueba por el contenido del archivo, no solo por el nombre. PDF cifrados, dañados o con dimensiones superiores al límite se rechazan con un mensaje.
- Los límites de imagen y PDF se pueden ajustar en `application.properties` (`app.upload.*` y `app.pdf.*`).

## Limites actuales

- las marcas conocidas se buscan también después del nombre genérico del producto, con límites de palabra; las aproximaciones dudosas siguen requiriendo revisión
- la categoría base usa reglas locales por producto/comercio y `Supermercado` como fallback predeterminado
- la cantidad se lee desde multiplicadores o columnas OCR cuando son claras; si no hay evidencia confiable, queda en `1`
- la corrección geométrica requiere evidencia suficiente de bordes del papel o renglones; las fotos muy borrosas, con papel curvado o contenido tapado pueden seguir perdiendo texto
- el primer build de Docker puede tardar porque descarga la imagen y los modelos de OCR
- para fotos de tickets, el OCR prueba varias versiones de la imagen y elige la mas util con un score estructural, sin favorecer marcas concretas
- la selección pondera precios/productos por confianza y reconoce palabras fiscales completas; `OLIVA` no se confunde con `IVA`
- cada variante se limita a 4 megapíxeles y 1400 píxeles en el lado corto; los bloques OCR tienen hasta 1400 píxeles por lado y 160 de solapamiento. Los tickets largos pueden tardar más al procesarse por bloques
- las variantes de geometría e iluminación agregan trabajo de OCR; su mejora de precisión y costo de tiempo deben medirse con los tickets de uso real
- la relectura se limita a cajas que el detector ya encontró con confianza baja; no recupera zonas completamente omitidas y conserva el texto anterior si las nuevas lecturas discrepan o resultan incompletas
- la combinación tampoco agrega zonas omitidas; no mezcla recortes, rotaciones o correcciones de perspectiva con coordenadas incompatibles. Su consenso sigue siendo una heurística y debe verificarse con transcripciones reales
- la vista previa se limita a 2 megapíxeles y 900 KB de JPEG por página; ampliar ayuda a inspeccionar el texto pero no recupera detalle perdido. El recorte ampliado se limita a 2000 × 800 píxeles
- la tabla permite agregar líneas manualmente, además de editar o eliminar las filas detectadas
- el precio total no forma parte de las siete columnas CSV/TSV; la carga directa a Sheets mantiene la fórmula de total en H
- el aprendizaje guarda solo marca, categoria y descripcion; no almacena precios ni datos de tarjetas
- `data/corrections.json` no se versiona en git; en Docker persiste por el volumen `./data:/app/data`, junto con marcas y configuración de Sheets. Detener o reconstruir los contenedores conserva estos archivos. Los stores JSON escriben de forma atómica y mantienen una copia `.bak` de la versión anterior para recuperar un archivo dañado; para protegerse ante fallos del disco, guardá también una copia externa de `data/`.

## Mejoras faciles para despues

- ampliar el banco de evaluación con fotos reales anonimizadas e independientes. El banco sintético ampliado ya incluye 16 casos con atributos, unidades separadas, productos repetidos, pesos y degradaciones; ver `docs/OCR_EVALUATION.md`.

Los logs normales registran tiempos, estados y cantidades de filas, sin texto OCR, comercio, fecha ni total de compra. `APP_OCR_DEBUG=true` habilita archivos de diagnóstico sensibles en `debug/`; mantenelos fuera de Git.
