package com.opencode.facturas.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.opencode.facturas.service.LearningBackupService;
import com.opencode.facturas.model.LearningBackup.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;

@RestController
@RequestMapping("/api/learning")
public class LearningBackupController {
    private final LearningBackupService learning;
    private final ObjectMapper mapper;
    public LearningBackupController(LearningBackupService learning, ObjectMapper mapper) { this.learning = learning; this.mapper = mapper; }
    @GetMapping("/backup") public Document export() { return learning.export(); }
    @GetMapping("/limits") public java.util.Map<String, Integer> limits() {
        return java.util.Map.of("maxFileBytes", LearningBackupService.MAX_BACKUP_BYTES, "maxEntries", LearningBackupService.MAX_ENTRIES);
    }
    @PostMapping("/preview") public Preview preview(HttpServletRequest request) { return learning.preview(read(request)); }
    @PostMapping("/restore") public Result restore(HttpServletRequest request) { return learning.restore(read(request)); }
    private JsonNode read(HttpServletRequest request) {
        try {
            int limit = LearningBackupService.MAX_BACKUP_BYTES + 4096;
            byte[] bytes = request.getInputStream().readNBytes(limit + 1);
            if (bytes.length > limit) throw new IllegalArgumentException("El respaldo supera el límite de 16 MB.");
            return mapper.readTree(bytes);
        } catch (IOException error) { throw new IllegalArgumentException("No se pudo leer el respaldo JSON."); }
    }
}
