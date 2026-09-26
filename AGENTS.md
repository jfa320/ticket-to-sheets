# AGENTS.md

## Propósito del repositorio

`ProyectoFacturas` es una aplicación local para extraer productos, precios, fechas y comercios desde tickets o facturas en imágenes y PDF. El backend Spring Boot procesa el archivo y consulta un servicio OCR local basado en PaddleOCR; luego transforma el texto OCR mediante reglas heurísticas y devuelve filas listas para copiar a Google Sheets.

La aplicación está pensada para uso personal/local. No hay autenticación, base de datos, historial de tickets ni despliegue multiusuario.

## Instrucciones de prioridad

- Seguir primero las instrucciones explícitas del usuario.
- Mantener el alcance local del proyecto y evitar agregar servicios o dependencias externas sin necesidad.
- Antes de cambiar reglas de parsing, revisar los tests existentes y agregar una regresión para cada caso corregido.
- No versionar datos personales o artefactos generados en runtime, especialmente `data/corrections.json`, `test-data/`, logs y archivos de debug.
- No modificar `target/`, caches de Docker ni otros artefactos generados para resolver un problema de código fuente.

## Stack y estructura

- Java 17, Spring Boot 3.3.5 y Maven.
- Apache PDFBox 3.0.3 para convertir PDFs a imágenes.
- Frontend estático sin npm ni bundler en `src/main/resources/static/`.
- Python 3.11, Flask 3.0.3 y PaddleOCR 2.8.1 en `ocr/`.
- Docker Compose coordina el backend (`app`) y el OCR (`paddleocr`).

Directorios importantes:

- `src/main/java/com/opencode/facturas/controller/`: endpoints REST y errores HTTP.
- `src/main/java/com/opencode/facturas/service/`: cliente OCR, parser, catálogo de marcas, memoria de correcciones y nombres de comercios.
- `src/main/java/com/opencode/facturas/model/`: records DTO y modelos de dominio.
- `src/main/java/com/opencode/facturas/util/`: exportación delimitada para Sheets/CSV.
- `src/main/resources/static/`: `index.html`, `app.js` y `styles.css`.
- `src/main/resources/store-mappings.json`: aliases OCR de comercios.
- `data/brands.json`: catálogo de marcas que puede actualizarse durante la ejecución.
- `data/corrections.json`: memoria local generada por las correcciones del usuario; está ignorada por Git.
- `ocr/service.py`: API Flask que ejecuta PaddleOCR y elige la mejor variante de imagen.
- `ocr/preprocess.py`: preprocesamiento y utilidades de imagen.
- `src/test/java/` y `ocr/tests/`: pruebas Java y Python.
- `docs/PROJECT_CONTEXT.md`: contexto técnico ampliado y mapa de componentes.

## Flujo funcional

1. El frontend envía `multipart/form-data` con el campo `file` a `POST /api/receipts/extract`.
2. `ReceiptController` valida la entrada y delega en `OcrService`.
3. `OcrService` renderiza PDFs si corresponde, prepara PNGs y llama al OCR local.
4. El servicio Python responde con texto, líneas, detecciones, variante y score.
5. `ReceiptParserService` extrae comercio, fecha, productos, marca, cantidad y precio.
6. `DelimitedExporter` genera formato con `|`, TSV con encabezado y TSV sin encabezado.
7. El frontend permite editar/eliminar filas y recalcula la salida antes de copiarla.
8. Las correcciones de descripción, marca y categoría se envían a `POST /api/corrections` y se guardan por comercio y firma de línea.

El endpoint del OCR es `POST /ocr` y su healthcheck es `GET /health`. En Docker, el backend usa `http://paddleocr:5000`; en ejecución local de Spring Boot, los valores por defecto apuntan a `127.0.0.1:5000`.

## Desarrollo y comandos

Requisitos mínimos: Docker Desktop para el flujo completo y Java 17/Maven para ejecutar solo Spring Boot.

```bash
# Flujo completo
docker compose up --build -d
docker compose logs -f app paddleocr
docker compose down

# Desarrollo mixto: OCR en Docker y backend local
docker compose up --build -d paddleocr
mvn spring-boot:run

# Validación Java
mvn test
mvn package

# Pruebas del servicio OCR
python -m unittest discover -s ocr/tests
```

La aplicación web queda disponible en `http://localhost:8080`. El primer arranque del contenedor OCR puede tardar porque descarga los modelos.

No hay `package.json`; no ejecutar comandos npm para el frontend.

## Convenciones de implementación

- Mantener Java 17 y el estilo actual de records para DTOs simples.
- Mantener la separación controller/service/model/util. Los controllers deben orquestar y validar; las reglas de negocio pertenecen a services.
- Reutilizar normalización de OCR y reglas existentes antes de agregar expresiones regulares nuevas.
- `ReceiptParserService` es sensible: cambiar una regla puede afectar muchos formatos de ticket. Preferir funciones pequeñas y tests específicos.
- La salida exportada conserva estas siete columnas y este orden: `Descripción`, `Marca`, `Lugar de compra`, `Categoria`, `Cantidad`, `Precio unitario`, `Fecha`.
- El total se calcula a partir de `cantidad * precioUnitario` de los items reconocidos; no debe tomarse del total impreso en el OCR.
- Las líneas de subtotal, descuentos y bloques de interfaz de PedidosYa no deben convertirse en productos.
- Si una marca no se reconoce, se usa `Genérico` según las reglas actuales. Las marcas dudosas deben conservar su estado/warning para revisión.
- Las fechas y precios deben respetar las convenciones actuales de locale `es-AR`.
- Los cambios de UI deben mantener sincronizados la tabla editable, el total, CSV/TSV, warnings y el envío con debounce de correcciones.
- No agregar Tesseract: los archivos en `tessdata/` son legado y el flujo actual usa PaddleOCR.

## Tests y cambios en el parser

Antes de considerar terminado un cambio relevante:

1. Ejecutar `mvn test`.
2. Si se modifica `ocr/`, ejecutar `python -m unittest discover -s ocr/tests`.
3. Agregar o actualizar una prueba cuando se cambie una regla de fecha, comercio, marca, precio, cantidad, ambigüedad, memoria o exportación.
4. Para una regresión OCR, preferir guardar texto de entrada reproducible y no imágenes que puedan contener datos personales.
5. Si no es posible ejecutar una prueba por falta de Docker, modelos o dependencias, indicarlo claramente en el resultado final.

## Datos, configuración y seguridad

- No incluir tickets, fotos, PDFs, correcciones personales, tokens ni secretos en commits.
- `data/corrections.json` contiene aprendizaje del usuario y se persiste mediante `./data:/app/data` en Compose.
- `data/brands.json` también puede cambiar en runtime; revisar el diff antes de incluirlo en un commit.
- El directorio `debug/` puede contener imágenes o salidas OCR sensibles y está ignorado.
- No exponer públicamente el puerto del OCR: Compose usa `expose`, mientras que solo la app publica `8080`.
- Los endpoints y timeouts del OCR se configuran en `src/main/resources/application.properties` o mediante variables `APP_OCR_*` en Compose.
- No introducir credenciales en `README.md`, archivos de configuración o tests.

## Documentación

- Actualizar `README.md` si cambia la forma de levantar o usar la aplicación.
- Actualizar `docs/PROJECT_CONTEXT.md` si cambia la arquitectura, un endpoint, el modelo de datos o el flujo principal.
- Mantener la documentación en español y consistente con el comportamiento real del código.
