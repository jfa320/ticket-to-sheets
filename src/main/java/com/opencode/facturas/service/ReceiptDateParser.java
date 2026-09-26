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

        return Optional.empty();
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
        String[] parts = rawDate.replace('-', '/').split("/");
        if (parts.length == 2) {
            int currentYear = LocalDate.now(clock).getYear();
            return LocalDate.of(currentYear, Integer.parseInt(parts[1]), Integer.parseInt(parts[0]));
        }
        if (parts.length == 3) {
            int year = Integer.parseInt(parts[2]);
            if (year < 100) {
                year += 2000;
            }
            return LocalDate.of(year, Integer.parseInt(parts[1]), Integer.parseInt(parts[0]));
        }
        throw new DateTimeParseException("Fecha invalida", rawDate, 0);
    }
}
