package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.facturas.model.OcrDetection;
import com.opencode.facturas.model.OcrResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiptPhotoRegressionTest {
    @TempDir Path directory;

    private ReceiptParserService parser() {
        var mapper = new ObjectMapper();
        var brands = new BrandCatalog(mapper, directory.resolve("brands.json"));
        for (String brand : List.of("Procenex", "Lucchetti", "Trio", "Pitusas", "Lux")) brands.remember(brand);
        return new ReceiptParserService(StoreNameMapper.empty(), brands,
                new CorrectionMemory(mapper, directory.resolve("corrections.json")));
    }

    @Test void joinedAndMisspelledBrandsKeepTheProductSuffixAndRepeatedRows() {
        var response = parser().parse("MERCADO PRUEBA\nFecha 30/09/2026\n"
                + "PROCENEXLIMON900ML 1600,00\nLUCCHETTTSPAG500GR 1350,00\n"
                + "LUCCHETTTSPAG500GR 1350,00\nTRTOGALLET300GR 1600,00\n"
                + "PTTUSASGALLET300GR 2100,00\nLUXJABONPAN3UN 4200,00\nSubtotal 12200,00");
        assertThat(response.itemCount()).isEqualTo(6);
        assertThat(response.items().stream().map(i -> i.marca())).containsExactly("Procenex", "Lucchetti", "Lucchetti", "Trio", "Pitusas", "Lux");
        assertThat(response.items().get(0).descripcion()).isEqualTo("Limon900ml");
        assertThat(response.items().get(1).descripcion()).isEqualTo("Spag500gr");
        assertThat(response.items().get(3).descripcion()).contains("Galletitas");
        assertThat(response.items().get(5).descripcion()).contains("Jabon");
        assertThat(response.warnings()).isEmpty();
    }

    @Test void subtotalEndsTheProductBlockEvenWhenPaymentAndTaxLabelsAreDamaged() {
        var response = parser().parse("MERCADO PRUEBA\nFecha 25/09/2026\n"
                + "PEPSI GASEOSA 3400,C0\nPRODUCTO SIGUIENTE 1500,00\nSubtotal 4900,00\n"
                + "ARJETA CE DHITE 4900,00\nCONSUM10CRE 2.74\nACONTERIDU 214.03");
        assertThat(response.itemCount()).isEqualTo(2);
        assertThat(response.items().get(0).precioUnitario()).isEqualTo("3400,00");
        assertThat(response.total()).isEqualTo("4900,00");
    }

    @Test void readsQuantityWhenOcrDropsTheMultiplierAndUsesTheFollowingLineAmount() {
        var response = parser().parse("MERCADO PRUEBA\n23000.00\nNOQUIS 450GR 6000,00\nSubtotal 6000,00");
        assertThat(response.itemCount()).isEqualTo(1);
        assertThat(response.items().get(0).cantidad()).isEqualTo("2");
        assertThat(response.items().get(0).precioUnitario()).isEqualTo("3000,00");
        assertThat(response.items().get(0).estado()).isEqualTo("AMBIGUOUS");
        assertThat(response.total()).isEqualTo("6000,00");
    }

    @Test void readsAWeightJoinedToItsUnitAmountWithoutChangingThePrintedProductAmount() {
        var response = parser().parse("MERCADO PRUEBA\n0.255012000.00\nQUESO CREMOSO 3060,00\nSubtotal 3060,00");
        assertThat(response.itemCount()).isEqualTo(1);
        assertThat(response.items().get(0).cantidad()).isEqualTo("0,255");
        assertThat(response.items().get(0).precioUnitario()).isEqualTo("12000,00");
        assertThat(response.items().get(0).estado()).isEqualTo("AMBIGUOUS");
        assertThat(response.total()).isEqualTo("3060,00");
        assertThat(response.warnings()).anyMatch(w -> w.contains("cantidad OCR"));
    }

    @Test void preservesClearlyReadWeightWhenTheStandaloneUnitAmountHasMissingDigits() {
        var response = parser().parse("MERCADO PRUEBA\n0,1700x220.0\nPAPAS FRITAS UN 3740,00\nSubtotal 3740,00");
        assertThat(response.itemCount()).isEqualTo(1);
        assertThat(response.items().get(0).cantidad()).isEqualTo("0,17");
        assertThat(response.items().get(0).precioUnitario()).isEqualTo("22000,00");
        assertThat(response.items().get(0).estado()).isEqualTo("AMBIGUOUS");
        assertThat(response.total()).isEqualTo("3740,00");
    }

    @Test void totalDisagreementIsReportedWithoutAdjustingPricesToMatchThePrintedTotal() {
        var response = parser().parse("MERCADO PRUEBA\nJABON 2800,00\nSubtotal 2600,00\nTOTAL 2600,00");
        assertThat(response.total()).isEqualTo("2800,00");
        assertThat(response.items().get(0).precioUnitario()).isEqualTo("2800,00");
        assertThat(response.warnings()).singleElement().asString().contains("difiere", "2600,00", "2800,00");
    }

    @Test void unreadableDateAndLowConfidenceMerchantAreExplicitlyReported() {
        var ocr = new OcrResult("MERCADO PRUEBA\nFecha25/C902\nPRODUCTO NORMAL 1000,00", List.of(), List.of(
                new OcrDetection("MERCADO PRUEBA", .6, List.of(List.of(20.0, 10.0), List.of(330.0, 10.0),
                        List.of(330.0, 34.0), List.of(20.0, 34.0))),
                box("Fecha25/C902", 50, 20, 330),
                box("PRODUCTO NORMAL", 100, 20, 330), box("1000,00", 82, 620, 730)
        ), "synthetic-weak-header", 100.0);
        var response = parser().parse(ocr);
        assertThat(response.date()).isEmpty();
        assertThat(response.warnings()).anyMatch(w -> w.contains("fecha de compra válida"));
        assertThat(response.warnings()).anyMatch(w -> w.contains("Comercio con lectura OCR dudosa"));
    }

    @Test void verifiedOcrMerchantAliasesUseTheCanonicalStore() {
        var stores = new StoreNameMapper(new ObjectMapper(), new ClassPathResource("store-mappings.json"));
        assertThat(stores.resolve(List.of("ZCLWEME"), "Zclweme")).isEqualTo("Los Tres Corazones");
        assertThat(stores.resolve(List.of("TOL.WENG."), "Tol.weng.")).isEqualTo("Los Tres Corazones");
    }

    @Test void explicitlyCorrectedGenericBrandDoesNotReappearAsThePreviousFalseBrand() {
        var mapper = new ObjectMapper();
        var brands = new BrandCatalog(mapper, directory.resolve("brands.json"));
        brands.remember("Marca Falsa");
        var memory = new CorrectionMemory(mapper, directory.resolve("corrections.json"));
        var controller = new com.opencode.facturas.controller.CorrectionController(memory, brands);
        controller.learn(new com.opencode.facturas.model.CorrectionsRequest("Mercado prueba",
                List.of(new com.opencode.facturas.model.CorrectionsRequest.Correction("marca falsa papas fritas un",
                        "Papas fritas", "Genérico", "Supermercado", "Marca Falsa"))));
        var response = new ReceiptParserService(StoreNameMapper.empty(), brands,
                new CorrectionMemory(mapper, directory.resolve("corrections.json")))
                .parse("MERCADO PRUEBA\nMARCA FALSA PAPAS FRITAS UN 3740,00");
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).marca()).isEqualTo("Genérico");
        assertThat(response.items().get(0).estado()).isEqualTo("LEARNED");
        assertThat(response.total()).isEqualTo("3740,00");
    }

    @Test void manualSupermarketLabelsKeepEachCurrentPriceDespiteOldCorrectionsAndHistory() throws Exception {
        var mapper = new ObjectMapper();
        var brands = new BrandCatalog(mapper, directory.resolve("brands.json"));
        var memoryPath = directory.resolve("corrections.json");
        var memory = new CorrectionMemory(mapper, memoryPath);
        memory.upsert("Mercado prueba", "almacen", "Fideos", "Marca vieja", "Otros");
        var localHistory = directory.resolve("local-history.json");
        mapper.writeValue(localHistory.toFile(), new com.opencode.facturas.model.SheetsHistoryModels.Snapshot(
                true, "", 2, "", 2, List.of("Mes prueba"), List.of(), List.of(
                new com.opencode.facturas.model.SheetsHistoryModels.Product("Almacen", "Genérico", "Mercado prueba", "Otros"),
                new com.opencode.facturas.model.SheetsHistoryModels.Product("Almacén", "Marca vieja", "Mercado prueba", "Limpieza"))));
        var history = new SheetsHistoryService(mapper,
                new SheetsConfigStore(mapper, directory.resolve("config.json").toString()), null,
                StoreNameMapper.empty(), directory.resolve("history.json").toString(), localHistory.toString());
        var response = new ReceiptParserService(StoreNameMapper.empty(), brands,
                new CorrectionMemory(mapper, memoryPath), history).parse("MERCADO PRUEBA\nFecha 05/10/2026\n"
                + "ALMACEN 2800,00\nALMACÉN 1400,00\nALMACEN 1400,00\nSubtotal 5600,00");
        assertThat(response.items()).hasSize(3).allSatisfy(item -> {
            assertThat(item.descripcion()).isEqualTo("Almacén");
            assertThat(item.marca()).isEqualTo("Genérico");
            assertThat(item.cantidad()).isEqualTo("1");
            assertThat(item.estado()).isEqualTo("CORRECT");
            assertThat(item.categoria()).isEqualTo("Supermercado");
        });
        assertThat(response.items().stream().map(item -> item.precioUnitario()))
                .containsExactly("2800,00", "1400,00", "1400,00");
        assertThat(response.total()).isEqualTo("5600,00");
        assertThat(response.tsvWithoutHeader().lines()).hasSize(3)
                .allMatch(line -> line.startsWith("Almacén\tGenérico\t"));
        assertThat(response.warnings()).isEmpty();
    }

    @Test void adjustsReceiptTiltBeforePairingPriceColumnsAndNeverUsesSubtotalAsAProductPrice() {
        var ocr = new OcrResult("", List.of(), List.of(
                box("MERCADO PRUEBA", 10, 20, 330),
                box("PRODUCTO ALFA", 100, 20, 330), box("1500,00", 82, 620, 730),
                box("PRODUCTO BETA", 140, 20, 330), box("1900,00", 122, 620, 730),
                box("PRODUCTO GAMMA", 180, 20, 330), box("2000,00", 162, 620, 730),
                box("ALMACEN", 220, 20, 160), box("2500,00", 202, 620, 730),
                box("Subtotal", 260, 20, 160), box("38000,00", 242, 620, 730),
                box("ARJETA CE DHITE", 330, 20, 330), box("36000,00", 312, 620, 730)
        ), "synthetic-tilted", 100.0);
        var response = parser().parse(ocr);
        assertThat(response.itemCount()).isEqualTo(4);
        assertThat(response.items().stream().map(i -> i.precioUnitario())).containsExactly("1500,00", "1900,00", "2000,00", "2500,00");
        assertThat(response.items().get(3).descripcion()).isEqualTo("Almacén");
        assertThat(response.items().get(3).marca()).isEqualTo("Genérico");
        assertThat(response.total()).isEqualTo("7900,00");
    }

    private OcrDetection box(String text, double y, double x, double right) {
        double rise = (right-x) * -.03;
        return new OcrDetection(text, .98, List.of(List.of(x, y), List.of(right, y+rise),
                List.of(right, y+rise+24), List.of(x, y+24)));
    }
}
