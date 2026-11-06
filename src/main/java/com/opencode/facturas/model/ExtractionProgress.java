package com.opencode.facturas.model;

/** Metadatos temporales; nunca incluye nombres, texto OCR ni productos. */
public record ExtractionProgress(String status, String phase, int pageNumber, int pageCount, long elapsedMillis) { }
