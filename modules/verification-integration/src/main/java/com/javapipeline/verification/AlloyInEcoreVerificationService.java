package com.javapipeline.verification;

import com.google.gson.Gson;
import com.javapipeline.core.CancellationToken;
import com.javapipeline.core.ProgressEvent;
import com.javapipeline.core.ProgressListener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class AlloyInEcoreVerificationService {
    private static final Duration TIMEOUT = Duration.ofMinutes(15);
    private final Gson gson = new Gson();

    public VerificationOutcome readExisting(Path outputDirectory) throws VerificationException {
        Path json = outputDirectory.resolve("verification-report.json");
        Path csv = outputDirectory.resolve("verification-report.csv");
        try {
            if (!Files.isRegularFile(json) || !Files.isRegularFile(csv)) {
                throw new VerificationException("Cached verification report is incomplete");
            }
            return readOutcome(json, csv, "Reused cached verification result");
        } catch (VerificationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new VerificationException("Cannot read cached verification report", ex);
        }
    }

    public VerificationOutcome verify(VerificationRequest request, ProgressListener progress,
                                      CancellationToken cancellation) throws VerificationException {
        ProgressListener listener = progress == null ? ProgressListener.NONE : progress;
        CancellationToken token = cancellation == null ? CancellationToken.NONE : cancellation;
        validate(request);
        try {
            Files.createDirectories(request.outputDirectory());
            Path json = request.outputDirectory().resolve("verification-report.json");
            Path csv = request.outputDirectory().resolve("verification-report.csv");
            listener.onProgress(new ProgressEvent(ProgressEvent.Stage.VERIFYING,
                    "Mapping Spoon model and checking AlloyInEcore constraints", 0, 1));

            Process process = new ProcessBuilder(command(request, json, csv))
                    .directory(request.verifierHome().toFile())
                    .redirectErrorStream(true)
                    .start();
            StringBuilder output = new StringBuilder();
            Thread reader = new Thread(() -> readOutput(process, output), "alloy-verifier-output");
            reader.setDaemon(true);
            reader.start();
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (process.isAlive()) {
                if (token.isCancellationRequested()) {
                    process.destroyForcibly();
                    throw new VerificationException("Verification cancelled");
                }
                if (System.nanoTime() >= deadline) {
                    process.destroyForcibly();
                    throw new VerificationException("Verification exceeded the 15 minute safety timeout");
                }
                process.waitFor(150, TimeUnit.MILLISECONDS);
            }
            reader.join(2_000);
            if (process.exitValue() != 0 || !Files.isRegularFile(json)) {
                throw new VerificationException("AlloyInEcore verifier failed (exit " + process.exitValue()
                        + "). " + tail(output.toString()));
            }
            VerificationOutcome outcome = readOutcome(json, csv, output.toString());
            VerificationOutcome.Status status = outcome.status();
            List<VerificationOutcome.Violation> violations = outcome.violations();
            listener.onProgress(new ProgressEvent(ProgressEvent.Stage.VERIFYING,
                    status == VerificationOutcome.Status.SAT ? "All constraints hold"
                            : violations.size() + " constraint violation(s) reported", 1, 1));
            return outcome;
        } catch (VerificationException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new VerificationException("Verification interrupted", ex);
        } catch (Exception ex) {
            throw new VerificationException("Cannot run AlloyInEcore verification: " + ex.getMessage(), ex);
        }
    }

    private VerificationOutcome readOutcome(Path json, Path csv, String processOutput) throws IOException {
        RawReport raw = gson.fromJson(Files.readString(json, StandardCharsets.UTF_8), RawReport.class);
        VerificationOutcome.Status status = parseStatus(raw == null ? null : raw.result);
        List<VerificationOutcome.Violation> violations = new ArrayList<>();
        if (raw != null && raw.violations != null) {
            for (RawViolation violation : raw.violations) {
                violations.add(new VerificationOutcome.Violation(violation.line,
                        violation.invariantName, violation.description, violation.formula));
            }
        }
        return new VerificationOutcome(status, violations, json, csv, processOutput);
    }

    private static void validate(VerificationRequest request) throws VerificationException {
        if (!Files.isDirectory(request.verifierHome())) throw new VerificationException("Verifier folder not found: " + request.verifierHome());
        if (!Files.isRegularFile(request.metamodel())) throw new VerificationException("AlloyInEcore metamodel not found: " + request.metamodel());
        if (!Files.isRegularFile(request.extractionJson())) throw new VerificationException("Spoon extraction not found: " + request.extractionJson());
        if (!request.metamodel().getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".recore")) {
            throw new VerificationException("Metamodel must be an AlloyInEcore .recore file");
        }
    }

    private static List<String> command(VerificationRequest request, Path json, Path csv) {
        List<String> command = new ArrayList<>();
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            command.add("powershell"); command.add("-NoProfile"); command.add("-ExecutionPolicy");
            command.add("Bypass"); command.add("-File"); command.add(request.verifierHome().resolve("run.ps1").toString());
        } else {
            command.add("bash"); command.add(request.verifierHome().resolve("run.sh").toString());
        }
        command.add("-r"); command.add(request.metamodel().toString());
        command.add("-i"); command.add(request.extractionJson().toString());
        command.add("-o"); command.add(request.outputDirectory().toString());
        command.add("--strict"); command.add("--details"); command.add("--report"); command.add(json.toString());
        command.add("--csv"); command.add(csv.toString());
        return command;
    }

    private static void readOutput(Process process, StringBuilder output) {
        try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) output.append(line).append(System.lineSeparator());
        } catch (IOException ignored) { }
    }

    private static String tail(String output) {
        String normalized = output.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 800 ? normalized : normalized.substring(normalized.length() - 800);
    }

    private static VerificationOutcome.Status parseStatus(String value) {
        if ("SAT".equalsIgnoreCase(value)) return VerificationOutcome.Status.SAT;
        if ("UNSAT".equalsIgnoreCase(value)) return VerificationOutcome.Status.UNSAT;
        return VerificationOutcome.Status.ERROR;
    }

    private static final class RawReport { String result; List<RawViolation> violations; }
    private static final class RawViolation { Integer line; String invariantName; String description; String formula; }
}
