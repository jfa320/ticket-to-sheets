package com.opencode.facturas.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record CorrectionsRequest(
        @NotBlank String store,
        @Valid List<Correction> corrections
) {
    public record Correction(
            @NotBlank String firma,
            String descripcion,
            String marca,
            String categoria,
            String marcaOriginal
    ) {
        public Correction(String firma, String descripcion, String marca, String categoria) {
            this(firma, descripcion, marca, categoria, null);
        }
    }
}
