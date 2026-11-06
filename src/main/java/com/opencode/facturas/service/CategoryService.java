package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

/** Reglas locales opcionales; la memoria y el historial conservan su prioridad. */
@Service
public class CategoryService {
    private final ObjectMapper mapper;
    private final Path path;
    private final Config defaults;
    private Config cached;
    private String fileVersion = "";
    private record Rule(String store, String category, List<String> keywords) { }
    private record Config(String fallback, List<String> suggestions, Map<String, String> stores, List<Rule> rules) { }

    @Autowired public CategoryService(ObjectMapper mapper, @Value("${app.categories.path:data/categories.json}") String path) {
        this(mapper, Path.of(path));
    }
    public CategoryService(ObjectMapper mapper, Path path) {
        this.mapper = mapper; this.path = path;
        try (var stream = CategoryService.class.getResourceAsStream("/category-defaults.json")) {
            defaults = validate(mapper.readTree(stream)); cached = defaults;
        } catch (IOException ex) { throw new IllegalStateException("No se pudo leer la configuración base de categorías.", ex); }
    }
    public static CategoryService defaults() { return new CategoryService(new ObjectMapper(), (Path) null); }

    public String categoryFor(String store, String description) {
        var config = current();
        String storeKey = normalize(store), text = " " + normalize(description) + " ";
        for (var rule : config.rules()) if ((rule.store().isEmpty() || rule.store().equals(storeKey))
                && rule.keywords().stream().anyMatch(keyword -> text.contains(" " + keyword + " "))) return rule.category();
        return config.stores().getOrDefault(storeKey, config.fallback());
    }
    public List<String> suggestions() { return current().suggestions(); }

    private synchronized Config current() {
        if (path == null || !Files.exists(path)) return defaults;
        try {
            if (Files.size(path) > 262_144) throw new IllegalArgumentException("El archivo de categorías supera 256 KB.");
            String version = Files.getLastModifiedTime(path).toString() + ":" + Files.size(path);
            if (!version.equals(fileVersion)) { var next = validate(mapper.readTree(path.toFile())); cached = next; fileVersion = version; }
            return cached;
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalStateException("Revisá la configuración local de categorías: " + path + ".", ex);
        }
    }
    private Config validate(JsonNode root) {
        fields(root, Set.of("schemaVersion", "fallback", "categories", "stores", "productRules"));
        if (!root.path("schemaVersion").isIntegralNumber() || root.path("schemaVersion").asInt() != 1)
            throw new IllegalArgumentException("Versión de categorías inválida.");
        String fallback = text(root.path("fallback"));
        var suggestions = new LinkedHashSet<String>(); suggestions.add(fallback);
        var categories = root.path("categories");
        if (!categories.isArray() || categories.size() > 100) throw new IllegalArgumentException("Categorías inválidas.");
        categories.forEach(value -> suggestions.add(text(value)));
        var stores = new LinkedHashMap<String, String>();
        if (!root.path("stores").isObject() || root.path("stores").size() > 200) throw new IllegalArgumentException("Comercios inválidos.");
        root.path("stores").fields().forEachRemaining(entry -> {
            String key = normalize(entry.getKey()), category = text(entry.getValue());
            if (key.isBlank() || key.length() > 200 || stores.putIfAbsent(key, category) != null)
                throw new IllegalArgumentException("Comercio vacío o repetido en categorías.");
            suggestions.add(category);
        });
        var rules = new ArrayList<Rule>();
        if (!root.path("productRules").isArray() || root.path("productRules").size() > 200)
            throw new IllegalArgumentException("Reglas de categoría inválidas.");
        for (var rule : root.path("productRules")) {
            fields(rule, Set.of("store", "category", "keywords"));
            String category = text(rule.path("category"));
            String store = rule.has("store") ? normalize(text(rule.path("store"))) : "";
            if (rule.has("store") && store.isBlank()) throw new IllegalArgumentException("Comercio inválido en la regla de categoría.");
            if (!rule.path("keywords").isArray() || rule.path("keywords").isEmpty() || rule.path("keywords").size() > 30)
                throw new IllegalArgumentException("Palabras de categoría inválidas.");
            var keywords = new ArrayList<String>();
            rule.path("keywords").forEach(value -> {
                String keyword = normalize(text(value));
                if (keyword.isBlank()) throw new IllegalArgumentException("Palabra vacía en la regla de categoría.");
                keywords.add(keyword);
            });
            rules.add(new Rule(store, category, List.copyOf(keywords))); suggestions.add(category);
        }
        return new Config(fallback, List.copyOf(suggestions), Map.copyOf(stores), List.copyOf(rules));
    }
    private void fields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Se esperaba un objeto de categorías.");
        node.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) throw new IllegalArgumentException("Campo de categoría desconocido."); });
    }
    private String text(JsonNode value) {
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 200 || value.asText().matches(".*[\\x00-\\x1f].*"))
            throw new IllegalArgumentException("Texto de categoría inválido.");
        return value.asText().trim();
    }
    private String normalize(String value) { return BrandCatalogMatcher.normalize(value); }
}
