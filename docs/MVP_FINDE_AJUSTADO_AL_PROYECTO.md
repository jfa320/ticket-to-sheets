# MVP OCR de tickets — Plan del fin de semana ajustado al proyecto actual

## 0. Objetivo

Este documento define qué implementar este fin de semana **sobre el proyecto que ya existe**, sin reescribirlo ni agregar infraestructura innecesaria.

El sistema actual ya tiene un pipeline local funcional:

```text
UI web estática
    ↓
Spring Boot
    ↓
OcrService
    ↓
Flask + PaddleOCR
    ↓
ReceiptParserService
    ↓
ExtractResponse
    ↓
UI + TSV para copiar a Google Sheets
```

El problema principal ya no es “hacer funcionar OCR”, sino **hacerlo robusto frente a tickets reales**. El caso que motiva este sprint es una lectura donde una línea/separador del ticket provocó una salida mezclada:

```text
GAL OREO LECHE 1L TREGAR 01/08 2000
```

La prioridad es determinar **en qué etapa aparece esa fusión**, mejorar el preprocesamiento existente, conservar geometría/confianza del OCR, marcar resultados dudosos y permitir corregirlos rápidamente desde la UI.

No se incorpora LLM en esta etapa.

---

## 1. Contexto técnico real

### Backend

- Java 17.
- Spring Boot 3.3.5.
- Maven.
- Spring Web MVC.
- Spring Validation.
- PDFBox 3.0.3.
- Jackson vía Spring Boot.

Estructura relevante:

```text
src/main/java/com/opencode/facturas/
├── controller/
│   ├── ReceiptController.java
│   └── ApiExceptionHandler.java
├── model/
│   ├── ExtractResponse.java
│   └── ReceiptItem.java
├── service/
│   ├── OcrService.java
│   ├── ReceiptParserService.java
│   ├── BrandCatalog.java
│   └── StoreNameMapper.java
└── util/
    └── DelimitedExporter.java
```

### Frontend

Ya existe una UI estática:

```text
src/main/resources/static/
├── index.html
├── app.js
└── styles.css
```

No hay npm, Angular, React ni bundler.

La UI actual:
- sube imagen/PDF;
- llama a `POST /api/receipts/extract`;
- muestra comercio, fecha e ítems;
- muestra OCR crudo;
- permite copiar salidas delimitadas.

**No agregar Thymeleaf, Angular ni React.**

### OCR

Existe un microservicio propio:

```text
ocr/service.py
```

Stack:
- Python 3.11.
- Flask 3.0.3.
- PaddleOCR 2.8.1.
- PaddlePaddle 2.6.2.
- Pillow.
- NumPy.

Endpoints:

```text
POST /ocr
GET  /health
```

El OCR ya:
- recibe PNG;
- genera variantes de imagen;
- hace crop;
- rotaciones;
- grises;
- contraste;
- threshold;
- denoise;
- escalado;
- ejecuta PaddleOCR;
- hace scoring heurístico;
- elige la variante que considera mejor.

Por eso **no corresponde crear otro ImagePreprocessor en Java**. El trabajo debe concentrarse primero en `ocr/service.py`.

### Parser

`ReceiptParserService` ya:
- detecta fecha;
- detecta comercio;
- parsea ítems;
- interpreta cantidades/precios;
- detecta marcas;
- normaliza descripciones;
- contempla PedidosYa Market con un camino especial.

Es una clase grande y sensible.

**No hacer un refactor masivo este fin de semana.**

### Google Sheets

Hoy no existe Google Sheets API.

La integración actual es:

```text
procesar
→ obtener TSV
→ copiar
→ pegar en Google Sheets
```

Para un MVP personal usado por dos personas, eso es suficiente.

La integración automática con Sheets queda como stretch goal.

---

## 2. Objetivo funcional del domingo

La experiencia final debe ser:

```text
1. Elegir una imagen o PDF.
2. Procesarla.
3. Ver los productos detectados.
4. Ver cuáles son dudosos.
5. Corregir filas desde la UI.
6. Eliminar o agregar filas.
7. Copiar un TSV regenerado con las correcciones.
8. Pegar en Google Sheets.
```

Criterio principal:

> Corregir un ticket procesado debe ser claramente más rápido que cargarlo completamente a mano.

---

## 3. Problema técnico prioritario: fusión de layout

El error:

```text
GAL OREO LECHE 1L TREGAR 01/08 2000
```

puede nacer en distintos puntos:

1. La imagen/preprocesamiento genera una región conectada.
2. PaddleOCR detecta un bounding box demasiado grande.
3. `ocr/service.py` concatena detecciones separadas.
4. `OcrService` descarta geometría y conserva solo texto.
5. `ReceiptParserService` asume que una línea OCR equivale a una línea lógica.

La primera tarea no es “agregar regex”.

La primera tarea es responder:

> ¿En qué etapa exacta aparece la fusión?

---

## 4. Regla de trabajo para el agente

Antes de tocar heurísticas del parser, reproducir el ticket problemático y capturar:

- imagen original;
- imagen enviada efectivamente a PaddleOCR;
- variantes generadas;
- variante elegida;
- detecciones PaddleOCR originales;
- bounding boxes;
- confidence;
- JSON devuelto por `/ocr`;
- texto recibido por Java;
- texto recibido por `ReceiptParserService`;
- `ReceiptItem` final.

No corregir a ciegas.

---

## 5. Alcance obligatorio

### OCR / imagen

- reproducir el bug;
- agregar modo debug;
- conservar bounding boxes;
- conservar confidence;
- guardar variantes;
- generar overlay visual de detecciones;
- agregar variante con eliminación de líneas físicas;
- revisar scoring de variantes.

### Java

- modelar respuesta OCR estructurada;
- dejar de depender únicamente del `String` crudo;
- conservar `rawText`;
- mantener compatibilidad con flujo actual;
- agregar señales de ambigüedad.

### Parser

- sumar reglas defensivas;
- agregar tests de regresión;
- no romper casos existentes;
- no reescribir arquitectura.

### Frontend

- tabla editable;
- corregir campos;
- eliminar fila;
- agregar fila;
- marcar filas ambiguas;
- regenerar TSV desde el estado editado.

### Pruebas

- corpus pequeño de tickets;
- ticket con línea física;
- ticket limpio;
- ticket con sombra;
- ticket inclinado;
- ticket largo;
- PedidosYa.

---

## 6. Fuera de alcance

No implementar:

- LLM;
- OpenAI;
- Gemini;
- categorización semántica;
- catálogo global de productos;
- autenticación;
- multiusuario;
- Telegram;
- WhatsApp;
- app móvil;
- nube;
- Supabase;
- PostgreSQL;
- JPA;
- dashboard;
- históricos;
- comparación de precios;
- programación lineal;
- scraping;
- API pública;
- microservicios nuevos.

Tampoco migrar el frontend a otro framework.

---

## 7. Arquitectura objetivo

Se mantiene la arquitectura actual:

```text
static/index.html + app.js
             ↓
POST /api/receipts/extract
             ↓
ReceiptController
             ↓
OcrService
             ↓
POST /ocr
             ↓
ocr/service.py
             ↓
PaddleOCR
             ↓
OCR estructurado
             ↓
ReceiptParserService
             ↓
ExtractResponse
             ↓
UI editable
             ↓
TSV corregido
```

El cambio conceptual es:

```text
ANTES

OCR
 ↓
texto
 ↓
parser
```

```text
DESPUÉS

OCR
 ↓
texto + detecciones + bounding boxes + confidence + variante
 ↓
parser + validación
 ↓
ítems + warnings
```

---

# 8. Fase 1 — Observabilidad del OCR

Esta es la prioridad absoluta.

## 8.1. Modo debug

Agregar un flag equivalente a:

```text
APP_OCR_DEBUG=true
```

Cuando esté habilitado, guardar artefactos por ejecución:

```text
debug/
└── run-20260808-001/
    ├── 00-original.png
    ├── 01-java-input.png
    ├── python/
    │   ├── original.png
    │   ├── gray.png
    │   ├── contrast.png
    │   ├── threshold.png
    │   ├── denoise.png
    │   └── without-lines.png
    ├── selected.png
    ├── detections.json
    ├── detections-overlay.png
    ├── ocr-response.json
    ├── parser-input.txt
    └── extract-response.json
```

No es necesario respetar exactamente estos nombres; sí conservar el concepto.

## 8.2. Detección OCR estructurada

Cada detección debe poder inspeccionarse así:

```json
{
  "text": "GAL OREO",
  "confidence": 0.94,
  "box": [
    [40, 120],
    [210, 120],
    [210, 145],
    [40, 145]
  ]
}
```

## 8.3. Overlay

Generar una imagen con:
- bounding boxes;
- índice de detección;
- confidence;
- texto abreviado.

Objetivo:

saber si `GAL OREO` y `LECHE 1L` nacieron como dos detecciones o como una sola caja gigante.

---

# 9. Fase 2 — Eliminación de líneas físicas

El OCR Python ya hace preprocesamiento. Agregar una nueva variante allí.

Nombre sugerido:

```text
without_lines
```

## 9.1. OpenCV

Es razonable agregar:

```text
opencv-python-headless
```

a:

```text
ocr/requirements.txt
```

No usar `opencv-python` con dependencias GUI.

## 9.2. Estrategia

1. convertir a gris;
2. binarizar;
3. detectar líneas horizontales largas;
4. detectar líneas verticales largas;
5. combinar máscaras;
6. eliminar/inpaintar;
7. devolver variante;
8. ejecutar PaddleOCR normalmente sobre ella.

Los kernels deben ser proporcionales a la resolución.

Ejemplo conceptual:

```python
horizontal_size = max(30, width // 20)
vertical_size = max(30, height // 20)
```

No depender únicamente de `40 px`.

## 9.3. Precauciones

Una línea vertical puede confundirse con:

```text
1
I
l
```

Una horizontal puede destruir:

```text
-
_
=
```

Por lo tanto:
- detectar solo estructuras largas;
- conservar siempre variantes sin eliminación;
- no obligar a usar `without_lines`;
- dejar que compita en scoring;
- guardar la máscara en modo debug.

---

# 10. Fase 3 — Revisar `score_lines`

El OCR ya elige variantes con heurísticas.

Un problema posible es que una línea mezclada tenga muchos números/palabras útiles y reciba score alto.

Agregar penalizaciones.

Posibles señales negativas:

- línea mucho más larga que la mediana;
- demasiados tokens numéricos;
- fecha embebida en una línea tipo producto;
- `TOTAL`, `CUIT`, `TICKET`, etc. dentro de línea de producto;
- bounding box exageradamente ancho;
- baja confianza;
- múltiples gaps visuales;
- relación `box_width / image_width` anormal.

No hacer reglas absolutas si no hay evidencia.

Usar estas señales principalmente para ranking de variantes.

---

# 11. Fase 4 — Extender `/ocr` sin romper compatibilidad

Hoy el servicio devuelve texto/lines/variant.

Mantener esos campos y agregar metadata.

Formato sugerido:

```json
{
  "text": "...",
  "lines": [
    "GAL OREO",
    "LECHE 1L"
  ],
  "detections": [
    {
      "text": "GAL OREO",
      "confidence": 0.94,
      "box": [[40,120],[210,120],[210,145],[40,145]]
    }
  ],
  "variant": "without_lines",
  "score": 18.4
}
```

Preferir agregar `detections` en vez de cambiar `lines` de tipo y romper consumidores.

---

# 12. Fase 5 — Ajustar `OcrService`

Actualmente `OcrService`:
- recibe multipart;
- detecta PDF por extensión;
- renderiza PDF a 300 DPI;
- hace preprocesamiento básico;
- envía PNG;
- espera `/health`;
- llama `/ocr`.

## 12.1. Revisar duplicación de preprocesamiento

Java y Python preprocesan.

Antes de cambiar, comparar.

Dirección deseada:

```text
Java:
- PDF → imagen
- normalización mínima
- serialización

Python:
- variantes OCR
- contraste
- threshold
- denoise
- rotación
- line removal
- scoring
```

Si el preprocesamiento Java ayuda, mantenerlo.

Si destruye información antes de que Python pueda crear buenas variantes, simplificarlo.

Solo hacerlo con prueba A/B.

## 12.2. DTOs OCR

Modelo conceptual:

```java
public record OcrDetection(
        String text,
        double confidence,
        List<List<Double>> box
) {}
```

```java
public record OcrResult(
        String text,
        List<String> lines,
        List<OcrDetection> detections,
        String variant,
        Double score
) {}
```

Adaptar coordenadas al JSON real.

## 12.3. Cambiar contrato interno

Evolucionar de:

```java
String text = ocrService.extractText(file);
receiptParserService.parse(text);
```

a algo como:

```java
OcrResult ocr = ocrService.extract(file);
receiptParserService.parse(ocr);
```

El parser puede seguir usando `ocr.text()` inicialmente, pero ya no se pierde la geometría.

---

# 13. Fase 6 — Ambigüedad defensiva

No hace falta un modelo probabilístico.

El objetivo es evitar que una fila corrupta aparezca como válida.

Estados mínimos:

```text
CORRECT
AMBIGUOUS
```

Una fila debe ser candidata a `AMBIGUOUS` cuando:
- contiene fecha dentro de descripción;
- contiene `TOTAL`;
- contiene texto de header;
- tiene longitud anormal;
- tiene demasiados números;
- su precio no parsea;
- tiene confidence bajo;
- su bounding box es demasiado ancho;
- parece contener más de un producto;
- mezcla bloques separados.

Ejemplo:

```text
GAL OREO LECHE 1L TREGAR 01/08 2000
```

Debe llegar a UI con advertencia.

Regla:

> Mejor pedir una corrección manual que guardar silenciosamente una fila corrupta.

---

# 14. Fase 7 — `ReceiptParserService`: cambios mínimos

El parser actual ya tiene bastante lógica y tests.

No convertirlo en una nueva arquitectura este fin de semana.

Refactors permitidos si facilitan testear:

```text
MoneyParser
DateParser
LineClassifier
```

pero solo si:
- reducen código repetido;
- hacen una regla testeable;
- no fuerzan reescritura.

Evitar:
- strategy por supermercado;
- factories;
- pipelines genéricos;
- decenas de services;
- refactor completo.

---

# 15. Inconsistencias existentes relevantes

## 15.1. `precioTotal`

El MVP no calcula ni exporta `precioTotal`. Se retira del DTO y de la UI para mantener una salida de siete columnas verificadas.

Revisar si puede completarse con información ya disponible.

Salida del MVP:

```text
Descripcion
Marca
Lugar de compra
Categoria
Cantidad
Precio unitario
Fecha
```

El precio total queda fuera de alcance hasta contar con una regla confiable y una necesidad explícita.

## 15.2. Categoría

Actualmente:

```text
Categoria = Supermercado
```

Mantenerlo.

No intentar todavía:

```text
Galletitas
Lácteos
Limpieza
```

Eso pertenece a una etapa posterior.

---

# 16. Fase 8 — UI editable usando el frontend actual

Mantener:

```text
index.html
app.js
styles.css
```

Agregar una tabla editable.

Columnas sugeridas:

| Usar | Descripción | Marca | Comercio | Categoría | Cantidad | Unitario | Fecha | Estado |
|---|---|---|---|---|---:|---:|---|---|

Funciones mínimas:
- editar descripción;
- editar marca;
- editar cantidad;
- editar precio unitario;
- precio total queda fuera del MVP;
- editar fecha;
- eliminar/desactivar fila;
- no agregar filas manualmente;
- duplicar fila opcionalmente;
- marcar visualmente ambiguos.

No construir editor geométrico.

Para separar:

```text
GAL OREO LECHE 1L
```

es suficiente con:
1. eliminar la fila mala;
2. corregir una fila detectada;
3. si faltan productos, agregarlos manualmente queda fuera del MVP.

---

# 17. Fase 9 — TSV regenerado desde frontend

Actualmente backend genera TSV.

Una vez que la tabla es editable, ese TSV queda obsoleto después de una corrección.

Nuevo flujo:

```text
ExtractResponse
    ↓
modelo editable en app.js
    ↓
usuario corrige
    ↓
app.js regenera TSV
    ↓
Copiar
```

Mantener `DelimitedExporter` en backend por compatibilidad/tests.

Pero el botón final de copia debe usar el estado actualizado de la UI.

---

# 18. Google Sheets API: stretch goal

No bloquear el MVP por Sheets API.

Para este fin de semana:

```text
procesar
→ corregir
→ copiar TSV
→ pegar
```

es suficiente.

Agregar Sheets API implicaría:
- OAuth;
- credenciales;
- permisos;
- refresh tokens;
- spreadsheet ID;
- idempotencia;
- errores externos.

No resuelve el problema actual de OCR.

Solo abordar si todo lo demás está cerrado.

---

# 19. Corpus de tickets

Crear un corpus pequeño:

```text
test-data/receipts/
├── clean/
├── lines/
├── rotated/
├── shadows/
├── long/
└── pedidosya/
```

Casos mínimos:

### A — Limpio
Ticket que hoy funciona.

Objetivo: no regresión.

### B — Línea física
El ticket que originó el problema.

Objetivo: mejora observable.

### C — Sombra
Objetivo: evaluar threshold/contrast.

### D — Inclinado
Objetivo: rotación/variante.

### E — Largo
Muchos productos.

Objetivo: estabilidad.

### F — PedidosYa
Objetivo: no romper parser especial.

No versionar información sensible sin anonimizar.

---

# 20. Ground truth mínimo

Ejemplo por ticket:

```json
{
  "store": "Los Tres Corazones",
  "date": "1/8/2026",
  "minimumItemCount": 12,
  "knownItems": [
    {
      "descriptionContains": "OREO",
      "price": "2000,00"
    }
  ]
}
```

No intentar construir aún datasets perfectos.

---

# 21. Tests Java

Ya existe:

```text
ReceiptParserServiceTest
```

Agregar regresiones.

Casos prioritarios:

### Línea contaminada

```text
GAL OREO LECHE 1L TREGAR 01/08 2000
```

Esperado:
- no aceptarla silenciosamente como producto correcto.

### Valores monetarios

```text
2.000
2.000,00
2000,00
20.450,95
```

### Casos existentes

Todos deben seguir pasando:

```bash
mvn test
```

Especial atención a:
- Tienda Filipa;
- Los Tres Corazones;
- queso Punta del Agua;
- PedidosYa Market;
- tickets largos;
- fechas.

---

# 22. Tests Python

Actualmente no hay pytest.

Si se incorpora OpenCV y helpers de líneas, agregar tests mínimos:

```text
ocr/tests/test_preprocessing.py
ocr/tests/test_scoring.py
```

Probar:
- detección horizontal;
- detección vertical;
- creación de máscara;
- preservación razonable de texto;
- `without_lines`;
- penalización de línea fusionada.

Si pytest complica demasiado el cierre, al menos dejar fixtures y un script reproducible.

---

# 23. Comparación A/B

Por cada ticket registrar:

| Ticket | Versión | Variante | Ítems detectados | Fusionados | Perdidos | Correcciones |
|---|---|---|---:|---:|---:|---:|

No usar “cantidad de texto OCR” como métrica principal.

Más texto puede significar peor estructura.

Métrica principal:

```text
correcciones manuales necesarias por ticket
```

Secundarias:
- ítems correctos / reales;
- fusiones;
- pérdidas;
- precios incorrectos;
- tiempo OCR;
- tiempo de revisión.

---

# 24. Plan por sesiones

## Primera sesión — Diagnóstico

Meta: saber dónde nace la fusión.

Tareas:
1. levantar el proyecto;
2. procesar el ticket problemático;
3. ejecutar `mvn test`;
4. capturar salida actual;
5. inspeccionar `service.py`;
6. inspeccionar detecciones PaddleOCR;
7. documentar el flujo exacto.

Entregable:

```text
docs/ocr-layout-problem.md
```

Debe contestar:

```text
¿Fusión en PaddleOCR?
¿Fusión en service.py?
¿Fusión en Java?
¿Fusión en parser?
```

---

## Sábado mañana — Debug OCR

Tareas:
- flag debug;
- guardar variantes;
- guardar detecciones;
- overlay de boxes;
- response JSON reproducible.

DONE:

> Se puede abrir una carpeta y entender visualmente qué hizo el OCR.

---

## Sábado tarde — Line removal

Tareas:
- agregar OpenCV headless si hace falta;
- detectar horizontal;
- detectar vertical;
- producir `without_lines`;
- guardar máscara;
- integrar al scoring;
- probar corpus.

DONE:

> El ticket de línea mejora, o se demuestra con evidencia que la línea no era la causa.

---

## Sábado noche — OCR estructurado

Tareas:
- ampliar `/ocr`;
- devolver `detections`;
- crear DTO Java;
- mantener `text`;
- mantener `variant`;
- no romper UI.

DONE:

> Java recibe texto + boxes + confidence + variante.

---

## Domingo mañana — Parser defensivo

Tareas:
- reglas de ambigüedad;
- tests;
- resolver `precioTotal` dejándolo fuera del MVP;
- mantener marcas/comercios;
- no romper PedidosYa.

DONE:

> La línea corrupta no aparece como producto normal sin aviso.

---

## Domingo tarde — UI editable

Tareas:
- tabla editable;
- eliminar;
- agregar queda fuera de alcance;
- duplicar opcional;
- warnings;
- regenerar TSV;
- conservar raw OCR.

DONE:

> Se puede corregir un ticket sin tocar código.

---

## Domingo cierre — Regresión

Tareas:
- corpus completo;
- `mvn test`;
- tests Python;
- rebuild Docker;
- probar imagen;
- probar PDF;
- probar PedidosYa;
- medir al menos cinco tickets;
- actualizar README;
- documentar pendientes.

---

# 25. Prioridad si falta tiempo

Orden estricto:

1. reproducir bug;
2. debug visual;
3. bounding boxes/confidence;
4. eliminar líneas;
5. tests de regresión;
6. marcar ambiguos;
7. UI editable;
8. TSV desde frontend;
9. mejorar scoring;
10. dejar `precioTotal` fuera del MVP;
11. pytest formal;
12. Google Sheets API.

Si falta tiempo, **Sheets API es lo primero que se descarta**.

---

# 26. Archivos que probablemente se toquen

La lista definitiva se decide después de inspección.

### `ocr/service.py`
- debug;
- detecciones;
- line removal;
- scoring;
- metadata.

### `ocr/requirements.txt`
Posibles dependencias:

```text
opencv-python-headless
pytest
```

### `ocr/Dockerfile`
Solo si las dependencias lo requieren.

### `OcrService.java`
- consumir OCR estructurado;
- conservar metadata;
- revisar preprocesamiento duplicado.

### `ReceiptParserService.java`
- ambigüedad;
- cambios mínimos;
- no refactor masivo.

### `model/`
Posibles:

```text
OcrResult
OcrDetection
```

### `ExtractResponse.java`
Solo si se necesitan warnings/variant en UI.

### `ReceiptItem.java`
Estado actual:
- `precioTotal` fue retirado del DTO;
- `estado` forma parte de cada fila ambigua o correcta.

### `DelimitedExporter.java`
- mantener las siete columnas del MVP;
- no agregar `precioTotal` sin una decisión de producto posterior.

### `static/index.html`
- tabla editable.

### `static/app.js`
- estado editable;
- alta/baja;
- regeneración TSV.

### `static/styles.css`
- tabla;
- estados.

### `ReceiptParserServiceTest.java`
- regresiones.

---

# 27. Docker

Flujos actuales:

```bash
docker compose up --build -d
docker compose logs -f app paddleocr
```

o:

```bash
docker compose up --build -d paddleocr
mvn spring-boot:run
```

Si se agrega OpenCV:
- usar headless;
- comprobar build limpio;
- probar `/health`;
- no cambiar versiones de PaddleOCR/PaddlePaddle salvo necesidad real.

---

# 28. PDF

Ya existe render con PDFBox a 300 DPI.

No reimplementar.

Al final probar:
- imagen directa;
- PDF equivalente.

Mejora futura:
- detectar PDF por MIME/magic bytes y no solo extensión.

No es prioridad.

---

# 29. `BrandCatalog`

Ya persiste marcas en:

```text
data/brands.json
```

No convertirlo aún en catálogo global de productos.

Recordar deuda técnica:
- en Docker puede perder cambios si no se monta volumen.

No mezclar eso con el bug de layout.

---

# 30. `StoreNameMapper`

Mantener reglas existentes.

Probar regresiones para:
- Los Tres Corazones;
- Tienda Filipa;
- PedidosYa.

No refactorizarlo como parte del OCR.

---

# 31. Definición de DONE

## Funcional

- [x] La app levanta como antes.
- [x] Imagen funciona.
- [ ] PDF funciona.
- [x] El bug se reproduce.
- [x] Se sabe exactamente dónde nace la fusión.
- [x] Existe debug visual.
- [x] Se conservan boxes/confidence.
- [x] Existe variante de eliminación de líneas.
- [x] Los resultados dudosos se marcan.
- [x] La UI permite corregir.
- [x] Se pueden eliminar filas; agregar filas queda fuera del MVP.
- [x] El TSV final refleja correcciones.
- [x] Raw OCR sigue visible.

## Calidad

- [x] `mvn test` pasa.
- [x] Hay regresión para el bug.
- [x] PedidosYa sigue funcionando.
- [ ] Se probaron al menos cinco tickets.
- [x] Existe comparación antes/después.
- [x] README actualizado.
- [x] No se agregó LLM.
- [x] No se agregó DB.
- [x] No se agregó framework frontend.
- [x] No se reescribió el parser.

## Producto

- [ ] Fabi puede procesar un ticket sin tocar código (validación manual pendiente).
- [ ] Marianela puede corregirlo desde la UI (validación manual pendiente).
- [ ] Cargarlo resulta más rápido que hacerlo totalmente a mano (validación manual pendiente).

---

# 32. Prompt inicial para el agente de IA

```text
Tomá PROJECT_CONTEXT.md como fuente de verdad sobre el estado actual del repositorio.

No reconstruyas el sistema. Ya existe:

UI HTML/JS -> Spring Boot -> OcrService -> Flask/PaddleOCR -> ReceiptParserService -> ExtractResponse.

El problema concreto es robustez con tickets reales. Tengo un ticket con una línea/separador físico que terminó produciendo una fila mezclada similar a:

GAL OREO LECHE 1L TREGAR 01/08 2000

No quiero usar LLM todavía.

PRIMERA TAREA:
No agregues heurísticas nuevas a ciegas. Reproducí el error e identificá exactamente en qué etapa aparece:

1. preprocesamiento de imagen;
2. detección PaddleOCR;
3. armado de lines en ocr/service.py;
4. OcrService Java;
5. ReceiptParserService.

Agregá, preferentemente bajo un debug flag:
- guardado de variantes;
- detecciones originales;
- bounding boxes;
- confidence;
- variante elegida;
- overlay de bounding boxes.

Después de tener evidencia, aplicá el cambio mínimo.

Dirección técnica deseada:
1. mantener el preprocesamiento principal en ocr/service.py;
2. agregar una variante que elimine líneas horizontales/verticales, probablemente con opencv-python-headless;
3. mejorar score_lines si favorece falsamente líneas fusionadas;
4. ampliar /ocr para devolver detecciones estructuradas sin perder text/lines;
5. hacer que OcrService conserve metadata OCR;
6. agregar reglas defensivas para marcar resultados sospechosos;
7. no reescribir ReceiptParserService;
8. agregar tests de regresión;
9. transformar la UI estática actual en tabla editable;
10. generar el TSV final desde el estado corregido en frontend.

No agregues:
- LLM;
- OpenAI;
- Gemini;
- Angular;
- React;
- Thymeleaf;
- base de datos;
- JPA;
- Supabase;
- Telegram;
- autenticación;
- multiusuario;
- dashboard;
- programación lineal.

Google Sheets API tampoco es requisito para este MVP. Copiar TSV y pegar en Sheets es suficiente.

Antes de cada cambio importante indicá:
- problema observado;
- evidencia;
- archivo a modificar;
- solución mínima;
- prueba que evita regresión.

Conservá todos los casos actuales de ReceiptParserServiceTest y prestá especial atención al flujo de PedidosYa Market.
```

---

# 33. Prompt específico para OCR

```text
Analizá ocr/service.py.

El servicio ya genera múltiples variantes y usa score_lines para elegir una.

Quiero incorporar eliminación de líneas físicas SIN sustituir el pipeline existente.

Objetivos:
- conservar variantes actuales;
- detectar líneas horizontales y verticales;
- generar una variante without_lines;
- guardar máscara y variante en debug;
- ejecutar PaddleOCR normalmente;
- comparar score;
- evitar destruir caracteres.

Antes de implementar mostrame:
1. cómo se representan hoy los resultados de PaddleOCR;
2. dónde se pierden bounding box/confidence;
3. cómo score_lines evalúa una variante;
4. qué cambios mínimos harías.

Después agregá tests o fixtures reproducibles para el ticket con línea.
```

---

# 34. Prompt específico para frontend

```text
La UI actual es HTML/CSS/JS estático en src/main/resources/static.

No agregues framework.

Quiero convertir la visualización actual de items en una tabla editable manteniendo el resto de la interfaz.

Necesito:
- editar descripción;
- marca;
- cantidad;
- precio unitario;
- precio total queda fuera del MVP;
- fecha;
- eliminar fila;
- no agregar filas manualmente;
- destacar items ambiguos;
- mantener rawText;
- generar/copiar TSV desde el estado ACTUAL de la tabla, no desde el TSV original recibido del backend.

Debe seguir funcionando localmente, sin persistencia y sin autenticación.
```

---

# 35. Próxima etapa después de este MVP

No implementar ahora.

```text
MVP OCR confiable
    ↓
historial local
    ↓
catálogo de productos
    ↓
normalización
    ↓
LLM solo para desconocidos/ambiguos
    ↓
categorías reales
    ↓
Telegram
    ↓
multiusuario
    ↓
base agregada anónima
    ↓
histórico de precios
    ↓
comparación de comercios
    ↓
optimización de compras con PL
```

---

# 36. Resultado esperado

El domingo la experiencia ideal es:

```text
Fabi/Marianela saca foto
    ↓
sube ticket
    ↓
OCR procesa
    ↓
la mayoría de ítems aparece bien
    ↓
los dudosos se marcan
    ↓
se corrigen en segundos
    ↓
copiar TSV
    ↓
pegar en Google Sheets
```

Si esto funciona de manera razonable con varios tickets reales, el proyecto deja de ser solamente un experimento OCR y pasa a ser una **herramienta personal utilizable**.

Ese es el objetivo del fin de semana.
