package com.opencode.facturas.service;

import com.opencode.facturas.model.ReceiptItem;
import com.opencode.facturas.model.ReceiptSource;
import java.util.*;

/** Asocia firmas exactas en orden de aparición; nunca adivina por precio o similitud. */
final class ReceiptEvidenceMapper {
    static List<ReceiptItem> attach(List<ReceiptItem> items, ReceiptLayoutReader.Layout layout, ReceiptLineAnalyzer analyzer) {
        Map<String, Deque<ReceiptSource>> available = new LinkedHashMap<>();
        Map<String, Deque<ReceiptSource>> descriptionOnly = new LinkedHashMap<>();
        for (int index = 0; index < layout.lines().size(); index++) {
            String line = layout.lines().get(index);
            ReceiptSource source = layout.sourcesByLine().get(index);
            if (source == null || source.detectionIndices().isEmpty()) continue;
            String signature = analyzer.normalize(analyzer.removeMoneyValues(analyzer.clean(line)));
            if (analyzer.containsMoney(line)) {
                available.computeIfAbsent(signature, key -> new ArrayDeque<>()).add(source);
            } else if (!analyzer.shouldSkip(signature)) {
                List<Integer> indices = new ArrayList<>(source.detectionIndices());
                if (index + 1 < layout.lines().size() && analyzer.isMoneyValue(layout.lines().get(index + 1))) {
                    ReceiptSource price = layout.sourcesByLine().get(index + 1);
                    if (price != null && price.pageNumber() == source.pageNumber()) indices.addAll(price.detectionIndices());
                }
                descriptionOnly.computeIfAbsent(signature, key -> new ArrayDeque<>()).add(new ReceiptSource(source.pageNumber(), indices));
            }
        }
        return items.stream().map(item -> {
            Deque<ReceiptSource> sources = available.get(item.firma());
            if ((sources == null || sources.isEmpty()) && !item.firma().equals(analyzer.normalize(item.lugarDeCompra()))) {
                sources = descriptionOnly.get(item.firma());
            }
            return sources == null || sources.isEmpty() ? item : item.withSources(List.of(sources.removeFirst()));
        }).toList();
    }
}
