package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.facturas.model.OcrResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OcrServiceTest {

    @Test
    void parsesStructuredOcrResponse() throws Exception {
        OcrService service = new OcrService(
                new ObjectMapper(),
                "http://127.0.0.1:5000/ocr",
                "http://127.0.0.1:5000/health",
                "es",
                100,
                100,
                1
        );

        OcrResult result = service.parseOcrResult("""
                {
                  "text": "PRODUCTO 123,45",
                  "variant": "enhanced-rot270",
                  "score": 42.5,
                  "lines": [
                    {"text": "PRODUCTO 123,45", "score": 0.91, "confidence": 0.88, "top": 10, "left": 20, "right": 200, "bottom": 40}
                  ],
                  "detections": [
                    {"text": "PRODUCTO", "confidence": 0.94, "box": [[20,10],[120,10],[120,40],[20,40]], "ignored": true}
                  ]
                }
                """);

        assertThat(result.text()).isEqualTo("PRODUCTO 123,45");
        assertThat(result.variant()).isEqualTo("enhanced-rot270");
        assertThat(result.score()).isEqualTo(42.5);
        assertThat(result.lines()).hasSize(1);
        assertThat(result.lines().get(0).confidence()).isEqualTo(0.88);
        assertThat(result.detections()).hasSize(1);
        assertThat(result.detections().get(0).box()).containsExactly(
                java.util.List.of(20.0, 10.0),
                java.util.List.of(120.0, 10.0),
                java.util.List.of(120.0, 40.0),
                java.util.List.of(20.0, 40.0)
        );
    }
}
