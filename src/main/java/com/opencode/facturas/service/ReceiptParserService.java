package com.opencode.facturas.service;

import com.opencode.facturas.model.ExtractResponse;
import com.opencode.facturas.model.ReceiptItem;
import com.opencode.facturas.util.DelimitedExporter;
import org.springframework.stereotype.Service;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ReceiptParserService {

    private final StoreNameMapper storeNameMapper;
    private final BrandCatalog brandCatalog;

    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{1,2}\\s*[/-]\\s*\\d{1,2}(?:\\s*[/-]\\s*\\d{2,4})?)");
    private static final Pattern MONEY_PATTERN = Pattern.compile("(?:\\$\\s*\\d+[\\.,]\\d{5}|\\$\\s*\\d+(?:[\\.,]\\d{3})*(?:[\\.,]\\d{2})?|\\d+(?:[\\.,]\\d{3})*[\\.,]\\d{2})(?!\\d)");
    private static final Pattern PRICE_AT_END_PATTERN = Pattern.compile("(.+?)\\s+(\\d+(?:[\\.,]\\d{3})*[\\.,]\\d{2})$");
    private static final Pattern PUNTA_DE_AGUA_CREMOSO_PATTERN = Pattern.compile("(?i).*?(PUNTA\\s+DE\\s+AGUA\\s+CR\\s*\\*?\\s*1UN)\\s+(\\d+(?:[\\.,]\\d{3})*[\\.,]\\d{2}).*");
    private static final Pattern PRICE_ONLY_PATTERN = Pattern.compile("^(\\d+(?:[\\.,]\\d{3})*[\\.,]\\d{2})$");
    private static final Pattern MULTIPLIER_PATTERN = Pattern.compile("(?i)\\b(\\d+)\\s*[xX]\\s*(\\d+(?:[\\.,]\\d{3})*[\\.,]\\d{2})\\b");
    private static final List<String> STOP_WORDS = List.of("subtotal", "total", "recibi", "cambio", "tarjeta", "efectivo");
    private static final List<String> METADATA_WORDS = List.of(
            "cuit", "direccion", "responsable", "consumidor", "actividad", "fecha", "hora", "nro", "ing.", "iva", "cod.", "pv", "tique",
            "seshia", "orientacion", "transparencia", "fiscal", "regimen", "afip", "cliente", "cantidad", "descripcion", "importe",
            "whatsapp", "ticket", "lunes", "sabados", "gracias", "onsumidor"
    );
    private static final List<ProductRule> PRODUCT_RULES = List.of(
            new ProductRule("Salchichas", List.of("salchi", "salchich")),
            new ProductRule("Galletitas", List.of("gallet", "gal let", "gal.let", "galleta")),
            new ProductRule("Pure de tomates", List.of("pure tom", "pure tomate", "pure de tom")),
            new ProductRule("Papel higienico", List.of("papel")),
            new ProductRule("Panuelos", List.of("panue", "panuel")),
            new ProductRule("Ravioles", List.of("raviol", "rayiol", "rayioles")),
            new ProductRule("Limpiador", List.of("limpi")),
            new ProductRule("Gelatina", List.of("gelleti", "gelletid", "gelat")),
            new ProductRule("Manteca", List.of("manteca")),
            new ProductRule("Yogur", List.of("yogur")),
            new ProductRule("Azucar", List.of("azucar")),
            new ProductRule("Suavizante", List.of("suavi")),
            new ProductRule("Jabon", List.of("jabon")),
            new ProductRule("Manzanilla", List.of("manzan", "manzani", "manzanilla")),
            new ProductRule("Mix de semillas", List.of("mix de semillas", "semillas")),
            new ProductRule("Sal", List.of("sal")),
            new ProductRule("Pan multicereal", List.of("pan multicerea", "pan multicereal", "multicerea")),
            new ProductRule("Bolsa", List.of("bolsa")),
            new ProductRule("Cebolla", List.of("cebolla")),
            new ProductRule("Agua mineral", List.of("agua mineral")),
            new ProductRule("Papas", List.of("papas")),
            new ProductRule("Harina", List.of("harina")),
            new ProductRule("Leche", List.of("leche")),
            new ProductRule("Queso rallado", List.of("queso rallado"))
    );
    private static final Locale LOCALE_AR = Locale.forLanguageTag("es-AR");
    private static final DecimalFormat AMOUNT_FORMAT = new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(LOCALE_AR));

    public ReceiptParserService() {
        this(StoreNameMapper.empty(), new BrandCatalog(new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    public ReceiptParserService(StoreNameMapper storeNameMapper) {
        this(storeNameMapper, new BrandCatalog(new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    public ReceiptParserService(StoreNameMapper storeNameMapper, BrandCatalog brandCatalog) {
        this.storeNameMapper = storeNameMapper;
        this.brandCatalog = brandCatalog;
    }

    public ExtractResponse parse(String rawText) {
        List<String> lines = rawText.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();

        String date = normalizeDate(extractDate(lines).orElse(""));
        String storeName = storeNameMapper.resolve(lines, detectStoreName(lines));
        List<ReceiptItem> items = isPedidosYa(lines) ? extractPedidosYaItems(lines, storeName, date) : extractItems(lines, storeName, date);

        return new ExtractResponse(
                storeName,
                date,
                items.size(),
                DelimitedExporter.toPipeSeparated(items),
                DelimitedExporter.toTabSeparated(items),
                DelimitedExporter.toTabSeparatedWithoutHeader(items),
                rawText,
                items
        );
    }

    private List<ReceiptItem> extractItems(List<String> lines, String storeName, String date) {
        List<ReceiptItem> items = new ArrayList<>();

        for (int index = 0; index < lines.size(); index++) {
            String line = cleanOcrNoise(lines.get(index));
            String normalized = normalizeForDetection(line);
            if (shouldSkipLine(normalized)) {
                continue;
            }

            if (isLikelyDescriptionOnly(line, normalized) && index + 1 < lines.size()) {
                String nextLine = cleanOcrNoise(lines.get(index + 1));
                if (PRICE_ONLY_PATTERN.matcher(nextLine).matches() && isLikelyProductLine(line, normalized)) {
                    items.add(buildItem(line, nextLine, storeName, date));
                    index++;
                    continue;
                }
            }

            if (PRICE_ONLY_PATTERN.matcher(line).matches()) {
                continue;
            }

            Matcher puntaDeAguaMatcher = PUNTA_DE_AGUA_CREMOSO_PATTERN.matcher(line);
            if (puntaDeAguaMatcher.matches()) {
                items.add(buildItem(puntaDeAguaMatcher.group(1), puntaDeAguaMatcher.group(2), storeName, date));
                continue;
            }

            Optional<ParsedItemLine> parsedItemLine = parseItemLineWithMoney(line);
            if (parsedItemLine.isPresent() && isLikelyProductLine(parsedItemLine.get().description(), normalized)) {
                items.add(buildItem(parsedItemLine.get().description(), parsedItemLine.get().price(), storeName, date));
                continue;
            }

            Matcher matcher = PRICE_AT_END_PATTERN.matcher(line);
            if (!matcher.find()) {
                continue;
            }

            String rawDescription = matcher.group(1).trim();
            String rawPrice = matcher.group(2).trim();
            if (rawDescription.length() < 3) {
                continue;
            }

            if (!isLikelyProductLine(rawDescription, normalized)) {
                continue;
            }

            items.add(buildItem(rawDescription, rawPrice, storeName, date));
        }

        return items;
    }

    private ReceiptItem buildItem(String rawDescription, String rawPrice, String storeName, String date) {
        int quantity = detectQuantity(rawDescription);
        double totalPrice = parseAmount(rawPrice);
        double unitPrice = quantity > 0 ? totalPrice / quantity : totalPrice;
        String cleanedDescription = beautifyDescription(rawDescription);
        BrandMatch brandMatch = detectBrand(cleanedDescription);
        String brand = brandMatch.brand();
        String descriptionWithoutBrand = expandProductDescription(removeBrandFromDescription(cleanedDescription, brandMatch), brand);

        return new ReceiptItem(
                descriptionWithoutBrand,
                brand,
                storeName,
                "Supermercado",
                String.valueOf(quantity),
                formatAmount(unitPrice),
                normalizeDate(date),
                ""
        );
    }

    private boolean isPedidosYa(List<String> lines) {
        return lines.stream()
                .map(this::normalizeForDetection)
                .anyMatch(line -> line.contains("pedidosya") || line.contains("pedidos ya"));
    }

    private List<ReceiptItem> extractPedidosYaItems(List<String> lines, String storeName, String date) {
        List<ReceiptItem> items = new ArrayList<>();

        for (int index = 0; index < lines.size(); index++) {
            String line = cleanOcrNoise(lines.get(index));
            String normalized = normalizeForDetection(line);
            Optional<PedidosYaInlineItem> inlineItem = parsePedidosYaInlineItem(line, normalized);
            if (inlineItem.isPresent()) {
                PedidosYaInlineItem item = inlineItem.get();
                items.add(buildPedidosYaItem(item.description(), item.price(), item.quantity(), storeName, date));
                continue;
            }

            if (!isLikelyPedidosYaProductLine(line, normalized)) {
                continue;
            }

            PedidosYaProductLine productLine = splitPedidosYaQuantity(line);
            Optional<String> price = findPedidosYaPriceInFollowingLines(lines, index + 1);
            if (price.isEmpty()) {
                continue;
            }

            String quantity = productLine.quantity().orElse(null);
            if (quantity == null) {
                quantity = findPedidosYaQuantityInFollowingLines(lines, index + 1).orElse("1");
            }
            items.add(buildPedidosYaItem(productLine.description(), price.get(), quantity, storeName, date));
        }

        return items;
    }

    private boolean isLikelyPedidosYaProductLine(String line, String normalized) {
        if (line.length() < 5 || containsMetadata(normalized)) {
            return false;
        }
        if (normalized.matches("[0-9 kg]+")) {
            return false;
        }
        if (normalized.contains("cambio de peso") || normalized.contains("off") || normalized.contains("market")) {
            return false;
        }
        return line.chars().filter(Character::isLetter).count() >= 4;
    }

    private Optional<PedidosYaInlineItem> parsePedidosYaInlineItem(String line, String normalized) {
        if (containsMetadata(normalized)
                || normalized.contains("off")
                || normalized.contains("market")
                || normalized.contains("subtotal")
                || normalized.contains("total")) {
            return Optional.empty();
        }
        if (line.chars().filter(Character::isLetter).count() < 4) {
            return Optional.empty();
        }

        Matcher matcher = MONEY_PATTERN.matcher(line);
        List<String> prices = new ArrayList<>();
        while (matcher.find()) {
            prices.add(matcher.group());
        }
        if (prices.isEmpty()) {
            return Optional.empty();
        }

        boolean hasUnitQuantity = Pattern.compile("(?i)\\b\\d+x\\s*$").matcher(line).find();
        String quantity = extractPedidosYaQuantity(line).orElse("1");
        String description = MONEY_PATTERN.matcher(line).replaceAll(" ")
                .replaceAll("(?i)^\\s*\\d+\\s+", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (hasUnitQuantity) {
            description = description.replaceAll("(?i)\\b\\d+x\\b", " ");
        } else {
            description = description
                    .replaceAll("(?i)\\b\\d+g?\\s*\\d+(?:[\\.,]\\d+)?\\s*kg\\b", " ")
                    .replaceAll("(?i)\\b\\d+(?:[\\.,]\\d+)?\\s*kg\\b", " ");
        }
        description = description.replaceAll("\\s+", " ").trim();

        if (description.length() < 4) {
            return Optional.empty();
        }
        return Optional.of(new PedidosYaInlineItem(description, prices.get(0), quantity));
    }

    private Optional<String> findPedidosYaPriceInFollowingLines(List<String> lines, int startIndex) {
        for (int i = startIndex; i < Math.min(lines.size(), startIndex + 3); i++) {
            Matcher matcher = MONEY_PATTERN.matcher(lines.get(i));
            if (matcher.find()) {
                return Optional.of(matcher.group());
            }
        }
        return Optional.empty();
    }

    private Optional<String> findPedidosYaQuantityInFollowingLines(List<String> lines, int startIndex) {
        for (int i = startIndex; i < Math.min(lines.size(), startIndex + 4); i++) {
            Optional<String> quantity = extractPedidosYaQuantity(lines.get(i));
            if (quantity.isPresent()) {
                return quantity;
            }
        }
        return Optional.empty();
    }

    private PedidosYaProductLine splitPedidosYaQuantity(String line) {
        Matcher unitsMatcher = Pattern.compile("(?i)\\s+(\\d+)x\\s*$").matcher(line);
        if (unitsMatcher.find()) {
            return new PedidosYaProductLine(line.substring(0, unitsMatcher.start()).trim(), Optional.of(unitsMatcher.group(1)));
        }
        return new PedidosYaProductLine(line, Optional.empty());
    }

    private Optional<String> extractPedidosYaQuantity(String line) {
        Matcher unitMatcher = Pattern.compile("(?i)\\b(\\d+)x\\s*$").matcher(line);
        if (unitMatcher.find()) {
            return Optional.of(unitMatcher.group(1));
        }

        Matcher kgMatcher = Pattern.compile("(?i)(\\d+(?:[\\.,]\\d+)?)\\s*kg\\b").matcher(line);
        String lastKg = null;
        while (kgMatcher.find()) {
            lastKg = kgMatcher.group(1).replace(',', '.');
        }
        if (lastKg != null) {
            return Optional.of(lastKg);
        }

        return Optional.empty();
    }

    private ReceiptItem buildPedidosYaItem(String rawDescription, String rawPrice, String quantity, String storeName, String date) {
        String cleanedDescription = beautifyDescription(rawDescription);
        BrandMatch brandMatch = brandCatalog.findAnywhereIn(cleanedDescription)
                .map(match -> new BrandMatch(match.brand(), match.normalizedAlias()))
                .orElse(new BrandMatch("Generico", ""));
        String brand = brandMatch.brand();
        String descriptionWithoutBrand = expandProductDescription(removeBrandFromDescription(cleanedDescription, brandMatch), brand);
        double totalPrice = parseAmount(rawPrice);
        double numericQuantity = parseQuantity(quantity).orElse(1.0);

        return new ReceiptItem(
                descriptionWithoutBrand,
                brand,
                storeName,
                "Supermercado",
                quantity,
                formatAmount(totalPrice / numericQuantity),
                normalizeDate(date),
                ""
        );
    }

    private Optional<Double> parseQuantity(String quantity) {
        try {
            return Optional.of(Double.parseDouble(quantity.replace(',', '.')));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private Optional<String> extractDate(List<String> lines) {
        for (int index = 0; index < lines.size(); index++) {
            String normalized = normalizeForDetection(lines.get(index));
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
        Matcher matcher = DATE_PATTERN.matcher(normalizeDateSeparators(line));
        while (matcher.find()) {
            String date = matcher.group(1).replaceAll("\\s+", "");
            if (isLikelyReceiptDate(date)) {
                return Optional.of(date);
            }
        }
        return Optional.empty();
    }

    private String normalizeDateSeparators(String value) {
        return value.replace('O', '0').replace('o', '0').replace('|', '/');
    }

    private boolean isLikelyReceiptDate(String rawDate) {
        try {
            LocalDate parsed = parseDate(rawDate);
            return parsed.getYear() >= 2015;
        } catch (DateTimeParseException ex) {
            return false;
        }
    }

    private String detectStoreName(List<String> lines) {
        String fallback = "Compra sin identificar";

        for (int i = 0; i < Math.min(lines.size(), 14); i++) {
            String line = cleanOcrNoise(lines.get(i));
            String normalized = normalizeForDetection(line);

            if (normalized.contains("supermercado") && i + 1 < lines.size()) {
                String next = cleanOcrNoise(lines.get(i + 1));
                if (!containsMetadata(normalizeForDetection(next))) {
                    return toTitleCase(next);
                }
            }

            if (!containsMetadata(normalized) && line.length() > 6 && !line.matches(".*\\d.*")) {
                fallback = toTitleCase(line);
                break;
            }
        }

        return fallback;
    }

    private boolean shouldSkipLine(String normalized) {
        if (normalized.isBlank()) {
            return true;
        }
        if (normalized.matches("v ?\\d+(?: \\d+)?")) {
            return true;
        }
        if (containsMetadata(normalized)) {
            return true;
        }
        return STOP_WORDS.stream().anyMatch(normalized::contains);
    }

    private boolean containsMetadata(String normalized) {
        return METADATA_WORDS.stream().anyMatch(normalized::contains);
    }

    private boolean isLikelyDescriptionOnly(String line, String normalized) {
        if (line.length() < 5 || containsMetadata(normalized)) {
            return false;
        }
        if (STOP_WORDS.stream().anyMatch(normalized::contains)) {
            return false;
        }
        return line.chars().filter(Character::isLetter).count() >= 4 && !PRICE_ONLY_PATTERN.matcher(line).matches();
    }

    private boolean isLikelyProductLine(String description, String normalizedLine) {
        String normalizedDescription = normalizeForDetection(description);
        if (containsMetadata(normalizedDescription) || STOP_WORDS.stream().anyMatch(normalizedDescription::contains)) {
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

    private Optional<ParsedItemLine> parseItemLineWithMoney(String line) {
        Matcher moneyMatcher = MONEY_PATTERN.matcher(line);
        List<String> prices = new ArrayList<>();
        while (moneyMatcher.find()) {
            prices.add(moneyMatcher.group());
        }
        if (prices.isEmpty() || line.chars().filter(Character::isLetter).count() < 3) {
            return Optional.empty();
        }

        String description = MONEY_PATTERN.matcher(line).replaceAll(" ")
                .replaceAll("(?i)^\\s*\\d+(?:[\\.,]\\d+)?\\s*x\\s*", "")
                .replaceAll("(?i)\\s+\\d+(?:[\\.,]\\d+)?\\s*x\\s*$", "")
                .replaceAll("\\b\\d+[\\.,]\\d{3,4}\\b", " ")
                .replaceAll("(?i)\\bprecio\\s+unit\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();

        if (description.length() < 3) {
            return Optional.empty();
        }

        return Optional.of(new ParsedItemLine(description, prices.get(prices.size() - 1)));
    }

    private int detectQuantity(String description) {
        Matcher multiplierMatcher = MULTIPLIER_PATTERN.matcher(description);
        if (multiplierMatcher.find()) {
            return Integer.parseInt(multiplierMatcher.group(1));
        }

        Matcher explicitQuantity = Pattern.compile("(?i)\\bx\\s*(\\d{1,2})\\b").matcher(description);
        if (explicitQuantity.find()) {
            return Integer.parseInt(explicitQuantity.group(1));
        }

        return 1;
    }

    private String beautifyDescription(String rawDescription) {
        String cleaned = cleanOcrNoise(rawDescription)
                .replace('*', ' ')
                .replace('_', ' ')
                .replace('.', ' ')
                .replaceAll("(?i)([a-z])x(\\d)", "$1 X$2")
                .replace("€", "C")
                .replace("§", "S")
                .replace("°", "o")
                .replaceAll("(?i)\\s*\\(?\\b21\\)?\\s*$", "")
                .replaceAll("\\s+", " ")
                .trim();

        return toTitleCase(cleaned);
    }

    private BrandMatch detectBrand(String description) {
        Optional<BrandMatch> knownBrand = brandCatalog.findIn(description)
                .map(match -> new BrandMatch(match.brand(), match.normalizedAlias()));
        if (knownBrand.isPresent()) {
            return knownBrand.get();
        }

        String[] parts = description.split(" ");
        if (parts.length == 0) {
            return new BrandMatch("Sin marca", "");
        }
        if (parts.length > 1 && parts[0].length() <= 3) {
            String brand = toTitleCase(parts[0] + " " + parts[1]);
            brandCatalog.remember(brand);
            return new BrandMatch(brand, normalizeForDetection(parts[0] + " " + parts[1]));
        }
        String brand = toTitleCase(parts[0]);
        brandCatalog.remember(brand);
        return new BrandMatch(brand, normalizeForDetection(parts[0]));
    }

    private String removeBrandFromDescription(String description, BrandMatch brandMatch) {
        if (brandMatch.normalizedAlias().isBlank() || brandMatch.brand().equals("Sin marca")) {
            return description;
        }

        List<String> words = new ArrayList<>(List.of(description.split(" ")));
        while (!words.isEmpty() && startsWithBrandAlias(String.join(" ", words), brandMatch.normalizedAlias())) {
            String currentPrefix = "";
            int wordsToRemove = 0;
            for (int i = 0; i < words.size(); i++) {
                currentPrefix = currentPrefix.isBlank() ? words.get(i) : currentPrefix + " " + words.get(i);
                wordsToRemove = i + 1;
                if (matchesBrandAlias(currentPrefix, brandMatch.normalizedAlias())) {
                    break;
                }
            }
            if (wordsToRemove <= 0) {
                break;
            }
            words = new ArrayList<>(words.subList(wordsToRemove, words.size()));
            break;
        }

        if (!words.isEmpty()) {
            String compactFirstWord = normalizeForDetection(words.get(0)).replace(" ", "");
            String compactAlias = brandMatch.normalizedAlias().replace(" ", "");
            if (compactFirstWord.startsWith(compactAlias) && compactFirstWord.length() > compactAlias.length()) {
                String suffix = words.get(0).substring(Math.min(compactAlias.length(), words.get(0).length()));
                if (suffix.isBlank()) {
                    words = new ArrayList<>(words.subList(1, words.size()));
                } else {
                    words.set(0, suffix);
                }
            }
        }

        String cleaned = String.join(" ", words).trim();
        return cleaned.isBlank() ? description : cleaned;
    }

    private boolean startsWithBrandAlias(String value, String normalizedAlias) {
        String normalizedValue = normalizeForDetection(value);
        return normalizedValue.startsWith(normalizedAlias)
                || normalizedValue.replace(" ", "").startsWith(normalizedAlias.replace(" ", ""));
    }

    private boolean matchesBrandAlias(String value, String normalizedAlias) {
        String normalizedValue = normalizeForDetection(value);
        return normalizedValue.equals(normalizedAlias)
                || normalizedValue.replace(" ", "").equals(normalizedAlias.replace(" ", ""));
    }

    private double parseAmount(String value) {
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
        String normalized = integerPart + "." + decimalPart;
        return Double.parseDouble(normalized);
    }

    private String normalizeDate(String rawDate) {
        if (rawDate == null || rawDate.isBlank()) {
            return "";
        }

        try {
            LocalDate parsed = parseDate(rawDate);
            return parsed.format(DateTimeFormatter.ofPattern("d/M/yyyy"));
        } catch (DateTimeParseException ex) {
            return rawDate;
        }
    }

    private LocalDate parseDate(String rawDate) {
        String[] parts = rawDate.replace('-', '/').split("/");
        if (parts.length == 2) {
            int currentYear = LocalDate.now().getYear();
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

    private String expandProductDescription(String description, String brand) {
        String normalizedDescription = normalizeForDetection(description);
        if (normalizeForDetection(brand).equals("punta del agua") && normalizedDescription.contains("cr")) {
            String specs = extractProductSpecs(description);
            return specs.isBlank() ? "Queso cremoso" : "Queso cremoso " + specs;
        }

        Optional<ProductRule> rule = PRODUCT_RULES.stream()
                .filter(productRule -> productRule.aliases().stream()
                        .map(this::normalizeForDetection)
                        .anyMatch(normalizedDescription::contains))
                .findFirst();

        if (rule.isEmpty()) {
            return description;
        }

        String specs = extractProductSpecs(description);
        return specs.isBlank() ? rule.get().description() : rule.get().description() + " " + specs;
    }

    private String extractProductSpecs(String description) {
        List<String> specs = new ArrayList<>();
        Matcher matcher = Pattern.compile("(?i)\\b(?:x\\d+[a-z]*|\\d+(?:[a-z]+)?)\\b").matcher(description);
        while (matcher.find()) {
            String spec = matcher.group().toLowerCase(LOCALE_AR);
            specs.add(spec);
        }
        return String.join(" ", specs);
    }

    private String formatAmount(double amount) {
        return AMOUNT_FORMAT.format(amount);
    }

    private String normalizeForDetection(String value) {
        return Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9/ ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String cleanOcrNoise(String value) {
        return value
                .replace('|', 'I')
                .replace('"', ' ')
                .replace('`', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String toTitleCase(String value) {
        String[] words = value.toLowerCase(LOCALE_AR).split(" ");
        StringBuilder builder = new StringBuilder();

        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            if (word.length() == 1) {
                builder.append(word.toUpperCase(LOCALE_AR));
            } else {
                builder.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }

        return builder.toString();
    }

    private record BrandMatch(String brand, String normalizedAlias) {
    }

    private record ProductRule(String description, List<String> aliases) {
    }

    private record ParsedItemLine(String description, String price) {
    }

    private record PedidosYaProductLine(String description, Optional<String> quantity) {
    }

    private record PedidosYaInlineItem(String description, String price, String quantity) {
    }
}
