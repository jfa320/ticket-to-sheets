package com.opencode.facturas.service;

import com.opencode.facturas.model.ReceiptItem;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceiptParsingComponentsTest {

    private final ReceiptLineAnalyzer lineAnalyzer = new ReceiptLineAnalyzer();

    @Test
    void extractsAndNormalizesReceiptDateWithOcrSeparators() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
        ReceiptDateParser parser = new ReceiptDateParser(lineAnalyzer, clock);

        String date = parser.extractNormalized(List.of("Fecha V.", "29/O6/26"));

        assertEquals("29/6/2026", date);
    }

    @Test
    void ignoresActivityAndInvalidDates() {
        ReceiptDateParser parser = new ReceiptDateParser(lineAnalyzer);

        assertEquals("", parser.extractNormalized(List.of("Inicio actividad 01/01/2020")));
        assertEquals("", parser.extractNormalized(List.of("Fecha 01/00/2026")));
    }

    @Test
    void findsUnlabeledDatesAndPrioritizesExplicitReceiptDate() {
        ReceiptDateParser parser = new ReceiptDateParser(lineAnalyzer);

        assertEquals("26/9/2026", parser.extractNormalized(List.of("MERCADO", "26/09/2026 18:34", "PRODUCTO 100,00")));
        assertEquals("26/9/2026", parser.extractNormalized(List.of("MERCADO", "Emision 26.09.2026", "PRODUCTO 100,00")));
        assertEquals("26/9/2026", parser.extractNormalized(List.of("MERCADO", "2026-09-26", "PRODUCTO 100,00")));
        assertEquals("26/9/2026", parser.extractNormalized(List.of("Vto 30/09/2026", "Fecha 26/09/2026")));
        assertEquals("", parser.extractNormalized(List.of("Vto 30/09/2026", "PRODUCTO 100,00")));
    }

    @Test
    void parsesAndFormatsArgentineAmountsWithoutSharedFormatterState() {
        ReceiptAmounts amounts = new ReceiptAmounts();

        assertEquals(7170.40, amounts.parse("$ 7.17040"), 0.001);
        assertEquals(5082.15, amounts.parse("5.082,15"), 0.001);
        assertEquals(0.9, amounts.parseQuantity("0,9").orElseThrow(), 0.001);
        assertTrue(amounts.parseQuantity("sin cantidad").isEmpty());
        assertTrue(IntStream.range(0, 500)
                .parallel()
                .mapToObj(index -> amounts.format(5082.15))
                .allMatch("5082,15"::equals));
    }

    @Test
    void calculatesOnlyRecognizedItemsInsteadOfPrintedTaxesOrTotal() {
        ReceiptAmounts amounts = new ReceiptAmounts();
        ReceiptTotalCalculator calculator = new ReceiptTotalCalculator(amounts);
        List<ReceiptItem> items = List.of(
                new ReceiptItem("Producto", "Marca", "Comercio", "Otros", "2", "100,00", "1/1/2026")
        );

        String total = calculator.calculate(items);

        assertEquals("200,00", total);
    }

    @Test
    void extractsPedidosYaCandidateWithoutDependingOnItemConstruction() {
        PedidosYaReceiptParser parser = new PedidosYaReceiptParser(lineAnalyzer);
        List<String> lines = List.of(
                "PodidosYa Market - San Miguel II",
                "Cebolla Seleccion",
                "$ 800,02",
                "1g0.9 kg"
        );

        PedidosYaReceiptParser.ParseResult result = parser.parse(lines);

        assertTrue(parser.supports(lines));
        assertEquals(1, result.candidates().size());
        assertEquals("Cebolla Seleccion", result.candidates().get(0).description());
        assertEquals("$ 800,02", result.candidates().get(0).price());
        assertEquals("0.9", result.candidates().get(0).quantity());
        assertFalse(result.candidates().get(0).ambiguous());
        assertTrue(result.warnings().isEmpty());
    }
}
