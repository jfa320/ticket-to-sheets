package com.opencode.facturas.util;

import com.opencode.facturas.model.ReceiptItem;

import java.util.ArrayList;
import java.util.List;

public final class DelimitedExporter {

    private static final List<String> HEADERS = List.of(
            "Descripción",
            "Marca",
            "Lugar de compra",
            "Categoria",
            "Cantidad",
            "Precio unitario",
            "Fecha"
    );

    private DelimitedExporter() {
    }

    public static String toPipeSeparated(List<ReceiptItem> items) {
        return export(items, "|", true);
    }

    public static String toTabSeparated(List<ReceiptItem> items) {
        return export(items, "\t", true);
    }

    public static String toTabSeparatedWithoutHeader(List<ReceiptItem> items) {
        return export(items, "\t", false);
    }

    private static String export(List<ReceiptItem> items, String delimiter, boolean includeHeader) {
        List<String> rows = new ArrayList<>();
        if (includeHeader) {
            rows.add(String.join(delimiter, HEADERS));
        }

        for (ReceiptItem item : items) {
            rows.add(String.join(delimiter,
                    escape(item.descripcion(), delimiter),
                    escape(item.marca(), delimiter),
                    escape(item.lugarDeCompra(), delimiter),
                    escape(item.categoria(), delimiter),
                    String.valueOf(item.cantidad()),
                    escape(item.precioUnitario(), delimiter),
                    escape(item.fecha(), delimiter)
            ));
        }

        return String.join("\n", rows);
    }

    private static String escape(String value, String delimiter) {
        String safe = value == null ? "" : value;
        if (safe.contains(delimiter) || safe.contains("\n") || safe.contains("\"") ) {
            return '"' + safe.replace("\"", "\"\"") + '"';
        }
        return safe;
    }
}
