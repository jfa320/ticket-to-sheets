package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opencode.facturas.model.LearningBackup.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class LearningBackupServiceTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper();
    CorrectionMemory memory; BrandCatalog brands; LearningBackupService service;
    @BeforeEach void setup() {
        memory = new CorrectionMemory(mapper, directory.resolve("corrections.json"));
        brands = new BrandCatalog(mapper, directory.resolve("brands.json"));
        memory.upsert("Local Prueba", "leche entera", "Mi leche", "Genérico", "Personal"); brands.remember("Marca Local");
        service = new LearningBackupService(mapper, memory, brands);
    }
    ObjectNode backup() {
        var data = mapper.valueToTree(new Document("ProyectoFacturas-learning", 1,
                List.of(new CorrectionMemory.Entry("Local Prueba", "leche entera", "Otra leche", "Marca Remota", "Otra", 1, "2026-10-20"),
                        new CorrectionMemory.Entry("Otro Local", "pan integral", "Pan integral", "Genérico", "Panadería", 2, "2026-10-21")),
                List.of("Marca Remota")));
        return (ObjectNode) data;
    }
    ObjectNode request(JsonNode backup, Preview preview) {
        var node = mapper.createObjectNode(); node.set("backup", backup);
        node.put("expectedVersion", preview.expectedVersion()).put("backupHash", preview.backupHash()); return node;
    }
    @Test void aBackupLargerThanTwoMegabytesRoundTripsUnderTheSharedLimit() throws Exception {
        var entries = new ArrayList<CorrectionMemory.Entry>();
        for (int i=0;i<3200;i++) entries.add(new CorrectionMemory.Entry("Local Ficticio", "producto " + i,
                "Producto " + "x".repeat(510), "Genérico", "Prueba", 1, "2026-10-29"));
        memory.applySnapshot(entries);
        var document = service.export(); var bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(document);
        assertThat(bytes.length).isGreaterThan(2_000_000).isLessThan(LearningBackupService.MAX_BACKUP_BYTES);
        assertThat(service.preview(mapper.readTree(bytes)).correctionsToAdd()).isZero();
    }
    @Test void exportCannotDownloadAMemoryThatExceedsTheEntryLimit() {
        var entry = new CorrectionMemory.Entry("Local", "producto", "Producto", "Genérico", "", 1, "2026-10-29");
        memory.applySnapshot(Collections.nCopies(LearningBackupService.MAX_ENTRIES+1, entry));
        assertThatThrownBy(service::export).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("10.000");
    }
    @Test void overflowingVersionsAndMultilineControlTextAreRejected() {
        var document=backup(); document.put("schemaVersion",4294967297L);
        assertThatThrownBy(()->service.preview(document)).isInstanceOf(IllegalArgumentException.class);
        var multiline=backup(); ((ObjectNode)multiline.path("corrections").get(0)).put("descripcion","a\nb\nc");
        assertThatThrownBy(()->service.preview(multiline)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void legacyCaseDuplicatesExportAsAPortableBrandWithoutChangingTheLocalCatalog() throws Exception {
        mapper.writeValue(directory.resolve("brands.json").toFile(), List.of("Marca Local", "MARCA LOCAL"));
        brands = new BrandCatalog(mapper, directory.resolve("brands.json"));
        service = new LearningBackupService(mapper, memory, brands);
        var backup = mapper.valueToTree(service.export());
        assertThat(backup.path("brands")).hasSize(1);
        var preview = service.preview(backup);
        assertThat(preview.brandsToAdd()).isZero();
        service.restore(request(backup, preview));
        assertThat(mapper.readTree(directory.resolve("brands.json").toFile())).hasSize(2);
    }
    @Test void exportContainsOnlyLearningAndRestoreMergesWithoutOverwritingLocalCorrections() throws Exception {
        Files.writeString(directory.resolve("google-oauth-tokens.json"), "{\"refreshToken\":\"DO_NOT_EXPORT\"}");
        assertThat(mapper.writeValueAsString(service.export())).doesNotContain("refreshToken", "DO_NOT_EXPORT", "precio", "spreadsheet");
        var backup = backup(); var preview = service.preview(backup);
        assertThat(preview.correctionsToAdd()).isEqualTo(1); assertThat(preview.conflicts()).isEqualTo(1);
        assertThat(preview.brandsToAdd()).isEqualTo(1); assertThat(memory.snapshot()).hasSize(1);
        var result = service.restore(request(backup, preview));
        assertThat(result.correctionsAdded()).isEqualTo(1);
        assertThat(memory.find("Local Prueba", "leche entera").descripcion()).isEqualTo("Mi leche");
        assertThat(memory.find("Otro Local", "pan integral").veces()).isEqualTo(2);
        assertThat(brands.findIn("Marca Remota producto")).isPresent();
        var again = service.restore(request(backup, service.preview(backup)));
        assertThat(again.correctionsAdded()).isZero(); assertThat(again.brandsAdded()).isZero();
        assertThat(new CorrectionMemory(mapper, directory.resolve("corrections.json")).snapshot()).hasSize(2);
        assertThat(service.preview(mapper.valueToTree(service.export())).correctionsToAdd()).isZero();
    }
    @Test void stalePreviewAndAlteredBackupCannotWrite() {
        var backup = backup(); var preview = service.preview(backup);
        memory.upsert("Local Prueba", "leche entera", "Cambio posterior", "Genérico", "Personal");
        assertThatThrownBy(() -> service.restore(request(backup, preview))).isInstanceOf(LearningConflictException.class);
        var fresh = service.preview(backup); backup.putArray("brands").add("Marca Distinta");
        assertThatThrownBy(() -> service.restore(request(backup, fresh))).isInstanceOf(LearningConflictException.class);
        assertThat(memory.snapshot()).hasSize(1); assertThat(brands.snapshot()).containsExactly("Marca Local");
    }
    @Test void invalidSchemaDuplicatesAndPrivateFieldsAreRejectedBeforeWriting() {
        var future = backup(); future.put("schemaVersion", 2);
        assertThatThrownBy(() -> service.preview(future)).isInstanceOf(IllegalArgumentException.class);
        var privateData = backup(); privateData.put("refreshToken", "not-a-backup");
        assertThatThrownBy(() -> service.preview(privateData)).isInstanceOf(IllegalArgumentException.class);
        var duplicate = backup(); duplicate.withArray("corrections").add(duplicate.path("corrections").get(0).deepCopy());
        assertThatThrownBy(() -> service.preview(duplicate)).isInstanceOf(IllegalArgumentException.class);
        assertThat(memory.snapshot()).hasSize(1);
    }
    @Test void diskFailureRollsBackBothFilesAndDoesNotChangeLiveMemory() throws Exception {
        JsonNode oldMemory = mapper.readTree(directory.resolve("corrections.json").toFile());
        JsonNode oldBrands = mapper.readTree(directory.resolve("brands.json").toFile());
        var transaction = new LearningRestoreTransaction() {
            @Override protected void write(ObjectMapper mapper, Path path, Object value) throws IOException {
                if (path.getFileName().toString().equals("brands.json")) throw new IOException("synthetic disk failure");
                super.write(mapper, path, value);
            }
        };
        var faulty = new LearningBackupService(mapper, memory, brands, transaction);
        var backup = backup();
        assertThatThrownBy(() -> faulty.restore(request(backup, faulty.preview(backup)))).isInstanceOf(IllegalStateException.class);
        assertThat(mapper.readTree(directory.resolve("corrections.json").toFile())).isEqualTo(oldMemory);
        assertThat(mapper.readTree(directory.resolve("brands.json").toFile())).isEqualTo(oldBrands);
        assertThat(memory.snapshot()).hasSize(1); assertThat(brands.snapshot()).containsExactly("Marca Local");
        assertThat(Files.exists(directory.resolve(".learning-restore.json"))).isFalse();
    }
    @Test void startupRecoversAnInterruptedRestoreBeforeLoadingEitherStore() throws Exception {
        var journal = Map.of("schemaVersion", 1, "corrections", memory.snapshot(), "brands", brands.snapshot());
        mapper.writeValue(directory.resolve(".learning-restore.json").toFile(), journal);
        mapper.writeValue(directory.resolve("corrections.json").toFile(), Map.of("entries", List.of()));
        mapper.writeValue(directory.resolve("brands.json").toFile(), List.of("Marca incompleta"));
        var restored = new BrandCatalog(mapper, directory.resolve("brands.json"));
        assertThat(restored.snapshot()).containsExactly("Marca Local");
        assertThat(new CorrectionMemory(mapper, directory.resolve("corrections.json")).snapshot()).hasSize(1);
        assertThat(Files.exists(directory.resolve(".learning-restore.json"))).isFalse();
    }
}
