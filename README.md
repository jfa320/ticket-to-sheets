# Extractor local de facturas con Spring Boot

App local para subir una foto o PDF de un ticket, correr OCR gratis con PaddleOCR en Docker y devolver filas listas para pegar en Google Sheets.

## Que hace

- extrae texto de imagen o PDF con PaddleOCR
- detecta fecha y lugar de compra
- arma filas con estas columnas: `Descripción|Marca|Lugar de compra|Categoria|Cantidad|Precio unitario|Fecha`
- genera dos salidas:
  - formato con `|` para guardar o copiar
  - formato tabulado para pegar directo en Google Sheets
- muestra una tabla editable para corregir o eliminar filas antes de copiar
- marca filas ambiguas y muestra advertencias completas cuando una línea no pudo confirmarse
- muestra tambien el texto OCR crudo para depurar

## Requisitos

- Docker Desktop

## Levantar con Docker Compose

1. Abre Docker Desktop.
2. Desde la carpeta del proyecto ejecuta:

```bash
docker compose up --build -d
```

3. En el primer arranque el contenedor OCR descarga los modelos. Espera a que termine antes de subir la primera factura.
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
4. Usa `Copiar formato para Sheets`.
5. Pega en Google Sheets.
6. Si algun item sale raro, revisa el bloque `Texto OCR crudo`.

## Limites actuales

- la marca se estima a partir del inicio de la descripcion
- la categoria se fija en `Supermercado`
- la cantidad se asume `1`, salvo cuando el texto sugiere multiplicador explicito tipo `x4`
- tickets muy borrosos o torcidos van a necesitar mejores reglas o preprocesado
- el primer build de Docker puede tardar porque descarga la imagen y los modelos de OCR
- para fotos de tickets, el OCR ahora prueba varias versiones de la imagen y elige la mas util automaticamente
- las filas nuevas no se cargan manualmente desde la UI; se corrigen o eliminan las filas detectadas
- el precio total no forma parte de la salida del MVP

## Mejoras faciles para despues

- diccionario de marcas frecuentes
- categorias configurables
- exportacion directa a Google Sheets API
- guardar historial de tickets procesados
