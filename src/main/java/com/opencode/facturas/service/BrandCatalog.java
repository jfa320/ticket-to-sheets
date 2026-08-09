package com.opencode.facturas.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.text.Normalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Component
public class BrandCatalog {

    private final ObjectMapper objectMapper;
    private final Path catalogPath;
    private final Set<String> brands = new LinkedHashSet<>();
    private static final Set<String> IGNORED_BRANDS = Set.of(
            "almacen", "generico", "sin marca", "supermercado", "zou wenguo", "onsumidorley", "consumidor",
            "bolsa", "bahia", "levadura", "s p coctel", "su a"
    );

    @Autowired
    public BrandCatalog(ObjectMapper objectMapper) {
        this(objectMapper, Path.of("data", "brands.json"));
    }

    public BrandCatalog(ObjectMapper objectMapper, Path catalogPath) {
        this.objectMapper = objectMapper;
        this.catalogPath = catalogPath;
        load();
    }

    public synchronized Optional<BrandMatch> findIn(String description) {
        String normalizedDescription = normalize(description);
        String compactDescription = compact(normalizedDescription);

        return brands.stream()
                .flatMap(brand -> aliasesFor(brand).stream()
                        .map(alias -> new BrandMatch(brand, normalize(alias))))
                .filter(match -> startsWithAlias(normalizedDescription, compactDescription, match.normalizedAlias()))
                .max(Comparator.comparingInt(match -> compact(match.normalizedAlias()).length()));
    }

    public synchronized Optional<BrandMatch> findAnywhereIn(String description) {
        String normalizedDescription = normalize(description);
        String compactDescription = compact(normalizedDescription);

        return brands.stream()
                .flatMap(brand -> aliasesFor(brand).stream()
                        .map(alias -> new BrandMatch(brand, normalize(alias))))
                .filter(match -> normalizedDescription.contains(match.normalizedAlias())
                        || compactDescription.contains(compact(match.normalizedAlias())))
                .max(Comparator.comparingInt(match -> compact(match.normalizedAlias()).length()));
    }

    public synchronized Optional<FuzzyBrandMatch> findFuzzyAtStart(String description) {
        Optional<FuzzyBrandMatch> best = findBestFuzzyAtStart(description);
        if (best.isEmpty()) {
            return Optional.empty();
        }
        String firstToken = comparisonKey(normalize(description).split(" ")[0]);
        String alias = comparisonKey(best.get().normalizedAlias());
        int requiredPrefix = Math.max(2, (int) Math.ceil(Math.max(firstToken.length(), alias.length()) * 0.2));
        return commonPrefixLength(firstToken, alias) >= requiredPrefix ? best : Optional.empty();
    }

    public synchronized Optional<FuzzyBrandMatch> findBestFuzzyAtStart(String description) {
        String firstToken = comparisonKey(normalize(description).split(" ")[0]);
        if (firstToken.length() < 4) {
            return Optional.empty();
        }

        return brands.stream()
                .flatMap(brand -> aliasesFor(brand).stream()
                        .map(alias -> new FuzzyBrandMatch(brand, normalize(alias).split(" ")[0])))
                .filter(match -> match.normalizedAlias().length() >= 4)
                .map(match -> new FuzzyBrandMatch(
                        match.brand(),
                        match.normalizedAlias(),
                        similarity(firstToken, comparisonKey(match.normalizedAlias()))))
                .max(Comparator.comparingDouble(FuzzyBrandMatch::percentage)
                        .thenComparingInt(match -> match.normalizedAlias().length()));
    }

    public synchronized void remember(String brand) {
        String cleaned = cleanBrand(brand);
        String normalized = normalize(cleaned);
        if (cleaned.isBlank()
                || IGNORED_BRANDS.contains(normalized)
                || normalized.contains("seshia")
                || cleaned.matches(".*\\d.*")
                || cleaned.chars().filter(Character::isLetter).count() < 3) {
            return;
        }
        boolean exists = brands.stream().anyMatch(existing -> normalize(existing).equals(normalized));
        if (!exists) {
            brands.add(cleaned);
            save();
        }
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

    private void load() {
        try {
            if (!Files.exists(catalogPath)) {
                Files.createDirectories(catalogPath.getParent());
                save();
                return;
            }
            List<String> loaded = objectMapper.readValue(catalogPath.toFile(), new TypeReference<>() {});
            loaded.stream()
                    .map(this::cleanBrand)
                    .filter(value -> !value.isBlank() && !value.equals("-"))
                    .forEach(brands::add);
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo leer data/brands.json", ex);
        }
    }

    private void save() {
        try {
            Files.createDirectories(catalogPath.getParent());
            List<String> sorted = brands.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(catalogPath.toFile(), sorted);
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo guardar data/brands.json", ex);
        }
    }

    private String cleanBrand(String brand) {
        return brand == null ? "" : brand.trim().replaceAll("\\s+", " ");
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
                int substitution = previous[column - 1] + (left.charAt(row - 1) == right.charAt(column - 1) ? 0 : 1);
                current[column] = Math.min(Math.min(previous[column] + 1, current[column - 1] + 1), substitution);
            }
            previous = current;
        }
        return previous[right.length()];
    }

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public record BrandMatch(String brand, String normalizedAlias) {
    }

    public record FuzzyBrandMatch(String brand, String normalizedAlias, double percentage) {
        public FuzzyBrandMatch(String brand, String normalizedAlias) {
            this(brand, normalizedAlias, 0.0);
        }
    }
}
