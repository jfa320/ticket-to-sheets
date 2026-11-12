package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.facturas.model.OcrDetection;
import com.opencode.facturas.model.OcrResult;
import com.opencode.facturas.model.ReceiptSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiptHardwareBlockTest {
    @TempDir Path directory;

    // Encabezado y códigos ficticios; se conserva solamente la estructura del caso.
    @Test void readsDescriptionThenQuantityAndAmountsWithoutExportingArticleCodes() {
        var response = parser().parse("1199990000\nFERRETERIA DE PRUEBA\nTICKET\nFecha 11/11/26\n"
                + "PLAFON LED CUADRADO EXTERIOR 18W LUZ\n1 x $ 9.200,00 $ 9.200,00\nART0101\n"
                + "POXIPOL TRANS.16G.POX\n1 x $ 8.100,00 $ 8.100,00\nART0102\n"
                + "Subtotal $ 17.300,00\nTOTAL $ 17.300,00");
        assertThat(response.items()).hasSize(2);
        assertThat(response.items()).extracting(i -> i.cantidad()).containsExactly("1", "1");
        assertThat(response.items()).extracting(i -> i.precioUnitario()).containsExactly("9200,00", "8100,00");
        assertThat(response.items()).allSatisfy(i -> {
            assertThat(i.descripcion()).doesNotContainIgnoringCase("ART010", "Ferreteria");
            assertThat(i.firma()).doesNotContain("art010");
            assertThat(i.estado()).isEqualTo("CORRECT");
        });
        assertThat(response.total()).isEqualTo("17300,00");
        assertThat(response.warnings()).isEmpty();
        assertThat(response.tsvWithoutHeader().lines()).hasSize(2);
    }

    @Test void phoneBeforeMerchantIsNeverAnIncompleteProduct() {
        var response = parser().parse("1199990000\nFERRETERIA DE PRUEBA\nTICKET\n"
                + "TORNILLO ACERO 900,00\nTOTAL 900,00");
        assertThat(response.items()).hasSize(1);
        assertThat(response.warnings()).isEmpty();
    }

    @Test void plafonIsAProductTypeEvenWhenOcrConfusesTheLWithAnI() {
        var mapper = new ObjectMapper();
        var brands = new BrandCatalog(mapper, directory.resolve("known-brands.json"));
        brands.remember("Patitas");
        brands.remember("Pepsi");
        var parser = new ReceiptParserService(StoreNameMapper.empty(), brands,
                new CorrectionMemory(mapper, directory.resolve("memory.json")));
        for (String prefix : List.of("PLAFON", "PIAFON")) {
            var response = parser.parse("FERRETERIA DE PRUEBA\n" + prefix
                    + " LED CUADRADO EXTERIOR 18W\n1 x $9200,00 $9200,00\nART0101\nTOTAL $9200,00");
            assertThat(response.items()).singleElement().satisfies(i -> {
                assertThat(i.descripcion()).isEqualTo("Plafon Led Cuadrado Exterior 18w");
                assertThat(i.marca()).isEqualTo("Genérico");
            });
            assertThat(response.warnings()).isEmpty();
        }
    }

    @Test void readsCompactMultipliersAndTotalsOnArticleCodeRows() {
        var response = parser().parse("FERRETERIA DE PRUEBA\nPLAFON LED EXTERIOR 18W\n"
                + "1$9.200,00\nART0101 $9.200,00\nPOXIPOLTRANS.16G.POX\n"
                + "1%$8.100,00\nART0102 $8.100,00\nTOTAL $17.300,00");
        assertThat(response.items()).hasSize(2);
        assertThat(response.items()).extracting(i -> i.precioUnitario()).containsExactly("9200,00", "8100,00");
        assertThat(response.items().get(1).estado()).isEqualTo("AMBIGUOUS");
        assertThat(response.warnings()).anyMatch(w -> w.contains("cantidad OCR del bloque"));
        assertThat(response.items()).allSatisfy(i -> assertThat(i.firma()).doesNotContain("art010"));
        assertThat(response.total()).isEqualTo("17300,00");
    }

    @Test void readsSplitAmountsAndPreservesQuantityEvidenceAndUncertainty() {
        var detections = List.of(box("FERRETERIA DE PRUEBA", .99, 0),
                box("TORNILLO ACERO", .99, 60), box("2 x $ 900,00", .45, 90),
                box("$ 1.800,00", .99, 120), box("ART0101", .99, 150),
                box("TOTAL $ 1.800,00", .99, 180));
        var response = parser().parse(new OcrResult("", List.of(), detections, "synthetic", 100.0));
        assertThat(response.items()).singleElement().satisfies(i -> {
            assertThat(i.cantidad()).isEqualTo("2");
            assertThat(i.precioUnitario()).isEqualTo("900,00");
            assertThat(i.estado()).isEqualTo("AMBIGUOUS");
            assertThat(i.fuentesOcr()).containsExactly(new ReceiptSource(1, List.of(1, 2, 3, 4)));
        });
        assertThat(response.total()).isEqualTo("1800,00");
    }

    @Test void mismatchedAmountsRequireReviewAndKeepThePrintedLineAmount() {
        var response = parser().parse("FERRETERIA DE PRUEBA\nTORNILLO ACERO\n"
                + "2 x $ 900,00 $ 1.700,00\nART0101\nTOTAL $ 1.700,00");
        assertThat(response.items()).singleElement().satisfies(i -> {
            assertThat(i.cantidad()).isEqualTo("2");
            assertThat(i.precioUnitario()).isEqualTo("850,00");
            assertThat(i.estado()).isEqualTo("AMBIGUOUS");
        });
        assertThat(response.total()).isEqualTo("1700,00");
        assertThat(response.warnings()).anyMatch(w -> w.contains("cantidad") && w.contains("importe"));
    }

    @Test void missingPriceNeverBorrowsTheNextProductsAmount() {
        var response = parser().parse("FERRETERIA DE PRUEBA\nTORNILLO ACERO\nART0101\n"
                + "TUERCA ACERO\n2 x $ 900,00 $ 1.800,00\nART0102\nTOTAL $ 1.800,00");
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).firma()).contains("tuerca");
        assertThat(response.total()).isEqualTo("1800,00");
    }

    private ReceiptParserService parser() {
        var mapper = new ObjectMapper();
        return new ReceiptParserService(StoreNameMapper.empty(),
                new BrandCatalog(mapper, directory.resolve("brands.json")),
                new CorrectionMemory(mapper, directory.resolve("memory.json")));
    }

    private OcrDetection box(String text, double confidence, double top) {
        return new OcrDetection(text, confidence, List.of(List.of(0.0, top), List.of(450.0, top),
                List.of(450.0, top + 20), List.of(0.0, top + 20)));
    }
}
