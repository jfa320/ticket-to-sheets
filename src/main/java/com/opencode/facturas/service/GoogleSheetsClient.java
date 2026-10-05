package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Service
public class GoogleSheetsClient {
    private static final String BASE = "https://sheets.googleapis.com/v4/spreadsheets/";
    private final ObjectMapper mapper;
    private final GoogleServiceAccountAuth auth;
    private final SheetsHttpTransport transport;
    private final Duration timeout;

    public GoogleSheetsClient(ObjectMapper mapper, GoogleServiceAccountAuth auth, SheetsHttpTransport transport,
                              @Value("${app.sheets.timeout-ms:15000}") int timeoutMs) {
        this.mapper = mapper;
        this.auth = auth;
        this.transport = transport;
        this.timeout = Duration.ofMillis(Math.max(1000, Math.min(timeoutMs, 120000)));
    }

    JsonNode metadata(String id) {
        return request(id, "?fields=" + encode("properties(title),sheets(properties,merges,basicFilter,tables(tableId,range,rowsProperties(footerColorStyle)),protectedRanges(range,warningOnly,requestingUserCanEdit))"), null);
    }

    JsonNode values(String id, String range) {
        return request(id, "/values/" + encode(range)
                + "?valueRenderOption=FORMULA&dateTimeRenderOption=SERIAL_NUMBER", null);
    }

    JsonNode findRequest(String id, Object body) {
        return request(id, "/developerMetadata:search", body);
    }

    JsonNode historyValues(String id, String range) {
        return request(id, "/values/" + encode(range) + "?valueRenderOption=UNFORMATTED_VALUE", null);
    }

    JsonNode batchUpdate(String id, Object body) {
        return request(id, ":batchUpdate", body);
    }

    private JsonNode request(String id, String suffix, Object body) {
        // Validate IDs even if this client is called outside the configuration service.
        String validatedId = SheetsConfigStore.extractId(id);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(BASE + validatedId + suffix))
                .timeout(timeout).header("Authorization", "Bearer " + auth.accessToken());
        try {
            if (body == null) {
                builder.GET();
            } else {
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            }
            SheetsHttpTransport.Response response = transport.send(builder.build());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                if (response.statusCode() == 401) {
                    auth.invalidateToken();
                }
                throw new IllegalStateException(errorMessage(response.statusCode()));
            }
            JsonNode json = mapper.readTree(response.body());
            if (json == null || !json.isObject()) {
                throw new IllegalStateException("Google Sheets devolvió una respuesta inválida.");
            }
            return json;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Se interrumpió la conexión con Google Sheets. Reintentá la misma carga para confirmar su resultado.");
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo confirmar la respuesta de Google Sheets. Revisá Internet y reintentá la misma carga; se verificará si ya fue guardada.");
        }
    }

    private static String errorMessage(int status) {
        return switch (status) {
            case 400 -> "Google Sheets rechazó la carga. Revisá la pestaña, sus permisos y el formato de la planilla.";
            case 401 -> "Google Sheets rechazó la sesión. Reintentá para autenticar otra vez.";
            case 403 -> "Google Sheets denegó el acceso. Activá la API de Google Sheets y compartí la planilla con la cuenta de servicio como Editor.";
            case 404 -> "No se encontró la planilla o la cuenta de servicio no tiene acceso. Revisá el enlace y los permisos.";
            case 429 -> "Google Sheets alcanzó su límite de solicitudes. Esperá unos momentos y reintentá la misma carga.";
            default -> "Google Sheets no pudo confirmar la operación. Reintentá la misma carga para verificar si ya fue guardada.";
        };
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
