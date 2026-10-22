package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.facturas.model.CorrectionManagement.Edit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CorrectionManagementTest {
    @TempDir Path directory;
    @Test void searchesPagesReplacesGenericAndDeletesOnlyTheExactStore() {
        Path file = directory.resolve("memory.json");
        var memory = new CorrectionMemory(new ObjectMapper(), file);
        memory.upsert("Local A", "leche", "Leche entera", "Marca", "Lácteos");
        memory.upsert("Local B", "leche", "Leche", "Otra", "Lácteos");
        assertThat(memory.list("LACTEOS", 0, 1).total()).isEqualTo(2);
        var entry = memory.list("local a", 0, 25).entries().get(0);
        var updated = memory.replace(new Edit(entry.store(), entry.firma(), entry.version(), "Leche corregida", "Genérico", ""));
        assertThat(updated.marca()).isEqualTo("Genérico");
        assertThat(updated.categoria()).isEmpty();
        assertThatThrownBy(() -> memory.remove(entry.store(), entry.firma(), entry.version())).isInstanceOf(CorrectionConflictException.class);
        memory.remove(updated.store(), updated.firma(), updated.version());
        var reloaded = new CorrectionMemory(new ObjectMapper(), file);
        assertThat(reloaded.list("", 0, 25).entries()).hasSize(1);
        assertThat(reloaded.find("Local A", "leche")).isNull();
        assertThat(reloaded.find("Local B", "leche")).isNotNull();
    }
    @Test void diskFailureDoesNotAlterTheInMemoryCorrection() {
        var store = mock(CorrectionMemoryStore.class);
        when(store.load()).thenReturn(List.of(new CorrectionMemory.Entry("Local", "pan", "Pan", "Marca", "Otros", 1, "2026-10-22")));
        var memory = new CorrectionMemory(store);
        var entry = memory.list("", 0, 25).entries().get(0);
        doThrow(new IllegalStateException("disk full")).when(store).save(any());
        assertThatThrownBy(() -> memory.replace(new Edit(entry.store(), entry.firma(), entry.version(), "Nuevo", "Genérico", ""))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> memory.remove(entry.store(), entry.firma(), entry.version())).isInstanceOf(IllegalStateException.class);
        assertThat(memory.list("", 0, 25).entries()).containsExactly(entry);
    }
    @Test void rejectsInvalidPaginationAndMissingVersion() {
        var memory = new CorrectionMemory(new ObjectMapper(), directory.resolve("memory.json"));
        assertThatThrownBy(() -> memory.list("", -1, 25)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> memory.list("", 0, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> memory.remove("Local", "pan", "")).isInstanceOf(IllegalArgumentException.class);
    }
}
