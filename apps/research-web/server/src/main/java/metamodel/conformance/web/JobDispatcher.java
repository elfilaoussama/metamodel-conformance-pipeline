package metamodel.conformance.web;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** Persistent FIFO backlog; only reserved worker slots have in-memory tasks or cancellation flags. */
final class JobDispatcher {
    private final Catalog catalog;
    private final int capacity;
    private final ExecutorService workers;
    private final BiConsumer<RunRecord, AtomicBoolean> execute;
    private final Map<String, AtomicBoolean> active = new HashMap<>();
    private boolean stopping;
    private Exception failure;

    JobDispatcher(Catalog catalog, int capacity, BiConsumer<RunRecord, AtomicBoolean> execute) {
        if (capacity < 1) throw new IllegalArgumentException("Worker capacity must be positive.");
        this.catalog = catalog; this.capacity = capacity; this.execute = execute;
        workers = Executors.newFixedThreadPool(capacity);
    }
    synchronized void requireAvailable() {
        if (stopping) throw new IllegalStateException("The worker service is stopping.");
        if (failure != null) throw new IllegalStateException("Job dispatch failed; queued attempts are retained for inspection.", failure);
    }
    synchronized boolean blocked() { return failure != null || stopping; }
    synchronized void dispatch() throws Exception {
        requireAvailable();
        try {
            for (RunRecord run : catalog.queued(capacity - active.size())) {
                AtomicBoolean cancel = new AtomicBoolean(); active.put(run.id(), cancel);
                // Reservation removes the row from the pending query before an executor can start it.
                catalog.save(run.transition("RUNNING", "compute slot reserved; worker starting", null));
                workers.execute(() -> {
                    try { execute.accept(run, cancel); }
                    finally { finished(run.id()); }
                });
            }
        } catch (Exception unavailable) { failure = unavailable; throw unavailable; }
    }
    private synchronized void finished(String id) {
        active.remove(id);
        if (stopping || failure != null) return;
        try { dispatch(); }
        catch (Exception unavailable) {
            failure = unavailable;
            System.err.println("Cannot dispatch queued work: " + unavailable.getMessage());
        }
    }
    synchronized RunRecord cancel(String id) throws Exception {
        RunRecord run = catalog.get(id);
        AtomicBoolean flag = active.get(id);
        if (run.state().equals("QUEUED") && flag == null) {
            RunRecord cancelled = run.transition("CANCELLED", "cancelled before launch; no worker started", null);
            catalog.save(cancelled); return cancelled;
        }
        if (flag == null || !Set.of("RUNNING", "CANCELLING").contains(run.state()))
            throw new IllegalArgumentException("run is not cancellable");
        flag.set(true);
        RunRecord cancelling = run.transition("CANCELLING", "waiting for owned worker cleanup", null);
        catalog.save(cancelling); return cancelling;
    }
    boolean shutdown() throws Exception {
        synchronized (this) {
            stopping = true; active.values().forEach(flag -> flag.set(true));
            for (RunRecord run : catalog.queued(Integer.MAX_VALUE))
                catalog.save(run.transition("CANCELLED", "service stopped before launch; no worker started", null));
            workers.shutdown();
        }
        return workers.awaitTermination(15, TimeUnit.SECONDS);
    }
}
