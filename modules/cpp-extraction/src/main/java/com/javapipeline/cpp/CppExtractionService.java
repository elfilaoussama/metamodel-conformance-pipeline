package com.javapipeline.cpp;

import com.google.gson.Gson;
import com.javapipeline.core.*;
import com.javapipeline.core.model.ExtractionResult;
import com.javapipeline.core.model.ExtractionResult.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public final class CppExtractionService implements JavaExtractionService {

    private static final Duration TIMEOUT = Duration.ofMinutes(15);
    private final Gson gson = new Gson();

    @Override
    public Language getLanguage() {
        return Language.CPP;
    }

    @Override
    public ExtractionResult extract(
            String projectName,
            Path repository,
            ExtractionOptions options,
            ProgressListener progress,
            CancellationToken cancellation
    ) throws JavaExtractionException {
        ProgressListener listener = progress == null ? ProgressListener.NONE : progress;
        CancellationToken token = cancellation == null ? CancellationToken.NONE : cancellation;
        Path repo = repository.toAbsolutePath().normalize();

        try {
            listener.onProgress(ProgressEvent.indeterminate(
                    ProgressEvent.Stage.DISCOVERING, "Discovering C++ source files"));

            String scriptPath = extractScript(repo);
            token.throwIfCancellationRequested();
            listener.onProgress(ProgressEvent.indeterminate(
                    ProgressEvent.Stage.EXTRACTING, "Running Clang AST extraction"));

            Process process = new ProcessBuilder("python", scriptPath, repo.toString())
                    .redirectErrorStream(true)
                    .start();

            StringBuilder output = new StringBuilder();
            Thread reader = new Thread(() -> readOutput(process, output), "cpp-extractor");
            reader.setDaemon(true);
            reader.start();

            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (process.isAlive()) {
                if (token.isCancellationRequested()) {
                    process.destroyForcibly();
                    throw new JavaExtractionException("C++ extraction cancelled");
                }
                if (System.nanoTime() >= deadline) {
                    process.destroyForcibly();
                    throw new JavaExtractionException("C++ extraction timed out");
                }
                process.waitFor(150, TimeUnit.MILLISECONDS);
            }
            reader.join(2_000);

            if (process.exitValue() != 0) {
                throw new JavaExtractionException("C++ extraction failed (exit " + process.exitValue()
                        + "): " + tail(output.toString()));
            }

            String result = output.toString();
            RawResult raw = gson.fromJson(result, RawResult.class);
            if (raw == null || raw.error != null) {
                throw new JavaExtractionException("C++ extraction error: "
                        + (raw != null ? raw.error : "no output"));
            }

            List<TypeModel> types = new ArrayList<>();
            if (raw.types != null) {
                for (RawType rt : raw.types) {
                    types.add(new TypeModel(
                            rt.qualifiedName != null ? rt.qualifiedName : rt.name,
                            rt.simpleName != null ? rt.simpleName : (rt.name != null ? rt.name : ""),
                            rt.kind,
                            rt.superClass,
                            rt.interfaces != null ? rt.interfaces : List.of(),
                            mapFields(rt.fields),
                            mapExecutables(rt.executables),
                            Boolean.TRUE.equals(rt.abstractType),
                            Boolean.TRUE.equals(rt.finalType),
                            rt.sourceFile,
                            rt.line
                    ));
                }
            }

            List<Diagnostic> diagnostics = new ArrayList<>();
            if (raw.diagnostics != null) {
                for (RawDiagnostic rd : raw.diagnostics) {
                    diagnostics.add(new Diagnostic(
                            Diagnostic.Severity.valueOf(rd.severity != null ? rd.severity.toUpperCase() : "INFO"),
                            rd.code != null ? rd.code : "CPP",
                            rd.message));
                }
            }

            List<Path> sourceRoots = raw.sourceRoots != null
                    ? raw.sourceRoots.stream().map(Path::of).collect(Collectors.toList())
                    : List.of(repo);

            return new ExtractionResult(
                    "1.0", projectName != null ? projectName : repo.getFileName().toString(),
                    repo, Instant.now(), sourceRoots, types, diagnostics);

        } catch (JavaExtractionException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new JavaExtractionException("C++ extraction failed for " + projectName + ": " + ex.getMessage(), ex);
        }
    }

    private static String extractScript(Path workDir) throws Exception {
        Path script = workDir.resolveSibling(workDir.getFileName() + "_cpp_extractor").resolve("extract_cpp.py");
        Files.createDirectories(script.getParent());
        try (InputStream in = CppExtractionService.class.getResourceAsStream("/extract_cpp.py")) {
            if (in == null) throw new JavaExtractionException("extract_cpp.py not found in classpath");
            Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
        }
        return script.toString();
    }

    private static void readOutput(Process process, StringBuilder output) {
        try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
            }
        } catch (Exception ignored) { }
    }

    private static String tail(String output) {
        String normalized = output.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(normalized.length() - 500);
    }

    private static List<FieldModel> mapFields(List<RawField> rawFields) {
        if (rawFields == null) return List.of();
        return rawFields.stream().map(rf -> new FieldModel(
                rf.name, rf.type != null ? rf.type : "unknown",
                rf.visibility != null ? rf.visibility : "public",
                Boolean.TRUE.equals(rf.staticField),
                Boolean.TRUE.equals(rf.finalField),
                rf.line)).collect(Collectors.toList());
    }

    private static List<ExecutableModel> mapExecutables(List<RawExecutable> rawExs) {
        if (rawExs == null) return List.of();
        return rawExs.stream().map(re -> new ExecutableModel(
                re.name, re.returnType != null ? re.returnType : "void",
                re.visibility != null ? re.visibility : "public",
                Boolean.TRUE.equals(re.constructor),
                Boolean.TRUE.equals(re.staticExecutable),
                Boolean.TRUE.equals(re.abstractExecutable),
                re.parameters != null ? re.parameters.stream()
                        .map(rp -> new ParameterModel(
                                rp.name != null ? rp.name : "unknown",
                                rp.type != null ? rp.type : "unknown"))
                        .collect(Collectors.toList()) : List.of(),
                re.line)).collect(Collectors.toList());
    }

    @SuppressWarnings("unused")
    private static final class RawResult { String error; List<RawType> types; List<RawDiagnostic> diagnostics; List<String> sourceRoots; }
    @SuppressWarnings("unused")
    private static final class RawType { String qualifiedName; String simpleName; String name; String kind; String superClass;
        List<String> interfaces; List<RawField> fields; List<RawExecutable> executables;
        Boolean abstractType; Boolean finalType; String sourceFile; Integer line; }
    @SuppressWarnings("unused")
    private static final class RawField { String name; String type; String visibility; Boolean staticField; Boolean finalField; Integer line; }
    @SuppressWarnings("unused")
    private static final class RawExecutable { String name; String returnType; String visibility; Boolean constructor;
        Boolean staticExecutable; Boolean abstractExecutable; List<RawParameter> parameters; Integer line; }
    @SuppressWarnings("unused")
    private static final class RawParameter { String name; String type; }
    @SuppressWarnings("unused")
    private static final class RawDiagnostic { String severity; String code; String message; }
}
