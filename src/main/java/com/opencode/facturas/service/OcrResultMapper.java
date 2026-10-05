package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.facturas.model.OcrDetection;
import com.opencode.facturas.model.OcrLine;
import com.opencode.facturas.model.OcrResult;
import com.opencode.facturas.model.OcrPreview;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

class OcrResultMapper {

    private final ObjectMapper objectMapper;

    OcrResultMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    OcrResult parse(String output) throws IOException {
        JsonNode root = objectMapper.readTree(output);
        if (root.hasNonNull("error") && !root.path("error").asText().isBlank()) {
            throw new IllegalStateException("PaddleOCR fallo: " + root.path("error").asText());
        }

        String text = root.path("text").asText("");
        String variant = root.path("variant").asText(null);
        Double score = root.path("score").isNumber() ? root.path("score").asDouble() : null;
        return new OcrResult(
                text,
                parseLines(root.path("lines")),
                parseDetections(root.path("detections")),
                variant,
                score,
                parsePages(root.path("pages")),
                parsePreview(root.path("preview"))
        );
    }

    private List<OcrResult> parsePages(JsonNode pagesNode) {
        List<OcrResult> pages = new ArrayList<>();
        if (!pagesNode.isArray()) {
            return pages;
        }

        for (JsonNode page : pagesNode) {
            String text = page.path("text").asText("");
            pages.add(new OcrResult(
                    text,
                    parseLines(page.path("lines")),
                    parseDetections(page.path("detections")),
                    page.path("variant").asText(null),
                    page.path("score").isNumber() ? page.path("score").asDouble() : null,
                    List.of(),
                    parsePreview(page.path("preview"))
            ));
        }
        return pages;
    }

    private List<OcrLine> parseLines(JsonNode linesNode) {
        List<OcrLine> lines = new ArrayList<>();
        if (!linesNode.isArray()) {
            return lines;
        }

        for (JsonNode line : linesNode) {
            String text = line.path("text").asText("");
            if (text.isBlank()) {
                continue;
            }
            lines.add(new OcrLine(
                    text,
                    line.path("score").asDouble(0.0),
                    optionalDouble(line.path("confidence")),
                    line.path("top").asDouble(0.0),
                    line.path("left").asDouble(0.0),
                    line.path("right").asDouble(0.0),
                    line.path("bottom").asDouble(0.0)
            ));
        }
        return lines;
    }

    private List<OcrDetection> parseDetections(JsonNode detectionsNode) {
        List<OcrDetection> detections = new ArrayList<>();
        if (!detectionsNode.isArray()) {
            return detections;
        }

        for (JsonNode detection : detectionsNode) {
            String text = detection.path("text").asText("");
            if (text.isBlank()) {
                continue;
            }
            detections.add(new OcrDetection(
                    text,
                    optionalDouble(detection.path("confidence")),
                    parseBox(detection.path("box"))
            ));
        }
        return detections;
    }

    private List<List<Double>> parseBox(JsonNode boxNode) {
        List<List<Double>> box = new ArrayList<>();
        if (!boxNode.isArray()) {
            return box;
        }

        for (JsonNode pointNode : boxNode) {
            if (!pointNode.isArray() || pointNode.size() < 2) {
                continue;
            }
            box.add(List.of(pointNode.get(0).asDouble(), pointNode.get(1).asDouble()));
        }
        return box;
    }

    private Double optionalDouble(JsonNode node) {
        return node.isNumber() ? node.asDouble() : null;
    }

    private OcrPreview parsePreview(JsonNode node) {
        String data = node.path("imageDataUrl").asText("");
        int width = node.path("width").asInt(0);
        int height = node.path("height").asInt(0);
        // Previews are optional. Reject remote URLs and unbounded payloads while
        // preserving successful text extraction from older OCR services.
        if (width < 1 || height < 1 || (long) width * height > 4_000_000L
                || data.length() > 1_500_000 || !data.startsWith("data:image/jpeg;base64,")) {
            return null;
        }
        try {
            byte[] decoded = java.util.Base64.getDecoder().decode(data.substring("data:image/jpeg;base64,".length()));
            if (decoded.length < 3 || (decoded[0] & 0xff) != 0xff
                    || (decoded[1] & 0xff) != 0xd8 || (decoded[2] & 0xff) != 0xff) {
                return null;
            }
        } catch (IllegalArgumentException ex) {
            return null;
        }
        return new OcrPreview(data, width, height);
    }
}
