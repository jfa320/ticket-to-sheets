package com.opencode.facturas.controller;

import com.opencode.facturas.service.OcrService;
import com.opencode.facturas.service.ReceiptParserService;
import com.opencode.facturas.service.ExtractionBusyException;
import com.opencode.facturas.service.OcrUnavailableException;
import org.springframework.mock.web.MockMultipartFile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ReceiptController.class)
class ReceiptHttpErrorsTest {
    @Autowired MockMvc mvc;
    @MockBean OcrService ocr;
    @MockBean ReceiptParserService parser;

    @Test void missingRoutesReturn404InsteadOfInternalErrors() throws Exception {
        mvc.perform(get("/api/receipts")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("La ruta solicitada no existe."));
        mvc.perform(get("/favicon.ico")).andExpect(status().isNotFound());
        verifyNoInteractions(ocr, parser);
    }

    @Test void extractionRequiresPostAndMultipart() throws Exception {
        mvc.perform(get("/api/receipts/extract")).andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"));
        mvc.perform(post("/api/receipts/extract").contentType("application/json").content("{}"))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(ocr, parser);
    }

    @Test void multipartWithoutFileReturnsHelpfulValidationError() throws Exception {
        mvc.perform(multipart("/api/receipts/extract")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Subi una imagen o PDF de la factura."));
        verifyNoInteractions(ocr, parser);
    }

    @Test void overlappingExtractionReturns429WithRecoveryMessage() throws Exception {
        when(ocr.extract(any())).thenThrow(new ExtractionBusyException());
        mvc.perform(multipart("/api/receipts/extract")
                        .file(new MockMultipartFile("file", "ticket.png", "image/png", new byte[]{1})))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Ya hay una factura en procesamiento. Esperá a que termine y reintentá."))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        verifyNoInteractions(parser);
    }

    @Test void pendingModelsReturn503InsteadOfGenericProcessingError() throws Exception {
        when(ocr.extract(any())).thenThrow(new OcrUnavailableException("El OCR está preparando los modelos.", null));
        mvc.perform(multipart("/api/receipts/extract")
                        .file(new MockMultipartFile("file", "ticket.png", "image/png", new byte[]{1})))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("El OCR está preparando los modelos."));
        verifyNoInteractions(parser);
    }
}
