package com.opencode.facturas;

import com.opencode.facturas.service.StoreNameMapper;
import com.opencode.facturas.model.ExtractResponse;
import com.opencode.facturas.service.ReceiptParserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceiptParserServiceTest {

    private final ReceiptParserService parserService = new ReceiptParserService(
            new StoreNameMapper(new ObjectMapper(), new ClassPathResource("store-mappings.json"))
    );

    @Test
    void parseReceiptLinesIntoRows() {
        String raw = """
                SUPERMERCADO
                LOS TRES CORAZONES
                Fecha 04/03/2026 Hora 18:34:33
                QUERUBIN LIQUI*800ML 2400,00
                LYSOFORM DESIN*257GR 3000,00
                HIGIENOL PAPEL 4*4UN 3700,00
                TOTAL 9100,00
                """;

        ExtractResponse response = parserService.parse(raw);

        assertEquals("Los Tres Corazones", response.storeName());
        assertEquals("4/3/2026", response.date());
        assertEquals(3, response.itemCount());
        assertTrue(response.csv().contains("Liqui 800ml|Querubin|Los Tres Corazones"));
        assertTrue(response.tsvWithoutHeader().startsWith("Liqui 800ml\tQuerubin\tLos Tres Corazones"));
        assertTrue(response.tsvWithoutHeader().contains("2400,00\t4/3/2026"));
    }

    @Test
    void parseSplitDescriptionAndPriceLinesFromOcrPhoto() {
        String raw = """
                ZOU WENGUO
                Dirección:
                IVA RESPONSABLE INSCRIPTO
                A CONSUMIDOR FINAL
                C6d.083-TIQUE
                P.V.Nro.00014-Nro.T.00212463
                Fecha 11/04/2026
                Hora20:28:29
                QUERUBIN SUAVI*90OML
                2700,00
                HILERET AZUCAR*500GR
                1700,00
                PAR*NOR GAL.LET*17OGR
                900,00
                Subtotal
                5300,00
                TOTAL
                5300,00
                RECIBIMOS
                Efectivo
                10000,00
                Vuelto
                4700,00
                GIMEN DE TRANSPARENCIA FISCAL AL
                ONSUMIDORLEY27.743
                IVA Contenido 21%:919.83
                ORIENTACION AL CONSUMIDOR
                0800-222-9042BS.AS
                V:1.01
                SESHIA0000012120
                V:|Cseshia0000017996|Zou Wenguo|Supermercado|1|101,00|2/6/2026
                """;

        ExtractResponse response = parserService.parse(raw);

        assertEquals(3, response.itemCount());
        assertEquals("Los Tres Corazones", response.storeName());
        assertTrue(response.csv().contains("Suavizante 90oml|Querubin|Los Tres Corazones"));
        assertTrue(response.csv().contains("Azucar 500gr|Hileret|Los Tres Corazones"));
        assertTrue(response.csv().contains("Galletitas 17ogr|Parnor|Los Tres Corazones"));
        assertTrue(response.tsvWithoutHeader().lines().noneMatch(line -> line.contains("$")));
    }

    @Test
    void parseLongReceiptWithPricesAtRight() {
        String raw = """
                ZOU WENGUO
                CUIT Nro.: 20-94027663-3
                Dirección: CONSEJAL TRIBULATO 1040
                Fecha 02/06/2026 Hora 19:24:47
                ALMACEN 2500,00
                0,2950 X 10500,00
                PUNTA DE AGUA CR*1UN 3097,50
                ELEGANTE PANUE*150UN 1200,00
                ALMACEN 1400,00
                ALMACEN 1400,00
                ALMACEN 1400,00
                HIGIENOL PAPEL 4*4UN 3900,00
                MOLTO PURE TOM*520GR 700,00
                EL SOL RAYIOLES *1KG 4500,00
                PROCENEX LIMPI*900ML 1600,00
                FORMIS GELLETID*55GR 800,00
                MANTY MANTECA *200GR 2200,00
                FRUTIGRAN GRAN*250GR 2000,00
                LA PROVIDECIA *505GR 2100,00
                PITUSAS GALLET*300GR 2100,00
                VOCACION CLASI*384GR 2100,00
                UNION SALCHICH*230GR 2300,00
                LUX JABON PAN X3*3UN 3800,00
                LUX JABONX3*3UN 3800,00
                Subtotal 42897,50
                TOTAL 42897,50
                """;

        ExtractResponse response = parserService.parse(raw);

        assertEquals("Los Tres Corazones", response.storeName());
        assertEquals("2/6/2026", response.date());
        assertEquals(19, response.itemCount());
        assertTrue(response.csv().contains("Queso cremoso 1un|Punta del agua|Los Tres Corazones"));
        assertTrue(response.csv().contains("Papel higienico 4 4un|Higienol|Los Tres Corazones"));
        assertTrue(response.csv().contains("Jabon x3 3un|Lux|Los Tres Corazones"));
        assertTrue(response.csv().contains("505gr|La Providencia|Los Tres Corazones"));
        assertTrue(response.csv().contains("Pure de tomates 520gr|Molto|Los Tres Corazones"));
        assertTrue(response.csv().contains("Salchichas 230gr|Unión Ganadera|Los Tres Corazones"));
    }

    @Test
    void parseMergedWeightAndPuntaDeAguaCheeseLine() {
        String raw = """
                ZOU WENGUO
                Fecha 02/06/2026 Hora 19:24:47
                0,2950 x 10500,00 PUNTA DE AGUA CR*1UN 3097,50 1200,00
                """;

        ExtractResponse response = parserService.parse(raw);

        assertEquals("Los Tres Corazones", response.storeName());
        assertEquals(1, response.itemCount());
        assertTrue(response.csv().contains("Queso cremoso 1un|Punta del agua|Los Tres Corazones|Supermercado|1|3097,50|2/6/2026"));
    }
}
