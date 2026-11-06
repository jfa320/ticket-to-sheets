package com.opencode.facturas.service;

import com.opencode.facturas.model.OcrResult;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class ExtractionBudgetTest {
    @Test void deadlineIsSharedAcrossPagesDiscardsLateResultsAndReleasesTheService() throws Exception {
        var clock = new AtomicLong();
        var mapper = mock(OcrResultMapper.class);
        var client = mock(OcrApiClient.class);
        var budgets = new ArrayList<Double>();
        when(client.recognize(any(BufferedImage.class), any(ExtractionTask.class))).thenAnswer(invocation -> {
            ExtractionTask task = invocation.getArgument(1);
            budgets.add(task.remainingSeconds());
            clock.addAndGet(20_000_000_000L);
            return "page";
        });
        var page = new OcrResult("PRODUCTO 1200,00", List.of(), List.of(), "synthetic", 1.0);
        when(mapper.parse("page")).thenReturn(page);
        var service = new OcrService(mapper, client, new PdfPageRenderer(72), 20_000_000, 10_000,
                false, 30, clock::get);
        byte[] bytes;
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            for (int i = 0; i < 3; i++) document.addPage(new PDPage(new PDRectangle(100, 100)));
            document.save(output); bytes = output.toByteArray();
        }
        assertThatThrownBy(() -> service.extract(new MockMultipartFile("file", "fixture.pdf", "application/pdf", bytes)))
                .isInstanceOf(ExtractionTimeoutException.class).hasMessageContaining("No se devolvieron resultados parciales");
        assertThat(budgets).containsExactly(30.0, 10.0);
        verify(mapper, times(1)).parse("page");
        // Una nueva extracción obtiene su propio presupuesto, sin reiniciar el servicio.
        when(client.recognize(any(BufferedImage.class), any(ExtractionTask.class))).thenReturn("page");
        assertThat(service.extract(new MockMultipartFile("file", "fixture.pdf", "application/pdf", bytes)).pages()).hasSize(3);
    }

    @Test void deadlineUsesMonotonicTimeAndCancellationStillTakesPriority() {
        var clock = new AtomicLong();
        var task = new ExtractionTask("fixture", 40, clock::get);
        task.startDeadline(10);
        clock.set(8_500_000_000L);
        assertThat(task.remainingSeconds()).isEqualTo(1.5);
        clock.set(10_000_000_000L);
        assertThatThrownBy(task::checkCancelled).isInstanceOf(ExtractionTimeoutException.class);
        task.cancel();
        assertThatThrownBy(task::checkCancelled).isInstanceOf(ExtractionCancelledException.class);
        var ticking = new AtomicLong();
        var boundary = new ExtractionTask(null, null, () -> ticking.getAndIncrement());
        boundary.startDeadline(1);
        ticking.set(ticking.get() + 1_000_000_000L - 2);
        // Vence entre la comprobación y el cálculo: nunca enviar un plazo cero.
        assertThatThrownBy(boundary::remainingSeconds).isInstanceOf(ExtractionTimeoutException.class);
    }
}
