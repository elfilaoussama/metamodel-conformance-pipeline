package com.javapipeline.desktop;

import com.javapipeline.core.*;
import com.javapipeline.core.model.ExtractionResult;
import com.javapipeline.github.JGitHubRepositoryIngestionService;
import com.javapipeline.python.PythonExtractionService;
import com.javapipeline.spoon.ExtractionJsonWriter;
import com.javapipeline.spoon.SpoonJavaExtractionService;
import com.javapipeline.verification.AlloyInEcoreVerificationService;
import com.javapipeline.verification.VerificationOutcome;
import com.javapipeline.verification.VerificationRequest;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * CLI pipeline entry point. Runs clone → extract → verify for a single repository.
 *
 * <pre>
 * java com.javapipeline.desktop.PipelineCli
 *   --repo https://github.com/user/repo
 *   [--language java|python|cpp]
 *   [--output analysis-output]
 *   [--workspace workspace/repositories]
 *   [--metamodel modules/verification-cli/src/main/resources/kernel_v2_obligation.als]
 *   [--verifier modules/verification-cli]
 *   [--depth 1]
 *   [--no-verify]
 *   [--help]
 * </pre>
 */
public final class PipelineCli {

    private static final String DEFAULT_WORKSPACE = "workspace/repositories";
    private static final String DEFAULT_OUTPUT = "analysis-output";
    private static final String DEFAULT_VERIFIER = "modules/verification-cli";
    private static final String DEFAULT_METAMODEL =
            DEFAULT_VERIFIER + "/src/main/resources/kernel_v2_obligation.als";

    private final PrintStream out;
    private final PrintStream err;

    public PipelineCli(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    public int run(String... args) {
        CliConfig cfg = parse(args);
        if (cfg == null) return 1;
        if (cfg.help) { printHelp(); return 0; }
        if (cfg.repo == null || cfg.repo.isBlank()) {
            err.println("Error: --repo is required. Use --help for usage.");
            return 1;
        }

        try {
            out.println("[1/4] Cloning " + cfg.repo + " ...");
            ExistingRepositoryPolicy policy = ExistingRepositoryPolicy.REUSE;
            RepositoryRequest request = RepositoryRequest.fromGitHubUrl(cfg.repo, cfg.depth, policy);
            JGitHubRepositoryIngestionService ingestion = new JGitHubRepositoryIngestionService();
            IngestedRepository repo = ingestion.ingest(request, cfg.workspace, ev -> {}, () -> false);
            out.println("      -> " + (repo.reused() ? "Reused " : "Cloned ")
                    + request.coordinate() + " at " + repo.revision());

            Language lang = cfg.language != null ? cfg.language
                    : AnalysisFrame.detectLanguage(repo.directory());
            out.println("[2/4] Extracting (" + lang + ") ...");

            JavaExtractionService extractor = lang == Language.PYTHON
                    ? new PythonExtractionService() : new SpoonJavaExtractionService();
            ExtractionOptions opts = ExtractionOptions.forLanguage(lang);
            ExtractionResult result = extractor.extract(
                    request.coordinate(), repo.directory(), opts, ev -> {}, () -> false);
            Path outputDir = cfg.output.resolve(request.owner() + "__" + request.name());
            Files.createDirectories(outputDir);
            Path extractionJson = outputDir.resolve("extraction.json");
            new ExtractionJsonWriter().write(result, extractionJson);
            out.println("      -> " + result.types().size() + " types extracted to " + extractionJson);

            if (!cfg.verify) {
                out.println("[3/4] Verification skipped (--no-verify).");
                out.println("[4/4] Done.");
                return 0;
            }

            out.println("[3/4] Verifying with " + cfg.metamodel.getFileName() + " ...");
            AlloyInEcoreVerificationService verifier = new AlloyInEcoreVerificationService();
            Path verificationDir = outputDir.resolve("verification");
            VerificationOutcome outcome = verifier.verify(
                    new VerificationRequest(cfg.verifier, cfg.metamodel,
                            extractionJson, verificationDir),
                    ev -> {}, () -> false);

            out.println("[4/4] Done.");
            out.println();
            out.println("Result: " + outcome.status());
            if (!outcome.violations().isEmpty()) {
                out.println("Violations: " + outcome.violations().size());
                for (VerificationOutcome.Violation v : outcome.violations()) {
                    out.println("  - [" + (v.invariantName() != null ? v.invariantName() : "?")
                            + "] " + (v.description() != null ? v.description() : "unspecified"));
                }
            }
            out.println("JSON report: " + verificationDir.resolve("verification-report.json"));
            out.println("CSV report:  " + verificationDir.resolve("verification-report.csv"));

            return outcome.status() == VerificationOutcome.Status.UNSAT ? 1 : 0;
        } catch (Exception e) {
            err.println("Pipeline error: " + e.getMessage());
            return 2;
        }
    }

    private CliConfig parse(String[] args) {
        CliConfig cfg = new CliConfig();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-h", "--help" -> { cfg.help = true; return cfg; }
                case "-r", "--repo" -> { if (++i < args.length) cfg.repo = args[i]; }
                case "-l", "--language" -> {
                    if (++i < args.length) cfg.language = parseLanguage(args[i]);
                }
                case "-o", "--output" -> { if (++i < args.length) cfg.output = Path.of(args[i]); }
                case "-w", "--workspace" -> { if (++i < args.length) cfg.workspace = Path.of(args[i]); }
                case "-m", "--metamodel" -> { if (++i < args.length) cfg.metamodel = Path.of(args[i]); }
                case "-v", "--verifier" -> { if (++i < args.length) cfg.verifier = Path.of(args[i]); }
                case "-d", "--depth" -> {
                    if (++i < args.length) {
                        try { cfg.depth = Integer.parseInt(args[i]); }
                        catch (NumberFormatException e) { cfg.depth = 1; }
                    }
                }
                case "--no-verify" -> cfg.verify = false;
                default -> {
                    err.println("Unknown option: " + args[i] + ". Use --help for usage.");
                    return null;
                }
            }
        }
        return cfg;
    }

    private Language parseLanguage(String s) {
        try { return Language.valueOf(s.toUpperCase()); }
        catch (IllegalArgumentException e) {
            err.println("Unknown language: " + s + ". Use java, python, or cpp.");
            return null;
        }
    }

    private void printHelp() {
        out.println("""
                Java Analysis Pipeline CLI
                                
                USAGE:
                  java -jar pipeline.jar --repo <url> [options]
                  pipeline.ps1 --repo <url> [options]
                  pipeline.sh  --repo <url> [options]
                                
                REQUIRED:
                  -r, --repo <url>         GitHub repository URL
                                
                OPTIONS:
                  -l, --language <lang>    java | python | cpp (auto-detected if omitted)
                  -o, --output <dir>       Output directory (default: analysis-output)
                  -w, --workspace <dir>    Clone workspace (default: workspace/repositories)
                  -m, --metamodel <file>   Alloy .als metamodel path
                  -v, --verifier <dir>     Verification module directory
                  -d, --depth <n>          Clone depth (default: 1)
                  --no-verify              Skip verification after extraction
                  -h, --help               Show this help
                                
                EXIT CODES:
                  0    SAT (all invariants hold)
                  1    UNSAT (one or more violations)
                  2    ERROR (pipeline failure)
                                
                EXAMPLES:
                  pipeline.ps1 --repo https://github.com/user/javaproject
                  pipeline.ps1 --repo https://github.com/user/pythonlib --language python
                  pipeline.ps1 --repo https://github.com/user/lib --no-verify -o results/
                """);
    }

    private static final class CliConfig {
        String repo;
        Language language;
        Path output = Path.of(DEFAULT_OUTPUT);
        Path workspace = Path.of(DEFAULT_WORKSPACE);
        Path metamodel = Path.of(DEFAULT_METAMODEL);
        Path verifier = Path.of(DEFAULT_VERIFIER);
        int depth = 1;
        boolean verify = true;
        boolean help;
    }

    public static void main(String[] args) {
        System.exit(new PipelineCli(System.out, System.err).run(args));
    }
}
