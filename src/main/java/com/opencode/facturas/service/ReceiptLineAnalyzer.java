package com.opencode.facturas.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ReceiptLineAnalyzer {

    static final Pattern DATE_PATTERN = Pattern.compile(
            "(?<!\\d)((?:\\d{4}\\s*[./-]\\s*\\d{1,2}\\s*[./-]\\s*\\d{1,2})|(?:\\d{1,2}\\s*[./-]\\s*\\d{1,2}(?:\\s*[./-]\\s*\\d{2,4})?))(?!\\d)"
    );

    private static final Pattern MONEY_PATTERN = Pattern.compile(
            "(?:\\$\\s*\\d+[\\.,]\\d{5}|\\$\\s*\\d+(?:[\\.,]\\d{3})*(?:[\\.,]\\d{2})?|\\d+(?:[\\.,]\\d{3})*[\\.,]\\d{2})(?!\\d)"
    );
    private static final Pattern PRICE_ONLY_PATTERN = Pattern.compile("^(\\d+(?:[\\.,]\\d{3})*[\\.,]\\d{2})$");
    private static final Pattern NUMBER_TOKEN_PATTERN = Pattern.compile("\\d+(?:[\\.,]\\d+)*");
    private static final List<String> STOP_WORDS = List.of(
            "subtotal", "total", "recibi", "cambio", "tarjeta", "efectivo"
    );
    private static final List<String> METADATA_WORDS = List.of(
            "cuit", "direccion", "responsable", "consumidor", "actividad", "fecha", "hora", "nro", "ing.", "iva", "cod.", "pv", "tique",
            "seshia", "orientacion", "transparencia", "fiscal", "regimen", "afip", "cliente", "cantidad", "descripcion", "importe",
            "whatsapp", "ticket", "lunes", "sabados", "gracias", "onsumidor"
    );

    String normalize(String value) {
        return Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9/ ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    String clean(String value) {
        return value
                .replace('|', 'I')
                .replace('"', ' ')
                .replace('`', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    String toTitleCase(String value) {
        Locale locale = Locale.forLanguageTag("es-AR");
        String[] words = value.toLowerCase(locale).split(" ");
        StringBuilder builder = new StringBuilder();

        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            if (word.length() == 1) {
                builder.append(word.toUpperCase(locale));
            } else {
                builder.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }

        return builder.toString();
    }

    boolean shouldSkip(String normalized) {
        if (normalized.isBlank()) {
            return true;
        }
        if (normalized.matches("v ?\\d+(?: \\d+)?")) {
            return true;
        }
        if (containsMetadata(normalized)) {
            return true;
        }
        return isSummary(normalized) || containsStopWord(normalized);
    }

    boolean isSummary(String normalized) {
        String compact = normalized.replace(" ", "");
        return compact.contains("subtot")
                || compact.contains("total")
                || compact.contains("t0tal")
                || compact.contains("tota1")
                || compact.contains("t0ta1")
                || compact.contains("totai")
                || compact.contains("apagar")
                || (normalized.contains("neto") && normalized.contains("gravado"));
    }

    boolean containsMetadata(String normalized) {
        return METADATA_WORDS.stream().anyMatch(normalized::contains);
    }

    boolean containsStopWord(String normalized) {
        return STOP_WORDS.stream().anyMatch(normalized::contains);
    }

    boolean isLikelyDescriptionOnly(String line, String normalized) {
        if (line.length() < 5 || containsMetadata(normalized)) {
            return false;
        }
        if (containsStopWord(normalized)) {
            return false;
        }
        return line.chars().filter(Character::isLetter).count() >= 4 && !isPriceOnly(line);
    }

    boolean isLikelyProduct(String description, String normalizedLine) {
        String normalizedDescription = normalize(description);
        if (containsMetadata(normalizedDescription) || isSummary(normalizedDescription)
                || containsStopWord(normalizedDescription)) {
            return false;
        }
        long letters = description.chars().filter(Character::isLetter).count();
        if (letters < 3) {
            return false;
        }
        return !normalizedLine.contains("vuelto")
                && !normalizedLine.contains("ley 27")
                && !normalizedLine.replace(" ", "").contains("ley27")
                && !normalizedLine.contains("transparencia")
                && !normalizedLine.contains("descuento")
                && !normalizedLine.contains("recargo")
                && !normalizedLine.contains("envio");
    }

    boolean isAmbiguous(String line, String normalized) {
        Matcher numberMatcher = NUMBER_TOKEN_PATTERN.matcher(line);
        int numberTokens = 0;
        while (numberMatcher.find()) {
            numberTokens++;
        }

        return DATE_PATTERN.matcher(normalized).find()
                || normalized.contains("total")
                || containsMetadata(normalized)
                || line.length() > 60
                || numberTokens > 3
                || moneyValues(line).size() > 1;
    }

    boolean isPriceOnly(String value) {
        return PRICE_ONLY_PATTERN.matcher(value).matches();
    }

    boolean isMoneyValue(String value) {
        String trimmed = value.trim();
        return PRICE_ONLY_PATTERN.matcher(trimmed).matches() || MONEY_PATTERN.matcher(trimmed).matches();
    }

    boolean containsMoney(String value) {
        return MONEY_PATTERN.matcher(value).find();
    }

    List<String> moneyValues(String value) {
        List<String> values = new ArrayList<>();
        Matcher matcher = MONEY_PATTERN.matcher(value);
        while (matcher.find()) {
            values.add(matcher.group());
        }
        return values;
    }

    Optional<String> firstMoneyValue(String value) {
        Matcher matcher = MONEY_PATTERN.matcher(value);
        return matcher.find() ? Optional.of(matcher.group()) : Optional.empty();
    }

    Optional<String> lastMoneyValue(String value) {
        List<String> values = moneyValues(value);
        return values.isEmpty() ? Optional.empty() : Optional.of(values.get(values.size() - 1));
    }

    String removeMoneyValues(String value) {
        return MONEY_PATTERN.matcher(value).replaceAll(" ");
    }
}
