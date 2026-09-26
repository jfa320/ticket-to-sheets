package com.opencode.facturas.service;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;

final class ReceiptDateParser {

    private final ReceiptLineAnalyzer lineAnalyzer;
    private final Clock clock;

    ReceiptDateParser(ReceiptLineAnalyzer lineAnalyzer) {
        this(lineAnalyzer, Clock.systemDefaultZone());
    }

    ReceiptDateParser(ReceiptLineAnalyzer lineAnalyzer, Clock clock) {
        this.lineAnalyzer = lineAnalyzer;
        this.clock = clock;
    }

    String extractNormalized(List<String> lines) {
        return normalize(extract(lines).orElse(""));
    }

    String normalize(String rawDate) {
        if (rawDate == null || rawDate.isBlank()) {
            return "";
        }

        try {
            LocalDate parsed = parse(rawDate);
            return parsed.format(DateTimeFormatter.ofPattern("d/M/yyyy"));
        } catch (DateTimeException | NumberFormatException ex) {
            return rawDate;
        }
    }

    private Optional<String> extract(List<String> lines) {
        for (int index = 0; index < lines.size(); index++) {
            String normalized = lineAnalyzer.normalize(lines.get(index));
            if (!isReceiptDateLabel(normalized)) {
                continue;
            }

            Optional<String> sameLineDate = findDateInLine(lines.get(index));
            if (sameLineDate.isPresent()) {
                return sameLineDate;
            }

            if (index + 1 < lines.size()) {
                Optional<String> nextLineDate = findDateInLine(lines.get(index + 1));
                if (nextLineDate.isPresent()) {
                    return nextLineDate;
                }
            }
        }

        for (String line : lines) {
            if (isUnlabeledReceiptDateLine(line)) {
                Optional<String> date = findDateInLine(line);
                if (date.isPresent()) {
                    return date;
                }
            }
        }

        return Optional.empty();
    }

    private boolean isUnlabeledReceiptDateLine(String line) {
        String normalized = lineAnalyzer.normalize(line);
        if (normalized.contains("actividad") || normalized.contains("venc")
                || normalized.contains("vto") || normalized.contains("caduc")
                || normalized.contains("elabor") || normalized.contains("fabric")
                || normalized.contains("cuit")) {
            return false;
        }
        Matcher dateMatcher = ReceiptLineAnalyzer.DATE_PATTERN.matcher(normalizeSeparators(line));
        if (!dateMatcher.find()) {
            return false;
        }
        String withoutDate = line.substring(0, dateMatcher.start()) + " " + line.substring(dateMatcher.end());
        if (lineAnalyzer.containsMoney(withoutDate)) {
            return false;
        }
        withoutDate = withoutDate.replaceAll("(?i)\\b(?:hora|hs|h|emision|venta|compra|ticket|comprobante|fecha|f)\\b", " ")
                .replaceAll("\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b", " ")
                .replaceAll("[^\\p{L}]+", " ").trim();
        return withoutDate.isBlank();
    }

    private boolean isReceiptDateLabel(String normalizedLine) {
        if (!normalizedLine.contains("fecha")) {
            return false;
        }
        return !normalizedLine.contains("inicio") && !normalizedLine.contains("actividad");
    }

    private Optional<String> findDateInLine(String line) {
        Matcher matcher = ReceiptLineAnalyzer.DATE_PATTERN.matcher(normalizeSeparators(line));
        while (matcher.find()) {
            String date = matcher.group(1).replaceAll("\\s+", "");
            if (isLikelyReceiptDate(date)) {
                return Optional.of(date);
            }
        }
        return Optional.empty();
    }

    private String normalizeSeparators(String value) {
        return value.replace('O', '0').replace('o', '0').replace('|', '/');
    }

    private boolean isLikelyReceiptDate(String rawDate) {
        try {
            LocalDate parsed = parse(rawDate);
            return parsed.getYear() >= 2015;
        } catch (DateTimeException | NumberFormatException ex) {
            return false;
        }
    }

    private LocalDate parse(String rawDate) {
        String[] parts = rawDate.replace('-', '/').replace('.', '/').split("/");
        if (parts.length == 2) {
            int currentYear = LocalDate.now(clock).getYear();
            return LocalDate.of(currentYear, Integer.parseInt(parts[1]), Integer.parseInt(parts[0]));
        }
        if (parts.length == 3) {
            if (parts[0].length() == 4) {
                return LocalDate.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            }
            int year = Integer.parseInt(parts[2]);
            if (year < 100) {
                year += 2000;
            }
            return LocalDate.of(year, Integer.parseInt(parts[1]), Integer.parseInt(parts[0]));
        }
        throw new DateTimeParseException("Fecha invalida", rawDate, 0);
    }
}
