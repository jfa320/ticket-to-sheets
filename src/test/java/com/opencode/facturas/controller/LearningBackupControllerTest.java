package com.opencode.facturas.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.facturas.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Path;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LearningBackupControllerTest {
    @TempDir Path directory;
    MockMvc mvc;
    @BeforeEach void setup() {
        var mapper = new ObjectMapper();
        var service = new LearningBackupService(mapper, new CorrectionMemory(mapper, directory.resolve("corrections.json")),
                new BrandCatalog(mapper, directory.resolve("brands.json")));
        mvc = MockMvcBuilders.standaloneSetup(new LearningBackupController(service, mapper), new CategoryController(CategoryService.defaults()))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }
    @Test void exportAndSuggestionsAreLocalStructuredDocuments() throws Exception {
        mvc.perform(get("/api/learning/limits")).andExpect(status().isOk())
                .andExpect(jsonPath("$.maxFileBytes").value(LearningBackupService.MAX_BACKUP_BYTES));
        mvc.perform(get("/api/learning/backup")).andExpect(status().isOk()).andExpect(jsonPath("$.format").value("ProyectoFacturas-learning"))
                .andExpect(jsonPath("$.corrections").isArray()).andExpect(jsonPath("$.brands").isArray())
                .andExpect(jsonPath("$.credentials").doesNotExist());
        mvc.perform(get("/api/categories")).andExpect(status().isOk()).andExpect(jsonPath("$.categories").isArray());
    }
    @Test void malformedOversizedAndFutureBackupRequestsDoNotRestore() throws Exception {
        mvc.perform(post("/api/learning/preview").contentType("application/json").content("{invalid"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/learning/preview").contentType("application/json").content(" ".repeat(LearningBackupService.MAX_BACKUP_BYTES+4097)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/learning/restore").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
    }
}
