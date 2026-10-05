package com.opencode.facturas.service;

import com.opencode.facturas.model.ExtractResponse;
import com.opencode.facturas.model.OcrResult;
import com.opencode.facturas.model.ReceiptItem;
import com.opencode.facturas.util.DelimitedExporter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(ReceiptParserService.class);

    private final StoreNameMapper storeNameMapper;
    private final BrandCatalog brandCatalog;
    private final CorrectionMemory correctionMemory;
    private final SheetsHistoryService sheetsHistory;
    private final ReceiptLineAnalyzer lineAnalyzer = new ReceiptLineAnalyzer();
    private final ReceiptDateParser dateParser = new ReceiptDateParser(lineAnalyzer);
    private final ReceiptAmounts amounts = new ReceiptAmounts();
    private final ReceiptLayoutReader layoutReader = new ReceiptLayoutReader(lineAnalyzer, amounts);
    private final ReceiptTotalCalculator totalCalculator = new ReceiptTotalCalculator(amounts);
    private final PedidosYaReceiptParser pedidosYaParser = new PedidosYaReceiptParser(lineAnalyzer);

    private static final Pattern PRICE_AT_END_PATTERN = Pattern.compile("(.+?)\\s+(\\d+(?:[\\.,]\\d{3})*[\\.,]\\d{2})$");
    private static final Pattern PUNTA_DE_AGUA_CREMOSO_PATTERN = Pattern.compile("(?i).*?(PUNTA\\s+DE\\s+AGUA\\s+CR\\s*\\*?\\s*1UN)\\s+(\\d+(?:[\\.,]\\d{3})*[\\.,]\\d{2}).*");
    private static final Pattern QUANTITY_PRICE_PATTERN = Pattern.compile("(?i)^\\s*(\\d+(?:[\\.,]\\d+)?)\\s*[xX]\\s*(?:\\$\\s*)?(\\d+(?:[\\.,]\\d{3})*[\\.,]\\d{2})(?:\\s+.*)?\\s*$");
    private static final Pattern COMPACT_QUANTITY_PRICE_PATTERN = Pattern.compile("(?i)^\\s*(\\d{1,2})\\s*\\$\\s*(\\d+(?:[\\.,]\\d{3})*[\\.,]\\d{2})(?:\\s+.*)?\\s*$");
    private static final Pattern MULTIPLIER_PATTERN = Pattern.compile("(?i)\\b(\\d+)\\s*[xX]\\s*(\\d+(?:[\\.,]\\d{3})*[\\.,]\\d{2})\\b");
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

    public ReceiptParserService() {
        this(StoreNameMapper.empty(), new BrandCatalog(new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    public ReceiptParserService(StoreNameMapper storeNameMapper) {
        this(storeNameMapper, new BrandCatalog(new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    public ReceiptParserService(StoreNameMapper storeNameMapper, BrandCatalog brandCatalog) {
        this(storeNameMapper, brandCatalog, new CorrectionMemory(new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    public ReceiptParserService(StoreNameMapper storeNameMapper, BrandCatalog brandCatalog, CorrectionMemory correctionMemory) {
        this(storeNameMapper, brandCatalog, correctionMemory, null);
    }

    @Autowired
    public ReceiptParserService(StoreNameMapper storeNameMapper, BrandCatalog brandCatalog, CorrectionMemory correctionMemory,
                                SheetsHistoryService sheetsHistory) {
        this.storeNameMapper = storeNameMapper;
        this.brandCatalog = brandCatalog;
        this.correctionMemory = correctionMemory;
        this.sheetsHistory = sheetsHistory;
    }

    public ExtractResponse parse(String rawText) {
        String safeRawText = rawText == null ? "" : rawText;
        List<String> lines = safeRawText.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
        return parseLines(safeRawText, lines, Map.of());
    }

    public ExtractResponse parse(OcrResult ocrResult) {
        if (ocrResult == null) {
            return parse("");
        }

        ReceiptLayoutReader.Layout layout = layoutReader.read(ocrResult);
        if (layout.lines().isEmpty()) {
            return parse(ocrResult.text());
        }

        String rawText = ocrResult.text() == null || ocrResult.text().isBlank()
                ? String.join("\n", layout.lines())
                : ocrResult.text();
        return parseLines(rawText, layout.lines(), layout.candidatesByLine());
    }

    private ExtractResponse parseLines(String rawText, List<String> lines,
                                       Map<Integer, List<ReceiptLayoutReader.Candidate>> layoutCandidates) {
        long startedAt = System.nanoTime();
        log.info("Iniciando parsing: líneas={}, caracteres={}, candidatosLayout={}",
                lines.size(), rawText.length(), layoutCandidates.values().stream().mapToInt(List::size).sum());
        String date = dateParser.extractNormalized(lines);
        if (date.isBlank() && !rawText.isBlank()) {
            date = dateParser.extractNormalized(rawText.lines().toList());
        }
        String storeName = storeNameMapper.resolve(lines, detectStoreName(lines));
        List<String> warnings = new ArrayList<>();
        List<ReceiptItem> items = new ArrayList<>(pedidosYaParser.supports(lines)
                ? extractPedidosYaItems(lines, storeName, date, warnings)
                : extractItems(lines, storeName, date, warnings, layoutCandidates));
        items.addAll(recoverFromMemory(lines, storeName, date, warnings, items));
        items.replaceAll(item -> applyLearned(item, storeName));
        if (sheetsHistory != null) items = new ArrayList<>(sheetsHistory.enrich(items, warnings));
        String total = totalCalculator.calculate(items);

        ExtractResponse response = new ExtractResponse(
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
        log.info("Parsing completado: comercio='{}', fecha='{}', items={}, advertencias={}, total={}, duración={} ms",
                storeName, date, items.size(), warnings.size(), total, (System.nanoTime() - startedAt) / 1_000_000);
        return response;
    }

    private List<ReceiptItem> extractItems(List<String> lines, String storeName, String date, List<String> warnings) {
        return extractItems(lines, storeName, date, warnings, Map.of());
    }

    private List<ReceiptItem> extractItems(List<String> lines, String storeName, String date, List<String> warnings,
                                           Map<Integer, List<ReceiptLayoutReader.Candidate>> layoutCandidates) {
        List<ReceiptItem> items = new ArrayList<>();

        for (int index = 0; index < lines.size(); index++) {
            String line = lineAnalyzer.clean(lines.get(index));
            String normalized = lineAnalyzer.normalize(line);
            List<ReceiptLayoutReader.Candidate> candidates = layoutCandidates.getOrDefault(index, List.of());
            if (!candidates.isEmpty()) {
                for (ReceiptLayoutReader.Candidate candidate : candidates) {
                    items.add(buildItem(
                            candidate.description(),
                            candidate.rawPrice(),
                            storeName,
                            date,
                            candidate.ambiguous(),
                            candidate.sourceLine(),
                            warnings,
                            candidate.quantity(),
                            candidate.priceIsUnit()
                    ));
                }
                continue;
            }

            if (index + 1 < lines.size()) {
                Optional<QuantityPrice> quantityPrice = parseStandaloneQuantityPrice(line);
                if (quantityPrice.isPresent()) {
                    String followingLine = lineAnalyzer.clean(lines.get(index + 1));
                    Optional<ParsedItemLine> followingItem = parseItemLineWithMoney(followingLine);
                    if (followingItem.isPresent()
                            && lineAnalyzer.isLikelyProduct(followingItem.get().description(), lineAnalyzer.normalize(followingLine))) {
                        double quantity = amounts.parseQuantity(quantityPrice.get().quantity()).orElse(1.0);
                        double unitPrice = amounts.parse(quantityPrice.get().unitPrice());
                        double total = amounts.parse(followingItem.get().price());
                        if (quantity > 0 && amounts.isConsistent(unitPrice, quantity, total)) {
                            items.add(buildItem(
                                    followingItem.get().description(),
                                    followingItem.get().price(),
                                    storeName,
                                    date,
                                    lineAnalyzer.isAmbiguous(followingLine, lineAnalyzer.normalize(followingLine)),
                                    followingLine,
                                    warnings,
                                    quantityPrice.get().quantity(),
                                    false
                            ));
                            index++;
                            continue;
                        }
                    }
                }
            }

            if (lineAnalyzer.shouldSkip(normalized)) {
                if (lineAnalyzer.isPriceOnly(line)) {
                    warnings.add("Precio sin descripción: " + line);
                }
                continue;
            }

            if (lineAnalyzer.isLikelyDescriptionOnly(line, normalized)
                    && !lineAnalyzer.containsMoney(line)
                    && index + 1 < lines.size()) {
                String nextLine = lineAnalyzer.clean(lines.get(index + 1));
                Matcher quantityPriceMatcher = QUANTITY_PRICE_PATTERN.matcher(nextLine);
                if (!quantityPriceMatcher.matches()) {
                    quantityPriceMatcher = COMPACT_QUANTITY_PRICE_PATTERN.matcher(nextLine);
                }
                if (quantityPriceMatcher.matches()
                        && nextLine.replace("x", "").replace("X", "").chars().noneMatch(Character::isLetter)
                        && lineAnalyzer.isMoneyValue(quantityPriceMatcher.group(2))) {
                    items.add(buildItem(line, quantityPriceMatcher.group(2), storeName, date,
                            lineAnalyzer.isAmbiguous(line, normalized), line, warnings,
                            quantityPriceMatcher.group(1), true));
                    index++;
                    continue;
                }

                if (lineAnalyzer.isPriceOnly(nextLine) && lineAnalyzer.isLikelyProduct(line, normalized)) {
                    items.add(buildItem(line, nextLine, storeName, date, lineAnalyzer.isAmbiguous(line, normalized), line, warnings));
                    index++;
                    continue;
                }
            }

            if (lineAnalyzer.isPriceOnly(line)) {
                warnings.add("Precio sin descripción: " + line);
                continue;
            }

            Matcher puntaDeAguaMatcher = PUNTA_DE_AGUA_CREMOSO_PATTERN.matcher(line);
            if (puntaDeAguaMatcher.matches()) {
                items.add(buildItem(puntaDeAguaMatcher.group(1), puntaDeAguaMatcher.group(2), storeName, date,
                        lineAnalyzer.isAmbiguous(line, normalized), line, warnings));
                continue;
            }

            Optional<ParsedItemLine> parsedItemLine = parseItemLineWithMoney(line);
            if (parsedItemLine.isPresent() && lineAnalyzer.isLikelyProduct(parsedItemLine.get().description(), normalized)) {
                items.add(buildItem(parsedItemLine.get().description(), parsedItemLine.get().price(), storeName, date,
                        lineAnalyzer.isAmbiguous(line, normalized), line, warnings));
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

            if (!lineAnalyzer.isLikelyProduct(rawDescription, normalized)) {
                warnings.add("Línea de producto descartada: " + line);
                continue;
            }

            items.add(buildItem(rawDescription, rawPrice, storeName, date, lineAnalyzer.isAmbiguous(line, normalized), line, warnings));
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
        double quantity = amounts.parseQuantity(quantityValue).orElse(1.0);
        if (quantity <= 0) {
            quantity = 1.0;
        }
        double totalPrice = amounts.parse(rawPrice);
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
                amounts.formatQuantity(quantity),
                amounts.format(unitPrice),
                dateParser.normalize(date),
                ambiguous || brandMatch.reviewRequired() ? "AMBIGUOUS" : "CORRECT",
                signatureFor(sourceLine)
        );
    }

    private List<ReceiptItem> extractPedidosYaItems(List<String> lines, String storeName, String date, List<String> warnings) {
        PedidosYaReceiptParser.ParseResult result = pedidosYaParser.parse(lines);
        warnings.addAll(result.warnings());
        return result.candidates().stream()
                .map(candidate -> buildPedidosYaItem(
                        candidate.description(),
                        candidate.price(),
                        candidate.quantity(),
                        storeName,
                        date,
                        candidate.ambiguous(),
                        candidate.sourceLine()
                ))
                .toList();
    }

    private ReceiptItem buildPedidosYaItem(String rawDescription, String rawPrice, String quantity, String storeName, String date, boolean ambiguous, String sourceLine) {
        String cleanedDescription = beautifyDescription(rawDescription);
        BrandMatch brandMatch = brandCatalog.findAnywhereIn(cleanedDescription)
                .map(match -> new BrandMatch(match.brand(), match.normalizedAlias(), false, false, ""))
                .orElse(new BrandMatch("Genérico", "", false, false, ""));
        String brand = normalizeBrand(brandMatch.brand());
        String descriptionWithoutBrand = expandProductDescription(removeBrandFromDescription(cleanedDescription, brandMatch), brand);
        double totalPrice = amounts.parse(rawPrice);
        double numericQuantity = amounts.parseQuantity(quantity).orElse(1.0);

        return new ReceiptItem(
                descriptionWithoutBrand,
                brand,
                storeName,
                categoryForStore(storeName),
                quantity,
                amounts.format(totalPrice / numericQuantity),
                dateParser.normalize(date),
                ambiguous ? "AMBIGUOUS" : "CORRECT",
                signatureFor(sourceLine)
        );
    }

    private String signatureFor(String sourceLine) {
        if (sourceLine == null) {
            return "";
        }
        String withoutPrices = lineAnalyzer.removeMoneyValues(sourceLine);
        return lineAnalyzer.normalize(withoutPrices);
    }

    private String categoryForStore(String storeName) {
        return STORE_CATEGORIES.getOrDefault(lineAnalyzer.normalize(storeName), "Supermercado");
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
            String normalized = lineAnalyzer.normalize(rawLine);
            if (lineAnalyzer.containsMetadata(normalized) || lineAnalyzer.isSummary(normalized)
                    || lineAnalyzer.containsStopWord(normalized)) {
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
        Optional<String> price = lineAnalyzer.firstMoneyValue(rawLine);
        if (price.isPresent()) {
            return price.get();
        }
        int index = lines.indexOf(rawLine);
        for (int i = index + 1; i < Math.min(lines.size(), index + 3); i++) {
            Optional<String> next = lineAnalyzer.firstMoneyValue(lines.get(i));
            if (next.isPresent()) {
                return next.get();
            }
        }
        return "";
    }

    private ReceiptItem buildItemFromMemory(CorrectionMemory.Entry entry, String rawPrice, String storeName, String date) {
        String unitPrice = rawPrice.isBlank() ? "" : amounts.format(amounts.parse(rawPrice));
        return new ReceiptItem(
                entry.descripcion(),
                normalizeBrand(entry.marca()),
                storeName,
                entry.categoria().isBlank() ? categoryForStore(storeName) : entry.categoria(),
                "1",
                unitPrice,
                dateParser.normalize(date),
                "LEARNED",
                entry.firma()
        );
    }

    private String detectStoreName(List<String> lines) {
        String fallback = "Compra sin identificar";

        for (int i = 0; i < Math.min(lines.size(), 14); i++) {
            String line = lineAnalyzer.clean(lines.get(i));
            String normalized = lineAnalyzer.normalize(line);

            if (normalized.contains("market")
                    && (normalized.contains("pedidos") || normalized.contains("podidos"))) {
                return "PedidosYa Market - San Miguel II";
            }

            if (normalized.contains("supermercado") && i + 1 < lines.size()) {
                String next = lineAnalyzer.clean(lines.get(i + 1));
                if (!lineAnalyzer.containsMetadata(lineAnalyzer.normalize(next))) {
                    return lineAnalyzer.toTitleCase(next);
                }
            }

            if (!lineAnalyzer.containsMetadata(normalized) && line.length() > 6 && !line.matches(".*\\d.*")) {
                fallback = lineAnalyzer.toTitleCase(line);
                break;
            }
        }

        return fallback;
    }

    private Optional<ParsedItemLine> parseItemLineWithMoney(String line) {
        List<String> prices = lineAnalyzer.moneyValues(line);
        if (prices.isEmpty() || line.chars().filter(Character::isLetter).count() < 3) {
            return Optional.empty();
        }

        String description = lineAnalyzer.removeMoneyValues(line)
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

    private Optional<QuantityPrice> parseStandaloneQuantityPrice(String line) {
        Matcher matcher = QUANTITY_PRICE_PATTERN.matcher(line);
        if (!matcher.matches()
                || line.replace("x", "").replace("X", "").chars().anyMatch(Character::isLetter)
                || !lineAnalyzer.isMoneyValue(matcher.group(2))) {
            return Optional.empty();
        }
        return Optional.of(new QuantityPrice(matcher.group(1), matcher.group(2)));
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
        String cleaned = lineAnalyzer.clean(rawDescription)
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

        return lineAnalyzer.toTitleCase(cleaned);
    }

    private BrandMatch detectBrand(String description, String rawDescription) {
        String firstWord = rawDescription == null ? "" : rawDescription.trim().split("\\s+")[0];
        String normalizedFirstWord = lineAnalyzer.normalize(firstWord);
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
                        lineAnalyzer.toTitleCase(firstWord),
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
                    lineAnalyzer.toTitleCase(firstWord),
                    normalizedFirstWord,
                    false,
                    false,
                    ""
            );
        }

        return new BrandMatch("Genérico", "", false, false, "");
    }

    private String normalizeBrand(String brand) {
        return brand == null || brand.isBlank() || brand.equalsIgnoreCase("Sin marca")
                ? "Genérico"
                : brand;
    }

    private boolean isGenericBrand(String brand) {
        return brand != null && lineAnalyzer.normalize(brand).equals("generico");
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
            String compactFirstWord = lineAnalyzer.normalize(words.get(0)).replace(" ", "");
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
        String normalizedValue = lineAnalyzer.normalize(value);
        return normalizedValue.startsWith(normalizedAlias)
                || normalizedValue.replace(" ", "").startsWith(normalizedAlias.replace(" ", ""));
    }

    private boolean matchesBrandAlias(String value, String normalizedAlias) {
        String normalizedValue = lineAnalyzer.normalize(value);
        return normalizedValue.equals(normalizedAlias)
                || normalizedValue.replace(" ", "").equals(normalizedAlias.replace(" ", ""));
    }

    private String expandProductDescription(String description, String brand) {
        description = description == null ? "" : description.trim();
        String normalizedDescription = lineAnalyzer.normalize(description);
        if (lineAnalyzer.normalize(brand).equals("punta del agua") && normalizedDescription.contains("cr")) {
            String specs = extractProductSpecs(description);
            return specs.isBlank() ? "Queso cremoso" : "Queso cremoso " + specs;
        }

        Optional<ProductRule> rule = PRODUCT_RULES.stream()
                .filter(productRule -> productRule.aliases().stream()
                        .map(lineAnalyzer::normalize)
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

    private record BrandMatch(String brand, String normalizedAlias, boolean approximate, boolean reviewRequired,
                              String reviewLabel) {
    }

    private record ProductRule(String description, List<String> aliases) {
    }

    private record ParsedItemLine(String description, String price) {
    }

    private record QuantityPrice(String quantity, String unitPrice) {
    }

}
