package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.facturas.controller.CorrectionController;
import com.opencode.facturas.model.CorrectionsRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CorrectionControllerTest {

    @TempDir
    Path tempDir;

    private CorrectionMemory memory;
    private CorrectionController controller;

    @BeforeEach
    void setUp() {
        memory = new CorrectionMemory(new ObjectMapper(), tempDir.resolve("corrections.json"));
        BrandCatalog brands = new BrandCatalog(new ObjectMapper(), tempDir.resolve("brands.json"));
        controller = new CorrectionController(memory, brands);
    }

    @Test
    void storesCorrectionsAndRemembersBrand() {
        CorrectionsRequest request = new CorrectionsRequest(
                "Los Tres Corazones",
                List.of(new CorrectionsRequest.Correction("gal oreo leche 1l", "Galletitas Oreo", "Oreo", "Galletitas"))
        );

        CorrectionController.LearnResponse response = controller.learn(request);

        assertEquals(1, response.saved());
        CorrectionMemory.Entry entry = memory.find("Los Tres Corazones", "gal oreo leche 1l");
        assertNotNull(entry);
        assertEquals("Oreo", entry.marca());
    }

    @Test
    void emptyCorrectionsReturnsZero() {
        CorrectionsRequest request = new CorrectionsRequest("Los Tres Corazones", List.of());

        assertEquals(0, controller.learn(request).saved());
    }

    @Test
    void genericOriginalDoesNotSaveCorrection() {
        CorrectionsRequest request = new CorrectionsRequest(
                "Los Tres Corazones",
                List.of(new CorrectionsRequest.Correction(
                        "articulo especial 1200", "Articulo", "Molto", "Supermercado", "Genérico")));

        assertEquals(0, controller.learn(request).saved());
    }
}
