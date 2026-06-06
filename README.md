# Extractor local de facturas con Spring Boot

App local para subir una foto o PDF de un ticket, correr OCR gratis con PaddleOCR en Docker y devolver filas listas para pegar en Google Sheets.

## Que hace

- extrae texto de imagen o PDF con PaddleOCR
- detecta fecha y lugar de compra
- arma filas con estas columnas: `Descripción|Marca|Lugar de compra|Categoria|Cantidad|Precio unitario|Fecha|Precio total`
- genera dos salidas:
  - formato con `|` para guardar o copiar
  - formato tabulado para pegar directo en Google Sheets
- muestra tambien el texto OCR crudo para depurar

## Requisitos

- Java 17+
- Maven 3.9+
- Docker Desktop

## Levantar PaddleOCR con Docker

1. Abre Docker Desktop.
2. Desde la carpeta del proyecto ejecuta:

```bash
docker compose up --build paddleocr
```

3. En el primer arranque el contenedor descarga los modelos OCR. Espera a que termine antes de subir la primera factura.
4. El servicio OCR queda en:

```text
http://localhost:5000/ocr
```

## Levantar local

```bash
mvn spring-boot:run
```

Luego abre `http://localhost:8080`.

## Levantar con todo el comando completo

```bash
cd "C:\Users\juare\Documents\Proyectos Opencode\App Extraccion datos factura"
docker compose up --build -d paddleocr
mvn spring-boot:run
```

## Flujo recomendado

1. Subi una foto bien centrada del ticket.
2. Si la foto tiene mucho fondo, mano o monitor alrededor, intenta que el ticket ocupe la mayor parte de la imagen.
3. Usa `Copiar formato para Sheets`.
4. Pega en Google Sheets.
5. Si algun item sale raro, revisa el bloque `Texto OCR crudo`.

## Limites actuales

- la marca se estima a partir del inicio de la descripcion
- la categoria se fija en `Supermercado`
- la cantidad se asume `1`, salvo cuando el texto sugiere multiplicador explicito tipo `x4`
- tickets muy borrosos o torcidos van a necesitar mejores reglas o preprocesado
- el primer build de Docker puede tardar porque descarga la imagen y los modelos de OCR
- para fotos de tickets, el OCR ahora prueba varias versiones de la imagen y elige la mas util automaticamente

## Apagar el OCR Docker

```bash
cd "C:\Users\juare\Documents\Proyectos Opencode\App Extraccion datos factura"
docker compose down
```

## Mejoras faciles para despues

- diccionario de marcas frecuentes
- categorias configurables
- exportacion directa a Google Sheets API
- guardar historial de tickets procesados
