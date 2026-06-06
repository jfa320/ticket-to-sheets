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
    private static final Set<String> IGNORED_BRANDS = Set.of("almacen", "generico", "sin marca", "supermercado", "zou wenguo");

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

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public record BrandMatch(String brand, String normalizedAlias) {
    }
}
