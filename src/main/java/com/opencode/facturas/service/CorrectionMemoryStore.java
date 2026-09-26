package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

class CorrectionMemoryStore {

    private final ObjectMapper objectMapper;
    private final Path memoryPath;

    CorrectionMemoryStore(ObjectMapper objectMapper, Path memoryPath) {
        this.objectMapper = objectMapper;
        this.memoryPath = memoryPath;
    }

    List<CorrectionMemory.Entry> load() {
        MemoryData data = AtomicJsonFile.loadOrRecover(
                objectMapper,
                memoryPath,
                objectMapper.constructType(MemoryData.class),
                new MemoryData(List.of())
        );
        return data == null || data.entries() == null ? List.of() : data.entries();
    }

    void save(List<CorrectionMemory.Entry> entries) {
        try {
            AtomicJsonFile.save(objectMapper, memoryPath, new MemoryData(entries));
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo guardar " + memoryPath, ex);
        }
    }

    private record MemoryData(List<CorrectionMemory.Entry> entries) {
    }
}
