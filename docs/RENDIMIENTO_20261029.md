# Lectura rápida y mejoras de confiabilidad — 29/10/2026

Las seis mejoras de esta ronda están implementadas, con una lectura rápida predeterminada orientada a resolver un ticket en menos de 10 segundos.

## Medición con PaddleOCR real

Se midieron siete imágenes del banco local con referencias existentes, en una instancia aislada de Spring Boot y PaddleOCR. Se copió únicamente el catálogo de marcas; no se usaron correcciones, historial ni credenciales personales. Las imágenes y anotaciones no se modificaron. El tiempo incluye solicitud HTTP, validación, OCR, parser y respuesta; la primera solicitud está incluida. El arranque/precalentamiento de modelos ocurre antes de anunciar el servicio listo.

| Medida observada | Resultado |
| --- | --- |
| Tickets por debajo de 10 s | **7 de 7** |
| Mínimo | 4,56 s |
| Mediana | 6,05 s |
| Percentil 95 de la muestra | 7,82 s |
| Máximo | 8,03 s |
| Filas reconocidas / referencias | 69 / 69 |
| Totales coincidentes | 7 / 7 |
| Fechas anotadas coincidentes | 6 / 6 |

Entorno: Windows, Python 3.11.17, PaddleOCR 2.8.1 y PaddlePaddle 2.6.2; ocho CPU lógicas, cuatro hilos de inferencia y MKL-DNN habilitado. Antes del cambio, dos de esas imágenes consumieron 84,62 y 90,95 segundos solo en la búsqueda OCR.

La precisión de nombres sigue siendo una limitación: hubo 12 coincidencias completas estrictas sobre 69 referencias en esta ejecución sin aprendizaje personal. Las abreviaturas y marcas requieren revisión; que el total coincida no confirma cada descripción. Una comparación adicional de las dos lecturas anteriores guardadas, usando el mismo parser, catálogo y hashes de referencia, mantuvo 21 filas reconocidas y pasó de una a dos coincidencias completas. La muestra es pequeña y no demuestra precisión general sobre fotos nuevas.

El presupuesto es cooperativo. Una inferencia nativa iniciada, un PDF de varias páginas, otra carga de CPU o una foto difícil pueden superar los diez segundos. La opción **Detallada** sigue disponible para buscar más variantes. Los informes completos, tiempos por caso y datos privados permanecen en `logs/real-ocr-20261029-114847/report.json` y `logs/paired-replay/comparison.json`, ignorados por Git.

## Cambios realizados

1. **Respaldo restaurable:** límite compartido de 16 MB y 10.000 entradas por lista, política publicada por el servidor y comprobación de esquema/tamaño al exportar e importar. El navegador revisa los bytes del archivo final antes de descargar. Hay regresión para 3200 entradas cuyo archivo supera los 2 MB anteriores. Versiones desbordadas y controles multilínea se rechazan. Un estado fuera del límite informa el problema sin generar un archivo incompleto.
2. **Ediciones protegidas:** buscar, paginar, eliminar, cerrar o presionar Escape ofrece guardar, descartar o seguir editando. La recarga conserva el aviso del navegador. Se permiten correcciones parciales sin obligar a inventar una marca o descripción; un guardado fallido conserva los campos y detiene la navegación.
3. **Esperas acotadas:** cancelación/recuperación tiene 8 s de espera y progreso 5 s, incluidos cuerpos de respuesta bloqueados. El UUID pendiente se conserva y no se genera una nueva extracción tras una confirmación fallida.
4. **Catálogo cacheado:** alias deduplicados y patrones reutilizados; aprender o restaurar marcas reconstruye el índice después de persistir. Los bloqueos transitorios de archivos en Windows tienen un reintento breve y acotado sin borrar el destino.
5. **Navegador ampliado:** portapapeles real para texto e imagen, filtro de revisión, teclado, PDF multipágina y guardar/descartar ediciones administrativas, además del respaldo y la recuperación de Sheets sin duplicados.
6. **Evaluación real reproducible:** `scripts/benchmark_local.py` inicia servicios propios en 18083/18084, usa referencias congeladas, registra latencia/precisión y falla si una solicitud alcanza 10 s. Permite comparar informes con las mismas referencias y no modifica la instalación habitual.

La lectura rápida evita decenas de variantes, intenta orientaciones adicionales solo cuando no obtiene texto útil y confirma un importe únicamente con tiempo para sus relecturas. Conserva las advertencias si no logra confirmarlo. Los JPEG validados se transmiten sin convertir a PNG, preservando EXIF. El motor se precalienta antes de publicar disponibilidad y tiene fallback compatible si falla la aceleración.

## Validación

- **262 pruebas Java**, **82 JavaScript** y **214 Python**, sin fallos ni omisiones.
- `./scripts/check.ps1 -Browser`: imports del contenedor, scripts PowerShell, empaquetado y recorrido completo en Edge correctos.
- Revisión visual de escritorio y comprobación sin desborde horizontal en móvil de 390 px.
- El navegador usa datos ficticios y OCR/Sheets simulados. La medición de latencia anterior usa PaddleOCR real; no escribe en Google.
- Docker no se ejecutó. Los datos personales, capturas, respuestas OCR y workspaces generados permanecen fuera de Git.

Uso y configuración: `README.md`. Procedimiento de evaluación: `docs/OCR_EVALUATION.md`. Arquitectura y contratos: `docs/PROJECT_CONTEXT.md`.
