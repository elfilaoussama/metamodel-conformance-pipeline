package metamodel.conformance.pipeline.adapter.python;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import metamodel.conformance.pipeline.adapter.ObservationException;
import metamodel.conformance.pipeline.util.Hashing;
import metamodel.conformance.pipeline.model.NativeEvidence;

/** Reported module backing files/configuration, not proof of consumed bytecode. */
record PythonRuntimeContext(Configuration configuration, List<Module> modules) {
    NativeEvidence.ReportedInputs reportedInputs(String contextId) {
        var items = modules.stream().map(module -> new NativeEvidence.ReportedItem(
                module.name(), module.origin(), module.files().isEmpty()
                    ? NativeEvidence.Evidence.<NativeEvidence.Input>unavailable(NativeEvidence.Knowledge.UNKNOWN,
                        "No file-backed locations reported: " + module.backingStatus())
                    : NativeEvidence.Evidence.known(module.files().stream().map(file -> new NativeEvidence.Input(
                        file.path(), file.sha256(), NativeEvidence.Evidence.<Long>unavailable(
                            NativeEvidence.Knowledge.UNKNOWN, "modification time not captured"))).toList()),
                "Python import metadata; backing status=" + module.backingStatus())).toList();
        var entries = List.of(
                new NativeEvidence.ConfigurationEntry("searchPath", NativeEvidence.Evidence.known(configuration.searchPath())),
                new NativeEvidence.ConfigurationEntry("prefix", NativeEvidence.Evidence.known(List.of(configuration.prefix()))),
                new NativeEvidence.ConfigurationEntry("basePrefix", NativeEvidence.Evidence.known(List.of(configuration.basePrefix()))),
                new NativeEvidence.ConfigurationEntry("flags", NativeEvidence.Evidence.known(List.of(configuration.flags()))),
                new NativeEvidence.ConfigurationEntry("workingDirectory", NativeEvidence.Evidence.known(List.of(configuration.workingDirectory()))));
        return new NativeEvidence.ReportedInputs(contextId, "PYTHON_IMPORT_METADATA", "Reported interpreter module backing files and configuration",
                items, entries, "Consumed bytecode/shared images/transient loaders/environment/input roles unverified");
    }

    PythonRuntimeContext {
        if (configuration == null || modules == null || modules.isEmpty()) {
            throw new IllegalArgumentException("missing Python runtime context receipt");
        }
        modules = modules.stream().sorted(Comparator.comparing(Module::name)).toList();
        if (modules.stream().map(Module::name).distinct().count() != modules.size()) {
            throw new IllegalArgumentException("duplicate Python runtime module receipt");
        }
    }

    record Configuration(List<String> searchPath, String prefix, String basePrefix, String flags, String workingDirectory) {
        Configuration {
            if (searchPath == null || prefix == null || basePrefix == null || flags == null
                    || workingDirectory == null || !Path.of(workingDirectory).isAbsolute()) {
                throw new IllegalArgumentException("missing Python runtime configuration");
            }
            searchPath = List.copyOf(searchPath);
        }
    }

    record Module(String name, String origin, String backingStatus, List<FileInput> files) {
        Module {
            if (name == null || name.isBlank() || origin == null || files == null || backingStatus == null
                    || !Set.of("FILES_REPORTED", "BUILTIN_OR_FROZEN", "BRIDGE_MAIN", "UNAVAILABLE").contains(backingStatus)) {
                throw new IllegalArgumentException("invalid Python runtime module receipt");
            }
            files = files.stream().sorted(Comparator.comparing(FileInput::path)).toList();
            if (backingStatus.equals("FILES_REPORTED") != !files.isEmpty()
                    || files.stream().map(FileInput::path).distinct().count() != files.size()
                    || (backingStatus.equals("BUILTIN_OR_FROZEN") && !Set.of("built-in", "frozen").contains(origin))
                    || (backingStatus.equals("BRIDGE_MAIN") && !name.equals("__main__"))) {
                throw new IllegalArgumentException("inconsistent Python module backing status");
            }
        }
    }

    record FileInput(String path, String sha256) {
        FileInput {
            if (path == null || !Path.of(path).isAbsolute() || sha256 == null || !sha256.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("invalid Python module backing-file receipt");
            }
        }
    }

    void verify() throws IOException, ObservationException {
        // Coalesce shared files without losing literal alias paths or accepting conflicts.
        var inputs = new TreeMap<String, String>();
        for (Module module : modules) for (FileInput file : module.files()) {
            String old = inputs.putIfAbsent(file.path(), file.sha256());
            if (old != null && !old.equals(file.sha256())) {
                throw new ObservationException("conflicting Python module snapshots: " + file.path());
            }
        }
        for (var input : inputs.entrySet()) {
            Path file = Path.of(input.getKey());
            if (!Files.isRegularFile(file) || !Hashing.sha256(file).equals(input.getValue())) {
                throw new ObservationException("Python module backing file changed or is unavailable: " + file);
            }
        }
    }
}
