package com.opencode.facturas.service;

public class ExtractionTimeoutException extends RuntimeException {
    public ExtractionTimeoutException() {
        super("El PDF superó el presupuesto total de procesamiento. No se devolvieron resultados parciales. "
                + "Dividí el archivo en menos páginas o aumentá app.pdf.processing-budget-seconds.");
    }
}
