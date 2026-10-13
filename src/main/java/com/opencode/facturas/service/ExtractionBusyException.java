package com.opencode.facturas.service;

public class ExtractionBusyException extends IllegalStateException {

    public ExtractionBusyException() {
        super("Ya hay una factura en procesamiento. Esperá a que termine y reintentá.");
    }
}
