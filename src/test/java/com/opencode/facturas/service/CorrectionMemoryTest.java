package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CorrectionMemoryTest {

    @TempDir
    Path tempDir;

    private CorrectionMemory memory;
    private Path memoryPath;

    @BeforeEach
    void setUp() {
        memoryPath = tempDir.resolve("corrections.json");
        memory = new CorrectionMemory(new ObjectMapper(), memoryPath);
    }

    @Test
    void upsertAndFindExactMatchNormalized() {
        memory.upsert("Los Tres Corazones", "GAL OREO LECHE 1L", "Galletitas Oreo", "Oreo", "Galletitas");

        CorrectionMemory.Entry entry = memory.find("Los Tres Corazones", "gal oreo leche 1l");

        assertNotNull(entry);
        assertEquals("Oreo", entry.marca());
        assertEquals("Galletitas", entry.categoria());
        assertEquals("Galletitas Oreo", entry.descripcion());
    }

    @Test
    void findIsScopedByStore() {
        memory.upsert("Los Tres Corazones", "gal oreo leche 1l", "X", "Y", "Z");

        assertNull(memory.find("PedidosYa Market - San Miguel II", "gal oreo leche 1l"));
    }

    @Test
    void incrementsTimesAndKeepsPreviousFields() {
        memory.upsert("LTC", "gal oreo leche 1l", "Galletitas Oreo", "Oreo", "Galletitas");
        memory.upsert("LTC", "gal oreo leche 1l", "Galletitas de chocolate", "", "");

        CorrectionMemory.Entry entry = memory.find("LTC", "gal oreo leche 1l");
        assertEquals(2, entry.veces());
        assertEquals("Galletitas de chocolate", entry.descripcion());
        assertEquals("Oreo", entry.marca());
    }

    @Test
    void persistsAcrossInstances() {
        memory.upsert("LTC", "gal oreo leche 1l", "Galletitas Oreo", "Oreo", "Galletitas");

        CorrectionMemory reloaded = new CorrectionMemory(new ObjectMapper(), memoryPath);
        CorrectionMemory.Entry entry = reloaded.find("LTC", "gal oreo leche 1l");

        assertNotNull(entry);
        assertEquals(1, entry.veces());
    }

    @Test
    void matchesByTokenOverlapWithoutExactFirma() {
        memory.upsert("LTC", "hamburguesss paty 72 g 4 undsd", "Hamburguesa Paty", "Paty", "Congelados");

        CorrectionMemory.Entry entry = memory.find("LTC", "hamburguesss paty 4 undsd");

        assertNotNull(entry);
        assertEquals("Paty", entry.marca());
    }

    @Test
    void doesNotCreateFileUntilFirstWrite() {
        assertNull(memory.find("LTC", "cualquier cosa"));
        assertEquals(false, memoryPath.toFile().exists());
    }
}
