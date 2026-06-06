package com.opencode.facturas.model;

public record ReceiptItem(
        String descripcion,
        String marca,
        String lugarDeCompra,
        String categoria,
        int cantidad,
        String precioUnitario,
        String fecha,
        String precioTotal
) {
}
