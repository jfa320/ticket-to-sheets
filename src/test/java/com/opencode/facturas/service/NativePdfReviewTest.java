package com.opencode.facturas.service;

import com.opencode.facturas.model.OcrResult;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.io.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class NativePdfReviewTest {
    @Test void fractionalPageSizesCannotExceedThePreviewPixelLimit() throws Exception {
        try (var document = new PDDocument()) {
            var page = new PDPage(new PDRectangle(200.49f, 5000.49f)); document.addPage(page);
            var preview = new PdfPreviewRenderer().render(new org.apache.pdfbox.rendering.PDFRenderer(document), page, 0);
            var image = decode(Base64.getDecoder().decode(preview.imageDataUrl().split(",", 2)[1]));
            assertThat((long) image.getWidth() * image.getHeight()).isLessThanOrEqualTo(2_000_000);
        }
    }
    @Test void nativePagesHaveBoundedPreviewsAlignedWithTextWithoutCallingOcr() throws Exception {
        byte[] bytes;
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            for (int rotation : new int[]{0, 90, 180, 270}) {
                var page = new PDPage(new PDRectangle(500, 700));
                page.setCropBox(new PDRectangle(20, 30, 400, 600)); page.setRotation(rotation); document.addPage(page);
                try (var content = new PDPageContentStream(document, page)) {
                    content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(50, 550);
                    for (String line : List.of("COMERCIO FICTICIO DE PRUEBA", "Fecha 10/10/2026",
                            "LECHE ENTERA 1200,00", "PAN INTEGRAL 800,00", "TOTAL 2000,00")) {
                        content.showText(line); content.newLineAtOffset(0, -30);
                    }
                    content.endText();
                }
            }
            document.save(output); bytes = output.toByteArray();
        }
        List<OcrResult> pages = new ArrayList<>();
        new PdfPageRenderer().forEachPage(bytes, true, (number, count, nativeText, raster) -> {
            assertThat(raster).isNull(); assertThat(nativeText.variant()).isEqualTo("pdf-text");
            assertThat(nativeText.preview()).isNotNull(); pages.add(nativeText);
            var preview = nativeText.preview();
            byte[] jpeg = Base64.getDecoder().decode(preview.imageDataUrl().split(",", 2)[1]);
            assertThat(jpeg.length).isLessThanOrEqualTo(900_000);
            var image = decode(jpeg);
            assertThat((long) image.getWidth() * image.getHeight()).isLessThanOrEqualTo(2_000_000);
            var detection = nativeText.detections().get(0);
            int left = (int) (detection.box().stream().mapToDouble(point -> point.get(0)).min().orElseThrow() * image.getWidth() / preview.width());
            int top = (int) (detection.box().stream().mapToDouble(point -> point.get(1)).min().orElseThrow() * image.getHeight() / preview.height());
            int right = Math.min(image.getWidth(), (int) Math.ceil(detection.box().stream().mapToDouble(point -> point.get(0)).max().orElseThrow() * image.getWidth() / preview.width()));
            int bottom = Math.min(image.getHeight(), (int) Math.ceil(detection.box().stream().mapToDouble(point -> point.get(1)).max().orElseThrow() * image.getHeight() / preview.height()));
            boolean ink = false;
            for (int y = top; y < bottom; y++) for (int x = left; x < right; x++)
                ink |= (image.getRGB(x, y) & 0xff) < 180;
            assertThat(ink).as("el recorte de texto digital debe contener glifos visibles").isTrue();
            image.flush();
        });
        assertThat(OcrReviewMapper.pages(new OcrResult("", List.of(), List.of(), "", null, pages)))
                .hasSize(4).allMatch(page -> page.imageDataUrl() != null && page.source().equals("pdf-text"));
    }
    private java.awt.image.BufferedImage decode(byte[] bytes) {
        try { return ImageIO.read(new ByteArrayInputStream(bytes)); }
        catch (IOException error) { throw new UncheckedIOException(error); }
    }
}
