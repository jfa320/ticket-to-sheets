package com.opencode.facturas.service;

final class ExtractionTask {
    final String id;
    private volatile boolean cancelled;

    ExtractionTask(String id) { this.id = id; }
    void cancel() { cancelled = true; }
    boolean isCancelled() { return cancelled; }
    void checkCancelled() {
        if (cancelled) throw new ExtractionCancelledException();
    }
}
