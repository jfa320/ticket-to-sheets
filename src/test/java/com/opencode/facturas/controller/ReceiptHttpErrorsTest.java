package com.opencode.facturas.controller;

import com.opencode.facturas.service.OcrService;
import com.opencode.facturas.service.ReceiptParserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
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
}
