package metamodel.conformance.pipeline.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.lang.management.ManagementFactory;
import java.nio.channels.FileChannel;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import metamodel.conformance.pipeline.emf.ObservationXmiReader;
import metamodel.conformance.pipeline.util.Hashing;
import java.util.LinkedHashMap;
import java.util.Map;

/** Script companion. The engine JAR and its ordinary CLI remain unchanged. */
public final class SequentialAnalysisWorker {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static void receipt(Path file, Map<String, Object> value) throws Exception {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        JSON.writeValue(temporary.toFile(), value);
        try(var channel=FileChannel.open(temporary,WRITE)){channel.force(true);}
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        try(var directory=FileChannel.open(file.getParent(),READ)){directory.force(true);}
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
            Path output=Path.of(job.get("output").asText());
            boolean observationOnly="observe".equals(invocation[0]);
            boolean completed = (exit == 0 || exit == 2 || exit == 3)
                    && Files.isRegularFile(output.resolve(observationOnly ? "observation.xmi" : "verification-capsule.json"));
            if(completed && job.has("expectedObservation")) {
                Path expected=Path.of(job.get("expectedObservation").asText());
                boolean same=Hashing.sha256(expected).equals(job.get("expectedObservationSha256").asText())
                        && new ObservationXmiReader().read(expected).equals(new ObservationXmiReader().read(output.resolve("observation.xmi")));
                if(!same){completed=false;result.put("reason","INPUT_OBSERVATION_CHANGED");}
            }
            result.put("phase",observationOnly ? "observe" : "analyze");
            if(completed) {
                var artifacts=new LinkedHashMap<String,String>();
                String[] names=observationOnly ? new String[]{"observation.xmi"}
                        : new String[]{"observation.xmi","repository-instance.als","verification-capsule.json"};
                for(String name:names) {
                    Path path=output.resolve(name);
                    try(var file=FileChannel.open(path,WRITE)){file.force(true);}
                    artifacts.put(name,Hashing.sha256(path));
                }
                // Persist newly created artifact/attempt directory entries before
                // publishing a durable terminal receipt. No invariant is evaluated here.
                Path boundary=Path.of(job.get("batchRoot").asText());
                for(Path directory=output;directory!=null && directory.startsWith(boundary);directory=directory.getParent())
                    try(var channel=FileChannel.open(directory,READ)){channel.force(true);}
                result.put("artifacts",artifacts);
            }
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
