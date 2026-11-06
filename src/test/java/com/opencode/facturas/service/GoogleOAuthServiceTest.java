package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleOAuthServiceTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper();
    final SheetsHttpTransport transport = mock(SheetsHttpTransport.class);
    final MutableClock clock = new MutableClock();
    final String redirect = "http://localhost:8080/api/sheets/oauth/callback";

    GoogleOAuthService auth() { return auth("synthetic.apps.googleusercontent.com", redirect); }
    GoogleOAuthService auth(String clientId, String callback) {
        return new GoogleOAuthService(mapper, transport, clientId, "synthetic-client-secret", "synthetic-picker-key",
                "123456789", callback, directory.resolve("tokens.json"), clock, 12345);
    }
    String tokenResponse(String extra) {
        return "{\"access_token\":\"synthetic-access\",\"token_type\":\"Bearer\",\"expires_in\":3600," + extra + "}";
    }
    void successfulExchange() throws Exception {
        when(transport.send(any())).thenReturn(new SheetsHttpTransport.Response(200, tokenResponse(
                "\"scope\":\"" + GoogleOAuthService.FILE_SCOPE + "\",\"refresh_token\":\"synthetic-refresh\"")));
    }
    void connect(GoogleOAuthService service) {
        var begin = service.begin();
        service.complete(query(begin.authorizationUrl()).get("state"), begin.browserCookie(), "synthetic-code", null);
    }
    static Map<String,String> query(String url) { return form(URI.create(url).getRawQuery()); }
    static Map<String,String> form(String form) {
        Map<String,String> result = new HashMap<>();
        for (String pair : form.split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return result;
    }
    static String requestBody(HttpRequest request) {
        var subscriber = HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
        request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
            @Override public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { subscriber.onSubscribe(subscription); }
            @Override public void onNext(java.nio.ByteBuffer buffer) { subscriber.onNext(List.of(buffer)); }
            @Override public void onError(Throwable error) { subscriber.onError(error); }
            @Override public void onComplete() { subscriber.onComplete(); }
        });
        return subscriber.getBody().toCompletableFuture().join();
    }

    @Test void requestsOnlyFileAccessWithPkceAndBrowserBoundSingleUseState() throws Exception {
        var service = auth(); successfulExchange();
        var begin = service.begin(); var parameters = query(begin.authorizationUrl());
        assertThat(parameters).containsEntry("scope", GoogleOAuthService.FILE_SCOPE)
                .containsEntry("access_type", "offline").containsEntry("redirect_uri", redirect)
                .containsEntry("code_challenge_method", "S256");
        assertThat(begin.authorizationUrl()).doesNotContain("synthetic-client-secret", "spreadsheets", "refresh_token");
        assertThatThrownBy(() -> service.complete(parameters.get("state"), "other-browser", "code", null)).hasMessageContaining("navegador");
        verifyNoInteractions(transport);
        service.complete(parameters.get("state"), begin.browserCookie(), "synthetic-code", null);
        ArgumentCaptor<HttpRequest> capture = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).send(capture.capture());
        var request = capture.getValue(); var body = form(requestBody(request));
        assertThat(request.uri()).isEqualTo(GoogleOAuthService.TOKEN_ENDPOINT);
        assertThat(request.timeout()).contains(Duration.ofMillis(12345));
        assertThat(body).containsEntry("grant_type", "authorization_code").containsEntry("code", "synthetic-code");
        assertThat(Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(body.get("code_verifier").getBytes(StandardCharsets.US_ASCII)))).isEqualTo(parameters.get("code_challenge"));
        assertThatThrownBy(() -> service.complete(parameters.get("state"), begin.browserCookie(), "code", null)).hasMessageContaining("venció");
        assertThat(service.status().connected()).isTrue();
    }

    @Test void missingConfigurationExpiredDeniedOrForgedCallbacksNeverExchangeCodes() {
        var missing = auth("", redirect);
        assertThat(missing.status().configured()).isFalse(); assertThatThrownBy(missing::begin).hasMessageContaining("configurar");
        assertThat(auth("synthetic.apps.googleusercontent.com", "http://evil.example" + GoogleOAuthService.CALLBACK_PATH).status().configured()).isFalse();
        var service = auth(); var begin = service.begin(); var state = query(begin.authorizationUrl()).get("state");
        assertThatThrownBy(() -> service.complete(state, begin.browserCookie(), null, "sensitive-denial")).hasMessageNotContaining("sensitive");
        assertThatThrownBy(() -> service.complete(state, begin.browserCookie(), "code", null)).hasMessageContaining("venció");
        final var expired = service.begin();
        clock.now = clock.now.plusSeconds(601);
        assertThatThrownBy(() -> service.complete(query(expired.authorizationUrl()).get("state"), expired.browserCookie(), "code", null)).hasMessageContaining("venció");
        assertThatThrownBy(() -> service.complete("forged", "browser", "code", null)).hasMessageContaining("navegador");
        verifyNoInteractions(transport);
    }

    @Test void persistsOnlyRefreshTokenCachesAccessAndRefreshesAfterRestartAndExpiry() throws Exception {
        var service = auth(); successfulExchange(); connect(service);
        assertThat(Files.readString(directory.resolve("tokens.json"))).contains("synthetic-refresh")
                .doesNotContain("synthetic-access", "synthetic-client-secret", "synthetic-picker-key");
        assertThat(Files.exists(directory.resolve("tokens.json.bak"))).isFalse();
        assertThat(service.accessToken()).isEqualTo("synthetic-access"); verify(transport).send(any());
        reset(transport);
        when(transport.send(any())).thenReturn(new SheetsHttpTransport.Response(200, tokenResponse("\"refresh_token\":\"rotated-refresh\"")));
        var restarted = auth(); assertThat(restarted.status().connected()).isTrue(); verifyNoInteractions(transport);
        assertThat(restarted.accessToken()).isEqualTo("synthetic-access");
        assertThat(Files.readString(directory.resolve("tokens.json"))).contains("rotated-refresh").doesNotContain("synthetic-refresh");
        assertThat(restarted.pickerSession().accessToken()).isEqualTo("synthetic-access");
        assertThat(mapper.writeValueAsString(restarted.status())).doesNotContain("refresh", "secret", "synthetic-access");
        assertThat(auth("other.apps.googleusercontent.com", redirect).status().connected()).isFalse();
        clock.now = clock.now.plusSeconds(3600); restarted.accessToken();
        verify(transport, times(2)).send(any());
    }

    @Test void revokedRefreshRequiresReconnectButTemporaryFailuresPreserveConnection() throws Exception {
        var service = auth(); successfulExchange(); connect(service); service.invalidateToken();
        when(transport.send(any())).thenThrow(new IOException("sensitive-secret"));
        assertThatThrownBy(service::accessToken).hasMessageContaining("Internet").hasMessageNotContaining("sensitive-secret");
        assertThat(service.status().connected()).isTrue();
        doReturn(new SheetsHttpTransport.Response(400, "{\"error\":\"invalid_grant\",\"error_description\":\"sensitive-secret\"}"))
                .when(transport).send(any());
        assertThatThrownBy(service::accessToken).hasMessageContaining("revocada").hasMessageNotContaining("sensitive-secret");
        assertThat(service.status().connected()).isFalse();
        assertThat(Files.readString(directory.resolve("tokens.json"))).doesNotContain("synthetic-refresh");
    }

    @Test void requiresScopeAndNewRefreshTokenAndSanitizesUpstreamErrors() throws Exception {
        var service = auth();
        when(transport.send(any())).thenReturn(new SheetsHttpTransport.Response(200, tokenResponse("\"scope\":\"unrelated\",\"refresh_token\":\"secret\"")));
        assertThatThrownBy(() -> connect(service)).hasMessageContaining("permisos");
        when(transport.send(any())).thenReturn(new SheetsHttpTransport.Response(200, tokenResponse("\"scope\":\"" + GoogleOAuthService.FILE_SCOPE + "\"")));
        assertThatThrownBy(() -> connect(service)).hasMessageContaining("persistente");
        when(transport.send(any())).thenReturn(new SheetsHttpTransport.Response(400, "sensitive-upstream-secret"));
        assertThatThrownBy(() -> connect(service)).hasMessageContaining("rechazó").hasMessageNotContaining("sensitive-upstream-secret");
        assertThat(service.status().connected()).isFalse();
    }

    @Test void disconnectClearsTokensAndPendingLoginsEvenWhenRevocationCannotBeConfirmed() throws Exception {
        var service = auth(); successfulExchange(); connect(service); var pending = service.begin();
        when(transport.send(any())).thenThrow(new IOException("private"));
        assertThat(service.disconnect()).contains("No se pudo confirmar");
        assertThat(service.status().connected()).isFalse();
        assertThatThrownBy(service::accessToken).hasMessageContaining("Conectá");
        assertThatThrownBy(() -> service.complete(query(pending.authorizationUrl()).get("state"), pending.browserCookie(), "code", null)).hasMessageContaining("venció");
        assertThat(auth().status().connected()).isFalse();
    }

    @Test void disconnectRevokesAtGoogleAndDoesNotTreatEveryBadRequestAsConfirmed() throws Exception {
        var service = auth(); successfulExchange(); connect(service);
        when(transport.send(any())).thenReturn(new SheetsHttpTransport.Response(200, ""));
        assertThat(service.disconnect()).contains("revocada");
        ArgumentCaptor<HttpRequest> capture = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(2)).send(capture.capture());
        var revoke = capture.getAllValues().get(1);
        assertThat(revoke.uri()).isEqualTo(GoogleOAuthService.REVOKE_ENDPOINT);
        assertThat(form(requestBody(revoke))).containsEntry("token", "synthetic-refresh");
        successfulExchange(); connect(service);
        when(transport.send(any())).thenReturn(new SheetsHttpTransport.Response(400, "{\"error\":\"invalid_request\"}"));
        assertThat(service.disconnect()).contains("No se pudo confirmar");
        assertThat(service.status().connected()).isFalse();
    }

    private static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-06T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
