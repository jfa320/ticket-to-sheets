# PROJECT_CONTEXT

## Objetivo general del sistema

Aplicacion local para extraer datos utiles de tickets/facturas de supermercado desde imagenes o PDFs. El usuario sube un archivo desde una UI web, el backend lo convierte/preprocesa si hace falta, llama a un servicio OCR local en Docker basado en PaddleOCR, parsea texto y geometria OCR con reglas heuristicas y devuelve filas listas para copiar en Google Sheets.

La salida principal modela productos comprados con columnas: `Descripcion`, `Marca`, `Lugar de compra`, `Categoria`, `Cantidad`, `Precio unitario`, `Fecha`. Tambien devuelve texto OCR crudo para depuracion.

Inferencia: el proyecto parece orientado a uso personal/local, no a despliegue multiusuario ni persistencia historica.

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

- `ReceiptItem.estado` marca cada fila como `CORRECT` o `AMBIGUOUS`.
- En filas reconstruidas desde detecciones, el parser también marca como ambiguas las que tienen alguna confianza OCR menor a `0.65` o una caja individual con una relación ancho/alto de `28` o más. La confianza ausente se trata como desconocida.
- `ExtractResponse.warnings` informa lineas dudosas descartadas, por ejemplo precios sin descripcion.
- La UI estatica renderiza una tabla editable, permite corregir o eliminar filas y resalta resultados ambiguos.
- La UI muestra el texto completo de las advertencias antes de copiar las salidas.
- `app.js` regenera CSV/TSV desde las filas editadas; los botones de copia no dependen de la exportacion inicial del backend.
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

Responsabilidades por capa:

- Controller: validacion minima HTTP y orquestacion de `OcrService` + `ReceiptParserService`.
- Service Java: OCR client/preprocesado y reglas de parsing de dominio.
- Model: records de respuesta y filas parseadas.
- Util: conversion de `ReceiptItem` a formatos delimitados.
- Frontend: upload, fetch, render de respuesta y copia al portapapeles.
- OCR Python: carga/warmup de PaddleOCR, preprocesamiento robusto de imagenes, merge de filas y scoring de variantes.

No hay base de datos; la memoria de correcciones y el catalogo de marcas usan archivos JSON locales.

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
- `ExtractResponse`: respuesta completa del endpoint. Contiene metadata (`storeName`, `date`, `itemCount`, `total` calculado desde los items), exportaciones (`csv`, `tsv`, `tsvWithoutHeader`), `rawText`, `items`, `warnings`, `variant` y `score` OCR.
- `OcrResult`: resultado OCR estructurado recibido desde Python, con `text`, `lines`, `detections`, `variant`, `score` y, para PDFs mergeados en Java, `pages`.
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
- Flujo: valida que el archivo no este vacio, llama `OcrService.extract(file)`, luego `ReceiptParserService.parse(ocrResult)` para conservar texto y geometria, y agrega `variant`/`score` a la respuesta.

Manejo de errores:

- `ApiExceptionHandler` captura `IllegalArgumentException`, `IllegalStateException` y `MethodArgumentNotValidException`.
- Responde HTTP 400 con cuerpo `{ "message": "..." }`.
- Inferencia: errores de OCR/integracion se exponen como 400 aunque algunos podrian considerarse 502/503 en una arquitectura remota.

Endpoints del microservicio OCR Python:

- `POST /ocr`: recibe bytes de imagen, header opcional `X-OCR-Language`, responde JSON `{ text, lines, detections, variant, score }` o `{ error }`.
- `GET /health`: responde `{ status: "ok", ocrReady: boolean }`.

Endpoint de aprendizaje:

- `POST /api/corrections`: recibe JSON `{ store, corrections: [{ firma, descripcion, marca, categoria }] }`, persiste la correccion en `CorrectionMemory` y registra la marca en `BrandCatalog`; responde `{ saved }`.

## Services principales

- `OcrService`: fachada de extracción. Detecta PDF por su firma, inspecciona las dimensiones de imagen antes de decodificar, preprocesa a escala de grises/contraste/padding y coordina los colaboradores OCR.
- `PdfPageRenderer`: valida el máximo de páginas y los píxeles de cada página antes de renderizar; procesa una página RGB a 300 DPI por vez y libera la imagen al terminar el OCR.
- `OcrApiClient`: serializa imagenes a PNG, envia el request al OCR, valida `/health` y administra timeouts y reintentos configurables.
- `OcrResultMapper`: transforma la respuesta JSON de PaddleOCR en `OcrResult`, incluyendo lineas, detecciones, bounding boxes y metadata de variante/score.
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
- `ReceiptAmounts` y `ReceiptTotalCalculator`: parsean/formatean importes `es-AR` y calculan el total desde items e impuestos detectados. El formateador se crea por llamada para evitar estado compartido entre requests.
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

No hay clientes REST a APIs publicas como Google Sheets; la aplicación se mantiene local y exporta texto para copiarlo manualmente.

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

## BPM/Flowable

No existe BPM/Flowable. No hay dependencias, archivos BPMN, procesos ni integracion Java asociada.

## Flujo tipico de una operacion

1. Usuario abre `http://localhost:8080`, servido desde `src/main/resources/static/index.html`.
2. `app.js` captura submit del formulario, arma `FormData` con `file` y hace `fetch('/api/receipts/extract', { method: 'POST', body })`.
3. `ReceiptController.extract` valida que el archivo no este vacio.
4. `OcrService.extract` inspecciona el contenido: busca la firma PDF y, para imágenes, consulta el formato y las dimensiones con `ImageReader` antes de decodificar.
5. Si es PDF, `PdfPageRenderer` valida el número de páginas y los píxeles estimados; renderiza y procesa cada página RGB a 300 DPI antes de pasar a la siguiente.
6. Java preprocesa la imagen con escala de grises, contraste y padding.
7. `OcrApiClient` serializa la imagen como PNG y, antes de cada intento, consulta `/health` hasta que `ocrReady=true`.
8. `ocr/service.py` recibe PNG, genera variantes con crop, rotaciones, grises, contraste, threshold, denoise y escalados.
9. PaddleOCR corre sobre cada variante; `score_lines` elige la variante con mas senales estructurales utiles, como precios, lineas tipo item, metadata generica y confianza, sin premiar marcas concretas.
10. OCR devuelve texto, lineas, detecciones, score y metadata de variante.
11. Java conserva la metadata en `OcrResult`; para PDFs agrega `pages` para que las coordenadas de paginas distintas no se mezclen.
12. `ReceiptParserService.parse(OcrResult)` usa `ReceiptLayoutReader` si hay geometria OCR, y cae al parser textual cuando no hay cajas suficientes.
13. `ReceiptParserService` orquesta `ReceiptDateParser`, `ReceiptLineAnalyzer`, `PedidosYaReceiptParser` y las reglas de tickets comunes para producir items.
14. `DelimitedExporter` genera salidas pipe-separated, TSV con header y TSV sin header.
15. `app.js` renderiza comercio, fecha, cantidad de items, advertencias, tabla editable y texto OCR crudo; botones copian al portapapeles.
16. `app.js` compara cada item editado contra el snapshot original y, con debounce, envia a `POST /api/corrections` solo los campos marca/categoria/descripcion que cambiaron.
17. `ReceiptParserService` consulta `CorrectionMemory` para aplicar lo aprendido (override) y recuperar lineas perdidas (warnings "Recuperado de memoria").
18. Las lineas `subtotal`, `subtot` y variantes se descartan como resumen, nunca como item; si no se reconoce una marca, se usa `Generico`.
19. `ReceiptTotalCalculator` calcula `total` sumando `cantidad * precioUnitario` de los items extraidos e impuestos detectados, nunca leyendo el total impreso del OCR; la UI lo recalcula al editar o deseleccionar filas.
20. `BrandCatalog` aplica Levenshtein sobre la primera palabra: menos de 30% produce `Genérico`, 30%-70% deja la palabra OCR editable con warning y más de 70% aplica la marca del catálogo.
21. La memoria solo aprende filas cuya marca original no era `Genérico` y cuyo resultado es una marca real; una fila originalmente `Genérico` no guarda ninguna edición.
22. La categoría base se determina por comercio: `Los Tres Corazones`, `PedidosYa Market - San Miguel II` y `Tienda Filipa` usan `Supermercado`; `Perfumerías Pigmento` usa `Perfumeria`; `Central de Sabores` usa `Panaderia`; `Estancia San Francisco` usa `Otros`; `Farmacias TKL San Miguel` usa `Farmacia`; y `Tuti Fruti` usa `Verduleria`. Una categoría vacía en memoria nunca borra la categoría detectada.

## Frontend

- `index.html`: pagina unica orientada a usuario final, con carga de archivo, metadata, advertencias accionables, tabla editable, total calculado, botones de copia y texto original oculto en un desplegable de diagnostico.
- `styles.css`: estilos responsive, tema visual beige/verde, tipografias Manrope y Space Grotesk, layout de paneles y media query para mobile.
- `app.js`: controla estado de seleccion de archivo, submit async, llamada al backend, manejo de errores `{message}`, render de items/warnings con badge `Memorizado`, regeneracion de salidas desde la tabla, envio automatico de correcciones con debounce y copia con `navigator.clipboard.writeText`.

Comunicacion con backend:

- Endpoint unico: `POST /api/receipts/extract`.
- Request: `multipart/form-data`, campo `file`.
- Response esperada: JSON con campos de `ExtractResponse`.

No hay framework frontend, router, bundler ni servicios separados.

## Sistema de tests y como ejecutarlos

- Tests Java con JUnit 5 via `spring-boot-starter-test`.
- Tests principales: `ReceiptParserServiceTest`, `ReceiptLayoutReaderTest`, `ReceiptEvaluationTest`, `ReceiptParsingComponentsTest`, `OcrServiceTest`, `OcrApiClientTest`, `OcrResultMapperTest`, `PdfPageRendererTest`, `BrandCatalogComponentsTest` y `CorrectionMemoryComponentsTest`.
- Cobertura Java actual: tests del parser, componentes puros de fechas/importes/totales/PedidosYa, fachada OCR, cliente HTTP OCR con health/reintentos, mapeo JSON OCR, límites de render PDF, matching de marcas, stores JSON y reglas de memoria. El banco sintético de evaluación cubre texto OCR multipágina, cantidades/precios separados, ruido de precios aislados y detecciones geométricas; reporta aciertos exactos por campo.
- Tests Python con `unittest` en `ocr/tests`: merge de filas OCR, scoring de variantes y preprocesamiento `without-lines`.
- No hay tests para frontend ni integracion con OCR real.

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
- Parser basado en expresiones regulares, normalizacion de OCR, layout OCR cuando existe geometria y reglas hardcodeadas por comercio/producto.
- Categoria base por comercio conocido, con fallback `Supermercado`.
- Precios formateados con locale `es-AR` y `DecimalFormat("0.00")`, por ejemplo `2400,00`.
- Fechas normalizadas a patron `d/M/yyyy`; fechas sin anio usan el anio actual del sistema.
- Marcas: primero intenta catalogo conocido; si no encuentra, infiere la marca desde las primeras palabras de la descripcion y puede persistirla en `data/brands.json`.
- Comercio: deteccion por primeras lineas del ticket y posterior normalizacion via `StoreNameMapper`.
- PedidosYa se detecta si alguna linea contiene `pedidosya`, `pedidos ya`, `podidosya` o una combinacion de `market` y `pedido`; usa parsing especial para cantidades `x`, kg y precios con descuentos y evita advertencias sobre bloques de interfaz.
- OCR Python prueba multiples variantes de imagen y elige por scoring heuristico basado en estructura del ticket, numeros/precios, confianza y lineas tipo item, sin tokens de marcas concretas.

## Deuda tecnica o partes confusas importantes

- `ReceiptParserService` ya delega fechas, importes/totales, analisis de lineas y PedidosYa, pero todavia concentra reglas de productos, marcas, comercio y recuperacion de memoria. Esas son las siguientes fronteras de extraccion si vuelve a crecer.
- Las reglas de productos, metadata, stop words, comercios especiales y aliases estan mezcladas entre codigo Java y JSON.
- `BrandCatalog.remember` escribe en `data/brands.json` desde runtime. En Docker, con el volumen `./data:/app/data` montado en `compose.yaml`, `brands.json` y `corrections.json` persisten entre rebuilds.
- El MVP no exporta precio total por item; la salida queda limitada a siete columnas verificadas.
- `ApiExceptionHandler` devuelve HTTP 400 para entradas inválidas y HTTP 502 para fallas de OCR/conectividad.
- El límite de carga sigue siendo 20 MB; además, imágenes están limitadas a 20 megapíxeles/10.000 px por lado y PDF a 20 páginas/20 megapíxeles renderizados por página. Son configurables mediante propiedades `app.upload.*` y `app.pdf.*`.
- `OcrService` identifica PDF por firma de contenido, no por extensión o content type; sus límites y el procesamiento incremental tienen pruebas unitarias.
- `application.properties` default apunta a `127.0.0.1:5000`; en Docker depende de variables de entorno convertidas por Spring relaxed binding.
- `tessdata` sugiere una implementacion previa con Tesseract, pero no hay uso actual en codigo.
- No hay persistencia de historial, autenticacion, autorizacion ni rate limiting.
- No hay tests de integracion con OCR real ni contrato del endpoint `/ocr`.

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
| OCR service | `ocr/service.py`, `ocr/receipt_layout.py`, `ocr/scoring.py` | Flask + PaddleOCR; genera variantes de imagen, ejecuta OCR, mergea filas y scorea variantes. |
| OCR deps | `ocr/requirements.txt`, `ocr/Dockerfile` | Versiones Python y build del contenedor OCR. |
| Docker app | `Dockerfile`, `compose.yaml` | Build Java, runtime backend y orquestacion con servicio OCR. |
| Config app | `application.properties` | Multipart, límites de archivos/PDF, endpoint OCR, health, idioma, timeouts y reintentos. |
| Tests parser, OCR y memoria | `ReceiptParserServiceTest.java`, `ReceiptLayoutReaderTest.java`, `ReceiptEvaluationTest.java`, `ReceiptParsingComponentsTest.java`, `OcrServiceTest.java`, `OcrApiClientTest.java`, `OcrResultMapperTest.java`, `PdfPageRendererTest.java`, `BrandCatalogComponentsTest.java`, `CorrectionMemoryComponentsTest.java`, `src/test/resources/receipt-evaluation` | Regresiones del parser, banco anonimo de evaluacion y pruebas directas de fachadas, matching y persistencia. |
| Documentacion usuario | `README.md` | Uso con Docker, flujo recomendado, limites actuales y mejoras futuras. |
