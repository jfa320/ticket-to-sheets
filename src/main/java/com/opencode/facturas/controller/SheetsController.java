package com.opencode.facturas.controller;

import com.opencode.facturas.model.SheetsModels.*;
import com.opencode.facturas.service.SheetsService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sheets")
public class SheetsController {
    private final SheetsService sheets;

    public SheetsController(SheetsService sheets) {
        this.sheets = sheets;
    }

    @GetMapping("/config")
    public ConfigResponse config() {
        return sheets.getConfig();
    }

    @PutMapping(value = "/config", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ConfigResponse save(@RequestBody ConfigRequest request) {
        return sheets.saveConfig(request);
    }

    @PostMapping("/check")
    public CheckResponse check() {
        return sheets.check();
    }

    @PostMapping(value = "/append", consumes = MediaType.APPLICATION_JSON_VALUE)
    public AppendResponse append(@RequestBody AppendRequest request) {
        return sheets.append(request);
    }
}
