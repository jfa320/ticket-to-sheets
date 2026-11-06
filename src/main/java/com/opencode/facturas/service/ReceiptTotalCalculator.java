package com.opencode.facturas.service;

import com.opencode.facturas.model.ReceiptItem;
import com.opencode.facturas.model.ReceiptDiscount;

import java.math.BigDecimal;
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
            BigDecimal unitPrice = ReceiptAmounts.decimal(item.precioUnitario(), true);
            BigDecimal quantity = ReceiptAmounts.decimal(item.cantidad(), false);
            total = total.add(unitPrice.multiply(quantity));
        }
        return format(total);
    }

    String discounts(List<ReceiptDiscount> discounts) {
        BigDecimal total = discounts.stream()
                .map(discount -> ReceiptAmounts.decimal(discount.importe(), true))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return format(total);
    }

    String net(String subtotal, String discountTotal) {
        BigDecimal total = ReceiptAmounts.decimal(subtotal, true).add(ReceiptAmounts.decimal(discountTotal, true));
        return format(total);
    }

    private String format(BigDecimal amount) {
        return amounts.format(amount);
    }
}
