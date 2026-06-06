package com.opencode.facturas.model;

import java.util.List;

public record ExtractResponse(
        String storeName,
        String date,
        int itemCount,
        String csv,
        String tsv,
        String tsvWithoutHeader,
        String rawText,
        List<ReceiptItem> items
) {
}
