package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.facturas.model.ReceiptItem;
import com.opencode.facturas.model.SheetsModels.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SheetsServiceTest {
    private static final String ID = "synthetic_spreadsheet_id_1234567890";
    private static final String UUID = "00000000-0000-4000-8000-000000000001";
    private static final String TAB = "Mes ' Prueba";
    private static final List<String> HEADERS = List.of("Descripción", "Marca", "Lugar de compra", "Categoria",
            "Cantidad", "Precio unitario", "Fecha", "Precio total", "Comentarios");
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private GoogleSheetsClient client;
    private SheetsConfigStore config;
    private SheetsService service;
    private JsonNode metadata;
    private Destination destination;

    @BeforeEach void prepare() throws Exception {
        config = new SheetsConfigStore(mapper, directory.resolve("destination.json").toString());
        config.save(new ConfigRequest(ID, 2026, 2, Map.of("10", TAB)));
        destination = new Destination(ID, TAB, 2);
        GoogleServiceAccountAuth auth = mock(GoogleServiceAccountAuth.class);
        when(auth.status()).thenReturn(new GoogleServiceAccountAuth.CredentialStatus(false, "", "Falta credencial"));
        client = mock(GoogleSheetsClient.class);
        service = new SheetsService(mapper, config, auth, client);
        metadata = mapper.readTree("""
                {"properties":{"title":"Compras sintéticas"},"sheets":[{"properties":{
                "sheetId":42,"title":"Mes ' Prueba","sheetType":"GRID","gridProperties":{"rowCount":10,"columnCount":15}},
                "basicFilter":{"range":{"sheetId":42,"startRowIndex":1,"endRowIndex":4,"startColumnIndex":0,"endColumnIndex":9},
                "criteria":{"3":{"hiddenValues":["Otros"]}}}}]}
                """);
        when(client.metadata(ID)).thenReturn(metadata);
        when(client.values(ID, "'Mes '' Prueba'!A2:I2")).thenReturn(mapper.valueToTree(Map.of("values", List.of(HEADERS))));
        when(client.values(ID, "'Mes '' Prueba'!A3:I10")).thenReturn(mapper.valueToTree(Map.of("values", List.of(
                List.of("Producto anterior", "", "", "", 1, 2, 46000, "=E3*F3"),
                List.of("", "", "", "", "", "", "", "=E4*F4")))));
        when(client.findRequest(eq(ID), any())).thenReturn(mapper.createObjectNode());
        when(client.batchUpdate(eq(ID), any())).thenReturn(mapper.createObjectNode());
    }

    private ReceiptItem item() {
        return new ReceiptItem("=SUM(A1:A2)", "Marca prueba", "Comercio prueba", "Otros", "0.875", "1.500,50", "4/10/2026");
    }

    private AppendRequest request() { return new AppendRequest(UUID, List.of(item()), destination, config.version()); }

    private JsonNode writtenBatch() {
        ArgumentCaptor<Object> capture = ArgumentCaptor.forClass(Object.class);
        verify(client).batchUpdate(eq(ID), capture.capture());
        return mapper.valueToTree(capture.getValue());
    }

    @Test void writesSelectedSnapshotWithTypedNumbersDatesAndLiteralTextOnlyInAtoH() {
        AppendResponse result = service.append(request());
        assertThat(result.updatedRange()).isEqualTo("'Mes '' Prueba'!A4:H4");
        assertThat(result.appendedRows()).isEqualTo(1);
        assertThat(result.duplicate()).isFalse();
        JsonNode batch = writtenBatch();
        JsonNode write = batch.path("requests").get(0).path("updateCells");
        assertThat(write.path("start").path("rowIndex").asInt()).isEqualTo(3);
        assertThat(write.path("fields").asText()).isEqualTo("userEnteredValue");
        JsonNode cells = write.path("rows").get(0).path("values");
        assertThat(cells.size()).isEqualTo(8);
        assertThat(cells.get(0).path("userEnteredValue").path("stringValue").asText()).isEqualTo("=SUM(A1:A2)");
        assertThat(cells.get(4).path("userEnteredValue").path("numberValue").decimalValue()).isEqualByComparingTo("0.875");
        assertThat(cells.get(5).path("userEnteredValue").path("numberValue").decimalValue()).isEqualByComparingTo("1500.50");
        assertThat(cells.get(6).path("userEnteredValue").path("numberValue").asLong()).isEqualTo(
                ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), LocalDate.of(2026, 10, 4)));
        assertThat(cells.get(7).path("userEnteredValue").path("formulaValue").asText()).isEqualTo("=E4*F4");
        assertThat(batch.findValue("createDeveloperMetadata").path("developerMetadata").path("metadataKey").asText())
                .isEqualTo("facturas.import." + UUID);
        assertThat(batch.toString()).doesNotContain("SUM(H:H)", "columnIndex\":8");
    }

    @Test void ignoresPreparedTotalsButProtectsCommentsAndCustomTotals() {
        when(client.values(ID, "'Mes '' Prueba'!A3:I10")).thenReturn(mapper.valueToTree(Map.of("values", List.of(
                List.of("", "", "", "", "", "", "", "=E3*F3", "Comentario existente"),
                List.of("", "", "", "", "", "", "", 0),
                List.of("", "", "", "", "", "", "", "=SUM(E5:F5)"),
                List.of("", "", "", "", "", "", "", "=$E$6*$F$6")))));
        assertThat(service.append(request()).updatedRange()).endsWith("A6:H6");
        JsonNode batch = writtenBatch();
        assertThat(batch.findValue("formulaValue").asText()).isEqualTo("=$E$6*$F$6");
        assertThat(batch.findValue("setBasicFilter").path("filter").path("criteria").path("3").path("hiddenValues").get(0).asText()).isEqualTo("Otros");
    }

    @Test void retryAfterLostResponseAndRestartFindsAtomicMarkerWithoutAnotherWrite() {
        doThrow(new IllegalStateException("Respuesta perdida")).when(client).batchUpdate(eq(ID), any());
        assertThatThrownBy(() -> service.append(request())).hasMessageContaining("Respuesta perdida");
        JsonNode batch = writtenBatch();
        JsonNode marker = batch.findValue("createDeveloperMetadata").path("developerMetadata");
        when(client.findRequest(eq(ID), any())).thenReturn(mapper.valueToTree(Map.of("matchedDeveloperMetadata",
                List.of(Map.of("developerMetadata", marker)))));
        clearInvocations(client);
        SheetsService restarted = new SheetsService(mapper, config, mock(GoogleServiceAccountAuth.class), client);
        assertThat(restarted.append(request()).duplicate()).isTrue();
        verify(client, never()).batchUpdate(anyString(), any());
        verify(client, never()).metadata(anyString());
        ReceiptItem changed = new ReceiptItem("Otro producto", "", "", "", "1", "10", "4/10/2026");
        assertThatThrownBy(() -> restarted.append(new AppendRequest(UUID, List.of(changed), destination, config.version())))
                .hasMessageContaining("otros datos");
    }

    @Test void rejectsInvalidHeadersBeforeAnyWrite() {
        when(client.values(ID, "'Mes '' Prueba'!A2:I2")).thenReturn(mapper.valueToTree(Map.of("values", List.of(List.of("Otra tabla")))));
        assertThatThrownBy(() -> service.append(request())).hasMessageContaining("encabezados");
        verify(client, never()).batchUpdate(anyString(), any());
    }

    @Test void neverChoosesADifferentMonthWhenConfiguredTabIsMissing() {
        config.save(new ConfigRequest(ID, 2026, 2, Map.of("10", "Octubre")));
        assertThatThrownBy(() -> service.append(new AppendRequest(UUID, List.of(item()),
                new Destination(ID, "Octubre", 2), config.version())))
                .hasMessageContaining("No existe la pestaña 'Octubre'").hasMessageContaining(TAB);
        verify(client, never()).batchUpdate(anyString(), any());
    }

    @Test void rejectsStaleDestinationBeforeGoogleAccess() {
        String previousVersion = config.version();
        config.save(new ConfigRequest(ID, 2027, 2, Map.of("10", TAB)));
        assertThatThrownBy(() -> service.append(new AppendRequest(UUID, List.of(item()), destination, previousVersion)))
                .hasMessageContaining("destino cambió");
        verifyNoInteractions(client);
    }

    @Test void rejectsMergedAndProtectedTargets() {
        ((com.fasterxml.jackson.databind.node.ObjectNode) metadata.path("sheets").get(0)).set("merges", mapper.valueToTree(List.of(
                Map.of("startRowIndex", 3, "endRowIndex", 4, "startColumnIndex", 0, "endColumnIndex", 2))));
        assertThatThrownBy(() -> service.append(request())).hasMessageContaining("combinadas");
        ((com.fasterxml.jackson.databind.node.ObjectNode) metadata.path("sheets").get(0)).remove("merges");
        ((com.fasterxml.jackson.databind.node.ObjectNode) metadata.path("sheets").get(0)).set("protectedRanges", mapper.valueToTree(List.of(
                Map.of("range", Map.of("startRowIndex", 3, "endRowIndex", 4), "warningOnly", false, "requestingUserCanEdit", false))));
        assertThatThrownBy(() -> service.append(request())).hasMessageContaining("protegido");
        verify(client, never()).batchUpdate(anyString(), any());
    }

    @Test void extendsGridAndOnlyMatchingNativeTable() {
        ((com.fasterxml.jackson.databind.node.ObjectNode) metadata.path("sheets").get(0).path("properties").path("gridProperties")).put("rowCount", 3);
        ((com.fasterxml.jackson.databind.node.ObjectNode) metadata.path("sheets").get(0)).set("tables", mapper.valueToTree(List.of(
                Map.of("tableId", "products", "range", Map.of("sheetId", 42, "startRowIndex", 1, "endRowIndex", 3, "startColumnIndex", 0, "endColumnIndex", 9)),
                Map.of("tableId", "summary", "range", Map.of("sheetId", 42, "startRowIndex", 1, "endRowIndex", 3, "startColumnIndex", 10, "endColumnIndex", 13)))));
        when(client.values(ID, "'Mes '' Prueba'!A3:I3")).thenReturn(mapper.valueToTree(Map.of("values", List.of(List.of("Existente")))));
        service.append(request());
        JsonNode batch = writtenBatch();
        assertThat(batch.findValue("appendDimension").path("length").asInt()).isEqualTo(1);
        assertThat(batch.findValue("updateTable").path("table").path("tableId").asText()).isEqualTo("products");
        assertThat(batch.toString()).doesNotContain("summary");
    }

    @Test void refusesNativeTableFooterBeforeMutatingRowsOrTotals() {
        ((com.fasterxml.jackson.databind.node.ObjectNode) metadata.path("sheets").get(0)).set("tables", mapper.valueToTree(List.of(
                Map.of("tableId", "products", "range", Map.of("sheetId", 42, "startRowIndex", 1, "endRowIndex", 4,
                        "startColumnIndex", 0, "endColumnIndex", 9), "rowsProperties", Map.of("footerColorStyle", Map.of("rgbColor", Map.of("red", 1)))))));
        assertThatThrownBy(() -> service.append(request())).hasMessageContaining("pie de totales");
        verify(client, never()).batchUpdate(anyString(), any());
    }

    @Test void validatesAllRowsAndLocaleWithoutNetwork() {
        assertThat(SheetsService.number("$ 1.500,50", true)).isEqualByComparingTo("1500.5");
        assertThat(SheetsService.number("1.500", true)).isEqualByComparingTo("1500");
        assertThat(SheetsService.number("0.875", false)).isEqualByComparingTo("0.875");
        assertThat(SheetsService.number("0", false)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThatThrownBy(() -> SheetsService.number("-1", false)).isInstanceOf(IllegalArgumentException.class);
        ReceiptItem invalid = new ReceiptItem("Producto", "", "", "", "1", "10", "31/2/2026");
        assertThatThrownBy(() -> service.append(new AppendRequest(UUID, List.of(item(), invalid), destination, config.version()))).hasMessageContaining("Item 2");
        assertThatThrownBy(() -> service.append(new AppendRequest("", List.of(item()), destination, config.version()))).hasMessageContaining("UUID");
        verifyNoInteractions(client);
    }

    @Test void configurationStatusRemainsAvailableWithoutCredentialsOrGoogle() {
        assertThat(service.getConfig().credentialsConfigured()).isFalse();
        assertThat(service.getConfig().headerRow()).isEqualTo(2);
        verifyNoInteractions(client);
    }
}
