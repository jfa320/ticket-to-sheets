package com.opencode.facturas.service;

public class CorrectionConflictException extends RuntimeException {
    public CorrectionConflictException() {
        super("La corrección cambió o ya no existe. Actualizá la lista antes de volver a editarla.");
    }
}
