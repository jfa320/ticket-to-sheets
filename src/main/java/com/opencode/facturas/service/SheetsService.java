package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.facturas.model.ReceiptItem;
import com.opencode.facturas.model.SheetsModels.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class SheetsService {
    private static final List<String> HEADERS = List.of("Descripción", "Marca", "Lugar de compra", "Categoria",
            "Cantidad", "Precio unitario", "Fecha", "Precio total", "Comentarios");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("d/M/uuuu")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final LocalDate SHEETS_EPOCH = LocalDate.of(1899, 12, 30);
    private static final String METADATA_PREFIX = "facturas.import.";
    private static final int MAX_ITEMS = 500;
    private static final int MAX_SCAN_ROWS = 100000;
    private final ObjectMapper mapper;
    private final SheetsConfigStore config;
    private final GoogleServiceAccountAuth auth;
    private final GoogleSheetsClient client;

    public SheetsService(ObjectMapper mapper, SheetsConfigStore config, GoogleServiceAccountAuth auth,
                         GoogleSheetsClient client) {
        this.mapper = mapper;
        this.config = config;
        this.auth = auth;
        this.client = client;
    }

    public synchronized ConfigResponse getConfig() {
        return response(config.get());
    }

    public synchronized ConfigResponse saveConfig(ConfigRequest request) {
        return response(config.save(request));
    }

    public synchronized CheckResponse check() {
        Settings settings = configuredSettings();
        JsonNode metadata = client.metadata(settings.spreadsheetId());
        JsonNode sheets = metadata.path("sheets");
        if (!sheets.isArray()) throw new IllegalStateException("Google Sheets no devolvió las pestañas de la planilla.");
        if (sheets.size() > 1000) throw new IllegalArgumentException("La planilla supera las 1.000 pestañas. Usá un archivo anual más pequeño.");
        List<String> available = new ArrayList<>(), unavailable = new ArrayList<>();
        for (JsonNode sheet : sheets) {
            String name = sheet.path("properties").path("title").asText("");
            try {
                inspect(new Destination(settings.spreadsheetId(), name, settings.headerRow()), metadata);
                available.add(name);
            } catch (IllegalArgumentException incompatible) {
                unavailable.add(name);
            }
        }
        return new CheckResponse(settings.spreadsheetId(), metadata.path("properties").path("title").asText(),
                settings.headerRow(), config.version(), List.copyOf(available),
                suggestMonths(available, settings.year()), List.copyOf(unavailable));
    }

    public synchronized AppendResponse append(AppendRequest request) {
        if (request == null || request.requestId() == null
                || !request.requestId().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("La carga debe incluir un identificador UUID para evitar duplicados al reintentar.");
        }
        String requestId = UUID.fromString(request.requestId()).toString();
        List<List<Object>> rows = validatedRows(request.items());
        Settings settings = configuredSettings();
        Destination destination = request.destination();
        if (destination == null || !config.matches(new ConfigReference(destination.spreadsheetId(), destination.headerRow(), request.configVersion()))) {
            throw new IllegalArgumentException("El destino cambió o la configuración quedó desactualizada. Volvé a guardar y comprobar la configuración antes de cargar.");
        }
        if (settings.year() == null) throw new IllegalArgumentException("Guardá el año de la planilla antes de cargar.");
        String sheetName = SheetsConfigStore.validateSheetName(destination.sheetName());
        if (!sheetName.equals(destination.sheetName())) throw new IllegalArgumentException("Elegí el nombre exacto de la pestaña de destino.");
        String hash = payloadHash(destination, rows);
        String metadataKey = METADATA_PREFIX + requestId;
        JsonNode markers = client.findRequest(destination.spreadsheetId(), Map.of("dataFilters", List.of(
                Map.of("developerMetadataLookup", Map.of("metadataKey", metadataKey, "visibility", "DOCUMENT")))));
        JsonNode matches = markers.path("matchedDeveloperMetadata");
        if (matches.isArray() && !matches.isEmpty()) {
            return previousResult(destination, matches, hash, rows.size());
        }

        SheetContext context = inspect(destination);
        if (context.rowCount() > MAX_SCAN_ROWS) {
            throw new IllegalArgumentException("La pestaña supera las 100.000 filas. Elegí una pestaña mensual más pequeña para realizar una carga segura.");
        }
        String sheetRange = quoteSheet(destination.sheetName());
        JsonNode values = client.values(destination.spreadsheetId(), sheetRange + "!A"
                + (destination.headerRow() + 1) + ":I" + context.rowCount()).path("values");
        int firstDataRow = destination.headerRow() + 1;
        int firstWriteRow = firstDataRow;
        if (values.isArray()) {
            for (int index = 0; index < values.size(); index++) {
                int rowNumber = firstDataRow + index;
                if (occupied(values.get(index), rowNumber)) {
                    firstWriteRow = rowNumber + 1;
                }
            }
        }
        int lastWriteRow = firstWriteRow + rows.size() - 1;
        validateTargetRanges(context.sheet(), firstWriteRow - 1, lastWriteRow);

        List<Object> requests = new ArrayList<>();
        if (lastWriteRow > context.rowCount()) {
            requests.add(Map.of("appendDimension", Map.of("sheetId", context.sheetId(), "dimension", "ROWS",
                    "length", lastWriteRow - context.rowCount())));
        }
        List<Object> rowData = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            int rowNumber = firstWriteRow + index;
            JsonNode previous = values.path(rowNumber - firstDataRow);
            if (occupied(previous, rowNumber)) {
                throw new IllegalStateException("El rango de destino contiene datos. Volvé a comprobar la planilla antes de cargar.");
            }
            List<Object> cells = new ArrayList<>();
            for (Object value : rows.get(index)) {
                cells.add(Map.of("userEnteredValue", value instanceof Number
                        ? Map.of("numberValue", value) : Map.of("stringValue", value)));
            }
            String formula = cell(previous, 7).asText("");
            if (formula.isBlank()) {
                formula = "=E" + rowNumber + "*F" + rowNumber;
            }
            if (!expectedTotalFormula(formula, rowNumber)) {
                throw new IllegalArgumentException("La columna H del rango de destino contiene una fórmula o dato ajeno a cantidad por precio. Revisá la planilla.");
            }
            cells.add(Map.of("userEnteredValue", Map.of("formulaValue", formula)));
            rowData.add(Map.of("values", cells));
        }
        requests.add(Map.of("updateCells", Map.of("start", Map.of("sheetId", context.sheetId(),
                        "rowIndex", firstWriteRow - 1, "columnIndex", 0),
                "rows", rowData, "fields", "userEnteredValue")));
        requests.add(numberFormat(context.sheetId(), firstWriteRow, lastWriteRow, 4, "NUMBER", "0.####"));
        requests.add(numberFormat(context.sheetId(), firstWriteRow, lastWriteRow, 5, "CURRENCY", "$ #,##0.00"));
        requests.add(numberFormat(context.sheetId(), firstWriteRow, lastWriteRow, 6, "DATE", "d/MM/yyyy"));
        requests.add(numberFormat(context.sheetId(), firstWriteRow, lastWriteRow, 7, "CURRENCY", "$ #,##0.00"));
        extendTablesAndFilter(context.sheet(), destination.headerRow(), lastWriteRow, requests);
        String metadataValue = hash + ":" + context.sheetId() + ":" + firstWriteRow + ":" + lastWriteRow;
        requests.add(Map.of("createDeveloperMetadata", Map.of("developerMetadata", Map.of(
                "metadataKey", metadataKey, "metadataValue", metadataValue,
                "visibility", "DOCUMENT", "location", Map.of("sheetId", context.sheetId())))));
        // The rows and their retry marker are committed together in one atomic Sheets batch.
        client.batchUpdate(destination.spreadsheetId(), Map.of("requests", requests));
        return new AppendResponse(SheetsConfigStore.spreadsheetUrl(destination.spreadsheetId()), destination.sheetName(),
                sheetRange + "!A" + firstWriteRow + ":H" + lastWriteRow, rows.size(), false);
    }

    private AppendResponse previousResult(Destination destination, JsonNode matches, String hash, int count) {
        if (matches.size() != 1) {
            throw new IllegalStateException("Hay más de un marcador para esta carga. Revisá la planilla antes de continuar.");
        }
        String[] marker = matches.get(0).path("developerMetadata").path("metadataValue").asText().split(":");
        if (marker.length != 4 || !marker[0].equals(hash)) {
            throw new IllegalArgumentException("Este identificador ya se usó con otros datos o destino. Generá una nueva carga para los cambios.");
        }
        try {
            int first = Integer.parseInt(marker[2]);
            int last = Integer.parseInt(marker[3]);
            if (first <= destination.headerRow() || last - first + 1 != count) {
                throw new NumberFormatException();
            }
            return new AppendResponse(SheetsConfigStore.spreadsheetUrl(destination.spreadsheetId()), destination.sheetName(),
                    quoteSheet(destination.sheetName()) + "!A" + first + ":H" + last, count, true);
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("El marcador de una carga anterior está dañado. Revisá la planilla antes de continuar.");
        }
    }

    private ConfigResponse response(Settings settings) {
        GoogleServiceAccountAuth.CredentialStatus status = auth.status();
        return new ConfigResponse(settings.spreadsheetId(), SheetsConfigStore.spreadsheetUrl(settings.spreadsheetId()),
                settings.year(), settings.headerRow(), settings.monthSheets(), config.legacySheetName(), config.version(),
                status.configured(), status.email(), status.message());
    }

    private Settings configuredSettings() {
        Settings settings = config.get();
        if (settings.spreadsheetId().isBlank()) {
            throw new IllegalArgumentException("Primero guardá el Google Sheet y el año de la planilla.");
        }
        return settings;
    }

    private SheetContext inspect(Destination destination) {
        return inspect(destination, client.metadata(destination.spreadsheetId()));
    }

    private SheetContext inspect(Destination destination, JsonNode metadata) {
        List<String> names = new ArrayList<>();
        JsonNode selected = null;
        for (JsonNode sheet : metadata.path("sheets")) {
            String name = sheet.path("properties").path("title").asText();
            names.add(name);
            if (destination.sheetName().equals(name)) {
                selected = sheet;
            }
        }
        if (selected == null) {
            throw new IllegalArgumentException("No existe la pestaña '" + destination.sheetName()
                    + "'. Pestañas disponibles: " + String.join(", ", names));
        }
        JsonNode properties = selected.path("properties");
        for (JsonNode table : selected.path("tables")) {
            if (productTableRange(table.path("range"), destination.headerRow())
                    && table.path("rowsProperties").has("footerColorStyle")) {
                throw new IllegalArgumentException("La tabla de productos tiene un pie de totales. Usá una tabla sin pie y mantené el resumen en una columna lateral, como en la plantilla.");
            }
        }
        int rowCount = properties.path("gridProperties").path("rowCount").asInt();
        int columnCount = properties.path("gridProperties").path("columnCount").asInt();
        if (!"GRID".equals(properties.path("sheetType").asText("GRID"))
                || rowCount <= destination.headerRow() || columnCount < HEADERS.size()) {
            throw new IllegalArgumentException("La pestaña debe ser una cuadrícula con encabezados A:I y filas para cargar items.");
        }
        JsonNode header = client.values(destination.spreadsheetId(), quoteSheet(destination.sheetName())
                + "!A" + destination.headerRow() + ":I" + destination.headerRow()).path("values").path(0);
        for (int column = 0; column < HEADERS.size(); column++) {
            if (!normalizeHeader(cell(header, column).asText()).equals(normalizeHeader(HEADERS.get(column)))) {
                throw new IllegalArgumentException("Los encabezados de la fila " + destination.headerRow()
                        + " deben ser A:I: " + String.join(" | ", HEADERS) + ". Revisá la pestaña y la fila configuradas.");
            }
        }
        return new SheetContext(metadata.path("properties").path("title").asText(), selected,
                properties.path("sheetId").asInt(), rowCount, List.copyOf(names));
    }

    static Map<String, String> suggestMonths(List<String> names, Integer year) {
        List<String> months = List.of("enero", "febrero", "marzo", "abril", "mayo", "junio",
                "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre");
        Map<String, String> suggestions = new LinkedHashMap<>();
        for (int month = 1; month <= 12; month++) {
            String monthName = months.get(month - 1);
            List<String> candidates = names.stream().filter(name -> {
                String normalized = normalizeHeader(name).replaceAll("[\\s_-]+", "");
                return normalized.equals(monthName) || (year != null
                        && (normalized.equals(monthName + year) || normalized.equals(year + monthName)));
            }).toList();
            if (candidates.size() == 1) suggestions.put(Integer.toString(month), candidates.get(0));
        }
        return java.util.Collections.unmodifiableMap(suggestions);
    }

    static List<List<Object>> validatedRows(List<ReceiptItem> items) {
        if (items == null || items.isEmpty() || items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("Seleccioná entre 1 y 500 items para cargar.");
        }
        List<List<Object>> rows = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            ReceiptItem item = items.get(index);
            if (item == null) {
                throw new IllegalArgumentException("El item " + (index + 1) + " está vacío.");
            }
            try {
                String description = text(item.descripcion(), true);
                String brand = text(item.marca(), false);
                String store = text(item.lugarDeCompra(), false);
                String category = text(item.categoria(), false);
                BigDecimal quantity = number(item.cantidad(), false);
                BigDecimal price = number(item.precioUnitario(), true);
                String date = item.fecha() == null ? "" : item.fecha().trim();
                if (!date.matches("\\d{1,2}/\\d{1,2}/\\d{4}")) {
                    throw new IllegalArgumentException("La fecha debe usar día/mes/año.");
                }
                LocalDate parsed = LocalDate.parse(date, DATE_FORMAT);
                if (parsed.getYear() < 1900 || parsed.getYear() > 9999) {
                    throw new IllegalArgumentException("La fecha debe estar entre 1900 y 9999.");
                }
                rows.add(List.of(description, brand, store, category, quantity, price,
                        ChronoUnit.DAYS.between(SHEETS_EPOCH, parsed)));
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException("Item " + (index + 1) + ": la fecha no existe o no usa día/mes/año.");
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Item " + (index + 1) + ": " + ex.getMessage());
            }
        }
        return List.copyOf(rows);
    }

    private static String text(String value, boolean required) {
        String clean = value == null ? "" : value.trim();
        if ((required && clean.isBlank()) || clean.length() > 1000 || clean.matches(".*[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f].*")) {
            throw new IllegalArgumentException("Completá descripción, marca, comercio y categoría con textos de hasta 1000 caracteres.");
        }
        return clean;
    }

    static BigDecimal number(String value, boolean price) {
        String clean = value == null ? "" : value.replaceAll("\\s", "");
        if (price) {
            clean = clean.replaceFirst("^\\$", "");
        }
        if (clean.length() > 64) {
            throw new IllegalArgumentException("Cantidad o precio demasiado largo.");
        }
        if (price && clean.matches("[+-]?\\d{1,3}(\\.\\d{3})+(,\\d+)?")) {
            clean = clean.replace(".", "").replace(',', '.');
        } else if (clean.matches("[+-]?(?:\\d+(?:[.,]\\d+)?|[.,]\\d+)")) {
            clean = clean.replace(',', '.');
        } else {
            throw new IllegalArgumentException("Cantidad y precio deben ser números válidos; usá 0,875 para cantidades y 1.234,56 para precios.");
        }
        BigDecimal number = new BigDecimal(clean).stripTrailingZeros();
        if ((!price && number.signum() < 0) || number.abs().compareTo(new BigDecimal("1000000000")) > 0) {
            throw new IllegalArgumentException("La cantidad no puede ser negativa; el máximo absoluto por campo es 1.000.000.000.");
        }
        return number;
    }

    private String payloadHash(Destination destination, List<List<Object>> rows) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(List.of(destination.spreadsheetId(), destination.sheetName(), destination.headerRow(), rows));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo preparar el identificador seguro de la carga.");
        }
    }

    private static boolean occupied(JsonNode row, int rowNumber) {
        for (int column = 0; column < 7; column++) {
            if (!cell(row, column).asText("").isBlank()) {
                return true;
            }
        }
        if (!cell(row, 8).asText("").isBlank()) {
            return true;
        }
        String total = cell(row, 7).asText("");
        return !total.isBlank() && !expectedTotalFormula(total, rowNumber);
    }

    private static boolean expectedTotalFormula(String formula, int rowNumber) {
        return formula.replace("$", "").replaceAll("\\s", "").equalsIgnoreCase("=E" + rowNumber + "*F" + rowNumber);
    }

    private static JsonNode cell(JsonNode row, int column) {
        return row == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : row.path(column);
    }

    private static String normalizeHeader(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String quoteSheet(String name) {
        return "'" + name.replace("'", "''") + "'";
    }

    private static Object numberFormat(int sheetId, int firstRow, int lastRow, int column, String type, String pattern) {
        return Map.of("repeatCell", Map.of("range", Map.of("sheetId", sheetId, "startRowIndex", firstRow - 1,
                        "endRowIndex", lastRow, "startColumnIndex", column, "endColumnIndex", column + 1),
                "cell", Map.of("userEnteredFormat", Map.of("numberFormat", Map.of("type", type, "pattern", pattern))),
                "fields", "userEnteredFormat.numberFormat"));
    }

    private static void validateTargetRanges(JsonNode sheet, int firstRowIndex, int endRowIndex) {
        for (JsonNode merge : sheet.path("merges")) {
            if (intersects(merge, firstRowIndex, endRowIndex)) {
                throw new IllegalArgumentException("El rango de carga contiene celdas combinadas. Elegí una tabla de productos sin celdas combinadas en A:H.");
            }
        }
        for (JsonNode protection : sheet.path("protectedRanges")) {
            if (!protection.path("warningOnly").asBoolean(false)
                    && !protection.path("requestingUserCanEdit").asBoolean(false)
                    && intersects(protection.path("range"), firstRowIndex, endRowIndex)) {
                throw new IllegalArgumentException("El rango de carga está protegido. Permití editar A:H a la cuenta de servicio.");
            }
        }
    }

    private static boolean intersects(JsonNode range, int firstRowIndex, int endRowIndex) {
        return range.path("startRowIndex").asInt(0) < endRowIndex
                && range.path("endRowIndex").asInt(Integer.MAX_VALUE) > firstRowIndex
                && range.path("startColumnIndex").asInt(0) < 8
                && range.path("endColumnIndex").asInt(Integer.MAX_VALUE) > 0;
    }

    private static void extendTablesAndFilter(JsonNode sheet, int headerRow, int lastRow, List<Object> requests) {
        JsonNode filter = sheet.path("basicFilter");
        JsonNode range = filter.path("range");
        int end = range.path("endRowIndex").asInt();
        if (productTableRange(range, headerRow) && end > 0 && lastRow > end) {
            com.fasterxml.jackson.databind.node.ObjectNode updated = filter.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) updated.path("range")).put("endRowIndex", lastRow);
            requests.add(Map.of("setBasicFilter", Map.of("filter", updated)));
        }
        for (JsonNode table : sheet.path("tables")) {
            JsonNode tableRange = table.path("range");
            if (productTableRange(tableRange, headerRow) && lastRow > tableRange.path("endRowIndex").asInt()) {
                com.fasterxml.jackson.databind.node.ObjectNode extended = tableRange.deepCopy();
                extended.put("endRowIndex", lastRow);
                requests.add(Map.of("updateTable", Map.of("table", Map.of("tableId", table.path("tableId").asText(),
                        "range", extended), "fields", "range")));
            }
        }
    }

    private static boolean productTableRange(JsonNode range, int headerRow) {
        return range.isObject() && range.path("startRowIndex").asInt(0) == headerRow - 1
                && range.path("startColumnIndex").asInt(0) == 0
                && range.path("endColumnIndex").asInt() == HEADERS.size();
    }

    private record SheetContext(String title, JsonNode sheet, int sheetId, int rowCount, List<String> availableSheets) { }
}
