# Carga directa a Google Sheets

La aplicación puede cargar los productos revisados con el botón **Cargar en Google Sheets**. El destino se configura desde el engranaje con la URL o ID de una planilla nativa alojada en Drive, su año y la fila de encabezados. La fecha de las filas seleccionadas propone la pestaña mensual; también se puede elegir otra pestaña para cargar tickets atrasados. El Excel de referencia se usa para conocer la estructura; no se sube ni modifica durante la configuración.

## Autorización elegida

El backend usa una cuenta de servicio de Google. Compartí únicamente las planillas que quieras cargar con su email como **Editor**. Para acceder a un documento compartido no hacen falta roles administrativos ni delegación de dominio. La conexión de Drive del chat no autoriza automáticamente a la aplicación local. [Documentación oficial de acceso a documentos](https://developers.google.com/workspace/guides/create-credentials#access_google_workspace_files_directly_with_a_service_account).

La app solicita el permiso `https://www.googleapis.com/auth/spreadsheets` y llama a Google Sheets API. No necesita Drive API ni listar todo tu Drive para un destino configurado por URL/ID. La clave RSA firma una solicitud de token OAuth; el token se conserva en memoria y se renueva cuando vence. [Autenticación de cuentas de servicio](https://developers.google.com/identity/protocols/oauth2/service-account).

## Configuración inicial

1. En [Google Cloud Console](https://console.cloud.google.com/), elegí o creá un proyecto para la aplicación. Habilitá **Google Sheets API** en **APIs y servicios > Biblioteca**.
2. Abrí **IAM y administración > Cuentas de servicio**, creá una cuenta y anotá su email. Podés omitir la asignación de roles de Google Cloud para este uso.
3. En esa cuenta, abrí **Claves > Agregar clave > Crear clave nueva**, elegí **JSON** y descargalo. Guardalo como `data/google-service-account.json` dentro de este proyecto. Si tu organización impide crear claves, necesitás que su administrador habilite una alternativa de autenticación; esta implementación espera un JSON de cuenta de servicio. [Creación de claves](https://docs.cloud.google.com/iam/docs/keys-create-delete).
4. Abrí el Google Sheet, pulsá **Compartir** y agregá el email de la cuenta como **Editor**. Desmarcá la notificación: las cuentas de servicio no tienen bandeja de correo.
5. Iniciá o reconstruí la app: `docker compose up --build -d`. El volumen existente `./data:/app/data` incluye las credenciales y la configuración. Para backend local usá `mvn spring-boot:run`.
6. Abrí el engranaje, pegá la URL, indicá el año de la planilla y dejá **Fila de encabezados = 2** para la plantilla de compras. Guardá y comprobá la conexión. Si la detección automática no encuentra los nombres de pestaña, asociá cada mes a su pestaña exacta y guardá otra vez.
7. Procesá el ticket, corregí las filas y marcá las que querés usar. La app propone una pestaña cuando todas esas filas tienen fecha del mismo mes y año configurado. Para un ticket atrasado, elegí otra pestaña en **Destino de carga**; las fechas originales de las filas se conservan. Pulsá **Cargar en …**. La confirmación informa cantidad, pestaña y rango escrito.

El archivo debe ser una planilla nativa de Google Sheets. Si solo tenés el `.xlsx` en Drive, primero abrilo con Google Sheets y guardalo como una planilla de Google.

## Estructura compatible

Los encabezados A:I deben tener este orden; se admite normalización de mayúsculas, espacios y acentos:

| Columna | Encabezado | Carga |
| --- | --- | --- |
| A | Descripción | Texto literal |
| B | Marca | Texto literal |
| C | Lugar de compra | Texto literal |
| D | Categoria | Texto literal |
| E | Cantidad | Número, admite fracciones |
| F | Precio unitario | Número con formato monetario |
| G | Fecha | Fecha numérica, formato `d/MM/yyyy` |
| H | Precio total | Fórmula `=E<fila>*F<fila>` |
| I | Comentarios | Se conserva |

Se busca la última fila con entradas A:G, comentarios I o un total H ajeno a la fórmula de la plantilla. Las fórmulas H preparadas en filas vacías no cuentan como productos. La carga escribe después de esa última fila y conserva los bloques laterales, incluido un resumen como `=SUM(H:H)`. No rellena huecos intermedios ni ordena compras existentes.

Se conservan las validaciones y estilos existentes; se define el formato numérico de E:H para las filas cargadas. Si faltan filas físicas, se amplía la cuadrícula. Se extiende únicamente la tabla nativa o el filtro básico A:I que empieza en la fila de encabezados configurada. No se crean pestañas mensuales: si falta **Octubre**, creala en Sheets con los mismos encabezados y luego volvé a comprobar la conexión. Solo se ofrecen pestañas con la estructura A:I compatible.

La asociación mensual puede detectar nombres inequívocos como `Agosto`, `Agosto 2026` o `2026 Agosto`. Si hay varias coincidencias o usás otros nombres, seleccioná la pestaña de cada mes en el engranaje. El selector por ticket muestra **Automático** mientras usa la fecha; al elegir un nombre de pestaña, esa selección manual se mantiene mientras revisás ese ticket. Al procesar otro archivo, vuelve a proponerse el mes por su fecha.

Los textos que empiezan por `=` se envían como texto. Las fechas se validan antes de convertirlas a días desde `1899-12-30`, evitando que el locale de la planilla invierta día y mes. [Valores y formatos de fechas](https://developers.google.com/workspace/sheets/api/guides/formats#about_date_and_time_values), [escritura de celdas](https://developers.google.com/workspace/sheets/api/reference/rest/v4/spreadsheets/request#UpdateCellsRequest).

## Reintentos y duplicados

Cada operación tiene un UUID y una huella del destino y los datos. Las filas y un marcador de esa operación se guardan juntos mediante un batch atómico. Al reintentar el mismo UUID, el backend busca el marcador y devuelve la confirmación anterior. Si el UUID llega con otros datos, rechaza la carga. No reintenta escrituras automáticamente cuando Google no confirma la respuesta. [Batch atómico](https://developers.google.com/workspace/sheets/api/reference/rest/v4/spreadsheets/batchUpdate), [búsqueda de metadatos](https://developers.google.com/workspace/sheets/api/reference/rest/v4/spreadsheets.developerMetadata/search).

La interfaz conserva el UUID para reintentar durante la revisión actual y bloquea el doble clic. Tras una carga confirmada, cambiar las filas o el destino crea una operación nueva que vuelve a enviar **todas las filas seleccionadas**; la pantalla lo advierte. Volver a procesar el ticket o recargar la página también inicia una revisión nueva. No hay detección universal de tickets repetidos: el catálogo de productos descrito abajo no identifica operaciones de carga.

Usá una sola instancia local para cargar. El batch conserva sus propios cambios de forma atómica, pero la aplicación no bloquea a otra persona que esté editando simultáneamente las mismas filas desde Sheets.

Si una carga falla sin confirmación y editás los datos, la interfaz advierte que la versión anterior podría haberse guardado. Volvé a esa versión y reintentá para confirmarla antes de enviar cambios. Las tablas nativas con pie de totales se rechazan: conservá el resumen lateral de la plantilla para evitar que una ampliación mueva el pie sobre productos.

## Usar las compras anteriores para mejorar la detección

Después de guardar y comprobar el destino, pulsá **Actualizar historial**. La app lee A:D de todas las pestañas de cuadrícula del archivo configurado que tengan `Descripción`, `Marca`, `Lugar de compra` y `Categoria` en la fila de encabezados elegida. El destino de carga sigue siendo una sola pestaña; el catálogo reúne las pestañas compatibles. La pantalla informa cuáles se incluyeron y cuáles se omitieron. Usa [lecturas por rango](https://developers.google.com/workspace/sheets/api/reference/rest/v4/spreadsheets.values/get) con valores calculados sin formato, para no importar fórmulas como nombres de productos.

La sincronización es manual y reemplaza el catálogo local únicamente al completar la lectura. Un error de conexión conserva la versión anterior. El catálogo funciona sin Internet en las próximas extracciones; actualizarlo no modifica el ticket que ya tenés abierto. Volvé a extraerlo si querés aplicar el catálogo nuevo.

El cruce considera el **texto original de la línea**, el comercio y la marca, para evitar confundir sabores o presentaciones que el parser haya resumido. Coincidencias exactas y sin contradicciones completan descripción, marca y categoría. Si hay varias marcas, variantes o categorías incompatibles, no se decide por mayoría. Las aproximaciones solo aparecen como sugerencias en las advertencias. Una marca reconocida que contradice la historia se conserva.

Las correcciones manuales de `data/corrections.json` tienen prioridad. No se sustituyen cantidades, precios, fechas ni comercios; no se recuperan líneas descartadas basándose únicamente en el historial y no se entrena el motor PaddleOCR. Las filas exactas se marcan **Historial**, salvo que ya requieran revisión por ambigüedad.

El archivo local `data/sheets-history.json` conserva los campos de producto, contadores, origen y fecha de actualización. No almacena importes, fechas de compra ni imágenes. Está ignorado por Git y excluido del build Docker; su respaldo `.bak` también está ignorado. **Dejar de usar historial** desactiva su aplicación. Al elegir otro archivo o fila de encabezados no se aplica un catálogo ajeno; cambiar solo la pestaña mensual del mismo archivo conserva su utilidad.

La lectura tiene un límite de **50.000 celdas** por sincronización. No se instala una base de datos ni se consulta Google por cada ticket. Esta ayuda reduce trabajo de revisión en productos repetidos; no garantiza reconocer productos nuevos o imágenes ilegibles.

## Configuración local

| Propiedad | Variable | Valor predeterminado |
| --- | --- | --- |
| `app.sheets.credentials-path` | `APP_SHEETS_CREDENTIALS_PATH` | `data/google-service-account.json` |
| `app.sheets.config-path` | `APP_SHEETS_CONFIG_PATH` | `data/sheets-config.json` |
| `app.sheets.history-path` | `APP_SHEETS_HISTORY_PATH` | `data/sheets-history.json` |
| `app.sheets.timeout-ms` | `APP_SHEETS_TIMEOUT_MS` | `15000` |

En Compose, las rutas predeterminadas son `/app/data/...`. `.env.example` contiene valores para ese entorno. Para una clave fuera del proyecto al correr Maven en Windows:

```powershell
$env:APP_SHEETS_CREDENTIALS_PATH = 'C:\ruta-local\cuenta-servicio.json'
mvn spring-boot:run
```

Las credenciales nunca se envían al navegador. El JSON de la cuenta, el destino y el catálogo histórico están ignorados por Git y excluidos del build Docker. `secrets/` también está ignorado si preferís guardar la clave allí. El backend no guarda tokens ni operaciones de carga en archivos locales.

## Diagnóstico y pruebas

- **Falta JSON**: guardá la clave en la ruta configurada y volvé a comprobar la conexión; no necesitás reiniciar por un cambio del contenido del archivo.
- **Acceso denegado / no encontrado**: revisá que Sheets API esté habilitada y que el email de la cuenta tenga permiso de Editor sobre el archivo correcto.
- **Pestaña o encabezados incorrectos**: volvé a comprobar la conexión, elegí una pestaña compatible o asociá el mes manualmente en el engranaje. La app no crea pestañas.
- **Rango protegido o celdas combinadas**: permití editar el rango A:H o usá una tabla de productos sin combinaciones allí.
- **Respuesta perdida**: reintentá la misma carga sin cambiar los ítems para comprobar su marcador.
- **Windows / Java 17: `Unable to establish loopback connection` al iniciar**: en algunos equipos fallan los sockets Unix internos del JDK. Para una comprobación local con el JAR, podés forzar su fallback TCP con `java -Djdk.net.unixdomain.tmpdir=debug/unused-unix-socket-directory -jar target/facturas-ocr-0.0.1-SNAPSHOT.jar`. Esa ruta debe ser inexistente; la app seguirá usando el puerto 8080. En esta revisión se comprobó el arranque y los endpoints locales mediante ese ajuste temporal. No se modificó la configuración del JDK del equipo.

`mvn test` y `node --test src/test/js/*.test.mjs` verifican la integración con respuestas simuladas, sin escribir compras reales. La prueba de autorización real requiere tu JSON y una planilla compartida con esa cuenta. Comprobar conexión solo lee metadatos y encabezados; la primera escritura se hace al pulsar el botón de carga.
