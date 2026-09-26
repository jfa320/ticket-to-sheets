package com.opencode.facturas.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PedidosYaReceiptParser {

    private static final Pattern POSSIBLE_QUANTITY_PATTERN = Pattern.compile(
            "(?i)\\b\\d+(?:[\\.,]\\d+)?\\s*(?:x|kg|g|gr|ml|l|un|und|unidad(?:es)?)\\b"
    );
    private static final Pattern TRAILING_UNITS_PATTERN = Pattern.compile("(?i)\\s+(\\d+)x\\s*$");
    private static final Pattern UNIT_QUANTITY_PATTERN = Pattern.compile("(?i)\\b(\\d+)x\\s*$");
    private static final Pattern KILOGRAM_QUANTITY_PATTERN = Pattern.compile("(?i)(\\d+(?:[\\.,]\\d+)?)\\s*kg\\b");

    private final ReceiptLineAnalyzer lineAnalyzer;

    PedidosYaReceiptParser(ReceiptLineAnalyzer lineAnalyzer) {
        this.lineAnalyzer = lineAnalyzer;
    }

    boolean supports(List<String> lines) {
        return lines.stream()
                .map(lineAnalyzer::normalize)
                .anyMatch(line -> line.contains("pedidosya")
                        || line.contains("pedidos ya")
                        || line.contains("podidosya")
                        || (line.contains("market") && line.contains("pedido")));
    }

    ParseResult parse(List<String> lines) {
        List<Candidate> candidates = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (int index = 0; index < lines.size(); index++) {
            String line = lineAnalyzer.clean(lines.get(index));
            String normalized = lineAnalyzer.normalize(line);
            Optional<InlineItem> inlineItem = parseInlineItem(line, normalized);
            if (inlineItem.isPresent()) {
                InlineItem item = inlineItem.get();
                candidates.add(new Candidate(
                        item.description(),
                        item.price(),
                        item.quantity(),
                        lineAnalyzer.isAmbiguous(line, normalized),
                        line
                ));
                continue;
            }

            if (!isLikelyProductLine(line, normalized)) {
                continue;
            }

            ProductLine productLine = splitQuantity(line);
            Optional<String> price = findPriceInFollowingLines(lines, index + 1);
            if (price.isEmpty()) {
                if (shouldWarnMissingPrice(line, normalized)) {
                    warnings.add("Producto posible sin precio: " + line);
                }
                continue;
            }

            String quantity = productLine.quantity().orElse(null);
            if (quantity == null) {
                quantity = findQuantityInFollowingLines(lines, index + 1).orElse("1");
            }
            candidates.add(new Candidate(
                    productLine.description(),
                    price.get(),
                    quantity,
                    lineAnalyzer.isAmbiguous(line, normalized),
                    line
            ));
        }

        return new ParseResult(candidates, warnings);
    }

    private boolean shouldWarnMissingPrice(String line, String normalized) {
        if (!isLikelyProductLine(line, normalized)
                || normalized.contains("pedido")
                || normalized.contains("pago")
                || normalized.contains("medio")
                || normalized.contains("detalle")
                || normalized.contains("entrega")
                || normalized.contains("timbre")
                || normalized.contains("telefon")) {
            return false;
        }

        return lineAnalyzer.containsMoney(line) || POSSIBLE_QUANTITY_PATTERN.matcher(line).find();
    }

    private boolean isLikelyProductLine(String line, String normalized) {
        if (line.length() < 5 || lineAnalyzer.containsMetadata(normalized) || lineAnalyzer.isSummary(normalized)) {
            return false;
        }
        if (normalized.matches("[0-9 kg]+")) {
            return false;
        }
        if (normalized.contains("cambio de peso") || normalized.contains("off") || normalized.contains("market")) {
            return false;
        }
        if (normalized.contains("tu pedido")
                || normalized.contains("tu pago")
                || normalized.contains("medio de pago")
                || normalized.contains("detalle sobre la entrega")
                || normalized.contains("llamar por telefono")
                || normalized.contains("timbre no funciona")
                || normalized.contains("hrptt prdiac")) {
            return false;
        }
        return line.chars().filter(Character::isLetter).count() >= 4;
    }

    private Optional<InlineItem> parseInlineItem(String line, String normalized) {
        if (lineAnalyzer.containsMetadata(normalized)
                || normalized.contains("off")
                || normalized.contains("market")
                || lineAnalyzer.isSummary(normalized)
                || normalized.contains("total")) {
            return Optional.empty();
        }
        if (line.chars().filter(Character::isLetter).count() < 4) {
            return Optional.empty();
        }

        List<String> prices = lineAnalyzer.moneyValues(line);
        if (prices.isEmpty()) {
            return Optional.empty();
        }

        boolean hasUnitQuantity = UNIT_QUANTITY_PATTERN.matcher(line).find();
        String quantity = extractQuantity(line).orElse("1");
        String description = lineAnalyzer.removeMoneyValues(line)
                .replaceAll("(?i)^\\s*\\d+\\s+", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (hasUnitQuantity) {
            description = description.replaceAll("(?i)\\b\\d+x\\b", " ");
        } else {
            description = description
                    .replaceAll("(?i)\\b\\d+g?\\s*\\d+(?:[\\.,]\\d+)?\\s*kg\\b", " ")
                    .replaceAll("(?i)\\b\\d+(?:[\\.,]\\d+)?\\s*kg\\b", " ");
        }
        description = description.replaceAll("\\s+", " ").trim();

        if (description.length() < 4) {
            return Optional.empty();
        }
        return Optional.of(new InlineItem(description, prices.get(0), quantity));
    }

    private Optional<String> findPriceInFollowingLines(List<String> lines, int startIndex) {
        for (int index = startIndex; index < Math.min(lines.size(), startIndex + 3); index++) {
            Optional<String> price = lineAnalyzer.firstMoneyValue(lines.get(index));
            if (price.isPresent()) {
                return price;
            }
        }
        return Optional.empty();
    }

    private Optional<String> findQuantityInFollowingLines(List<String> lines, int startIndex) {
        for (int index = startIndex; index < Math.min(lines.size(), startIndex + 4); index++) {
            Optional<String> quantity = extractQuantity(lines.get(index));
            if (quantity.isPresent()) {
                return quantity;
            }
        }
        return Optional.empty();
    }

    private ProductLine splitQuantity(String line) {
        Matcher unitsMatcher = TRAILING_UNITS_PATTERN.matcher(line);
        if (unitsMatcher.find()) {
            return new ProductLine(
                    line.substring(0, unitsMatcher.start()).trim(),
                    Optional.of(unitsMatcher.group(1))
            );
        }
        return new ProductLine(line, Optional.empty());
    }

    private Optional<String> extractQuantity(String line) {
        Matcher unitMatcher = UNIT_QUANTITY_PATTERN.matcher(line);
        if (unitMatcher.find()) {
            return Optional.of(unitMatcher.group(1));
        }

        Matcher kgMatcher = KILOGRAM_QUANTITY_PATTERN.matcher(line);
        String lastKg = null;
        while (kgMatcher.find()) {
            lastKg = kgMatcher.group(1).replace(',', '.');
        }
        return Optional.ofNullable(lastKg);
    }

    record Candidate(String description, String price, String quantity, boolean ambiguous, String sourceLine) {
    }

    record ParseResult(List<Candidate> candidates, List<String> warnings) {
    }

    private record ProductLine(String description, Optional<String> quantity) {
    }

    private record InlineItem(String description, String price, String quantity) {
    }
}
