package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.facturas.model.ExtractResponse;
import com.opencode.facturas.model.OcrResult;
import com.opencode.facturas.model.ReceiptItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ReceiptEvaluationTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesAnonymousEvaluationBank() throws Exception {
        parsesFixtures("evaluation");
    }

    @Test
    void parsesAnonymousDevelopmentFixtures() throws Exception {
        parsesFixtures("development");
    }

    private void parsesFixtures(String split) throws Exception {
        Path root = Path.of("src", "test", "resources", "receipt-evaluation");
        List<Path> fixtures;
        try (Stream<Path> paths = Files.walk(root)) {
            fixtures = paths
                    .filter(path -> path.toString().endsWith(".json"))
                    .sorted()
                    .toList();
        }
        assertFalse(fixtures.isEmpty(), "El banco de evaluacion no tiene fixtures");

        ReceiptParserService parser = new ReceiptParserService(
                StoreNameMapper.empty(),
                new BrandCatalog(objectMapper, tempDir.resolve("brands-" + split + ".json")),
                new CorrectionMemory(objectMapper, tempDir.resolve("corrections-" + split + ".json"))
        );
        EvaluationMetrics metrics = new EvaluationMetrics();
        int fixtureCount = 0;

        for (Path fixture : fixtures) {
            JsonNode rootNode = objectMapper.readTree(fixture.toFile());
            if (!split.equals(rootNode.path("split").asText("evaluation"))) {
                continue;
            }
            fixtureCount++;
            ExtractResponse response = parser.parse(objectMapper.treeToValue(rootNode.path("ocr"), OcrResult.class));
            JsonNode expected = rootNode.path("expected");

            metrics.compare("storeName", expected.path("storeName").asText(), response.storeName(), fixture);
            metrics.compare("date", expected.path("date").asText(), response.date(), fixture);
            if (expected.has("total")) {
                metrics.compare("total", expected.path("total").asText(), response.total(), fixture);
            }
            metrics.compare("itemCount", expected.path("items").size(), response.itemCount(), fixture);

            int comparableItems = Math.min(expected.path("items").size(), response.items().size());
            for (int i = 0; i < comparableItems; i++) {
                JsonNode expectedItem = expected.path("items").get(i);
                ReceiptItem actual = response.items().get(i);
                metrics.recordExpectedItem();
                metrics.compare("description", expectedItem.path("description").asText(), actual.descripcion(), fixture);
                metrics.compare("quantity", expectedItem.path("quantity").asText(), actual.cantidad(), fixture);
                metrics.compare("unitPrice", expectedItem.path("unitPrice").asText(), actual.precioUnitario(), fixture);
                if (expectedItem.has("state")) {
                    metrics.compare("state", expectedItem.path("state").asText(), actual.estado(), fixture);
                }
                if ("AMBIGUOUS".equals(expectedItem.path("state").asText())) {
                    metrics.recordAmbiguousItem();
                }
            }
        }

        assertFalse(fixtureCount == 0, "No hay fixtures en el split " + split);
        String report = metrics.describe(split, fixtureCount);
        System.out.println(report);
        assertEquals(0, metrics.mismatches().size(), report + "\n" + String.join("\n", metrics.mismatches()));
    }

    private static final class EvaluationMetrics {
        private int expectedItems;
        private int expectedAmbiguousItems;
        private final Map<String, Integer> comparisons = new LinkedHashMap<>();
        private final Map<String, Integer> exactMatches = new LinkedHashMap<>();
        private final List<String> mismatches = new ArrayList<>();

        void compare(String field, Object expected, Object actual, Path fixture) {
            comparisons.merge(field, 1, Integer::sum);
            if (Objects.equals(expected, actual)) {
                exactMatches.merge(field, 1, Integer::sum);
            } else {
                mismatches.add(fixture.getFileName() + " " + field + ": esperado='" + expected
                        + "', obtenido='" + actual + "'");
            }
        }

        void recordExpectedItem() {
            expectedItems++;
        }

        void recordAmbiguousItem() {
            expectedAmbiguousItems++;
        }

        List<String> mismatches() {
            return List.copyOf(mismatches);
        }

        String describe(String split, int fixtureCount) {
            String fieldAccuracy = comparisons.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + exactMatches.getOrDefault(entry.getKey(), 0) + "/" + entry.getValue())
                    .collect(java.util.stream.Collectors.joining(", "));
            return "Receipt evaluation split=" + split + " fixtures=" + fixtureCount
                    + " expectedItems=" + expectedItems
                    + " expectedAmbiguousItems=" + expectedAmbiguousItems
                    + " fieldAccuracy=[" + fieldAccuracy + "]"
                    + " mismatches=" + mismatches.size();
        }
    }
}
