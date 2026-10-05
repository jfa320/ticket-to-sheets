package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.facturas.model.ReceiptItem;
import com.opencode.facturas.model.SheetsHistoryModels.*;
import com.opencode.facturas.model.SheetsModels.ConfigReference;
import com.opencode.facturas.model.SheetsModels.Settings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

@Service
public class SheetsHistoryService {
    private static final int MAX_CELLS = 50000;
    private static final List<String> HEADERS = List.of("descripcion", "marca", "lugar de compra", "categoria");
    private final ObjectMapper mapper;
    private final SheetsConfigStore config;
    private final GoogleSheetsClient client;
    private final StoreNameMapper stores;
    private final Path path;
    private Snapshot snapshot;
    private HistoryProductMatcher matcher;
    private String loadError = "";

    public SheetsHistoryService(ObjectMapper mapper, SheetsConfigStore config, GoogleSheetsClient client,
                                StoreNameMapper stores,
                                @Value("${app.sheets.history-path:data/sheets-history.json}") String path) {
        this.mapper = mapper; this.config = config; this.client = client; this.stores = stores; this.path = Path.of(path);
    }

    public synchronized HistoryStatus status() {
        load();
        boolean active = active();
        String message = !loadError.isBlank() ? loadError : active ? "El historial se usa en las próximas extracciones."
                : snapshot.enabled() ? "El catálogo guardado pertenece a otro archivo o fila de encabezados. Actualizá el historial para este destino."
                : "El historial está desactivado.";
        return new HistoryStatus(active, snapshot.spreadsheetId(), snapshot.updatedAt(), snapshot.rowCount(),
                snapshot.products().size(), snapshot.sheets(), snapshot.skippedSheets(), message);
    }

    public synchronized HistoryStatus sync(ConfigReference expected) {
        Settings destination = config.get();
        if (!config.matches(expected))
            throw new IllegalArgumentException("El destino cambió o no está configurado. Guardá y comprobá el destino antes de actualizar el historial.");
        JsonNode metadata = client.metadata(destination.spreadsheetId());
        JsonNode sheets = metadata.path("sheets");
        if (!sheets.isArray()) throw new IllegalStateException("Google Sheets no devolvió las pestañas del historial.");
        List<String> included = new ArrayList<>(), skipped = new ArrayList<>();
        List<Tab> compatible = new ArrayList<>();
        long cells = 0;
        for (JsonNode sheet : sheets) {
            JsonNode properties = sheet.path("properties");
            String title = properties.path("title").asText("");
            int rows = properties.path("gridProperties").path("rowCount").asInt(0);
            int columns = properties.path("gridProperties").path("columnCount").asInt(0);
            if (title.isBlank() || !properties.path("sheetType").asText("GRID").equals("GRID")
                    || rows < destination.headerRow() || columns < 4) { skipped.add(title); continue; }
            cells += 4;
            checkBudget(cells);
            JsonNode header = client.historyValues(destination.spreadsheetId(), quote(title) + "!A"
                    + destination.headerRow() + ":D" + destination.headerRow()).path("values");
            if (!validHeader(header)) { skipped.add(title); continue; }
            cells += (long) (rows - destination.headerRow()) * 4;
            checkBudget(cells);
            compatible.add(new Tab(title, rows));
        }
        if (compatible.isEmpty()) throw new IllegalArgumentException("No hay pestañas con las cuatro columnas del historial en la fila de encabezados configurada.");
        Map<String, Product> products = new LinkedHashMap<>();
        int rowCount = 0;
        for (Tab tab : compatible) {
            included.add(tab.title());
            if (tab.rows() == destination.headerRow()) continue;
            JsonNode values = client.historyValues(destination.spreadsheetId(), quote(tab.title()) + "!A"
                    + (destination.headerRow() + 1) + ":D" + tab.rows()).path("values");
            if (values.isMissingNode()) continue; // Google omits values for an empty range.
            if (!values.isArray() || values.size() > tab.rows() - destination.headerRow())
                throw new IllegalStateException("Google Sheets devolvió filas de historial inválidas.");
            for (JsonNode row : values) {
                Product product = product(row);
                if (product == null) continue;
                rowCount++;
                String key = HistoryProductMatcher.normalize(product.descripcion()) + "|"
                        + HistoryProductMatcher.normalize(product.marca()) + "|"
                        + HistoryProductMatcher.normalize(product.comercio()) + "|"
                        + HistoryProductMatcher.normalize(product.categoria());
                products.putIfAbsent(key, product);
            }
        }
        // A different browser may have changed the destination while the read was in flight.
        if (!config.matches(expected)) throw new IllegalArgumentException("El destino cambió durante la lectura. Volvé a actualizar el historial.");
        Snapshot next = new Snapshot(true, destination.spreadsheetId(), destination.headerRow(), Instant.now().toString(),
                rowCount, List.copyOf(included), List.copyOf(skipped), List.copyOf(products.values()));
        save(next);
        return status();
    }

    public synchronized HistoryStatus disable() {
        save(Snapshot.empty());
        return status();
    }

    public synchronized List<ReceiptItem> enrich(List<ReceiptItem> items, List<String> warnings) {
        load();
        if (!active()) return items;
        return items.stream().map(item -> matcher.enrich(item, warnings)).toList();
    }

    private boolean active() {
        if (!snapshot.enabled()) return false;
        try {
            Settings destination = config.get();
            return snapshot.spreadsheetId().equals(destination.spreadsheetId()) && snapshot.headerRow() == destination.headerRow();
        } catch (RuntimeException ignored) { return false; }
    }

    private void load() {
        if (snapshot != null) return;
        try {
            if (Files.exists(path) && Files.size(path) > 16000000) throw new IOException("Historical cache too large");
            Snapshot loaded = AtomicJsonFile.loadOrRecover(mapper, path, mapper.constructType(Snapshot.class), Snapshot.empty());
            if (loaded == null || loaded.products() == null || loaded.sheets() == null || loaded.skippedSheets() == null
                    || loaded.spreadsheetId() == null || loaded.updatedAt() == null || loaded.rowCount() < 0
                    || loaded.products().size() > MAX_CELLS / 4 || loaded.headerRow() < 1 || loaded.headerRow() > 1000)
                throw new IllegalStateException();
            for (Product product : loaded.products()) {
                if (product == null || !validText(product.descripcion()) || product.descripcion().isBlank()
                        || !validText(product.marca()) || !validText(product.comercio()) || product.comercio().isBlank()
                        || !validText(product.categoria())) throw new IllegalStateException();
            }
            snapshot = loaded;
        } catch (IOException | RuntimeException ignored) {
            snapshot = Snapshot.empty();
            loadError = "No se pudo leer el catálogo local. Actualizá el historial para repararlo; la extracción sigue disponible.";
        }
        matcher = new HistoryProductMatcher(snapshot.products());
    }

    private void save(Snapshot next) {
        try { AtomicJsonFile.save(mapper, path, next); }
        catch (IOException ex) { throw new IllegalStateException("No se pudo guardar el catálogo histórico local. Se conserva la versión anterior."); }
        snapshot = next; matcher = new HistoryProductMatcher(next.products()); loadError = "";
    }

    private Product product(JsonNode row) {
        if (!row.isArray()) return null;
        List<String> fields = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            JsonNode cell = row.path(i);
            if (!cell.isMissingNode() && !cell.isNull() && !cell.isTextual()) return null;
            String text = cell.asText("").trim().replaceAll("\\s+", " ");
            if (!validText(text)) return null;
            fields.add(text);
        }
        if (fields.get(0).isBlank() || fields.get(2).isBlank()) return null;
        String store = stores.resolve(List.of(fields.get(2)), fields.get(2));
        return new Product(fields.get(0), fields.get(1), store, fields.get(3));
    }

    private static boolean validText(String value) { return value != null && value.length() <= 1000 && !value.matches(".*[\\x00-\\x1f].*"); }
    private static boolean validHeader(JsonNode rows) {
        if (!rows.isArray() || rows.isEmpty() || !rows.get(0).isArray()) return false;
        for (int i = 0; i < 4; i++) if (!HistoryProductMatcher.normalize(rows.get(0).path(i).asText("")).equals(HEADERS.get(i))) return false;
        return true;
    }
    private static void checkBudget(long cells) {
        if (cells > MAX_CELLS) throw new IllegalArgumentException("El historial supera el límite de 50.000 celdas. Usá un archivo con menos filas físicas; se conserva el catálogo anterior.");
    }
    private static String quote(String title) { return "'" + title.replace("'", "''") + "'"; }
    private record Tab(String title, int rows) { }
}
