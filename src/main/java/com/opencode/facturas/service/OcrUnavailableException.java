package com.opencode.facturas.service;

public class OcrUnavailableException extends IllegalStateException {

    public OcrUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
