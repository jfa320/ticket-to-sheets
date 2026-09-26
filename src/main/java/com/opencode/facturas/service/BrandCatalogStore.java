package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

class BrandCatalogStore {

    private final ObjectMapper objectMapper;
    private final Path catalogPath;

    BrandCatalogStore(ObjectMapper objectMapper, Path catalogPath) {
        this.objectMapper = objectMapper;
        this.catalogPath = catalogPath;
    }

    List<String> load() {
        List<String> brands = AtomicJsonFile.loadOrRecover(
                objectMapper,
                catalogPath,
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class),
                List.of()
        );
        if (!Files.exists(catalogPath)) {
            save(List.of());
        }
        return brands == null ? List.of() : brands;
    }

    void save(Collection<String> brands) {
        try {
            AtomicJsonFile.save(objectMapper, catalogPath, brands.stream()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList());
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo guardar " + catalogPath, ex);
        }
    }
}
