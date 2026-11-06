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

    @Test void defaultsAndFreeFormSuggestionsRemainCompatible() {
        var service = CategoryService.defaults();
        assertThat(service.categoryFor("Ferretería Tribulato", "Tornillo")).isEqualTo("Ferreteria");
        assertThat(service.categoryFor("Desconocido", "Leche")).isEqualTo("Supermercado");
        assertThat(service.suggestions()).contains("Lácteos", "Otros");
    }
    @Test void reloadsValidatedLocalRulesWithWordBoundariesAndStoreScope() throws Exception {
        var path = directory.resolve("categories.json"); var config = config();
        config.put("fallback", "Personal");
        config.putArray("productRules").addObject().put("category", "Lácteos propios").put("store", "Local Prueba")
                .putArray("keywords").add("leche");
        mapper.writeValue(path.toFile(), config);
        var service = new CategoryService(mapper, path);
        assertThat(service.categoryFor("LOCAL PRUEBA", "LECHE ENTERA")).isEqualTo("Lácteos propios");
        assertThat(service.categoryFor("Otro", "Leche")).isEqualTo("Personal");
        assertThat(service.categoryFor("Local Prueba", "Lecheria")).isEqualTo("Personal");
        assertThat(service.suggestions()).contains("Lácteos propios");
        config.put("fallback", "Nueva categoría"); mapper.writeValue(path.toFile(), config);
        assertThat(service.categoryFor("Otro", "Producto")).isEqualTo("Nueva categoría");
        Files.writeString(path, "{invalid");
        assertThatThrownBy(() -> service.categoryFor("Otro", "Producto")).isInstanceOf(IllegalStateException.class);
        mapper.writeValue(path.toFile(), config);
        assertThat(service.categoryFor("Otro", "Producto")).isEqualTo("Nueva categoría");
    }
    @Test void memoryRetainsPriorityOverProductCategoryRules() throws Exception {
        var path = directory.resolve("categories.json"); var config = config();
        config.putArray("productRules").addObject().put("category", "Lácteos").putArray("keywords").add("leche");
        mapper.writeValue(path.toFile(), config);
        var memory = new CorrectionMemory(mapper, directory.resolve("corrections.json"));
        var parser = new ReceiptParserService(StoreNameMapper.empty(), new BrandCatalog(mapper, directory.resolve("brands.json")),
                memory, null, new CategoryService(mapper, path));
        String text = "Local Prueba\nFecha 10/10/2026\nLECHE ENTERA 1200,00";
        assertThat(parser.parse(text).items().get(0).categoria()).isEqualTo("Lácteos");
        memory.upsert("Local Prueba", "leche entera", "Leche propia", "Genérico", "Mi categoría");
        assertThat(parser.parse(text).items().get(0).categoria()).isEqualTo("Mi categoría");
    }
    @Test void punctuationCannotAccidentallyCreateAGlobalOrEmptyKeywordRule() throws Exception {
        var path = directory.resolve("categories.json"); var config = config();
        var rule = config.putArray("productRules").addObject().put("category", "Personal").put("store", "!!!");
        rule.putArray("keywords").add("leche"); mapper.writeValue(path.toFile(), config);
        var service = new CategoryService(mapper, path);
        assertThatThrownBy(() -> service.categoryFor("Otro", "Leche")).isInstanceOf(IllegalStateException.class);
        rule.remove("store"); rule.putArray("keywords").add("---"); mapper.writeValue(path.toFile(), config);
        assertThatThrownBy(() -> service.categoryFor("Otro", "Leche")).isInstanceOf(IllegalStateException.class);
    }
}
