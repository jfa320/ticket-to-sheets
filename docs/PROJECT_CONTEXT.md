# PROJECT_CONTEXT

## Objetivo general del sistema

Aplicacion local para extraer datos utiles de tickets/facturas de supermercado desde imagenes o PDFs. El usuario sube un archivo desde una UI web, el backend lo convierte/preprocesa si hace falta, llama a un servicio OCR local en Docker basado en PaddleOCR, parsea el texto OCR con reglas heuristicas y devuelve filas listas para copiar en Google Sheets.

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
- `ocr`: microservicio Flask/PaddleOCR, preprocesamiento de imagenes, tests Python y Dockerfile.
- `tessdata`: contiene modelos `eng.traineddata` y `spa.traineddata`, pero el codigo actual no usa Tesseract.
- `src/test/java/com/opencode/facturas`: tests unitarios del parser y del cliente OCR estructurado.

## Estado actual de ambiguedad y UI

- `ReceiptItem.estado` marca cada fila como `CORRECT` o `AMBIGUOUS`.
- `ExtractResponse.warnings` informa lineas dudosas descartadas, por ejemplo precios sin descripcion.
- La UI estatica renderiza una tabla editable, permite corregir o eliminar filas y resalta resultados ambiguos.
- La UI muestra el texto completo de las advertencias antes de copiar las salidas.
- `app.js` regenera CSV/TSV desde las filas editadas; los botones de copia no dependen de la exportacion inicial del backend.
- Las reglas actuales de ambiguedad son textuales. Confidence y geometria OCR quedan disponibles para una fase posterior.
- PedidosYa tolera errores OCR frecuentes en el encabezado (`PodidosYa`) y descarta bloques de interfaz como `Tu pedido`, `Tu pago`, descuentos y detalles de entrega.
- Se agrego una regresion OCR reproducible en `test-data/receipts/pedidosya/pedidosya-market-san-miguel-ii-ocr.txt`; la imagen original del caso no estaba disponible como archivo en el workspace.
- `ReceiptItem.firma` identifica la linea OCR normalizada que origino cada fila y sirve de llave para el aprendizaje.
- El sistema aprende correcciones de marca, categoria y descripcion por comercio, las persiste en `data/corrections.json` y las reutiliza en el proximo ticket (override o recuperacion de filas perdidas), marcando los items aplicados como `LEARNED`.

## Arquitectura general

Arquitectura simple de dos servicios locales:

- Frontend estatico: `index.html`, `styles.css`, `app.js`. Permite elegir archivo, invoca endpoint REST y muestra/copia resultados.
- Backend Spring Boot: recibe multipart, procesa imagen/PDF, llama al OCR, conserva resultado OCR estructurado, parsea texto y devuelve JSON.
- Microservicio OCR Python: recibe una imagen PNG, ejecuta PaddleOCR sobre variantes preprocesadas y responde texto, lineas, detecciones, variante seleccionada y score.

Responsabilidades por capa:

- Controller: validacion minima HTTP y orquestacion de `OcrService` + `ReceiptParserService`.
- Service Java: OCR client/preprocesado y reglas de parsing de dominio.
- Model: records de respuesta y filas parseadas.
- Util: conversion de `ReceiptItem` a formatos delimitados.
- Frontend: upload, fetch, render de respuesta y copia al portapapeles.
- OCR Python: carga/warmup de PaddleOCR, preprocesamiento robusto de imagenes y scoring de variantes.

No hay capa de persistencia ni base de datos.

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
│   ├── preprocess.py
│   ├── requirements.txt
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
    │   │   │   └── ReceiptController.java
    │   │   ├── model/
    │   │   │   ├── ExtractResponse.java
    │   │   │   ├── OcrDetection.java
    │   │   │   ├── OcrLine.java
    │   │   │   ├── OcrResult.java
    │   │   │   └── ReceiptItem.java
    │   │   ├── service/
    │   │   │   ├── BrandCatalog.java
    │   │   │   ├── OcrService.java
    │   │   │   ├── ReceiptParserService.java
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
    └── test/java/com/opencode/facturas/
        ├── ReceiptParserServiceTest.java
        └── service/OcrServiceTest.java
```

Excluido: `.git`, `target`, logs, caches, outputs de build.

## Principales entidades de dominio y relaciones

- `ReceiptItem`: fila de producto extraida. Campos: `descripcion`, `marca`, `lugarDeCompra`, `categoria`, `cantidad`, `precioUnitario`, `fecha`, `estado`, `firma`.
- `ExtractResponse`: respuesta completa del endpoint. Contiene metadata (`storeName`, `date`, `itemCount`, `total` calculado desde los items), exportaciones (`csv`, `tsv`, `tsvWithoutHeader`), `rawText`, `items`, `warnings`, `variant` y `score` OCR.
- `OcrResult`: resultado OCR estructurado recibido desde Python, con `text`, `lines`, `detections`, `variant` y `score`.
- `OcrLine`: linea OCR mergeada con texto, confidence/score y geometria basica.
- `OcrDetection`: deteccion cruda OCR con texto, confidence y bounding box de 4 puntos.
- `BrandCatalog.BrandMatch`: record interno/publico de `BrandCatalog` para marca y alias normalizado encontrado.
- Records internos de `ReceiptParserService`: `BrandMatch`, `ProductRule`, `ParsedItemLine`, `PedidosYaProductLine`, `PedidosYaInlineItem`.

Relacion principal: `ExtractResponse` contiene una lista de `ReceiptItem`. Los items se derivan exclusivamente del texto OCR, reglas de marcas y reglas de nombres de comercios.

No hay entidades JPA ni agregados persistidos.

## Controllers/endpoints principales

- `ReceiptController` en `src/main/java/com/opencode/facturas/controller/ReceiptController.java`.
- Base path: `/api/receipts`.
- `POST /api/receipts/extract`.
- Consume: `multipart/form-data` con parametro `file` obligatorio.
- Produce: JSON serializado desde `ExtractResponse`.
- Flujo: valida que el archivo no este vacio, llama `OcrService.extract(file)`, luego `ReceiptParserService.parse(ocrResult.text())` y conserva `variant`/`score` en la respuesta.

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

- `OcrService`: integra Spring con OCR. Detecta PDF por extension `.pdf`, renderiza cada pagina con PDFBox a 300 DPI, preprocesa imagenes a escala de grises/contraste/padding, serializa PNG y llama al microservicio OCR. Devuelve `OcrResult` estructurado y mantiene `extractText` como compatibilidad. Usa `RestTemplate` con timeouts configurables y espera/reintenta contra `/health` hasta `app.ocr.max-attempts`.
- `ReceiptParserService`: parsea texto OCR. Extrae fecha, comercio, items, cantidades, precios, marcas, descripciones normalizadas y exportaciones. Tiene reglas generales para tickets y un camino especial para PedidosYa Market. La categoria se fija como `Supermercado`.
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
- `OcrResult`, `OcrLine`, `OcrDetection`: DTOs internos para conservar metadata estructurada del OCR en Java.
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

No hay clientes REST a APIs publicas como Google Sheets. README lista Google Sheets API como mejora futura.

## Configuracion de base de datos y migraciones

No hay base de datos configurada. `application.properties` solo configura multipart, mensajes de error y parametros OCR.

No hay migraciones Flyway/Liquibase ni dependencias relacionadas.

Configuracion relevante:

- `spring.servlet.multipart.max-file-size=20MB`.
- `spring.servlet.multipart.max-request-size=20MB`.
- `server.error.include-message=always`.
- `app.ocr.endpoint`.
- `app.ocr.health-endpoint`.
- `app.ocr.language=es`.
- `app.ocr.connect-timeout-ms=5000`.
- `app.ocr.read-timeout-ms=300000`.
- `app.ocr.max-attempts=120`.

## BPM/Flowable

No existe BPM/Flowable. No hay dependencias, archivos BPMN, procesos ni integracion Java asociada.

## Flujo tipico de una operacion

1. Usuario abre `http://localhost:8080`, servido desde `src/main/resources/static/index.html`.
2. `app.js` captura submit del formulario, arma `FormData` con `file` y hace `fetch('/api/receipts/extract', { method: 'POST', body })`.
3. `ReceiptController.extract` valida que el archivo no este vacio.
4. `OcrService.extract` decide si es PDF o imagen.
5. Si es PDF, `OcrService.extractFromPdf` renderiza cada pagina a imagen RGB 300 DPI con PDFBox.
6. Java preprocesa la imagen con escala de grises, contraste y padding, luego la envia como PNG al OCR.
7. Antes de cada intento, `OcrService.waitForOcrHealth` consulta `/health` hasta que `ocrReady=true`.
8. `ocr/service.py` recibe PNG, genera variantes con crop, rotaciones, grises, contraste, threshold, denoise y escalados.
9. PaddleOCR corre sobre cada variante; `score_lines` elige la variante con mas senales utiles.
10. OCR devuelve texto, lineas, detecciones, score y metadata de variante.
11. Java conserva la metadata en `OcrResult`; el parser todavia usa `ocrResult.text()` como entrada.
12. `ReceiptParserService.parse` normaliza lineas, extrae fecha/comercio e items. Para PedidosYa usa reglas especificas; para tickets comunes usa patrones de precio/descripcion.
13. `DelimitedExporter` genera salidas pipe-separated, TSV con header y TSV sin header.
14. `app.js` renderiza comercio, fecha, cantidad de items, advertencias, tabla editable y texto OCR crudo; botones copian al portapapeles.
15. `app.js` compara cada item editado contra el snapshot original y, con debounce, envia a `POST /api/corrections` solo los campos marca/categoria/descripcion que cambiaron.
16. `ReceiptParserService` consulta `CorrectionMemory` para aplicar lo aprendido (override) y recuperar lineas perdidas (warnings "Recuperado de memoria").
17. Las lineas `subtotal`, `subtot` y variantes se descartan como resumen, nunca como item; si no se reconoce una marca, se usa `Generico`.
18. `total` se calcula sumando `cantidad * precioUnitario` de los items extraidos, nunca leyendo el total del OCR; la UI lo recalcula al editar o deseleccionar filas.
19. `BrandCatalog` aplica Levenshtein sobre la primera palabra: menos de 30% produce `Genérico`, 30%-70% deja la palabra OCR editable con warning y más de 70% aplica la marca del catálogo.
20. La memoria solo aprende filas cuya marca original no era `Genérico` y cuyo resultado es una marca real; una fila originalmente `Genérico` no guarda ninguna edición.
21. La categoría base se determina por comercio: `Los Tres Corazones`, `PedidosYa Market - San Miguel II` y `Tienda Filipa` usan `Supermercado`; `Perfumerías Pigmento` usa `Perfumeria`; `Central de Sabores` usa `Panaderia`; `Estancia San Francisco` usa `Otros`; `Farmacias TKL San Miguel` usa `Farmacia`; y `Tuti Fruti` usa `Verduleria`. Una categoría vacía en memoria nunca borra la categoría detectada.

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
- Tests principales: `ReceiptParserServiceTest` y `OcrServiceTest`.
- Cobertura Java actual: tests del parser, de `CorrectionMemory` y del cliente OCR; tickets comunes, lineas OCR separadas, tickets largos, casos especiales de queso Punta del Agua, fechas, Tienda Filipa, PedidosYa Market con typos OCR, ambiguedad, override/recuperacion de memoria y parsing de OCR estructurado.
- Tests Python con `unittest` en `ocr/tests`: merge de filas OCR y preprocesamiento `without-lines`.
- No hay tests para controller ni frontend.

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

Desarrollo local mixto, segun README:

```bash
docker compose up --build -d paddleocr
mvn spring-boot:run
```

npm:

- No aplica. No hay `package.json`.

## Convenciones o patrones particulares detectados

- Uso de Java records para DTOs simples.
- Parser basado en expresiones regulares, normalizacion de OCR y reglas hardcodeadas por comercio/producto.
- Categoria hardcodeada como `Supermercado`.
- Precios formateados con locale `es-AR` y `DecimalFormat("0.00")`, por ejemplo `2400,00`.
- Fechas normalizadas a patron `d/M/yyyy`; fechas sin anio usan el anio actual del sistema.
- Marcas: primero intenta catalogo conocido; si no encuentra, infiere la marca desde las primeras palabras de la descripcion y puede persistirla en `data/brands.json`.
- Comercio: deteccion por primeras lineas del ticket y posterior normalizacion via `StoreNameMapper`.
- PedidosYa se detecta si alguna linea contiene `pedidosya`, `pedidos ya`, `podidosya` o una combinacion de `market` y `pedido`; usa parsing especial para cantidades `x`, kg y precios con descuentos y evita advertencias sobre bloques de interfaz.
- OCR Python prueba multiples variantes de imagen y elige por scoring heuristico basado en tokens esperados, numeros/precios y lineas tipo item.

## Deuda tecnica o partes confusas importantes

- `ReceiptParserService` concentra mucha logica heuristica en una unica clase grande; es dificil extender sin romper casos existentes.
- Las reglas de productos, metadata, stop words, comercios especiales y aliases estan mezcladas entre codigo Java y JSON.
- `BrandCatalog.remember` escribe en `data/brands.json` desde runtime. En Docker, con el volumen `./data:/app/data` montado en `compose.yaml`, `brands.json` y `corrections.json` persisten entre rebuilds.
- El MVP no calcula ni exporta precio total; la salida queda limitada a siete columnas verificadas.
- `ApiExceptionHandler` convierte errores de OCR/conectividad en HTTP 400; puede dificultar distinguir errores de usuario de errores de infraestructura.
- `OcrService` detecta PDF solo por nombre de archivo terminado en `.pdf`, no por content type.
- `application.properties` default apunta a `127.0.0.1:5000`; en Docker depende de variables de entorno convertidas por Spring relaxed binding.
- `tessdata` sugiere una implementacion previa con Tesseract, pero no hay uso actual en codigo.
- No hay persistencia de historial, autenticacion, autorizacion ni rate limiting.
- No hay tests de integracion con OCR real ni contrato del endpoint `/ocr`.

## Indice rapido para IA

| Area | Archivos/clases principales | Para que sirven |
|---|---|---|
| Entrada Spring Boot | `FacturasOcrApplication.java` | Bootstrap de la aplicacion Spring Boot. |
| API receipts | `ReceiptController.java` | Expone `POST /api/receipts/extract` para subir imagen/PDF y obtener datos parseados. |
| Errores API | `ApiExceptionHandler.java` | Convierte excepciones frecuentes en JSON `{message}` con HTTP 400. |
| OCR Java client | `OcrService.java` | Renderiza PDFs, preprocesa imagenes, llama a Flask/PaddleOCR y maneja health/reintentos. |
| Parser de tickets | `ReceiptParserService.java` | Extrae fecha, comercio, items, marcas, cantidades y precios desde texto OCR. |
| Catalogo marcas | `BrandCatalog.java`, `data/brands.json` | Busca, normaliza y recuerda marcas detectadas. |
| Mapeo comercios | `StoreNameMapper.java`, `store-mappings.json` | Convierte aliases OCR de comercios a nombres canonicos. |
| Memoria de correcciones | `CorrectionMemory.java`, `data/corrections.json` | Aprende y reutiliza correcciones de marca/categoria/descripcion por comercio. |
| Endpoint aprendizaje | `CorrectionController.java`, `CorrectionsRequest.java` | Recibe correcciones del frontend y las persiste. |
| DTO respuesta | `ExtractResponse.java` | JSON completo devuelto al frontend/API. |
| DTO item | `ReceiptItem.java` | Representa una fila de producto extraida. |
| Export delimitado | `DelimitedExporter.java` | Genera salida con `|`, TSV con header y TSV sin header. |
| Frontend HTML | `static/index.html` | UI de carga, resultados y textareas de salida. |
| Frontend JS | `static/app.js` | Maneja submit, fetch al backend, render y clipboard. |
| Frontend CSS | `static/styles.css` | Estilos responsive de la pagina. |
| OCR service | `ocr/service.py` | Flask + PaddleOCR; genera variantes de imagen, ejecuta OCR y responde texto. |
| OCR deps | `ocr/requirements.txt`, `ocr/Dockerfile` | Versiones Python y build del contenedor OCR. |
| Docker app | `Dockerfile`, `compose.yaml` | Build Java, runtime backend y orquestacion con servicio OCR. |
| Config app | `application.properties` | Multipart, endpoint OCR, health, idioma, timeouts y reintentos. |
| Tests parser | `ReceiptParserServiceTest.java` | Casos unitarios que documentan reglas y regresiones del parser. |
| Documentacion usuario | `README.md` | Uso con Docker, flujo recomendado, limites actuales y mejoras futuras. |
