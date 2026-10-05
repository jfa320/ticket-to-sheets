package com.opencode.facturas.model;

import java.util.List;
import java.util.Map;

public final class SheetsModels {
    private SheetsModels() { }

    public record ConfigRequest(String spreadsheetUrlOrId, Integer year, Integer headerRow,
                                Map<String, String> monthSheets) { }

    public record Settings(String spreadsheetId, Integer year, int headerRow, Map<String, String> monthSheets) { }

    public record ConfigReference(String spreadsheetId, int headerRow, String configVersion) { }

    public record Destination(String spreadsheetId, String sheetName, int headerRow) { }

    public record ConfigResponse(String spreadsheetId, String spreadsheetUrl, Integer year,
                                 int headerRow, Map<String, String> monthSheets, String legacySheetName,
                                 String configVersion, boolean credentialsConfigured,
                                 String serviceAccountEmail, String message) { }

    public record CheckResponse(String spreadsheetId, String spreadsheetTitle, int headerRow, String configVersion,
                                List<String> availableSheets, Map<String, String> suggestedMonthSheets,
                                List<String> unavailableSheets) { }

    public record AppendRequest(String requestId, List<ReceiptItem> items, Destination destination,
                                String configVersion) { }

    public record AppendResponse(String spreadsheetUrl, String sheetName, String updatedRange,
                                 int appendedRows, boolean duplicate) { }
}
