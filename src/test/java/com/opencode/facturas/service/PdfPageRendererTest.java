package com.opencode.facturas.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfPageRendererTest {

    @Test
    void rendersEveryPdfPageAsRgbImage() throws Exception {
        List<String> pages = new ArrayList<>();
        new PdfPageRenderer(72.0f).forEachPage(pdfWithPages(2), (pageNumber, pageCount, page) -> {
            pages.add(pageNumber + "/" + pageCount + ":" + page.getWidth() + "x" + page.getHeight() + ":" + page.getType());
        });

        assertThat(pages).containsExactly(
                "1/2:36x72:" + BufferedImage.TYPE_INT_RGB,
                "2/2:36x72:" + BufferedImage.TYPE_INT_RGB
        );
    }

    @Test
    void rejectsInvalidPdfBytesWithDomainMessage() {
        assertThatThrownBy(() -> new PdfPageRenderer().forEachPage(new byte[]{1, 2, 3}, (page, total, image) -> { }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No se pudo procesar el PDF.");
    }

    @Test
    void rejectsPdfsWithMoreThanTheConfiguredPageLimitBeforeRendering() throws Exception {
        assertThatThrownBy(() -> new PdfPageRenderer(72.0f, 1, 1_000_000L)
                .forEachPage(pdfWithPages(2), (page, total, image) -> { }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El PDF supera el máximo de páginas permitido: 1.");
    }

    @Test
    void rejectsOversizedPageBeforeRendering() throws Exception {
        assertThatThrownBy(() -> new PdfPageRenderer(72.0f, 10, 1_000L)
                .forEachPage(pdfWithPages(1), (page, total, image) -> { }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Una página del PDF supera el límite permitido");
    }

    private byte[] pdfWithPages(int pageCount) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int index = 0; index < pageCount; index++) {
                document.addPage(new PDPage(new PDRectangle(36, 72)));
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
