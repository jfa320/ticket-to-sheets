package com.opencode.facturas.service;

import com.opencode.facturas.model.ReceiptItem;
import com.opencode.facturas.model.SheetsHistoryModels.Product;

import java.text.Normalizer;
import java.util.*;

/** History is evidence about product names, never about the amounts in a new receipt. */
final class HistoryProductMatcher {
    private final Map<String, Map<String, List<Product>>> exact = new HashMap<>();
    private final Map<String, List<Product>> byStore = new HashMap<>();

    HistoryProductMatcher(List<Product> products) {
        for (Product product : products) {
            String store = normalize(product.comercio());
            if (unknownStore(store) || normalize(product.descripcion()).isBlank()) continue;
            byStore.computeIfAbsent(store, ignored -> new ArrayList<>()).add(product);
            Map<String, List<Product>> keys = exact.computeIfAbsent(store, ignored -> new HashMap<>());
            for (String name : names(product)) keys.computeIfAbsent(name, ignored -> new ArrayList<>()).add(product);
        }
    }

    ReceiptItem enrich(ReceiptItem item, List<String> warnings) {
        if (ReceiptProductRules.isAlmacen(item)) return ReceiptProductRules.almacen(item);
        if ("LEARNED".equals(item.estado())) return item;
        String store = normalize(item.lugarDeCompra());
        if (unknownStore(store)) return item;
        // A generalized parser description can lose flavor/size: require the original OCR signature.
        String signature = ProductSimilarity.key(item.firma());
        if (signature.isBlank()) return item;
        List<Product> matches = exact.getOrDefault(store, Map.of()).getOrDefault(signature, List.of());
        if (!matches.isEmpty()) {
            List<Product> unique = unique(matches);
            if (unique.size() != 1) {
                warnings.add("Historial contradictorio para «" + item.descripcion()
                        + "»: hay marcas o categorías diferentes. Revisá la fila.");
                return item;
            }
            Product product = unique.get(0);
            String currentBrand = normalize(item.marca());
            String historicalBrand = normalize(product.marca());
            if (!generic(currentBrand) && !historicalBrand.equals(currentBrand)) return item;
            if ("AMBIGUOUS".equals(item.estado())) {
                warnings.add(suggestion(product, item));
                return item;
            }
            return apply(product, item);
        }
        // Apply a clearly leading candidate; close competitors stay visible for review.
        List<Product> candidates = byStore.getOrDefault(store, List.of());
        if (candidates.size() > 1000 || signature.length() > 200) return item;
        double best = 0, second = 0;
        Product selected = null;
        for (Product candidate : unique(candidates)) {
            if (!generic(normalize(item.marca())) && !normalize(item.marca()).equals(normalize(candidate.marca()))) continue;
            double score = 0;
            for (String name : names(candidate)) {
                if (name.length() > 200) continue;
                score = Math.max(score, ProductSimilarity.score(signature, name));
            }
            if (score > best) { second = best; best = score; selected = candidate; }
            else second = Math.max(second, score);
        }
        if (selected != null && best >= .88 && best - second >= .06) {
            if ("AMBIGUOUS".equals(item.estado())) warnings.add(suggestion(selected, item));
            else return apply(selected, item);
        } else if (selected != null && best >= .8) {
            warnings.add(suggestion(selected, item));
        }
        return item;
    }

    private static ReceiptItem apply(Product product, ReceiptItem item) {
        return new ReceiptItem(product.descripcion(), generic(normalize(product.marca())) ? item.marca() : product.marca(),
                item.lugarDeCompra(), product.categoria().isBlank() ? item.categoria() : product.categoria(),
                item.cantidad(), item.precioUnitario(), item.fecha(), "HISTORY", item.firma());
    }

    private static String suggestion(Product product, ReceiptItem item) {
        return "Sugerencia del historial para «" + item.descripcion() + "»: " + product.descripcion()
                + (product.marca().isBlank() ? "" : " / " + product.marca())
                + (product.categoria().isBlank() ? "" : " / " + product.categoria()) + ". Confirmá los datos en la tabla.";
    }

    private static List<Product> unique(List<Product> products) {
        Map<String, Product> result = new LinkedHashMap<>();
        for (Product p : products) result.putIfAbsent(normalize(p.descripcion()) + "|" + normalize(p.marca())
                + "|" + normalize(p.categoria()), p);
        return List.copyOf(result.values());
    }

    private static Set<String> names(Product product) {
        String description = ProductSimilarity.key(product.descripcion()), brand = normalize(product.marca());
        if (generic(brand)) return Set.of(description);
        // Some historical descriptions already include the brand; do not require it twice.
        if ((" " + description + " ").contains(" " + brand + " ")) return Set.of(description);
        if (ProductSimilarity.descriptive(description)) return Set.of(description, brand + " " + description, description + " " + brand);
        return Set.of(brand + " " + description, description + " " + brand);
    }

    static boolean generic(String brand) { return brand.isBlank() || brand.equals("generico") || brand.equals("sin marca"); }
    private static boolean unknownStore(String store) { return store.isBlank() || store.equals("compra sin identificar"); }
    static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").replaceAll("\\s+", " ").trim();
    }
}
