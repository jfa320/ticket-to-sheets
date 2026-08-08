package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.facturas.model.OcrDetection;
import com.opencode.facturas.model.OcrLine;
import com.opencode.facturas.model.OcrResult;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.awt.image.RescaleOp;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class OcrService {

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final String endpoint;
    private final String healthEndpoint;
    private final String language;
    private final int maxAttempts;

    public OcrService(
            ObjectMapper objectMapper,
            @Value("${app.ocr.endpoint:http://127.0.0.1:5000/ocr}") String endpoint,
            @Value("${app.ocr.health-endpoint:http://127.0.0.1:5000/health}") String healthEndpoint,
            @Value("${app.ocr.language:es}") String language,
            @Value("${app.ocr.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${app.ocr.read-timeout-ms:300000}") int readTimeoutMs,
            @Value("${app.ocr.max-attempts:3}") int maxAttempts
    ) {
        this.objectMapper = objectMapper;
        this.restTemplate = buildRestTemplate(connectTimeoutMs, readTimeoutMs);
        this.endpoint = endpoint;
        this.healthEndpoint = healthEndpoint;
        this.language = language;
        this.maxAttempts = maxAttempts;
    }

    public String extractText(MultipartFile file) {
        return extract(file).text();
    }

    public OcrResult extract(MultipartFile file) {
        String filename = file.getOriginalFilename() == null ? "archivo" : file.getOriginalFilename().toLowerCase();

        try {
            if (filename.endsWith(".pdf")) {
                return extractFromPdf(file.getBytes());
            }

            BufferedImage image = ImageIO.read(file.getInputStream());
            if (image == null) {
                throw new IllegalStateException("Formato de imagen no soportado.");
            }
            return runPaddle(preprocess(image));
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo leer el archivo subido.", ex);
        }
    }

    private OcrResult extractFromPdf(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFRenderer renderer = new PDFRenderer(document);
            List<OcrResult> pages = new ArrayList<>();

            for (int page = 0; page < document.getNumberOfPages(); page++) {
                BufferedImage image = renderer.renderImageWithDPI(page, 300, ImageType.RGB);
                pages.add(runPaddle(preprocess(image)));
            }

            return mergePageResults(pages);
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo procesar el PDF.", ex);
        }
    }

    private OcrResult runPaddle(BufferedImage image) {
        try {
            String output = callOcrApi(image);
            OcrResult result = parseOcrResult(output);
            if (result.text() == null || result.text().isBlank()) {
                throw new IllegalStateException("PaddleOCR no detecto texto util en la factura.");
            }
            return result;
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo interpretar la respuesta del servicio OCR.", ex);
        }
    }

    OcrResult parseOcrResult(String output) throws IOException {
        JsonNode root = objectMapper.readTree(output);
        if (root.hasNonNull("error") && !root.path("error").asText().isBlank()) {
            throw new IllegalStateException("PaddleOCR fallo: " + root.path("error").asText());
        }

        String text = root.path("text").asText("");
        String variant = root.path("variant").asText(null);
        Double score = root.path("score").isNumber() ? root.path("score").asDouble() : null;
        return new OcrResult(
                text,
                parseOcrLines(root.path("lines")),
                parseOcrDetections(root.path("detections")),
                variant,
                score
        );
    }

    private List<OcrLine> parseOcrLines(JsonNode linesNode) {
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
                    line.path("confidence").asDouble(0.0),
                    line.path("top").asDouble(0.0),
                    line.path("left").asDouble(0.0),
                    line.path("right").asDouble(0.0),
                    line.path("bottom").asDouble(0.0)
            ));
        }
        return lines;
    }

    private List<OcrDetection> parseOcrDetections(JsonNode detectionsNode) {
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
                    detection.path("confidence").asDouble(0.0),
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
        return new OcrResult(String.join("\n\n", texts), lines, detections, String.join(";", variants), averageScore);
    }

    private String callOcrApi(BufferedImage image) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("X-OCR-Language", language);

        HttpEntity<byte[]> request = new HttpEntity<>(toPngBytes(image), headers);
        RestClientException lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                waitForOcrHealth();
                ResponseEntity<String> response = restTemplate.postForEntity(URI.create(endpoint), request, String.class);
                String body = response.getBody();
                if (!response.getStatusCode().is2xxSuccessful() || body == null || body.isBlank()) {
                    throw new IllegalStateException("El servicio OCR respondio vacio o con error.");
                }
                return body;
            } catch (ResourceAccessException ex) {
                lastException = ex;
                sleepBeforeRetry(attempt);
            } catch (HttpStatusCodeException ex) {
                String responseBody = ex.getResponseBodyAsString();
                throw new IllegalStateException("El servicio OCR respondio con error " + ex.getStatusCode() + ": " + responseBody, ex);
            } catch (RestClientException ex) {
                throw new IllegalStateException("El servicio OCR respondio con error. Revisa los logs del contenedor PaddleOCR.", ex);
            }
        }

        throw new IllegalStateException("No se pudo conectar al servicio OCR Docker en " + endpoint + ". Verifica que Docker Desktop este corriendo y que `docker compose ps` muestre el contenedor activo.", lastException);
    }

    private byte[] toPngBytes(BufferedImage image) {
        try (java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream()) {
            ImageIO.write(image, "png", outputStream);
            return outputStream.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException("No se pudo serializar la imagen para OCR.", ex);
        }
    }

    private RestTemplate buildRestTemplate(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }

    private void waitForOcrHealth() {
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(URI.create(healthEndpoint), String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("El servicio OCR todavia no esta listo.");
            }
            JsonNode health = objectMapper.readTree(response.getBody() == null ? "{}" : response.getBody());
            if (!health.path("ocrReady").asBoolean(false)) {
                throw new ResourceAccessException("El servicio OCR esta levantado, pero todavia esta descargando modelos.", new IOException("ocrReady=false"));
            }
        } catch (IOException ex) {
            throw new ResourceAccessException("No se pudo interpretar el health check OCR", ex);
        } catch (RestClientException ex) {
            throw new ResourceAccessException("OCR health check fallo", new IOException(ex));
        }
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(Math.min(1500L * attempt, 4000L));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Se interrumpio la espera del servicio OCR.", ex);
        }
    }

    private BufferedImage preprocess(BufferedImage source) {
        BufferedImage gray = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        ColorConvertOp colorConvert = new ColorConvertOp(ColorSpace.getInstance(ColorSpace.CS_GRAY), null);
        colorConvert.filter(source, gray);

        BufferedImage contrasted = new BufferedImage(gray.getWidth(), gray.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        RescaleOp rescaleOp = new RescaleOp(1.28f, 14f, null);
        rescaleOp.filter(gray, contrasted);

        BufferedImage padded = new BufferedImage(contrasted.getWidth() + 32, contrasted.getHeight() + 32, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = padded.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, padded.getWidth(), padded.getHeight());
        graphics.drawImage(contrasted, 16, 16, null);
        graphics.dispose();

        return padded;
    }
}
