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
        String firma,
        boolean memorizado,
        java.util.List<ReceiptSource> fuentesOcr
) {
    public ReceiptItem {
        memorizado = memorizado || "LEARNED".equals(estado);
        fuentesOcr = fuentesOcr == null ? java.util.List.of() : java.util.List.copyOf(fuentesOcr);
    }

    public ReceiptItem(String descripcion, String marca, String lugarDeCompra, String categoria,
                       String cantidad, String precioUnitario, String fecha, String estado, String firma, boolean memorizado) {
        this(descripcion, marca, lugarDeCompra, categoria, cantidad, precioUnitario, fecha, estado, firma, memorizado, java.util.List.of());
    }

    public ReceiptItem withSources(java.util.List<ReceiptSource> sources) {
        return new ReceiptItem(descripcion, marca, lugarDeCompra, categoria, cantidad, precioUnitario, fecha, estado, firma, memorizado, sources);
    }

    public ReceiptItem(String descripcion, String marca, String lugarDeCompra, String categoria,
                       String cantidad, String precioUnitario, String fecha, String estado, String firma) {
        this(descripcion, marca, lugarDeCompra, categoria, cantidad, precioUnitario, fecha, estado, firma, false);
    }

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
