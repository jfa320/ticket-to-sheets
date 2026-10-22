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
