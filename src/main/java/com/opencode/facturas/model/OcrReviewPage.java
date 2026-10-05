package com.opencode.facturas.model;

import java.util.List;

public record OcrReviewPage(
        int pageNumber,
        String source,
        String imageDataUrl,
        int width,
        int height,
        List<OcrDetection> detections
) {
    public OcrReviewPage {
        detections = detections == null ? List.of() : List.copyOf(detections);
    }
}
