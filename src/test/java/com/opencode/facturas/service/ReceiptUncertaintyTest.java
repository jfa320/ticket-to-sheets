package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.facturas.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ReceiptUncertaintyTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void repeatedRecoveredDescriptionsUseTheirOwnFollowingPriceAndUncertainty() {
        var memory = new CorrectionMemory(mapper, directory.resolve("memory.json"));
        memory.upsert("Comercio Prueba", "gal oreo leche 1l descuento", "Galletitas Oreo", "Oreo", "Otros");
        String[] text = {"Comercio Prueba", "GAL OREO LECHE 1L DESCUENTO", "2400,00",
                "GAL OREO LECHE 1L DESCUENTO", "2800,00"};
        var lines = java.util.stream.IntStream.range(0, text.length).mapToObj(i ->
                new OcrLine(text[i], 0, i == 4 ? .20 : .99, i * 40, 0, 400, i * 40 + 20)).toList();
        var result = parser(memory).parse(new OcrResult("", lines, List.of(), "synthetic", 1.0));
        assertThat(result.items()).extracting(item -> item.precioUnitario()).containsExactly("2400,00", "2800,00");
        assertThat(result.items()).extracting(item -> item.estado()).containsExactly("LEARNED", "AMBIGUOUS");
        assertThat(result.items()).allMatch(item -> item.memorizado());
    }

    @Test void recoveredMemoryNeverBorrowsTheNextProductsAmount() {
        var memory = new CorrectionMemory(mapper, directory.resolve("memory.json"));
        memory.upsert("Comercio Prueba", "gal oreo leche 1l descuento", "Galletitas Oreo", "Oreo", "Otros");
        var result = parser(memory).parse("Comercio Prueba\nGAL OREO LECHE 1L DESCUENTO\nLECHE ENTERA 2800,00");
        assertThat(result.items()).filteredOn(item -> item.memorizado()).singleElement().satisfies(item -> {
            assertThat(item.precioUnitario()).isEmpty(); assertThat(item.estado()).isEqualTo("AMBIGUOUS");
        });
        assertThat(result.total()).isEqualTo("2800,00");
    }

    @Test void quantityFromPreviousRowKeepsLowConfidenceForBoxesAndFallbackLines() {
        String[] text = {"Comercio Prueba", "Fecha 10/10/2026", "2 x 1000,00", "Leche entera 2000,00"};
        var lines = java.util.stream.IntStream.range(0, text.length)
                .mapToObj(i -> new OcrLine(text[i], 0, i == 2 ? .20 : .99, i * 40, 0, 400, i * 40 + 20)).toList();
        var boxes = List.of(box(text[0], .99, 0, 0, 200), box(text[1], .99, 40, 0, 200),
                box(text[2], .20, 80, 0, 200), box("Leche entera", .99, 120, 0, 180), box("2000,00", .99, 120, 300, 400));
        var parser = parser(new CorrectionMemory(mapper, directory.resolve("memory.json")));
        for (var ocr : List.of(new OcrResult(String.join("\n", text), lines, List.of(), "synthetic", 1.0),
                new OcrResult(String.join("\n", text), List.of(), boxes, "synthetic", 1.0))) {
            var result = parser.parse(ocr);
            assertThat(result.items()).singleElement().satisfies(item -> {
                assertThat(item.cantidad()).isEqualTo("2");
                assertThat(item.precioUnitario()).isEqualTo("1000,00");
                assertThat(item.estado()).isEqualTo("AMBIGUOUS");
            });
            assertThat(result.warnings()).anyMatch(w -> w.contains("OCR"));
        }
    }

    @Test void memoryRecoveryKeepsUncertainPriceAndTheCurrentSourceSignature() {
        var memory = new CorrectionMemory(mapper, directory.resolve("memory.json"));
        memory.upsert("Comercio Prueba", "gal oreo leche 1l descuento", "Galletitas Oreo", "Oreo", "Otros");
        var ocr = new OcrResult("", List.of(), List.of(box("Comercio Prueba", .99, 0, 0, 200),
                box("GAL OREO LECHE 1L DESCUENTO", .99, 40, 0, 330),
                box("2400,00", .20, 40, 400, 500)), "synthetic", 1.0);
        var item = parser(memory).parse(ocr).items().get(0);
        assertThat(item.memorizado()).isTrue();
        assertThat(item.estado()).isEqualTo("AMBIGUOUS");
        assertThat(item.precioUnitario()).isEqualTo("2400,00");
        assertThat(item.fuentesOcr()).containsExactly(new ReceiptSource(1, List.of(1, 2)));
    }

    @Test void splitDescriptionAndPriceKeepUncertaintyFromEitherLine() {
        var ocr = new OcrResult("", List.of(new OcrLine("Comercio Prueba", 0, .99, 0, 0, 300, 20),
                new OcrLine("Leche entera", 0, .99, 40, 0, 200, 60),
                new OcrLine("1200,00", 0, .20, 80, 0, 100, 100)), List.of(), "synthetic", 1.0);
        assertThat(parser(new CorrectionMemory(mapper, directory.resolve("memory.json"))).parse(ocr).items())
                .singleElement().extracting(item -> item.estado()).isEqualTo("AMBIGUOUS");
    }

    private OcrDetection box(String text, double confidence, double top, double left, double right) {
        return new OcrDetection(text, confidence, List.of(List.of(left, top), List.of(right, top),
                List.of(right, top + 20), List.of(left, top + 20)));
    }

    private ReceiptParserService parser(CorrectionMemory memory) {
        return new ReceiptParserService(StoreNameMapper.empty(),
                new BrandCatalog(mapper, directory.resolve("brands.json")), memory);
    }

    @Test void conflictingHighConfidencePriceRemainsAmbiguousWithItsOriginalValueAndSource() throws Exception {
        String json = """
                {"text":"LOCAL\\nMAYONESA 2800,00","detections":[
                {"text":"LOCAL","confidence":0.99,"box":[[0,0],[100,0],[100,20],[0,20]]},
                {"text":"MAYONESA","confidence":0.99,"box":[[0,40],[140,40],[140,60],[0,60]]},
                {"text":"2800,00","confidence":0.946,"reviewRequired":true,"box":[[200,40],[300,40],[300,60],[200,60]]}]}
                """;
        var ocr = new OcrResultMapper(mapper).parse(json);
        var result = parser(new CorrectionMemory(mapper, directory.resolve("memory.json"))).parse(ocr);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).precioUnitario()).isEqualTo("2800,00");
        assertThat(result.items().get(0).estado()).isEqualTo("AMBIGUOUS");
        assertThat(result.items().get(0).fuentesOcr()).containsExactly(new ReceiptSource(1, List.of(1,2)));
    }

    @Test void flagsLowConfidencePedidosYaPriceAndKeepsItFlaggedAfterLearning() {
        String[] text = {"PedidosYa Market", "Fecha 10/10/2026", "Tu pedido", "Naranja Chica Comercial",
                "$1.025,46", "1x", "Tu pago"};
        List<OcrLine> lines = new ArrayList<>();
        for (int i = 0; i < text.length; i++) {
            lines.add(new OcrLine(text[i], 0, i == 4 ? .20 : .99, i * 40, 0, 600, i * 40 + 20));
        }
        OcrResult ocr = new OcrResult(String.join("\n", text), lines, List.of(), "synthetic", 1.0);
        CorrectionMemory memory = new CorrectionMemory(mapper, directory.resolve("memory.json"));
        var parser = parser(memory);
        var before = parser.parse(ocr);
        var item = before.items().get(0);
        assertThat(item.estado()).isEqualTo("AMBIGUOUS");
        assertThat(before.warnings()).anyMatch(warning -> warning.contains("lectura OCR"));
        assertThat(item.memorizado()).isFalse();
        memory.upsert(item.lugarDeCompra(), item.firma(), "Naranja corregida", "Genérico", "Verdulería");
        var learned = parser.parse(ocr).items().get(0);
        assertThat(learned.estado()).isEqualTo("AMBIGUOUS");
        assertThat(learned.memorizado()).isTrue();
        assertThat(learned.descripcion()).isEqualTo("Naranja corregida");
        assertThat(learned.precioUnitario()).isEqualTo(item.precioUnitario());
    }

    @Test void warnsAboutInferredYearWithoutChangingNormalization() {
        var result = parser(new CorrectionMemory(mapper, directory.resolve("memory.json")))
                .parse("Mercado Prueba\nEntregado 09 de oct\nPRODUCTO 1200,00");
        assertThat(result.date()).isEqualTo("9/10/" + java.time.LocalDate.now().getYear());
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("no indica año"));
    }

    @Test void learnedLayoutProductKeepsItsAmbiguityAndSevenExportColumns() throws Exception {
        var fixture = mapper.readTree(Path.of("src/test/resources/receipt-evaluation/evaluation/weighted-layout.json").toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) fixture.path("ocr").path("detections").get(4)).put("confidence", .20);
        OcrResult ocr = mapper.treeToValue(fixture.path("ocr"), OcrResult.class);
        var memory = new CorrectionMemory(mapper, directory.resolve("memory.json"));
        var parser = parser(memory);
        var before = parser.parse(ocr);
        var item = before.items().get(0);
        assertThat(item.estado()).isEqualTo("AMBIGUOUS");
        memory.upsert(item.lugarDeCompra(), item.firma(), "Nombre corregido", "Genérico", "Otros");
        var after = parser.parse(ocr);
        assertThat(after.items().get(0).estado()).isEqualTo("AMBIGUOUS");
        assertThat(after.items().get(0).memorizado()).isTrue();
        assertThat(after.total()).isEqualTo(before.total());
        assertThat(after.tsvWithoutHeader().lines()).allMatch(line -> line.split("\t", -1).length == 7);
        assertThat(after.warnings()).isEqualTo(before.warnings());
    }
}
