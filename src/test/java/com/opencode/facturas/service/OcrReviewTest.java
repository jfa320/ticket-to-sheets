package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.facturas.controller.ReceiptController;
import com.opencode.facturas.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OcrReviewTest {
    @Test
    void mapsPreviewFrameDimensionsWithoutUsingThumbnailDimensions() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(10, 20, BufferedImage.TYPE_INT_RGB), "jpeg", bytes);
        String url = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
        String json = "{\"text\":\"LECHE\",\"preview\":{\"imageDataUrl\":\"" + url
                + "\",\"width\":1000,\"height\":2000}}";
        OcrResult parsed = new OcrResultMapper(new ObjectMapper()).parse(json);
        assertThat(parsed.preview().width()).isEqualTo(1000);
        assertThat(parsed.preview().height()).isEqualTo(2000);
        assertThat(parsed.preview().imageDataUrl()).isEqualTo(url);
        assertThat(OcrReviewMapper.pages(parsed).get(0).imageDataUrl()).isEqualTo(url);
    }

    @Test
    void invalidOptionalPreviewsDoNotDiscardTheRecognizedText() throws Exception {
        for (String url : List.of("https://example.org/image.jpg", "data:image/jpeg;base64,invalid", "data:image/svg+xml;base64,AAAA")) {
            OcrResult parsed = new OcrResultMapper(new ObjectMapper()).parse(
                    "{\"text\":\"LECHE\",\"preview\":{\"imageDataUrl\":\"" + url + "\",\"width\":100,\"height\":200}}");
            assertThat(parsed.preview()).isNull();
            assertThat(parsed.text()).isEqualTo("LECHE");
        }
    }

    @Test
    void controllerKeepsReviewPagesAndConfidenceSeparateFromEditableProducts() {
        OcrDetection uncertain = new OcrDetection("LECH3", .6, List.of(List.of(10.0, 20.0), List.of(90.0, 20.0),
                List.of(90.0, 40.0), List.of(10.0, 40.0)));
        OcrResult photo = new OcrResult("LECH3", List.of(), List.of(uncertain), "gray+fusion", 40.0, List.of(),
                new OcrPreview("data:image/jpeg;base64,/9j/", 200, 300));
        OcrResult digital = new OcrResult("PAN", List.of(), List.of(new OcrDetection("PAN", null, List.of())), "pdf-text", null);
        OcrResult combined = new OcrResult("LECH3\nPAN", List.of(), List.of(), "mixed", 40.0, List.of(photo, digital));
        OcrService ocr = mock(OcrService.class);
        ReceiptParserService parser = mock(ReceiptParserService.class);
        var file = new MockMultipartFile("file", "synthetic.pdf", "application/pdf", new byte[]{1});
        when(ocr.extract(file)).thenReturn(combined);
        when(parser.parse(combined)).thenReturn(new ExtractResponse("Mercado", "1/1/2026", 0, "", "", "", combined.text(), List.of()));
        ExtractResponse response = new ReceiptController(ocr, parser).extract(file);
        assertThat(response.ocrReview()).hasSize(2);
        assertThat(response.ocrReview().get(0).pageNumber()).isEqualTo(1);
        assertThat(response.ocrReview().get(0).detections()).containsExactly(uncertain);
        assertThat(response.ocrReview().get(1).source()).isEqualTo("pdf-text");
        assertThat(response.ocrReview().get(1).imageDataUrl()).isNull();
        assertThat(response.ocrReview().get(1).detections().get(0).confidence()).isNull();
        assertThat(response.rawText()).isEqualTo(combined.text());
        assertThat(response.items()).isEmpty();
    }
}
