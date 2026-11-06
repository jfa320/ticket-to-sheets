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

class ReceiptProductBlockTest {
    @TempDir Path directory;

    // Formato reproducible con comercio y códigos ficticios, sin datos del ticket personal.
    private static final String OCR = """
            MERCADO DE PRUEBA
            Fecha 30-10-26
            Código barras Pieza X Precio Importe
            9999999 1X2800.00 $2,800.00
            ALMACEN (21)
            4000000000001 1X1350.00 $1,350.00
            LUCCHETTI SPAGHETTI 500G (21)
            4000000000001 1X1350.00 $1,350.00
            LUCCHETTI SPAGHETTI 500G (21)
            4000000000002 2X2500.00 $5,000.00
            UNION SALCHICHA 230GR (21)
            Artículos: 5.00
            Subtot. 10500.00
            Total: $10,500.00
            Pago Efectivo 10500.00
            IVA Contenido 21% 1822.31
            """;

    @Test void recognizesPriceBeforeDescriptionByStructureAndPreservesRepetitions() {
        var response = parser().parse(OCR);
        assertThat(response.items()).hasSize(4);
        assertThat(response.items()).extracting(i -> i.cantidad()).containsExactly("1", "1", "1", "2");
        assertThat(response.items()).extracting(i -> i.precioUnitario())
                .containsExactly("2800,00", "1350,00", "1350,00", "2500,00");
        assertThat(response.items()).extracting(i -> i.marca())
                .containsExactly("Genérico", "Lucchetti", "Lucchetti", "Union");
        assertThat(response.items()).allSatisfy(i -> {
            assertThat(i.descripcion()).doesNotContain("(21)", "400000", "9999999");
            assertThat(i.firma()).doesNotContain("400000", "9999999", "21");
            assertThat(i.estado()).isEqualTo("CORRECT");
        });
        assertThat(response.date()).isEqualTo("30/10/2026");
        assertThat(response.total()).isEqualTo("10500,00");
        assertThat(response.warnings()).isEmpty();
        assertThat(response.tsvWithoutHeader().lines()).hasSize(4)
                .allMatch(line -> line.split("\t", -1).length == 7);
    }

    @Test void keepsBothRowsAsEvidenceAndPropagatesPriceUncertaintyThroughMemory() {
        var detections = List.of(box("MERCADO DE PRUEBA", .99, 0, 0, 280),
                box("4000000000001", .99, 60, 0, 220), box("2 × 1500,00", .99, 60, 240, 400),
                box("$3.000,00", .4, 60, 500, 610),
                box("LECHE ENTERA 1L", .99, 90, 0, 290), box("(21.00)", .99, 90, 500, 600));
        var ocr = new OcrResult("", List.of(), detections, "synthetic", 100.0);
        var mapper = new ObjectMapper();
        var memory = new CorrectionMemory(mapper, directory.resolve("memory.json"));
        var parser = new ReceiptParserService(StoreNameMapper.empty(),
                new BrandCatalog(mapper, directory.resolve("brands.json")), memory);
        var before = parser.parse(ocr);
        assertThat(before.items()).singleElement().satisfies(item -> {
            assertThat(item.cantidad()).isEqualTo("2");
            assertThat(item.precioUnitario()).isEqualTo("1500,00");
            assertThat(item.estado()).isEqualTo("AMBIGUOUS");
            assertThat(item.fuentesOcr()).containsExactly(new ReceiptSource(1, List.of(1, 2, 3, 4, 5)));
        });
        var item = before.items().get(0);
        memory.upsert(item.lugarDeCompra(), item.firma(), "Leche corregida", "Genérico", "Otros");
        assertThat(parser.parse(ocr).items()).singleElement().satisfies(learned -> {
            assertThat(learned.memorizado()).isTrue();
            assertThat(learned.estado()).isEqualTo("AMBIGUOUS");
            assertThat(learned.precioUnitario()).isEqualTo("1500,00");
            assertThat(learned.fuentesOcr()).isEqualTo(item.fuentesOcr());
        });
    }

    @Test void readsSplitCodeAndAmountsAndDoesNotTreatTheTaxRateAsAPrice() {
        var response = parser().parse("MERCADO DE PRUEBA\n4000000000001\n2X1500.00 $3000.00\n"
                + "LECHE ENTERA 1L\n(21.00)\n4000000000002 $900.00\nARROZ 1KG (10.50)\nTOTAL 3900.00");
        assertThat(response.items()).hasSize(2);
        assertThat(response.items()).extracting(i -> i.cantidad()).containsExactly("2", "1");
        assertThat(response.items()).extracting(i -> i.precioUnitario()).containsExactly("1500,00", "900,00");
        assertThat(response.total()).isEqualTo("3900,00");
        assertThat(response.warnings()).isEmpty();
    }

    @Test void inconsistentUnitPriceUsesThePrintedLineAmountAndRequiresReviewEvenIfTotalMatches() {
        var response = parser().parse("MERCADO DE PRUEBA\n4000000000001 2X1500.00 $2600.00\n"
                + "LECHE ENTERA 1L (21)\nTOTAL 2600.00");
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.cantidad()).isEqualTo("2");
            assertThat(item.precioUnitario()).isEqualTo("1300,00");
            assertThat(item.estado()).isEqualTo("AMBIGUOUS");
        });
        assertThat(response.total()).isEqualTo("2600,00");
        assertThat(response.warnings()).anyMatch(w -> w.contains("cantidad") && w.contains("importe"));
    }

    @Test void smallDisagreementBetweenExplicitAmountsAlsoRequiresReview() {
        var response = parser().parse("MERCADO DE PRUEBA\n4000000000001 2X1500.00 $2990.00\n"
                + "LECHE ENTERA 1L (21)\nTOTAL 2990.00");
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.cantidad()).isEqualTo("2");
            assertThat(item.precioUnitario()).isEqualTo("1495,00");
            assertThat(item.estado()).isEqualTo("AMBIGUOUS");
        });
        assertThat(response.total()).isEqualTo("2990,00");
        assertThat(response.warnings()).isNotEmpty();
    }

    @Test void missingDescriptionNeverConsumesTheNextProductsPriceOrDescription() {
        var response = parser().parse("MERCADO DE PRUEBA\n4000000000001 1X900.00 $900.00\n"
                + "4000000000002 2X1500.00 $3000.00\nLECHE ENTERA 1L (21)\nTOTAL 3900.00");
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.descripcion()).contains("Leche");
            assertThat(item.cantidad()).isEqualTo("2");
            assertThat(item.precioUnitario()).isEqualTo("1500,00");
        });
        assertThat(response.total()).isEqualTo("3000,00");
        assertThat(response.warnings()).anyMatch(w -> w.contains("Extracción parcial") && w.contains("sin descripción"));
    }

    @Test void missingPriceKeepsAnEditableProductWithoutBorrowingTheNextAmount() {
        var response = parser().parse("MERCADO DE PRUEBA\n4000000000001\nLECHE ENTERA 1L (21.00)\n"
                + "4000000000002 1X900.00 $900.00\nARROZ 1KG (21)\nTOTAL 900.00");
        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).precioUnitario()).isEmpty();
        assertThat(response.items().get(0).estado()).isEqualTo("AMBIGUOUS");
        assertThat(response.items().get(1).precioUnitario()).isEqualTo("900,00");
        assertThat(response.total()).isEqualTo("900,00");
        assertThat(response.warnings()).anyMatch(w -> w.contains("Extracción parcial") && w.contains("sin precio"));
    }

    @Test void warnsAboutUnassociatedDescriptionsInsideTheProductSection() {
        var response = parser().parse("MERCADO DE PRUEBA\n4000000000001 $900.00\nARROZ 1KG (21)\n"
                + "LECHE ENTERA 1L\n4000000000002 $1000.00\nAZUCAR 1KG (21)\nTOTAL 1900.00");
        assertThat(response.items()).hasSize(2);
        assertThat(response.warnings()).anyMatch(w -> w.contains("Extracción parcial") && w.contains("LECHE ENTERA"));
    }

    @Test void neverAssociatesBlocksAcrossPdfPages() {
        var first = new OcrResult("", List.of(), List.of(box("MERCADO DE PRUEBA", .99, 0, 0, 280),
                box("4000000000001 1X900.00 $900.00", .99, 60, 0, 600)), "synthetic", 100.0);
        var second = new OcrResult("", List.of(), List.of(box("LECHE ENTERA 1L (21)", .99, 0, 0, 280),
                box("TOTAL 900.00", .99, 60, 0, 280)), "synthetic", 100.0);
        var response = parser().parse(new OcrResult("", List.of(), List.of(), "synthetic", 100.0,
                List.of(first, second)));
        assertThat(response.items()).isEmpty();
        assertThat(response.warnings()).anyMatch(w -> w.contains("sin descripción"));
    }

    @Test void separatesJoinedBarcodesAndQuantitiesInsteadOfReadingHugeMultipliers() {
        var response = parser().parse("MERCADO DE PRUEBA\n40000000000012X1500.00 $3,000.00\nLECHE ENTERA 1L (21)\n"
                + "400000011X900.00 $900.00\nARROZ 1KG (21)\nTOTAL 3900.00");
        assertThat(response.items()).hasSize(2);
        assertThat(response.items()).extracting(i -> i.cantidad()).containsExactly("2", "1");
        assertThat(response.items()).extracting(i -> i.precioUnitario()).containsExactly("1500,00", "900,00");
        assertThat(response.total()).isEqualTo("3900,00");
        assertThat(response.warnings()).isEmpty();
    }

    @Test void damagedDecimalAndTaxParenthesisKeepTheCurrentAmountAndRequireReview() {
        var response = parser().parse("MERCADO DE PRUEBA\n40000000000011X120000 $1,200.00\nGALLETITAS 120GR (21)\n"
                + "40000000000021X2400.00 $2.400.00\nGALLETITAS 505GR (21\nTOTAL 3600.00");
        assertThat(response.items()).hasSize(2);
        assertThat(response.items()).extracting(i -> i.cantidad()).containsExactly("1", "1");
        assertThat(response.items()).extracting(i -> i.precioUnitario()).containsExactly("1200,00", "2400,00");
        assertThat(response.items()).allSatisfy(item -> {
            assertThat(item.estado()).isEqualTo("AMBIGUOUS");
            assertThat(item.descripcion()).doesNotContain("(21");
        });
        assertThat(response.total()).isEqualTo("3600,00");
        assertThat(response.warnings()).anyMatch(w -> w.contains("unitario OCR"));
    }

    @Test void unrecognizedNumericCodesAreNeverQuantitiesOfThePreviousDescription() {
        var response = parser().parse("MERCADO DE PRUEBA\nPRODUCTO SIN PRECIO\n"
                + "123456789012345678901X1500.00 $1500.00\nARROZ 1KG 900,00\nTOTAL 900.00");
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.cantidad()).isEqualTo("1");
            assertThat(item.precioUnitario()).isEqualTo("900,00");
        });
        assertThat(response.total()).isEqualTo("900,00");
    }

    private ReceiptParserService parser() {
        var mapper = new ObjectMapper();
        var brands = new BrandCatalog(mapper, directory.resolve("brands.json"));
        for (String brand : List.of("Lucchetti", "Union")) brands.remember(brand);
        return new ReceiptParserService(StoreNameMapper.empty(), brands,
                new CorrectionMemory(mapper, directory.resolve("memory.json")));
    }

    private OcrDetection box(String text, double confidence, double top, double left, double right) {
        return new OcrDetection(text, confidence, List.of(List.of(left, top), List.of(right, top),
                List.of(right, top + 20), List.of(left, top + 20)));
    }
}
