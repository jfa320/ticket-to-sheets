package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.*;
import com.opencode.facturas.model.LearningBackup.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

@Service
public class LearningBackupService {
    public static final int MAX_BACKUP_BYTES = 16_000_000;
    public static final int MAX_ENTRIES = 10_000;
    private static final String FORMAT = "ProyectoFacturas-learning";
    private final ObjectMapper mapper;
    private final CorrectionMemory memory;
    private final BrandCatalog brands;
    private final LearningRestoreTransaction transaction;
    @Autowired public LearningBackupService(ObjectMapper mapper, CorrectionMemory memory, BrandCatalog brands) {
        this(mapper, memory, brands, new LearningRestoreTransaction());
    }
    LearningBackupService(ObjectMapper mapper, CorrectionMemory memory, BrandCatalog brands, LearningRestoreTransaction transaction) {
        this.mapper = mapper; this.memory = memory; this.brands = brands; this.transaction = transaction;
    }
    public Document export() {
        synchronized (memory) { synchronized (brands) {
            var current = snapshot();
            var uniqueBrands = new LinkedHashMap<String, String>();
            current.brands().forEach(brand -> uniqueBrands.putIfAbsent(normalize(brand), brand));
            var document = new Document(FORMAT, 1, current.corrections(), List.copyOf(uniqueBrands.values()));
            validate(mapper.valueToTree(document));
            try {
                if (mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(document).length > MAX_BACKUP_BYTES)
                    throw new IllegalArgumentException("El aprendizaje supera los 16 MB admitidos para un respaldo. No se generó un archivo incompleto.");
            } catch (java.io.IOException error) { throw new IllegalStateException("No se pudo preparar el respaldo."); }
            return document;
        } }
    }
    public Preview preview(JsonNode payload) {
        var incoming = validate(payload);
        synchronized (memory) { synchronized (brands) { return plan(snapshot(), incoming).preview(); } }
    }
    public Result restore(JsonNode payload) {
        fields(payload, Set.of("backup", "expectedVersion", "backupHash"));
        var incoming = validate(payload.path("backup"));
        String version = text(payload.path("expectedVersion"), true), hash = text(payload.path("backupHash"), true);
        synchronized (memory) { synchronized (brands) {
            var current = snapshot(); var plan = plan(current, incoming); var preview = plan.preview();
            if (!version.equals(preview.expectedVersion()) || !hash.equals(preview.backupHash())) throw new LearningConflictException();
            if (preview.correctionsToAdd() > 0 || preview.brandsToAdd() > 0) {
                transaction.commit(mapper, memory.path(), current.corrections(), plan.next().corrections(),
                        brands.path(), current.brands(), plan.next().brands());
                memory.applySnapshot(plan.next().corrections()); brands.applySnapshot(plan.next().brands());
            }
            return new Result(preview.correctionsToAdd(), preview.correctionsKept(), preview.conflicts(), preview.brandsToAdd());
        } }
    }
    private Document snapshot() { return new Document(FORMAT, 1, memory.snapshot().stream()
            .sorted(Comparator.comparing(this::key)).toList(), brands.snapshot()); }
    private record Plan(Document next, Preview preview) { }
    private Plan plan(Document current, Document incoming) {
        var entries = new LinkedHashMap<String, CorrectionMemory.Entry>(); current.corrections().forEach(entry -> entries.put(key(entry), entry));
        int added = 0, kept = 0, conflicts = 0;
        for (var entry : incoming.corrections()) {
            var existing = entries.putIfAbsent(key(entry), entry);
            if (existing == null) added++;
            else { kept++; if (!existing.descripcion().equals(entry.descripcion()) || !existing.marca().equals(entry.marca())
                    || !existing.categoria().equals(entry.categoria())) conflicts++; }
        }
        var catalog = new LinkedHashMap<String, String>(); current.brands().forEach(brand -> catalog.putIfAbsent(normalize(brand), brand));
        int before = catalog.size(); incoming.brands().forEach(brand -> catalog.putIfAbsent(normalize(brand), brand));
        var next = new Document(FORMAT, 1, List.copyOf(entries.values()), List.copyOf(catalog.values()));
        return new Plan(next, new Preview(hash(current), hash(incoming), added, kept, conflicts, catalog.size() - before,
                incoming.corrections().stream().limit(3).map(entry -> new Sample(entry.store(), entry.descripcion())).toList()));
    }
    private Document validate(JsonNode root) {
        fields(root, Set.of("format", "schemaVersion", "corrections", "brands"));
        if (!FORMAT.equals(root.path("format").asText()) || !root.path("schemaVersion").isIntegralNumber()
                || !root.path("schemaVersion").canConvertToInt() || root.path("schemaVersion").asInt() != 1)
            throw new IllegalArgumentException("El archivo no es un respaldo de aprendizaje compatible.");
        if (!root.path("corrections").isArray() || root.path("corrections").size() > MAX_ENTRIES
                || !root.path("brands").isArray() || root.path("brands").size() > MAX_ENTRIES)
            throw new IllegalArgumentException("El respaldo admite hasta 10.000 correcciones y marcas.");
        var entries = new ArrayList<CorrectionMemory.Entry>(); var keys = new HashSet<String>();
        for (var node : root.path("corrections")) {
            fields(node, Set.of("store", "firma", "descripcion", "marca", "categoria", "veces", "ultimaVez"));
            if (!node.path("veces").canConvertToInt() || !node.path("veces").isIntegralNumber() || node.path("veces").asInt() < 1)
                throw new IllegalArgumentException("Contador inválido en el respaldo.");
            String date = text(node.path("ultimaVez"), true);
            try { LocalDate.parse(date); } catch (RuntimeException ex) { throw new IllegalArgumentException("Fecha de aprendizaje inválida en el respaldo."); }
            var entry = new CorrectionMemory.Entry(text(node.path("store"), true), normalize(text(node.path("firma"), true)),
                    text(node.path("descripcion"), false), text(node.path("marca"), false), text(node.path("categoria"), false), node.path("veces").asInt(), date);
            if (entry.firma().isBlank() || !keys.add(key(entry))) throw new IllegalArgumentException("Firma vacía o repetida en el respaldo.");
            entries.add(entry);
        }
        var catalog = new LinkedHashMap<String, String>();
        for (var node : root.path("brands")) {
            String brand = text(node, true), key = normalize(brand);
            if (key.isBlank() || catalog.putIfAbsent(key, brand) != null) throw new IllegalArgumentException("Marca vacía o repetida en el respaldo.");
        }
        return new Document(FORMAT, 1, entries.stream().sorted(Comparator.comparing(this::key)).toList(),
                catalog.values().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList());
    }
    private void fields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Formato de respaldo inválido.");
        node.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) throw new IllegalArgumentException("El respaldo contiene campos desconocidos."); });
    }
    private String text(JsonNode node, boolean required) {
        if (!node.isTextual() || node.asText().length() > 1000 || node.asText().chars().anyMatch(Character::isISOControl)
                || required && node.asText().isBlank()) throw new IllegalArgumentException("Texto inválido en el respaldo.");
        return node.asText().trim();
    }
    private String normalize(String value) { return CorrectionMemoryRules.normalize(value); }
    private String key(CorrectionMemory.Entry entry) { return normalize(entry.store()) + "\u0000" + normalize(entry.firma()); }
    private String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(value))); }
        catch (Exception error) { throw new IllegalStateException("No se pudo preparar la restauración.", error); }
    }
}
