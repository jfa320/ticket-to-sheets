package com.opencode.facturas.service;

import com.opencode.facturas.model.ExtractResponse;
import com.opencode.facturas.model.ReceiptItem;
import com.opencode.facturas.util.DelimitedExporter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.Normalizer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ReceiptParserService {

    private final StoreNameMapper storeNameMapper;
    private final BrandCatalog brandCatalog;
    private final CorrectionMemory correctionMemory;

    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{1,2}\\s*[/-]\\s*\\d{1,2}(?:\\s*[/-]\\s*\\d{2,4})?)");
    private static final Pattern MONEY_PATTERN = Pattern.compile("(?:\\$\\s*\\d+[\\.,]\\d{5}|\\$\\s*\\d+(?:[\\.,]\\d{3})*(?:[\\.,]\\d{2})?|\\d+(?:[\\.,]\\d{3})*[\\.,]\\d{2})(?!\\d)");
    private static final Pattern PRICE_AT_END_PATTERN = Pattern.compile("(.+?)\\s+(\\d+(?:[\\.,]\\d{3})*[\\.,]\\d{2})$");
    private static final Pattern PUNTA_DE_AGUA_CREMOSO_PATTERN = Pattern.compile("(?i).*?(PUNTA\\s+DE\\s+AGUA\\s+CR\\s*\\*?\\s*1UN)\\s+(\\d+(?:[\\.,]\\d{3})*[\\.,]\\d{2}).*");
    private static final Pattern PRICE_ONLY_PATTERN = Pattern.compile("^(\\d+(?:[\\.,]\\d{3})*[\\.,]\\d{2})$");
    private static final Pattern QUANTITY_PRICE_PATTERN = Pattern.compile("(?i)^\\s*(\\d+(?:[\\.,]\\d+)?)\\s*[xX]\\s*(?:\\$\\s*)?(\\d+(?:[\\.,]\\d{3})*[\\.,]\\d{2})(?:\\s+.*)?\\s*$");
    private static final Pattern COMPACT_QUANTITY_PRICE_PATTERN = Pattern.compile("(?i)^\\s*(\\d{1,2})\\s*\\$\\s*(\\d+(?:[\\.,]\\d{3})*[\\.,]\\d{2})(?:\\s+.*)?\\s*$");
    private static final Pattern NUMBER_TOKEN_PATTERN = Pattern.compile("\\d+(?:[\\.,]\\d+)*");
    private static final Pattern MULTIPLIER_PATTERN = Pattern.compile("(?i)\\b(\\d+)\\s*[xX]\\s*(\\d+(?:[\\.,]\\d{3})*[\\.,]\\d{2})\\b");
    private static final List<String> STOP_WORDS = List.of("subtotal", "total", "recibi", "cambio", "tarjeta", "efectivo");
    private static final Map<String, String> STORE_CATEGORIES = Map.of(
            "los tres corazones", "Supermercado",
            "pedidosya market san miguel ii", "Supermercado",
            "tienda filipa", "Supermercado",
            "ferreteria tribulato", "Ferreteria",
            "perfumerias pigmento", "Perfumeria",
            "central de sabores", "Panaderia",
            "estancia san francisco", "Otros",
            "farmacias tkl san miguel", "Farmacia",
            "tuti fruti", "Verduleria"
        );
    private static final Set<String> NON_BRAND_PREFIXES = Set.of(
            "articulo", "producto", "papel", "leche", "azucar", "harina", "arroz", "yerba", "galletitas",
            "galleta", "jabon", "manteca", "sal", "agua", "pan", "carne", "queso", "fideos", "detergente",
            "suavizante", "limpiador", "bolsa", "almacen", "soporte", "trabuco", "generico"
    );
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
        this(storeNameMapper, brandCatalog, new CorrectionMemory(new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    @Autowired
    public ReceiptParserService(StoreNameMapper storeNameMapper, BrandCatalog brandCatalog, CorrectionMemory correctionMemory) {
        this.storeNameMapper = storeNameMapper;
        this.brandCatalog = brandCatalog;
        this.correctionMemory = correctionMemory;
    }

    public ExtractResponse parse(String rawText) {
        List<String> lines = rawText.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();

        String date = normalizeDate(extractDate(lines).orElse(""));
        String storeName = storeNameMapper.resolve(lines, detectStoreName(lines));
        List<String> warnings = new ArrayList<>();
        List<ReceiptItem> items = new ArrayList<>(isPedidosYa(lines)
                ? extractPedidosYaItems(lines, storeName, date, warnings)
                : extractItems(lines, storeName, date, warnings));
        items.addAll(recoverFromMemory(lines, storeName, date, warnings, items));
        items.replaceAll(item -> applyLearned(item, storeName));
        String total = calculateTotal(items, lines);

        return new ExtractResponse(
                storeName,
                date,
                items.size(),
                total,
                DelimitedExporter.toPipeSeparated(items),
                DelimitedExporter.toTabSeparated(items),
                DelimitedExporter.toTabSeparatedWithoutHeader(items),
                rawText,
                items,
                null,
                null,
                warnings
        );
    }

    private String calculateTotal(List<ReceiptItem> items, List<String> lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (ReceiptItem item : items) {
            if (item.precioUnitario().isBlank()) {
                continue;
            }
            BigDecimal unitPrice = BigDecimal.valueOf(parseAmount(item.precioUnitario()));
            BigDecimal quantity = BigDecimal.valueOf(parseQuantity(item.cantidad()).orElse(1.0));
            total = total.add(unitPrice.multiply(quantity));
        }
        total = total.add(extractTaxTotal(lines));
        return formatAmount(total.setScale(2, RoundingMode.HALF_UP).doubleValue());
    }

    private BigDecimal extractTaxTotal(List<String> lines) {
        BigDecimal taxes = BigDecimal.ZERO;
        for (String line : lines) {
            String normalized = normalizeForDetection(line);
            if (!normalized.contains("iva") || normalized.contains("contenido")) {
                continue;
            }

            Matcher matcher = MONEY_PATTERN.matcher(line);
            String lastAmount = null;
            while (matcher.find()) {
                lastAmount = matcher.group();
            }
            if (lastAmount != null) {
                taxes = taxes.add(BigDecimal.valueOf(parseAmount(lastAmount)));
            }
        }
        return taxes;
    }

    private List<ReceiptItem> extractItems(List<String> lines, String storeName, String date, List<String> warnings) {
        List<ReceiptItem> items = new ArrayList<>();

        for (int index = 0; index < lines.size(); index++) {
            String line = cleanOcrNoise(lines.get(index));
            String normalized = normalizeForDetection(line);
            if (shouldSkipLine(normalized)) {
                if (PRICE_ONLY_PATTERN.matcher(line).matches()) {
                    warnings.add("Precio sin descripción: " + line);
                }
                continue;
            }

            if (isLikelyDescriptionOnly(line, normalized) && index + 1 < lines.size()) {
                String nextLine = cleanOcrNoise(lines.get(index + 1));
                Matcher quantityPriceMatcher = QUANTITY_PRICE_PATTERN.matcher(nextLine);
                if (!quantityPriceMatcher.matches()) {
                    quantityPriceMatcher = COMPACT_QUANTITY_PRICE_PATTERN.matcher(nextLine);
                }
                if (quantityPriceMatcher.matches()
                        && nextLine.replace("x", "").replace("X", "").chars().noneMatch(Character::isLetter)
                        && isMoneyValue(quantityPriceMatcher.group(2))) {
                    items.add(buildItem(line, quantityPriceMatcher.group(2), storeName, date,
                            isAmbiguousLine(line, normalized), line, warnings,
                            quantityPriceMatcher.group(1), true));
                    index++;
                    continue;
                }

                if (PRICE_ONLY_PATTERN.matcher(nextLine).matches() && isLikelyProductLine(line, normalized)) {
                    items.add(buildItem(line, nextLine, storeName, date, isAmbiguousLine(line, normalized), line, warnings));
                    index++;
                    continue;
                }
            }

            if (PRICE_ONLY_PATTERN.matcher(line).matches()) {
                warnings.add("Precio sin descripción: " + line);
                continue;
            }

            Matcher puntaDeAguaMatcher = PUNTA_DE_AGUA_CREMOSO_PATTERN.matcher(line);
            if (puntaDeAguaMatcher.matches()) {
                items.add(buildItem(puntaDeAguaMatcher.group(1), puntaDeAguaMatcher.group(2), storeName, date,
                        isAmbiguousLine(line, normalized), line, warnings));
                continue;
            }

            Optional<ParsedItemLine> parsedItemLine = parseItemLineWithMoney(line);
            if (parsedItemLine.isPresent() && isLikelyProductLine(parsedItemLine.get().description(), normalized)) {
                items.add(buildItem(parsedItemLine.get().description(), parsedItemLine.get().price(), storeName, date,
                        isAmbiguousLine(line, normalized), line, warnings));
                continue;
            }

            if (parsedItemLine.isPresent()) {
                warnings.add("Línea de producto descartada: " + line);
            }

            Matcher matcher = PRICE_AT_END_PATTERN.matcher(line);
            if (!matcher.find()) {
                continue;
            }

            String rawDescription = matcher.group(1).trim();
            String rawPrice = matcher.group(2).trim();
            if (rawDescription.length() < 3) {
                warnings.add("Descripción demasiado corta: " + line);
                continue;
            }

            if (!isLikelyProductLine(rawDescription, normalized)) {
                warnings.add("Línea de producto descartada: " + line);
                continue;
            }

            items.add(buildItem(rawDescription, rawPrice, storeName, date, isAmbiguousLine(line, normalized), line, warnings));
        }

        return items;
    }

    private ReceiptItem buildItem(String rawDescription, String rawPrice, String storeName, String date,
                                  boolean ambiguous, String sourceLine, List<String> warnings) {
        return buildItem(rawDescription, rawPrice, storeName, date, ambiguous, sourceLine, warnings,
                String.valueOf(detectQuantity(rawDescription)));
    }

    private ReceiptItem buildItem(String rawDescription, String rawPrice, String storeName, String date,
                                  boolean ambiguous, String sourceLine, List<String> warnings, String quantityValue) {
        return buildItem(rawDescription, rawPrice, storeName, date, ambiguous, sourceLine, warnings, quantityValue, false);
    }

    private ReceiptItem buildItem(String rawDescription, String rawPrice, String storeName, String date,
                                  boolean ambiguous, String sourceLine, List<String> warnings,
                                  String quantityValue, boolean priceIsUnit) {
        int quantity = (int) Math.max(1, parseQuantity(quantityValue).orElse(1.0));
        double totalPrice = parseAmount(rawPrice);
        double unitPrice = priceIsUnit || quantity <= 0 ? totalPrice : totalPrice / quantity;
        String cleanedDescription = beautifyDescription(rawDescription);
        BrandMatch brandMatch = detectBrand(cleanedDescription, rawDescription);
        if (brandMatch.reviewRequired()) {
            warnings.add("Marca aproximada: " + brandMatch.reviewLabel());
        }
        String brand = normalizeBrand(brandMatch.brand());
        String descriptionWithoutBrand = expandProductDescription(removeBrandFromDescription(cleanedDescription, brandMatch), brand);

        return new ReceiptItem(
                descriptionWithoutBrand,
                brand,
                storeName,
                categoryForStore(storeName),
                String.valueOf(quantity),
                formatAmount(unitPrice),
                normalizeDate(date),
                ambiguous || brandMatch.reviewRequired() ? "AMBIGUOUS" : "CORRECT",
                signatureFor(sourceLine)
        );
    }

    private boolean isPedidosYa(List<String> lines) {
        return lines.stream()
                .map(this::normalizeForDetection)
                .anyMatch(line -> line.contains("pedidosya")
                        || line.contains("pedidos ya")
                        || line.contains("podidosya")
                        || (line.contains("market") && line.contains("pedido")));
    }

    private List<ReceiptItem> extractPedidosYaItems(List<String> lines, String storeName, String date, List<String> warnings) {
        List<ReceiptItem> items = new ArrayList<>();

        for (int index = 0; index < lines.size(); index++) {
            String line = cleanOcrNoise(lines.get(index));
            String normalized = normalizeForDetection(line);
            Optional<PedidosYaInlineItem> inlineItem = parsePedidosYaInlineItem(line, normalized);
            if (inlineItem.isPresent()) {
                PedidosYaInlineItem item = inlineItem.get();
                items.add(buildPedidosYaItem(item.description(), item.price(), item.quantity(), storeName, date,
                        isAmbiguousLine(line, normalized), line));
                continue;
            }

            if (!isLikelyPedidosYaProductLine(line, normalized)) {
                continue;
            }

            PedidosYaProductLine productLine = splitPedidosYaQuantity(line);
            Optional<String> price = findPedidosYaPriceInFollowingLines(lines, index + 1);
            if (price.isEmpty()) {
                if (shouldWarnPedidosYaMissingPrice(line, normalized)) {
                    warnings.add("Producto posible sin precio: " + line);
                }
                continue;
            }

            String quantity = productLine.quantity().orElse(null);
            if (quantity == null) {
                quantity = findPedidosYaQuantityInFollowingLines(lines, index + 1).orElse("1");
            }
            items.add(buildPedidosYaItem(productLine.description(), price.get(), quantity, storeName, date,
                    isAmbiguousLine(line, normalized), line));
        }

        return items;
    }

    private boolean shouldWarnPedidosYaMissingPrice(String line, String normalized) {
        if (!isLikelyPedidosYaProductLine(line, normalized)
                || normalized.contains("pedido")
                || normalized.contains("pago")
                || normalized.contains("medio")
                || normalized.contains("detalle")
                || normalized.contains("entrega")
                || normalized.contains("timbre")
                || normalized.contains("telefon")) {
            return false;
        }

        if (MONEY_PATTERN.matcher(line).find()) {
            return true;
        }

        return Pattern.compile("(?i)\\b\\d+(?:[\\.,]\\d+)?\\s*(?:x|kg|g|gr|ml|l|un|und|unidad(?:es)?)\\b")
                .matcher(line)
                .find();
    }

    private boolean isLikelyPedidosYaProductLine(String line, String normalized) {
        if (line.length() < 5 || containsMetadata(normalized) || isSummaryLine(normalized)) {
            return false;
        }
        if (normalized.matches("[0-9 kg]+")) {
            return false;
        }
        if (normalized.contains("cambio de peso") || normalized.contains("off") || normalized.contains("market")) {
            return false;
        }
        if (normalized.contains("tu pedido")
                || normalized.contains("tu pago")
                || normalized.contains("medio de pago")
                || normalized.contains("detalle sobre la entrega")
                || normalized.contains("llamar por telefono")
                || normalized.contains("timbre no funciona")
                || normalized.contains("hrptt prdiac")) {
            return false;
        }
        return line.chars().filter(Character::isLetter).count() >= 4;
    }

    private Optional<PedidosYaInlineItem> parsePedidosYaInlineItem(String line, String normalized) {
        if (containsMetadata(normalized)
                || normalized.contains("off")
                || normalized.contains("market")
                || isSummaryLine(normalized)
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

    private ReceiptItem buildPedidosYaItem(String rawDescription, String rawPrice, String quantity, String storeName, String date, boolean ambiguous, String sourceLine) {
        String cleanedDescription = beautifyDescription(rawDescription);
        BrandMatch brandMatch = brandCatalog.findAnywhereIn(cleanedDescription)
                .map(match -> new BrandMatch(match.brand(), match.normalizedAlias(), false, false, ""))
                .orElse(new BrandMatch("Genérico", "", false, false, ""));
        String brand = normalizeBrand(brandMatch.brand());
        String descriptionWithoutBrand = expandProductDescription(removeBrandFromDescription(cleanedDescription, brandMatch), brand);
        double totalPrice = parseAmount(rawPrice);
        double numericQuantity = parseQuantity(quantity).orElse(1.0);

        return new ReceiptItem(
                descriptionWithoutBrand,
                brand,
                storeName,
                categoryForStore(storeName),
                quantity,
                formatAmount(totalPrice / numericQuantity),
                normalizeDate(date),
                ambiguous ? "AMBIGUOUS" : "CORRECT",
                signatureFor(sourceLine)
        );
    }

    private String signatureFor(String sourceLine) {
        if (sourceLine == null) {
            return "";
        }
        String withoutPrices = MONEY_PATTERN.matcher(sourceLine).replaceAll(" ");
        return normalizeForDetection(withoutPrices);
    }

    private String categoryForStore(String storeName) {
        return STORE_CATEGORIES.getOrDefault(normalizeForDetection(storeName), "Supermercado");
    }

    private ReceiptItem applyLearned(ReceiptItem item, String storeName) {
        if (item.firma().isBlank()) {
            return item;
        }
        CorrectionMemory.Entry entry = correctionMemory.find(storeName, item.firma());
        if (entry == null) {
            return item;
        }
        return new ReceiptItem(
                entry.descripcion(),
                entry.marca().isBlank() ? item.marca() : normalizeBrand(entry.marca()),
                item.lugarDeCompra(),
                entry.categoria().isBlank() ? item.categoria() : entry.categoria(),
                item.cantidad(),
                item.precioUnitario(),
                item.fecha(),
                "LEARNED",
                item.firma()
        );
    }

    private List<ReceiptItem> recoverFromMemory(List<String> lines, String storeName, String date, List<String> warnings, List<ReceiptItem> items) {
        Set<String> presentFirmas = items.stream()
                .map(ReceiptItem::firma)
                .filter(firma -> !firma.isBlank())
                .collect(Collectors.toSet());

        List<ReceiptItem> recovered = new ArrayList<>();
        for (String rawLine : lines) {
            String firma = signatureFor(rawLine);
            if (firma.isBlank() || presentFirmas.contains(firma)) {
                continue;
            }
            String normalized = normalizeForDetection(rawLine);
            if (containsMetadata(normalized) || isSummaryLine(normalized)
                    || STOP_WORDS.stream().anyMatch(normalized::contains)) {
                continue;
            }
            CorrectionMemory.Entry entry = correctionMemory.find(storeName, firma);
            if (entry == null) {
                continue;
            }
            warnings.add("Recuperado de memoria: " + rawLine);
            recovered.add(buildItemFromMemory(entry, findPriceInLineOrNext(rawLine, lines), storeName, date));
        }
        return recovered;
    }

    private String findPriceInLineOrNext(String rawLine, List<String> lines) {
        Matcher matcher = MONEY_PATTERN.matcher(rawLine);
        if (matcher.find()) {
            return matcher.group();
        }
        int index = lines.indexOf(rawLine);
        for (int i = index + 1; i < Math.min(lines.size(), index + 3); i++) {
            Matcher next = MONEY_PATTERN.matcher(lines.get(i));
            if (next.find()) {
                return next.group();
            }
        }
        return "";
    }

    private ReceiptItem buildItemFromMemory(CorrectionMemory.Entry entry, String rawPrice, String storeName, String date) {
        String unitPrice = rawPrice.isBlank() ? "" : formatAmount(parseAmount(rawPrice));
        return new ReceiptItem(
                entry.descripcion(),
                normalizeBrand(entry.marca()),
                storeName,
                entry.categoria().isBlank() ? categoryForStore(storeName) : entry.categoria(),
                "1",
                unitPrice,
                normalizeDate(date),
                "LEARNED",
                entry.firma()
        );
    }

    private boolean isAmbiguousLine(String line, String normalized) {
        Matcher numberMatcher = NUMBER_TOKEN_PATTERN.matcher(line);
        int numberTokens = 0;
        while (numberMatcher.find()) {
            numberTokens++;
        }
        int priceCount = 0;
        Matcher priceMatcher = MONEY_PATTERN.matcher(line);
        while (priceMatcher.find()) {
            priceCount++;
        }

        return DATE_PATTERN.matcher(normalized).find()
                || normalized.contains("total")
                || containsMetadata(normalized)
                || line.length() > 60
                || numberTokens > 3
                || priceCount > 1;
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
        } catch (DateTimeException | NumberFormatException ex) {
            return false;
        }
    }

    private String detectStoreName(List<String> lines) {
        String fallback = "Compra sin identificar";

        for (int i = 0; i < Math.min(lines.size(), 14); i++) {
            String line = cleanOcrNoise(lines.get(i));
            String normalized = normalizeForDetection(line);

            if (normalized.contains("market")
                    && (normalized.contains("pedidos") || normalized.contains("podidos"))) {
                return "PedidosYa Market - San Miguel II";
            }

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
        return isSummaryLine(normalized) || STOP_WORDS.stream().anyMatch(normalized::contains);
    }

    private boolean isSummaryLine(String normalized) {
        String compact = normalized.replace(" ", "");
        return compact.contains("subtot")
                || (normalized.contains("neto") && normalized.contains("gravado"));
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
        if (containsMetadata(normalizedDescription) || isSummaryLine(normalizedDescription)
                || STOP_WORDS.stream().anyMatch(normalizedDescription::contains)) {
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

    private boolean isMoneyValue(String value) {
        return PRICE_ONLY_PATTERN.matcher(value.trim()).matches()
                || MONEY_PATTERN.matcher(value.trim()).matches();
    }

    private int detectQuantity(String description) {
        Matcher trailingQuantity = Pattern.compile("(?i)(\\d+)\\s*[xX]\\s*$").matcher(description.trim());
        if (trailingQuantity.find()) {
            return Integer.parseInt(trailingQuantity.group(1));
        }
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

    private BrandMatch detectBrand(String description, String rawDescription) {
        String firstWord = rawDescription == null ? "" : rawDescription.trim().split("\\s+")[0];
        String normalizedFirstWord = normalizeForDetection(firstWord);
        if (NON_BRAND_PREFIXES.contains(normalizedFirstWord)) {
            return new BrandMatch("Genérico", "", false, false, "");
        }

        Optional<BrandMatch> knownBrand = brandCatalog.findIn(description)
                .map(match -> new BrandMatch(match.brand(), match.normalizedAlias(), false, false, ""));
        if (knownBrand.isPresent()) {
            return knownBrand.get();
        }

        Optional<BrandCatalog.FuzzyBrandMatch> fuzzy = brandCatalog.findFuzzyAtStart(rawDescription);
        if (fuzzy.isPresent()) {
            if (fuzzy.get().percentage() > 70.0) {
                return new BrandMatch(
                        fuzzy.get().brand(),
                        normalizedFirstWord,
                        true,
                        false,
                        ""
                );
            }
            if (fuzzy.get().percentage() >= 30.0) {
                return new BrandMatch(
                        toTitleCase(firstWord),
                        normalizedFirstWord,
                        true,
                        true,
                        firstWord + " -> " + fuzzy.get().brand()
                                + " (" + Math.round(fuzzy.get().percentage()) + "%)"
                );
            }
            return new BrandMatch("Genérico", "", false, false, "");
        }

        Optional<BrandCatalog.FuzzyBrandMatch> bestFuzzy = brandCatalog.findBestFuzzyAtStart(rawDescription);
        if (bestFuzzy.isPresent() && bestFuzzy.get().percentage() < 30.0) {
            return new BrandMatch("Genérico", "", false, false, "");
        }

        if (firstWord.matches("[A-ZÁÉÍÓÚÑÜ&'.-]{4,}")
                && !NON_BRAND_PREFIXES.contains(normalizedFirstWord)) {
            return new BrandMatch(
                    toTitleCase(firstWord),
                    normalizedFirstWord,
                    false,
                    false,
                    ""
            );
        }

        return new BrandMatch("Genérico", "", false, false, "");
    }

    private String firstWord(String value) {
        return value == null || value.isBlank() ? "" : value.trim().split("\\s+")[0];
    }

    private String normalizeBrand(String brand) {
        return brand == null || brand.isBlank() || brand.equalsIgnoreCase("Sin marca")
                ? "Genérico"
                : brand;
    }

    private boolean isGenericBrand(String brand) {
        return brand != null && normalizeForDetection(brand).equals("generico");
    }

    private String removeBrandFromDescription(String description, BrandMatch brandMatch) {
        if (brandMatch.normalizedAlias().isBlank() || isGenericBrand(brandMatch.brand())) {
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
        } catch (DateTimeException | NumberFormatException ex) {
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
        description = description == null ? "" : description.trim();
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

    private record BrandMatch(String brand, String normalizedAlias, boolean approximate, boolean reviewRequired,
                              String reviewLabel) {
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
