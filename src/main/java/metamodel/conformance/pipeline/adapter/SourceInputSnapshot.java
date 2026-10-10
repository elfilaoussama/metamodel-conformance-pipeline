package metamodel.conformance.pipeline.adapter;

import metamodel.conformance.pipeline.model.Language;
import metamodel.conformance.pipeline.model.SourceUnit;
import metamodel.conformance.pipeline.util.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Private parser inputs whose recorded identity is derived from captured bytes. */
public final class SourceInputSnapshot implements AutoCloseable {
    private final Path originalRoot;
    private final Path capturedRoot;
    private final Map<Path,String> originalHashes = new LinkedHashMap<>();
    private SourceInputSnapshot(Path originalRoot, Path capturedRoot) {
        this.originalRoot=originalRoot; this.capturedRoot=capturedRoot;
    }
    public static SourceInputSnapshot capture(Path root, List<Path> files) throws IOException, ObservationException {
        var snapshot=new SourceInputSnapshot(root.toAbsolutePath().normalize(),Files.createTempDirectory("source-input-snapshot-"));
        try {
            for (Path file : files) {
                Path path=file.toAbsolutePath().normalize();
                if (!path.startsWith(snapshot.originalRoot) || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)
                        || !path.toRealPath().equals(path)) {
                    throw new ObservationException("source snapshot input is unavailable, escaping or aliased: "+file);
                }
                Path relative=snapshot.originalRoot.relativize(path);
                Path copy=snapshot.capturedRoot.resolve(relative); Files.createDirectories(copy.getParent());
                Files.copy(path,copy);
                snapshot.originalHashes.put(path,Hashing.sha256(copy));
            }
            return snapshot;
        } catch (IOException | ObservationException | RuntimeException failure) {
            try { snapshot.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    public Path root() { return capturedRoot; }
    public List<SourceUnit> units(Language language) {
        return originalHashes.entrySet().stream().map(entry -> new SourceUnit(language,
                originalRoot.relativize(entry.getKey()).toString().replace('\\','/'),entry.getValue())).toList();
    }
    public void verifyOriginalsUnchanged(List<Path> selectedNow) throws IOException, ObservationException {
        Set<Path> selected=selectedNow.stream().map(p -> p.toAbsolutePath().normalize()).collect(Collectors.toSet());
        if (!selected.equals(originalHashes.keySet())) throw new ObservationException("source population changed during observation");
        for (var entry : originalHashes.entrySet()) {
            Path path=entry.getKey();
            if (!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().equals(path)
                    || !entry.getValue().equals(Hashing.sha256(path))) {
                throw new ObservationException("source input changed during observation: "+originalRoot.relativize(path));
            }
        }
    }
    @Override public void close() throws IOException {
        try (var paths=Files.walk(capturedRoot)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
