package com.opencode.facturas.controller;

import com.opencode.facturas.service.CategoryService;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {
    private final CategoryService categories;
    public CategoryController(CategoryService categories) { this.categories = categories; }
    @GetMapping public Map<String, List<String>> suggestions() { return Map.of("categories", categories.suggestions()); }
}
