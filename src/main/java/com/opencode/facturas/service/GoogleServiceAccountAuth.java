package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

@Service
public class GoogleServiceAccountAuth {
    static final URI TOKEN_ENDPOINT = URI.create("https://oauth2.googleapis.com/token");
    static final String SHEETS_SCOPE = "https://www.googleapis.com/auth/spreadsheets";
    private final ObjectMapper mapper;
    private final SheetsHttpTransport transport;
    private final Path credentialsPath;
    private final Clock clock;
    private final Duration timeout;
    private Credentials cachedCredentials;
    private long credentialsModified;
    private long credentialsSize;
    private String accessToken;
    private long tokenExpiresAt;

    @Autowired
    public GoogleServiceAccountAuth(ObjectMapper mapper, SheetsHttpTransport transport,
            @Value("${app.sheets.credentials-path:data/google-service-account.json}") String credentialsPath,
            @Value("${app.sheets.timeout-ms:15000}") int timeoutMs) {
        this(mapper, transport, Path.of(credentialsPath), Clock.systemUTC(), timeoutMs);
    }

    GoogleServiceAccountAuth(ObjectMapper mapper, SheetsHttpTransport transport, Path credentialsPath, Clock clock) {
        this(mapper, transport, credentialsPath, clock, 15000);
    }

    private GoogleServiceAccountAuth(ObjectMapper mapper, SheetsHttpTransport transport, Path credentialsPath, Clock clock, int timeoutMs) {
        this.mapper = mapper;
        this.transport = transport;
        this.credentialsPath = credentialsPath;
        this.clock = clock;
        this.timeout = Duration.ofMillis(Math.max(1000, Math.min(timeoutMs, 120000)));
    }

    public synchronized CredentialStatus status() {
        try {
            Credentials credentials = credentials();
            return new CredentialStatus(true, credentials.email, "Compartí el Google Sheet con esta cuenta de servicio como Editor.");
        } catch (IllegalStateException ex) {
            return new CredentialStatus(false, "", ex.getMessage());
        }
    }

    synchronized String accessToken() {
        Credentials credentials = credentials();
        long now = clock.instant().getEpochSecond();
        if (accessToken != null && tokenExpiresAt > now + 60) {
            return accessToken;
        }
        String assertion = assertion(credentials, now);
        String body = "grant_type=" + encode("urn:ietf:params:oauth:grant-type:jwt-bearer")
                + "&assertion=" + encode(assertion);
        HttpRequest request = HttpRequest.newBuilder(TOKEN_ENDPOINT).timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try {
            SheetsHttpTransport.Response response = transport.send(request);
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Google rechazó la autenticación. Verificá la clave de la cuenta de servicio y la hora del equipo.");
            }
            JsonNode json = mapper.readTree(response.body());
            String token = json.path("access_token").asText();
            int expiresIn = json.path("expires_in").asInt();
            if (token.isBlank() || expiresIn < 60 || expiresIn > 86400) {
                throw new IllegalStateException("Google devolvió una respuesta de autenticación inválida.");
            }
            accessToken = token;
            tokenExpiresAt = now + expiresIn;
            return accessToken;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Se interrumpió la conexión de autenticación con Google.");
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo conectar con Google para autenticar. Revisá la conexión a Internet.");
        }
    }

    synchronized void invalidateToken() {
        accessToken = null;
        tokenExpiresAt = 0;
    }

    private Credentials credentials() {
        try {
            if (!Files.isRegularFile(credentialsPath)) {
                throw new IllegalStateException("Falta el JSON de la cuenta de servicio. Guardalo en data/google-service-account.json o configurá APP_SHEETS_CREDENTIALS_PATH.");
            }
            long size = Files.size(credentialsPath);
            long modified = Files.getLastModifiedTime(credentialsPath).toMillis();
            if (size <= 0 || size > 65536) {
                throw new IllegalStateException("El archivo de credenciales de Google no es un JSON de cuenta de servicio válido.");
            }
            if (cachedCredentials != null && size == credentialsSize && modified == credentialsModified) {
                return cachedCredentials;
            }
            JsonNode json = mapper.readTree(credentialsPath.toFile());
            String email = json.path("client_email").asText();
            String pem = json.path("private_key").asText();
            if (!"service_account".equals(json.path("type").asText())
                    || !email.matches("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.gserviceaccount\\.com")
                    || !pem.startsWith("-----BEGIN PRIVATE KEY-----") || !pem.contains("-----END PRIVATE KEY-----")) {
                throw new IllegalStateException("El archivo de credenciales de Google debe corresponder a una cuenta de servicio con email y clave privada RSA.");
            }
            byte[] keyBytes = Base64.getDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", ""));
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            cachedCredentials = new Credentials(email, key);
            credentialsSize = size;
            credentialsModified = modified;
            invalidateToken();
            return cachedCredentials;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            cachedCredentials = null;
            invalidateToken();
            throw new IllegalStateException("No se pudo leer el JSON de la cuenta de servicio. Verificá el archivo y su clave privada RSA.");
        }
    }

    private String assertion(Credentials credentials, long now) {
        try {
            String header = base64(mapper.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT")));
            String payload = base64(mapper.writeValueAsBytes(Map.of("iss", credentials.email,
                    "scope", SHEETS_SCOPE, "aud", TOKEN_ENDPOINT.toString(), "iat", now, "exp", now + 3600)));
            String unsigned = header + "." + payload;
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(credentials.key);
            signature.update(unsigned.getBytes(StandardCharsets.UTF_8));
            return unsigned + "." + base64(signature.sign());
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo firmar la autenticación de la cuenta de servicio.");
        }
    }

    private static String base64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static final class Credentials {
        private final String email;
        private final PrivateKey key;
        private Credentials(String email, PrivateKey key) { this.email = email; this.key = key; }
    }

    public record CredentialStatus(boolean configured, String email, String message) { }
}
