# Extractor local de facturas con Spring Boot

App local para subir una foto o PDF de un ticket, correr OCR gratis con PaddleOCR en Docker y devolver filas para copiar o cargar directamente en un Google Sheet configurable.

## Que hace

- extrae texto de imagen o PDF con PaddleOCR
- prueba primero la orientación del texto y concentra las mejoras en la más legible; reutiliza lecturas de imágenes con píxeles idénticos
- prioriza variantes que asocian productos e importes, reduciendo el puntaje de precios sueltos en tickets inclinados
- conserva el color y el texto tenue como candidato OCR; compara el original con versiones recortadas y mejoradas
- compara también variantes con inclinación/perspectiva corregidas y con contraste local o sombras compensadas, conservando el original como alternativa
- relee hasta ocho zonas de baja confianza de la variante elegida, con recortes ampliados y dos tratamientos; acepta cambios cuando ambas lecturas coinciden y mejoran la confianza, conservando las cajas para evitar duplicados
- combina lecturas de distintas variantes para corregir zonas débiles de cada renglón, cuando dos imágenes distintas coinciden con mayor confianza y sus coordenadas son compatibles
- resalta las zonas dudosas sobre la imagen procesada y permite ampliar cada recorte a 2×, 4× u 8×, con navegación por página en PDF
- procesa los tickets largos en bloques solapados para conservar el detalle, reunificando coordenadas y detecciones repetidas del mismo renglón
- detecta fecha y lugar de compra; admite fechas sin etiqueta, con `/`, `-` o `.`, además de las etiquetadas
- reconoce el encabezado de PedidosYa `Entregado mié 30 de sept · 22:04 hs`, incluso separado por el OCR, con meses completos o abreviados; si falta el año usa el año actual (revisarlo en pedidos antiguos)
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
- permite guardar un destino de Google Sheets y cargar las filas seleccionadas con un botón, mediante una cuenta de servicio
- marca filas ambiguas y muestra advertencias completas cuando una línea no pudo confirmarse
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
```

El arranque espera `ocrReady`, conserva los logs en `logs/` y ejecuta una copia del JAR en `logs/runtime/`, separada de `target/`. Así una nueva compilación no bloquea ni altera el archivo en ejecución. Detiene primero las instancias antiguas que ejecutaban directamente `target/` y comprueba la respuesta HTTP antes de anunciar que la app está lista. Reutiliza un OCR que todavía está arrancando y detecta si los procesos recién lanzados terminan con error. La espera de modelos es de hasta 300 segundos; se puede ampliar, por ejemplo, con `-OcrStartupTimeoutSec 600`.

En Windows, las rutas de modelos PaddleOCR usan el nombre corto de la caché cuando el perfil contiene acentos, sin cambiar `HOME` ni `USERPROFILE`. Se reutilizan los modelos descargados. Si Windows no dispone de un nombre corto utilizable, el OCR informa el problema en el log.

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

La [guía de configuración](docs/GOOGLE_SHEETS.md) explica cómo habilitar Google Sheets API, crear la cuenta de servicio, guardar el JSON local y compartir la planilla con esa cuenta como Editor. Después abrí el engranaje de la aplicación, guardá la URL o ID, el año de la planilla y la fila de encabezados, y comprobá la conexión. Podés asociar meses a pestañas con nombres personalizados.

La plantilla de compras usa encabezados en la fila 2: las siete columnas exportadas en A:G, `Precio total` en H y `Comentarios` en I. La carga calcula H como cantidad × precio unitario, conserva I y los resúmenes laterales y almacena números y fechas como valores de hoja de cálculo. La fecha de las filas seleccionadas propone el mes de destino; podés elegir otra pestaña para cargar tickets atrasados sin cambiar sus fechas.

El destino se guarda en `data/sheets-config.json`. El archivo de credenciales predeterminado es `data/google-service-account.json`; ambos están ignorados por Git y excluidos del contexto de build de Docker. El token de Google permanece en memoria del backend. Cada carga lleva un identificador para reconocer reintentos y evitar duplicados de la misma operación.

## Aprendizaje de correcciones

En fotos inclinadas, el parser ajusta las posiciones de las cajas OCR antes de asociar nombres y precios. El listado termina en subtotal/total/recibido para no convertir el pie fiscal o el pago en productos. Admite decimales OCR como `3400,C0` y conserva la cantidad fraccionaria cuando el importe de línea es legible aunque falle el precio unitario. Si reconstruye un multiplicador sin `×`, la fila queda para revisión. Una diferencia con un total OCR legible se informa como advertencia; el total de la app siempre se calcula con las filas y descuentos reconocidos.

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

## Límites de archivos

- El tamaño máximo de carga es 20 MB.
- Las imágenes se validan antes de decodificarlas: máximo 20 megapíxeles y 10.000 píxeles por lado.
- Los PDF admiten hasta 20 páginas. Cada página se limita a 20 megapíxeles al renderizarse a 300 DPI y se procesa de a una para no mantener todas las imágenes en memoria.
- El formato se comprueba por el contenido del archivo, no solo por el nombre. PDF cifrados, dañados o con dimensiones superiores al límite se rechazan con un mensaje.
- Los límites de imagen y PDF se pueden ajustar en `application.properties` (`app.upload.*` y `app.pdf.*`).

## Limites actuales

- la marca se estima a partir del inicio de la descripcion
- la categoria base se infiere por comercio conocido y usa `Supermercado` como fallback
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

- ampliar el banco de evaluación con más transcripciones sintéticas o anonimizadas
- permitir configurar categorías desde archivos locales
