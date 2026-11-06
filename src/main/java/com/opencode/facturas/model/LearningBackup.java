package com.opencode.facturas.model;

import com.opencode.facturas.service.CorrectionMemory;
import java.util.List;

public final class LearningBackup {
    private LearningBackup() { }
    public record Document(String format, int schemaVersion, List<CorrectionMemory.Entry> corrections, List<String> brands) { }
    public record Sample(String store, String descripcion) { }
    public record Preview(String expectedVersion, String backupHash, int correctionsToAdd, int correctionsKept,
                          int conflicts, int brandsToAdd, List<Sample> sample) { }
    public record Result(int correctionsAdded, int correctionsKept, int conflicts, int brandsAdded) { }
}
