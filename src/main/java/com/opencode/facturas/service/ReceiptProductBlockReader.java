package com.opencode.facturas.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Lee bloques de descripción, cantidad e importes sin depender del comercio. */
final class ReceiptProductBlockReader {
    private static final Pattern CODE = Pattern.compile("^\\d{7,14}(?:\\s+|$)");
    private static final Pattern JOINED_CODE = Pattern.compile("(?i)^(?:\\d{13}|\\d{8})(\\d{1,4}(?:[.,]\\d{1,4})?\\s*[x×].*)$");
    private static final Pattern MULTIPLIER = Pattern.compile("(?i)^(\\d{1,4}(?:[.,]\\d{1,4})?)\\s*[x×]\\s*(.*)$");
    private static final Pattern FOLLOWING_AMOUNTS = Pattern.compile(
            "(?i)^\\d{1,4}(?:[.,]\\d{1,4})?\\s*(?:[x×%]\\s*\\$?|\\$)\\s*\\d.*$");
    private static final Pattern ARTICLE_CODE = Pattern.compile("(?i)^[a-z]{1,8}\\d[a-z\\d]{2,}(?=\\s|$)");
    private static final Pattern TAX = Pattern.compile("\\s*\\((?:(?:21|27|0)(?:[.,]00)?|10[.,]50)\\)?\\s*$");

    private final ReceiptLineAnalyzer analyzer;
    private final ReceiptAmounts amounts;

    ReceiptProductBlockReader(ReceiptLineAnalyzer analyzer, ReceiptAmounts amounts) {
        this.analyzer = analyzer;
        this.amounts = amounts;
    }

    Optional<Block> read(List<String> lines, int start) {
        String header = analyzer.clean(lines.get(start));
        Optional<Block> descriptionFirst = readDescriptionFirst(lines, start, header);
        if (descriptionFirst.isPresent()) return descriptionFirst;
        Optional<String> headerPayload = payload(header);
        if (headerPayload.isEmpty()) return Optional.empty();
        String payload = headerPayload.get();
        if (!numericPayload(payload)) return Optional.empty();

        int end = start;
        // Algunos OCR separan el código, el multiplicador y el importe en tres filas.
        while (end + 1 < lines.size() && end < start + 2) {
            String next = analyzer.clean(lines.get(end + 1));
            if (payload(next).isPresent() || !numericPayload(next) || next.isBlank()) break;
            payload = (payload + " " + next).trim();
            end++;
        }

        String rawDescription = end + 1 < lines.size() ? analyzer.clean(lines.get(end + 1)) : "";
        String description = withoutTax(rawDescription);
        // Un teléfono anterior al comercio no es un bloque de producto sin precio.
        if (payload.isBlank() && !TAX.matcher(rawDescription).find()
                && !header.matches("\\d{8}|\\d{13,14}")) return Optional.empty();
        if (description.isBlank() || analyzer.containsMoney(description)
                || !analyzer.isLikelyProduct(description, analyzer.normalize(description))) {
            // Un código sin importes puede preceder a una descripción que ya tiene su precio.
            if (payload.isBlank()) return Optional.empty();
            return Optional.of(new Block("", "", "1", false, true, end,
                    List.of("Extracción parcial: código con importes sin descripción asociable: " + header)));
        }
        end++;
        if (end + 1 < lines.size() && withoutTax(lines.get(end + 1)).isBlank()) end++;

        return Optional.of(amountBlock(description, rawDescription, payload, end, false));
    }

    private Optional<Block> readDescriptionFirst(List<String> lines, int start, String description) {
        if (start + 1 >= lines.size() || analyzer.containsMoney(description)
                || ARTICLE_CODE.matcher(description).find()
                || !analyzer.isLikelyDescriptionOnly(description, analyzer.normalize(description))
                || !analyzer.isLikelyProduct(description, analyzer.normalize(description))) return Optional.empty();
        String payload = analyzer.clean(lines.get(start + 1));
        if (!FOLLOWING_AMOUNTS.matcher(payload).matches()) return Optional.empty();
        boolean damagedMultiplier = payload.contains("%");
        payload = payload.replaceFirst("%", "x")
                .replaceFirst("^(\\d{1,4}(?:[.,]\\d{1,4})?)\\s*(?=\\$)", "$1 x ");
        if (!numericPayload(payload)) return Optional.empty();
        int end = start + 1;
        boolean articleCode = false;
        // El importe puede quedar en la fila del código por la inclinación de la foto.
        while (end + 1 < lines.size() && end < start + 3) {
            String next = analyzer.clean(lines.get(end + 1));
            var code = ARTICLE_CODE.matcher(next);
            boolean hasCode = code.find();
            String numeric = hasCode ? next.substring(code.end()).trim() : next;
            if (!numericPayload(numeric) || (!hasCode && !analyzer.containsMoney(numeric))) break;
            payload = (payload + " " + numeric).trim();
            end++;
            if (hasCode) { articleCode = true; break; }
        }
        // Una sola cantidad/unitario también puede pertenecer al producto siguiente.
        if (!articleCode && analyzer.moneyValues(payload).size() < 2) return Optional.empty();
        return Optional.of(amountBlock(description, description, payload, end, damagedMultiplier));
    }

    private Block amountBlock(String description, String rawDescription, String payload, int end,
                              boolean damagedMultiplier) {
        List<String> warnings = new ArrayList<>();
        String quantity = "1";
        var multiplier = MULTIPLIER.matcher(payload);
        boolean explicitQuantity = multiplier.matches();
        if (explicitQuantity) {
            quantity = multiplier.group(1);
            payload = multiplier.group(2);
        }
        List<String> prices = analyzer.moneyValues(payload);
        boolean validQuantity = amounts.parseQuantity(quantity).filter(q -> q > 0).isPresent();
        // Puede faltar el separador del unitario (120000), con importe final legible.
        String residue = analyzer.removeMoneyValues(payload).trim();
        if (explicitQuantity && validQuantity && prices.size() == 1 && residue.matches("\\d+")
                && payload.endsWith(prices.get(0))) {
            warnings.add("Revisá el precio unitario OCR; se conservó el importe de línea: " + description);
            return new Block(description, prices.get(0), quantity, false, true, end, warnings);
        }
        boolean validPrices = !prices.isEmpty() && prices.size() <= (explicitQuantity ? 2 : 1)
                && residue.isBlank();
        if (!validQuantity || !validPrices) {
            warnings.add("Extracción parcial: producto sin precio o cantidad legibles: " + description);
            return new Block(description, "", validQuantity ? quantity : "1", false, true, end, warnings);
        }

        String price = prices.get(prices.size() - 1);
        boolean priceIsUnit = explicitQuantity && prices.size() == 1;
        boolean damagedTax = TAX.matcher(rawDescription).find() && !rawDescription.endsWith(")");
        boolean ambiguous = damagedMultiplier || damagedTax;
        if (damagedTax) warnings.add("Revisá la descripción OCR del bloque: " + description);
        if (damagedMultiplier) warnings.add("Revisá la cantidad OCR del bloque: " + description);
        if (explicitQuantity && prices.size() == 2) {
            double expected = amounts.parse(prices.get(0)) * amounts.parseQuantity(quantity).orElseThrow();
            // Con ambos importes impresos, exigir precisión monetaria; no usar la
            // tolerancia amplia de las heurísticas que reconstruyen números OCR.
            if (Math.abs(expected - amounts.parse(price)) <= .05) {
                price = prices.get(0);
                priceIsUnit = true;
            } else {
                ambiguous = true;
                warnings.add("Revisá cantidad, precio unitario e importe del bloque: " + description);
            }
        }
        return new Block(description, price, quantity, priceIsUnit, ambiguous, end, warnings);
    }

    private boolean numericPayload(String value) {
        return value.matches("[\\d\\s$.,xX×]*");
    }

    private Optional<String> payload(String header) {
        var code = CODE.matcher(header);
        if (code.find()) return Optional.of(header.substring(code.end()).trim());
        // Longitudes EAN habituales: no interpretar todo el código como cantidad.
        var joined = JOINED_CODE.matcher(header);
        return joined.matches() ? Optional.of(joined.group(1)) : Optional.empty();
    }

    private String withoutTax(String value) {
        return TAX.matcher(value).replaceFirst("").trim();
    }

    record Block(String description, String rawPrice, String quantity, boolean priceIsUnit,
                 boolean ambiguous, int endIndex, List<String> warnings) {
        String line() { return (description + " " + rawPrice).trim(); }
    }
}
