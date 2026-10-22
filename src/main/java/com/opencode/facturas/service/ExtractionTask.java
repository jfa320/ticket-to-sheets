package com.opencode.facturas.service;

final class ExtractionTask {
    final String id;
    final Integer searchBudgetSeconds;
    private volatile boolean cancelled;

    ExtractionTask(String id) { this(id, null); }
    ExtractionTask(String id, Integer searchBudgetSeconds) { this.id = id; this.searchBudgetSeconds = searchBudgetSeconds; }
    void cancel() { cancelled = true; }
    boolean isCancelled() { return cancelled; }
    void checkCancelled() {
        if (cancelled) throw new ExtractionCancelledException();
    }
}
