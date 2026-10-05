# Extractor local de facturas con Spring Boot

App local para subir una foto o PDF de un ticket, correr OCR gratis con PaddleOCR en Docker y devolver filas para copiar o cargar directamente en un Google Sheet configurable.

## Que hace

- extrae texto de imagen o PDF con PaddleOCR
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
- muestra un `Total calculado` debajo de las filas, sumando cantidad por precio unitario de los items extraidos (nunca el total del OCR)
- descarta lineas de subtotal, total y monto a pagar; usa `Genérico` cuando no reconoce la marca
- corrige marcas OCR con Levenshtein: menos de 30% usa `Genérico`, de 30% a 70% deja la marca dudosa para revisar y más de 70% aplica la marca automáticamente
- genera dos salidas:
  - formato con `|` para guardar o copiar
  - formato tabulado sin encabezado para pegar directo en Google Sheets
- muestra una tabla editable para corregir o eliminar filas antes de copiar
- permite guardar un destino de Google Sheets y cargar las filas seleccionadas con un botón, mediante una cuenta de servicio
- marca filas ambiguas y muestra advertencias completas cuando una línea no pudo confirmarse
- aprende de tus correcciones (marca, categoría y descripción) y las aplica en el próximo ticket del mismo comercio
- permite actualizar un catálogo local desde el historial de Google Sheets para completar productos conocidos y sugerir coincidencias dudosas
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

El servicio OCR no se expone publicamente. Solo la app Spring Boot lo llama dentro de la red Docker usando `http://paddleocr:5000`.

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

El arranque espera `ocrReady`, conserva los logs en `logs/` y ejecuta una copia del JAR en `logs/runtime/`, separada de `target/`. Así una nueva compilación no bloquea ni altera el archivo en ejecución. Detiene primero las instancias antiguas que ejecutaban directamente `target/` y comprueba la respuesta HTTP antes de anunciar que la app está lista.

En Windows, las rutas de modelos PaddleOCR usan el nombre corto de la caché cuando el perfil contiene acentos, sin cambiar `HOME` ni `USERPROFILE`. Se reutilizan los modelos descargados. Si Windows no dispone de un nombre corto utilizable, el OCR informa el problema en el log.

```bash
mvn test
python -m unittest discover -s ocr/tests
node --test src/test/js/*.test.mjs
```

Los tests Java incluyen fixtures sintéticos en `src/test/resources/receipt-evaluation` y reportan aciertos exactos por campo. El banco cubre texto OCR multipágina, cantidad/precio separados, líneas de precio sueltas y detecciones geométricas, sin versionar tickets reales.

Los tests Python cubren conservación de texto tenue, geometría, iluminación, variantes de imagen, bloques solapados, separación de renglones, elección por confianza y el contrato Flask con el motor OCR simulado. Requieren las dependencias de `ocr/requirements.txt`; no descargan modelos. La precisión del reconocimiento real debe comprobarse también con tickets locales.

Para comparar el texto OCR antes y después de las nuevas correcciones, se incluye `ocr/evaluate.py`. Mide errores de caracteres, coincidencias de líneas e importes y tiempo por caso usando el backend local. La [guía de evaluación](docs/OCR_EVALUATION.md) explica cómo preparar transcripciones, generar informes y comparar los mismos archivos. Los tickets y resultados se guardan en `test-data/`, ignorado por Git.

Las nuevas correcciones se habilitan por defecto. Se pueden desactivar para comparar resultados o reducir procesamiento, definiendo `OCR_IMAGE_CORRECTIONS=false` al levantar Compose. El reconocimiento conserva las variantes anteriores en ambos modos.

La relectura selectiva también está habilitada por defecto y se controla por separado con `OCR_TARGETED_RETRY=false`. Agrega como máximo 16 llamadas OCR pequeñas por imagen, después de elegir la mejor variante. Si acepta alguna mejora, el nombre de la variante termina en `+retry`. La guía incluye una comparación específica con esta opción activada y desactivada.

La combinación de variantes está habilitada por defecto; `OCR_VARIANT_FUSION=false` permite desactivarla. Reutiliza lecturas ya realizadas, sin llamadas adicionales al motor. Solo modifica zonas con confianza inferior a 0,80 cuando al menos dos imágenes distintas coinciden, alcanzan 0,85 y mejoran la confianza en al menos 0,10. Conserva las cajas, los renglones repetidos y el texto anterior ante contradicciones. Una mejora aceptada agrega `+fusion` al nombre de la variante, antes del posible `+retry`.

## Desarrollo local sin Docker para Spring Boot

Si queres correr Spring Boot desde Maven y dejar solo el OCR en Docker:

```bash
docker compose up --build -d paddleocr
mvn spring-boot:run
```

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

Además de las correcciones manuales, podés usar las compras que ya registraste en Google Sheets. Tras guardar y comprobar el destino, pulsá **Actualizar historial**. Se leen las cuatro primeras columnas de las pestañas compatibles del mismo archivo: descripción, marca, comercio y categoría. El catálogo se guarda en `data/sheets-history.json`, ignorado por Git y persistido en el volumen local de Docker.

Las próximas extracciones consultan ese catálogo sin conectarse a Google. Una coincidencia exacta del texto original, dentro del mismo comercio y sin contradicciones, puede completar descripción, marca y categoría; aparece como **Historial**. Las coincidencias aproximadas solo generan sugerencias para revisar. Se conservan cantidad, precio y fecha del ticket y las advertencias de ambigüedad; las correcciones manuales **Memorizadas** tienen prioridad. No se entrena nuevamente PaddleOCR.

Actualizá el historial cuando quieras incorporar nuevas compras o correcciones de la planilla. **Dejar de usar historial** desactiva esta ayuda. Cambiar de archivo o de fila de encabezados la desactiva para ese destino hasta sincronizarlo; cambiar de pestaña mensual dentro del mismo archivo permite seguir usando el catálogo. La [guía de Sheets](docs/GOOGLE_SHEETS.md) detalla límites y manejo de errores.

- Al editar la descripcion, la marca o la categoria de una fila, la correccion se guarda en `data/corrections.json` (envio automatico con breve demora despues de dejar de tipear).
- La memoria se consulta por **comercio + linea del ticket**; un patron aprendido en un supermercado no se aplica en otro.
- En el proximo ticket del mismo comercio, la fila equivalente se pre-completa con lo aprendido y se marca como `Memorizado`.
- Las filas que hoy se descartan pero coinciden con algo aprendido se recuperan con una advertencia "Recuperado de memoria".
- Solo se aprenden marca, categoria y descripcion. Precio y fecha siempre vienen del ticket actual.

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
- `data/corrections.json` no se versiona en git; en Docker persiste por el volumen `./data:/app/data`

## Mejoras faciles para despues

- ampliar el banco de evaluación con más transcripciones sintéticas o anonimizadas
- permitir configurar categorías desde archivos locales
