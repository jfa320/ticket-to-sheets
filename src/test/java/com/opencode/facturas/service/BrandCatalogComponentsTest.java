package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BrandCatalogComponentsTest {

    @TempDir
    Path tempDir;

    @Test
    void matcherUsesCatalogAliasesAndLongestMatch() {
        BrandCatalogMatcher matcher = new BrandCatalogMatcher(new LinkedHashSet<>(List.of(
                "Frutigram", "Union Ganadera", "La Providencia"
        )));

        BrandCatalog.BrandMatch result = matcher.findIn("Frutigran copos de maiz").orElseThrow();

        assertThat(result.brand()).isEqualTo("Frutigram");
        assertThat(result.normalizedAlias()).isEqualTo("frutigran");
    }

    @Test
    void matcherKeepsFuzzyOcrCorrectionBehavior() {
        BrandCatalogMatcher matcher = new BrandCatalogMatcher(Set.of("Molto"));

        BrandCatalog.FuzzyBrandMatch result = matcher.findFuzzyAtStart("M0LTO JARDINER").orElseThrow();

        assertThat(result.brand()).isEqualTo("Molto");
        assertThat(result.percentage()).isEqualTo(100.0);
    }

    @Test
    void catalogStoreCreatesAndSortsJsonCatalog() {
        Path catalogPath = tempDir.resolve("brands.json");
        BrandCatalogStore store = new BrandCatalogStore(new ObjectMapper(), catalogPath);

        assertThat(store.load()).isEmpty();
        store.save(List.of("Zeta", "Alpha"));

        assertThat(store.load()).containsExactly("Alpha", "Zeta");
    }

    @Test
    void catalogStoreRestoresLastGoodJsonBackupAfterCorruption() throws Exception {
        Path catalogPath = tempDir.resolve("brands.json");
        BrandCatalogStore store = new BrandCatalogStore(new ObjectMapper(), catalogPath);
        store.save(List.of("Beta"));
        store.save(List.of("Alpha", "Beta"));
        Files.writeString(catalogPath, "{ JSON incompleto");

        assertThat(store.load()).containsExactly("Beta");
        assertThat(store.load()).containsExactly("Beta");
    }

    @Test
    void rememberPersistsBrandThroughCatalogStore() {
        Path catalogPath = tempDir.resolve("brands.json");
        BrandCatalog catalog = new BrandCatalog(new ObjectMapper(), catalogPath);
        catalog.remember("  Nueva   Marca ");

        BrandCatalog reloaded = new BrandCatalog(new ObjectMapper(), catalogPath);

        assertThat(reloaded.findIn("Nueva Marca producto")).get()
                .extracting(BrandCatalog.BrandMatch::brand)
                .isEqualTo("Nueva Marca");
    }
}
