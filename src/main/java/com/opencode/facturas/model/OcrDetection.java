package com.opencode.facturas.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OcrDetection(
        String text,
        Double confidence,
        List<List<Double>> box,
        boolean reviewRequired
) {
    public OcrDetection(String text, Double confidence, List<List<Double>> box) {
        this(text, confidence, box, false);
    }
}
