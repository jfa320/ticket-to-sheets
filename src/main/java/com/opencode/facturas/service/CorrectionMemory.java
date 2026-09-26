package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class CorrectionMemory {

    private final CorrectionMemoryStore store;
    private final List<Entry> entries = new ArrayList<>();

    @Autowired
    public CorrectionMemory(ObjectMapper objectMapper) {
        this(objectMapper, Path.of("data", "corrections.json"));
    }

    public CorrectionMemory(ObjectMapper objectMapper, Path memoryPath) {
        this.store = new CorrectionMemoryStore(objectMapper, memoryPath);
        entries.addAll(store.load());
    }

    public synchronized Entry find(String storeName, String firma) {
        return CorrectionMemoryRules.find(entries, storeName, firma);
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
            entries.set(entries.indexOf(current), updated);
        } else {
            entries.add(new Entry(
                    storeClean,
                    firmaKey,
                    CorrectionMemoryRules.clean(descripcion),
                    CorrectionMemoryRules.cleanMemoryBrand(marca),
                    CorrectionMemoryRules.clean(categoria),
                    1,
                    today
            ));
        }
        store.save(entries);
    }

    public record Entry(String store, String firma, String descripcion, String marca, String categoria, int veces, String ultimaVez) {
    }
}
