package com.opencode.facturas.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import com.opencode.facturas.model.OcrResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.IOException;

class PdfPageRenderer {

    private static final Logger log = LoggerFactory.getLogger(PdfPageRenderer.class);

    static final float DEFAULT_DPI = 300.0f;
    static final int DEFAULT_MAX_PAGES = 20;
    static final long DEFAULT_MAX_PAGE_PIXELS = 20_000_000L;

    private final float dpi;
    private final int maxPages;
    private final long maxPagePixels;

    PdfPageRenderer() {
        this(DEFAULT_DPI, DEFAULT_MAX_PAGES, DEFAULT_MAX_PAGE_PIXELS);
    }

    PdfPageRenderer(float dpi) {
        this(dpi, DEFAULT_MAX_PAGES, DEFAULT_MAX_PAGE_PIXELS);
    }

    PdfPageRenderer(float dpi, int maxPages, long maxPagePixels) {
        if (dpi <= 0 || maxPages < 1 || maxPagePixels < 1) {
            throw new IllegalArgumentException("Los límites de renderizado del PDF deben ser positivos.");
        }
        this.dpi = dpi;
        this.maxPages = maxPages;
        this.maxPagePixels = maxPagePixels;
    }

    void forEachPage(byte[] pdfBytes, PageProcessor processor) {
        forEachPage(pdfBytes, false, (number, total, nativeText, image) -> processor.process(number, total, image));
    }

    void forEachPage(byte[] pdfBytes, boolean nativeTextEnabled, HybridPageProcessor processor) {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            int pageCount = document.getNumberOfPages();
            if (pageCount == 0) {
                throw new IllegalArgumentException("El PDF no contiene páginas.");
            }
            if (pageCount > maxPages) {
                throw new IllegalArgumentException("El PDF supera el máximo de páginas permitido: " + maxPages + ".");
            }

            PDFRenderer renderer = new PDFRenderer(document);
            PdfTextExtractor textExtractor = new PdfTextExtractor();
            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                validatePageDimensions(document.getPage(pageIndex));
                if (nativeTextEnabled) {
                    OcrResult nativeText = null;
                    try {
                        nativeText = textExtractor.extractPage(document, pageIndex).orElse(null);
                    } catch (IOException | RuntimeException ex) {
                        log.debug("No se pudo extraer texto nativo de página PDF {}; se usará OCR.", pageIndex + 1);
                    }
                    if (nativeText != null) {
                        processor.process(pageIndex + 1, pageCount, nativeText, null);
                        continue;
                    }
                }
                BufferedImage image = renderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB);
                try {
                    processor.process(pageIndex + 1, pageCount, null, image);
                } finally {
                    image.flush();
                }
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException(
                    "No se pudo procesar el PDF. Verificá que esté íntegro y no protegido con contraseña.", ex);
        }
    }

    private void validatePageDimensions(PDPage page) {
        PDRectangle cropBox = page.getCropBox();
        double scale = dpi * page.getUserUnit() / 72.0;
        double widthPixels = Math.ceil(cropBox.getWidth() * scale);
        double heightPixels = Math.ceil(cropBox.getHeight() * scale);
        if (!Double.isFinite(widthPixels) || !Double.isFinite(heightPixels)
                || widthPixels < 1 || heightPixels < 1
                || widthPixels > Integer.MAX_VALUE || heightPixels > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Una página del PDF tiene dimensiones no válidas.");
        }

        long pixelCount = (long) widthPixels * (long) heightPixels;
        if (pixelCount > maxPagePixels) {
            throw new IllegalArgumentException("Una página del PDF supera el límite permitido de "
                    + maxPagePixels + " píxeles a " + Math.round(dpi) + " DPI.");
        }
    }

    @FunctionalInterface
    interface PageProcessor {
        void process(int pageNumber, int pageCount, BufferedImage image);
    }

    @FunctionalInterface
    interface HybridPageProcessor {
        void process(int pageNumber, int pageCount, OcrResult nativeText, BufferedImage image);
    }
}
