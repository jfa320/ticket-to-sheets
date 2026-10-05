package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.facturas.model.SheetsModels.ConfigRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class SheetsConfigStoreTest {
    @TempDir Path directory;
    private static final String ID = "synthetic_spreadsheet_id_1234567890";

    @Test void savesAndRecoversLocalDestinationWithoutCredentials() throws Exception {
        Path path = directory.resolve("sheets.json");
        SheetsConfigStore store = new SheetsConfigStore(new ObjectMapper(), path.toString());
        assertThat(store.get().spreadsheetId()).isEmpty();
        assertThat(store.get().monthSheets()).isEmpty();
        var first = store.save(new ConfigRequest("https://docs.google.com/spreadsheets/d/" + ID + "/edit?gid=42", 2026, 2, Map.of("9", " Septiembre ")));
        assertThat(first.monthSheets()).containsEntry("9", "Septiembre");
        store.save(new ConfigRequest(ID, 2026, 2, Map.of("10", "Octubre")));
        var restored = new SheetsConfigStore(new ObjectMapper(), path.toString()).get();
        assertThat(restored.year()).isEqualTo(2026);
        assertThat(restored.monthSheets()).containsExactlyEntriesOf(Map.of("10", "Octubre"));
        Files.writeString(path, "damaged json");
        assertThat(new SheetsConfigStore(new ObjectMapper(), path.toString()).get()).isEqualTo(first);
    }

    @Test void rejectsExternalUrlsAndInvalidYearMonthTabOrHeader() {
        for (String input : new String[]{"https://evil.example/spreadsheets/d/" + ID, "https://docs.google.com.evil.example/spreadsheets/d/" + ID,
                "https://user@docs.google.com/spreadsheets/d/" + ID, "https://docs.google.com:444/spreadsheets/d/" + ID, "short"}) {
            assertThatThrownBy(() -> SheetsConfigStore.extractId(input)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> SheetsConfigStore.validate(new ConfigRequest(ID, null, 2, Map.of()))).hasMessageContaining("año");
        assertThatThrownBy(() -> SheetsConfigStore.validate(new ConfigRequest(ID, 2026, 2, Map.of("13", "Septiembre")))).hasMessageContaining("meses");
        assertThatThrownBy(() -> SheetsConfigStore.validate(new ConfigRequest(ID, 2026, 2, Map.of("9", "x".repeat(101))))).hasMessageContaining("pestaña");
        assertThatThrownBy(() -> SheetsConfigStore.validate(new ConfigRequest(ID, 2026, 0, Map.of("9", "Septiembre")))).hasMessageContaining("encabezados");
    }

    @Test void migratesLegacyTabWithoutInventingYearOrMonthlyAssociations() throws Exception {
        Path path = directory.resolve("legacy.json");
        String original = "{\"spreadsheetId\":\"" + ID + "\",\"sheetName\":\"Septiembre\",\"headerRow\":2}";
        Files.writeString(path, original);
        SheetsConfigStore store = new SheetsConfigStore(new ObjectMapper(), path.toString());
        assertThat(store.get().year()).isNull();
        assertThat(store.get().monthSheets()).isEmpty();
        assertThat(store.legacySheetName()).isEqualTo("Septiembre");
        assertThat(Files.readString(path)).isEqualTo(original);
        store.save(new ConfigRequest(ID, 2026, 2, Map.of("9", "Septiembre")));
        assertThat(store.legacySheetName()).isEmpty();
    }
}
