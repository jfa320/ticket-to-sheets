package com.opencode.facturas.service;

import com.opencode.facturas.model.ReceiptItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

final class ReceiptTotalCalculator {

    private final ReceiptAmounts amounts;

    ReceiptTotalCalculator(ReceiptAmounts amounts) {
        this.amounts = amounts;
    }

    String calculate(List<ReceiptItem> items) {
        BigDecimal total = BigDecimal.ZERO;
        for (ReceiptItem item : items) {
            if (item.precioUnitario().isBlank()) {
                continue;
            }
            BigDecimal unitPrice = BigDecimal.valueOf(amounts.parse(item.precioUnitario()));
            BigDecimal quantity = BigDecimal.valueOf(amounts.parseQuantity(item.cantidad()).orElse(1.0));
            total = total.add(unitPrice.multiply(quantity));
        }
        return amounts.format(total.setScale(2, RoundingMode.HALF_UP).doubleValue());
    }
}
