# Diagnostico inicial: fusion de layout en OCR

Fecha: 2026-08-08

## Objetivo

Identificar en que etapa puede aparecer una fusion de layout como:

```text
GAL OREO LECHE 1L TREGAR 01/08 2000
```

El plan pide no agregar heuristicas del parser a ciegas. Este documento registra la evidencia disponible en el repositorio antes de implementar cambios de OCR/debug.

## Estado de reproduccion

No se pudo reproducir el ticket problematico con un archivo real porque el repositorio no contiene imagenes, PDFs ni `test-data` versionado.

Busqueda realizada:

- `**/*.{png,jpg,jpeg,pdf,webp}`: sin resultados.
- `test-data`: no existe.
- `GAL OREO`, `OREO`, `TREGAR`, `LECHE 1L`, `01/08 2000`: solo aparecen en `docs/MVP_FINDE_AJUSTADO_AL_PROYECTO.md`.

Estado de servicios:

- `docker compose ps`: sin servicios levantados.

Por lo tanto, esta primera pasada no puede afirmar con evidencia visual si la fusion nace en PaddleOCR, en el preprocesamiento o en el parser. Si se agrega el ticket real al corpus, el siguiente paso es procesarlo con modo debug y comparar detecciones crudas contra filas armadas.

## Ambiguedad defensiva y correccion manual

El resultado Java ahora conserva las filas dudosas con `items[].estado`:

- `CORRECT`: la fila no activa las defensas actuales;
- `AMBIGUOUS`: la linea contiene senales de posible fusion, como una fecha embebida, demasiados numeros, multiples precios, texto de total/header o longitud anormal.

Las lineas de precio sin descripcion y otros candidatos descartados aparecen en `warnings`. La UI muestra las filas ambiguas resaltadas y permite editar campos o eliminar filas. Las exportaciones se regeneran en el frontend despues de cada correccion para que copiar TSV/CSV use el estado actualizado.

La ambiguedad basada en confidence o bounding boxes queda pendiente: esos datos existen en `OcrResult`, pero todavia no se pasan al parser de recibos.

## Regresion PedidosYa

Se agrego una transcripcion OCR del caso `PodidosYa Market - San Miguol II` en `test-data/receipts/pedidosya/`. El parser ahora reconoce el encabezado aunque `PedidosYa` tenga errores OCR, ignora textos de interfaz como `Tu pedido`, `Tu pago` y descuentos, y recupera productos cuyo precio aparece en la linea siguiente.

## Tests actuales

Comando ejecutado:

```bash
mvn test
```

Resultado:

```text
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Los tests cubren principalmente `ReceiptParserService`: tickets comunes, OCR con descripcion/precio separados, ticket largo, Tienda Filipa y PedidosYa Market.

## Flujo actual observado

### 1. Java recibe archivo y preprocesa

`ReceiptController.extract` hace:

```java
String text = ocrService.extractText(file);
return parserService.parse(text);
```

`OcrService.extractText`:

- detecta PDF solo por extension `.pdf`;
- renderiza PDF a 300 DPI si aplica;
- convierte imagen a gris;
- aplica contraste con `RescaleOp(1.28f, 14f)`;
- agrega padding blanco;
- serializa PNG;
- llama a `/ocr`.

Evidencia: `src/main/java/com/opencode/facturas/service/OcrService.java`.

### 2. Python genera variantes y ejecuta PaddleOCR

`ocr/service.py` genera variantes por rotacion:

- `cropped-*`;
- `gray-*`;
- `enhanced-*`;
- `threshold-*`;
- `denoised-*`;
- `light-threshold-*`;
- `small-*`;
- `small-gray-*`.

Todavia no existe variante `without_lines`.

Evidencia: `build_variants` en `ocr/service.py`.

### 3. PaddleOCR devuelve boxes/confidence, pero el servicio los reduce

`extract_lines` recibe cada entrada de PaddleOCR con:

- `box = entry[0]`;
- `data = entry[1]`;
- `text = data[0]`;
- `score = data[1]`.

En ese punto existen bounding box y confidence.

Luego solo se conserva metadata parcial:

```python
boxes.append({"text": text, "score": score, "top": top, "left": left, "height": height})
```

Se pierde:

- box completo de 4 puntos;
- `right`/ancho;
- relacion contra ancho de imagen;
- deteccion original separada una vez mergeada.

Evidencia: `extract_lines` en `ocr/service.py`.

### 4. `service.py` fusiona detecciones por filas

`merge_boxes_into_rows` agrupa detecciones si sus centros verticales estan cerca:

```python
threshold = max(18, min(row["height"], box["height"]) * 0.8)
if abs(center - row["center"]) <= threshold:
    matching_row = row
```

Despues concatena textos de izquierda a derecha:

```python
text = " ".join(item["text"] for item in row_boxes).strip()
```

Esto significa que, aun si PaddleOCR hubiese detectado `GAL OREO` y `LECHE 1L TREGAR 01/08 2000` como boxes separados en la misma altura visual, el servicio puede devolverlos como una sola linea logica.

No se puede confirmar que este sea el bug sin detecciones reales, pero es el primer punto concreto donde una fusion puede introducirse dentro del codigo propio.

### 5. `score_lines` puede favorecer filas fusionadas

`score_lines` premia:

- cantidad de lineas;
- longitud total de texto;
- tokens esperados;
- existencia de digitos;
- lineas con precio;
- lineas que parecen item.

No penaliza actualmente:

- lineas mucho mas largas que la mediana;
- fechas embebidas en descripcion de producto;
- boxes exageradamente anchos;
- baja confianza;
- demasiados tokens numericos.

Una linea fusionada con producto + fecha + precio puede recibir buen score si contiene texto util y numeros.

Evidencia: `score_lines` y `looks_like_item_line` en `ocr/service.py`.

### 6. `/ocr` devuelve lines y variant, pero no detecciones crudas

Respuesta actual:

```json
{"text": "...", "lines": lines, "variant": "..."}
```

`lines` contiene filas ya mergeadas, no detecciones PaddleOCR originales. Aunque cada fila tiene `score`, `top` y `left`, no tiene box completo ni confidence por deteccion despues del merge.

Evidencia: `ocr_endpoint` en `ocr/service.py`.

### 7. Java descarta todo salvo `text`

`OcrService.runPaddle` parsea el JSON y retorna solo:

```java
String text = root.path("text").asText();
return text;
```

Se pierde en Java:

- `variant`;
- `lines`;
- `score`/confidence parcial;
- cualquier metadata futura si no se cambia el contrato interno.

Evidencia: `runPaddle` en `OcrService.java`.

### 8. Parser asume texto plano por lineas

`ReceiptParserService.parse` recibe `String rawText`, hace `rawText.lines()` y parsea cada linea como unidad logica.

Si la fusion ya llego como una linea de texto, el parser no tiene geometria ni confianza para decidir si esa fila mezcla bloques separados.

Evidencia: `ReceiptParserService.parse`.

## Respuestas pedidas por la fase de diagnostico

### ¿Fusion en PaddleOCR?

No confirmado. El codigo recibe boxes y confidence desde PaddleOCR, pero no hay ticket real ni artefactos para inspeccionar si PaddleOCR produce una caja gigante o detecciones separadas.

### ¿Fusion en `service.py`?

Posible y probable como punto de riesgo. `merge_boxes_into_rows` concatena todas las detecciones alineadas verticalmente y descarta la geometria completa. Es el primer punto de fusion comprobable en codigo propio.

### ¿Fusion en Java?

Java no fusiona lineas por si mismo, pero descarta metadata estructurada. Desde `OcrService.runPaddle` en adelante solo queda `text`, asi que Java impide diagnosticar o corregir por geometria despues.

### ¿Fusion en parser?

El parser puede aceptar una linea ya fusionada como producto si contiene letras y un precio parseable. No tiene datos de OCR para marcar ambiguedad por ancho, gaps o confidence. No es el primer sospechoso sin evidencia, pero hoy tampoco tiene defensa explicita para una fecha embebida en descripcion de producto.

## Cambio minimo recomendado antes de heuristicas

Antes de cambiar reglas del parser, implementar observabilidad en `ocr/service.py` bajo flag de debug:

- guardar imagen recibida por Python;
- guardar variantes generadas;
- conservar detecciones PaddleOCR crudas con `text`, `confidence` y `box`;
- guardar las filas mergeadas por `merge_boxes_into_rows`;
- guardar variante elegida y score;
- generar overlay visual con boxes.

Esto permite responder con evidencia si `GAL OREO` y `LECHE 1L` nacen como una caja de PaddleOCR o se unen en `merge_boxes_into_rows`.

## Pendiente para reproduccion real

Agregar al menos una imagen/PDF anonimizado del ticket problematico en un corpus local, por ejemplo:

```text
test-data/receipts/lines/
```

Con ese archivo, ejecutar:

```bash
docker compose up --build -d paddleocr
mvn spring-boot:run
```

y procesarlo desde la UI o endpoint `/api/receipts/extract` con debug OCR habilitado.

## Reproduccion real con ticket problematico

Archivo usado:

```text
test-data/receipts/lines/ticket-linea-fisica.jpg
```

Comando de reproduccion por el flujo completo Java -> OCR -> parser:

```bash
curl.exe -s -S -F "file=@test-data\receipts\lines\ticket-linea-fisica.jpg" "http://localhost:8080/api/receipts/extract"
```

Artefactos generados:

- Antes del ajuste: `debug/run-20260808-130559-408163/`.
- Despues del ajuste: `debug/run-20260808-130801-834936/`.
- Respuesta parser antes: `debug/extract-response-current.json`.
- Respuesta parser despues: `debug/extract-response-after-merge-threshold.json`.

Estos artefactos estan ignorados por git porque pueden contener datos reales del ticket.

## Evidencia encontrada

La fusion no nacio como una unica caja gigante de PaddleOCR. En `detections.json`, PaddleOCR devolvio detecciones separadas para descripcion y precio, por ejemplo:

```text
LA UNICA BOLEA*1OML
2000,00
ELEGANTE PANLE*100UN
1100,00
TREGAR LECHE. ENT*1LT
1950,00
POETT FRESIJFA*90OML
1500,00
```

El problema aparecia al agrupar esas detecciones en filas logicas. Antes del ajuste, `merge_boxes_into_rows` produjo lineas como:

```text
ELEGANTE PANLE*100UN LA UNICA BOLEA*1OML 2000,00 1100,00
POETT FRESIJFA*90OML TREGAR LECHE. ENT*1LT 1950,00 1500,00
PARNOR GAL.E1I*120GR LA PROVIDEIA*505GR 2100,00 1100,00
PATY SALCHCFASBUN FRUTIGRANAL.L*260GR 1900,00 2400,00
ALMACEN EL SOL RAV.ES *1KG 4000,00 2500,00
```

El parser recibia ya esas lineas contaminadas como `rawText`, por lo que generaba solo 6 items y varios mezclados.

## Cambio aplicado

Archivo modificado:

```text
ocr/service.py
```

Cambio minimo:

```python
threshold = max(18, min(row["height"], box["height"]) * 0.45)
```

Antes usaba `0.8`. Ese umbral era demasiado permisivo para tickets inclinados con renglones cercanos: dos filas distintas podian tener centros verticales lo bastante cerca como para fusionarse.

No se tocaron heuristicas del parser para este problema. La correccion se hizo en la etapa donde la evidencia mostraba que nacian las fusiones.

## Resultado despues del ajuste

Despues del ajuste, el OCR devuelve lineas separadas:

```text
LA UNICA BOLEA*1OML 2000,00
ELEGANTE PANLE*100UN 1100,00
TREGAR LECHE. ENT*1LT 1950,00
POETT FRESIJFA*90OML 1500,00
PARNOR GAL.E1I*120GR 1100,00
LA PROVIDEIA*505GR 2100,00
FRUTIGRANAL.L*260GR 1900,00
PATY SALCHCFASBUN 2400,00
EL SOL RAV.ES *1KG 4000,00
ALMACEN 2500,00
```

Resultado parser:

- Antes: 6 items, varios mezclados.
- Despues: 11 items, sin las fusiones principales entre productos consecutivos.

Todavia quedan errores propios de OCR/normalizacion, por ejemplo `BOLEA` por `BOLSA`, `FRESIJFA` por `FRESCURA` y `LA PROVIDEIA` por `LA PROVIDENCIA`. Eso pertenece a fases posteriores: line removal, scoring, reglas defensivas/ambiguedad y UI editable.

## Fase 2: variante sin lineas fisicas

Se agrego una variante OCR `without-lines-*` para cada rotacion. La variante detecta lineas fisicas largas con OpenCV, genera una mascara de debug y limpia esas zonas antes de competir en el scoring.

Archivos modificados:

- `ocr/preprocess.py`: deteccion de lineas horizontales/verticales por morfologia y limpieza con inpaint.
- `ocr/service.py`: agrega `without-lines-original`, `without-lines-rot90`, `without-lines-rot270` y `without-lines-rot180` en `build_variants`.
- `ocr/requirements.txt`: fija `opencv-python==4.10.0.84` y `opencv-contrib-python==4.10.0.84` para evitar que `paddleocr` resuelva OpenCV 5/4.13.
- `ocr/Dockerfile`: copia `preprocess.py` y aumenta timeout/retries de pip por wheels grandes.
- `ocr/tests/test_preprocessing.py`: tests sinteticos de eliminacion/preservacion de trazos.

Nota de dependencias: inicialmente se evaluo `opencv-python-headless`, pero `paddleocr==2.8.1` ya depende de `opencv-python` y `opencv-contrib-python`. Se fijaron esas dependencias directas para evitar instalar paquetes `cv2` duplicados.

### Resultado A/B con el ticket real

Artefacto nuevo:

- `debug/run-20260808-134143-336737/`.

Resumen relevante de `variants-summary.json`:

| Variante | Score | Lineas | Detecciones |
| --- | ---: | ---: | ---: |
| `gray-rot270` | 531.13 | 26 | 42 |
| `without-lines-rot270` | 531.13 | 26 | 42 |
| `enhanced-rot270` | 553.37 | 26 | 42 |

La variante seleccionada siguio siendo `enhanced-rot270`. El endpoint completo mantuvo 11 items y no reaparecieron fusiones entre productos consecutivos.

Mascaras de lineas detectadas en esta imagen:

```text
without-lines-original-mask.png 0 px
without-lines-rot90-mask.png 0 px
without-lines-rot270-mask.png 0 px
without-lines-rot180-mask.png 0 px
```

Conclusion de Fase 2 para este ticket: la linea fisica no era la causa. La nueva variante queda disponible y probada para tickets futuros con separadores/rejillas reales.

## Fase 5: OCR estructurado en Java

El endpoint Python ya devolvia `text`, `lines`, `detections`, `variant` y `score`, pero Java descartaba todo salvo `text`. Se completo el lado Java para conservar esa metadata sin cambiar todavia el parser.

Archivos modificados:

- `src/main/java/com/opencode/facturas/model/OcrDetection.java`: texto, confidence y bounding box de deteccion.
- `src/main/java/com/opencode/facturas/model/OcrLine.java`: linea OCR mergeada con confidence/score y geometria basica.
- `src/main/java/com/opencode/facturas/model/OcrResult.java`: resultado OCR estructurado.
- `src/main/java/com/opencode/facturas/service/OcrService.java`: `extract(...)` devuelve `OcrResult`; `extractText(...)` queda como compatibilidad.
- `src/main/java/com/opencode/facturas/controller/ReceiptController.java`: usa `ocrService.extract(file)` y sigue parseando `ocrResult.text()`.
- `src/main/java/com/opencode/facturas/model/ExtractResponse.java`: agrega `variant` y `score`.
- `src/test/java/com/opencode/facturas/service/OcrServiceTest.java`: regresion de parsing del JSON OCR estructurado.

Resultado con el ticket real:

```json
{
  "itemCount": 11,
  "variant": "enhanced-rot270",
  "score": 553.3666666666667
}
```

Conclusion de Fase 5: Java ya recibe y conserva la metadata OCR necesaria para la siguiente fase de ambiguedad defensiva. El parser sigue intacto y no se altero el resultado funcional del ticket.

## Verificaciones

Comandos ejecutados:

```bash
python -m py_compile ocr\service.py
python -m py_compile ocr\service.py ocr\preprocess.py
docker compose exec -T paddleocr python -m unittest discover -s tests
docker compose exec -T paddleocr python -m py_compile service.py preprocess.py
mvn test
```

Resultados:

```text
Python syntax: OK
Python unittest: Ran 5 tests, OK
Maven: Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
```
