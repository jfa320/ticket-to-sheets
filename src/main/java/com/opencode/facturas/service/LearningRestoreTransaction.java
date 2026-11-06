package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Map;

/** Diario local de rollback para restaurar dos JSON como una sola operación. */
class LearningRestoreTransaction {
    private static final String JOURNAL = ".learning-restore.json";
    private record Journal(int schemaVersion, List<CorrectionMemory.Entry> corrections, List<String> brands) { }

    void commit(ObjectMapper mapper, Path corrections, List<CorrectionMemory.Entry> previousCorrections,
                List<CorrectionMemory.Entry> nextCorrections, Path brands, List<String> previousBrands, List<String> nextBrands) {
        Path root = corrections.toAbsolutePath().normalize().getParent();
        if (!root.equals(brands.toAbsolutePath().normalize().getParent())
                || !corrections.getFileName().toString().equals("corrections.json")
                || !brands.getFileName().toString().equals("brands.json")) throw new IllegalStateException("Los archivos de aprendizaje deben compartir la carpeta data.");
        Path journal = root.resolve(JOURNAL);
        if (Files.exists(journal)) throw new IllegalStateException("Hay una restauración interrumpida. Reiniciá la aplicación para recuperarla.");
        try {
            AtomicJsonFile.save(mapper, journal, new Journal(1, previousCorrections, previousBrands));
            write(mapper, corrections, Map.of("entries", nextCorrections));
            write(mapper, brands, nextBrands);
            Files.deleteIfExists(AtomicJsonFile.backupPath(journal)); Files.delete(journal);
        } catch (IOException | RuntimeException error) {
            try { recover(mapper, root); }
            catch (RuntimeException rollback) { error.addSuppressed(rollback); }
            throw new IllegalStateException("No se pudo restaurar el aprendizaje. Se conserva la memoria anterior; si el error persiste, reiniciá la aplicación.", error);
        }
    }
    protected void write(ObjectMapper mapper, Path target, Object value) throws IOException { AtomicJsonFile.save(mapper, target, value); }

    static synchronized void recover(ObjectMapper mapper, Path root) {
        Path journal = root.resolve(JOURNAL);
        if (!Files.exists(journal)) return;
        try {
            var previous = mapper.readValue(journal.toFile(), Journal.class);
            if (previous.schemaVersion() != 1 || previous.corrections() == null || previous.brands() == null)
                throw new IOException("Diario de restauración inválido.");
            AtomicJsonFile.replaceWithoutBackup(mapper, root.resolve("corrections.json"), Map.of("entries", previous.corrections()));
            AtomicJsonFile.replaceWithoutBackup(mapper, root.resolve("brands.json"), previous.brands());
            Files.deleteIfExists(AtomicJsonFile.backupPath(journal)); Files.delete(journal);
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo recuperar la restauración de aprendizaje. Conservá los archivos data y revisá el diagnóstico local.", ex);
        }
    }
}
