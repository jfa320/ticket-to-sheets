package com.opencode.facturas.model;

import java.util.List;
import java.util.Collections;

public record ExtractResponse(
        String storeName,
        String date,
        int itemCount,
        String total,
        String csv,
        String tsv,
        String tsvWithoutHeader,
        String rawText,
        List<ReceiptItem> items,
        String variant,
        Double score,
        List<String> warnings,
        List<OcrReviewPage> ocrReview,
        String subtotal,
        String discountTotal,
        List<ReceiptDiscount> discounts
) {
    public ExtractResponse {
        ocrReview = ocrReview == null ? List.of() : List.copyOf(ocrReview);
        discounts = discounts == null ? List.of() : List.copyOf(discounts);
    }

    public ExtractResponse(String storeName, String date, int itemCount, String total,
                           String csv, String tsv, String tsvWithoutHeader, String rawText,
                           List<ReceiptItem> items, String variant, Double score, List<String> warnings,
                           List<OcrReviewPage> ocrReview) {
        this(storeName, date, itemCount, total, csv, tsv, tsvWithoutHeader, rawText,
                items, variant, score, warnings, ocrReview, total, "0,00", List.of());
    }

    public ExtractResponse(String storeName, String date, int itemCount, String total,
                           String csv, String tsv, String tsvWithoutHeader, String rawText,
                           List<ReceiptItem> items, String variant, Double score, List<String> warnings) {
        this(storeName, date, itemCount, total, csv, tsv, tsvWithoutHeader, rawText,
                items, variant, score, warnings, List.of());
    }

    public ExtractResponse(
            String storeName,
            String date,
            int itemCount,
            String csv,
            String tsv,
            String tsvWithoutHeader,
            String rawText,
            List<ReceiptItem> items
    ) {
        this(storeName, date, itemCount, "", csv, tsv, tsvWithoutHeader, rawText, items, null, null, Collections.emptyList());
    }

    public ExtractResponse(
            String storeName,
            String date,
            int itemCount,
            String csv,
            String tsv,
            String tsvWithoutHeader,
            String rawText,
            List<ReceiptItem> items,
            String variant,
            Double score
    ) {
        this(storeName, date, itemCount, "", csv, tsv, tsvWithoutHeader, rawText, items, variant, score, Collections.emptyList());
    }

    public ExtractResponse(
            String storeName,
            String date,
            int itemCount,
            String total,
            String csv,
            String tsv,
            String tsvWithoutHeader,
            String rawText,
            List<ReceiptItem> items
    ) {
        this(storeName, date, itemCount, total, csv, tsv, tsvWithoutHeader, rawText, items, null, null, Collections.emptyList());
    }

    public ExtractResponse(
            String storeName,
            String date,
            int itemCount,
            String total,
            String csv,
            String tsv,
            String tsvWithoutHeader,
            String rawText,
            List<ReceiptItem> items,
            String variant,
            Double score
    ) {
        this(storeName, date, itemCount, total, csv, tsv, tsvWithoutHeader, rawText, items, variant, score, Collections.emptyList());
    }

    public ExtractResponse withOcrMetadata(String variant, Double score) {
        return new ExtractResponse(storeName, date, itemCount, total, csv, tsv, tsvWithoutHeader, rawText, items, variant, score, warnings, ocrReview, subtotal, discountTotal, discounts);
    }

    public ExtractResponse withOcrReview(List<OcrReviewPage> review) {
        return new ExtractResponse(storeName, date, itemCount, total, csv, tsv, tsvWithoutHeader, rawText, items, variant, score, warnings, review, subtotal, discountTotal, discounts);
    }
}
