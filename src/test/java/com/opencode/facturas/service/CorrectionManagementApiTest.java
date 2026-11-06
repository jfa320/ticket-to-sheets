package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.facturas.controller.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Path;
import java.util.Map;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CorrectionManagementApiTest {
    @TempDir Path directory;
    @Test void acceptsAMeaningfulPartialEditWithAnEmptyBrand() throws Exception {
        var mapper=new ObjectMapper();var memory=new CorrectionMemory(mapper,directory.resolve("memory.json"));
        memory.upsert("Local","leche","Leche","","");var entry=memory.list("",0,25).entries().get(0);
        var mvc=MockMvcBuilders.standaloneSetup(new CorrectionController(memory,mock(BrandCatalog.class)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(put("/api/corrections").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(new com.opencode.facturas.model.CorrectionManagement.Edit("Local","leche",entry.version(),"Leche corregida","",""))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.marca").value(""))
                .andExpect(jsonPath("$.descripcion").value("Leche corregida"));
    }
    @Test void listsEditsAndDeletesWithValidationAndConflictProtection() throws Exception {
        var mapper = new ObjectMapper();
        var memory = new CorrectionMemory(mapper,directory.resolve("memory.json"));
        memory.upsert("Local", "leche", "Leche", "Marca", "Lácteos");
        var brands = mock(BrandCatalog.class);
        var mvc = MockMvcBuilders.standaloneSetup(new CorrectionController(memory,brands))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        var response = mvc.perform(get("/api/corrections").param("q","lacteos")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1)).andReturn().getResponse().getContentAsString();
        var entry = mapper.readTree(response).path("entries").get(0);
        var key = Map.of("store","Local","firma","leche","version",entry.path("version").asText());
        mvc.perform(put("/api/corrections").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(key)))
                .andExpect(status().isBadRequest());
        var edit = new java.util.HashMap<String,String>(key);
        edit.put("descripcion","Leche corregida");edit.put("marca","Genérico");edit.put("categoria","");
        mvc.perform(put("/api/corrections").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(edit)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.marca").value("Genérico"));
        mvc.perform(delete("/api/corrections").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(key)))
                .andExpect(status().isConflict());
        key = Map.of("store","Local","firma","leche","version",memory.list("",0,25).entries().get(0).version());
        mvc.perform(delete("/api/corrections").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(key)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(true));
        mvc.perform(get("/api/corrections").param("limit","incorrecto")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/corrections").param("offset","-1")).andExpect(status().isBadRequest());
        assertThat(new CorrectionMemory(mapper,directory.resolve("memory.json")).list("",0,25).total()).isZero();
        verifyNoInteractions(brands);
    }
}
