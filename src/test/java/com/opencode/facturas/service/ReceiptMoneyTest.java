package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.facturas.model.ReceiptItem;
import com.opencode.facturas.model.ReceiptDiscount;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.assertThat;

class ReceiptMoneyTest {
    @Test void sharesExactDecimalTotalsWithJavaScript() throws Exception {
        var cases = new ObjectMapper().readTree(getClass().getResourceAsStream("/money-cases.json"));
        var calculator = new ReceiptTotalCalculator(new ReceiptAmounts());
        for (var example : cases) {
            var items = new ArrayList<ReceiptItem>();
            for (var item : example.path("items")) items.add(new ReceiptItem("Producto", "Genérico", "Local", "Otros",
                    item.path("cantidad").asText(), item.path("precioUnitario").asText(), "1/10/2026"));
            var discounts = new ArrayList<ReceiptDiscount>();
            for (var discount : example.path("discounts")) discounts.add(new ReceiptDiscount("Descuento", discount.asText()));
            String subtotal = calculator.calculate(items), discountTotal = calculator.discounts(discounts);
            assertThat(subtotal).as(example.path("name").asText()).isEqualTo(example.path("subtotal").asText().replace('.', ','));
            assertThat(discountTotal).isEqualTo(example.path("discountTotal").asText().replace('.', ','));
            assertThat(calculator.net(subtotal, discountTotal)).isEqualTo(example.path("total").asText().replace('.', ','));
        }
    }
}
