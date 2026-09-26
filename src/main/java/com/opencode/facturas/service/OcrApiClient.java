package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.List;
import java.util.function.IntConsumer;

class OcrApiClient {

    private static final Logger log = LoggerFactory.getLogger(OcrApiClient.class);

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final String endpoint;
    private final String healthEndpoint;
    private final String language;
    private final int maxAttempts;
    private final IntConsumer retryWaiter;

    OcrApiClient(
            ObjectMapper objectMapper,
            String endpoint,
            String healthEndpoint,
            String language,
            int connectTimeoutMs,
            int readTimeoutMs,
            int maxAttempts
    ) {
        this(
                objectMapper,
                buildRestTemplate(connectTimeoutMs, readTimeoutMs),
                endpoint,
                healthEndpoint,
                language,
                maxAttempts,
                OcrApiClient::sleepBeforeRetry
        );
    }

    OcrApiClient(
            ObjectMapper objectMapper,
            RestTemplate restTemplate,
            String endpoint,
            String healthEndpoint,
            String language,
            int maxAttempts,
            IntConsumer retryWaiter
    ) {
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
        this.endpoint = endpoint;
        this.healthEndpoint = healthEndpoint;
        this.language = language;
        this.maxAttempts = maxAttempts;
        this.retryWaiter = retryWaiter;
    }

    String recognize(BufferedImage image) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("X-OCR-Language", language);

        HttpEntity<byte[]> request = new HttpEntity<>(toPngBytes(image), headers);
        RestClientException lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.info("Intento OCR {}/{}: verificando disponibilidad del servicio", attempt, maxAttempts);
                waitForHealth();
                ResponseEntity<String> response = restTemplate.postForEntity(URI.create(endpoint), request, String.class);
                String body = response.getBody();
                if (!response.getStatusCode().is2xxSuccessful() || body == null || body.isBlank()) {
                    throw new IllegalStateException("El servicio OCR respondio vacio o con error.");
                }
                log.info("Servicio OCR respondió correctamente en el intento {} ({} bytes)", attempt, body.length());
                return body;
            } catch (ResourceAccessException ex) {
                lastException = ex;
                log.warn("Servicio OCR no disponible en el intento {}/{}: {}", attempt, maxAttempts, ex.getMessage());
                retryWaiter.accept(attempt);
            } catch (HttpStatusCodeException ex) {
                String responseBody = ex.getResponseBodyAsString();
                log.error("Servicio OCR respondió HTTP {}: {}", ex.getStatusCode(), responseBody, ex);
                throw new IllegalStateException("El servicio OCR respondio con error " + ex.getStatusCode() + ": " + responseBody, ex);
            } catch (RestClientException ex) {
                log.error("Error de comunicación con el servicio OCR", ex);
                throw new IllegalStateException("El servicio OCR respondio con error. Revisa los logs del contenedor PaddleOCR.", ex);
            }
        }

        throw new IllegalStateException("No se pudo conectar al servicio OCR Docker en " + endpoint + ". Verifica que Docker Desktop este corriendo y que `docker compose ps` muestre el contenedor activo.", lastException);
    }

    private void waitForHealth() {
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(URI.create(healthEndpoint), String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                log.warn("Healthcheck OCR devolvió HTTP {}", response.getStatusCode());
                throw new IllegalStateException("El servicio OCR todavia no esta listo.");
            }
            JsonNode health = objectMapper.readTree(response.getBody() == null ? "{}" : response.getBody());
            if (!health.path("ocrReady").asBoolean(false)) {
                log.info("Healthcheck OCR correcto, pero los modelos todavía no están listos");
                throw new ResourceAccessException("El servicio OCR esta levantado, pero todavia esta descargando modelos.", new IOException("ocrReady=false"));
            }
            log.debug("Healthcheck OCR OK");
        } catch (IOException ex) {
            log.error("Respuesta inválida del healthcheck OCR", ex);
            throw new ResourceAccessException("No se pudo interpretar el health check OCR", ex);
        } catch (RestClientException ex) {
            log.warn("No se pudo consultar el healthcheck OCR: {}", ex.getMessage());
            throw new ResourceAccessException("OCR health check fallo", new IOException(ex));
        }
    }

    private byte[] toPngBytes(BufferedImage image) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", outputStream);
            return outputStream.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException("No se pudo serializar la imagen para OCR.", ex);
        }
    }

    private static RestTemplate buildRestTemplate(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }

    private static void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(Math.min(1500L * attempt, 4000L));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Se interrumpio la espera del servicio OCR.", ex);
        }
    }
}
