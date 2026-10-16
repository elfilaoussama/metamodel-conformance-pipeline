package metamodel.conformance.pipeline.util;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Supervises launched tools during caller-owned streaming reads and waits.
 * Tracked descendants are cleaned up; this is not an OS containment boundary
 * or a hard deadline for arbitrary caller computation/filesystem startup. */
public final class ToolProcessLease implements AutoCloseable {
    private final Process process;
    private final Thread owner = Thread.currentThread();
    private final ToolProcessPolicy policy;
    private final long started;
    private final Map<Long,ProcessHandle> owned = new ConcurrentHashMap<>();
    private final AtomicReference<IOException> monitorFailure = new AtomicReference<>();
    private final ScheduledExecutorService monitor;
    private volatile boolean expired, interrupted;

    private ToolProcessLease(ProcessBuilder builder, ToolProcessPolicy policy) throws IOException {
        this.policy=policy;
        started=System.nanoTime();
        monitor=Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread=new Thread(task,"tool-process-supervisor");thread.setDaemon(true);return thread;
        });
        try { process=builder.start(); }
        catch (IOException failure) {
            monitor.shutdownNow();
            throw failure(ToolExecutionException.Reason.START_FAILED,"cannot start tool process",failure);
        }
        try {
            owned.put(process.pid(),process.toHandle());
            monitor.scheduleAtFixedRate(this::inspect,0,10,TimeUnit.MILLISECONDS);
        } catch (RuntimeException | Error failure) {
            process.destroyForcibly();monitor.shutdownNow();throw failure;
        }
    }
    public static ToolProcessLease start(ProcessBuilder builder, ToolProcessPolicy policy) throws IOException {
        if (Thread.currentThread().isInterrupted())
            throw new ToolExecutionException(ToolExecutionException.Reason.INTERRUPTED,policy,"tool execution interrupted before launch",null);
        return new ToolProcessLease(builder,policy);
    }
    public Process process() { return process; }
    private long remainingNanos() {
        return TimeUnit.MILLISECONDS.toNanos(policy.timeoutMillis())-(System.nanoTime()-started);
    }
    private void collect() { process.descendants().forEach(handle -> owned.putIfAbsent(handle.pid(),handle)); }
    private void inspect() {
        try {
            collect();
            if (owner.isInterrupted()) { interrupted=true;terminate(); }
            if (remainingNanos()<=0) { expired=true;terminate(); }
        } catch (RuntimeException failure) {
            monitorFailure.compareAndSet(null,failure(ToolExecutionException.Reason.SUPERVISION_FAILED,"tool process supervision failed",failure));
            process.destroyForcibly();
        }
    }
    private synchronized void terminate() {
        collect();
        // Only handles observed from this process are eligible for termination.
        for (var handle:owned.values()) if (handle.pid()!=process.pid() && handle.isAlive()) handle.destroyForcibly();
        // Give a parent waiting on its killed children a short chance to reap them.
        if (owned.size()>1 && process.isAlive()) {
            try { process.waitFor(100,TimeUnit.MILLISECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        if (process.isAlive()) process.destroyForcibly();
    }
    public int awaitExit() throws IOException,InterruptedException {
        check();
        if (!process.waitFor(Math.max(1,remainingNanos()),TimeUnit.NANOSECONDS)) {
            expired=true;terminate();
        }
        check();
        if (owned.values().stream().anyMatch(handle -> handle.pid()!=process.pid() && handle.isAlive()))
            throw failure(ToolExecutionException.Reason.LIVE_DESCENDANTS,"tool exited with live tracked descendants",null);
        return process.exitValue();
    }
    private void check() throws IOException,InterruptedException {
        if (interrupted || owner.isInterrupted()) throw new InterruptedException("tool execution interrupted");
        if (expired || remainingNanos()<=0) {
            expired=true;terminate();
            throw deadlineFailure(null);
        }
        IOException failure=monitorFailure.get();if (failure!=null) throw failure;
    }
    private ToolExecutionException failure(ToolExecutionException.Reason reason,String message,Throwable cause) {
        return new ToolExecutionException(reason,policy,message,cause);
    }
    private ToolExecutionException deadlineFailure(Throwable cause) {
        return failure(ToolExecutionException.Reason.DEADLINE,
                "tool execution deadline exceeded ("+policy.timeoutMillis()+" ms)",cause);
    }
    /** A killed pipe must retain its execution failure, not look like malformed source. */
    public IOException classify(IOException io) {
        if (expired || remainingNanos()<=0) return deadlineFailure(io);
        if (interrupted || owner.isInterrupted())
            return failure(ToolExecutionException.Reason.INTERRUPTED,"tool execution interrupted",io);
        IOException supervision=monitorFailure.get();return supervision==null ? io : supervision;
    }
    @Override public void close() throws IOException {
        monitor.shutdownNow();
        boolean restoreInterrupt=Thread.interrupted();
        try {
            terminate();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while (owned.values().stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime()<deadline)
                Thread.sleep(10);
            if (owned.values().stream().anyMatch(ProcessHandle::isAlive))
                throw failure(ToolExecutionException.Reason.CLEANUP_FAILED,"tool process cleanup did not finish",null);
            if (!monitor.awaitTermination(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS))
                throw failure(ToolExecutionException.Reason.CLEANUP_FAILED,"tool supervisor cleanup did not finish",null);
            if (expired || remainingNanos()<=0)
                throw deadlineFailure(null);
            if (interrupted) throw failure(ToolExecutionException.Reason.INTERRUPTED,"tool execution interrupted",null);
        } catch (InterruptedException interrupted) {
            restoreInterrupt=true;process.destroyForcibly();
            throw failure(ToolExecutionException.Reason.INTERRUPTED,"tool process cleanup interrupted",interrupted);
        } finally { if (restoreInterrupt) Thread.currentThread().interrupt(); }
    }
}
