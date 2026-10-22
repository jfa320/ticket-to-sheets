package com.opencode.facturas.model;

import java.util.List;

public record ReceiptSource(int pageNumber, List<Integer> detectionIndices) {
    public ReceiptSource { detectionIndices = List.copyOf(detectionIndices); }
}
