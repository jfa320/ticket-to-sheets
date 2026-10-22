package com.opencode.facturas.service;

import com.opencode.facturas.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ReceiptEvidenceTest {
    private OcrDetection box(String text, double x, double y) {
        return new OcrDetection(text, .99, List.of(List.of(x,y), List.of(x+100,y), List.of(x+100,y+20), List.of(x,y+20)));
    }
    private ReceiptItem item() { return new ReceiptItem("Leche corregida", "Marca", "Local", "Otros", "1", "1200", "", "AMBIGUOUS", "leche", true); }
    @Test void preservesOriginalIndicesAndAssignsRepeatedSignaturesAcrossPagesInOrder() {
        var page1 = new OcrResult("", List.of(), List.of(box("Leche", 0, 10), box("1200,00", 200, 10)), "x", 1.0);
        var page2 = new OcrResult("", List.of(), List.of(new OcrDetection("", .99, List.of()), box("Leche", 0, 40), box("1400,00", 200, 40)), "x", 1.0);
        var analyzer = new ReceiptLineAnalyzer();
        var layout = new ReceiptLayoutReader(analyzer, new ReceiptAmounts()).read(new OcrResult("", List.of(), List.of(), "x", 1.0, List.of(page1,page2)));
        var items = ReceiptEvidenceMapper.attach(List.of(item(), item(), item()), layout, analyzer);
        assertThat(items.get(0).fuentesOcr()).containsExactly(new ReceiptSource(1, List.of(0,1)));
        assertThat(items.get(1).fuentesOcr()).containsExactly(new ReceiptSource(2, List.of(1,2)));
        assertThat(items.get(2).fuentesOcr()).isEmpty();
        assertThat(items.get(0).memorizado()).isTrue();
        assertThat(items.get(0).estado()).isEqualTo("AMBIGUOUS");
    }
    @Test void textOnlyInputHasNoInventedCoordinates() {
        var analyzer = new ReceiptLineAnalyzer();
        var layout = new ReceiptLayoutReader(analyzer, new ReceiptAmounts()).read(new OcrResult("Leche 1200,00",List.of(),List.of(),"pdf-text",1.0));
        assertThat(ReceiptEvidenceMapper.attach(List.of(item()),layout,analyzer).get(0).fuentesOcr()).isEmpty();
    }
    @Test void aSeparatePriceRowIsIncludedWithoutCrossingToAnotherPage() {
        var analyzer = new ReceiptLineAnalyzer();
        var layout = new ReceiptLayoutReader(analyzer, new ReceiptAmounts()).read(new OcrResult("", List.of(),
                List.of(box("Leche",0,10), box("1200,00",0,60)), "x",1.0));
        assertThat(ReceiptEvidenceMapper.attach(List.of(item()),layout,analyzer).get(0).fuentesOcr())
                .containsExactly(new ReceiptSource(1,List.of(0,1)));
    }
}
