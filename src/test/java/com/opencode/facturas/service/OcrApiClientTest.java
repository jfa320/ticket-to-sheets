package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OcrApiClientTest {

    private static final String OCR_ENDPOINT = "http://127.0.0.1:5000/ocr";
    private static final String HEALTH_ENDPOINT = "http://127.0.0.1:5000/health";
    private static final URI OCR_URI = URI.create(OCR_ENDPOINT);
    private static final URI HEALTH_URI = URI.create(HEALTH_ENDPOINT);

    @Test
    void sendsPngWithLanguageHeaderAfterHealthyResponse() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.getForEntity(HEALTH_URI, String.class))
                .thenReturn(ResponseEntity.ok("{\"ocrReady\":true}"));
        when(restTemplate.postForEntity(eq(OCR_URI), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"text\":\"OK\"}"));
        List<Integer> waits = new ArrayList<>();

        String response = client(restTemplate, 1, waits).recognize(
                new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        );

        assertThat(response).isEqualTo("{\"text\":\"OK\"}");
        assertThat(waits).isEmpty();

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<HttpEntity<byte[]>> requestCaptor = ArgumentCaptor.forClass((Class) HttpEntity.class);
        verify(restTemplate).postForEntity(eq(OCR_URI), requestCaptor.capture(), eq(String.class));
        HttpEntity<byte[]> request = requestCaptor.getValue();
        assertThat(request.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(request.getHeaders().getAccept()).containsExactly(MediaType.APPLICATION_JSON);
        assertThat(request.getHeaders().getFirst("X-OCR-Language")).isEqualTo("es");
        assertThat(request.getBody()).startsWith(0x89, 0x50, 0x4e, 0x47);
    }

    @Test
    void retriesWhenHealthEndpointIsTemporarilyUnavailable() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.getForEntity(HEALTH_URI, String.class))
                .thenThrow(new ResourceAccessException("offline"))
                .thenReturn(ResponseEntity.ok("{\"ocrReady\":true}"));
        when(restTemplate.postForEntity(eq(OCR_URI), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("respuesta"));
        List<Integer> waits = new ArrayList<>();

        String response = client(restTemplate, 2, waits).recognize(
                new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
        );

        assertThat(response).isEqualTo("respuesta");
        assertThat(waits).containsExactly(1);
        verify(restTemplate, times(2)).getForEntity(HEALTH_URI, String.class);
    }

    @Test
    void reportsEndpointAfterExhaustingHealthRetries() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.getForEntity(HEALTH_URI, String.class))
                .thenReturn(ResponseEntity.ok("{\"ocrReady\":false}"));
        List<Integer> waits = new ArrayList<>();

        assertThatThrownBy(() -> client(restTemplate, 2, waits).recognize(
                new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(OCR_ENDPOINT);

        assertThat(waits).containsExactly(1, 2);
        verify(restTemplate, times(2)).getForEntity(HEALTH_URI, String.class);
        verify(restTemplate, never()).postForEntity(eq(OCR_URI), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void preservesHttpStatusAndBodyFromOcrFailure() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.getForEntity(HEALTH_URI, String.class))
                .thenReturn(ResponseEntity.ok("{\"ocrReady\":true}"));
        when(restTemplate.postForEntity(eq(OCR_URI), any(HttpEntity.class), eq(String.class)))
                .thenThrow(HttpServerErrorException.create(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "error",
                        HttpHeaders.EMPTY,
                        "fallo interno".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8
                ));

        assertThatThrownBy(() -> client(restTemplate, 1, ignored -> { }).recognize(
                new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("500")
                .hasMessageContaining("fallo interno");
    }

    private OcrApiClient client(RestTemplate restTemplate, int maxAttempts, List<Integer> waits) {
        return client(restTemplate, maxAttempts, waits::add);
    }

    private OcrApiClient client(RestTemplate restTemplate, int maxAttempts, IntConsumer waiter) {
        return new OcrApiClient(
                new ObjectMapper(),
                restTemplate,
                OCR_ENDPOINT,
                HEALTH_ENDPOINT,
                "es",
                maxAttempts,
                waiter
        );
    }
}
