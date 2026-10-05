package com.opencode.facturas.model;

/** Preview pixels represent the same coordinate frame as the OCR detections. */
public record OcrPreview(String imageDataUrl, int width, int height) {
}
