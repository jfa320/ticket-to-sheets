package com.opencode.facturas.service;

public class ExtractionCancelledException extends RuntimeException {
    public ExtractionCancelledException() {
        super("Se canceló el procesamiento de la factura.");
    }
}
