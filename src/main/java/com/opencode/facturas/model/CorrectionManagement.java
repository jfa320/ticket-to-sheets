package com.opencode.facturas.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class CorrectionManagement {
    private CorrectionManagement() { }
    public record ManagedCorrection(String store, String firma, String descripcion, String marca,
                                    String categoria, int veces, String ultimaVez, String version) { }
    public record Page(List<ManagedCorrection> entries, int total, int offset, int limit) { }
    public record Edit(@NotBlank @Size(max=1000) String store, @NotBlank @Size(max=1000) String firma,
                       @NotBlank String version, @NotBlank @Size(max=1000) String descripcion,
                       @NotBlank @Size(max=1000) String marca, @Size(max=1000) String categoria) { }
    public record Remove(@NotBlank @Size(max=1000) String store, @NotBlank @Size(max=1000) String firma,
                         @NotBlank String version) { }
}
