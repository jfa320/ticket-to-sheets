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
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.function.IntConsumer;

class OcrApiClient {

    private static final Logger log = LoggerFactory.getLogger(OcrApiClient.class);

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final RestTemplate healthRestTemplate;
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
            int healthTimeoutMs,
            int maxAttempts
    ) {
        this(
                objectMapper,
                buildRestTemplate(connectTimeoutMs, readTimeoutMs),
                buildRestTemplate(healthTimeoutMs, healthTimeoutMs),
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
        this(objectMapper, restTemplate, restTemplate, endpoint, healthEndpoint, language, maxAttempts, retryWaiter);
    }

    OcrApiClient(
            ObjectMapper objectMapper,
            RestTemplate restTemplate,
            RestTemplate healthRestTemplate,
            String endpoint,
            String healthEndpoint,
            String language,
            int maxAttempts,
            IntConsumer retryWaiter
    ) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("La cantidad de intentos OCR debe ser positiva.");
        }
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
        this.healthRestTemplate = healthRestTemplate;
        this.endpoint = endpoint;
        this.healthEndpoint = healthEndpoint;
        this.language = language;
        this.maxAttempts = maxAttempts;
        this.retryWaiter = retryWaiter;
    }

    String recognize(BufferedImage image) {
        ensureReady();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("X-OCR-Language", language);

        HttpEntity<byte[]> request = new HttpEntity<>(toPngBytes(image), headers);
        try {
            // No repetir una lectura tras perder la respuesta: Python puede seguir procesándola.
            ResponseEntity<String> response = restTemplate.postForEntity(URI.create(endpoint), request, String.class);
            String body = response.getBody();
            if (!response.getStatusCode().is2xxSuccessful() || body == null || body.isBlank()) {
                throw new IllegalStateException("El servicio OCR respondió vacío o con error.");
            }
            log.info("Servicio OCR respondió correctamente ({} bytes)", body.length());
            return body;
        } catch (ResourceAccessException ex) {
            if (hasTimeoutCause(ex)) {
                throw new IllegalStateException("El OCR superó el tiempo de espera. La lectura puede seguir en curso; esperá antes de reintentar.", ex);
            }
            throw new OcrUnavailableException("Se perdió la conexión con el OCR. La lectura puede seguir en curso; revisá el servicio y esperá antes de reintentar.", ex);
        } catch (HttpStatusCodeException ex) {
            if (ex.getStatusCode().value() == 429) {
                throw new ExtractionBusyException();
            }
            if (ex.getStatusCode().value() == 503) {
                throw new OcrUnavailableException("El OCR está preparando los modelos. Esperá a que termine y reintentá.", ex);
            }
            String responseBody = ex.getResponseBodyAsString();
            log.error("Servicio OCR respondió HTTP {}: {}", ex.getStatusCode(), responseBody, ex);
            throw new IllegalStateException("El servicio OCR respondió con error " + ex.getStatusCode() + ": " + responseBody, ex);
        } catch (RestClientException ex) {
            log.error("Error de comunicación con el servicio OCR", ex);
            throw new IllegalStateException("El servicio OCR respondió con error. Revisá los logs del OCR.", ex);
        }
    }

    private void ensureReady() {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                waitForHealth();
                return;
            } catch (OcrUnavailableException ex) {
                log.warn("OCR no disponible en el intento {}/{}: {}", attempt, maxAttempts, ex.getMessage());
                if (attempt == maxAttempts) {
                    throw ex;
                }
                retryWaiter.accept(attempt);
            }
        }
    }

    private void waitForHealth() {
        try {
            ResponseEntity<String> response = healthRestTemplate.getForEntity(URI.create(healthEndpoint), String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new OcrUnavailableException("El OCR no está disponible. Revisá el servicio local y reintentá.", null);
            }
            JsonNode health = objectMapper.readTree(response.getBody() == null ? "{}" : response.getBody());
            if (!health.path("ocrReady").asBoolean(false)) {
                throw new OcrUnavailableException("El OCR está preparando los modelos. Esperá a que termine y reintentá.", null);
            }
            if (health.path("ocrBusy").asBoolean(false)) {
                throw new ExtractionBusyException();
            }
            log.debug("Healthcheck OCR OK");
        } catch (IOException ex) {
            throw new OcrUnavailableException("El OCR devolvió un estado inválido. Reiniciá el servicio local y reintentá.", ex);
        } catch (HttpStatusCodeException ex) {
            String message = ex.getStatusCode().value() == 503
                    ? "El OCR está preparando los modelos. Esperá a que termine y reintentá."
                    : "El OCR no está disponible. Revisá el servicio local y reintentá.";
            throw new OcrUnavailableException(message, ex);
        } catch (RestClientException ex) {
            throw new OcrUnavailableException("No se pudo conectar con el OCR local. Iniciá el servicio OCR y reintentá.", ex);
        }
    }

    private static boolean hasTimeoutCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
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
