package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleSheetsClientTest {
    private static final String ID = "synthetic_spreadsheet_id_1234567890";

    @Test void usesFixedGoogleEndpointAndConfiguredTimeout() throws Exception {
        GoogleServiceAccountAuth auth = mock(GoogleServiceAccountAuth.class);
        SheetsHttpTransport transport = mock(SheetsHttpTransport.class);
        when(auth.accessToken()).thenReturn("synthetic-token");
        when(transport.send(any())).thenReturn(new SheetsHttpTransport.Response(200, "{}"));
        GoogleSheetsClient client = new GoogleSheetsClient(new ObjectMapper(), auth, transport, 12345);
        client.values(ID, "'Mes '' Prueba'!A2:I10");
        ArgumentCaptor<HttpRequest> capture = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).send(capture.capture());
        assertThat(capture.getValue().uri().getHost()).isEqualTo("sheets.googleapis.com");
        assertThat(URLDecoder.decode(capture.getValue().uri().toString(), StandardCharsets.UTF_8)).contains("'Mes '' Prueba'!A2:I10", "valueRenderOption=FORMULA");
        assertThat(capture.getValue().timeout()).contains(Duration.ofMillis(12345));
        assertThat(capture.getValue().headers().firstValue("Authorization")).contains("Bearer synthetic-token");
    }

    @Test void neverAutomaticallyRetriesAnUnconfirmedWrite() throws Exception {
        GoogleServiceAccountAuth auth = mock(GoogleServiceAccountAuth.class);
        SheetsHttpTransport transport = mock(SheetsHttpTransport.class);
        when(auth.accessToken()).thenReturn("synthetic-token");
        when(transport.send(any())).thenThrow(new IOException("synthetic-sensitive upstream error"));
        GoogleSheetsClient client = new GoogleSheetsClient(new ObjectMapper(), auth, transport, 15000);
        assertThatThrownBy(() -> client.batchUpdate(ID, Map.of("requests", java.util.List.of())))
                .hasMessageContaining("reintentá la misma carga").hasMessageNotContaining("synthetic-sensitive");
        verify(transport).send(any());
    }

    @Test void historyReadsEvaluatedValuesFromAnExplicitBoundedRange() throws Exception {
        GoogleServiceAccountAuth auth = mock(GoogleServiceAccountAuth.class);
        SheetsHttpTransport transport = mock(SheetsHttpTransport.class);
        when(auth.accessToken()).thenReturn("synthetic-token");
        when(transport.send(any())).thenReturn(new SheetsHttpTransport.Response(200, "{}"));
        GoogleSheetsClient client = new GoogleSheetsClient(new ObjectMapper(), auth, transport, 15000);
        client.historyValues(ID, "'Mes '' Prueba'!A3:D10");
        ArgumentCaptor<HttpRequest> capture = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).send(capture.capture());
        assertThat(capture.getValue().method()).isEqualTo("GET");
        assertThat(URLDecoder.decode(capture.getValue().uri().toString(), StandardCharsets.UTF_8))
                .contains("'Mes '' Prueba'!A3:D10", "valueRenderOption=UNFORMATTED_VALUE").doesNotContain("FORMULA");
    }

    @Test void invalidatesExpiredTokenAndSanitizesPermissionFailure() throws Exception {
        GoogleServiceAccountAuth auth = mock(GoogleServiceAccountAuth.class);
        SheetsHttpTransport transport = mock(SheetsHttpTransport.class);
        when(auth.accessToken()).thenReturn("synthetic-token");
        when(transport.send(any())).thenReturn(new SheetsHttpTransport.Response(401, "synthetic-secret"))
                .thenReturn(new SheetsHttpTransport.Response(403, "synthetic-secret"));
        GoogleSheetsClient client = new GoogleSheetsClient(new ObjectMapper(), auth, transport, 15000);
        assertThatThrownBy(() -> client.metadata(ID)).hasMessageContaining("sesión").hasMessageNotContaining("synthetic-secret");
        verify(auth).invalidateToken();
        assertThatThrownBy(() -> client.metadata(ID)).hasMessageContaining("Editor").hasMessageNotContaining("synthetic-secret");
        verify(transport, times(2)).send(any());
    }
}
