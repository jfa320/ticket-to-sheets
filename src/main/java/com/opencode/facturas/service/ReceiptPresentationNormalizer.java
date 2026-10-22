package com.opencode.facturas.service;

import java.util.Locale;
import java.util.regex.Pattern;

/** Repairs OCR glyphs only inside a product's explicit size, never monetary fields. */
final class ReceiptPresentationNormalizer {
    private static final Pattern SIZE = Pattern.compile("(?i)(?<![\\p{L}\\d])([0-9o]+)\\s*(ml|gr|g[fe]|kg|ke|lt|un|um)(?![\\p{L}\\d])");
    private ReceiptPresentationNormalizer() { }
    static String normalize(String text) {
        return SIZE.matcher(text).replaceAll(match -> {
            if (match.group(1).chars().noneMatch(Character::isDigit)) return match.group();
            String amount = match.group(1).replace('o','0').replace('O','0');
            String unit = match.group(2).toLowerCase(Locale.ROOT);
            if (amount.equals(match.group(1)) && !java.util.Set.of("ge", "gf", "ke", "um").contains(unit)) {
                return match.group();
            }
            unit = switch (unit) { case "ge", "gf" -> "gr"; case "ke" -> "kg"; case "um" -> "un"; default -> unit; };
            return amount + unit;
        });
    }
}
