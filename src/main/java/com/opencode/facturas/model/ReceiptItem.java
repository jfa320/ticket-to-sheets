package com.opencode.facturas.model;

public record ReceiptItem(
        String descripcion,
        String marca,
        String lugarDeCompra,
        String categoria,
        String cantidad,
        String precioUnitario,
        String fecha,
        String estado,
        String firma
) {
    public ReceiptItem(
            String descripcion,
            String marca,
            String lugarDeCompra,
            String categoria,
            String cantidad,
            String precioUnitario,
            String fecha,
            String estado
    ) {
        this(descripcion, marca, lugarDeCompra, categoria, cantidad, precioUnitario, fecha, estado, "");
    }

    public ReceiptItem(
            String descripcion,
            String marca,
            String lugarDeCompra,
            String categoria,
            String cantidad,
            String precioUnitario,
            String fecha
    ) {
        this(descripcion, marca, lugarDeCompra, categoria, cantidad, precioUnitario, fecha, "CORRECT", "");
    }
}
