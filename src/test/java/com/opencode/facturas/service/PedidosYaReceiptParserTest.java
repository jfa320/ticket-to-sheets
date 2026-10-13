package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.facturas.model.ExtractResponse;
import com.opencode.facturas.model.OcrDetection;
import com.opencode.facturas.model.OcrResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PedidosYaReceiptParserTest {

    @TempDir
    Path tempDir;

    private static final String CHANGED_PAYMENT_OCR = """
            Q Ayuda
            Entregado vie 09de oct·19:41 hs Ir al local
            PedidosYa Market - San Miguel Il
            Hubo cambios en tu pedido
            OF Repetir pedido
            Opinar
            Detalle de los cambios en tu pago
            Cobro extra
            $75,96
            Veras el cobro extra como un nuevo movimiento en tu cuenta
            Como veo mi cobro extra
            Tu pedido
            Cambio de peso
            1kg1.08 kg
            Naranja Chica Comercial
            $1.025,46
            50%OFF
            1x
            Bocaditos De Pollo Patitas Sabor Original 400 Grs
            PATAS $4.577.50$9.155
            50%OFF
            2x
            0.00 Tapas Para Empanadas Dona Noly Criolla 12Unidades
            Noly $4.278
            1x
            Hamburguesas De Carne Paty Vacuna Cläsica Caja320 g) 4 Unidades
            PATY $7.206,75$9.609
            25%OFF
            Ver menos
            Notas adicionales para el local
            Nota de prueba sin productos
            Tu pago
            Medios de pago
            Detalles sobre la entrega
            """;

    @Test
    void readsPaymentChangesWithoutInterfaceRowsPackagingDuplicatesOrShiftedQuantities() {
        ExtractResponse response = parser(memory()).parse(CHANGED_PAYMENT_OCR);

        assertExpectedOrder(response);
        assertEquals(List.of("Genérico", "Patitas", "Noly", "Paty"),
                response.items().stream().map(item -> item.marca()).toList());
        assertTrue(response.items().get(0).descripcion().startsWith("Naranja"));
        assertTrue(response.items().get(1).descripcion().startsWith("Bocaditos"));
        assertTrue(response.items().get(2).descripcion().startsWith("Tapas"));
        assertTrue(response.items().get(3).descripcion().startsWith("Hamburguesas"));
        assertEquals("AMBIGUOUS", response.items().get(3).estado());
        assertTrue(response.warnings().isEmpty(), response.warnings().toString());
        assertEquals(4, response.tsvWithoutHeader().lines().count());
        assertTrue(response.tsvWithoutHeader().lines().allMatch(line -> line.split("\t", -1).length == 7));
        assertTrue(response.tsvWithoutHeader().lines().allMatch(line -> line.endsWith("\t" + response.date())));
        assertTrue(response.csv().contains("|Noly|PedidosYa Market - San Miguel II|Supermercado|2|2139,00|"));
    }

    @Test
    void readsSameOrderWhenGeometryJoinsQuantitiesToDescriptions() {
        OcrResult ocr = new OcrResult(CHANGED_PAYMENT_OCR, List.of(), List.of(
                detection("Q Ayuda", 10, 1500, 1650),
                detection("Entregado vie 09de oct·19:41 hs Ir al local", 40, 100, 600),
                detection("PedidosYa Market - San Miguel Il", 70, 100, 600),
                detection("Cobro extra $75,96", 100, 100, 600),
                detection("Tu pedido", 150, 20, 180),
                detection("Naranja Chica Comercial", 200, 100, 450),
                detection("1kg1.08 kg", 200, 1700, 1900),
                detection("$1.025,46", 225, 100, 240),
                detection("Bocaditos De Pollo Patitas Sabor Original 400 Grs", 280, 100, 750),
                detection("1x", 280, 1800, 1850),
                detection("PATAS", 305, 20, 90),
                detection("$4.577.50$9.155", 305, 100, 350),
                detection("0.00 Tapas Para Empanadas Dona Noly Criolla 12Unidades", 360, 100, 750),
                detection("2x", 360, 1800, 1850),
                detection("Noly $4.278", 385, 20, 300),
                detection("Hamburguesas De Carne Paty Vacuna Cläsica Caja320 g) 4 Unidades", 440, 100, 1000),
                detection("1x", 440, 1800, 1850),
                detection("PATY $7.206,75$9.609", 465, 20, 400),
                detection("Tu pago", 520, 20, 180)
        ), "geometry", 100.0);

        assertExpectedOrder(parser(memory()).parse(ocr));
    }

    @Test
    void doesNotRecoverDiscardedInterfaceOrPackagingRowsFromCorrectionMemory() {
        CorrectionMemory memory = memory();
        for (String signature : List.of("opinar", "cobro extra", "como veo mi cobro extra", "patas", "noly", "paty")) {
            memory.upsert("PedidosYa Market - San Miguel II", signature, "Fila falsa", "Genérico", "Supermercado");
        }
        memory.upsert("PedidosYa Market - San Miguel II", "naranja chica comercial", "Naranja corregida", "Genérico", "Verduleria");

        ExtractResponse response = parser(memory).parse(CHANGED_PAYMENT_OCR);

        assertExpectedOrder(response);
        assertEquals("Naranja corregida", response.items().get(0).descripcion());
        assertEquals("LEARNED", response.items().get(0).estado());
    }

    @Test
    void doesNotBorrowPriceOrQuantityFromNextProductWhenPriceIsMissing() {
        PedidosYaReceiptParser parser = new PedidosYaReceiptParser(new ReceiptLineAnalyzer());
        PedidosYaReceiptParser.ParseResult result = parser.parse(List.of(
                "Tu pedido", "1x", "Producto sin precio 400 Grs", "2x",
                "Producto siguiente 12 Unidades", "$4.278", "Tu pago", "Producto fuera del pedido $999"
        ));

        assertEquals(1, result.candidates().size());
        assertEquals("Producto siguiente 12 Unidades", result.candidates().get(0).description());
        assertEquals("2", result.candidates().get(0).quantity());
        assertEquals(1, result.warnings().size());
    }

    @Test
    void readsFollowingQuantitiesWithoutUsingPackageWeightFromNextDescription() {
        PedidosYaReceiptParser parser = new PedidosYaReceiptParser(new ReceiptLineAnalyzer());
        PedidosYaReceiptParser.ParseResult result = parser.parse(List.of(
                "Tu pedido", "Naranja Chica Comercial", "$1.025,46", "50%OFF", "1kg1.08 kg",
                "Bocaditos De Pollo Patitas 400 Grs", "PATAS $4.577.50$9.155", "50%OFF", "1x",
                "Tapas Para Empanadas Noly 12 Unidades", "Noly $4.278", "2x", "Tu pago"
        ));

        assertEquals(List.of("1.08", "1", "2"), result.candidates().stream().map(item -> item.quantity()).toList());
        assertEquals(3, result.candidates().size());
        assertTrue(result.warnings().isEmpty());
    }

    private void assertExpectedOrder(ExtractResponse response) {
        assertEquals("PedidosYa Market - San Miguel II", response.storeName());
        assertEquals("9/10/" + LocalDate.now().getYear(), response.date());
        assertEquals(4, response.itemCount(), response.csv());
        assertEquals(List.of("1.08", "1", "2", "1"), response.items().stream().map(item -> item.cantidad()).toList());
        assertEquals(List.of("949,50", "4577,50", "2139,00", "7206,75"), response.items().stream().map(item -> item.precioUnitario()).toList());
        assertEquals("17087,71", response.total());
        assertTrue(response.items().stream().allMatch(item -> response.date().equals(item.fecha())));
    }

    private CorrectionMemory memory() {
        return new CorrectionMemory(new ObjectMapper(), tempDir.resolve("corrections.json"));
    }

    private ReceiptParserService parser(CorrectionMemory memory) {
        return new ReceiptParserService(StoreNameMapper.empty(), new BrandCatalog(new ObjectMapper()), memory);
    }

    private OcrDetection detection(String text, double top, double left, double right) {
        return new OcrDetection(text, 0.99, List.of(
                List.of(left, top), List.of(right, top), List.of(right, top + 20), List.of(left, top + 20)
        ));
    }
}
