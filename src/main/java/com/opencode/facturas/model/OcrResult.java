package com.opencode.facturas.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OcrResult(
        String text,
        List<OcrLine> lines,
        List<OcrDetection> detections,
        String variant,
        Double score
) {
}
