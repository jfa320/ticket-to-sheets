# Evaluación del OCR y de los productos extraídos

`ocr/evaluate.py` compara texto OCR crudo con transcripciones verificadas y productos extraídos con referencias por fila. Usa el backend local en `http://127.0.0.1:8080/api/receipts/extract`; el evaluador usa solo la biblioteca estándar de Python y no requiere publicar el puerto del OCR. Se pueden evaluar productos sin disponer de una transcripción textual completa.

Las pruebas unitarias usan imágenes sintéticas y un motor simulado. No demuestran por sí solas que PaddleOCR reconozca mejor los tickets reales. Este procedimiento permite medirlo con los mismos archivos y las mismas transcripciones en ambos modos.

La búsqueda adaptativa se activa por defecto y tiene un presupuesto de 90 segundos por imagen/página, comprobado entre pasadas. Para aislar los efectos de geometría, relectura o combinación en las comparaciones de esta guía, definí `$env:OCR_ADAPTIVE_SEARCH = "false"` en ambas ejecuciones. Para medir la propia búsqueda adaptativa, compará una ejecución con `false` contra otra con `true`, manteniendo las demás opciones iguales; registrá también `OCR_SEARCH_BUDGET_SECONDS`. Recreá el OCR de Compose o reiniciá Python nativo después de cambiar variables. Con debug habilitado, `search-summary.json` distingue cierre por consenso, presupuesto o agotamiento de variantes.

Cuando la relectura está activa, la búsqueda reserva hasta 12 segundos (15% del presupuesto) para la última etapa. Las zonas de fecha y los precios dudosos se atienden primero. El scoring concede evidencia acotada a una fecha de compra válida y, con geometría disponible, excluye el pie posterior a subtotal/total/recibido del conteo de productos; nunca ajusta importes para que coincidan con el total impreso.

## Preparar los casos

1. Creá `test-data/ocr-evaluation/`. Todo el directorio `test-data/` está ignorado por Git.
2. Guardá allí imágenes o PDF locales y un `.txt` UTF-8 por archivo con la transcripción completa, revisada contra el original. Incluí encabezados, productos, precios y totales en orden de lectura, sin corregir abreviaturas impresas. Para PDF, transcribí las páginas en orden.
3. Usá identificadores neutros como `ticket-01`. Conviene incluir entre 20 y 30 casos variados: rectos, inclinados, con perspectiva, sombras, impresión tenue y tickets largos.
4. Creá `manifest.json`. Las rutas se resuelven desde la carpeta del manifest:

```json
{
  "cases": [
    {
      "id": "ticket-01",
      "image": "ticket-01.png",
      "text_file": "ticket-01.txt"
    },
    {
      "id": "ticket-02",
      "image": "ticket-02.pdf",
      "text_file": "ticket-02.txt"
    }
  ]
}
```

También se admite `expected_text` en lugar de `text_file`. Opcionalmente, `expected_prices` permite especificar importes como strings, por ejemplo `["1.200,00", "800,00", "2.000,00"]`. Esta lista debe representar todos los importes esperados, incluidos totales y valores repetidos: la métrica busca números en todo el texto, no los vincula con productos. Si se omite, los importes se extraen de la transcripción.

## Verificar la asociación producto/precio y los campos finales

Para medir productos, agregá `receipt_file` a cada caso. Ese JSON contiene una referencia revisada contra el original:

```json
{
  "date": "4/10/2026",
  "total": "2500,00",
  "items": [
    {"descripcion": "Leche entera 1 L", "cantidad": "2", "precioUnitario": "1000,00"},
    {"descripcion": "Pan 500 g", "cantidad": "1", "precioUnitario": "500,00"}
  ]
}
```

Manifest que mide solo los campos finales:

```json
{"cases": [{"id": "ticket-01", "image": "ticket-01.png", "receipt_file": "ticket-01.expected.json"}]}
```

También se admite `expected_receipt` directamente en el manifest, en lugar de `receipt_file`. Se pueden combinar referencias de productos y `text_file` para medir ambas etapas. Sin `text_file`/`expected_text`, no se inventa una transcripción: el CER queda `null` y `text_annotated_cases` vale cero.

Cada producto requiere `descripcion`, `cantidad` y `precioUnitario`, como strings. Opcionalmente admite `marca`, `lugarDeCompra`, `categoria` y `fecha`. A nivel de ticket se pueden verificar `storeName`, `date` y `total`. Solo se puntúan los campos anotados; `items` incluye productos y no filas de bonificación. `total` puede representar el neto de productos y bonificaciones cuando se quiere validar también ese resultado.

Los productos se asocian por descripción exacta, normalizando mayúsculas y espacios, sin exigir el mismo orden. Se conservan las repeticiones y se priorizan las filas completamente coincidentes. Una descripción diferente se cuenta como producto sin reconocer, sin adivinar correspondencias por precio. Usá nombres canónicos revisados si el parser expande abreviaturas; no tomes automáticamente su salida como referencia. Un precio intercambiado entre dos productos falla aunque el conjunto de importes sea idéntico. Cantidades e importes se comparan numéricamente, sin tolerancia, aceptando los formatos actuales `es-AR`; las fechas se validan por calendario.

El informe contiene solo contadores, tasas y hashes, sin valores de productos ni transcripciones. Los archivos de referencia y originales quedan en `test-data/`, ignorado por Git. Si reutilizás anotaciones previas, registrá su procedencia y qué campos se verificaron realmente.

## Comparar las nuevas correcciones

Desde la raíz del proyecto, en PowerShell:

```powershell
$env:OCR_TARGETED_RETRY = "false"
$env:OCR_VARIANT_FUSION = "false"
$env:OCR_IMAGE_CORRECTIONS = "false"
docker compose up --build -d
python ocr/evaluate.py test-data/ocr-evaluation/manifest.json --output test-data/ocr-evaluation/baseline.json

$env:OCR_IMAGE_CORRECTIONS = "true"
docker compose up -d
python ocr/evaluate.py test-data/ocr-evaluation/manifest.json --output test-data/ocr-evaluation/corrected.json --compare test-data/ocr-evaluation/baseline.json
```

Antes de cada evaluación, esperá a que el backend responda y procesá un archivo de calentamiento fuera de la medición. El primer arranque descarga/prepara modelos y puede distorsionar los tiempos. Usá el mismo equipo, modelos, configuración y archivos, sin otras extracciones simultáneas. Repetí las mediciones si necesitás comparar tiempos con precisión.

`OCR_IMAGE_CORRECTIONS=false` desactiva solamente las nuevas variantes de geometría e iluminación. El modo habilitado agrega corrección de perspectiva/inclinación cuando hay evidencia suficiente y variantes de contraste local/compensación del fondo; el original sigue participando en la selección. En ejecución directa de Flask, definí esa variable en el proceso del servicio OCR.

## Comparar la relectura de zonas dudosas

Para medir su efecto por separado, mantené las correcciones geométricas y de iluminación en el mismo estado durante ambas ejecuciones:

```powershell
$env:OCR_IMAGE_CORRECTIONS = "true"
$env:OCR_TARGETED_RETRY = "false"
$env:OCR_VARIANT_FUSION = "false"
docker compose up --build -d
python ocr/evaluate.py test-data/ocr-evaluation/manifest.json --output test-data/ocr-evaluation/without-retry.json

$env:OCR_TARGETED_RETRY = "true"
docker compose up -d
python ocr/evaluate.py test-data/ocr-evaluation/manifest.json --output test-data/ocr-evaluation/with-retry.json --compare test-data/ocr-evaluation/without-retry.json
```

Aplicá el mismo calentamiento previo y usá exactamente los mismos casos. La relectura procesa como máximo ocho zonas de baja confianza de la variante ganadora, mediante dos tratamientos de cada recorte. Solo acepta cambios cuando ambas lecturas coinciden y superan la confianza anterior; también comprueba cobertura y contenido para rechazar fragmentos o texto vecino. Conserva el resultado previo si no se cumplen esas condiciones.

La selección también incluye precios con confianza inferior a 0,97 y fechas incompletas con confianza inferior a 0,98. Si la confianza original es al menos 0,80, ambas lecturas nuevas deben alcanzar 0,98. Para precios se exige una mejora mínima de 0,02; para fechas incompletas, una fecha válida y confianza igual o superior a la original. Los demás textos conservan los umbrales anteriores (original < 0,80, lecturas >= 0,85 y mejora >= 0,10).

En variantes de encuadre completo se usan píxeles originales para la relectura, manteniendo las coordenadas de la variante elegida. En recortes o perspectiva se usa su propia imagen. Al comparar, mantené estas opciones iguales en ambos informes y verificá también las cajas, porque el precio o nombre corregido debe seguir perteneciendo al mismo producto.

En los informes, `variant` termina en `+retry` cuando hubo al menos una corrección aceptada. Si la variante no tiene ese sufijo, pudo haber intentos sin cambios aceptados. Para diagnosticarlo, `APP_OCR_DEBUG=true` agrega `targeted-retry.json` con contadores en la carpeta de debug de cada ejecución; las demás salidas de debug pueden contener texto e imágenes del ticket y deben permanecer locales.

Incluí casos con dígitos dudosos, precios repetidos, descripciones junto a columnas de importes y renglones muy cercanos. El consenso es una condición heurística, no una garantía de lectura correcta: las dos pasadas usan el mismo motor. La comparación contra transcripciones verificadas es la que permite detectar mejoras y regresiones.

## Comparar la combinación de variantes por renglón

Para aislar esta mejora, mantené las correcciones de imagen activadas y la relectura desactivada en ambas ejecuciones:

```powershell
$env:OCR_IMAGE_CORRECTIONS = "true"
$env:OCR_TARGETED_RETRY = "false"
$env:OCR_VARIANT_FUSION = "false"
docker compose up --build -d
python ocr/evaluate.py test-data/ocr-evaluation/manifest.json --output test-data/ocr-evaluation/without-fusion.json

$env:OCR_VARIANT_FUSION = "true"
docker compose up -d
python ocr/evaluate.py test-data/ocr-evaluation/manifest.json --output test-data/ocr-evaluation/with-fusion.json --compare test-data/ocr-evaluation/without-fusion.json
```

La combinación reutiliza las detecciones de las variantes ya reconocidas. Mantiene las cajas de la variante ganadora y reemplaza zonas de baja confianza solo ante dos imágenes distintas con lecturas coincidentes y de mayor confianza. Comprueba cobertura, contenido y correspondencia espacial; descarta lecturas contradictorias, incluidas diferencias de importes. No agrega zonas omitidas. El sufijo `+fusion` indica cambios aceptados; `APP_OCR_DEBUG=true` genera también `variant-fusion.json` con contadores.

Además de las métricas, comprobá las zonas modificadas en **Revisar zonas dudosas en la imagen**, activando **Mostrar todas las zonas**: las correcciones aceptadas pueden superar el umbral de confianza y dejar de aparecer en el filtro de dudosas. Incluí renglones repetidos, importes diferentes en filas vecinas y variantes que dividan una línea en varias cajas. El consenso entre variantes del mismo motor no sustituye la transcripción verificada.

Al terminar las comparaciones, podés volver a habilitar ambas mejoras con `OCR_TARGETED_RETRY=true` y `OCR_VARIANT_FUSION=true`, y recrear los contenedores con `docker compose up -d`.

El timeout por caso es de 300 segundos. Para ajustarlo agregá `--timeout 600`. `--endpoint` permite elegir otro backend de loopback, por ejemplo `http://127.0.0.1:8081/api/receipts/extract`. El evaluador rechaza destinos externos y no sigue redirecciones.

## Interpretar el informe

Los productos pueden incluir `association_descriptions`, una lista explícita de textos OCR revisados contra la imagen. Sirve únicamente para asociar una fila a su producto y medir cantidad/precio aunque el nombre tenga errores. **No hace correcto ese nombre**: `items.descripcion` y el acierto completo siguen comparándose con `descripcion`. Se rechazan asociaciones que identifican dos productos distintos. No se usan tolerancias de precios ni similitud automática de nombres. Estas referencias son supervisadas; no constituyen un conjunto independiente si se anotaron después de observar resultados.

## Comparar presupuestos de búsqueda

La búsqueda adaptativa comienza con sondas de orientación de hasta un megapíxel y 700 píxeles de lado corto. Una lectura completa tiene prioridad sobre una sonda; esta última solo queda como salida disponible si no llega a completarse otra lectura. No se consideran una transcripción verificada.

```powershell
python ocr/benchmark.py test-data/ocr-evaluation/manifest.json --output test-data/ocr-benchmark --budgets 20 40 90
# Otra ejecución individual:
python ocr/evaluate.py test-data/ocr-evaluation/manifest.json --ocr-budget-seconds 40 --output test-data/ocr-evaluation/40s.json
```

El benchmark usa las mismas imágenes/referencias y escribe un informe por presupuesto y una comparación contra el último. El header `X-OCR-Search-Budget-Seconds` acepta enteros de 5 a 180. El valor no cambia la configuración global del OCR. Es un límite cooperativo de búsqueda, no un timeout absoluto de la petición: una pasada nativa iniciada puede terminar después. El valor predeterminado continúa siendo 90 segundos.

## Corpus ficticio reproducible

```powershell
./.venv/Scripts/python.exe ocr/synthetic_corpus.py --output test-data/synthetic-corpus
./.venv/Scripts/python.exe ocr/benchmark.py test-data/synthetic-corpus/manifest-holdout.json --output test-data/synthetic-corpus/results --budgets 60
```

El generador crea 20 imágenes con referencias anteriores al OCR: diez de calibración y diez reservadas, con productos, fechas e importes distintos entre ambos grupos. Incluye texto normal, rotación de 90°, inclinación de 2°, impresión tenue y desenfoque moderado. Usa DejaVu Sans Mono o Courier de Windows; `--font` permite indicar una fuente TTF local. Las referencias evalúan descripción, cantidad, precio, fecha y total; no anotan marcas ni comercio. No se ajustan a la salida del OCR.

Estas imágenes no representan fotos reales difíciles ni una prueba estadística de generalización. Para evaluar cambios, conservar el grupo reservado sin usarlo para ajustar reglas. Si se usa para desarrollo, pasa a ser un conjunto de regresión y hace falta un grupo reservado nuevo. Todos los archivos generados quedan bajo `test-data/`, ignorado por Git.

`--save-responses` es opcional y guarda **datos privados completos**, incluidas imágenes de revisión y texto OCR. Usarlo solo en `test-data/` o en otra carpeta privada ignorada por Git. Sin esa opción, los informes conservan únicamente métricas, hashes y metadatos. La evaluación recorre perfiles secuencialmente; para mediciones de rendimiento aisladas, cerrar otras cargas de CPU y repetir varias veces.

- `character_error_rate`: distancia de edición dividida por los caracteres esperados; menor es mejor y puede superar 1 si sobra mucho texto. Normaliza mayúsculas/minúsculas, espacios repetidos y líneas vacías, conservando puntuación y orden.
- `lines`: coincidencias exactas de líneas normalizadas, contando repeticiones y sin exigir el mismo orden. `missing` incluye tanto líneas omitidas como líneas que cambiaron algún carácter; no mide exclusivamente omisiones.
- `prices`: coincidencias numéricas exactas de importes, contando repeticiones. Acepta formatos como `1.200,00` y `1200.00`. Puede incluir totales u otras cifras con aspecto de importe, y no verifica la asociación producto/precio.
- `elapsed_seconds`: tiempo por caso, incluyendo la petición completa al backend y el cálculo de métricas; no es solo el tiempo del modelo OCR.
- `request_seconds`: duración de la petición completa al backend, incluyendo transferencia y respuesta; excluye el cálculo local de métricas. No es una medición aislada del motor PaddleOCR.
- `latency_seconds`: cantidad de casos exitosos medidos, mediana `p50`, percentil `p95` y máximo de `request_seconds`. Los percentiles usan interpolación lineal; con pocos casos son orientativos. Los casos fallidos se informan aparte y no entran en esos percentiles.
- `receipts.items`: coincidencias de productos completos (todos sus campos anotados correctos), `recall` respecto de productos esperados y `precision` respecto de productos devueltos. `missing` incluye productos con algún campo erróneo y no indica exclusivamente una omisión.
- `receipts.fields`: aciertos y exactitud por campo, con denominador explícito. Un producto que no puede asociarse también cuenta como error en sus campos esperados. `annotated_cases` distingue los casos que tienen referencia por producto.
- `failed_cases`: archivos que no pudieron evaluarse. Se informan por separado y no se suman como aciertos ni se incluyen en las métricas de texto.
- `variant` y `score`: metadata devuelta por el backend, cuando está disponible. El score interno del OCR es una heurística de selección, no una medida de exactitud contra la transcripción.

La sección `comparison` calcula diferencias como **actual menos anterior**: CER negativo indica menos errores; recall o exactitud positivos indican más aciertos; tiempo positivo indica más demora. Incluye cambios de productos completos, exactitud por campo y tiempos p50/p95 cuando existen ambos valores. Solo compara casos exitosos con igual ID y hash del archivo, transcripción normalizada, importes y referencia de productos. Cambiar una anotación excluye el caso de la comparación; no se presenta como mejora del OCR. Los casos modificados, nuevos, ausentes o fallidos quedan identificados fuera de esa comparación.

El CLI informa el estado y duración de cada caso. “Procesado” significa que se pudo evaluar, no que sus productos sean correctos. Repetí la misma medición varias veces con calentamiento previo y sin otra extracción simultánea si querés comparar latencia. Conservá el informe agregado y revisá los resultados por caso: el promedio puede ocultar un ticket que empeoró.

Revisá tanto los resultados por caso como el agregado: una mejora promedio puede ocultar una regresión en tickets tenues o largos. Conservá las transcripciones, archivos e informes localmente; el evaluador no copia el texto reconocido ni las rutas de los tickets al informe.
