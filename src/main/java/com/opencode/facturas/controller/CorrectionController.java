package com.opencode.facturas.controller;

import com.opencode.facturas.model.CorrectionsRequest;
import com.opencode.facturas.service.BrandCatalog;
import com.opencode.facturas.service.CorrectionMemory;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api")
public class CorrectionController {

    private static final Logger log = LoggerFactory.getLogger(CorrectionController.class);

    private final CorrectionMemory correctionMemory;
    private final BrandCatalog brandCatalog;

    public CorrectionController(CorrectionMemory correctionMemory, BrandCatalog brandCatalog) {
        this.correctionMemory = correctionMemory;
        this.brandCatalog = brandCatalog;
    }

    @PostMapping(value = "/corrections", consumes = MediaType.APPLICATION_JSON_VALUE)
    public LearnResponse learn(@RequestBody @Valid CorrectionsRequest request) {
        List<CorrectionsRequest.Correction> corrections = request.corrections();
        if (corrections == null || corrections.isEmpty()) {
            log.info("No hay correcciones para guardar");
            return new LearnResponse(0);
        }

        int saved = 0;
        for (CorrectionsRequest.Correction correction : corrections) {
            if (correction.firma().isBlank()) {
                continue;
            }
            if (isGeneric(correction.marcaOriginal()) || isGeneric(correction.marca())) {
                continue;
            }
            correctionMemory.upsert(
                    request.store(),
                    correction.firma(),
                    correction.descripcion(),
                    correction.marca(),
                    correction.categoria()
            );
            rememberBrandIfValid(correction.marca());
            saved++;
        }
        log.info("Correcciones procesadas: recibidas={}, guardadas={}, comercio='{}'",
                corrections.size(), saved, request.store());
        return new LearnResponse(saved);
    }

    private void rememberBrandIfValid(String marca) {
        if (marca == null || marca.isBlank() || isGeneric(marca) || marca.equalsIgnoreCase("Sin marca")) {
            return;
        }
        brandCatalog.remember(marca);
    }

    private boolean isGeneric(String marca) {
        if (marca == null) {
            return false;
        }
        return java.text.Normalizer.normalize(marca, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .trim()
                .equalsIgnoreCase("generico");
    }

    public record LearnResponse(int saved) {
    }
}
