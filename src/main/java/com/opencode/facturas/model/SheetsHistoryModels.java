package com.opencode.facturas.model;

import java.util.List;

public final class SheetsHistoryModels {
    private SheetsHistoryModels() { }

    public record Product(String descripcion, String marca, String comercio, String categoria) { }

    public record Snapshot(boolean enabled, String spreadsheetId, int headerRow, String updatedAt,
                           int rowCount, List<String> sheets, List<String> skippedSheets, List<Product> products) {
        public static Snapshot empty() {
            return new Snapshot(false, "", 2, "", 0, List.of(), List.of(), List.of());
        }
    }

    public record HistoryStatus(boolean active, String spreadsheetId, String updatedAt, int rowCount,
                                int productCount, List<String> sheets, List<String> skippedSheets, String message) { }
}
