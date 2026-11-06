package com.opencode.facturas.service;

public class LearningConflictException extends RuntimeException {
    public LearningConflictException() { super("El aprendizaje cambió desde la vista previa. Volvé a seleccionar el respaldo para revisarlo antes de restaurar."); }
}
