package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.facturas.model.ExtractResponse;
import com.opencode.facturas.model.OcrDetection;
import com.opencode.facturas.model.OcrResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PigmentoReceiptParserTest {
    @TempDir
    Path tempDir;

    // Texto reproducible sin CUIT, dirección, número de ticket ni imagen personal.
    private static final String OCR = """
            PIGMENTO
            Fecha05/10/2026 Hora:19:46:42
            4000000000001
            NIUEA BODY HID/EXP NORMAL 250M 21.00 8800.00
            4000000000001
            NIUEA BODY HID/EXP NORMAL 250M (21.00) 8800.00
            7500000000002
            PANTENE SH-SOLU/RESTAURAC 400M (21.00) 10833.33
            7500000000003
            PANTENE AC/SOLU/RESTAURAC 40OM (21.00) 10833.33
            4000004
            NIUEA FAC-CR CUID/NUTRITI 1OOM (21.00) 8700.00
            7500000000005
            COLGATE TRIPLEACCION 90G (21.00) 4125.00
            BONIFICACION
            2x1 (21.00 -8800.00
            BONIFICACION
            Oferta (21.00) -9491.66
            TOTAL 33800.00
            RECIBI(MOS)
            ELECTRON DEBITO OTROS BAN 33800.00
            CAMBIO 0.00
            IVA Contenido 5866.72
            Puntos Club Pigmento Obtenidos:100
            Consulta tus puntos en www.clubpigmento.com.ar
            CODIGO0000000000 V:01.00
            """;

    @Test
    void readsSixUnitsAndBonusesWithoutTaxPaymentOrFooterProducts() {
        ExtractResponse response = parser().parse(OCR);

        assertEquals(6, response.itemCount(), response.csv());
        assertEquals("Perfumerias Pigmento", response.storeName());
        assertEquals("5/10/2026", response.date());
        assertEquals(List.of("8800,00", "8800,00", "10833,33", "10833,33", "8700,00", "4125,00"),
                response.items().stream().map(item -> item.precioUnitario()).toList());
        assertEquals(List.of("Body Hid/Exp Normal 250 ml", "Body Hid/Exp Normal 250 ml",
                        "Shampoo Solu/Restauración 400 ml", "Acondicionador Solu/Restauración 400 ml",
                        "Facial Crema Cuidado/Nutritiva 100 ml", "Triple Acción 90 g"),
                response.items().stream().map(item -> item.descripcion()).toList());
        assertEquals(List.of("Nivea", "Nivea", "Pantene", "Pantene", "Nivea", "Colgate"),
                response.items().stream().map(item -> item.marca()).toList());
        assertTrue(response.items().stream().allMatch(item -> item.cantidad().equals("1")
                && item.categoria().equals("Perfumeria")));
        assertEquals("33800,00", response.total());
        assertEquals("52091,66", response.subtotal());
        assertEquals("-18291,66", response.discountTotal());
        assertEquals(List.of("Bonificación 2x1", "Bonificación Oferta"),
                response.discounts().stream().map(discount -> discount.concepto()).toList());
        assertEquals(List.of("-8800,00", "-9491,66"),
                response.discounts().stream().map(discount -> discount.importe()).toList());
        ExtractResponse enriched = response.withOcrMetadata("geometry", 100.0).withOcrReview(List.of());
        assertEquals(response.discounts(), enriched.discounts());
        assertEquals(response.subtotal(), enriched.subtotal());
        assertEquals(response.discountTotal(), enriched.discountTotal());
        assertFalse(response.tsv().contains("Bonificación"));
        List<String> rows = response.tsvWithoutHeader().lines().toList();
        assertEquals(8, rows.size());
        assertEquals("Descuento\tGenérico\tPerfumerias Pigmento\tPerfumeria\t1\t-8800,00\t5/10/2026", rows.get(6));
        assertEquals("Descuento\tGenérico\tPerfumerias Pigmento\tPerfumeria\t1\t-9491,66\t5/10/2026", rows.get(7));
        assertEquals(9, response.csv().lines().count());
        assertEquals(9, response.tsv().lines().count());
        assertTrue(rows.stream().allMatch(row -> row.split("\t", -1).length == 7));
    }

    @Test
    void associatesPriceAlignedWithBarcodeWithFollowingDescription() {
        OcrResult result = new OcrResult("", List.of(), List.of(
                box("PIGMENTO", 0, 0, 180),
                box("4000000000001", 50, 0, 200), box("8800.00", 50, 600, 700),
                box("NIVEA BODY HID/EXP NORMAL 250M", 80, 0, 400), box("(21.00)", 80, 450, 530),
                box("TOTAL 8800.00", 120, 0, 700)
        ), "geometry", 100.0);

        ExtractResponse response = parser().parse(result);

        assertEquals(1, response.itemCount(), response.csv());
        assertEquals("8800,00", response.items().get(0).precioUnitario());
        assertEquals("Body Hid/Exp Normal 250 ml", response.items().get(0).descripcion());
    }

    @Test
    void neverUsesTaxRateAsMissingProductPrice() {
        ExtractResponse response = parser().parse("""
                PIGMENTO
                NIVEA BODY HID/EXP NORMAL 250M (21.00)
                TOTAL 8800.00
                """);

        assertEquals(0, response.itemCount(), response.csv());
        assertEquals("0,00", response.total());
        assertFalse(response.warnings().isEmpty());
    }

    @Test
    void readsSplitProductPriceAndSplitBonusButNeverGuessesMissingMinusSign() {
        ExtractResponse response = parser().parse("""
                PIGMENTO
                NIVEA BODY HID/EXP NORMAL 250M
                (21.00) 8800.00
                BONIFICACION
                Oferta
                (21.00)
                -1000.00
                BONIFICACION 2x1 21.00 8800.00
                TOTAL 1.00
                """);

        assertEquals(1, response.itemCount());
        assertEquals("8800,00", response.subtotal());
        assertEquals(1, response.discounts().size());
        assertEquals("-1000,00", response.discountTotal());
        assertEquals("7800,00", response.total());
        assertTrue(response.warnings().stream().anyMatch(warning -> warning.contains("sin importe negativo")));
    }

    @Test
    void doesNotRecoverOldLearnedPaymentOrOfferAsProducts() {
        ObjectMapper mapper = new ObjectMapper();
        CorrectionMemory memory = new CorrectionMemory(mapper, tempDir.resolve("corrections.json"));
        memory.upsert("Perfumerias Pigmento", "oferta", "Oferta", "Genérico", "Perfumeria");
        memory.upsert("Perfumerias Pigmento", "electron debito otros ban", "Pago", "Genérico", "Perfumeria");
        ReceiptParserService parser = new ReceiptParserService(
                new StoreNameMapper(mapper, new ClassPathResource("store-mappings.json")),
                new BrandCatalog(mapper, tempDir.resolve("brands.json")), memory);

        ExtractResponse response = parser.parse("""
                PIGMENTO
                SHAMPOO 1000.00
                BONIFICACION
                Oferta -100.00
                TOTAL 900.00
                ELECTRON DEBITO OTROS BAN 900.00
                """);

        assertEquals(1, response.itemCount());
        assertEquals("900,00", response.total());
    }

    @Test
    void retainsLowConfidenceWhenPriceIsAlignedWithBarcode() {
        OcrDetection lowPrice = new OcrDetection("8800.00", 0.5, box("8800.00", 50, 600, 700).box());
        ExtractResponse response = parser().parse(new OcrResult("", List.of(), List.of(
                box("PIGMENTO", 0, 0, 180), box("4000000000001", 50, 0, 200), lowPrice,
                box("NIVEA BODY HID/EXP NORMAL 250M", 80, 0, 400), box("(21.00)", 80, 450, 530)
        ), "geometry", 100.0));

        assertEquals(1, response.itemCount());
        assertEquals("8800,00", response.items().get(0).precioUnitario());
        assertEquals("AMBIGUOUS", response.items().get(0).estado());
    }

    @Test
    void excludesPaymentAndFooterEvenWhenPrintedTotalIsMissing() {
        ExtractResponse response = parser().parse("""
                PIGMENTO
                NIVEA BODY HID/EXP NORMAL 250M (21.00) 8800.00
                BONIFICACION Oferta -1000.00
                ELECTRON DEBITO OTROS BAN 7800.00
                CODIGO0000000000 V:01.00
                """);

        assertEquals(1, response.itemCount(), response.csv());
        assertEquals("7800,00", response.total());
    }

    private ReceiptParserService parser() {
        ObjectMapper mapper = new ObjectMapper();
        BrandCatalog brands = new BrandCatalog(mapper, tempDir.resolve("brands.json"));
        for (String brand : List.of("Nivea", "Pantene", "Colgate")) brands.remember(brand);
        return new ReceiptParserService(
                new StoreNameMapper(mapper, new ClassPathResource("store-mappings.json")), brands,
                new CorrectionMemory(mapper, tempDir.resolve("corrections.json")));
    }

    private OcrDetection box(String text, double top, double left, double right) {
        return new OcrDetection(text, 0.93, List.of(List.of(left, top), List.of(right, top),
                List.of(right, top + 20), List.of(left, top + 20)));
    }
}
