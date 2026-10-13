package com.opencode.facturas.service;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

final class CorrectionMemoryRules {

    private CorrectionMemoryRules() {
    }

    static CorrectionMemory.Entry find(List<CorrectionMemory.Entry> entries, String store, String firma) {
        String storeKey = normalize(store);
        String firmaKey = normalize(firma);
        if (storeKey.isBlank() || firmaKey.isBlank()) {
            return null;
        }

        Optional<CorrectionMemory.Entry> exact = entries.stream()
                .filter(entry -> normalize(entry.store()).equals(storeKey)
                        && normalize(entry.firma()).equals(firmaKey))
                .findFirst();
        if (exact.isPresent()) {
            return exact.get();
        }

        String compactFirma = firmaKey.replace(" ", "");
        CorrectionMemory.Entry compactMatch = null;
        int longestMatch = 0;
        boolean compactConflict = false;
        for (CorrectionMemory.Entry entry : entries) {
            if (!normalize(entry.store()).equals(storeKey)) {
                continue;
            }
            String compactEntry = normalize(entry.firma()).replace(" ", "");
            if (Math.min(compactFirma.length(), compactEntry.length()) < 4) {
                continue;
            }
            if (!compactFirma.equals(compactEntry) && (ProductSimilarity.conflicts(firmaKey, entry.firma())
                    || Math.min(compactFirma.length(), compactEntry.length()) < Math.max(compactFirma.length(), compactEntry.length()) * .8)) continue;
            if (compactFirma.contains(compactEntry) || compactEntry.contains(compactFirma)) {
                if (compactEntry.length() > longestMatch) {
                    longestMatch = compactEntry.length();
                    compactMatch = entry;
                    compactConflict = false;
                } else if (compactEntry.length() == longestMatch && !sameProduct(compactMatch, entry)) {
                    compactConflict = true;
                }
            }
        }
        if (compactMatch != null && !compactConflict) {
            return compactMatch;
        }

        Set<String> firmaTokens = tokens(firmaKey);
        if (firmaTokens.size() < 2) {
            return null;
        }
        CorrectionMemory.Entry tokenMatch = null;
        double bestOverlap = 0.0;
        double secondOverlap = 0.0;
        for (CorrectionMemory.Entry entry : entries) {
            if (!normalize(entry.store()).equals(storeKey)) {
                continue;
            }
            if (ProductSimilarity.conflicts(firmaKey, entry.firma())) continue;
            Set<String> entryTokens = tokens(normalize(entry.firma()));
            if (entryTokens.size() < 2) {
                continue;
            }
            long intersection = firmaTokens.stream().filter(entryTokens::contains).count();
            long union = firmaTokens.size() + entryTokens.size() - intersection;
            double overlap = union == 0 ? 0 : (double) intersection / union;
            if (overlap >= 0.6 && overlap > bestOverlap) {
                if (tokenMatch == null || !sameProduct(tokenMatch, entry)) secondOverlap = bestOverlap;
                bestOverlap = overlap;
                tokenMatch = entry;
            } else if (tokenMatch != null && !sameProduct(tokenMatch, entry)) {
                secondOverlap = Math.max(secondOverlap, overlap);
            }
        }
        if (tokenMatch != null && bestOverlap - secondOverlap >= .06) return tokenMatch;

        CorrectionMemory.Entry best = null;
        double bestScore = 0, secondScore = 0;
        for (CorrectionMemory.Entry entry : entries) {
            if (!normalize(entry.store()).equals(storeKey)) continue;
            double score = ProductSimilarity.score(firmaKey, entry.firma());
            if (!entry.descripcion().isBlank()) {
                score = Math.max(score, ProductSimilarity.score(firmaKey, entry.descripcion() + " " + entry.marca()));
                if (ProductSimilarity.descriptive(entry.descripcion()))
                    score = Math.max(score, ProductSimilarity.score(firmaKey, entry.descripcion()));
            }
            if (score > bestScore) {
                if (best == null || !sameProduct(best, entry)) secondScore = bestScore;
                bestScore = score; best = entry;
            }
            else if (best == null || !sameProduct(best, entry)) secondScore = Math.max(secondScore, score);
        }
        return bestScore >= .88 && bestScore - secondScore >= .06 ? best : null;
    }

    private static boolean sameProduct(CorrectionMemory.Entry a, CorrectionMemory.Entry b) {
        return normalize(a.descripcion()).equals(normalize(b.descripcion()))
                && normalize(a.marca()).equals(normalize(b.marca()))
                && normalize(a.categoria()).equals(normalize(b.categoria()));
    }

    static String fallback(String value, String current) {
        String cleaned = clean(value);
        return cleaned.isBlank() ? clean(current) : cleaned;
    }

    static String fallbackMemoryBrand(String value, String current) {
        String cleaned = cleanMemoryBrand(value);
        return cleaned.isBlank() ? cleanMemoryBrand(current) : cleaned;
    }

    static String explicitBrand(String value, String original) {
        return normalize(value).equals("generico") && !clean(original).isBlank() && !normalize(original).equals("generico")
                ? "Sin marca" : value;
    }

    static String cleanMemoryBrand(String value) {
        String cleaned = clean(value);
        return normalize(cleaned).equals("generico") ? "" : cleaned;
    }

    static String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    static String normalize(String value) {
        return Normalizer.normalize(clean(value).toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static Set<String> tokens(String normalized) {
        Set<String> tokenSet = new HashSet<>();
        for (String token : normalized.split(" ")) {
            if (!token.isBlank()) {
                tokenSet.add(token);
            }
        }
        return tokenSet;
    }
}
