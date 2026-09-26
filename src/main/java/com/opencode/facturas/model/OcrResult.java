package com.opencode.facturas.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OcrResult(
        String text,
        List<OcrLine> lines,
        List<OcrDetection> detections,
        String variant,
        Double score,
        List<OcrResult> pages
) {
    public OcrResult(
            String text,
            List<OcrLine> lines,
            List<OcrDetection> detections,
            String variant,
            Double score
    ) {
        this(text, lines, detections, variant, score, List.of());
    }

    public OcrResult {
        lines = lines == null ? List.of() : Collections.unmodifiableList(lines);
        detections = detections == null ? List.of() : Collections.unmodifiableList(detections);
        pages = pages == null ? List.of() : Collections.unmodifiableList(pages);
    }
}
