package metamodel.conformance.pipeline.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/** Script companion. The engine JAR and its ordinary CLI remain unchanged. */
public final class SequentialAnalysisWorker {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static void receipt(Path file, Map<String, Object> value) throws Exception {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        JSON.writeValue(temporary.toFile(), value);
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public static void main(String[] args) throws Exception {
        JsonNode schedule = JSON.readTree(Path.of(args[0]).toFile());
        for (JsonNode job : schedule) {
            Path status = Path.of(job.get("receipt").asText());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("state", "STARTED");
            receipt(status, result);
            long started = System.nanoTime();
            String[] invocation = JSON.convertValue(job.get("arguments"), String[].class);
            int exit = PipelineCli.run(invocation);
            boolean completed = (exit == 0 || exit == 2 || exit == 3)
                    && Files.isRegularFile(Path.of(job.get("output").asText(), "verification-capsule.json"));
            result.put("state", completed ? "COMPLETED" : "FAILED");
            result.put("exit", exit);
            result.put("seconds", (System.nanoTime() - started) / 1_000_000_000.0);
            result.put("heapUsedBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            result.put("gcCount", ManagementFactory.getGarbageCollectorMXBeans().stream()
                    .mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum());
            receipt(status, result);
            // Ordinary observation failure also retires the worker before the next job.
            if (!completed) return;
        }
    }
}
