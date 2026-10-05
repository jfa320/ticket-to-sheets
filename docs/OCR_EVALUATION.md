# Evaluación del texto OCR

`ocr/evaluate.py` compara el texto OCR crudo con transcripciones verificadas. Usa el backend local en `http://127.0.0.1:8080/api/receipts/extract`; no requiere publicar el puerto del OCR ni instalar paquetes adicionales para ejecutar el evaluador.

Las pruebas unitarias usan imágenes sintéticas y un motor simulado. No demuestran por sí solas que PaddleOCR reconozca mejor los tickets reales. Este procedimiento permite medirlo con los mismos archivos y las mismas transcripciones en ambos modos.

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

- `character_error_rate`: distancia de edición dividida por los caracteres esperados; menor es mejor y puede superar 1 si sobra mucho texto. Normaliza mayúsculas/minúsculas, espacios repetidos y líneas vacías, conservando puntuación y orden.
- `lines`: coincidencias exactas de líneas normalizadas, contando repeticiones y sin exigir el mismo orden. `missing` incluye tanto líneas omitidas como líneas que cambiaron algún carácter; no mide exclusivamente omisiones.
- `prices`: coincidencias numéricas exactas de importes, contando repeticiones. Acepta formatos como `1.200,00` y `1200.00`. Puede incluir totales u otras cifras con aspecto de importe, y no verifica la asociación producto/precio.
- `elapsed_seconds`: tiempo por caso, incluyendo la petición completa al backend y el cálculo de métricas; no es solo el tiempo del modelo OCR.
- `failed_cases`: archivos que no pudieron evaluarse. Se informan por separado y no se suman como aciertos ni se incluyen en las métricas de texto.
- `variant` y `score`: metadata devuelta por el backend, cuando está disponible. El score interno del OCR es una heurística de selección, no una medida de exactitud contra la transcripción.

La sección `comparison` calcula diferencias como **actual menos anterior**: CER negativo indica menos errores; recall positivo indica más coincidencias; tiempo positivo indica más demora. Solo compara casos exitosos con igual ID y hash del archivo, transcripción normalizada e importes esperados. Los casos modificados, nuevos, ausentes o fallidos quedan identificados fuera de esa comparación.

Revisá tanto los resultados por caso como el agregado: una mejora promedio puede ocultar una regresión en tickets tenues o largos. Conservá las transcripciones, archivos e informes localmente; el evaluador no copia el texto reconocido ni las rutas de los tickets al informe.
