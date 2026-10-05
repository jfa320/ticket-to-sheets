package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.facturas.model.ReceiptItem;
import com.opencode.facturas.model.SheetsModels.*;
import com.opencode.facturas.model.SheetsHistoryModels.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SheetsHistoryServiceTest {
    private static final String ID = "synthetic_spreadsheet_id_1234567890";
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private SheetsConfigStore config;
    private GoogleSheetsClient client;
    private SheetsHistoryService service;
    private ConfigReference destination;
    private Path cache;

    @BeforeEach void setup() throws Exception {
        config = new SheetsConfigStore(mapper, directory.resolve("config.json").toString());
        config.save(configuration(ID, "Mes ' Prueba", 2));
        destination = new ConfigReference(ID, 2, config.version());
        client = mock(GoogleSheetsClient.class);
        cache = directory.resolve("history.json");
        service = newService();
        when(client.metadata(ID)).thenReturn(metadata(10));
        when(client.historyValues(eq(ID), anyString())).thenReturn(values(List.of()));
        when(client.historyValues(ID, "'Mes '' Prueba'!A2:D2")).thenReturn(values(List.of(List.of("Descripción", "Marca", "Lugar de compra", "Categoria"))));
        when(client.historyValues(ID, "'Otro mes'!A2:D2")).thenReturn(values(List.of(List.of("Descripcion", "Marca", "Lugar de compra", "Categoría"))));
        when(client.historyValues(ID, "'Resumen'!A2:D2")).thenReturn(values(List.of(List.of("Total", "", "", ""))));
        when(client.historyValues(ID, "'Mes '' Prueba'!A3:D10")).thenReturn(values(List.of(
                List.of("Jabón neutro 500g", "Marca Prueba", "Comercio prueba", "Limpieza"),
                List.of("Jabón neutro 500g", "Marca Prueba", "Comercio prueba", "Limpieza"),
                List.of(""), List.of(123, "Marca Prueba", "Comercio prueba", "Otros"),
                List.of("Producto sin comercio", "", "", "Otros"))));
        when(client.historyValues(ID, "'Otro mes'!A3:D10")).thenReturn(values(List.of(List.of("Producto nuevo", "Genérico", "Comercio prueba", "Otros"))));
    }
    private SheetsHistoryService newService() { return new SheetsHistoryService(mapper, config, client, StoreNameMapper.empty(), cache.toString()); }
    private ConfigRequest configuration(String id, String tab, int headerRow) {
        return new ConfigRequest(id, 2026, headerRow, Map.of("10", tab));
    }
    private JsonNode metadata(int rows) { return mapper.valueToTree(Map.of("sheets", List.of(tab("Mes ' Prueba", rows), tab("Otro mes", 10), tab("Resumen", 10)))); }
    private Map<String, Object> tab(String title, int rows) { return Map.of("properties", Map.of("title", title, "sheetType", "GRID", "gridProperties", Map.of("rowCount", rows, "columnCount", 9))); }
    private JsonNode values(Object rows) { return mapper.valueToTree(Map.of("values", rows)); }
    private ReceiptItem item() { return new ReceiptItem("Jabón", "Genérico", "Comercio prueba", "Otros", "2", "250", "4/10/2026", "CORRECT", "marca prueba jabon neutro 500g"); }

    @Test void syncReadsAllCompatibleTabsDeduplicatesAndSavesOnlyProductFields() throws Exception {
        HistoryStatus status = service.sync(destination);
        assertThat(status.active()).isTrue();
        assertThat(status.rowCount()).isEqualTo(3);
        assertThat(status.productCount()).isEqualTo(2);
        assertThat(status.sheets()).containsExactly("Mes ' Prueba", "Otro mes");
        assertThat(status.skippedSheets()).containsExactly("Resumen");
        Snapshot saved = mapper.readValue(cache.toFile(), Snapshot.class);
        assertThat(saved.products()).hasSize(2);
        JsonNode fields = mapper.readTree(cache.toFile()).path("products").get(0);
        assertThat(fields.size()).isEqualTo(4);
        assertThat(fields.has("precioUnitario")).isFalse();
        verify(client, never()).batchUpdate(any(), any());
        verify(client, never()).values(any(), any());
    }

    @Test void cacheWorksOfflineAfterRestartAndNewMonthButIsNotUsedForAnotherFileOrHeader() {
        service.sync(destination);
        clearInvocations(client);
        SheetsHistoryService restarted = newService();
        config.save(configuration(ID, "Mes siguiente", 2));
        assertThat(restarted.status().active()).isTrue();
        assertThat(restarted.enrich(List.of(item()), new ArrayList<>()).get(0).estado()).isEqualTo("HISTORY");
        verifyNoInteractions(client);
        config.save(configuration("other_synthetic_sheet_id_1234567890", "Mes siguiente", 2));
        assertThat(restarted.status().active()).isFalse();
        assertThat(restarted.enrich(List.of(item()), new ArrayList<>()).get(0).estado()).isEqualTo("CORRECT");
        config.save(configuration(ID, "Mes siguiente", 3));
        assertThat(restarted.status().active()).isFalse();
    }

    @Test void failureOrBudgetLimitDoesNotReplaceThePreviousCatalog() throws Exception {
        service.sync(destination);
        String before = Files.readString(cache);
        when(client.historyValues(ID, "'Otro mes'!A3:D10")).thenThrow(new IllegalStateException("Falla de lectura simulada"));
        assertThatThrownBy(() -> service.sync(destination)).hasMessageContaining("lectura");
        assertThat(Files.readString(cache)).isEqualTo(before);
        assertThat(service.status().active()).isTrue();
        when(client.metadata(ID)).thenReturn(metadata(20000));
        assertThatThrownBy(() -> service.sync(destination)).hasMessageContaining("50.000");
        assertThat(Files.readString(cache)).isEqualTo(before);
    }

    @Test void staleDestinationRejectsWithoutNetworkAndDestinationChangesDuringReadReject() {
        assertThatThrownBy(() -> service.sync(new ConfigReference(ID, 2, "outdated-version"))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
        when(client.historyValues(ID, "'Otro mes'!A3:D10")).thenAnswer(invocation -> {
            config.save(configuration(ID, "Otra pestaña", 2));
            return values(List.of());
        });
        assertThatThrownBy(() -> service.sync(destination)).hasMessageContaining("durante la lectura");
        assertThat(Files.exists(cache)).isFalse();
    }

    @Test void disabledAndDamagedCatalogsNeverBlockExtractionAndCanBeRepaired() throws Exception {
        assertThat(service.status().active()).isFalse();
        verifyNoInteractions(client);
        Files.writeString(cache, "{invalid synthetic cache");
        SheetsHistoryService damaged = newService();
        assertThat(damaged.status().active()).isFalse();
        assertThat(damaged.status().message()).contains("repararlo");
        assertThat(damaged.enrich(List.of(item()), new ArrayList<>())).containsExactly(item());
        assertThat(damaged.sync(destination).active()).isTrue();
        assertThat(damaged.disable().active()).isFalse();
        assertThat(newService().status().active()).isFalse();
        assertThat(newService().enrich(List.of(item()), new ArrayList<>()).get(0).estado()).isEqualTo("CORRECT");
    }

    @Test void incompatibleTabsDoNotSilentlyReplaceExistingCatalog() throws Exception {
        service.sync(destination);
        String before = Files.readString(cache);
        when(client.metadata(ID)).thenReturn(mapper.valueToTree(Map.of("sheets", List.of(tab("Resumen", 10)))));
        assertThatThrownBy(() -> service.sync(destination)).hasMessageContaining("No hay pestañas");
        assertThat(Files.readString(cache)).isEqualTo(before);
    }

    @Test void parserAppliesHistoryBeforeExportWithoutChangingTicketAmountsOrRecoveringDiscounts() {
        service.sync(destination);
        CorrectionMemory memory = new CorrectionMemory(mapper, directory.resolve("corrections.json"));
        BrandCatalog brands = new BrandCatalog(mapper, directory.resolve("brands.json"));
        String raw = "COMERCIO PRUEBA\nFECHA 04/10/2026\nJABON NEUTRO 500G MARCA PRUEBA\n2 x 1000,00\nDESCUENTO JABON NEUTRO 500G MARCA PRUEBA 100,00\nTOTAL 1900,00";
        var baseline = new ReceiptParserService(StoreNameMapper.empty(), brands, memory).parse(raw);
        var result = new ReceiptParserService(StoreNameMapper.empty(), brands, memory, service).parse(raw);
        assertThat(result.items()).hasSize(1);
        ReceiptItem known = result.items().get(0), original = baseline.items().get(0);
        assertThat(known.estado()).isEqualTo("HISTORY");
        assertThat(known.marca()).isEqualTo("Marca Prueba");
        assertThat(known.categoria()).isEqualTo("Limpieza");
        assertThat(known.cantidad()).isEqualTo(original.cantidad()).isEqualTo("2");
        assertThat(known.precioUnitario()).isEqualTo(original.precioUnitario()).isEqualTo("1000,00");
        assertThat(known.fecha()).isEqualTo(original.fecha());
        assertThat(result.total()).isEqualTo(baseline.total()).isEqualTo("2000,00");
        assertThat(result.csv()).contains("Marca Prueba", "Limpieza");
    }

    @Test void explicitManualMemoryWinsOverHistoryWithoutAddingHistoricalWarnings() {
        service.sync(destination);
        CorrectionMemory memory = new CorrectionMemory(mapper, directory.resolve("corrections.json"));
        memory.upsert("Comercio prueba", "jabon neutro 500g marca prueba", "Descripción manual", "Marca manual", "Categoría manual");
        var parser = new ReceiptParserService(StoreNameMapper.empty(), new BrandCatalog(mapper, directory.resolve("brands.json")), memory, service);
        var result = parser.parse("COMERCIO PRUEBA\nFECHA 04/10/2026\nJABON NEUTRO 500G MARCA PRUEBA 1000,00");
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).estado()).isEqualTo("LEARNED");
        assertThat(result.items().get(0).descripcion()).isEqualTo("Descripción manual");
        assertThat(result.items().get(0).marca()).isEqualTo("Marca manual");
        assertThat(result.items().get(0).categoria()).isEqualTo("Categoría manual");
        assertThat(result.warnings()).noneMatch(warning -> warning.contains("historial"));
    }
}
