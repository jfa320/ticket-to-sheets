package com.opencode.facturas.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class SheetsHttpTransport {
    private HttpClient client;

    public SheetsHttpTransport() { }

    SheetsHttpTransport(HttpClient client) {
        this.client = client;
    }

    Response send(HttpRequest request) throws IOException, InterruptedException {
        try {
            HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (java.io.UncheckedIOException ex) {
            throw new IOException("No se pudo iniciar el transporte HTTPS de Google.", ex);
        }
    }

    private synchronized HttpClient client() {
        if (client == null) {
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
        }
        return client;
    }

    record Response(int statusCode, String body) { }
}
