package com.opencode.facturas.controller;

import com.opencode.facturas.model.ExtractResponse;
import com.opencode.facturas.service.OcrService;
import com.opencode.facturas.service.ReceiptParserService;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@RequestMapping("/api/receipts")
public class ReceiptController {

    private final OcrService ocrService;
    private final ReceiptParserService parserService;

    public ReceiptController(OcrService ocrService, ReceiptParserService parserService) {
        this.ocrService = ocrService;
        this.parserService = parserService;
    }

    @PostMapping(value = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ExtractResponse extract(@RequestParam("file") @NotNull MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Subi una imagen o PDF de la factura.");
        }

        String text = ocrService.extractText(file);
        return parserService.parse(text);
    }
}
