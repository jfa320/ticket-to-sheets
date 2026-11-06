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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Local, single-user OAuth authorization. Never returns refresh tokens or the client secret. */
@Service
public class GoogleOAuthService {
    static final URI TOKEN_ENDPOINT = URI.create("https://oauth2.googleapis.com/token");
    static final URI REVOKE_ENDPOINT = URI.create("https://oauth2.googleapis.com/revoke");
    static final String FILE_SCOPE = "https://www.googleapis.com/auth/drive.file";
    public static final String CALLBACK_PATH = "/api/sheets/oauth/callback";
    private final ObjectMapper mapper;
    private final SheetsHttpTransport transport;
    private final String clientId, clientSecret, pickerApiKey, appId, redirectUri;
    private final Path tokenPath;
    private final Clock clock;
    private final Duration timeout;
    private final Map<String, Attempt> attempts = new LinkedHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private String accessToken;
    private long expiresAt;

    @Autowired
    public GoogleOAuthService(ObjectMapper mapper, SheetsHttpTransport transport,
            @Value("${app.google.client-id:}") String clientId,
            @Value("${app.google.client-secret:}") String clientSecret,
            @Value("${app.google.picker-api-key:}") String pickerApiKey,
            @Value("${app.google.app-id:}") String appId,
            @Value("${app.google.redirect-uri:http://localhost:8080/api/sheets/oauth/callback}") String redirectUri,
            @Value("${app.google.tokens-path:data/google-oauth-tokens.json}") String tokenPath,
            @Value("${app.sheets.timeout-ms:15000}") int timeoutMs) {
        this(mapper, transport, clientId, clientSecret, pickerApiKey, appId, redirectUri,
                Path.of(tokenPath), Clock.systemUTC(), timeoutMs);
    }

    GoogleOAuthService(ObjectMapper mapper, SheetsHttpTransport transport, String clientId, String clientSecret,
            String pickerApiKey, String appId, String redirectUri, Path tokenPath, Clock clock, int timeoutMs) {
        this.mapper = mapper; this.transport = transport;
        this.clientId = clientId.trim(); this.clientSecret = clientSecret.trim();
        this.pickerApiKey = pickerApiKey.trim(); this.appId = appId.trim(); this.redirectUri = redirectUri.trim();
        this.tokenPath = tokenPath; this.clock = clock;
        this.timeout = Duration.ofMillis(Math.max(1000, Math.min(timeoutMs, 120000)));
    }

    public synchronized Status status() {
        String problem = configurationError();
        if (problem != null) return new Status(false, false, problem);
        try {
            boolean connected = !refreshToken().isBlank();
            return new Status(true, connected, connected ? "Google conectado. Elegí la planilla que querés usar."
                    : "Conectá tu cuenta de Google para autorizar una planilla.");
        } catch (IllegalStateException error) { return new Status(true, false, error.getMessage()); }
    }

    public String origin() {
        requireConfiguration();
        URI uri = URI.create(redirectUri);
        return uri.getScheme() + "://" + uri.getRawAuthority();
    }

    public synchronized Begin begin() {
        requireConfiguration();
        long now = clock.instant().getEpochSecond();
        attempts.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
        if (attempts.size() >= 16) attempts.remove(attempts.keySet().iterator().next());
        String state = randomValue(), browser = randomValue(), verifier = randomValue();
        attempts.put(state, new Attempt(browser, verifier, now + 600));
        String challenge;
        try { challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException("No se pudo iniciar la conexión con Google."); }
        String url = "https://accounts.google.com/o/oauth2/v2/auth?" + form(Map.of(
                "client_id", clientId, "redirect_uri", redirectUri, "response_type", "code",
                "scope", FILE_SCOPE, "access_type", "offline", "prompt", "consent select_account",
                "state", state, "code_challenge", challenge, "code_challenge_method", "S256"));
        return new Begin(url, browser);
    }

    public synchronized void complete(String state, String browser, String code, String error) {
        requireConfiguration();
        Attempt attempt = state == null ? null : attempts.get(state);
        if (attempt == null || attempt.expiresAt <= clock.instant().getEpochSecond() || browser == null
                || !MessageDigest.isEqual(attempt.browser.getBytes(StandardCharsets.US_ASCII), browser.getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("La conexión con Google venció o no corresponde a este navegador. Volvé a iniciarla.");
        }
        attempts.remove(state); // Each state is single-use, including cancellations and failed exchanges.
        if (error != null) throw new IllegalArgumentException("No se autorizó el acceso a Google. Podés volver a conectar cuando quieras.");
        if (code == null || code.isBlank() || code.length() > 4096) throw new IllegalArgumentException("Google no devolvió una autorización válida.");
        JsonNode response = exchange(Map.of("grant_type", "authorization_code", "code", code,
                "redirect_uri", redirectUri, "code_verifier", attempt.verifier, "client_id", clientId, "client_secret", clientSecret));
        String refresh = response.path("refresh_token").asText();
        validateTokenResponse(response, true);
        if (refresh.isBlank() || refresh.length() > 16384) throw new IllegalStateException("Google no habilitó la conexión persistente. Volvé a conectar y aceptá los permisos.");
        saveRefreshToken(refresh);
        useAccessToken(response);
    }

    synchronized String accessToken() {
        requireConfiguration();
        if (accessToken != null && expiresAt > clock.instant().getEpochSecond() + 60) return accessToken;
        String refresh = refreshToken();
        if (refresh.isBlank()) throw new IllegalStateException("Conectá tu cuenta de Google desde el engranaje de Google Sheets.");
        JsonNode response = exchange(Map.of("grant_type", "refresh_token", "refresh_token", refresh,
                "client_id", clientId, "client_secret", clientSecret));
        validateTokenResponse(response, false);
        String rotated = response.path("refresh_token").asText();
        if (!rotated.isBlank()) saveRefreshToken(rotated);
        useAccessToken(response);
        return accessToken;
    }

    synchronized void invalidateToken() { accessToken = null; expiresAt = 0; }

    public synchronized PickerSession pickerSession() {
        return new PickerSession(accessToken(), pickerApiKey, appId);
    }

    public synchronized String disconnect() {
        String token;
        try { token = refreshToken(); } catch (IllegalStateException error) { token = ""; }
        // Clear on disk first: never resurrect a revoked session after restarting.
        saveRefreshToken("");
        invalidateToken(); attempts.clear();
        if (token.isBlank()) return "Google desconectado de esta aplicación.";
        try {
            var response = transport.send(formRequest(REVOKE_ENDPOINT, Map.of("token", token)));
            if (response.statusCode() == 200) return "Google desconectado y autorización revocada.";
            if (response.statusCode() == 400) {
                JsonNode failure = mapper.readTree(response.body());
                if (failure != null && "invalid_token".equals(failure.path("error").asText())) {
                    return "Google desconectado. La autorización ya no era válida.";
                }
            }
        } catch (IOException error) { /* Local disconnect still succeeded. */ }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        return "Google desconectado de esta aplicación. No se pudo confirmar la revocación; podés quitar el acceso desde tu cuenta de Google.";
    }

    private JsonNode exchange(Map<String, String> parameters) {
        try {
            var response = transport.send(formRequest(TOKEN_ENDPOINT, parameters));
            if (response.statusCode() != 200) {
                // Only inspect a fixed error code; never expose upstream descriptions or secrets.
                if (parameters.containsKey("refresh_token") && response.statusCode() == 400) {
                    JsonNode failure = mapper.readTree(response.body());
                    if (failure != null && "invalid_grant".equals(failure.path("error").asText())) {
                        saveRefreshToken(""); invalidateToken();
                        throw new IllegalStateException("La autorización de Google venció o fue revocada. Volvé a conectar tu cuenta.");
                    }
                }
                throw new IllegalStateException("Google rechazó la autorización. Revisá la configuración OAuth y volvé a conectar.");
            }
            JsonNode json = mapper.readTree(response.body());
            if (json == null || !json.isObject()) throw new IllegalStateException("Google devolvió una autorización inválida.");
            return json;
        } catch (IOException error) { throw new IllegalStateException("No se pudo conectar con Google. Revisá Internet y volvé a intentar."); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Se interrumpió la conexión con Google.");
        }
    }

    private void validateTokenResponse(JsonNode response, boolean requireScope) {
        int seconds = response.path("expires_in").asInt();
        String scope = response.path("scope").asText();
        if (!"Bearer".equalsIgnoreCase(response.path("token_type").asText()) || response.path("access_token").asText().isBlank()
                || seconds < 60 || seconds > 86400 || ((requireScope || !scope.isBlank())
                && !Arrays.asList(scope.split("\\s+")).contains(FILE_SCOPE))) {
            throw new IllegalStateException("Google no concedió los permisos necesarios para elegir y editar la planilla.");
        }
    }

    private void useAccessToken(JsonNode response) {
        accessToken = response.path("access_token").asText();
        expiresAt = clock.instant().getEpochSecond() + response.path("expires_in").asInt();
    }

    private String refreshToken() {
        if (!Files.exists(tokenPath)) return "";
        try {
            if (Files.size(tokenPath) > 65536) throw new IOException();
            JsonNode json = mapper.readTree(tokenPath.toFile());
            if (json == null || !json.isObject()) throw new IOException();
            return clientId.equals(json.path("clientId").asText()) ? json.path("refreshToken").asText() : "";
        } catch (IOException error) { throw new IllegalStateException("No se pudo leer la conexión guardada de Google. Volvé a conectar tu cuenta."); }
    }

    private void saveRefreshToken(String token) {
        try { AtomicJsonFile.replaceWithoutBackup(mapper, tokenPath, Map.of("clientId", clientId, "refreshToken", token)); }
        catch (IOException error) { throw new IllegalStateException("No se pudo guardar la conexión de Google en el equipo. Revisá los permisos de la carpeta data."); }
    }

    private HttpRequest formRequest(URI uri, Map<String, String> values) {
        return HttpRequest.newBuilder(uri).timeout(timeout).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(values))).build();
    }

    private static String form(Map<String, String> values) {
        return values.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private String randomValue() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private void requireConfiguration() {
        String problem = configurationError(); if (problem != null) throw new IllegalStateException(problem);
    }
    private String configurationError() {
        if (!clientId.matches("[A-Za-z0-9_-]+\\.apps\\.googleusercontent\\.com") || clientSecret.isBlank()
                || pickerApiKey.isBlank() || !appId.matches("[0-9]+")) {
            return "Falta configurar la conexión con Google. Consultá la guía docs/GOOGLE_SHEETS.md del proyecto.";
        }
        try {
            URI uri = URI.create(redirectUri);
            if (!"http".equals(uri.getScheme()) || !("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()))
                    || !CALLBACK_PATH.equals(uri.getPath()) || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getUserInfo() != null) {
                return "La URL de retorno de Google debe ser local y terminar en " + CALLBACK_PATH + ".";
            }
        } catch (IllegalArgumentException error) { return "La URL de retorno de Google no es válida."; }
        return null;
    }

    private record Attempt(String browser, String verifier, long expiresAt) { }
    public record Begin(String authorizationUrl, String browserCookie) { }
    public record Status(boolean configured, boolean connected, String message) { }
    public record PickerSession(String accessToken, String developerKey, String appId) { }
}
