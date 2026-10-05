package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.facturas.model.ExtractResponse;
import com.opencode.facturas.model.OcrDetection;
import com.opencode.facturas.model.OcrResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceiptLayoutReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void readsQuantityUnitPriceAndTotalFromOcrColumns() {
        ReceiptParserService parser = parser();
        OcrResult ocr = new OcrResult(
                "",
                List.of(),
                List.of(
                        detection("MERCADO NUEVO", 10, 10, 180, 30),
                        detection("Fecha 05/08/2026", 40, 10, 190, 60),
                        detection("DESCRIPCION", 80, 10, 150, 100),
                        detection("CANT", 80, 260, 320, 100),
                        detection("P.UNIT", 80, 420, 500, 100),
                        detection("IMPORTE", 80, 600, 700, 100),
                        detection("PRODUCTO FRESCO", 120, 10, 220, 145),
                        detection("2", 120, 285, 310, 145),
                        detection("1350,00", 120, 420, 510, 145),
                        detection("2700,00", 120, 600, 690, 145),
                        detection("TOTAL", 170, 500, 570, 195),
                        detection("2700,00", 170, 600, 690, 195)
                ),
                "enhanced",
                84.0
        );

        ExtractResponse response = parser.parse(ocr);

        assertEquals("Mercado Nuevo", response.storeName());
        assertEquals("5/8/2026", response.date());
        assertEquals(1, response.itemCount(), response.csv());
        assertEquals("2", response.items().get(0).cantidad());
        assertEquals("1350,00", response.items().get(0).precioUnitario());
        assertEquals("2700,00", response.total());
        assertTrue(response.csv().contains("Producto Fresco|Genérico|Mercado Nuevo|Supermercado|2|1350,00|5/8/2026"), response.csv());
    }

    @Test
    void associatesStandaloneQuantityPriceRowWithFollowingProductTotal() {
        ReceiptParserService parser = parser();
        OcrResult ocr = new OcrResult(
                "",
                List.of(),
                List.of(
                        detection("MERCADO NUEVO", 10, 10, 180, 30),
                        detection("PRODUCTO UNO", 80, 10, 220, 105),
                        detection("1600,00", 80, 600, 690, 105),
                        detection("2 X 3000,00", 110, 400, 580, 135),
                        detection("PRODUCTO DOS", 140, 10, 220, 165),
                        detection("6000,00", 140, 600, 690, 165)
                ),
                "enhanced",
                84.0
        );

        ExtractResponse response = parser.parse(ocr);

        assertEquals(2, response.itemCount(), response.csv());
        assertEquals("1", response.items().get(0).cantidad());
        assertEquals("2", response.items().get(1).cantidad());
        assertEquals("3000,00", response.items().get(1).precioUnitario());
        assertEquals("7600,00", response.total());
        assertTrue(response.warnings().isEmpty(), response.warnings().toString());
    }

    @Test
    void treatsMissingConfidenceAsUnknownInsteadOfAmbiguous() {
        ReceiptParserService parser = parser();
        OcrResult ocr = new OcrResult(
                "",
                List.of(),
                List.of(
                        detectionWithoutConfidence("MERCADO NUEVO", 10, 10, 180, 30),
                        detectionWithoutConfidence("LECHE ENTERA 1L", 100, 10, 230, 124),
                        detectionWithoutConfidence("1200,00", 100, 500, 580, 124)
                ),
                "legacy",
                40.0
        );

        ExtractResponse response = parser.parse(ocr);

        assertEquals(1, response.itemCount(), response.csv());
        assertEquals("CORRECT", response.items().get(0).estado());
    }

    @Test
    void excludesOcrTotalFromLayoutAndFindsDateInRawText() {
        ReceiptParserService parser = parser();
        OcrResult ocr = new OcrResult(
                "MERCADO NUEVO\n26/09/2026 18:34\nLECHE ENTERA 1200,00\nT0TAL 1200,00",
                List.of(),
                List.of(
                        detection("MERCADO NUEVO", 10, 10, 180, 30),
                        detection("LECHE ENTERA", 80, 10, 180, 105),
                        detection("1200,00", 80, 500, 580, 105),
                        detection("T0TAL", 120, 500, 580, 145),
                        detection("1200,00", 120, 600, 690, 145)
                ),
                "original",
                40.0
        );

        ExtractResponse response = parser.parse(ocr);

        assertEquals("26/9/2026", response.date());
        assertEquals(1, response.itemCount(), response.csv());
        assertEquals("1200,00", response.total());
    }

    @Test
    void keepsPdfPagesSeparateWhenCoordinatesRepeat() {
        ReceiptParserService parser = parser();
        OcrResult firstPage = new OcrResult(
                "MERCADO NUEVO\nPRODUCTO UNO 100,00",
                List.of(),
                List.of(
                        detection("MERCADO NUEVO", 10, 10, 180, 30),
                        detection("PRODUCTO UNO", 80, 10, 180, 105),
                        detection("100,00", 80, 600, 680, 105)
                ),
                "original",
                40.0
        );
        OcrResult secondPage = new OcrResult(
                "PRODUCTO DOS 200,00",
                List.of(),
                List.of(
                        detection("PRODUCTO DOS", 80, 10, 180, 105),
                        detection("200,00", 80, 600, 680, 105)
                ),
                "original",
                42.0
        );
        OcrResult merged = new OcrResult(
                "MERCADO NUEVO\nPRODUCTO UNO 100,00\n\nPRODUCTO DOS 200,00",
                List.of(),
                List.of(),
                "original",
                41.0,
                List.of(firstPage, secondPage)
        );

        ExtractResponse response = parser.parse(merged);

        assertEquals(2, response.itemCount(), response.csv());
        assertTrue(response.csv().contains("Producto Uno|Genérico|Mercado Nuevo"));
        assertTrue(response.csv().contains("Producto Dos|Genérico|Mercado Nuevo"));
    }

    private ReceiptParserService parser() {
        return new ReceiptParserService(
                StoreNameMapper.empty(),
                new BrandCatalog(new ObjectMapper(), tempDir.resolve("brands.json")),
                new CorrectionMemory(new ObjectMapper(), tempDir.resolve("corrections.json"))
        );
    }

    private OcrDetection detection(String text, double top, double left, double right, double bottom) {
        return new OcrDetection(text, 0.93, List.of(
                List.of(left, top),
                List.of(right, top),
                List.of(right, bottom),
                List.of(left, bottom)
        ));
    }

    private OcrDetection detectionWithoutConfidence(String text, double top, double left, double right, double bottom) {
        return new OcrDetection(text, null, List.of(
                List.of(left, top),
                List.of(right, top),
                List.of(right, bottom),
                List.of(left, bottom)
        ));
    }
}
