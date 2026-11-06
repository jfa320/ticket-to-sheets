package com.opencode.facturas.service;

final class ExtractionTask {
    final String id;
    final Integer searchBudgetSeconds;
    private volatile boolean cancelled;
    private final java.util.function.LongSupplier nanoTime;
    private Long deadline;
    private final long started;
    private volatile com.opencode.facturas.model.ExtractionProgress progress;

    ExtractionTask(String id) { this(id, null); }
    ExtractionTask(String id, Integer searchBudgetSeconds) { this(id, searchBudgetSeconds, System::nanoTime); }
    ExtractionTask(String id, Integer searchBudgetSeconds, java.util.function.LongSupplier nanoTime) {
        this.id = id; this.searchBudgetSeconds = searchBudgetSeconds; this.nanoTime = nanoTime;
        this.started = nanoTime.getAsLong();
        update("RUNNING", "PREPARING", 0, 0);
    }
    void update(String status, String phase, int page, int total) {
        progress = new com.opencode.facturas.model.ExtractionProgress(status, phase, page, total,
                Math.max(0, (nanoTime.getAsLong() - started) / 1_000_000));
    }
    com.opencode.facturas.model.ExtractionProgress progress() {
        var value = progress;
        return new com.opencode.facturas.model.ExtractionProgress(value.status(), value.phase(), value.pageNumber(),
                value.pageCount(), Math.max(0, (nanoTime.getAsLong() - started) / 1_000_000));
    }
    void phase(String phase) { var previous = progress; update("RUNNING", phase, previous.pageNumber(), previous.pageCount()); }
    void startDeadline(int seconds) { deadline = nanoTime.getAsLong() + seconds * 1_000_000_000L; }
    boolean hasDeadline() { return deadline != null; }
    double remainingSeconds() {
        checkCancelled();
        double remaining = (deadline - nanoTime.getAsLong()) / 1_000_000_000.0;
        if (remaining <= 0) throw new ExtractionTimeoutException();
        return remaining;
    }
    void cancel() { cancelled = true; }
    boolean isCancelled() { return cancelled; }
    void checkCancelled() {
        if (cancelled) throw new ExtractionCancelledException();
        if (deadline != null && nanoTime.getAsLong() >= deadline) throw new ExtractionTimeoutException();
    }
}
