package com.opencode.facturas.service;

import com.opencode.facturas.model.OcrDetection;
import com.opencode.facturas.model.OcrLine;
import com.opencode.facturas.model.OcrResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class ReceiptLayoutReader {

    private static final double LOW_CONFIDENCE_THRESHOLD = 0.65;
    private static final double EXTREME_BOX_ASPECT_RATIO = 28.0;
    private static final Pattern QUANTITY_TOKEN_PATTERN = Pattern.compile("^\\d{1,4}(?:[\\.,]\\d{1,4})?$");
    private static final Pattern MULTIPLIER_BEFORE_PRICE_PATTERN = Pattern.compile(
            "(?i)(?:^|\\s)(\\d+(?:[\\.,]\\d{1,4})?)\\s*x\\s*\\$?\\s*\\d"
    );
    private static final Pattern STANDALONE_MULTIPLIER_PRICE_PATTERN = Pattern.compile(
            "(?i)^\\s*(\\d+(?:[\\.,]\\d{1,4})?)\\s*x\\s*\\$?\\s*(\\d+(?:[\\.,]\\d{3})*[\\.,]\\d{2})\\s*$"
    );

    private final ReceiptLineAnalyzer lineAnalyzer;
    private final ReceiptAmounts amounts;

    ReceiptLayoutReader(ReceiptLineAnalyzer lineAnalyzer, ReceiptAmounts amounts) {
        this.lineAnalyzer = lineAnalyzer;
        this.amounts = amounts;
    }

    Layout read(OcrResult result) {
        if (result == null) {
            return new Layout(List.of(), Map.of());
        }

        List<OcrResult> pages = result.pages().isEmpty() ? List.of(result) : result.pages();
        List<String> lines = new ArrayList<>();
        Map<Integer, List<Candidate>> candidatesByLine = new LinkedHashMap<>();

        for (OcrResult page : pages) {
            List<Row> rows = rowsFromDetections(page.detections());
            if (rows.isEmpty()) {
                rows = rowsFromLines(page.lines());
            }

            appendRows(rows, lines, candidatesByLine);
        }

        if (lines.isEmpty() && result.text() != null) {
            lines.addAll(result.text().lines()
                    .map(String::trim)
                    .filter(line -> !line.isBlank())
                    .toList());
        }

        return new Layout(lines, candidatesByLine);
    }

    private void appendRows(List<Row> rows, List<String> lines, Map<Integer, List<Candidate>> candidatesByLine) {
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            Row row = rows.get(rowIndex);
            Optional<QuantityPrice> quantityPrice = standaloneMultiplierPrice(row.text());
            if (quantityPrice.isPresent() && rowIndex + 1 < rows.size()) {
                Row followingRow = rows.get(rowIndex + 1);
                Optional<Candidate> followingCandidate = followingRow.candidate(lineAnalyzer, amounts);
                if (followingCandidate.isPresent() && !followingCandidate.get().priceIsUnit()) {
                    Candidate candidate = followingCandidate.get();
                    double quantity = amounts.parseQuantity(quantityPrice.get().quantity()).orElse(1.0);
                    double unitPrice = amounts.parse(quantityPrice.get().unitPrice());
                    double total = amounts.parse(candidate.rawPrice());
                    if (quantity > 0 && amounts.isConsistent(unitPrice, quantity, total)) {
                        int lineIndex = lines.size();
                        lines.add(followingRow.text());
                        candidatesByLine.put(lineIndex, List.of(new Candidate(
                                candidate.description(),
                                candidate.rawPrice(),
                                amounts.formatQuantity(quantity),
                                false,
                                candidate.ambiguous(),
                                candidate.sourceLine()
                        )));
                        rowIndex++;
                        continue;
                    }
                }
            }

            int lineIndex = lines.size();
            lines.add(row.text());
            row.candidate(lineAnalyzer, amounts).ifPresent(candidate ->
                    candidatesByLine.put(lineIndex, List.of(candidate)));
        }
    }

    private static Optional<QuantityPrice> standaloneMultiplierPrice(String rowText) {
        Matcher matcher = STANDALONE_MULTIPLIER_PRICE_PATTERN.matcher(rowText);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new QuantityPrice(matcher.group(1), matcher.group(2)));
    }

    private List<Row> rowsFromLines(List<OcrLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        return lines.stream()
                .filter(line -> line.text() != null && !line.text().isBlank())
                .sorted(Comparator.comparingDouble(OcrLine::top).thenComparingDouble(OcrLine::left))
                .map(line -> new Row(List.of(new Box(
                        line.text(),
                        line.confidence(),
                        line.top(),
                        line.left(),
                        line.right(),
                        line.bottom()
                ))))
                .toList();
    }

    private List<Row> rowsFromDetections(List<OcrDetection> detections) {
        if (detections == null || detections.isEmpty()) {
            return List.of();
        }

        List<Box> boxes = detections.stream()
                .map(this::boxFrom)
                .flatMap(Optional::stream)
                .sorted(Comparator.comparingDouble(Box::top).thenComparingDouble(Box::left))
                .toList();
        List<MutableRow> rows = new ArrayList<>();

        for (Box box : boxes) {
            MutableRow matchingRow = rows.stream()
                    .filter(row -> sameRow(row, box))
                    .findFirst()
                    .orElse(null);
            if (matchingRow == null) {
                rows.add(new MutableRow(box));
            } else {
                matchingRow.add(box);
            }
        }

        return rows.stream()
                .sorted(Comparator.comparingDouble(MutableRow::top).thenComparingDouble(MutableRow::left))
                .map(row -> new Row(row.boxes()))
                .toList();
    }

    private Optional<Box> boxFrom(OcrDetection detection) {
        if (detection == null || detection.text() == null || detection.text().isBlank()
                || detection.box() == null || detection.box().size() < 2) {
            return Optional.empty();
        }

        List<List<Double>> points = detection.box().stream()
                .filter(point -> point != null && point.size() >= 2)
                .toList();
        if (points.size() < 2) {
            return Optional.empty();
        }

        double top = points.stream().mapToDouble(point -> point.get(1)).min().orElse(0.0);
        double bottom = points.stream().mapToDouble(point -> point.get(1)).max().orElse(0.0);
        double left = points.stream().mapToDouble(point -> point.get(0)).min().orElse(0.0);
        double right = points.stream().mapToDouble(point -> point.get(0)).max().orElse(0.0);
        if (right <= left || bottom <= top) {
            return Optional.empty();
        }

        return Optional.of(new Box(detection.text(), detection.confidence(), top, left, right, bottom));
    }

    private boolean sameRow(MutableRow row, Box box) {
        double centerDistance = Math.abs(row.center() - box.center());
        double threshold = Math.max(8.0, Math.min(row.height(), box.height()) * 0.45);
        return centerDistance <= threshold;
    }

    private static boolean isQuantityToken(String value) {
        return QUANTITY_TOKEN_PATTERN.matcher(value.trim()).matches();
    }

    private static Optional<String> multiplierQuantity(String rowText) {
        Matcher matcher = MULTIPLIER_BEFORE_PRICE_PATTERN.matcher(rowText);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private record QuantityPrice(String quantity, String unitPrice) {
    }

    record Layout(List<String> lines, Map<Integer, List<Candidate>> candidatesByLine) {
    }

    record Candidate(String description, String rawPrice, String quantity, boolean priceIsUnit,
                     boolean ambiguous, String sourceLine) {
    }

    private record Box(String text, Double confidence, double top, double left, double right, double bottom) {

        double width() {
            return right - left;
        }

        double height() {
            return bottom - top;
        }

        double center() {
            return top + height() / 2;
        }

        boolean isExtremelyWide() {
            return height() > 0 && width() / height() >= EXTREME_BOX_ASPECT_RATIO;
        }
    }

    private static final class MutableRow {
        private final List<Box> boxes = new ArrayList<>();

        MutableRow(Box box) {
            boxes.add(box);
        }

        void add(Box box) {
            boxes.add(box);
        }

        List<Box> boxes() {
            return boxes.stream()
                    .sorted(Comparator.comparingDouble(Box::left))
                    .toList();
        }

        double center() {
            return boxes.stream().mapToDouble(Box::center).average().orElse(0.0);
        }

        double height() {
            return boxes.stream().mapToDouble(Box::height).max().orElse(1.0);
        }

        double top() {
            return boxes.stream().mapToDouble(Box::top).min().orElse(0.0);
        }

        double left() {
            return boxes.stream().mapToDouble(Box::left).min().orElse(0.0);
        }
    }

    private static final class Row {
        private final List<Box> boxes;

        Row(List<Box> boxes) {
            this.boxes = boxes.stream()
                    .sorted(Comparator.comparingDouble(Box::left))
                    .toList();
        }

        String text() {
            return boxes.stream()
                    .map(Box::text)
                    .collect(Collectors.joining(" "))
                    .replaceAll("\\s+", " ")
                    .trim();
        }

        Optional<Candidate> candidate(ReceiptLineAnalyzer lineAnalyzer, ReceiptAmounts amounts) {
            String sourceLine = lineAnalyzer.clean(text());
            String normalized = lineAnalyzer.normalize(sourceLine);
            if (lineAnalyzer.shouldSkip(normalized)) {
                return Optional.empty();
            }

            List<MoneyBox> moneyBoxes = boxes.stream()
                    .map(box -> MoneyBox.from(box, lineAnalyzer))
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparingDouble(money -> money.box().left()))
                    .toList();
            if (moneyBoxes.isEmpty()) {
                return Optional.empty();
            }

            Optional<String> explicitQuantity = multiplierQuantity(sourceLine);
            Optional<Box> quantityBox = explicitQuantity.isEmpty() && moneyBoxes.size() >= 2
                    ? boxes.stream()
                    .filter(box -> !containsBox(moneyBoxes, box))
                    .filter(box -> isQuantityToken(box.text()))
                    .filter(box -> box.left() < moneyBoxes.get(0).box().left())
                    .max(Comparator.comparingDouble(Box::left))
                    : Optional.empty();

            String quantityValue = explicitQuantity
                    .or(() -> quantityBox.map(Box::text))
                    .orElse("1");
            double quantity = amounts.parseQuantity(quantityValue).orElse(1.0);
            if (quantity <= 0) {
                quantity = 1.0;
            }

            Set<Box> excludedBoxes = moneyBoxes.stream().map(MoneyBox::box).collect(Collectors.toSet());
            quantityBox.ifPresent(excludedBoxes::add);
            String description = boxes.stream()
                    .filter(box -> !excludedBoxes.contains(box))
                    .map(Box::text)
                    .collect(Collectors.joining(" "));
            description = lineAnalyzer.removeMoneyValues(description)
                    .replaceAll("(?i)^\\s*\\d+(?:[\\.,]\\d{1,4})?\\s*x\\s*", "")
                    .replaceAll("(?i)\\b\\d+(?:[\\.,]\\d{1,4})?\\s*x\\b", " ")
                    .replaceAll("\\s+", " ")
                    .trim();

            if (description.length() < 3 || !lineAnalyzer.isLikelyProduct(description, normalized)) {
                return Optional.empty();
            }

            MoneyBox firstMoney = moneyBoxes.get(0);
            MoneyBox lastMoney = moneyBoxes.get(moneyBoxes.size() - 1);
            boolean priceIsUnit = false;
            boolean lowConfidence = boxes.stream()
                    .map(Box::confidence)
                    .filter(confidence -> confidence != null)
                    .anyMatch(confidence -> confidence < LOW_CONFIDENCE_THRESHOLD);
            boolean suspiciousGeometry = boxes.stream().anyMatch(Box::isExtremelyWide);
            boolean ambiguous = lineAnalyzer.isAmbiguous(sourceLine, normalized)
                    || lowConfidence
                    || suspiciousGeometry;
            String rawPrice = lastMoney.value();

            if (moneyBoxes.size() >= 2 && (explicitQuantity.isPresent() || quantityBox.isPresent())) {
                double unitPrice = amounts.parse(firstMoney.value());
                double lineTotal = amounts.parse(lastMoney.value());
                if (amounts.isConsistent(unitPrice, quantity, lineTotal)) {
                    rawPrice = firstMoney.value();
                    priceIsUnit = true;
                } else {
                    ambiguous = true;
                }
            } else if (explicitQuantity.isPresent() && moneyBoxes.size() == 1) {
                rawPrice = firstMoney.value();
                priceIsUnit = true;
            }

            return Optional.of(new Candidate(
                    description,
                    rawPrice,
                    amounts.formatQuantity(quantity),
                    priceIsUnit,
                    ambiguous,
                    sourceLine
            ));
        }

        private static boolean containsBox(List<MoneyBox> moneyBoxes, Box box) {
            return moneyBoxes.stream().anyMatch(money -> money.box() == box);
        }

    }

    private record MoneyBox(Box box, String value) {

        static Optional<MoneyBox> from(Box box, ReceiptLineAnalyzer lineAnalyzer) {
            List<String> values = lineAnalyzer.moneyValues(box.text());
            if (values.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new MoneyBox(box, values.get(values.size() - 1)));
        }
    }
}
