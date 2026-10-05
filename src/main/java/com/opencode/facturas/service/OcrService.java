package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.facturas.model.OcrDetection;
import com.opencode.facturas.model.OcrLine;
import com.opencode.facturas.model.OcrResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);
    private static final int PDF_SIGNATURE_SEARCH_BYTES = 1024;
    private static final long DEFAULT_MAX_IMAGE_PIXELS = 20_000_000L;
    private static final int DEFAULT_MAX_IMAGE_SIDE = 10_000;

    private final OcrResultMapper resultMapper;
    private final OcrApiClient apiClient;
    private final PdfPageRenderer pdfPageRenderer;
    private final long maxImagePixels;
    private final int maxImageSide;
    private final boolean nativePdfTextEnabled;

    @Autowired
    public OcrService(
            ObjectMapper objectMapper,
            @Value("${app.ocr.endpoint:http://127.0.0.1:5000/ocr}") String endpoint,
            @Value("${app.ocr.health-endpoint:http://127.0.0.1:5000/health}") String healthEndpoint,
            @Value("${app.ocr.language:es}") String language,
            @Value("${app.ocr.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${app.ocr.read-timeout-ms:300000}") int readTimeoutMs,
            @Value("${app.ocr.max-attempts:3}") int maxAttempts,
            @Value("${app.upload.max-image-pixels:20000000}") long maxImagePixels,
            @Value("${app.upload.max-image-side:10000}") int maxImageSide,
            @Value("${app.pdf.max-pages:20}") int maxPdfPages,
            @Value("${app.pdf.max-page-pixels:20000000}") long maxPdfPagePixels,
            @Value("${app.pdf.native-text-enabled:true}") boolean nativePdfTextEnabled
    ) {
        this(
                new OcrResultMapper(objectMapper),
                new OcrApiClient(
                        objectMapper,
                        endpoint,
                        healthEndpoint,
                        language,
                        connectTimeoutMs,
                        readTimeoutMs,
                        maxAttempts
                ),
                new PdfPageRenderer(PdfPageRenderer.DEFAULT_DPI, maxPdfPages, maxPdfPagePixels),
                maxImagePixels,
                maxImageSide,
                nativePdfTextEnabled
        );
    }

    OcrService(OcrResultMapper resultMapper, OcrApiClient apiClient, PdfPageRenderer pdfPageRenderer) {
        this(resultMapper, apiClient, pdfPageRenderer, DEFAULT_MAX_IMAGE_PIXELS, DEFAULT_MAX_IMAGE_SIDE);
    }

    OcrService(OcrResultMapper resultMapper, OcrApiClient apiClient, PdfPageRenderer pdfPageRenderer,
               long maxImagePixels, int maxImageSide) {
        this(resultMapper, apiClient, pdfPageRenderer, maxImagePixels, maxImageSide, true);
    }

    OcrService(OcrResultMapper resultMapper, OcrApiClient apiClient, PdfPageRenderer pdfPageRenderer,
               long maxImagePixels, int maxImageSide, boolean nativePdfTextEnabled) {
        if (maxImagePixels < 1 || maxImageSide < 1) {
            throw new IllegalArgumentException("Los límites de imagen deben ser positivos.");
        }
        this.resultMapper = resultMapper;
        this.apiClient = apiClient;
        this.pdfPageRenderer = pdfPageRenderer;
        this.maxImagePixels = maxImagePixels;
        this.maxImageSide = maxImageSide;
        this.nativePdfTextEnabled = nativePdfTextEnabled;
    }

    public String extractText(MultipartFile file) {
        return extract(file).text();
    }

    public OcrResult extract(MultipartFile file) {
        String filename = file.getOriginalFilename() == null ? "archivo" : file.getOriginalFilename().toLowerCase();
        long startedAt = System.nanoTime();

        try {
            byte[] bytes = file.getBytes();
            boolean pdf = hasPdfSignature(bytes);
            if (filename.endsWith(".pdf") && !pdf) {
                throw new IllegalArgumentException("El archivo tiene extensión PDF, pero no contiene una cabecera PDF válida.");
            }
            log.info("Procesando archivo para OCR: formato={}, tamaño={} bytes", pdf ? "PDF" : "imagen", file.getSize());

            if (pdf) {
                OcrResult result = extractFromPdf(bytes);
                log.info("OCR de PDF completado: páginas={}, texto={} caracteres, variante={}, score={}, duración={} ms",
                        result.pages() == null ? 0 : result.pages().size(), textLength(result), result.variant(),
                        result.score(), elapsedMs(startedAt));
                return result;
            }

            BufferedImage image = readImage(bytes);
            OcrResult result;
            try {
                result = processImage(image);
            } finally {
                image.flush();
            }
            log.info("OCR de imagen completado: texto={} caracteres, variante={}, score={}, duración={} ms",
                    textLength(result), result.variant(), result.score(), elapsedMs(startedAt));
            return result;
        } catch (IOException ex) {
            log.error("No se pudo leer el archivo para OCR", ex);
            throw new IllegalStateException("No se pudo leer el archivo subido.", ex);
        } catch (RuntimeException ex) {
            log.error("Falló el procesamiento OCR: {}", ex.getMessage(), ex);
            throw ex;
        }
    }

    private OcrResult extractFromPdf(byte[] bytes) {
        log.info("Procesando páginas PDF: {} bytes, texto nativo={}", bytes.length, nativePdfTextEnabled);
        List<OcrResult> pages = new ArrayList<>();
        pdfPageRenderer.forEachPage(bytes, nativePdfTextEnabled, (pageNumber, pageCount, nativeText, image) -> {
            if (nativeText != null) {
                log.info("Texto nativo de página PDF {}/{}: {} caracteres", pageNumber, pageCount, textLength(nativeText));
                pages.add(nativeText);
            } else {
                log.info("Enviando página PDF {}/{} a OCR: {}x{} px", pageNumber, pageCount, image.getWidth(), image.getHeight());
                pages.add(processImage(image));
            }
        });
        log.info("PDF renderizado y procesado: {} páginas", pages.size());
        return mergePageResults(pages);
    }

    private OcrResult processImage(BufferedImage source) {
        BufferedImage prepared = prepareImage(source);
        try {
            return runPaddle(prepared);
        } finally {
            prepared.flush();
        }
    }

    private BufferedImage readImage(byte[] bytes) throws IOException {
        try (ImageInputStream imageInput = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (imageInput == null) {
                throw new IllegalArgumentException("Formato de imagen no soportado.");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException("Formato de imagen no soportado.");
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                validateImageDimensions(reader.getWidth(0), reader.getHeight(0));
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new IllegalArgumentException("No se pudo decodificar la imagen.");
                }
                return image;
            } catch (IllegalArgumentException ex) {
                throw ex;
            } catch (IOException ex) {
                throw new IllegalArgumentException("La imagen está dañada o no se puede leer.", ex);
            } finally {
                reader.dispose();
            }
        }
    }

    private void validateImageDimensions(int width, int height) {
        long pixels = (long) width * height;
        if (width < 1 || height < 1 || width > maxImageSide || height > maxImageSide || pixels > maxImagePixels) {
            throw new IllegalArgumentException("La imagen supera las dimensiones admitidas: máximo "
                    + maxImagePixels + " píxeles y " + maxImageSide + " píxeles por lado.");
        }
    }

    private boolean hasPdfSignature(byte[] bytes) {
        byte[] signature = "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int lastStart = Math.min(bytes.length, PDF_SIGNATURE_SEARCH_BYTES) - signature.length;
        for (int index = 0; index <= lastStart; index++) {
            boolean matches = true;
            for (int offset = 0; offset < signature.length; offset++) {
                if (bytes[index + offset] != signature[offset]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }

    private OcrResult runPaddle(BufferedImage image) {
        try {
            String output = apiClient.recognize(image);
            OcrResult result = resultMapper.parse(output);
            if (result.text() == null || result.text().isBlank()) {
                throw new IllegalStateException("PaddleOCR no detecto texto util en la factura.");
            }
            log.debug("Respuesta OCR interpretada: {} caracteres, {} líneas, {} detecciones",
                    result.text().length(), result.lines() == null ? 0 : result.lines().size(),
                    result.detections() == null ? 0 : result.detections().size());
            return result;
        } catch (IOException ex) {
            log.error("No se pudo interpretar la respuesta de PaddleOCR", ex);
            throw new IllegalStateException("No se pudo interpretar la respuesta del servicio OCR.", ex);
        }
    }

    private int textLength(OcrResult result) {
        return result.text() == null ? 0 : result.text().length();
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private OcrResult mergePageResults(List<OcrResult> pages) {
        List<String> texts = new ArrayList<>();
        List<OcrLine> lines = new ArrayList<>();
        List<OcrDetection> detections = new ArrayList<>();
        Set<String> variants = new LinkedHashSet<>();
        double scoreSum = 0.0;
        int scoreCount = 0;

        for (OcrResult page : pages) {
            if (page.text() != null && !page.text().isBlank()) {
                texts.add(page.text());
            }
            if (page.lines() != null) {
                lines.addAll(page.lines());
            }
            if (page.detections() != null) {
                detections.addAll(page.detections());
            }
            if (page.variant() != null && !page.variant().isBlank()) {
                variants.add(page.variant());
            }
            if (page.score() != null) {
                scoreSum += page.score();
                scoreCount++;
            }
        }

        Double averageScore = scoreCount == 0 ? null : scoreSum / scoreCount;
        return new OcrResult(String.join("\n\n", texts), lines, detections, String.join(";", variants), averageScore, pages);
    }

    private BufferedImage prepareImage(BufferedImage source) {
        BufferedImage padded = new BufferedImage(source.getWidth() + 32, source.getHeight() + 32, BufferedImage.TYPE_INT_RGB);
        try {
            Graphics2D graphics = padded.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, padded.getWidth(), padded.getHeight());
                graphics.drawImage(source, 16, 16, null);
            } finally {
                graphics.dispose();
            }
            return padded;
        } catch (RuntimeException ex) {
            padded.flush();
            throw ex;
        }
    }
}
