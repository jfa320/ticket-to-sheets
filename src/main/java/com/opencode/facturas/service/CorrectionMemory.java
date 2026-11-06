package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Comparator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import com.opencode.facturas.model.CorrectionManagement.*;

@Component
public class CorrectionMemory {

    private final CorrectionMemoryStore store;
    private final List<Entry> entries = new ArrayList<>();
    synchronized List<Entry> snapshot() { return List.copyOf(entries); }
    synchronized void applySnapshot(List<Entry> values) { entries.clear(); entries.addAll(values); }
    Path path() { return store.path(); }

    @Autowired
    public CorrectionMemory(ObjectMapper objectMapper) {
        this(objectMapper, Path.of("data", "corrections.json"));
    }

    public CorrectionMemory(ObjectMapper objectMapper, Path memoryPath) {
        this(new CorrectionMemoryStore(objectMapper, memoryPath));
    }

    CorrectionMemory(CorrectionMemoryStore store) {
        this.store = store;
        entries.addAll(store.load());
    }

    public synchronized Page list(String query, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 100 || (query != null && query.length() > 300)) {
            throw new IllegalArgumentException("Usá una búsqueda de hasta 300 caracteres, offset positivo y límite entre 1 y 100.");
        }
        String normalized = CorrectionMemoryRules.normalize(query);
        var matches = entries.stream().filter(entry -> normalized.isBlank() || CorrectionMemoryRules.normalize(
                entry.store() + " " + entry.firma() + " " + entry.descripcion() + " " + entry.marca() + " " + entry.categoria())
                .contains(normalized)).sorted(Comparator.comparing(Entry::store, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Entry::firma)).toList();
        return new Page(matches.stream().skip(offset).limit(limit).map(this::managed).toList(), matches.size(), offset, limit);
    }

    public synchronized ManagedCorrection replace(Edit edit) {
        int index = managementIndex(edit.store(), edit.firma(), edit.version());
        String description = managementText(edit.descripcion(), false);
        String brand = managementText(edit.marca(), false);
        String category = managementText(edit.categoria(), false);
        if (description.isBlank() && brand.isBlank() && category.isBlank())
            throw new IllegalArgumentException("Completá al menos una corrección de descripción, marca o categoría.");
        if (CorrectionMemoryRules.normalize(brand).equals("generico")) brand = "Genérico";
        Entry previous = entries.get(index);
        Entry updated = new Entry(previous.store(), previous.firma(), description, brand,
                category, previous.veces() + 1, LocalDate.now().toString());
        List<Entry> next = new ArrayList<>(entries);
        next.set(index, updated);
        store.save(next);
        entries.clear(); entries.addAll(next);
        return managed(updated);
    }

    public synchronized void remove(String storeName, String firma, String version) {
        int index = managementIndex(storeName, firma, version);
        List<Entry> next = new ArrayList<>(entries);
        next.remove(index);
        store.save(next);
        entries.clear(); entries.addAll(next);
    }

    private int managementIndex(String storeName, String firma, String expectedVersion) {
        String storeKey = CorrectionMemoryRules.normalize(managementText(storeName, true));
        String signature = CorrectionMemoryRules.normalize(managementText(firma, true));
        if (expectedVersion == null || !expectedVersion.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("La corrección debe incluir su versión actual.");
        }
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            if (CorrectionMemoryRules.normalize(entry.store()).equals(storeKey)
                    && CorrectionMemoryRules.normalize(entry.firma()).equals(signature)) {
                if (!version(entry).equals(expectedVersion)) throw new CorrectionConflictException();
                return index;
            }
        }
        throw new CorrectionConflictException();
    }

    private String managementText(String value, boolean required) {
        String clean = CorrectionMemoryRules.clean(value);
        if (clean.length() > 1000 || (required && clean.isBlank()) || clean.matches(".*[\\x00-\\x1f].*")) {
            throw new IllegalArgumentException("Completá los campos obligatorios con textos de hasta 1000 caracteres.");
        }
        return clean;
    }

    private ManagedCorrection managed(Entry entry) {
        return new ManagedCorrection(entry.store(), entry.firma(), entry.descripcion(), entry.marca(), entry.categoria(),
                entry.veces(), entry.ultimaVez(), version(entry));
    }

    private String version(Entry entry) {
        try {
            String content = String.join("\u0000", entry.store(), entry.firma(), entry.descripcion(), entry.marca(),
                    entry.categoria(), Integer.toString(entry.veces()), entry.ultimaVez());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    public synchronized Entry find(String storeName, String firma) {
        return CorrectionMemoryRules.find(entries, storeName, firma);
    }

    public synchronized void upsert(String storeName, String firma, String descripcion, String marca,
                                    String categoria, String marcaOriginal) {
        upsert(storeName, firma, descripcion, CorrectionMemoryRules.explicitBrand(marca, marcaOriginal), categoria);
    }

    public synchronized void upsert(String storeName, String firma, String descripcion, String marca, String categoria) {
        String storeClean = CorrectionMemoryRules.clean(storeName);
        String firmaKey = CorrectionMemoryRules.normalize(firma);
        if (storeClean.isBlank() || firmaKey.isBlank()) {
            return;
        }

        String today = LocalDate.now().toString();
        Optional<Entry> existing = entries.stream()
                .filter(entry -> CorrectionMemoryRules.normalize(entry.store()).equals(CorrectionMemoryRules.normalize(storeClean))
                        && CorrectionMemoryRules.normalize(entry.firma()).equals(firmaKey))
                .findFirst();

        List<Entry> next = new ArrayList<>(entries);
        if (existing.isPresent()) {
            Entry current = existing.get();
            Entry updated = new Entry(
                    storeClean,
                    firmaKey,
                    CorrectionMemoryRules.fallback(descripcion, current.descripcion()),
                    CorrectionMemoryRules.fallbackMemoryBrand(marca, current.marca()),
                    CorrectionMemoryRules.fallback(categoria, current.categoria()),
                    current.veces() + 1,
                    today
            );
            next.set(entries.indexOf(current), updated);
        } else {
            next.add(new Entry(
                    storeClean,
                    firmaKey,
                    CorrectionMemoryRules.clean(descripcion),
                    CorrectionMemoryRules.cleanMemoryBrand(marca),
                    CorrectionMemoryRules.clean(categoria),
                    1,
                    today
            ));
        }
        store.save(next);
        entries.clear();
        entries.addAll(next);
    }

    public record Entry(String store, String firma, String descripcion, String marca, String categoria, int veces, String ultimaVez) {
    }
}
