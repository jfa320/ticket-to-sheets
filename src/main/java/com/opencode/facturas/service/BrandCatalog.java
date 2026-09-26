package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

@Component
public class BrandCatalog {

    private static final Set<String> IGNORED_BRANDS = Set.of(
            "almacen", "generico", "sin marca", "supermercado", "zou wenguo", "onsumidorley", "consumidor",
            "bolsa", "bahia", "levadura", "s p coctel", "su a"
    );

    private final BrandCatalogStore store;
    private final Set<String> brands = new LinkedHashSet<>();
    private final BrandCatalogMatcher matcher;

    @Autowired
    public BrandCatalog(ObjectMapper objectMapper) {
        this(objectMapper, Path.of("data", "brands.json"));
    }

    public BrandCatalog(ObjectMapper objectMapper, Path catalogPath) {
        this.store = new BrandCatalogStore(objectMapper, catalogPath);
        load();
        this.matcher = new BrandCatalogMatcher(brands);
    }

    public synchronized Optional<BrandMatch> findIn(String description) {
        return matcher.findIn(description);
    }

    public synchronized Optional<BrandMatch> findAnywhereIn(String description) {
        return matcher.findAnywhereIn(description);
    }

    public synchronized Optional<FuzzyBrandMatch> findFuzzyAtStart(String description) {
        return matcher.findFuzzyAtStart(description);
    }

    public synchronized Optional<FuzzyBrandMatch> findBestFuzzyAtStart(String description) {
        return matcher.findBestFuzzyAtStart(description);
    }

    public synchronized void remember(String brand) {
        String cleaned = cleanBrand(brand);
        String normalized = BrandCatalogMatcher.normalize(cleaned);
        if (cleaned.isBlank()
                || IGNORED_BRANDS.contains(normalized)
                || normalized.contains("seshia")
                || cleaned.matches(".*\\d.*")
                || cleaned.chars().filter(Character::isLetter).count() < 3) {
            return;
        }
        boolean exists = brands.stream().anyMatch(existing -> BrandCatalogMatcher.normalize(existing).equals(normalized));
        if (!exists) {
            brands.add(cleaned);
            store.save(brands);
        }
    }

    private void load() {
        store.load().stream()
                .map(this::cleanBrand)
                .filter(value -> !value.isBlank() && !value.equals("-"))
                .forEach(brands::add);
    }

    private String cleanBrand(String brand) {
        return brand == null ? "" : brand.trim().replaceAll("\\s+", " ");
    }

    public record BrandMatch(String brand, String normalizedAlias) {
    }

    public record FuzzyBrandMatch(String brand, String normalizedAlias, double percentage) {
        public FuzzyBrandMatch(String brand, String normalizedAlias) {
            this(brand, normalizedAlias, 0.0);
        }
    }
}
