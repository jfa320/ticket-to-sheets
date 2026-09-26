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
        for (CorrectionMemory.Entry entry : entries) {
            if (!normalize(entry.store()).equals(storeKey)) {
                continue;
            }
            String compactEntry = normalize(entry.firma()).replace(" ", "");
            if (Math.min(compactFirma.length(), compactEntry.length()) < 4) {
                continue;
            }
            if (compactFirma.contains(compactEntry) || compactEntry.contains(compactFirma)) {
                if (compactEntry.length() > longestMatch) {
                    longestMatch = compactEntry.length();
                    compactMatch = entry;
                }
            }
        }
        if (compactMatch != null) {
            return compactMatch;
        }

        Set<String> firmaTokens = tokens(firmaKey);
        if (firmaTokens.size() < 2) {
            return null;
        }
        CorrectionMemory.Entry tokenMatch = null;
        double bestOverlap = 0.0;
        for (CorrectionMemory.Entry entry : entries) {
            if (!normalize(entry.store()).equals(storeKey)) {
                continue;
            }
            Set<String> entryTokens = tokens(normalize(entry.firma()));
            if (entryTokens.size() < 2) {
                continue;
            }
            long intersection = firmaTokens.stream().filter(entryTokens::contains).count();
            long union = firmaTokens.size() + entryTokens.size() - intersection;
            double overlap = union == 0 ? 0 : (double) intersection / union;
            if (overlap >= 0.6 && overlap > bestOverlap) {
                bestOverlap = overlap;
                tokenMatch = entry;
            }
        }
        return tokenMatch;
    }

    static String fallback(String value, String current) {
        String cleaned = clean(value);
        return cleaned.isBlank() ? clean(current) : cleaned;
    }

    static String fallbackMemoryBrand(String value, String current) {
        String cleaned = cleanMemoryBrand(value);
        return cleaned.isBlank() ? cleanMemoryBrand(current) : cleaned;
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
