# PROJECT_CONTEXT

## Objetivo general del sistema

Aplicacion local para extraer datos utiles de tickets/facturas de supermercado desde imagenes o PDFs. El usuario sube un archivo desde una UI web, el backend lo convierte/preprocesa si hace falta, llama a un servicio OCR local en Docker basado en PaddleOCR, parsea texto y geometria OCR con reglas heuristicas y devuelve filas para copiar o cargar en un Google Sheet configurable.

La salida principal modela productos comprados con columnas: `Descripcion`, `Marca`, `Lugar de compra`, `Categoria`, `Cantidad`, `Precio unitario`, `Fecha`. Tambien devuelve texto OCR crudo para depuracion.

El proyecto está orientado a uso personal/local. No hay despliegue multiusuario ni historial local de tickets; opcionalmente guarda un catálogo de productos importado de Google Sheets.

## Stack tecnologico y versiones relevantes

- Java 17.
- Spring Boot 3.3.5.
- Maven, artifact `com.opencode:facturas-ocr:0.0.1-SNAPSHOT`.
- Spring Web MVC, Spring Validation.
- Apache PDFBox 3.0.3 para renderizar PDFs a imagenes.
- Jackson provisto por Spring Boot para JSON.
- Frontend estatico HTML/CSS/JS servido desde `src/main/resources/static`.
- Python 3.11 slim para microservicio OCR.
- Flask 3.0.3.
- PaddleOCR 2.8.1 y PaddlePaddle 2.6.2.
- Pillow 10.4.0, NumPy 1.26.4 y OpenCV 4.10.0.84.
- Docker Compose para levantar backend y OCR.
- Java HttpClient y firma RSA estándar para Google Sheets API y OAuth de cuenta de servicio, sin SDK ni nuevas dependencias.

No hay `package.json`; no hay build frontend npm.

## Estructura de modulos y directorios importantes

- `src/main/java/com/opencode/facturas`: aplicacion Spring Boot.
- `src/main/java/com/opencode/facturas/controller`: REST controller y manejo global de errores.
- `src/main/java/com/opencode/facturas/service`: integracion OCR, parser de tickets, catalogo de marcas y mapeo de comercios.
- `src/main/java/com/opencode/facturas/model`: records usados como DTO/modelo de respuesta.
- `src/main/java/com/opencode/facturas/util`: exportacion delimitada para Sheets/CSV.
- `src/main/resources/static`: frontend estatico servido por Spring Boot.
- `src/main/resources/store-mappings.json`: aliases de nombres de comercio detectados por OCR.
- `data/brands.json`: catalogo mutable de marcas conocidas usado por `BrandCatalog`.
- `ocr`: microservicio Flask/PaddleOCR, layout/scoring OCR, preprocesamiento de imagenes, tests Python y Dockerfile.
- `tessdata`: contiene modelos `eng.traineddata` y `spa.traineddata`, pero el codigo actual no usa Tesseract.
- `src/test/java/com/opencode/facturas`: tests unitarios del parser y del cliente OCR estructurado.
- `src/test/resources/receipt-evaluation`: fixtures anonimos para medir regresiones de parser sin versionar tickets reales.

## Estado actual de ambiguedad y UI

- `ReceiptItem.estado` marca cada fila como `CORRECT`, `AMBIGUOUS`, `LEARNED` (corrección manual memorizada) o `HISTORY` (coincidencia con el catálogo de Sheets).
- En filas reconstruidas desde detecciones, el parser también marca como ambiguas las que tienen alguna confianza OCR menor a `0.65` o una caja individual con una relación ancho/alto de `28` o más. La confianza ausente se trata como desconocida.
- `ExtractResponse.warnings` informa lineas dudosas descartadas, por ejemplo precios sin descripcion.
- La UI estatica renderiza una tabla editable, permite corregir o eliminar filas y resalta resultados ambiguos.
- La UI muestra el texto completo de las advertencias antes de copiar las salidas.
- El panel de revisión OCR resalta zonas con confianza menor a 0,80 o desconocida, permite mostrar todas, seleccionar con mouse/teclado y ampliar recortes a 2×/4×/8×. Conserva cada página y sus coordenadas por separado.
- `app.js` regenera CSV/TSV desde las filas editadas; los botones de copia no dependen de la exportacion inicial del backend.
- `receipt-sheets.mjs` gestiona el engranaje de configuración, propone el destino a partir de las fechas de las filas seleccionadas y envía las siete columnas editadas. Usa un UUID por operación, conserva el mismo UUID en reintentos y bloquea el mismo snapshot después del éxito. No envía texto OCR ni firma de aprendizaje a Google.
- El parser usa geometria OCR cuando hay detecciones con bounding boxes: reconstruye filas por pagina y puede leer columnas de descripcion, cantidad, precio unitario e importe antes de caer al parser textual.
- PedidosYa tolera errores OCR frecuentes en el encabezado (`PodidosYa`) y descarta bloques de interfaz como `Tu pedido`, `Tu pago`, descuentos y detalles de entrega.
- Se agrego una regresion OCR reproducible en `test-data/receipts/pedidosya/pedidosya-market-san-miguel-ii-ocr.txt`; la imagen original del caso no estaba disponible como archivo en el workspace.
- `ReceiptItem.firma` identifica la linea OCR normalizada que origino cada fila y sirve de llave para el aprendizaje.
- El sistema aprende correcciones de marca, categoria y descripcion por comercio, las persiste en `data/corrections.json` y las reutiliza en el proximo ticket (override o recuperacion de filas perdidas), marcando los items aplicados como `LEARNED`.
- `CorrectionMemoryStore` y `BrandCatalogStore` escriben primero a un temporal, reemplazan el JSON y guardan una copia `.bak` del estado anterior. Si el archivo principal no se puede leer, restauran esa copia.

## Arquitectura general

Arquitectura simple de dos servicios locales:

- Frontend estatico: `index.html`, `styles.css`, `app.js`. Permite elegir archivo, invoca endpoint REST y muestra/copia resultados.
- Backend Spring Boot: recibe multipart, procesa imagen/PDF, llama al OCR, conserva resultado OCR estructurado, parsea layout/texto y devuelve JSON.
- Microservicio OCR Python: recibe una imagen PNG, ejecuta PaddleOCR sobre variantes preprocesadas y responde texto, lineas, detecciones, variante seleccionada y score estructural.
- Integración opcional Backend -> Google Sheets: cuenta de servicio compartida como Editor, destino persistido localmente y carga de filas A:H con números, fecha y fórmula de total. No requiere Google Drive API para un archivo identificado por URL/ID.
- Después de elegir la variante, `variant_fusion.py` combina lecturas compatibles ya disponibles; luego se ejecuta la relectura selectiva. `preview.py` genera en memoria una vista previa de la imagen ganadora para revisar las detecciones finales.

Responsabilidades por capa:

- Controller: validacion minima HTTP y orquestacion de `OcrService` + `ReceiptParserService`.
- Service Java: OCR client/preprocesado y reglas de parsing de dominio.
- Model: records de respuesta y filas parseadas.
- Util: conversion de `ReceiptItem` a formatos delimitados.
- Frontend: upload, fetch, render de respuesta y copia al portapapeles.
- OCR Python: carga/warmup de PaddleOCR, preprocesamiento robusto de imagenes, merge de filas y scoring de variantes.

No hay base de datos; la memoria de correcciones, el catálogo de marcas y el catálogo opcional de Sheets usan archivos JSON locales. El último conserva únicamente descripción, marca, comercio, categoría y metadatos de sincronización; no replica importes ni fechas de compra.

## Arbol simplificado del proyecto

```text
.
├── pom.xml
├── README.md
├── Dockerfile
├── compose.yaml
├── data/
│   └── brands.json
├── ocr/
│   ├── Dockerfile
│   ├── receipt_layout.py
│   ├── image_variants.py
│   ├── document_geometry.py
│   ├── illumination.py
│   ├── evaluate.py
│   ├── ocr_regions.py
│   ├── targeted_retry.py
│   ├── variant_fusion.py
│   ├── preview.py
│   ├── preprocess.py
│   ├── requirements.txt
│   ├── scoring.py
│   ├── service.py
│   └── tests/
├── tessdata/
│   ├── eng.traineddata
│   └── spa.traineddata
└── src/
    ├── main/
    │   ├── java/com/opencode/facturas/
    │   │   ├── FacturasOcrApplication.java
    │   │   ├── controller/
    │   │   │   ├── ApiExceptionHandler.java
    │   │   │   ├── CorrectionController.java
    │   │   │   └── ReceiptController.java
    │   │   ├── model/
    │   │   │   ├── ExtractResponse.java
    │   │   │   ├── OcrDetection.java
    │   │   │   ├── OcrLine.java
    │   │   │   ├── OcrResult.java
    │   │   │   └── ReceiptItem.java
    │   │   ├── service/
    │   │   │   ├── BrandCatalog.java
    │   │   │   ├── BrandCatalogMatcher.java
    │   │   │   ├── BrandCatalogStore.java
    │   │   │   ├── CorrectionMemory.java
    │   │   │   ├── CorrectionMemoryRules.java
    │   │   │   ├── CorrectionMemoryStore.java
    │   │   │   ├── OcrApiClient.java
    │   │   │   ├── OcrService.java
    │   │   │   ├── OcrResultMapper.java
    │   │   │   ├── PdfPageRenderer.java
    │   │   │   ├── PedidosYaReceiptParser.java
    │   │   │   ├── ReceiptAmounts.java
    │   │   │   ├── ReceiptDateParser.java
    │   │   │   ├── ReceiptLayoutReader.java
    │   │   │   ├── ReceiptLineAnalyzer.java
    │   │   │   ├── ReceiptParserService.java
    │   │   │   ├── ReceiptTotalCalculator.java
    │   │   │   └── StoreNameMapper.java
    │   │   └── util/
    │   │       └── DelimitedExporter.java
    │   └── resources/
    │       ├── application.properties
    │       ├── store-mappings.json
    │       └── static/
    │           ├── app.js
    │           ├── index.html
    │           └── styles.css
    └── test/
        ├── java/com/opencode/facturas/
        │   ├── ReceiptParserServiceTest.java
        │   └── service/
        │       ├── BrandCatalogComponentsTest.java
        │       ├── CorrectionControllerTest.java
        │       ├── CorrectionMemoryComponentsTest.java
        │       ├── CorrectionMemoryTest.java
        │       ├── OcrApiClientTest.java
        │       ├── OcrResultMapperTest.java
        │       ├── OcrServiceTest.java
        │       ├── PdfPageRendererTest.java
        │       ├── ReceiptEvaluationTest.java
        │       ├── ReceiptLayoutReaderTest.java
        │       └── ReceiptParsingComponentsTest.java
        └── resources/receipt-evaluation/
```

Excluido: `.git`, `target`, logs, caches, outputs de build.

## Principales entidades de dominio y relaciones

- `ReceiptItem`: fila de producto extraida. Campos: `descripcion`, `marca`, `lugarDeCompra`, `categoria`, `cantidad`, `precioUnitario`, `fecha`, `estado`, `firma`.
- `ExtractResponse`: respuesta completa del endpoint. Contiene metadata (`storeName`, `date`, `itemCount`, `total` calculado desde los items), exportaciones (`csv`, `tsv`, `tsvWithoutHeader`), `rawText`, `items`, `warnings`, `variant`, `score` OCR y `ocrReview`.
- `OcrResult`: resultado OCR estructurado recibido desde Python, con `text`, `lines`, `detections`, `variant`, `score`, `preview` opcional y, para PDFs mergeados en Java, `pages`.
- `OcrPreview`: `imageDataUrl` JPEG local, `width` y `height` del marco de coordenadas OCR. Las dimensiones del JPEG pueden ser menores: el frontend escala el recorte según su resolución real. El JPEG se limita a 2 megapíxeles, 8000 px por lado y 900 KB antes de base64.
- `OcrReviewPage`: `pageNumber`, `source` (`ocr` o `pdf-text`), `imageDataUrl`, `width`, `height` y `detections` de esa página. Sin vista previa, la imagen es null y las dimensiones son cero; el texto sigue disponible. No se persiste como historial.
- `OcrLine`: linea OCR mergeada con texto, confidence/score y geometria basica.
- `OcrDetection`: deteccion cruda OCR con texto, confidence y bounding box de 4 puntos.
- `BrandCatalog.BrandMatch`: record interno/publico de `BrandCatalog` para marca y alias normalizado encontrado.
- Records internos de `ReceiptParserService`: `BrandMatch`, `ProductRule` y `ParsedItemLine`.
- `PedidosYaReceiptParser` expone records package-private `Candidate` y `ParseResult` para separar la deteccion de lineas del armado final de `ReceiptItem`.

Relacion principal: `ExtractResponse` contiene una lista de `ReceiptItem`. Los items se derivan del layout OCR cuando hay cajas confiables y del texto OCR como fallback, mas reglas de marcas, memoria y nombres de comercios.

No hay entidades JPA ni agregados persistidos.

## Controllers/endpoints principales

- `ReceiptController` en `src/main/java/com/opencode/facturas/controller/ReceiptController.java`.
- Base path: `/api/receipts`.
- `POST /api/receipts/extract`.
- Consume: `multipart/form-data` con parametro `file` obligatorio.
- Produce: JSON serializado desde `ExtractResponse`.
- Flujo: valida que el archivo no este vacio, llama `OcrService.extract(file)`, luego `ReceiptParserService.parse(ocrResult)` para conservar texto y geometria, y agrega `variant`/`score` y las páginas de revisión mediante `OcrReviewMapper`.

Manejo de errores:

- `ApiExceptionHandler` captura `IllegalArgumentException`, `IllegalStateException` y `MethodArgumentNotValidException`.
- Responde HTTP 400 con cuerpo `{ "message": "..." }`.
- Inferencia: errores de OCR/integracion se exponen como 400 aunque algunos podrian considerarse 502/503 en una arquitectura remota.

Endpoints del microservicio OCR Python:

- `POST /ocr`: recibe bytes de imagen, header opcional `X-OCR-Language`, responde JSON `{ text, lines, detections, variant, score, preview }` o `{ error }`. `preview` es opcional y su ausencia no invalida el texto.
- `GET /health`: responde `{ status: "ok", ocrReady: boolean }`.

Endpoint de aprendizaje:

- `POST /api/corrections`: recibe JSON `{ store, corrections: [{ firma, descripcion, marca, categoria }] }`, persiste la correccion en `CorrectionMemory` y registra la marca en `BrandCatalog`; responde `{ saved }`.

Endpoints de Google Sheets (`SheetsController`):

- `GET /api/sheets/config`: devuelve archivo, año, fila de encabezados, asociaciones mensuales, versión de configuración, estado de credenciales y email público de la cuenta de servicio. No llama a Google ni devuelve claves o tokens.
- `PUT /api/sheets/config`: recibe `{ spreadsheetUrlOrId, year, headerRow, monthSheets }` y guarda URL/ID validado y asociaciones de meses a pestañas exactas en `data/sheets-config.json`.
- `POST /api/sheets/check`: lee metadatos y A:I de las pestañas; devuelve las compatibles, las incompatibles y sugerencias por nombres mensuales inequívocos.
- `POST /api/sheets/append`: recibe `{ requestId, items, destination: { spreadsheetId, sheetName, headerRow }, configVersion }`. Valida todas las filas y verifica que el destino y la versión coincidan con la configuración antes de llamar a Google. Responde `{ spreadsheetUrl, sheetName, updatedRange, appendedRows, duplicate }`.

Catálogo histórico (`SheetsHistoryController`):

- `GET /api/sheets/history`: estado local `{ active, spreadsheetId, updatedAt, rowCount, productCount, sheets, skippedSheets, message }`, sin acceso a Google.
- `POST /api/sheets/history/sync`: recibe la configuración esperada `{ spreadsheetId, headerRow, configVersion }`; comprueba que siga vigente. Lee A:D de las pestañas GRID compatibles y reemplaza el catálogo al terminar. No escribe en Google ni guarda filas parciales ante errores; máximo 50.000 celdas por sincronización.
- `DELETE /api/sheets/history`: desactiva el catálogo local para futuras extracciones.

`SheetsHistoryService` mantiene una copia local en `data/sheets-history.json` y solo la aplica al archivo y fila de encabezados de origen. `HistoryProductMatcher` cruza firmas OCR completas, comercio y marca; no toma como identidad suficiente la descripción generalizada del parser. Las contradicciones no se resuelven por frecuencia. Las coincidencias aproximadas generan advertencias sin modificar campos. La memoria manual tiene prioridad, y ninguna coincidencia histórica sustituye precio, cantidad, fecha, firma o comercio del ticket. No se agregan entradas a `CorrectionMemory` ni `BrandCatalog` al sincronizar, ni se cambia el motor OCR. El catálogo funciona sin red y una copia dañada no impide extraer tickets.

El esquema esperado es el del Excel de compras: encabezados A:I con las siete columnas exportadas más `Precio total` y `Comentarios`, fila 2 por defecto. La escritura abarca A:H, conserva I y las columnas laterales, y genera o conserva H como E × F. Los comentarios o totales H personalizados se consideran contenido ocupado. No se crean pestañas. El destino mensual se propone a partir de las fechas de las filas seleccionadas y el año configurado; el usuario puede elegir manualmente otra pestaña compatible.

## Services principales

- `SheetsConfigStore`: valida la URL/ID y guarda el destino opcional con `AtomicJsonFile`. La configuración se lee de forma diferida para no impedir iniciar el OCR local si ese archivo está dañado.
- `GoogleServiceAccountAuth`: lee el JSON local de la cuenta, firma un JWT RS256 con audiencia fija de Google y obtiene un token con scope `spreadsheets`; conserva el token en memoria y detecta cambios del archivo de credenciales.
- `GoogleSheetsClient` y `SheetsHttpTransport`: envían HTTPS solo a endpoints de Google, acotan timeouts, sanitizan errores y no reintentan automáticamente escrituras sin confirmar.
- `SheetsService`: valida fecha/importes, inspecciona esquema y destino libre, protege rangos combinados/protegidos, amplía la cuadrícula y tabla/filtro A:I si hace falta, y escribe filas junto con un marcador UUID/huella mediante un batch atómico. Los marcadores remotos permiten reconocer reintentos incluso tras reiniciar el backend; no guardan compras en archivos locales.

- `OcrService`: fachada de extracción. Detecta PDF por su firma, inspecciona las dimensiones de imagen antes de decodificar, agrega padding blanco conservando colores y contraste del original y coordina los colaboradores OCR.
- `PdfPageRenderer`: valida el máximo de páginas y los píxeles de cada página antes de renderizar; procesa una página RGB a 300 DPI por vez y libera la imagen al terminar el OCR.
- `OcrApiClient`: serializa imagenes a PNG, envia el request al OCR, valida `/health` y administra timeouts y reintentos configurables.
- `OcrResultMapper`: transforma la respuesta JSON de PaddleOCR en `OcrResult`, incluyendo lineas, detecciones, bounding boxes y metadata de variante/score.
- `OcrReviewMapper`: arma la lista de páginas para la UI sin mezclar sus detecciones; conserva la vista previa y distingue las páginas de texto digital. `OcrResultMapper` rechaza vistas previas inválidas sin descartar la extracción.
- `BrandCatalog`: fachada sincronizada para el catalogo de marcas; conserva la API pública y delega matching y persistencia.
- `BrandCatalogMatcher`: normaliza, resuelve aliases y calcula coincidencias exactas o fuzzy sin tocar archivos.
- `BrandCatalogStore`: lee y guarda `data/brands.json` ordenado y aislado de las reglas de matching.
- `CorrectionMemory`: fachada sincronizada para aprendizaje de correcciones; conserva `Entry` y la API usada por parser/controller.
- `CorrectionMemoryRules`: aplica normalizacion, matching por firma/solapamiento y fallback de campos en memoria.
- `CorrectionMemoryStore`: serializa y carga `data/corrections.json` sin mezclar persistencia con las reglas de dominio.
- `ReceiptParserService`: fachada del parsing OCR. Orquesta comercio, items, marcas, memoria y exportaciones sin exponer los colaboradores internos.
- `ReceiptLayoutReader`: reconstruye filas desde `OcrDetection` por pagina, detecta candidatos tabulares con cantidad/precio unitario/importe y evita mezclar coordenadas iguales de paginas distintas.
- `ReceiptLineAnalyzer`: concentra normalizacion OCR, deteccion de metadata/resumen, clasificacion de lineas y extraccion de valores monetarios.
- `ReceiptDateParser`: extrae y normaliza fechas de ticket; acepta un `Clock` para probar de forma determinista fechas sin anio.
- `ReceiptAmounts` y `ReceiptTotalCalculator`: parsean/formatean importes `es-AR` y calculan el total desde los items reconocidos. El formateador se crea por llamada para evitar estado compartido entre requests.
- `PedidosYaReceiptParser`: contiene el camino especial para cantidades, kg, precios inline o en lineas siguientes y warnings propios de capturas de PedidosYa Market.
- `BrandCatalog`: carga `data/brands.json`, busca marcas por alias al inicio o en cualquier parte de la descripcion y puede recordar marcas nuevas escribiendo el JSON. Ignora varias marcas genericas/no validas.
- `StoreNameMapper`: carga `store-mappings.json` desde classpath, normaliza texto OCR y resuelve nombres canonicos de comercio. Tiene reglas hardcodeadas para variantes de `Zou Wenguo`/`Los Tres Corazones`.
- `CorrectionMemory`: persiste en `data/corrections.json` entradas `{ store, firma, descripcion, marca, categoria, veces, ultimaVez }`. `find(store, firma)` matchea por igualdad exacta, substring compacta o solapamiento de tokens, siempre con alcance de comercio; `upsert` incrementa `veces` y conserva campos previos si no vienen valores nuevos. No crea el archivo hasta la primera escritura.

## Repositories/DAOs relevantes

No existen repositories ni DAOs. No hay Spring Data, JDBC ni JPA en el `pom.xml`.

Persistencia presente:

- `data/brands.json` es leido y potencialmente modificado por `BrandCatalog.remember` en runtime.
- `data/corrections.json` es leido y modificado por `CorrectionMemory.upsert`; no se versiona en git.
- `store-mappings.json` es recurso readonly de classpath para aliases de comercios.

Inferencia: `data/brands.json` y `data/corrections.json` funcionan como almacenamiento local de conocimiento, no como base de datos transaccional.

## DTOs y mappings importantes

- `ExtractResponse`: DTO de salida del endpoint `/api/receipts/extract`, incluyendo `variant` y `score` OCR cuando vienen del flujo completo.
- `OcrResult`, `OcrLine`, `OcrDetection`: DTOs internos para conservar metadata estructurada del OCR en Java, incluyendo paginas internas cuando el origen es PDF.
- `ReceiptItem`: DTO/fila de producto.
- `CorrectionsRequest`: DTO de entrada de `POST /api/corrections`, con `store` y lista de `Correction(firma, descripcion, marca, categoria)`.
- `DelimitedExporter`: mapea `List<ReceiptItem>` a texto delimitado con headers. Campos exportados: `Descripcion`, `Marca`, `Lugar de compra`, `Categoria`, `Cantidad`, `Precio unitario`, `Fecha`.
- `StoreNameMapper`: mapping de nombres detectados a nombres canonicos, por ejemplo `zou wenguo` -> `Los Tres Corazones`, `tienda filipa s r l` -> `Tienda Filipa`.
- `BrandCatalog`: mapping de alias/marcas desde `data/brands.json`; agrega aliases especiales para `La Providencia`, `Frutigram` y `Union Ganadera`.

## Integraciones externas y clientes REST

Integracion local principal:

- Backend Java -> OCR Flask/PaddleOCR.
- URL por defecto local: `http://127.0.0.1:5000/ocr` y health `http://127.0.0.1:5000/health`.
- En Docker Compose se sobreescribe a `http://paddleocr:5000/ocr` y `http://paddleocr:5000/health` mediante variables `APP_OCR_ENDPOINT` y `APP_OCR_HEALTH_ENDPOINT`.
- Header enviado: `X-OCR-Language`, valor default `es`.
- Content-Type enviado a OCR: `image/png`.

Frontend externo:

- `index.html` carga Google Fonts desde `fonts.googleapis.com`/`fonts.gstatic.com`.

La carga opcional conecta el backend local con `oauth2.googleapis.com/token` y `sheets.googleapis.com/v4/spreadsheets`. El navegador solo llama a la API local. El archivo debe estar compartido con el email de la cuenta de servicio como Editor. La autorización del conector de Drive de un chat no se reutiliza como credencial de esta aplicación. Ver `docs/GOOGLE_SHEETS.md` para el alta y las fuentes oficiales.

## Configuracion de base de datos y migraciones

No hay base de datos configurada. `application.properties` configura multipart, límites de procesamiento, errores y parámetros OCR.

No hay migraciones Flyway/Liquibase ni dependencias relacionadas.

Configuracion relevante:

- `spring.servlet.multipart.max-file-size=20MB`.
- `spring.servlet.multipart.max-request-size=20MB`.
- `app.upload.max-image-pixels=20000000` y `app.upload.max-image-side=10000`.
- `app.pdf.max-pages=20` y `app.pdf.max-page-pixels=20000000` (límite por página renderizada a 300 DPI).
- `server.error.include-message=never`.
- `app.ocr.endpoint`.
- `app.ocr.health-endpoint`.
- `app.ocr.language=es`.
- `app.ocr.connect-timeout-ms=5000`.
- `app.ocr.read-timeout-ms=300000`.
- `app.ocr.max-attempts=3`; si OCR no está disponible, el backend informa el error en pocos segundos.
- `app.sheets.credentials-path=data/google-service-account.json`, `app.sheets.config-path=data/sheets-config.json`, `app.sheets.history-path=data/sheets-history.json` y `app.sheets.timeout-ms=15000`. Variables equivalentes `APP_SHEETS_*`; en Compose se usa `/app/data/` por el volumen existente. Claves, configuración personal, catálogo histórico y `secrets/` están ignorados por Git y excluidos del contexto Docker.

## BPM/Flowable

No existe BPM/Flowable. No hay dependencias, archivos BPMN, procesos ni integracion Java asociada.

## Flujo tipico de una operacion

1. Usuario abre `http://localhost:8080`, servido desde `src/main/resources/static/index.html`.
2. `app.js` captura submit del formulario, arma `FormData` con `file` y hace `fetch('/api/receipts/extract', { method: 'POST', body })`.
3. `ReceiptController.extract` valida que el archivo no este vacio.
4. `OcrService.extract` inspecciona el contenido: busca la firma PDF y, para imágenes, consulta el formato y las dimensiones con `ImageReader` antes de decodificar.
5. Si es PDF, `PdfPageRenderer` valida el número de páginas y los píxeles estimados; renderiza y procesa cada página RGB a 300 DPI antes de pasar a la siguiente.
6. Java agrega padding blanco y conserva el color y contraste original; las mejoras visuales se comparan como candidatos en Python.
7. `OcrApiClient` serializa la imagen como PNG y, antes de cada intento, consulta `/health` hasta que `ocrReady=true`.
8. `ocr/service.py` recibe PNG y delega en `image_variants.py`, que genera de a una variantes originales sin recorte, crop, rotaciones, grises, contraste, threshold, denoise y escalados. Cada variante se limita a 1400 píxeles en el lado corto y 4 megapíxeles, conservando detalle en tickets estrechos y largos.
   Con `OCR_IMAGE_CORRECTIONS=true` (valor por defecto), también compara contraste local CLAHE, compensación de fondo y, cuando hay evidencia suficiente, perspectiva/inclinación corregidas. `document_geometry.py` estima la geometría una sola vez sobre el original limitado de tamaño, exige un contorno de papel contrastado o varios renglones consistentes y reutiliza el resultado en las cuatro orientaciones. `illumination.py` conserva niveles de gris y limita la ganancia para proteger trazos tenues. Estas variantes opcionales no reemplazan el original; si fallan se continúa con las demás.
9. `ocr_regions.py` divide las variantes grandes en bloques de hasta 1400 píxeles por lado, con 160 píxeles de solapamiento. PaddleOCR usa el mismo límite del detector. Las cajas vuelven a coordenadas de la variante y se deduplican por geometría entre bloques, prefiriendo detecciones completas. `receipt_layout.py` agrupa filas con tolerancia proporcional al texto y elige la fila más cercana. `score_lines` pondera las señales estructurales por confianza, usa palabras fiscales completas y evita sumar duplicados geométricos al puntaje, sin premiar marcas concretas.
   Después de elegir la variante ganadora, `targeted_retry.py` relee hasta ocho detecciones con confianza conocida inferior a 0,8. Usa las coordenadas y píxeles de esa variante, con dos tratamientos de cada recorte ampliado y un máximo de 16 llamadas OCR. Exige consenso del texto, confianza mínima de 0,85 y una mejora de al menos 0,1 en ambas lecturas, además de controles de cobertura, contenido y separación de vecinos. Cada cambio conserva la caja y posición original; el servicio reconstruye líneas y score, y añade `+retry` al nombre de variante si hubo cambios. Se habilita por defecto y se desactiva con `OCR_TARGETED_RETRY=false`. Los errores o resultados insuficientes conservan la lectura previa. En modo debug se escribe `targeted-retry.json` con contadores sin transcripciones.
10. OCR devuelve texto, lineas, detecciones, score y metadata de variante.
11. Java conserva la metadata en `OcrResult`; para PDFs agrega `pages` para que las coordenadas de paginas distintas no se mezclen.
12. `ReceiptParserService.parse(OcrResult)` usa `ReceiptLayoutReader` si hay geometria OCR, y cae al parser textual cuando no hay cajas suficientes.
13. `ReceiptParserService` orquesta `ReceiptDateParser`, `ReceiptLineAnalyzer`, `PedidosYaReceiptParser` y las reglas de tickets comunes para producir items.
14. `DelimitedExporter` genera salidas pipe-separated, TSV con header y TSV sin header.
15. `app.js` renderiza comercio, fecha, cantidad de items, advertencias, tabla editable y texto OCR crudo; botones copian al portapapeles.
16. `app.js` compara cada item editado contra el snapshot original y, con debounce, envia a `POST /api/corrections` solo los campos marca/categoria/descripcion que cambiaron.
17. `ReceiptParserService` consulta `CorrectionMemory` para aplicar lo aprendido (override) y recuperar lineas perdidas (warnings "Recuperado de memoria").
18. Las lineas `subtotal`, `subtot` y variantes se descartan como resumen, nunca como item; si no se reconoce una marca, se usa `Generico`.
19. `ReceiptTotalCalculator` calcula `total` sumando `cantidad * precioUnitario` de los items extraidos, nunca leyendo el total impreso del OCR; la UI lo recalcula al editar o deseleccionar filas.
20. `BrandCatalog` aplica Levenshtein sobre la primera palabra: menos de 30% produce `Genérico`, 30%-70% deja la palabra OCR editable con warning y más de 70% aplica la marca del catálogo.
21. La memoria solo aprende filas cuya marca original no era `Genérico` y cuyo resultado es una marca real; una fila originalmente `Genérico` no guarda ninguna edición.
22. La categoría base se determina por comercio: `Los Tres Corazones`, `PedidosYa Market - San Miguel II` y `Tienda Filipa` usan `Supermercado`; `Perfumerías Pigmento` usa `Perfumeria`; `Central de Sabores` usa `Panaderia`; `Estancia San Francisco` usa `Otros`; `Farmacias TKL San Miguel` usa `Farmacia`; y `Tuti Fruti` usa `Verduleria`. Una categoría vacía en memoria nunca borra la categoría detectada.
23. Opcionalmente el usuario pulsa `Cargar en Google Sheets`: la UI propone el mes/año según las fechas de las filas seleccionadas o permite seleccionar otra pestaña compatible. Congela ediciones y destino mientras envía un snapshot válido. La fecha se convierte a número de días desde 1899-12-30, precio y cantidad a números y los textos a `stringValue` para evitar interpretar fórmulas. La carga y su marcador se confirman juntas.

## Frontend

- `index.html`: pagina unica orientada a usuario final, con carga de archivo, metadata, advertencias accionables, tabla editable, total calculado, copia principal para Sheets sin encabezado y texto original oculto en un desplegable de diagnostico.
- `styles.css`: estilos responsive, tema visual beige/verde, tipografias Manrope y Space Grotesk, layout de paneles y media query para mobile.
- `app.js`: controla estado de seleccion de archivo, submit async, llamada al backend, manejo de errores `{message}`, render de items/warnings con badge `Memorizado`, regeneracion de salidas desde la tabla, envio automatico de correcciones con debounce y copia con `navigator.clipboard.writeText`.
- `ocr-review.mjs`: valida las páginas de revisión, dibuja las cajas SVG sobre la imagen procesada y amplía la zona seleccionada en canvas. El filtro inicial muestra confianza < 0,80 o desconocida. Permite cambiar de página, mostrar todas las zonas y usar teclado. Limpia la imagen al cambiar de archivo; los errores de vista previa no bloquean el texto. El recorte se limita a 2000 × 800 px, con desplazamiento interno en móvil.

La combinación de variantes usa metadatos explícitos de marco y escala de `image_variants.py`: nunca asume que dos imágenes están alineadas solo por tener igual tamaño. `variant_fusion.py` conserva orden, cantidad y geometría de detecciones. Solo cambia texto con confianza < 0,80 ante al menos dos imágenes distintas coincidentes con confianza >= 0,85 y mejora >= 0,10. Rechaza cobertura incompleta, texto de vecinos, pérdida de campos/signos y contradicciones entre lecturas. No agrega renglones omitidos ni ejecuta llamadas OCR extra. `OCR_VARIANT_FUSION` está habilitado por defecto; `+fusion` marca cambios aceptados y el debug opcional incluye `variant-fusion.json`.

Comunicacion con backend:

- Extracción: `POST /api/receipts/extract`; aprendizaje: `POST /api/corrections`; destino, comprobación y carga: `/api/sheets/config`, `/api/sheets/check`, `/api/sheets/append`.
- Request: `multipart/form-data`, campo `file`.
- Response esperada: JSON con campos de `ExtractResponse`.

No hay framework frontend, router, bundler ni servicios separados.

## Sistema de tests y como ejecutarlos

- Tests Java con JUnit 5 via `spring-boot-starter-test`.
- Tests principales: `ReceiptParserServiceTest`, `ReceiptLayoutReaderTest`, `ReceiptEvaluationTest`, `ReceiptParsingComponentsTest`, `OcrServiceTest`, `OcrApiClientTest`, `OcrResultMapperTest`, `PdfPageRendererTest`, `BrandCatalogComponentsTest` y `CorrectionMemoryComponentsTest`.
- Cobertura Java actual: tests del parser, componentes puros de fechas/importes/totales/PedidosYa, fachada OCR, cliente HTTP OCR con health/reintentos, mapeo JSON OCR, límites de render PDF, matching de marcas, stores JSON y reglas de memoria. El banco sintético de evaluación cubre texto OCR multipágina, cantidades/precios separados, ruido de precios aislados y detecciones geométricas; reporta aciertos exactos por campo.
- Tests Python con `unittest` en `ocr/tests`: conservación de texto tenue y color, generación gradual de variantes con límites de tamaño, coordenadas y duplicados de bloques solapados, merge de filas OCR a distintas escalas, scoring por confianza y preprocesamiento `without-lines`. Los tests de `service.py` verifican el contrato Flask y la orquestación con PaddleOCR simulado, sin descargar modelos.
- Las regresiones de `document_geometry.py` e `illumination.py` usan imágenes sintéticas para verificar inclinación, perspectiva, conservación de bordes, sombras y trazos tenues. `test_evaluation.py` verifica métricas y el envío multipart al backend con respuestas simuladas.
- Las pruebas de `targeted_retry.py` verifican consenso, precios contradictorios, fragmentos incompletos, vecinos, coordenadas y límites de relectura con respuestas OCR simuladas. Las pruebas del servicio comprueban que la relectura ocurre solo sobre la variante ganadora y que líneas, detecciones, score y debug se actualizan juntos.
- `ocr/evaluate.py` ejecuta un manifest explícito de imágenes/PDF y transcripciones locales a través de `POST /api/receipts/extract`. Evalúa `rawText`, registra variante/score si están disponibles y compara informes solo para casos con el mismo ID y hash de archivo/transcripción/importes. Los informes contienen métricas, no transcripciones. Ver `docs/OCR_EVALUATION.md`; los datos y resultados pertenecen a `test-data/`, ignorado por Git.
- `OcrReviewTest` comprueba el mapeo de vistas previas, las respuestas antiguas sin imagen y la separación de páginas. Las pruebas Python de fusión cubren escalas, marcos incompatibles, consenso, importes contradictorios y cajas divididas/repetidas. Las de vista previa verifican presupuesto y coordenadas.
- `src/test/js/ocr-review.test.mjs` verifica la normalización de páginas y las coordenadas de recortes cuando el JPEG está reducido. Se ejecuta con `node --test src/test/js/ocr-review.test.mjs`, sin npm ni dependencias adicionales. No hay integración automatizada con modelos OCR reales.
- `SheetsServiceTest`, `SheetsConfigStoreTest`, `GoogleServiceAccountAuthTest` y `GoogleSheetsClientTest` verifican schema, persistencia local, números/fechas, textos literales, slots con fórmulas preparadas, comentarios, rangos protegidos, UUID atómico, JWT, tokens y errores con Google simulado. `receipt-sheets.test.mjs` verifica selección, ediciones, destino, doble clic, reintentos y respuesta de la API. Ejecutar `node --test src/test/js/*.test.mjs` sin npm. La comprobación real de permisos y la escritura remota requieren credenciales del usuario y un archivo compartido.

Comando:

```bash
mvn test
```

## Maven/npm y comandos habituales

Maven:

```bash
mvn test
mvn spring-boot:run
mvn package
```

Docker Compose:

```bash
docker compose up --build -d
docker compose logs -f app paddleocr
docker compose down
```

Compose comprueba `GET /health` del servicio OCR hasta que `ocrReady=true`; el contenedor `app` espera a que `paddleocr` quede saludable antes de iniciar.

Desarrollo local mixto, segun README:

```bash
docker compose up --build -d paddleocr
mvn spring-boot:run
```

npm:

- No aplica. No hay `package.json`.

## Convenciones o patrones particulares detectados

- Uso de Java records para DTOs simples.
- El arranque local Windows en `scripts/local.ps1` espera los modelos OCR y ejecuta snapshots del JAR en `logs/runtime/`. Los accesos del escritorio pueden delegar en este script con `Start`/`Stop`. Evita recompilar un archivo en uso y valida los procesos antes de detenerlos. El entorno Java 17 usa su fallback TCP de loopback para evitar errores de sockets internos de Windows.
- Los recursos/rutas inexistentes responden 404; métodos no permitidos responden 405 y solicitudes con tipo incompatible responden 415. No pasan por el handler genérico 500. El frontend declara `/favicon.svg`.
- Parser basado en expresiones regulares, normalizacion de OCR, layout OCR cuando existe geometria y reglas hardcodeadas por comercio/producto.
- Categoria base por comercio conocido, con fallback `Supermercado`.
- Precios formateados con locale `es-AR` y `DecimalFormat("0.00")`, por ejemplo `2400,00`.
- Fechas normalizadas a patron `d/M/yyyy`; fechas sin anio usan el anio actual del sistema.
- `ReceiptDateParser` reconoce meses españoles completos y abreviados (`sep`, `sept`, `septiembre`, `setiembre`, etc.) en fechas etiquetadas o aisladas. Acepta `Entregado`/`Entregada` de PedidosYa y reúne hasta tres líneas OCR contiguas del encabezado. Prioriza `Fecha` explícita, valida el calendario y excluye vencimiento, fabricación e inicio de actividad. El año ausente mantiene la regla del año actual; no se deduce a partir del día de la semana.
- PedidosYa excluye compensaciones, cupones y botones antes de buscar productos y precios. El scoring OCR concede un bonus acotado por una fecha de entrega legible y no cuenta los importes de esos bloques como evidencia de productos.
- Marcas: primero intenta catalogo conocido; si no encuentra, infiere la marca desde las primeras palabras de la descripcion y puede persistirla en `data/brands.json`.
- Comercio: deteccion por primeras lineas del ticket y posterior normalizacion via `StoreNameMapper`.
- PedidosYa se detecta si alguna linea contiene `pedidosya`, `pedidos ya`, `podidosya` o una combinacion de `market` y `pedido`; usa parsing especial para cantidades `x`, kg y precios con descuentos y evita advertencias sobre bloques de interfaz.
- OCR Python compara también el original sin recorte ni contraste. Genera variantes de manera gradual para acotar memoria y reconoce imágenes largas por bloques sin reducir todo el ticket a un lado largo fijo. El scoring heurístico pondera estructura, precios y líneas tipo item por confianza, sin tokens de marcas concretas. El procesamiento por bloques puede aumentar el tiempo de OCR.

## Deuda tecnica o partes confusas importantes

- `ReceiptParserService` ya delega fechas, importes/totales, analisis de lineas y PedidosYa, pero todavia concentra reglas de productos, marcas, comercio y recuperacion de memoria. Esas son las siguientes fronteras de extraccion si vuelve a crecer.
- Las reglas de productos, metadata, stop words, comercios especiales y aliases estan mezcladas entre codigo Java y JSON.
- `BrandCatalog.remember` escribe en `data/brands.json` desde runtime. En Docker, con el volumen `./data:/app/data` montado en `compose.yaml`, `brands.json` y `corrections.json` persisten entre rebuilds.
- CSV/TSV mantienen las siete columnas; la carga directa a Sheets también escribe la fórmula de precio total en H y conserva comentarios I.
- `ApiExceptionHandler` devuelve HTTP 400 para entradas inválidas y HTTP 502 para fallas de OCR/conectividad.
- El límite de carga sigue siendo 20 MB; además, imágenes están limitadas a 20 megapíxeles/10.000 px por lado y PDF a 20 páginas/20 megapíxeles renderizados por página. Son configurables mediante propiedades `app.upload.*` y `app.pdf.*`.
- `OcrService` identifica PDF por firma de contenido, no por extensión o content type; sus límites y el procesamiento incremental tienen pruebas unitarias.
- `application.properties` default apunta a `127.0.0.1:5000`; en Docker depende de variables de entorno convertidas por Spring relaxed binding.
- `tessdata` sugiere una implementacion previa con Tesseract, pero no hay uso actual en codigo.
- No hay persistencia de tickets, autenticación de usuarios ni rate limiting. Hay un catálogo histórico opcional de productos; Google Sheets requiere la autorización de la cuenta de servicio.
- El contrato del endpoint `/ocr` tiene pruebas con PaddleOCR simulado; falta evaluar la precisión y latencia con modelos y tickets reales.

## Indice rapido para IA

| Area | Archivos/clases principales | Para que sirven |
|---|---|---|
| Entrada Spring Boot | `FacturasOcrApplication.java` | Bootstrap de la aplicacion Spring Boot. |
| API receipts | `ReceiptController.java` | Expone `POST /api/receipts/extract` para subir imagen/PDF y obtener datos parseados. |
| Errores API | `ApiExceptionHandler.java` | Devuelve errores JSON con HTTP 400 para entrada inválida y HTTP 502 para fallas del OCR. |
| OCR Java facade | `OcrService.java` | Detecta el tipo de archivo, preprocesa imagenes y coordina la extracción OCR. |
| PDF OCR input | `PdfPageRenderer.java` | Limita páginas/píxeles y renderiza una página PDF RGB por vez. |
| OCR HTTP client | `OcrApiClient.java` | Serializa PNG, consulta health, llama a Flask/PaddleOCR y maneja reintentos. |
| OCR response mapping | `OcrResultMapper.java` | Convierte JSON OCR a `OcrResult` estructurado. |
| Parser de tickets | `ReceiptParserService.java` | Fachada que orquesta la extraccion de comercio, items, marcas, memoria y exportaciones. |
| Componentes parser | `ReceiptLayoutReader.java`, `ReceiptLineAnalyzer.java`, `ReceiptDateParser.java`, `ReceiptAmounts.java`, `ReceiptTotalCalculator.java`, `PedidosYaReceiptParser.java` | Aislan layout OCR, normalizacion, fechas, importes, total y parsing especial de PedidosYa. |
| Catalogo marcas | `BrandCatalog.java`, `BrandCatalogMatcher.java`, `BrandCatalogStore.java`, `data/brands.json` | Busca, normaliza, recuerda marcas detectadas y persiste el catalogo separado de las reglas. |
| Mapeo comercios | `StoreNameMapper.java`, `store-mappings.json` | Convierte aliases OCR de comercios a nombres canonicos. |
| Memoria de correcciones | `CorrectionMemory.java`, `CorrectionMemoryRules.java`, `CorrectionMemoryStore.java`, `data/corrections.json` | Aprende y reutiliza correcciones por comercio con reglas y persistencia separadas. |
| Endpoint aprendizaje | `CorrectionController.java`, `CorrectionsRequest.java` | Recibe correcciones del frontend y las persiste. |
| DTO respuesta | `ExtractResponse.java` | JSON completo devuelto al frontend/API. |
| DTO item | `ReceiptItem.java` | Representa una fila de producto extraida. |
| Export delimitado | `DelimitedExporter.java` | Genera salida con `|`, TSV con header y TSV sin header. |
| Frontend HTML | `static/index.html` | UI de carga, resultados y textareas de salida. |
| Frontend JS | `static/app.js` | Maneja submit, fetch al backend, render y clipboard. |
| Frontend CSS | `static/styles.css` | Estilos responsive de la pagina. |
| OCR service | `ocr/service.py`, `ocr/image_variants.py`, `ocr/ocr_regions.py`, `ocr/receipt_layout.py`, `ocr/scoring.py` | Flask + PaddleOCR; compara original y variantes, reconoce por bloques solapados, mergea filas y scorea variantes por confianza. |
| Geometría e iluminación OCR | `ocr/document_geometry.py`, `ocr/illumination.py` | Candidatos de perspectiva/inclinación corregidas, contraste local y compensación de sombras; configurables con `OCR_IMAGE_CORRECTIONS`. |
| Relectura de zonas dudosas | `ocr/targeted_retry.py` | Compara dos lecturas ampliadas de detecciones con baja confianza; conserva cajas y requiere consenso. Configurable con `OCR_TARGETED_RETRY`. |
| Combinación de variantes | `ocr/variant_fusion.py` | Reutiliza lecturas con coordenadas compatibles, exige consenso y mantiene cajas/renglones. Configurable con `OCR_VARIANT_FUSION`. |
| Revisión visual OCR | `ocr/preview.py`, `OcrReviewMapper.java`, `OcrPreview.java`, `OcrReviewPage.java`, `static/ocr-review.mjs` | Vista previa por página, zonas seleccionables y recortes ampliados, sin historial persistente. |
| Evaluación OCR real | `ocr/evaluate.py`, `docs/OCR_EVALUATION.md` | Compara texto OCR crudo e informes sobre el mismo conjunto local de archivos y transcripciones. |
| OCR deps | `ocr/requirements.txt`, `ocr/Dockerfile` | Versiones Python y build del contenedor OCR. |
| Docker app | `Dockerfile`, `compose.yaml` | Build Java, runtime backend y orquestacion con servicio OCR. |
| Config app | `application.properties` | Multipart, límites de archivos/PDF, endpoint OCR, health, idioma, timeouts y reintentos. |
| Tests parser, OCR y memoria | `ReceiptParserServiceTest.java`, `ReceiptLayoutReaderTest.java`, `ReceiptEvaluationTest.java`, `ReceiptParsingComponentsTest.java`, `OcrServiceTest.java`, `OcrApiClientTest.java`, `OcrResultMapperTest.java`, `PdfPageRendererTest.java`, `BrandCatalogComponentsTest.java`, `CorrectionMemoryComponentsTest.java`, `src/test/resources/receipt-evaluation` | Regresiones del parser, banco anonimo de evaluacion y pruebas directas de fachadas, matching y persistencia. |
| Documentacion usuario | `README.md` | Uso con Docker, flujo recomendado, limites actuales y mejoras futuras. |
