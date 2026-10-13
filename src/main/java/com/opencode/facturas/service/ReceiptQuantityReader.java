package com.opencode.facturas.service;

import java.util.Optional;
import java.util.regex.Pattern;

/** Associates a standalone multiplier with the following printed line amount. */
final class ReceiptQuantityReader {
    private static final Pattern EXPLICIT = Pattern.compile("(?i)^\\s*(\\d+(?:[.,]\\d{1,4})?)\\s*x\\s*\\$?\\s*(\\d+(?:[.,]\\d+)*)\\s*$");
    private static final Pattern JOINED = Pattern.compile("^\\d{4,}[.,]\\d{2}$");
    private static final Pattern JOINED_WEIGHT = Pattern.compile("^(\\d{1,3}[.,])(\\d{5,})[.,](\\d{2})$");

    private ReceiptQuantityReader() { }

    static Optional<Match> read(String line, String lineAmount, ReceiptAmounts amounts) {
        var explicit = EXPLICIT.matcher(line);
        double total = amounts.parse(lineAmount);
        if (explicit.matches()) {
            String quantity = explicit.group(1);
            double q = amounts.parseQuantity(quantity).orElse(0.0);
            if (q <= 0) return Optional.empty();
            boolean consistent = amounts.isConsistent(amounts.parse(explicit.group(2)), q, total);
            // A clearly printed fractional weight remains useful when OCR damages
            // the unit amount: calculate from the current printed product amount.
            if (consistent || quantity.matches("\\d+[.,]\\d+")) return Optional.of(new Match(quantity, !consistent));
        }
        String compact = line.replace(" ", "");
        var weight = JOINED_WEIGHT.matcher(compact);
        if (weight.matches()) {
            Match selected = null;
            for (int precision = 1; precision <= 4 && precision < weight.group(2).length(); precision++) {
                String quantity = weight.group(1) + weight.group(2).substring(0, precision);
                String integer = weight.group(2).substring(precision);
                if (integer.startsWith("0")) continue;
                double q = amounts.parseQuantity(quantity).orElse(0.0);
                if (q > 0 && amounts.isConsistent(amounts.parse(integer + "." + weight.group(3)), q, total)) {
                    if (selected != null) return Optional.empty();
                    selected = new Match(quantity, true);
                }
            }
            return Optional.ofNullable(selected);
        }
        if (!JOINED.matcher(compact).matches()) return Optional.empty();
        Match selected = null;
        for (int digits = 1; digits <= 2; digits++) {
            int q = Integer.parseInt(compact.substring(0, digits));
            if (q < 2 || q > 99 || compact.charAt(digits) == '0') continue;
            String unit = compact.substring(digits);
            if (amounts.isConsistent(amounts.parse(unit), q, total)) {
                if (selected != null) return Optional.empty();
                selected = new Match(String.valueOf(q), true);
            }
        }
        return Optional.ofNullable(selected);
    }

    record Match(String quantity, boolean uncertain) { }
}
