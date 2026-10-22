package com.opencode.facturas.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReceiptPresentationNormalizerTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    @Test void parserPreservesAmountQuantityAndRawSignatureWhenRepairingASize() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var brands = new BrandCatalog(mapper,directory.resolve("brands.json")); brands.remember("Morenita");
        var parser = new ReceiptParserService(StoreNameMapper.empty(),brands,new CorrectionMemory(mapper,directory.resolve("memory.json")));
        var item = parser.parse("SUPERMERCADO\nLOCAL PRUEBA\nFecha 10/10/2026\nMORENITA CAFE*8OGF 8900,00").items().get(0);
        assertThat(item.descripcion()).isEqualTo("Cafe 80gr");
        assertThat(item.precioUnitario()).isEqualTo("8900,00");
        assertThat(item.cantidad()).isEqualTo("1");
        assertThat(item.firma()).contains("8ogf");
        assertThat(item.marca()).isEqualTo("Morenita");
    }
    @Test void repairsOnlyExplicitProductSizes() {
        assertThat(ReceiptPresentationNormalizer.normalize("CAFE*8OGF ARROZ*1KE LIMON*9OOML GALLETAS*25OGE"))
                .isEqualTo("CAFE*80gr ARROZ*1kg LIMON*900ml GALLETAS*250gr");
        assertThat(ReceiptPresentationNormalizer.normalize("ROLLO*2OOUN" )).isEqualTo("ROLLO*200un");
    }
    @Test void leavesNamesCodesAmountsAndAmbiguousDigitsAlone() {
        for (String value : new String[]{"OREO MOLTO OML", "MODELX9OO", "CAFE 2800,00", "GALLETAS 1BOGR", "MODELO 6GFX",
                "GALLETITAS 170 GR", "HARINA 000 1 kg", "AGUA 2 LT"}) {
            assertThat(ReceiptPresentationNormalizer.normalize(value)).isEqualTo(value);
        }
    }
}
