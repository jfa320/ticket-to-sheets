package com.opencode.facturas.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

class BrandCatalogMatcher {

    private final Set<String> brands;

    BrandCatalogMatcher(Set<String> brands) {
        this.brands = brands;
    }

    Optional<BrandCatalog.BrandMatch> findIn(String description) {
        String normalizedDescription = normalize(description);
        String compactDescription = compact(normalizedDescription);

        return brands.stream()
                .flatMap(brand -> aliasesFor(brand).stream()
                        .map(alias -> new BrandCatalog.BrandMatch(brand, normalize(alias))))
                .filter(match -> startsWithAlias(normalizedDescription, compactDescription, match.normalizedAlias()))
                .max(Comparator.comparingInt(match -> compact(match.normalizedAlias()).length()));
    }

    Optional<BrandCatalog.BrandMatch> findAnywhereIn(String description) {
        String normalizedDescription = normalize(description);
        String compactDescription = compact(normalizedDescription);

        return brands.stream()
                .flatMap(brand -> aliasesFor(brand).stream()
                        .map(alias -> new BrandCatalog.BrandMatch(brand, normalize(alias))))
                .filter(match -> normalizedDescription.contains(match.normalizedAlias())
                        || compactDescription.contains(compact(match.normalizedAlias())))
                .max(Comparator.comparingInt(match -> compact(match.normalizedAlias()).length()));
    }

    Optional<BrandCatalog.FuzzyBrandMatch> findFuzzyAtStart(String description) {
        Optional<BrandCatalog.FuzzyBrandMatch> best = findBestFuzzyAtStart(description);
        if (best.isEmpty()) {
            return Optional.empty();
        }
        String firstToken = comparisonKey(normalize(description).split(" ")[0]);
        String alias = comparisonKey(best.get().normalizedAlias());
        int requiredPrefix = Math.max(2, (int) Math.ceil(Math.max(firstToken.length(), alias.length()) * 0.2));
        return commonPrefixLength(firstToken, alias) >= requiredPrefix ? best : Optional.empty();
    }

    Optional<BrandCatalog.FuzzyBrandMatch> findBestFuzzyAtStart(String description) {
        String firstToken = comparisonKey(normalize(description).split(" ")[0]);
        if (firstToken.length() < 4) {
            return Optional.empty();
        }

        return brands.stream()
                .flatMap(brand -> aliasesFor(brand).stream()
                        .map(alias -> new BrandCatalog.FuzzyBrandMatch(brand, normalize(alias).split(" ")[0])))
                .filter(match -> match.normalizedAlias().length() >= 4)
                .map(match -> new BrandCatalog.FuzzyBrandMatch(
                        match.brand(),
                        match.normalizedAlias(),
                        similarity(firstToken, comparisonKey(match.normalizedAlias()))))
                .max(Comparator.comparingDouble(BrandCatalog.FuzzyBrandMatch::percentage)
                        .thenComparingInt(match -> match.normalizedAlias().length()));
    }

    static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean startsWithAlias(String normalizedDescription, String compactDescription, String normalizedAlias) {
        String compactAlias = compact(normalizedAlias);
        return normalizedDescription.equals(normalizedAlias)
                || normalizedDescription.startsWith(normalizedAlias + " ")
                || compactDescription.equals(compactAlias)
                || compactDescription.startsWith(compactAlias);
    }

    private List<String> aliasesFor(String brand) {
        List<String> aliases = new ArrayList<>();
        aliases.add(brand);
        aliases.add(brand.replace(" del ", " de "));
        aliases.add(brand.replace(" de la ", " "));
        aliases.add(brand.replace("'", ""));

        if (normalize(brand).equals("la providencia")) {
            aliases.add("la providecia");
            aliases.add("la providedcia");
        }
        if (normalize(brand).equals("frutigram")) {
            aliases.add("frutigran");
        }
        if (normalize(brand).equals("union ganadera")) {
            aliases.add("union");
        }

        return aliases;
    }

    private String compact(String value) {
        return value.replace(" ", "");
    }

    private double similarity(String left, String right) {
        int maxLength = Math.max(left.length(), right.length());
        if (maxLength == 0) {
            return 100.0;
        }
        return (1.0 - (double) levenshtein(left, right) / maxLength) * 100.0;
    }

    private String comparisonKey(String value) {
        return value.replace('0', 'o')
                .replace('1', 'i')
                .replace('5', 's')
                .replace('8', 'b')
                .replace('3', 'e');
    }

    private int commonPrefixLength(String left, String right) {
        int length = Math.min(left.length(), right.length());
        int index = 0;
        while (index < length && left.charAt(index) == right.charAt(index)) {
            index++;
        }
        return index;
    }

    private int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int column = 0; column <= right.length(); column++) {
            previous[column] = column;
        }
        for (int row = 1; row <= left.length(); row++) {
            int[] current = new int[right.length() + 1];
            current[0] = row;
            for (int column = 1; column <= right.length(); column++) {
                int substitution = previous[column - 1]
                        + (left.charAt(row - 1) == right.charAt(column - 1) ? 0 : 1);
                current[column] = Math.min(Math.min(previous[column] + 1, current[column - 1] + 1), substitution);
            }
            previous = current;
        }
        return previous[right.length()];
    }
}
