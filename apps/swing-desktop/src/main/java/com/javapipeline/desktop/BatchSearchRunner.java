package com.javapipeline.desktop;

import com.javapipeline.core.*;
import com.javapipeline.core.model.ExtractionResult;
import com.javapipeline.core.search.*;
import com.javapipeline.github.JGitHubRepositoryIngestionService;
import com.javapipeline.github.search.GitHubRestRepositorySearchService;
import com.javapipeline.python.PythonExtractionService;
import com.javapipeline.spoon.ExtractionJsonWriter;
import com.javapipeline.spoon.SpoonJavaExtractionService;
import com.javapipeline.verification.AlloyInEcoreVerificationService;
import com.javapipeline.verification.VerificationOutcome;
import com.javapipeline.verification.VerificationRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * Batch pipeline runner using parameterized GitHub search.
 * Searches GitHub for repositories matching criteria, then runs the full
 * pipeline (clone → extract → verify) for each, aggregating CSV results.
 *
 * <pre>
 * java -cp pipeline.jar com.javapipeline.desktop.BatchSearchRunner \
 *   --language Java --min-stars 10 --limit 50 \
 *   --output analysis-output
 * </pre>
 */
public final class BatchSearchRunner {

    private static final Path WORKSPACE = Path.of("workspace/repositories");
    private static final Path VERIFIER = Path.of("modules/verification-cli");
    private static final Path METAMODEL =
            VERIFIER.resolve("src/main/resources/metamodel.als");

    private final Path outputRoot;
    private final Path combinedCsv;
    private int sat, unsat, errors;

    public BatchSearchRunner(Path outputRoot) {
        this.outputRoot = outputRoot;
        this.combinedCsv = outputRoot.resolve("verification-combined.csv");
    }

    public void run(GitHubSearchCriteria criteria) throws Exception {
        Files.createDirectories(outputRoot);
        Files.writeString(combinedCsv,
                "Repository,Result,Constraint,Line,Description\n", StandardCharsets.UTF_8);

        System.out.println("Searching GitHub: language=" + criteria.language()
                + " minStars=" + criteria.minStars() + " limit=" + criteria.resultLimit());
        GitHubRestRepositorySearchService search = new GitHubRestRepositorySearchService();
        GitHubSearchResponse response = search.search(criteria, null,
                ProgressListener.NONE, CancellationToken.NONE);

        List<GitHubRepositorySummary> repos = response.repositories();
        System.out.println("Found " + repos.size() + " repos (total matches: "
                + response.totalCount() + ")");
        if (response.incomplete()) System.out.println("WARNING: results incomplete");

        int n = 0;
        for (GitHubRepositorySummary repo : repos) {
            n++;
            String label = repo.fullName().replace('/', '_');
            System.out.println("[" + n + "/" + repos.size() + "] " + label
                    + " (" + repo.language() + ", " + repo.stars() + " stars)");
            try {
                processOne(repo.cloneUrl(), label);
            } catch (Exception e) {
                System.err.println("  ERROR: " + e.getMessage());
                errors++;
            }
        }

        System.out.println("\nDone. SAT=" + sat + " UNSAT=" + unsat + " ERROR=" + errors);
        System.out.println("Combined CSV: " + combinedCsv.toAbsolutePath());
    }

    private void processOne(String cloneUrl, String label) throws Exception {
        ExistingRepositoryPolicy policy = ExistingRepositoryPolicy.REUSE;
        RepositoryRequest req = RepositoryRequest.fromGitHubUrl(cloneUrl, 1, policy);
        JGitHubRepositoryIngestionService ingestion = new JGitHubRepositoryIngestionService();
        IngestedRepository repo = ingestion.ingest(req, WORKSPACE, ev -> {}, () -> false);

        Language lang = AnalysisFrame.detectLanguage(repo.directory());
        JavaExtractionService extractor = lang == Language.PYTHON
                ? new PythonExtractionService() : new SpoonJavaExtractionService();
        ExtractionOptions opts = ExtractionOptions.forLanguage(lang);
        ExtractionResult result = extractor.extract(
                req.coordinate(), repo.directory(), opts, ev -> {}, () -> false);

        Path outDir = outputRoot.resolve(label);
        Files.createDirectories(outDir);
        new ExtractionJsonWriter().write(result, outDir.resolve("extraction.json"));

        AlloyInEcoreVerificationService verifier = new AlloyInEcoreVerificationService();
        Path vDir = outDir.resolve("verification");
        VerificationOutcome outcome = verifier.verify(
                new VerificationRequest(VERIFIER, METAMODEL,
                        outDir.resolve("extraction.json"), vDir),
                ev -> {}, () -> false);

        Path csvPath = vDir.resolve("verification-report.csv");
        if (Files.isRegularFile(csvPath)) {
            List<String> lines = Files.readAllLines(csvPath, StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (!line.isEmpty()) sb.append(label).append(",").append(line).append("\n");
            }
            Files.writeString(combinedCsv, sb.toString(),
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
            if (outcome.status() == VerificationOutcome.Status.SAT) sat++;
            else unsat++;
            System.out.println("  -> " + outcome.status() + " (" + (lines.size() - 1) + " rows)");
        } else {
            System.err.println("  -> CSV not found");
            errors++;
        }
    }

    // ---- CLI ----

    public static void main(String[] args) {
        String language = "Java";
        Integer minStars = null;
        int limit = 30;
        Path output = Path.of("analysis-output");

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-l", "--language" -> { if (++i < args.length) language = args[i]; }
                case "-s", "--min-stars" -> {
                    if (++i < args.length) minStars = Integer.parseInt(args[i]);
                }
                case "-n", "--limit" -> {
                    if (++i < args.length) limit = Integer.parseInt(args[i]);
                }
                case "-o", "--output" -> {
                    if (++i < args.length) output = Path.of(args[i]);
                }
                case "-h", "--help" -> {
                    System.out.println("""
                        BatchSearchRunner — GitHub search + pipeline automation
                        -l, --language <lang>   Language filter (default: Java)
                        -s, --min-stars <n>     Minimum stars (default: none)
                        -n, --limit <n>         Max repos (default: 30)
                        -o, --output <dir>      Output directory (default: analysis-output)
                        -h, --help              Show this help""");
                    return;
                }
            }
        }

        GitHubSearchCriteria criteria = new GitHubSearchCriteria(
                "", language, "", "", "", minStars, null, null, null, null, null,
                LocalDate.of(2020, 1, 1), null,
                GitHubSearchCriteria.ForkMode.EXCLUDE,
                GitHubSearchCriteria.ArchiveMode.EXCLUDE,
                GitHubSearchCriteria.Sort.STARS,
                GitHubSearchCriteria.Order.DESCENDING,
                limit);

        try {
            new BatchSearchRunner(output).run(criteria);
        } catch (Exception e) {
            System.err.println("Fatal: " + e.getMessage());
            e.printStackTrace();
            System.exit(2);
        }
    }
}
