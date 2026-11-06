package com.opencode.facturas.service;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Optional;
import java.math.BigDecimal;
import java.math.RoundingMode;

final class ReceiptAmounts {

    private static final Locale LOCALE_AR = Locale.forLanguageTag("es-AR");

    static BigDecimal decimal(String value, boolean price) {
        String clean = value == null ? "" : value.replaceAll("\\s", "");
        if (price) clean = clean.replaceFirst("^\\$", "");
        if (clean.length() > 64) throw new IllegalArgumentException("Cantidad o precio demasiado largo.");
        if (price && clean.matches("[+-]?\\d{1,3}(\\.\\d{3})+(,\\d+)?")) {
            clean = clean.replace(".", "").replace(',', '.');
        } else if (clean.matches("[+-]?(?:\\d+(?:[.,]\\d+)?|[.,]\\d+)")) {
            clean = clean.replace(',', '.');
        } else {
            throw new IllegalArgumentException("Cantidad y precio deben ser números válidos; usá 0,875 para cantidades y 1.234,56 para precios.");
        }
        return new BigDecimal(clean).stripTrailingZeros();
    }

    String format(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    double parse(String value) {
        boolean hasCurrencySymbol = value.contains("$");
        String cleaned = value.replace("$", "").replace(" ", "").trim();
        int lastComma = cleaned.lastIndexOf(',');
        int lastDot = cleaned.lastIndexOf('.');
        int decimalSeparator = Math.max(lastComma, lastDot);
        if (decimalSeparator < 0) {
            return Double.parseDouble(cleaned.replaceAll("[^0-9-]", ""));
        }

        String integerPart = cleaned.substring(0, decimalSeparator).replaceAll("[^0-9-]", "");
        String decimalPart = cleaned.substring(decimalSeparator + 1).replaceAll("[^0-9]", "");
        if (hasCurrencySymbol && decimalPart.length() == 5) {
            return Double.parseDouble((integerPart + decimalPart.substring(0, 3)) + "." + decimalPart.substring(3));
        }
        if (hasCurrencySymbol && decimalPart.length() == 3) {
            return Double.parseDouble((integerPart + decimalPart).replaceAll("[^0-9-]", ""));
        }
        if (decimalPart.length() > 2) {
            decimalPart = decimalPart.substring(0, 2);
        }
        return Double.parseDouble(integerPart + "." + decimalPart);
    }

    Optional<Double> parseQuantity(String quantity) {
        try {
            return Optional.of(Double.parseDouble(quantity.replace(',', '.')));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    String format(double amount) {
        DecimalFormat format = new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(LOCALE_AR));
        return format.format(amount);
    }

    String formatQuantity(double quantity) {
        double rounded = Math.rint(quantity);
        if (Math.abs(quantity - rounded) < 0.000001) {
            return String.valueOf((long) rounded);
        }
        DecimalFormat format = new DecimalFormat("0.####", DecimalFormatSymbols.getInstance(LOCALE_AR));
        return format.format(quantity);
    }

    boolean isConsistent(double unitPrice, double quantity, double total) {
        double expected = unitPrice * quantity;
        double tolerance = Math.max(0.05, Math.abs(total) * 0.03);
        return Math.abs(expected - total) <= tolerance;
    }
}
