package com.opencode.facturas.service;

import java.util.*;
import java.util.stream.Collectors;

/** Compares product identity without using price, quantity bought or purchase date. */
final class ProductSimilarity {
    private static final Set<String> STOP = Set.of("de", "del", "la", "el", "las", "los", "y", "en", "para", "con", "x");
    private static final Set<String> VARIANTS = Set.of("light", "entera", "descremada", "vainilla", "chocolate",
            "sin", "azucar", "integral", "original", "diet", "sal", "lactosa", "cheddar", "frutilla", "limon");
    private static final Set<String> ABBREVIATED = Set.of("liquido", "lavarropas", "galletitas", "jabon",
            "detergente", "suavizante", "aromatizante", "shampoo", "acondicionador", "cremoso", "hamburguesas", "noquis");

    private ProductSimilarity() { }

    static String key(String text) {
        return HistoryProductMatcher.normalize(text)
                .replaceAll("([a-z])(?=\\d+(?:gr?|kg|ml|lt?|un)\\b)", "$1 ")
                .replaceAll("(\\d+)\\s*(?:grs?|gramos?)\\b", "$1g")
                .replaceAll("(\\d+)\\s*(?:lts?|litros?)\\b", "$1l")
                .replaceAll("(\\d+)\\s*(kg|g|ml|l)\\b", "$1$2")
                .replaceAll("\\b(?:und|unds|unid|unidades|unidad)\\b", "un");
    }

    static boolean descriptive(String text) {
        return words(key(text)).stream().filter(t -> t.length() >= 3 && t.chars().noneMatch(Character::isDigit)).count() >= 2;
    }

    static boolean compatible(String a, String b) {
        return variants(key(a)).equals(variants(key(b)));
    }

    static boolean conflicts(String a, String b) {
        a = key(a); b = key(b);
        Set<String> leftSizes = sizes(a), rightSizes = sizes(b);
        if (!leftSizes.isEmpty() && !rightSizes.isEmpty() && !leftSizes.equals(rightSizes)) return true;
        return !words(a).stream().filter(VARIANTS::contains).collect(Collectors.toSet())
                .equals(words(b).stream().filter(VARIANTS::contains).collect(Collectors.toSet()));
    }

    private static Set<String> sizes(String text) {
        return java.util.regex.Pattern.compile("\\d+(?:kg|g|ml|l)\\b").matcher(text).results()
                .map(java.util.regex.MatchResult::group).collect(Collectors.toSet());
    }

    static double score(String a, String b) {
        a = key(a); b = key(b);
        if (a.isBlank() || b.isBlank() || !compatible(a, b)) return 0;
        if (a.equals(b)) return 1;
        List<String> left = words(a), right = words(b);
        if (left.size() < 2 || right.size() < 2) return 0;
        double coverage = (coverage(left, right) + coverage(right, left)) / 2;
        return Math.max(coverage, editSimilarity(a, b));
    }

    private static List<String> words(String value) {
        return Arrays.stream(value.split(" ")).filter(t -> !t.isBlank() && !STOP.contains(t)).distinct().toList();
    }

    private static Set<String> variants(String name) {
        return Arrays.stream(name.split(" ")).filter(token -> token.chars().anyMatch(Character::isDigit)
                || VARIANTS.contains(token)).collect(Collectors.toSet());
    }

    private static double coverage(List<String> source, List<String> target) {
        return source.stream().mapToDouble(word -> target.stream().mapToDouble(other -> tokenScore(word, other))
                .max().orElse(0)).average().orElse(0);
    }

    private static double tokenScore(String a, String b) {
        if (a.equals(b)) return 1;
        if (a.chars().anyMatch(Character::isDigit) || b.chars().anyMatch(Character::isDigit)) return 0;
        String full = a.length() > b.length() ? a : b;
        if (ABBREVIATED.contains(full) && Math.min(a.length(), b.length()) >= 3
                && (a.startsWith(b) || b.startsWith(a))) return .94;
        double score = editSimilarity(a, b);
        return score >= .65 ? score : 0;
    }

    private static double editSimilarity(String a, String b) {
        if (a.length() > 200 || b.length() > 200) return 0;
        int[] previous = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] row = new int[b.length() + 1]; row[0] = i;
            for (int j = 1; j <= b.length(); j++) row[j] = Math.min(Math.min(row[j - 1] + 1, previous[j] + 1),
                    previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            previous = row;
        }
        return 1 - (double) previous[b.length()] / Math.max(a.length(), b.length());
    }
}
