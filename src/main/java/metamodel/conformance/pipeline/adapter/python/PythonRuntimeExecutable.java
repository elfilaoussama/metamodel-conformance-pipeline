package metamodel.conformance.pipeline.adapter.python;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import metamodel.conformance.pipeline.adapter.ObservationException;
import metamodel.conformance.pipeline.util.Hashing;

/** Physical executable receipt, not certification of the whole Python runtime. */
record PythonRuntimeExecutable(String path, String sha256) {
    PythonRuntimeExecutable {
        if (path == null || !Path.of(path).isAbsolute() || sha256 == null
                || !sha256.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("invalid Python runtime executable receipt");
        }
    }

    void verify() throws IOException, ObservationException {
        Path executable = Path.of(path);
        if (!Files.isRegularFile(executable) || !sha256.equals(Hashing.sha256(executable))) {
            throw new ObservationException("Python runtime executable changed or is unavailable: " + path);
        }
    }
}
