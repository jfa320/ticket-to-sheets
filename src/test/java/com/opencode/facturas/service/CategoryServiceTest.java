package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class CategoryServiceTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper();
    ObjectNode config() throws Exception { return (ObjectNode) mapper.readTree(getClass().getResourceAsStream("/category-defaults.json")); }

    @Test void onlyTheNineSheetCategoriesAreAvailableAndHardwareUsesOthers() {
        var service = CategoryService.defaults();
        assertThat(service.categoryFor("Ferretería Tribulato", "Tornillo")).isEqualTo("Otros");
        assertThat(service.categoryFor("Ferretería de Prueba", "Plafon")).isEqualTo("Otros");
        assertThat(service.categoryFor("Desconocido", "Leche")).isEqualTo("Supermercado");
        assertThat(service.suggestions()).containsExactly("Limpieza", "Supermercado", "Farmacia", "Kiosco",
                "Carne", "Otros", "Verduleria", "Perfumeria", "Panaderia");
    }
    @Test void reloadsValidatedLocalRulesWithWordBoundariesAndStoreScope() throws Exception {
        var path = directory.resolve("categories.json"); var config = config();
        config.put("fallback", "Otros");
        config.putArray("productRules").addObject().put("category", "Limpieza").put("store", "Local Prueba")
                .putArray("keywords").add("leche");
        mapper.writeValue(path.toFile(), config);
        var service = new CategoryService(mapper, path);
        assertThat(service.categoryFor("LOCAL PRUEBA", "LECHE ENTERA")).isEqualTo("Limpieza");
        assertThat(service.categoryFor("Otro", "Leche")).isEqualTo("Otros");
        assertThat(service.categoryFor("Local Prueba", "Lecheria")).isEqualTo("Otros");
        assertThat(service.suggestions()).hasSize(9);
        config.put("fallback", "Kiosco"); mapper.writeValue(path.toFile(), config);
        assertThat(service.categoryFor("Otro", "Producto")).isEqualTo("Kiosco");
        Files.writeString(path, "{invalid");
        assertThatThrownBy(() -> service.categoryFor("Otro", "Producto")).isInstanceOf(IllegalStateException.class);
        mapper.writeValue(path.toFile(), config);
        assertThat(service.categoryFor("Otro", "Producto")).isEqualTo("Kiosco");
    }
    @Test void memoryRetainsPriorityOverProductCategoryRules() throws Exception {
        var path = directory.resolve("categories.json"); var config = config();
        config.putArray("productRules").addObject().put("category", "Supermercado").putArray("keywords").add("leche");
        mapper.writeValue(path.toFile(), config);
        var memory = new CorrectionMemory(mapper, directory.resolve("corrections.json"));
        var parser = new ReceiptParserService(StoreNameMapper.empty(), new BrandCatalog(mapper, directory.resolve("brands.json")),
                memory, null, new CategoryService(mapper, path));
        String text = "Local Prueba\nFecha 10/10/2026\nLECHE ENTERA 1200,00";
        assertThat(parser.parse(text).items().get(0).categoria()).isEqualTo("Supermercado");
        memory.upsert("Local Prueba", "leche entera", "Leche propia", "Genérico", "Limpieza");
        assertThat(parser.parse(text).items().get(0).categoria()).isEqualTo("Limpieza");
    }
    @Test void punctuationCannotAccidentallyCreateAGlobalOrEmptyKeywordRule() throws Exception {
        var path = directory.resolve("categories.json"); var config = config();
        var rule = config.putArray("productRules").addObject().put("category", "Limpieza").put("store", "!!!");
        rule.putArray("keywords").add("leche"); mapper.writeValue(path.toFile(), config);
        var service = new CategoryService(mapper, path);
        assertThatThrownBy(() -> service.categoryFor("Otro", "Leche")).isInstanceOf(IllegalStateException.class);
        rule.remove("store"); rule.putArray("keywords").add("---"); mapper.writeValue(path.toFile(), config);
        assertThatThrownBy(() -> service.categoryFor("Otro", "Leche")).isInstanceOf(IllegalStateException.class);
    }

    @Test void legacyCategoriesAreCanonicalizedWithoutExpandingTheCatalog() {
        var service = CategoryService.defaults();
        for (String legacy : List.of("Ferretería", "Categoría inventada", ""))
            assertThat(service.canonical(legacy)).isEqualTo("Otros");
        for (String legacy : List.of("Lácteos", "Almacén", "Galletitas"))
            assertThat(service.canonical(legacy)).isEqualTo("Supermercado");
        assertThat(service.canonical(" PERFUMERÍA ")).isEqualTo("Perfumeria");
    }

    @Test void invalidCategoriesInLocalConfigurationAreRejected() throws Exception {
        var path = directory.resolve("categories.json");
        for (String location : List.of("fallback", "categories", "stores", "productRules")) {
            var config = config();
            switch (location) {
                case "fallback" -> config.put("fallback", "Ferreteria");
                case "categories" -> config.putArray("categories").add("Personal");
                case "stores" -> config.withObject("stores").put("Prueba", "Personal");
                case "productRules" -> config.putArray("productRules").addObject().put("category", "Personal")
                        .putArray("keywords").add("leche");
            }
            mapper.writeValue(path.toFile(), config);
            var service = new CategoryService(mapper, path);
            assertThatThrownBy(service::suggestions).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void oldLearnedHardwareCategoryUsesOthersWithoutLosingMemoryOrCurrentAmounts() {
        var memory = new CorrectionMemory(mapper, directory.resolve("memory.json"));
        memory.upsert("Ferreteria de prueba", "plafon led", "Plafon LED corregido", "Genérico", "Ferreteria");
        var parser = new ReceiptParserService(StoreNameMapper.empty(),
                new BrandCatalog(mapper, directory.resolve("brands.json")), memory);
        var response = parser.parse("FERRETERIA DE PRUEBA\nPLAFON LED 9200,00\nTOTAL 9200,00");
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.categoria()).isEqualTo("Otros");
            assertThat(item.memorizado()).isTrue();
            assertThat(item.descripcion()).isEqualTo("Plafon LED corregido");
            assertThat(item.precioUnitario()).isEqualTo("9200,00");
        });
        assertThat(response.tsvWithoutHeader()).contains("\tOtros\t");
        assertThat(memory.find("Ferreteria de prueba", "plafon led").categoria()).isEqualTo("Ferreteria");
    }
}
