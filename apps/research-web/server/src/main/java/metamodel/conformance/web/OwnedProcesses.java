package metamodel.conformance.web;

import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns only live Process handles created here; persisted PIDs are never killed. */
@Component
public final class OwnedProcesses {
    @FunctionalInterface public interface ResourceCheck { void check() throws IOException; }
    public record Exit(int code, boolean timedOut, boolean cancelled, boolean cleanupConfirmed) { }
    public Exit run(List<String> command, Path log, long seconds, AtomicBoolean cancel) throws Exception {
        return run(new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()), seconds, cancel, () -> {});
    }
    /** Allows binary output and bounded input without a shell or a second process owner. */
    public Exit run(ProcessBuilder builder, long seconds, AtomicBoolean cancel, ResourceCheck resources) throws Exception {
        if (cancel.get()) return new Exit(-1, false, true, true);
        resources.check();
        Process process = builder.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        Map<Long, ProcessHandle> children = new HashMap<>();
        boolean timedOut = false;
        long lastCheck = 0;
        try {
            if (builder.redirectInput().type() == ProcessBuilder.Redirect.Type.PIPE) process.getOutputStream().close();
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(child -> children.put(child.pid(), child));
                if (cancel.get() || System.nanoTime() >= deadline) { timedOut = !cancel.get(); break; }
                if (System.nanoTime() - lastCheck > TimeUnit.MILLISECONDS.toNanos(500)) {
                    resources.check(); lastCheck = System.nanoTime();
                }
            }
        } catch (InterruptedException interrupted) {
            cancel.set(true); Thread.currentThread().interrupt();
        } finally {
            process.descendants().forEach(child -> children.put(child.pid(), child));
            if (process.isAlive()) {
                children.values().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                try { process.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            for (ProcessHandle child : children.values()) {
                if (child.isAlive()) {
                    child.destroyForcibly();
                    try { child.onExit().get(5, TimeUnit.SECONDS); }
                    catch (Exception failure) { throw new IOException("owned child cleanup is unconfirmed", failure); }
                }
            }
        }
        if (process.isAlive()) throw new IOException("owned process cleanup is unconfirmed");
        resources.check();
        return new Exit(process.exitValue(), timedOut, cancel.get(), true);
    }
}
