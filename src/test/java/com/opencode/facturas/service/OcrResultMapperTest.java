package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.facturas.model.OcrResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OcrResultMapperTest {

    private final OcrResultMapper mapper = new OcrResultMapper(new ObjectMapper());

    @Test
    void parsesStructuredOcrResponse() throws Exception {
        OcrResult result = mapper.parse("""
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
        assertThat(result.pages()).isEmpty();
    }

    @Test
    void parsesOptionalPageResults() throws Exception {
        OcrResult result = mapper.parse("""
                {
                  "text": "UNO\\n\\nDOS",
                  "pages": [
                    {"text": "UNO", "variant": "original", "score": 10.0},
                    {"text": "DOS", "variant": "rotated", "score": 30.0}
                  ]
                }
                """);

        assertThat(result.pages()).hasSize(2);
        assertThat(result.pages().get(0).text()).isEqualTo("UNO");
        assertThat(result.pages().get(1).variant()).isEqualTo("rotated");
    }

    @Test
    void skipsBlankRowsAndUsesDefaultsForPartialResponses() throws Exception {
        OcrResult result = mapper.parse("""
                {
                  "text": "PRODUCTO",
                  "lines": [
                    {"text": "  "},
                    {"text": "PRODUCTO"}
                  ],
                  "detections": [
                    {"text": ""},
                    {"text": "PRODUCTO", "box": [[10,20], [30], "invalido"]}
                  ]
                }
                """);

        assertThat(result.lines()).hasSize(1);
        assertThat(result.lines().get(0).score()).isZero();
        assertThat(result.lines().get(0).confidence()).isNull();
        assertThat(result.detections()).hasSize(1);
        assertThat(result.detections().get(0).confidence()).isNull();
        assertThat(result.detections().get(0).box()).containsExactly(java.util.List.of(10.0, 20.0));
        assertThat(result.variant()).isNull();
        assertThat(result.score()).isNull();
    }

    @Test
    void rejectsErrorsReportedByOcrService() {
        assertThatThrownBy(() -> mapper.parse("""
                {"error": "modelo no disponible"}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PaddleOCR fallo: modelo no disponible");
    }
}
