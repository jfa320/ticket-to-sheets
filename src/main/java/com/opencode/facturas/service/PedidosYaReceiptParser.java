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
    private static final Pattern TRAILING_UNITS_PATTERN = Pattern.compile("(?i)\\s+(\\d+)\\s*x\\s*$");
    private static final Pattern UNIT_QUANTITY_PATTERN = Pattern.compile("(?i)\\b(\\d+)\\s*x\\s*$");
    private static final Pattern KILOGRAM_QUANTITY_PATTERN = Pattern.compile("(?i)(\\d+(?:[\\.,]\\d+)?)\\s*kg\\b");
    private static final Pattern CHANGED_WEIGHT_PATTERN = Pattern.compile(
            "(?i)\\s+(\\d+(?:[.,]\\d+)?\\s*(?:kg|g)\\s*\\d+(?:[.,]\\d+)?\\s*kg)\\s*$");

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
        int start = orderStart(lines);
        int end = orderEnd(lines, start);
        boolean precedingQuantities = quantitiesPrecedeProducts(lines, start, end);
        String pendingQuantity = null;

        for (int index = start; index < end; index++) {
            String line = cleanProductLine(lines.get(index));
            String normalized = lineAnalyzer.normalize(line);
            if (isStandaloneQuantity(line)) {
                if (precedingQuantities) {
                    pendingQuantity = extractQuantity(line).orElse(null);
                }
                continue;
            }
            Optional<InlineItem> inlineItem = parseInlineItem(line, normalized);
            if (inlineItem.isPresent()) {
                InlineItem item = inlineItem.get();
                candidates.add(new Candidate(
                        item.description(),
                        item.price(),
                        precedingQuantities && pendingQuantity != null && extractQuantity(line).isEmpty()
                                ? pendingQuantity : item.quantity(),
                        lineAnalyzer.isAmbiguous(line, normalized),
                        line
                ));
                pendingQuantity = null;
                continue;
            }

            if (!isLikelyProductLine(line, normalized)) {
                continue;
            }

            ProductLine productLine = splitQuantity(line);
            Optional<LocatedPrice> price = findPriceInFollowingLines(lines, index + 1, end);
            if (price.isEmpty()) {
                if (pendingQuantity != null || productLine.quantity().isPresent() || shouldWarnMissingPrice(line, normalized)) {
                    warnings.add("Producto posible sin precio: " + line);
                }
                pendingQuantity = null;
                continue;
            }

            String quantity = productLine.quantity().orElse(precedingQuantities ? pendingQuantity : null);
            int consumedThrough = price.get().index();
            if (quantity == null && !precedingQuantities) {
                Optional<LocatedQuantity> followingQuantity = findQuantityInFollowingLines(lines, consumedThrough + 1, end);
                if (followingQuantity.isPresent()) {
                    quantity = followingQuantity.get().quantity();
                    consumedThrough = followingQuantity.get().index();
                }
            }
            candidates.add(new Candidate(
                    productLine.description(),
                    price.get().price(),
                    quantity == null ? "1" : quantity,
                    lineAnalyzer.isAmbiguous(line, normalized),
                    line
            ));
            // The price row may include lettering from the package. Consume it
            // with its description so that lettering cannot become another item.
            index = consumedThrough;
            pendingQuantity = null;
        }

        return new ParseResult(candidates, warnings);
    }

    private int orderStart(List<String> lines) {
        for (int index = 0; index < lines.size(); index++) {
            if (lineAnalyzer.normalize(lines.get(index)).equals("tu pedido")) {
                return index + 1;
            }
        }
        return 0;
    }

    private int orderEnd(List<String> lines, int start) {
        for (int index = start; index < lines.size(); index++) {
            String normalized = lineAnalyzer.normalize(lines.get(index));
            if (normalized.matches("^(?:ver menos|ver mas|notas adicionales\\b.*|tu pago\\b.*|medios de pago\\b.*|detalles? sobre la entrega\\b.*)$")) {
                return index;
            }
        }
        return lines.size();
    }

    private boolean quantitiesPrecedeProducts(List<String> lines, int start, int end) {
        for (int index = start; index < end; index++) {
            String line = cleanProductLine(lines.get(index));
            if (isStandaloneQuantity(line)) {
                return true;
            }
            if (isLikelyProductLine(line, lineAnalyzer.normalize(line))) {
                return false;
            }
        }
        return false;
    }

    private String cleanProductLine(String line) {
        // A package image can contribute a spurious zero before the name.
        // Keep explicit currency amounts, including genuine free products.
        return lineAnalyzer.clean(line).replaceFirst("^0[.,]0{2}\\s+(?=\\p{L})", "");
    }

    private boolean isStandaloneQuantity(String line) {
        return line.matches("(?i)\\d+\\s*x")
                || (KILOGRAM_QUANTITY_PATTERN.matcher(line).find()
                && line.replaceAll("(?i)kg|g", "").matches("[0-9.,\\s]+"));
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
        if (isInterfaceLine(normalized)) {
            return false;
        }
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
        if (isInterfaceLine(normalized) || lineAnalyzer.containsMetadata(normalized)
                || normalized.contains("off")
                || normalized.contains("market")
                || lineAnalyzer.isSummary(normalized)
                || normalized.contains("total")) {
            return Optional.empty();
        }
        if (line.chars().filter(Character::isLetter).count() < 4) {
            return Optional.empty();
        }

        String withoutChangedWeight = CHANGED_WEIGHT_PATTERN.matcher(line).replaceAll(" ");
        String withoutKilograms = KILOGRAM_QUANTITY_PATTERN.matcher(withoutChangedWeight).replaceAll(" ");
        List<String> prices = lineAnalyzer.moneyValues(withoutKilograms);
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

    private boolean isInterfaceLine(String normalized) {
        return normalized.matches("^(?:entregad[oa]\\b.*|compensacion\\b.*|te acreditamos\\b.*|(?:of )?repetir pedido\\b.*|ir al local|(?:q )?ayuda|opinar|ver menos|ver mas|notas adicionales\\b.*|hubo cambios\\b.*|detalle de los cambios\\b.*|cobro extra\\b.*|veras el cobro extra\\b.*|como veo mi cobro extra\\b.*)$");
    }

    private Optional<LocatedPrice> findPriceInFollowingLines(List<String> lines, int startIndex, int end) {
        for (int index = startIndex; index < Math.min(end, startIndex + 3); index++) {
            String line = lineAnalyzer.clean(lines.get(index));
            String normalized = lineAnalyzer.normalize(line);
            if (isStandaloneQuantity(line) || isInterfaceLine(normalized)) {
                break;
            }
            if (normalized.contains("off")) {
                continue;
            }
            Optional<String> price = lineAnalyzer.firstMoneyValue(line);
            String lettering = lineAnalyzer.removeMoneyValues(line).trim();
            boolean priceRow = lettering.chars().noneMatch(Character::isLetter)
                    || (lettering.split("\\s+").length <= 2 && extractQuantity(lettering).isEmpty());
            if (price.isPresent() && priceRow) {
                return Optional.of(new LocatedPrice(price.get(), index));
            }
            if (isLikelyProductLine(line, normalized)) {
                break;
            }
        }
        return Optional.empty();
    }

    private Optional<LocatedQuantity> findQuantityInFollowingLines(List<String> lines, int startIndex, int end) {
        for (int index = startIndex; index < Math.min(end, startIndex + 4); index++) {
            String line = lineAnalyzer.clean(lines.get(index));
            if (isStandaloneQuantity(line)) {
                return Optional.of(new LocatedQuantity(extractQuantity(line).orElseThrow(), index));
            }
            String normalized = lineAnalyzer.normalize(line);
            if (isLikelyProductLine(line, normalized) || isInterfaceLine(normalized)) {
                break;
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
        Matcher weightMatcher = CHANGED_WEIGHT_PATTERN.matcher(line);
        if (weightMatcher.find()) {
            return new ProductLine(line.substring(0, weightMatcher.start()).trim(), extractQuantity(weightMatcher.group(1)));
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

    private record LocatedPrice(String price, int index) {
    }

    private record LocatedQuantity(String quantity, int index) {
    }
}
