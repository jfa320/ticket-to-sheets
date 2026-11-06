# Carga directa a Google Sheets

La aplicación puede cargar los productos revisados con el botón **Cargar en Google Sheets**. El destino se configura desde el engranaje conectando Google, eligiendo una planilla nativa alojada en Drive e indicando su año y fila de encabezados. La fecha de las filas seleccionadas propone la pestaña mensual; también se puede elegir otra pestaña para cargar tickets atrasados. El Excel de referencia se usa para conocer la estructura; no se sube ni modifica durante la configuración.

## Autorización con tu cuenta de Google

Pulsá **Conectar con Google**, elegí tu cuenta y aceptá los permisos. Luego usá **Elegir planilla de Google** para autorizar una planilla nativa. El permiso `https://www.googleapis.com/auth/drive.file` permite trabajar con los archivos que autorices desde Google Picker; pegar un enlace por sí solo no concede acceso. No se usa una cuenta de servicio ni hay que compartir el archivo con un correo adicional. Tu cuenta debe poder editar la planilla. [Permisos de Sheets](https://developers.google.com/workspace/sheets/api/scopes), [acceso por archivo](https://developers.google.com/workspace/drive/api/guides/api-specific-auth#benefits_of_the_drive_file_scope).

El backend usa el flujo OAuth de código de autorización, `state` de un solo uso asociado a una cookie HttpOnly, PKCE S256 y acceso offline. La ventana de Google vuelve a un callback local sin enviar credenciales a la ventana principal. Solo se entrega un token de acceso temporal al selector oficial; el secreto del cliente y el token de renovación no llegan al navegador. El token de acceso se renueva automáticamente. [OAuth para aplicaciones web](https://developers.google.com/identity/protocols/oauth2/web-server).

## Configuración inicial en Google Cloud

Esta configuración se hace una vez para tu instalación local:

1. En [Google Cloud Console](https://console.cloud.google.com/), elegí o creá un proyecto y habilitá **Google Sheets API**, **Google Drive API** y **Google Picker API**.
2. En **Google Auth Platform**, configurá el nombre y correo de contacto de la aplicación y su audiencia. Para una cuenta personal, elegí audiencia externa y agregá tu correo como usuario de prueba mientras esté en Testing. Declarar únicamente el scope `https://www.googleapis.com/auth/drive.file`.
3. Creá un **cliente OAuth de tipo Aplicación web**. Agregá `http://localhost:8080` como origen JavaScript autorizado y `http://localhost:8080/api/sheets/oauth/callback` como URI de redireccionamiento autorizada. Anotá el Client ID y su Client Secret; son credenciales de la aplicación, no una clave de cuenta de servicio.
4. Creá una **clave de API para Google Picker**, restringida a Google Picker API. Para restricciones de sitios web, incluí `http://localhost:8080/*` y `https://docs.google.com/*`, donde se aloja el selector. Usá las credenciales del mismo proyecto. Anotá también el **número del proyecto** para `APP_GOOGLE_APP_ID`, no su nombre ni ID textual. [Configuración oficial de Picker](https://developers.google.com/workspace/drive/picker/guides/web-picker).
5. Completá un archivo `.env` local usando `.env.example` como referencia:

   ```dotenv
   APP_GOOGLE_CLIENT_ID=tu-cliente.apps.googleusercontent.com
   APP_GOOGLE_CLIENT_SECRET=tu-secreto-local
   APP_GOOGLE_PICKER_API_KEY=tu-clave-de-picker
   APP_GOOGLE_APP_ID=numero-del-proyecto
   APP_GOOGLE_REDIRECT_URI=http://localhost:8080/api/sheets/oauth/callback
   ```

   Compose lee `.env` automáticamente. Para `mvn spring-boot:run`, exportá esas variables en la terminal; Spring Boot no lee `.env` por sí solo. No publiques esos valores.
6. Iniciá o reconstruí la app con `docker compose up --build -d`. Abrí `http://localhost:8080`, usando exactamente el origen registrado; `localhost` y `127.0.0.1` son orígenes distintos. Si preferís `127.0.0.1`, actualizá el origen, las restricciones de la clave y el callback en Google Cloud y en `APP_GOOGLE_REDIRECT_URI`.
7. Abrí el engranaje, pulsá **Conectar con Google** y completá la autorización en la ventana emergente. Luego **Elegir planilla de Google**, indicá el año y dejá **Fila de encabezados = 2** para la plantilla de compras. Guardá y comprobá la conexión. Si las pestañas no se detectan por nombre, asociá cada mes a su pestaña exacta y guardá otra vez.
8. Procesá el ticket, corregí las filas y pulsá **Cargar en …**. La autorización y la selección de archivo no cargan productos; la escritura comienza con ese botón.

El archivo debe ser una planilla nativa de Google Sheets. Si solo tenés un `.xlsx` en Drive, abrilo con Sheets y guardalo como una planilla de Google.

**Desconectar Google** elimina la conexión persistida y solicita la revocación en Google. No borra tu planilla, la configuración del destino, el catálogo histórico ni los intentos de carga pendientes. Si Internet impide confirmar la revocación, la pantalla lo informa y podés quitar el acceso desde [las conexiones de tu cuenta](https://myaccount.google.com/connections). Para cambiar de cuenta, desconectá, conectá la nueva y elegí otra vez la planilla.

Las apps externas en modo Testing pueden tener tokens de renovación con vencimiento corto, habitualmente siete días para este scope; si Google pide volver a autorizar, usá Conectar con Google. Para evitar esa limitación, configurá el estado de publicación según las reglas de Google para tu uso personal. [Vencimiento de tokens y excepciones](https://developers.google.com/identity/protocols/oauth2#expiration).

La integración anterior de cuenta de servicio se eliminó. No se lee `APP_SHEETS_CREDENTIALS_PATH` ni el JSON anterior. La planilla y sus asociaciones mensuales se conservan; autorizala con el selector. Los archivos privados preexistentes no se eliminan automáticamente y siguen excluidos de Git.

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

La interfaz conserva el UUID y bloquea el doble clic. El intento sin confirmar también se guarda en `sessionStorage`, con el destino y una huella SHA-256; no incluye filas, precios, fotos, OCR ni credenciales. Tras recargar o volver a procesar, antes de enviar se consulta `POST /api/sheets/status`. Si Google ya guardó la misma operación, se devuelve su confirmación sin otra escritura. Si no aparece y se mantienen los mismos datos, se reintenta con el UUID original. Una falla al consultar conserva el intento y bloquea otra escritura.

Si la carga anterior quedó guardada pero ahora los datos son diferentes, el primer clic confirma esa situación y pide revisar la selección; una nueva carga enviará **todas las filas seleccionadas**. Si la carga aún no puede confirmarse, se deben restablecer sus datos y destino antes de reintentar. El navegador debe permitir almacenamiento de sesión: si no puede guardar el intento, no se envían filas. La recuperación cubre recargas dentro de esa pestaña; cerrar la pestaña elimina su sesión. Tras una confirmación normal se elimina el intento temporal. No hay detección universal de tickets repetidos: volver a cargar deliberadamente una compra confirmada puede crear otra operación.

Usá una sola instancia local para cargar. El batch conserva sus propios cambios de forma atómica, pero la aplicación no bloquea a otra persona que esté editando simultáneamente las mismas filas desde Sheets.

Si una carga falla sin confirmación y editás los datos, la interfaz advierte que la versión anterior podría haberse guardado. Volvé a esa versión y reintentá para confirmarla antes de enviar cambios. Las tablas nativas con pie de totales se rechazan: conservá el resumen lateral de la plantilla para evitar que una ampliación mueva el pie sobre productos.

## Usar las compras anteriores para mejorar la detección

Después de guardar y comprobar el destino, pulsá **Actualizar historial**. La app lee A:D de todas las pestañas de cuadrícula del archivo configurado que tengan `Descripción`, `Marca`, `Lugar de compra` y `Categoria` en la fila de encabezados elegida. El destino de carga sigue siendo una sola pestaña; el catálogo reúne las pestañas compatibles. La pantalla informa cuáles se incluyeron y cuáles se omitieron. Usa [lecturas por rango](https://developers.google.com/workspace/sheets/api/reference/rest/v4/spreadsheets.values/get) con valores calculados sin formato, para no importar fórmulas como nombres de productos.

La sincronización es manual y reemplaza el catálogo local únicamente al completar la lectura. Un error de conexión conserva la versión anterior. El catálogo funciona sin Internet en las próximas extracciones; actualizarlo no modifica el ticket que ya tenés abierto. Volvé a extraerlo si querés aplicar el catálogo nuevo.

El cruce considera el **texto original de la línea**, el comercio y la marca, para evitar confundir sabores o presentaciones que el parser haya resumido. Coincidencias exactas o aproximaciones claras y sin contradicciones completan descripción, marca y categoría. Admite errores OCR, abreviaturas comunes, palabras reordenadas y unidades equivalentes. Si hay varias marcas, variantes o categorías incompatibles, no se decide por mayoría. Las aproximaciones dudosas aparecen como sugerencias en las advertencias. Una marca reconocida que contradice la historia se conserva.

Las correcciones manuales de `data/corrections.json` tienen prioridad. No se sustituyen cantidades, precios, fechas ni comercios; no se recuperan líneas descartadas basándose únicamente en el historial y no se entrena el motor PaddleOCR. Las filas exactas se marcan **Historial**, salvo que ya requieran revisión por ambigüedad.

El archivo local `data/sheets-history.json` conserva los campos de producto, contadores, origen y fecha de actualización. No almacena importes, fechas de compra ni imágenes. Está ignorado por Git y excluido del build Docker; su respaldo `.bak` también está ignorado. Puede combinarse con `data/local-history.json`, importado desde Excel mediante `scripts/import_purchase_history.py` (ver README). **Dejar de usar historial** desactiva ambos. Al elegir otro archivo o fila de encabezados deja de aplicarse el catálogo de Sheets de otro destino; el catálogo de Excel sigue activo. Cambiar solo la pestaña mensual del mismo archivo conserva su utilidad.

La lectura tiene un límite de **50.000 celdas** por sincronización. No se instala una base de datos ni se consulta Google por cada ticket. Esta ayuda reduce trabajo de revisión en productos repetidos; no garantiza reconocer productos nuevos o imágenes ilegibles.

## Configuración local

| Propiedad | Variable | Valor predeterminado |
| --- | --- | --- |
| `app.google.client-id` | `APP_GOOGLE_CLIENT_ID` | Vacío |
| `app.google.client-secret` | `APP_GOOGLE_CLIENT_SECRET` | Vacío |
| `app.google.picker-api-key` | `APP_GOOGLE_PICKER_API_KEY` | Vacío |
| `app.google.app-id` | `APP_GOOGLE_APP_ID` | Vacío |
| `app.google.redirect-uri` | `APP_GOOGLE_REDIRECT_URI` | `http://localhost:8080/api/sheets/oauth/callback` |
| `app.google.tokens-path` | `APP_GOOGLE_TOKENS_PATH` | `data/google-oauth-tokens.json` |
| `app.sheets.config-path` | `APP_SHEETS_CONFIG_PATH` | `data/sheets-config.json` |
| `app.sheets.history-path` | `APP_SHEETS_HISTORY_PATH` | `data/sheets-history.json` |
| `app.sheets.local-history-path` | `APP_LOCAL_HISTORY_PATH` | `data/local-history.json` |
| `app.sheets.timeout-ms` | `APP_SHEETS_TIMEOUT_MS` | `15000` |

En Compose las rutas de datos son `/app/data/...`, por el volumen `./data:/app/data`. El token de renovación se persiste con reemplazo atómico, sin crear copias de respaldo que pudieran recuperar una sesión desconectada. Se asocia al Client ID y no se reutiliza si cambia de cliente. El archivo, sus temporales, `.env`, `data/` y `secrets/` quedan fuera de Git o del contexto de build según corresponda. Los respaldos de aprendizaje no exportan los tokens. No se guardan tokens en localStorage ni sessionStorage.

## Diagnóstico y pruebas

- **Falta configuración de Google**: completá las variables OAuth y Picker y reiniciá la app.
- **Acceso denegado / no encontrado**: habilitá las APIs y elegí la planilla con el selector. Verificá que tu cuenta pueda editarla. Un enlace guardado no concede permiso por sí solo.
- **Autorización vencida o revocada**: volvé a conectar Google y elegí la planilla. No reenvíes productos con un UUID nuevo si una carga sigue sin confirmar.
- **Ventana emergente bloqueada o cancelada**: permití ventanas emergentes para la app local y volvé a pulsar Conectar con Google.
- **redirect_uri_mismatch**: el callback de Google Cloud debe coincidir exactamente con `APP_GOOGLE_REDIRECT_URI`.
- **API developer key is invalid**: revisá la clave de Picker, su API habilitada, el número de proyecto y los referrers permitidos, incluido `https://docs.google.com/*`.
- **Pestaña o encabezados incorrectos**: volvé a comprobar la conexión, elegí una pestaña compatible o asociá el mes manualmente en el engranaje. La app no crea pestañas.
- **Rango protegido o celdas combinadas**: permití editar el rango A:H o usá una tabla de productos sin combinaciones allí.
- **Respuesta perdida**: reintentá la misma carga sin cambiar los ítems para comprobar su marcador.
- **Windows / Java 17: `Unable to establish loopback connection` al iniciar**: en algunos equipos fallan los sockets Unix internos del JDK. Para una comprobación local con el JAR, podés forzar su fallback TCP con `java -Djdk.net.unixdomain.tmpdir=debug/unused-unix-socket-directory -jar target/facturas-ocr-0.0.1-SNAPSHOT.jar`. Esa ruta debe ser inexistente; la app seguirá usando el puerto 8080. En esta revisión se comprobó el arranque y los endpoints locales mediante ese ajuste temporal. No se modificó la configuración del JDK del equipo.

`mvn test` y `node --test src/test/js/*.test.mjs` verifican la integración con respuestas simuladas, sin escribir compras reales. La prueba de autorización real requiere configurar tu cliente OAuth, autorizar tu cuenta en el navegador y elegir una planilla. Comprobar conexión solo lee metadatos y encabezados; la primera escritura se hace al pulsar el botón de carga.
