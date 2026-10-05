package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import java.net.URLDecoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleServiceAccountAuthTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    static String requestBody(HttpRequest request) {
        var subscriber = HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
        request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
            @Override public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { subscriber.onSubscribe(subscription); }
            @Override public void onNext(java.nio.ByteBuffer buffer) { subscriber.onNext(java.util.List.of(buffer)); }
            @Override public void onError(Throwable error) { subscriber.onError(error); }
            @Override public void onComplete() { subscriber.onComplete(); }
        });
        return subscriber.getBody().toCompletableFuture().join();
    }

    private KeyPair credentials(Path path) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        mapper.writeValue(path.toFile(), Map.of("type", "service_account", "client_email", "test@synthetic.iam.gserviceaccount.com",
                "private_key", pem, "token_uri", "https://untrusted.example/token"));
        return pair;
    }

    @Test void signsJwtForGoogleOnlyAndCachesThenRefreshesToken() throws Exception {
        Path path = directory.resolve("credentials.json");
        KeyPair pair = credentials(path);
        SheetsHttpTransport transport = mock(SheetsHttpTransport.class);
        when(transport.send(any())).thenReturn(new SheetsHttpTransport.Response(200, "{\"access_token\":\"synthetic-token\",\"expires_in\":3600}"));
        MutableClock clock = new MutableClock();
        GoogleServiceAccountAuth auth = new GoogleServiceAccountAuth(mapper, transport, path, clock);
        assertThat(auth.status().configured()).isTrue();
        verifyNoInteractions(transport);
        assertThat(auth.accessToken()).isEqualTo("synthetic-token");
        assertThat(auth.accessToken()).isEqualTo("synthetic-token");
        ArgumentCaptor<HttpRequest> capture = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).send(capture.capture());
        HttpRequest request = capture.getValue();
        assertThat(request.uri()).isEqualTo(GoogleServiceAccountAuth.TOKEN_ENDPOINT);
        String body = requestBody(request);
        String jwt = URLDecoder.decode(body.substring(body.indexOf("&assertion=") + 11), StandardCharsets.UTF_8);
        String[] pieces = jwt.split("\\.");
        var payload = mapper.readTree(Base64.getUrlDecoder().decode(pieces[1]));
        assertThat(payload.path("scope").asText()).isEqualTo(GoogleServiceAccountAuth.SHEETS_SCOPE);
        assertThat(payload.path("aud").asText()).isEqualTo("https://oauth2.googleapis.com/token");
        assertThat(payload.path("exp").asLong() - payload.path("iat").asLong()).isEqualTo(3600);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(pair.getPublic());
        signature.update((pieces[0] + "." + pieces[1]).getBytes(StandardCharsets.UTF_8));
        assertThat(signature.verify(Base64.getUrlDecoder().decode(pieces[2]))).isTrue();
        clock.now = clock.now.plusSeconds(3601);
        auth.accessToken();
        verify(transport, times(2)).send(any());
    }

    @Test void missingOrInvalidCredentialsDoNotContactGoogleOrExposeSecrets() throws Exception {
        Path path = directory.resolve("missing.json");
        SheetsHttpTransport transport = mock(SheetsHttpTransport.class);
        GoogleServiceAccountAuth auth = new GoogleServiceAccountAuth(mapper, transport, path, Clock.systemUTC());
        assertThat(auth.status().configured()).isFalse();
        assertThatThrownBy(auth::accessToken).hasMessageContaining("Falta el JSON");
        Files.writeString(path, "{\"type\":\"service_account\",\"private_key\":\"synthetic-secret\"}");
        assertThat(auth.status().message()).doesNotContain("synthetic-secret");
        verifyNoInteractions(transport);
    }

    @Test void upstreamAuthFailuresAreSanitized() throws Exception {
        Path path = directory.resolve("credentials.json");
        credentials(path);
        SheetsHttpTransport transport = mock(SheetsHttpTransport.class);
        when(transport.send(any())).thenReturn(new SheetsHttpTransport.Response(400, "synthetic-secret from upstream"));
        GoogleServiceAccountAuth auth = new GoogleServiceAccountAuth(mapper, transport, path, Clock.systemUTC());
        assertThatThrownBy(auth::accessToken).hasMessageContaining("rechazó").hasMessageNotContaining("synthetic-secret");
        verify(transport).send(any());
    }

    private static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-04T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
