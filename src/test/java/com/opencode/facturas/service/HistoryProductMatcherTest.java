package com.opencode.facturas.service;

import com.opencode.facturas.model.ReceiptItem;
import com.opencode.facturas.model.SheetsHistoryModels.Product;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class HistoryProductMatcherTest {
    private Product product(String description, String brand, String category) {
        return new Product(description, brand, "Comercio prueba", category);
    }
    private ReceiptItem item(String signature, String brand, String state) {
        return new ReceiptItem("Nombre resumido", brand, "Comercio prueba", "Supermercado",
                "0.875", "1.500,50", "4/10/2026", state, signature);
    }

    @Test void exactOriginalSignatureCanRecognizeAnUnknownBrandWithoutChangingCurrentAmounts() {
        HistoryProductMatcher matcher = new HistoryProductMatcher(List.of(product("Jabón neutro 500g", "Marca Prueba", "Limpieza")));
        ReceiptItem original = item("MARCA PRUEBA JABON NEUTRO 500G", "Genérico", "CORRECT");
        List<String> warnings = new ArrayList<>();
        ReceiptItem result = matcher.enrich(original, warnings);
        assertThat(result.descripcion()).isEqualTo("Jabón neutro 500g");
        assertThat(result.marca()).isEqualTo("Marca Prueba");
        assertThat(result.categoria()).isEqualTo("Limpieza");
        assertThat(result.estado()).isEqualTo("HISTORY");
        assertThat(result.cantidad()).isEqualTo(original.cantidad());
        assertThat(result.precioUnitario()).isEqualTo(original.precioUnitario());
        assertThat(result.fecha()).isEqualTo(original.fecha());
        assertThat(result.lugarDeCompra()).isEqualTo(original.lugarDeCompra());
        assertThat(result.firma()).isEqualTo(original.firma());
        assertThat(warnings).isEmpty();
    }

    @Test void reversedBrandOrderAndDescriptionsAlreadyIncludingBrandWork() {
        HistoryProductMatcher matcher = new HistoryProductMatcher(List.of(product("Jabón neutro 500g", "Marca Prueba", "Limpieza")));
        assertThat(matcher.enrich(item("jabon neutro 500g marca prueba", "Marca Prueba", "CORRECT"), new ArrayList<>()).estado()).isEqualTo("HISTORY");
        matcher = new HistoryProductMatcher(List.of(product("Jabón Marca Prueba neutro 500g", "Marca Prueba", "Limpieza")));
        assertThat(matcher.enrich(item("jabon marca prueba neutro 500g", "Genérico", "CORRECT"), new ArrayList<>()).estado()).isEqualTo("HISTORY");
    }

    @Test void explicitCorrectionsAndAmbiguousRowsKeepTheirFields() {
        HistoryProductMatcher matcher = new HistoryProductMatcher(List.of(product("Jabón neutro 500g", "Marca Prueba", "Limpieza")));
        ReceiptItem learned = item("marca prueba jabon neutro 500g", "Marca manual", "LEARNED");
        List<String> warnings = new ArrayList<>();
        assertThat(matcher.enrich(learned, warnings)).isSameAs(learned);
        assertThat(warnings).isEmpty();
        ReceiptItem ambiguous = item("marca prueba jabon neutro 500g", "Genérico", "AMBIGUOUS");
        assertThat(matcher.enrich(ambiguous, warnings)).isSameAs(ambiguous);
        assertThat(warnings).singleElement().asString().contains("Sugerencia", "Jabón neutro 500g");
    }

    @Test void duplicateRowsAreHarmlessButConflictingCategoriesNeverVoteByMajority() {
        Product known = product("Jabón neutro 500g", "Marca Prueba", "Limpieza");
        ReceiptItem original = item("marca prueba jabon neutro 500g", "Marca Prueba", "CORRECT");
        HistoryProductMatcher matcher = new HistoryProductMatcher(List.of(known, known));
        assertThat(matcher.enrich(original, new ArrayList<>()).estado()).isEqualTo("HISTORY");
        matcher = new HistoryProductMatcher(List.of(known, known, product("Jabón neutro 500g", "Marca Prueba", "Otros")));
        List<String> warnings = new ArrayList<>();
        assertThat(matcher.enrich(original, warnings)).isSameAs(original);
        assertThat(warnings).singleElement().asString().contains("contradictorio");
    }

    @Test void noBrandIsInferredFromAGenericDescriptionEvenWithOnlyOneHistoricalBrand() {
        HistoryProductMatcher matcher = new HistoryProductMatcher(List.of(product("Leche 1l", "Marca Prueba", "Supermercado")));
        ReceiptItem original = new ReceiptItem("Leche 1l", "Genérico", "Comercio prueba", "Supermercado",
                "1", "100", "4/10/2026", "CORRECT", "leche 1l");
        assertThat(matcher.enrich(original, new ArrayList<>())).isSameAs(original);
        matcher = new HistoryProductMatcher(List.of(product("Leche 1l", "Marca Prueba", "Supermercado"), product("Leche 1l", "Otra Marca", "Otros")));
        assertThat(matcher.enrich(original, new ArrayList<>())).isSameAs(original);
    }

    @Test void generalizedDescriptionsDoNotEraseSizeFlavorOrDietVariants() {
        HistoryProductMatcher matcher = new HistoryProductMatcher(List.of(product("Yogur vainilla 500g", "Marca Prueba", "Otros")));
        for (String signature : List.of("marca prueba yogur chocolate 500g", "marca prueba yogur vainilla 1kg",
                "marca prueba yogur vainilla 500g light", "marca prueba yogur vainilla 500g sin azucar")) {
            ReceiptItem original = item(signature, "Marca Prueba", "CORRECT");
            List<String> warnings = new ArrayList<>();
            assertThat(matcher.enrich(original, warnings)).isSameAs(original);
            assertThat(warnings).isEmpty();
        }
    }

    @Test void similarOcrIsOnlySuggestedWithoutRewritingTheRow() {
        HistoryProductMatcher matcher = new HistoryProductMatcher(List.of(product("Jabón neutro 500g", "Marca Prueba", "Limpieza")));
        ReceiptItem original = item("marca prueba jabon neulro 500g", "Genérico", "CORRECT");
        List<String> warnings = new ArrayList<>();
        assertThat(matcher.enrich(original, warnings)).isSameAs(original);
        assertThat(warnings).singleElement().asString().contains("Sugerencia", "Limpieza");
    }

    @Test void anotherStoreUnknownStoreBlankSignatureAndContradictingBrandReceiveNoOverride() {
        HistoryProductMatcher matcher = new HistoryProductMatcher(List.of(product("Jabón neutro 500g", "Marca Prueba", "Limpieza")));
        for (String store : List.of("Otro comercio", "Compra sin identificar", "")) {
            ReceiptItem original = new ReceiptItem("Jabón", "Genérico", store, "Otros", "1", "100", "4/10/2026", "CORRECT", "marca prueba jabon neutro 500g");
            assertThat(matcher.enrich(original, new ArrayList<>())).isSameAs(original);
        }
        ReceiptItem blank = item("", "Marca Prueba", "CORRECT");
        assertThat(matcher.enrich(blank, new ArrayList<>())).isSameAs(blank);
        ReceiptItem contrary = item("marca prueba jabon neutro 500g", "Marca diferente", "CORRECT");
        assertThat(matcher.enrich(contrary, new ArrayList<>())).isSameAs(contrary);
    }

    @Test void brandWithinAnotherWordIsNotAnExactBrandMatch() {
        HistoryProductMatcher matcher = new HistoryProductMatcher(List.of(product("Arroz", "Sol", "Otros")));
        ReceiptItem original = item("soluble arroz", "Genérico", "CORRECT");
        assertThat(matcher.enrich(original, new ArrayList<>())).isSameAs(original);
    }
}
