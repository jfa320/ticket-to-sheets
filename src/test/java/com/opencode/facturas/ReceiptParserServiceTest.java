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
        assertEquals(3, response.itemCount(), response.csv());
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

    @Test
    void preferReceiptDateAndExpandMorenitaManzanilla() {
        String raw = """
                LOS SUPERMERCADO
                TRES CORAZONES
                Inicio de Actividades: 01/02/2007
                Fecha 07/06/2026 Hora 13:42:06
                MORENITA MANZAN*20UN 1100,00
                TOTAL 1100,00
                """;

        ExtractResponse response = parserService.parse(raw);

        assertEquals("Los Tres Corazones", response.storeName());
        assertEquals("7/6/2026", response.date());
        assertEquals(1, response.itemCount());
        assertTrue(response.csv().contains("Manzanilla 20un|Morenita|Los Tres Corazones|Supermercado|1|1100,00|7/6/2026"));
    }

    @Test
    void ignoreBusinessStartDateEvenWhenReceiptDateUsesShortYear() {
        String raw = """
                ZOU WENGUC
                Inicio de Actividades:
                01/02/2007
                Fecha 10/06/26
                PITUSAS GALLET*300GR (21) 2100,00
                TOTAL 2100,00
                """;

        ExtractResponse response = parserService.parse(raw);

        assertEquals("Los Tres Corazones", response.storeName());
        assertEquals("10/6/2026", response.date());
        assertTrue(response.csv().contains("Galletitas 300gr|Pitusas|Los Tres Corazones|Supermercado|1|2100,00|10/6/2026"));
    }

    @Test
    void parseTiendaFilipaTicketWithMoneyBeforeAndAfterDescription() {
        String raw = """
                TIENDA FILIPA S.R.L.
                LUNES A SABADOS DE 9HS.A20HS
                WHATSAPP:11-2358-1877
                TICKET # YNT-00337183
                FECHA YHORA:13/06/202611:56
                CLIENTE:CONSUMIDOR FINAL
                CANTIDAD/PRECIO UNIT
                DESCRIPC.ION IMPORTE
                1.0000x$2.400.00
                CUMANA MIX DE SEMILLAS DE 1.0000 $2.800.00 $2.400.00
                NISINSALX500GR 1.0000x$5.800.00 $2.800,00
                1.000x$60,00 LA FRANCIA.PAN MULTICEREA $5.800.00
                BOLSA VERDE $60.00
                SUBTOTAL. DESCUENTO $11.060.00
                TOTAL $11.060,00 $0,00
                MUCHAS GRACIAS!
                """;

        ExtractResponse response = parserService.parse(raw);

        assertEquals("Tienda Filipa", response.storeName());
        assertEquals("13/6/2026", response.date());
        assertTrue(response.csv().contains("Mix de semillas|Cumana|Tienda Filipa|Supermercado|1|2400,00|13/6/2026"), response.csv());
        assertTrue(response.csv().contains("Sal x500gr|Nisin|Tienda Filipa|Supermercado|1|2800,00|13/6/2026"), response.csv());
        assertTrue(response.csv().contains("Pan multicereal|La Francia|Tienda Filipa|Supermercado|1|5800,00|13/6/2026"), response.csv());
    }

    @Test
    void parsePedidosYaMarketScreenshotOcr() {
        String raw = """
                PedidosYa Market - San Miguel II
                Cambio de peso
                Cebolla Selección
                $ 800,02
                15% OFF
                1kg 0.9 kg
                Agua Mineral Sierra De Los Padres Sin Gas 2 L
                $ 1.572,50 $1.850
                15% OFF
                1x
                Papas Air Fryer Mc Cain Más Finitas 700 g
                $ 11.470,80 $19.118
                2DA AL 80% OFF
                2x
                Yogur Tregar Natural Entero Sin Azúcar 280 g
                $ 3.849
                1x
                Harina De Trigo Morixe 000 1 kg
                $ 999
                1x
                Leche Tregar Entera 3% Larga Vida 1 L
                $ 3.105,70 $4.778
                2x
                Queso Rallado La Paulina Tradicional 150 g
                $ 5.082,15 $5.979
                1x
                """;

        ExtractResponse response = parserService.parse(raw);

        assertEquals("Pedidosya Market - San Miguel Ii", response.storeName());
        assertTrue(response.csv().contains("Cebolla|Generico|Pedidosya Market - San Miguel Ii|Supermercado|0.9|888,91|"), response.csv());
        assertTrue(response.csv().contains("Agua mineral 2|Sierra De Los Padres|Pedidosya Market - San Miguel Ii|Supermercado|1|1572,50|"), response.csv());
        assertTrue(response.csv().contains("Papas 700|McCain|Pedidosya Market - San Miguel Ii|Supermercado|2|5735,40|"), response.csv());
        assertTrue(response.csv().contains("Yogur 280|Tregar|Pedidosya Market - San Miguel Ii|Supermercado|1|3849,00|"), response.csv());
        assertTrue(response.csv().contains("Harina 000 1|Morixe|Pedidosya Market - San Miguel Ii|Supermercado|1|999,00|"), response.csv());
        assertTrue(response.csv().contains("Leche 3 1|Tregar|Pedidosya Market - San Miguel Ii|Supermercado|2|1552,85|"), response.csv());
        assertTrue(response.csv().contains("Queso rallado 150|La Paulina|Pedidosya Market - San Miguel Ii|Supermercado|1|5082,15|"), response.csv());
    }

    @Test
    void parseCompactPedidosYaOcrLines() {
        String raw = """
                PedidosYa Market - San Miguel II
                Cebolla Seleccion $800.02 1g0.9 kg
                15XOFF
                Agua Mineral Sierra De Los Padres Sin Gas 2 L $1.572,50 $4850 1x
                15%OFF
                $11.47080 8 Papas Air Fryer Mc Cain Mas Finitas 700 g 2x
                2DA.AL80XOFF
                Yogur Tregar Natural Entero Sin Azücar 280 g $3.849 1x
                Harina De Trigo Morixe 0001 kg $999 1x
                Leche Tregar Entera 3% Larga Vida 1 L $3.105,70 $4778 2x
                2DA.AL70XOFF
                Queso Rallado La Paulina Tradicional 150 g $5.082,15$5.979 1x
                """;

        ExtractResponse response = parserService.parse(raw);

        assertEquals("Pedidosya Market - San Miguel Ii", response.storeName());
        assertEquals(7, response.itemCount(), response.csv());
        assertTrue(response.csv().contains("Cebolla|Generico|Pedidosya Market - San Miguel Ii|Supermercado|0.9|888,91|"), response.csv());
        assertTrue(response.csv().contains("Agua mineral 2|Sierra De Los Padres|Pedidosya Market - San Miguel Ii|Supermercado|1|1572,50|"), response.csv());
        assertTrue(response.csv().contains("Papas 700|McCain|Pedidosya Market - San Miguel Ii|Supermercado|2|5735,40|"), response.csv());
        assertTrue(response.csv().contains("Yogur 280|Tregar|Pedidosya Market - San Miguel Ii|Supermercado|1|3849,00|"), response.csv());
        assertTrue(response.csv().contains("Harina 0001|Morixe|Pedidosya Market - San Miguel Ii|Supermercado|1|999,00|"), response.csv());
        assertTrue(response.csv().contains("Leche 3 1|Tregar|Pedidosya Market - San Miguel Ii|Supermercado|2|1552,85|"), response.csv());
        assertTrue(response.csv().contains("Queso rallado 150|La Paulina|Pedidosya Market - San Miguel Ii|Supermercado|1|5082,15|"), response.csv());
    }
}
