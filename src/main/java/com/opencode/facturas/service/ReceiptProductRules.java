package com.opencode.facturas.service;

import com.opencode.facturas.model.ReceiptItem;

/** ALMACEN is a supermarket's manual-entry label, not a reusable product identity. */
final class ReceiptProductRules {
    private static final ReceiptLineAnalyzer LINES = new ReceiptLineAnalyzer();

    private ReceiptProductRules() { }

    static boolean isAlmacen(String signature) {
        return signature != null && "almacen".equals(LINES.normalize(signature));
    }

    static boolean isAlmacen(ReceiptItem item) {
        return isAlmacen(item.firma() == null || item.firma().isBlank() ? item.descripcion() : item.firma());
    }

    static ReceiptItem almacen(ReceiptItem item) {
        return new ReceiptItem("Almacén", "Genérico", item.lugarDeCompra(), item.categoria(),
                item.cantidad(), item.precioUnitario(), item.fecha(), item.estado(), item.firma());
    }
}
