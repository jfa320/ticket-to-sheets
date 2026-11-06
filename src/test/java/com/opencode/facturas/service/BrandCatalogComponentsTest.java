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
    @Test void cachedAliasesFollowLearningAndReplacementWithoutLeakingRemovedBrands() {
        var catalog = new BrandCatalog(new ObjectMapper(), tempDir.resolve("brands.json"));
        assertThat(catalog.findAnywhereIn("Leche Marca Nueva entera")).isEmpty();
        catalog.remember("Marca Nueva");
        assertThat(catalog.findAnywhereIn("Leche Marca Nueva entera")).get()
                .extracting(BrandCatalog.BrandMatch::brand).isEqualTo("Marca Nueva");
        catalog.applySnapshot(List.of("Otra Marca"));
        assertThat(catalog.findAnywhereIn("Leche Marca Nueva entera")).isEmpty();
        assertThat(catalog.findAnywhereIn("Leche Otra Marca entera")).get()
                .extracting(BrandCatalog.BrandMatch::brand).isEqualTo("Otra Marca");
    }

    @TempDir
    Path tempDir;

    @Test void brandsInsideDescriptionsRequireCompleteWordsAndAllowSplitOcrSpelling() {
        var matcher = new BrandCatalogMatcher(Set.of("Lux", "McCain", "La Paulina"));
        assertThat(matcher.findAnywhereIn("Jabon Lux neutro")).get().extracting(BrandCatalog.BrandMatch::brand).isEqualTo("Lux");
        assertThat(matcher.findAnywhereIn("Papas Mc Cain finitas")).get().extracting(BrandCatalog.BrandMatch::brand).isEqualTo("McCain");
        assertThat(matcher.findAnywhereIn("Queso La Paulina rallado")).isPresent();
        assertThat(matcher.findAnywhereIn("Papel de Luxemburgo")).isEmpty();
        assertThat(matcher.findAnywhereIn("Producto Superlux")).isEmpty();
    }

    @Test void genericProductPrefixDoesNotHideAKnownBrandOrChangeAmounts() {
        var mapper = new ObjectMapper();
        var catalog = new BrandCatalog(mapper, tempDir.resolve("brands.json")); catalog.remember("Marca Prueba");
        var parser = new ReceiptParserService(StoreNameMapper.empty(), catalog,
                new CorrectionMemory(mapper, tempDir.resolve("memory.json")));
        var result = parser.parse("Comercio Ficticio\nFecha 1/10/2026\nLECHE MARCA PRUEBA ENTERA 1L 1200,00");
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.marca()).isEqualTo("Marca Prueba");
            assertThat(item.descripcion()).contains("Entera", "1l");
            assertThat(item.precioUnitario()).isEqualTo("1200,00");
            assertThat(item.firma()).isEqualTo("leche marca prueba entera 1l");
        });
    }

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
    void joinedBrandMatchingRequiresARecognizableProductSuffix() {
        var matcher = new BrandCatalogMatcher(Set.of("Lucchetti", "Trio", "Pitusas", "Molto"));
        assertThat(matcher.findJoinedAtStart("LUCCHETTTSPAG500GR")).get()
                .extracting(BrandCatalog.BrandMatch::brand).isEqualTo("Lucchetti");
        assertThat(matcher.findJoinedAtStart("TRTOGALLET300GR")).get()
                .extracting(BrandCatalog.BrandMatch::brand).isEqualTo("Trio");
        assertThat(matcher.findJoinedAtStart("MOLTOVOTROPRODUCTO")).isEmpty();
    }

    @Test
    void aSingleEarlyOcrTypoDoesNotHideAnOtherwiseStrongBrandMatch() {
        var matcher = new BrandCatalogMatcher(Set.of("Pitusas", "Pepsi"));
        assertThat(matcher.findFuzzyAtStart("PTTUSAS GALLET300GR")).get()
                .extracting(BrandCatalog.FuzzyBrandMatch::brand).isEqualTo("Pitusas");
        assertThat(matcher.findFuzzyAtStart("POETT LIMPIADOR900ML")).isEmpty();
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
