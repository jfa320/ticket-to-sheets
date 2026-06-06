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

    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{2}/\\d{2}/\\d{4})");
    private static final Pattern PRICE_AT_END_PATTERN = Pattern.compile("(.+?)\\s+(\\d+(?:[\\.,]\\d{3})*[\\.,]\\d{2})$");
    private static final Pattern PUNTA_DE_AGUA_CREMOSO_PATTERN = Pattern.compile("(?i).*?(PUNTA\\s+DE\\s+AGUA\\s+CR\\s*\\*?\\s*1UN)\\s+(\\d+(?:[\\.,]\\d{3})*[\\.,]\\d{2}).*");
    private static final Pattern PRICE_ONLY_PATTERN = Pattern.compile("^(\\d+(?:[\\.,]\\d{3})*[\\.,]\\d{2})$");
    private static final Pattern MULTIPLIER_PATTERN = Pattern.compile("(?i)\\b(\\d+)\\s*[xX]\\s*(\\d+(?:[\\.,]\\d{3})*[\\.,]\\d{2})\\b");
    private static final List<String> STOP_WORDS = List.of("subtotal", "total", "recibi", "cambio", "tarjeta", "efectivo");
    private static final List<String> METADATA_WORDS = List.of(
            "cuit", "direccion", "responsable", "consumidor", "actividad", "fecha", "hora", "nro", "ing.", "iva", "cod.", "pv", "tique",
            "seshia", "orientacion", "transparencia", "fiscal", "regimen", "afip"
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
            new ProductRule("Azucar", List.of("azucar")),
            new ProductRule("Suavizante", List.of("suavi")),
            new ProductRule("Jabon", List.of("jabon"))
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
        List<ReceiptItem> items = extractItems(lines, storeName, date);

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
                quantity,
                formatAmount(unitPrice),
                normalizeDate(date),
                ""
        );
    }

    private Optional<String> extractDate(List<String> lines) {
        return lines.stream()
                .map(DATE_PATTERN::matcher)
                .filter(Matcher::find)
                .map(matcher -> matcher.group(1))
                .findFirst();
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
        return !normalizedLine.contains("vuelto") && !normalizedLine.contains("ley 27") && !normalizedLine.contains("transparencia");
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
                .replace("€", "C")
                .replace("§", "S")
                .replace("°", "o")
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
        String normalized = value.replace(".", "").replace(",", ".").replace("$", "").trim();
        return Double.parseDouble(normalized);
    }

    private String normalizeDate(String rawDate) {
        if (rawDate == null || rawDate.isBlank()) {
            return "";
        }

        try {
            LocalDate parsed = LocalDate.parse(rawDate, DateTimeFormatter.ofPattern("dd/MM/yyyy"));
            return parsed.format(DateTimeFormatter.ofPattern("d/M/yyyy"));
        } catch (DateTimeParseException ex) {
            return rawDate;
        }
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
        Matcher matcher = Pattern.compile("(?i)\\b(?:x\\d+|\\d+(?:[a-z]+)?)\\b").matcher(description);
        while (matcher.find()) {
            specs.add(matcher.group().toLowerCase(LOCALE_AR));
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
}
