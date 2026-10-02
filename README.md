# Extractor local de facturas con Spring Boot

App local para subir una foto o PDF de un ticket, correr OCR gratis con PaddleOCR en Docker y devolver filas listas para pegar en Google Sheets.

## Que hace

- extrae texto de imagen o PDF con PaddleOCR
- detecta fecha y lugar de compra; admite fechas sin etiqueta, con `/`, `-` o `.`, además de las etiquetadas
- arma filas con estas columnas: `Descripción|Marca|Lugar de compra|Categoria|Cantidad|Precio unitario|Fecha`
- usa las cajas OCR para interpretar columnas de descripcion, cantidad, precio unitario e importe cuando el ticket viene en formato tabular
- muestra un `Total calculado` debajo de las filas, sumando cantidad por precio unitario de los items extraidos (nunca el total del OCR)
- descarta lineas de subtotal, total y monto a pagar; usa `Genérico` cuando no reconoce la marca
- corrige marcas OCR con Levenshtein: menos de 30% usa `Genérico`, de 30% a 70% deja la marca dudosa para revisar y más de 70% aplica la marca automáticamente
- genera dos salidas:
  - formato con `|` para guardar o copiar
  - formato tabulado sin encabezado para pegar directo en Google Sheets
- muestra una tabla editable para corregir o eliminar filas antes de copiar
- marca filas ambiguas y muestra advertencias completas cuando una línea no pudo confirmarse
- aprende de tus correcciones (marca, categoría y descripción) y las aplica en el próximo ticket del mismo comercio
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

```bash
mvn test
python -m unittest discover -s ocr/tests
```

Los tests Java incluyen fixtures sintéticos en `src/test/resources/receipt-evaluation` y reportan aciertos exactos por campo. El banco cubre texto OCR multipágina, cantidad/precio separados, líneas de precio sueltas y detecciones geométricas, sin versionar tickets reales.

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
4. Usa `Copiar tabla para Google Sheets`; se copian solo las filas seleccionadas, sin encabezado.
5. Pega en Google Sheets.
6. Si algun item sale raro, revisa el bloque `Texto OCR crudo`.

## Aprendizaje de correcciones

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
- tickets muy borrosos o torcidos van a necesitar mejores reglas o preprocesado
- el primer build de Docker puede tardar porque descarga la imagen y los modelos de OCR
- para fotos de tickets, el OCR prueba varias versiones de la imagen y elige la mas util con un score estructural, sin favorecer marcas concretas
- la tabla permite agregar líneas manualmente, además de editar o eliminar las filas detectadas
- el precio total no forma parte de la salida del MVP
- el aprendizaje guarda solo marca, categoria y descripcion; no almacena precios ni datos de tarjetas
- `data/corrections.json` no se versiona en git; en Docker persiste por el volumen `./data:/app/data`

## Mejoras faciles para despues

- ampliar el banco de evaluación con más transcripciones sintéticas o anonimizadas
- permitir configurar categorías desde archivos locales
