package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Component
public class CorrectionMemory {

    private final ObjectMapper objectMapper;
    private final Path memoryPath;
    private final List<Entry> entries = new ArrayList<>();

    @Autowired
    public CorrectionMemory(ObjectMapper objectMapper) {
        this(objectMapper, Path.of("data", "corrections.json"));
    }

    public CorrectionMemory(ObjectMapper objectMapper, Path memoryPath) {
        this.objectMapper = objectMapper;
        this.memoryPath = memoryPath;
        load();
    }

    public synchronized Entry find(String store, String firma) {
        String storeKey = normalize(store);
        String firmaKey = normalize(firma);
        if (storeKey.isBlank() || firmaKey.isBlank()) {
            return null;
        }

        Optional<Entry> exact = entries.stream()
                .filter(entry -> normalize(entry.store()).equals(storeKey)
                        && normalize(entry.firma()).equals(firmaKey))
                .findFirst();
        if (exact.isPresent()) {
            return exact.get();
        }

        String compactFirma = firmaKey.replace(" ", "");
        Entry compactMatch = null;
        int longestMatch = 0;
        for (Entry entry : entries) {
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
        Entry tokenMatch = null;
        double bestOverlap = 0.0;
        for (Entry entry : entries) {
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

    public synchronized void upsert(String store, String firma, String descripcion, String marca, String categoria) {
        String storeClean = clean(store);
        String firmaKey = normalize(firma);
        if (storeClean.isBlank() || firmaKey.isBlank()) {
            return;
        }

        String today = LocalDate.now().toString();
        Optional<Entry> existing = entries.stream()
                .filter(entry -> normalize(entry.store()).equals(normalize(storeClean))
                        && normalize(entry.firma()).equals(firmaKey))
                .findFirst();

        if (existing.isPresent()) {
            Entry current = existing.get();
            Entry updated = new Entry(
                    storeClean,
                    firmaKey,
                    fallback(descripcion, current.descripcion()),
                    fallbackMemoryBrand(marca, current.marca()),
                    fallback(categoria, current.categoria()),
                    current.veces() + 1,
                    today
            );
            entries.set(entries.indexOf(current), updated);
        } else {
            entries.add(new Entry(storeClean, firmaKey, clean(descripcion), cleanMemoryBrand(marca), clean(categoria), 1, today));
        }
        save();
    }

    private void load() {
        if (!Files.exists(memoryPath)) {
            return;
        }
        try {
            MemoryData data = objectMapper.readValue(memoryPath.toFile(), MemoryData.class);
            if (data != null && data.entries() != null) {
                entries.addAll(data.entries());
            }
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo leer " + memoryPath, ex);
        }
    }

    private void save() {
        try {
            if (memoryPath.getParent() != null) {
                Files.createDirectories(memoryPath.getParent());
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(memoryPath.toFile(), new MemoryData(entries));
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo guardar " + memoryPath, ex);
        }
    }

    private String fallback(String value, String current) {
        String cleaned = clean(value);
        return cleaned.isBlank() ? clean(current) : cleaned;
    }

    private String fallbackMemoryBrand(String value, String current) {
        String cleaned = cleanMemoryBrand(value);
        return cleaned.isBlank() ? cleanMemoryBrand(current) : cleaned;
    }

    private String cleanMemoryBrand(String value) {
        String cleaned = clean(value);
        return normalize(cleaned).equals("generico") ? "" : cleaned;
    }

    private String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private String normalize(String value) {
        return Normalizer.normalize(clean(value).toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private Set<String> tokens(String normalized) {
        Set<String> tokenSet = new HashSet<>();
        for (String token : normalized.split(" ")) {
            if (!token.isBlank()) {
                tokenSet.add(token);
            }
        }
        return tokenSet;
    }

    public record Entry(String store, String firma, String descripcion, String marca, String categoria, int veces, String ultimaVez) {
    }

    private record MemoryData(List<Entry> entries) {
    }
}
