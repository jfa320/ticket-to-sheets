package com.opencode.facturas.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OcrLine(
        String text,
        double score,
        double confidence,
        double top,
        double left,
        double right,
        double bottom
) {
}
