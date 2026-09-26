package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CorrectionMemoryComponentsTest {

    @TempDir
    Path tempDir;

    @Test
    void rulesFindCompactMatchOnlyWithinTheSameStore() {
        CorrectionMemory.Entry stored = new CorrectionMemory.Entry(
                "LTC", "molto jardin 340gr", "Jardiner", "Molto", "Supermercado", 1, "2026-09-19"
        );
        List<CorrectionMemory.Entry> entries = List.of(stored);

        assertThat(CorrectionMemoryRules.find(entries, "LTC", "moltojardin340gr")).isSameAs(stored);
        assertThat(CorrectionMemoryRules.find(entries, "Otro comercio", "moltojardin340gr")).isNull();
    }

    @Test
    void rulesKeepRealBrandWhenCorrectionSaysGeneric() {
        assertThat(CorrectionMemoryRules.cleanMemoryBrand("Genérico")).isEmpty();
        assertThat(CorrectionMemoryRules.fallbackMemoryBrand("Genérico", "Molto")).isEqualTo("Molto");
    }

    @Test
    void memoryStoreRoundTripsEntries() {
        Path memoryPath = tempDir.resolve("corrections.json");
        CorrectionMemoryStore store = new CorrectionMemoryStore(new ObjectMapper(), memoryPath);
        CorrectionMemory.Entry entry = new CorrectionMemory.Entry(
                "LTC", "oreo leche", "Galletitas Oreo", "Oreo", "Galletitas", 2, "2026-09-19"
        );

        assertThat(store.load()).isEmpty();
        store.save(List.of(entry));

        assertThat(store.load()).containsExactly(entry);
    }

    @Test
    void memoryStoreRestoresLastGoodJsonBackupAfterCorruption() throws Exception {
        Path memoryPath = tempDir.resolve("corrections.json");
        ObjectMapper mapper = new ObjectMapper();
        CorrectionMemoryStore store = new CorrectionMemoryStore(mapper, memoryPath);
        CorrectionMemory.Entry first = new CorrectionMemory.Entry(
                "LTC", "oreo leche", "Galletitas Oreo", "Oreo", "Supermercado", 1, "2026-09-19"
        );
        CorrectionMemory.Entry latest = new CorrectionMemory.Entry(
                "LTC", "manteca", "Manteca", "La Serenisima", "Supermercado", 1, "2026-09-20"
        );

        store.save(List.of(first));
        store.save(List.of(latest));
        assertThat(new CorrectionMemoryStore(mapper, AtomicJsonFile.backupPath(memoryPath)).load())
                .containsExactly(first);

        Files.writeString(memoryPath, "{ JSON incompleto");

        assertThat(store.load()).containsExactly(first);
        assertThat(store.load()).containsExactly(first);
    }
}
