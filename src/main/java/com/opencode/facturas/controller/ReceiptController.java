package com.opencode.facturas.controller;

import com.opencode.facturas.model.ExtractResponse;
import com.opencode.facturas.model.OcrResult;
import com.opencode.facturas.service.OcrService;
import com.opencode.facturas.service.OcrReviewMapper;
import com.opencode.facturas.service.ReceiptParserService;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/receipts")
public class ReceiptController {

    private static final Logger log = LoggerFactory.getLogger(ReceiptController.class);

    private final OcrService ocrService;
    private final ReceiptParserService parserService;

    public ReceiptController(OcrService ocrService, ReceiptParserService parserService) {
        this.ocrService = ocrService;
        this.parserService = parserService;
    }

    @PostMapping(value = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ExtractResponse extract(@RequestParam(value = "file", required = false) MultipartFile file,
                                   @RequestHeader(value = "X-Extraction-Id", required = false) String extractionId,
                                   @RequestHeader(value = "X-OCR-Search-Budget-Seconds", required = false) Integer searchBudgetSeconds) {
        if (file == null || file.isEmpty()) {
            log.warn("Extracción rechazada: no se recibió un archivo");
            throw new IllegalArgumentException("Subi una imagen o PDF de la factura.");
        }

        log.info("Iniciando extracción: tipo={}, tamaño={} bytes", file.getContentType(), file.getSize());
        OcrResult ocrResult = searchBudgetSeconds != null ? ocrService.extract(file, extractionId, searchBudgetSeconds)
                : extractionId == null ? ocrService.extract(file) : ocrService.extract(file, extractionId);
        ExtractResponse response = parserService.parse(ocrResult)
                .withOcrMetadata(ocrResult.variant(), ocrResult.score())
                .withOcrReview(OcrReviewMapper.pages(ocrResult));
        log.info("Extracción completada: caracteresOCR={}, líneasOCR={}, items={}, advertencias={}",
                ocrResult.text() == null ? 0 : ocrResult.text().length(),
                ocrResult.lines() == null ? 0 : ocrResult.lines().size(),
                response.items() == null ? 0 : response.items().size(),
                response.warnings() == null ? 0 : response.warnings().size());
        return response;
    }

    @PostMapping("/cancel")
    public Map<String, Boolean> cancel(@RequestParam String extractionId) {
        ocrService.cancelExtraction(extractionId);
        return Map.of("cancelled", true);
    }
}
