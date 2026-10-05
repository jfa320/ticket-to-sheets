package com.opencode.facturas.service;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ReceiptDateParser {

    private static final List<List<String>> MONTH_NAMES = List.of(
            List.of("ene", "enero"), List.of("feb", "febrero"), List.of("mar", "marzo"),
            List.of("abr", "abril"), List.of("may", "mayo"), List.of("jun", "junio"),
            List.of("jul", "julio"), List.of("ago", "agosto"),
            List.of("sep", "sept", "set", "septiembre", "setiembre"),
            List.of("oct", "octubre"), List.of("nov", "noviembre"), List.of("dic", "diciembre"));
    private static final Pattern TEXT_DATE_PATTERN = Pattern.compile(
            "(?<![a-z0-9])([0-9o]{1,2})\\s+(?:de\\s+)?("
                    + MONTH_NAMES.stream().flatMap(List::stream).collect(java.util.stream.Collectors.joining("|"))
                    + ")(?![a-z])(?:\\s+(?:de\\s+)?(\\d{4})(?!\\d))?");
    private static final Pattern DELIVERY_LABEL = Pattern.compile("\\bentregad[oa]\\b");
    private static final Pattern EXCLUDED_DATE_LABEL = Pattern.compile(
            "\\b(?:inicio|actividad|venc\\w*|vto|caduc\\w*|elabor\\w*|fabric\\w*|cuit)\\b");

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

        // PedidosYa shows a delivery status rather than a 'Fecha' label. OCR
        // may split the small header into separate rows, even between day/month.
        for (int index = 0; index < lines.size(); index++) {
            String normalized = lineAnalyzer.normalize(lines.get(index));
            if (!DELIVERY_LABEL.matcher(normalized).find() || isExcludedDateLine(normalized)) {
                continue;
            }
            for (int count = 1; count <= 3 && index + count <= lines.size(); count++) {
                String header = String.join(" ", lines.subList(index, index + count));
                if (isExcludedDateLine(lineAnalyzer.normalize(header))) {
                    break;
                }
                Optional<String> date = findDateInLine(header);
                if (date.isPresent()) {
                    return date;
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

        for (String line : lines) {
            Optional<String> date = findTextDate(line, true);
            if (date.isPresent()) {
                return date;
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
        return !isExcludedDateLine(normalizedLine);
    }

    private boolean isExcludedDateLine(String normalizedLine) {
        return EXCLUDED_DATE_LABEL.matcher(normalizedLine).find();
    }

    private Optional<String> findTextDate(String line, boolean standalone) {
        String normalized = lineAnalyzer.normalize(line);
        if (isExcludedDateLine(normalized)) {
            return Optional.empty();
        }
        Matcher matcher = TEXT_DATE_PATTERN.matcher(normalized);
        while (matcher.find()) {
            if (standalone) {
                String remainder = normalized.substring(0, matcher.start()) + " " + normalized.substring(matcher.end());
                remainder = remainder.replaceAll("\\b(?:lun(?:es)?|mar(?:tes)?|mie(?:rcoles)?|jue(?:ves)?|vie(?:rnes)?|sab(?:ado)?|dom(?:ingo)?|hs|h)\\b", " ")
                        .replaceAll("\\b\\d{1,2}\\s+\\d{2}(?:\\s+\\d{2})?\\b", " ").trim();
                if (!remainder.isBlank()) {
                    continue;
                }
            }
            int month = 0;
            for (int index = 0; index < MONTH_NAMES.size(); index++) {
                if (MONTH_NAMES.get(index).contains(matcher.group(2))) {
                    month = index + 1;
                    break;
                }
            }
            String rawDate = matcher.group(1).replace('o', '0') + "/" + month
                    + (matcher.group(3) == null ? "" : "/" + matcher.group(3));
            if (isLikelyReceiptDate(rawDate)) {
                return Optional.of(rawDate);
            }
        }
        return Optional.empty();
    }

    private Optional<String> findDateInLine(String line) {
        Matcher matcher = ReceiptLineAnalyzer.DATE_PATTERN.matcher(normalizeSeparators(line));
        while (matcher.find()) {
            String date = matcher.group(1).replaceAll("\\s+", "");
            if (isLikelyReceiptDate(date)) {
                return Optional.of(date);
            }
        }
        return findTextDate(line, false);
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
