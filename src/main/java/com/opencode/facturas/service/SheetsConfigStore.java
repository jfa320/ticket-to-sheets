package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.opencode.facturas.model.SheetsModels.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SheetsConfigStore {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{20,200}");
    private static final Pattern DOCUMENT_PATH = Pattern.compile("/spreadsheets/d/([A-Za-z0-9_-]{20,200})(?:/.*)?");
    private final ObjectMapper mapper;
    private final Path path;
    private Settings settings;
    private String legacySheetName = "";

    public SheetsConfigStore(ObjectMapper mapper,
                             @Value("${app.sheets.config-path:data/sheets-config.json}") String path) {
        this.mapper = mapper;
        this.path = Path.of(path);
        // Read lazily: a damaged optional configuration must not prevent local OCR from starting.
    }

    public synchronized Settings get() {
        if (settings == null) {
            try {
                JsonNode loaded = AtomicJsonFile.loadOrRecover(mapper, path,
                        mapper.constructType(JsonNode.class), mapper.createObjectNode());
                if (loaded == null || !loaded.isObject()) throw new IllegalArgumentException();
                String id = loaded.path("spreadsheetId").asText("");
                if (id.isBlank()) {
                    settings = new Settings("", null, 2, Map.of());
                } else if (loaded.has("sheetName") && !loaded.has("year")) {
                    // Migration stays in memory until the user saves a year and monthly associations.
                    legacySheetName = validateSheetName(loaded.path("sheetName").asText(""));
                    settings = new Settings(extractId(id), null, validateHeader(loaded.path("headerRow").asInt(2)), Map.of());
                } else {
                    Settings saved = mapper.treeToValue(loaded, Settings.class);
                    settings = validate(new ConfigRequest(id, saved.year(), saved.headerRow(), saved.monthSheets()));
                }
            } catch (IOException | RuntimeException ex) {
                throw new IllegalStateException("No se pudo leer la configuración de Sheets. Revisá data/sheets-config.json y su copia .bak.");
            }
        }
        return settings;
    }

    public synchronized Settings save(ConfigRequest request) {
        Settings validated = validate(request);
        try {
            AtomicJsonFile.save(mapper, path, validated);
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo guardar la configuración local de Sheets.");
        }
        settings = validated;
        legacySheetName = "";
        return settings;
    }

    public synchronized String legacySheetName() {
        get();
        return legacySheetName;
    }

    public synchronized String version() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(get())));
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo identificar la configuración de Sheets.");
        }
    }

    public synchronized boolean matches(ConfigReference expected) {
        Settings current = get();
        return expected != null && !current.spreadsheetId().isBlank()
                && current.spreadsheetId().equals(expected.spreadsheetId()) && current.headerRow() == expected.headerRow()
                && version().equals(expected.configVersion());
    }

    static Settings validate(ConfigRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Indicá el enlace o ID del Google Sheet.");
        }
        String id = extractId(request.spreadsheetUrlOrId());
        if (request.year() == null || request.year() < 1900 || request.year() > 9999) {
            throw new IllegalArgumentException("Indicá el año de la planilla entre 1900 y 9999.");
        }
        int headerRow = validateHeader(request.headerRow() == null ? 2 : request.headerRow());
        Map<String, String> months = new LinkedHashMap<>();
        Map<String, String> requested = request.monthSheets() == null ? Map.of() : request.monthSheets();
        for (String month : requested.keySet()) {
            if (month == null || !month.matches("[1-9]|1[0-2]"))
                throw new IllegalArgumentException("Las asociaciones mensuales deben usar meses del 1 al 12.");
        }
        // Canonical month order makes the version independent of JSON property order.
        for (int month = 1; month <= 12; month++) {
            String name = requested.get(Integer.toString(month));
            if (name != null && !name.isBlank()) months.put(Integer.toString(month), validateSheetName(name));
        }
        return new Settings(id, request.year(), headerRow, Collections.unmodifiableMap(months));
    }

    static String validateSheetName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isBlank() || name.length() > 100 || name.matches(".*[\\x00-\\x1f].*"))
            throw new IllegalArgumentException("Indicá el nombre exacto de la pestaña (hasta 100 caracteres).");
        return name;
    }

    private static int validateHeader(int headerRow) {
        if (headerRow < 1 || headerRow > 1000) {
            throw new IllegalArgumentException("La fila de encabezados debe estar entre 1 y 1000.");
        }
        return headerRow;
    }

    static String extractId(String value) {
        String input = value == null ? "" : value.trim();
        if (ID.matcher(input).matches()) {
            return input;
        }
        try {
            URI uri = URI.create(input);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"docs.google.com".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null || uri.getPort() != -1) {
                throw new IllegalArgumentException();
            }
            Matcher matcher = DOCUMENT_PATH.matcher(uri.getPath());
            if (matcher.matches()) {
                return matcher.group(1);
            }
        } catch (IllegalArgumentException ignored) {
            // All rejected inputs produce the same safe message.
        }
        throw new IllegalArgumentException("Usá un ID de Google Sheets o un enlace https://docs.google.com/spreadsheets/d/…");
    }

    static String spreadsheetUrl(String id) {
        return id.isBlank() ? "" : "https://docs.google.com/spreadsheets/d/" + id + "/edit";
    }
}
