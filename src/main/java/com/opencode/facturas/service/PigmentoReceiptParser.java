package com.opencode.facturas.service;

import com.opencode.facturas.model.ReceiptDiscount;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lectura del formato de Pigmento: código, descripción, alícuota e importe. */
final class PigmentoReceiptParser {
    private static final Pattern TAX_RATE = Pattern.compile("(?<![\\d.,])\\(?\\s*21[.,]00(?!\\d)\\s*\\)?");
    private static final Pattern CODE = Pattern.compile("^\\d{7,14}(?:\\s|$)");
    private static final Pattern NEGATIVE_AMOUNT = Pattern.compile("-\\s*(\\d+(?:[.,]\\d{3})*[.,]\\d{2})(?!\\d)");
    private static final Pattern SIZE = Pattern.compile("(?i)\\b([0-9][0-9o]*)\\s*(ml?|gr?)\\b");

    private final ReceiptLineAnalyzer analyzer;
    private final ReceiptAmounts amounts;

    PigmentoReceiptParser(ReceiptLineAnalyzer analyzer, ReceiptAmounts amounts) {
        this.analyzer = analyzer;
        this.amounts = amounts;
    }

    boolean supports(String storeName) {
        return analyzer.normalize(storeName).contains("pigmento");
    }

    ParseResult parse(List<String> lines, Map<Integer, Boolean> uncertainByLine) {
        List<Candidate> candidates = new ArrayList<>();
        List<ReceiptDiscount> discounts = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String pendingPrice = "";
        boolean pendingUncertain = false;

        for (int index = 0; index < lines.size(); index++) {
            String source = analyzer.clean(lines.get(index)).replace('−', '-');
            String normalized = analyzer.normalize(source);
            if (isEnd(normalized)) break;

            if (normalized.contains("bonific")) {
                String block = source;
                boolean uncertain = uncertainByLine.getOrDefault(index, false);
                // La bonificación puede estar dividida en título, concepto e importe.
                while (!NEGATIVE_AMOUNT.matcher(block).find() && index + 1 < lines.size()) {
                    String next = analyzer.clean(lines.get(index + 1)).replace('−', '-');
                    String nextNormalized = analyzer.normalize(next);
                    if (nextNormalized.contains("bonific") || isEnd(nextNormalized)
                            || (!nextNormalized.matches("(?:2 ?x ?1|oferta).*") && !withoutTax(next).isBlank()
                            && !analyzer.isMoneyValue(next.replaceFirst("^-\\s*", "")))) break;
                    block += " " + next;
                    index++;
                    uncertain |= uncertainByLine.getOrDefault(index, false);
                }
                Matcher negative = NEGATIVE_AMOUNT.matcher(block);
                if (negative.find()) {
                    String concept = analyzer.normalize(block).matches(".*2 ?x ?1.*") ? "Bonificación 2x1"
                            : analyzer.normalize(block).contains("oferta") ? "Bonificación Oferta" : "Bonificación";
                    discounts.add(new ReceiptDiscount(concept, amounts.format(-amounts.parse(negative.group(1)))));
                    if (uncertain) warnings.add("Bonificación con lectura OCR dudosa: " + block);
                } else {
                    warnings.add("Bonificación sin importe negativo legible: " + block);
                }
                pendingPrice = "";
                continue;
            }

            String line = withoutTax(source);
            if (CODE.matcher(line).find()) {
                line = CODE.matcher(line).replaceFirst("").trim();
                pendingPrice = analyzer.lastMoneyValue(line).orElse("");
                pendingUncertain = uncertainByLine.getOrDefault(index, false);
                if (line.chars().noneMatch(Character::isLetter)) continue;
            }
            if (line.isBlank() || analyzer.shouldSkip(analyzer.normalize(line))) continue;
            if (NEGATIVE_AMOUNT.matcher(line).find()) {
                pendingPrice = "";
                continue;
            }

            String description = analyzer.removeMoneyValues(line).replaceAll("[()]", " ").trim();
            if (!analyzer.isLikelyProduct(description, normalized)) continue;
            Optional<String> price = analyzer.lastMoneyValue(line);
            boolean ambiguous = uncertainByLine.getOrDefault(index, false)
                    || analyzer.isAmbiguous(line, analyzer.normalize(line));
            if (price.isEmpty() && !pendingPrice.isBlank()) {
                price = Optional.of(pendingPrice);
                ambiguous |= pendingUncertain;
            }
            if (price.isEmpty() && index + 1 < lines.size()) {
                String next = withoutTax(analyzer.clean(lines.get(index + 1)));
                if (analyzer.isMoneyValue(next)) {
                    price = Optional.of(next);
                    ambiguous = true; // Asociación textual entre filas separadas.
                    index++;
                }
            }
            pendingPrice = "";
            if (price.isPresent()) {
                candidates.add(new Candidate(description, price.get(), ambiguous, source));
            } else if (TAX_RATE.matcher(source).find()) {
                warnings.add("Producto Pigmento sin precio legible: " + source);
            }
        }
        return new ParseResult(candidates, discounts, warnings);
    }

    private String withoutTax(String line) {
        return TAX_RATE.matcher(line).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }

    private boolean isEnd(String normalized) {
        return normalized.matches("^(?:total|t0tal|tota1|t0ta1|totai|subtotal|a pagar|recibi\\w*|cambio"
                + "|electron debito|transparencia fiscal|iva contenido|puntos club|consulta tus puntos)\\b.*");
    }

    String expandDescription(String description, String brand) {
        if (brand.equalsIgnoreCase("Pantene")) {
            description = description.replaceAll("(?i)\\bsh[-/]", "Shampoo ")
                    .replaceAll("(?i)\\bac/", "Acondicionador ")
                    .replaceAll("(?i)\\bsolu/restaurac(?:i[oó]n)?\\b", "Solu/Restauración");
        } else if (brand.equalsIgnoreCase("Nivea")) {
            description = description.replaceAll("(?i)\\bfac-cr\\b", "Facial Crema")
                    .replaceAll("(?i)\\bcuid/nutriti\\b", "Cuidado/Nutritiva")
                    .replaceAll("(?i)\\bhid/exp\\b", "Hid/Exp");
        } else if (brand.equalsIgnoreCase("Colgate")) {
            description = description.replaceAll("(?i)\\btriple\\s*acci[oó]n\\b", "Triple Acción");
        }
        Matcher size = SIZE.matcher(description);
        StringBuffer expanded = new StringBuffer();
        while (size.find()) {
            String number = size.group(1).replace('O', '0').replace('o', '0');
            String unit = size.group(2).equalsIgnoreCase("g") || size.group(2).equalsIgnoreCase("gr") ? "g" : "ml";
            size.appendReplacement(expanded, Matcher.quoteReplacement(number + " " + unit));
        }
        size.appendTail(expanded);
        return expanded.toString().replaceAll("\\s+", " ").trim();
    }

    record Candidate(String description, String price, boolean ambiguous, String sourceLine) {}
    record ParseResult(List<Candidate> candidates, List<ReceiptDiscount> discounts, List<String> warnings) {}
}
