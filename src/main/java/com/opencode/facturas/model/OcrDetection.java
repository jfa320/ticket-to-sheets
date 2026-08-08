package com.opencode.facturas.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OcrDetection(
        String text,
        double confidence,
        List<List<Double>> box
) {
}
