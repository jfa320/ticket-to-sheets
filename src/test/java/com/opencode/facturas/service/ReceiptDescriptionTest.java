package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class ReceiptDescriptionTest {
    @TempDir Path directory;

    @Test void preservesProductIdentityVariantsAndSeparatedUnits() {
        var mapper = new ObjectMapper();
        var parser = new ReceiptParserService(StoreNameMapper.empty(),
                new BrandCatalog(mapper, directory.resolve("brands.json")),
                new CorrectionMemory(mapper, directory.resolve("memory.json")));
        var result = parser.parse("Comercio Prueba\nFecha 10/10/2026\n"
                + "PAPEL ALUMINIO 10M 1500,00\nSALSA DE TOMATE 500g 900,00\n"
                + "HARINA INTEGRAL 1KG 800,00\nLECHE ENTERA SIN LACTOSA 1 L 1200,00\n"
                + "SAL FINA 500 G 700,00\nGALLETITAS DE AVENA 170GR 600,00");
        assertThat(result.items()).extracting(item -> item.descripcion()).containsExactly(
                "Papel Aluminio 10m", "Salsa De Tomate 500g", "Harina Integral 1kg",
                "Leche Entera Sin Lactosa 1 L", "Sal Fina 500 G", "Galletitas De Avena 170gr");
        assertThat(result.total()).isEqualTo("5700,00");
    }
}
