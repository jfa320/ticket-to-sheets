package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.facturas.model.OcrResult;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class ExtractionProgressTest {
    @Test void progressIsScopedToTheIdAndKeepsOnlyEphemeralMetadata() throws Exception {
        var mapper = mock(OcrResultMapper.class); var client = mock(OcrApiClient.class);
        var service = new OcrService(mapper, client, mock(PdfPageRenderer.class));
        var started = new CountDownLatch(1); var finish = new CountDownLatch(1);
        when(client.recognize(any(BufferedImage.class), any(ExtractionTask.class))).thenAnswer(call -> {
            started.countDown(); if (!finish.await(5, TimeUnit.SECONDS)) throw new AssertionError("timeout de prueba");
            return "fictitious";
        });
        when(mapper.parse("fictitious")).thenReturn(new OcrResult("PRODUCTO PRIVADO 1234,56", List.of(), List.of(), "synthetic", 1.0));
        byte[] bytes; try (var output = new ByteArrayOutputStream()) {
            ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output); bytes = output.toByteArray();
        }
        var executor = Executors.newSingleThreadExecutor();
        try {
            var job = executor.submit(() -> service.extract(new MockMultipartFile("file", "fixture.png", "image/png", bytes), "active-id"));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(service.progress("active-id").phase()).isEqualTo("OCR");
            assertThat(service.progress("other-id").status()).isEqualTo("WAITING");
            assertThat(new ObjectMapper().writeValueAsString(service.progress("active-id"))).doesNotContain("PRIVADO", "1234", "fixture");
            finish.countDown(); job.get(5, TimeUnit.SECONDS);
            assertThat(service.progress("active-id").phase()).isEqualTo("PARSING");
            service.finishExtraction("active-id", true);
            assertThat(service.progress("active-id").status()).isEqualTo("COMPLETED");
            assertThatThrownBy(() -> service.progress("../bad")).isInstanceOf(IllegalArgumentException.class);
        } finally { finish.countDown(); executor.shutdownNow(); }
    }
}
