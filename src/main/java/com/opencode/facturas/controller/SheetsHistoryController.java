package com.opencode.facturas.controller;

import com.opencode.facturas.model.SheetsHistoryModels.HistoryStatus;
import com.opencode.facturas.model.SheetsModels.ConfigReference;
import com.opencode.facturas.service.SheetsHistoryService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sheets/history")
public class SheetsHistoryController {
    private final SheetsHistoryService history;
    public SheetsHistoryController(SheetsHistoryService history) { this.history = history; }
    @GetMapping public HistoryStatus status() { return history.status(); }
    @PostMapping("/sync") public HistoryStatus sync(@RequestBody ConfigReference config) { return history.sync(config); }
    @DeleteMapping public HistoryStatus disable() { return history.disable(); }
}
