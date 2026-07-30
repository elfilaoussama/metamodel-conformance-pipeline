package com.verification;

import eu.modelwriter.core.alloyinecore.recognizer.AlloyInEcoreLexer;
import eu.modelwriter.core.alloyinecore.recognizer.AlloyInEcoreParser;
import org.antlr.v4.runtime.*;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EPackage;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AlloyInEcore Verification Pipeline.
 *
 * Usage:
 *   ./run.sh -i <instance.json>              (uses default .recore)
 *   ./run.sh -r <model.recore> -i <file>     (custom .recore)
 *   ./run.sh --help
 *
 * All generated files go to output/ by default.
 */
public class Main {

    private static final String DEFAULT_OUTPUT_DIR = "output";

    public static void main(String[] args) {
        String recorePath = "src/main/resources/StructuralMetamodel.recore";
        String instancePath = null;
        String outputDir = DEFAULT_OUTPUT_DIR;
        boolean help = false;
        boolean details = false;
        String reportPath = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-r": case "--recore":
                    if (i + 1 < args.length) recorePath = args[++i];
                    break;
                case "-i": case "--instance":
                    if (i + 1 < args.length) instancePath = args[++i];
                    break;
                case "-o": case "--output":
                    if (i + 1 < args.length) outputDir = args[++i];
                    break;
                case "--details":
                    details = true;
                    break;
                case "--report":
                    if (i + 1 < args.length) reportPath = args[++i];
                    break;
                case "-h": case "--help":
                    help = true;
                    break;
            }
        }

        if (help) {
            System.out.println("AlloyInEcore Verification Pipeline");
            System.out.println("Usage: ./run.sh [options]");
            System.out.println("  -r, --recore <path>    Path to .recore metamodel (default: ClassHierarchies.recore)");
            System.out.println("  -i, --instance <path>  Path to instance model (.json, .aie, .xmi)");
            System.out.println("  -o, --output <dir>     Output directory (default: output/)");
            System.out.println("      --details          Print broken rules (UNSAT core) when UNSAT");
            System.out.println("      --report <path>    Write JSON report (SAT/UNSAT + violations)");
            System.out.println("  -h, --help             Show this help");
            return;
        }

        // Track summary fields
        String summaryMetamodel = recorePath;
        String summaryInstance = instancePath != null ? instancePath : "(none)";
        String summaryEcore = "";
        String summaryMappedAie = "";
        String summaryResult = "SKIPPED";
        String summarySolverOutput = "";

        try {
            // Ensure output directory exists
            File outDir = new File(outputDir);
            outDir.mkdirs();

            // ─── Step 1: Read .recore ───
            File recoreFile = new File(recorePath);
            if (!recoreFile.exists()) {
                System.err.println("ERROR: .recore not found: " + recorePath);
                return;
            }
            System.out.println("[1/3] Parsing metamodel: " + recoreFile.getName());
            String source = new String(Files.readAllBytes(Paths.get(recoreFile.getAbsolutePath())));

            // ─── Step 2: Parse with AlloyInEcore ───
            CharStream input = CharStreams.fromString(source);
            AlloyInEcoreLexer lexer = new AlloyInEcoreLexer(input);
            CommonTokenStream tokens = new CommonTokenStream(lexer);

            // On Windows, absolute paths contain backslashes; using java.net.URI ensures
            // a normalized file:/C:/... URI that EMF can reliably resolve.
            URI recoreURI = URI.createURI(recoreFile.toURI().toString());
            AlloyInEcoreParser parser = new AlloyInEcoreParser(tokens, recoreURI);

            parser.removeErrorListeners();
            parser.addErrorListener(new BaseErrorListener() {
                @Override
                public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                        int line, int charPositionInLine, String msg,
                                        RecognitionException e) {
                    System.err.println("  PARSE ERROR line " + line + ":" + charPositionInLine + " " + msg);
                }
            });

            // Suppress verbose parser output
        java.io.PrintStream originalOut = System.out;
        java.io.PrintStream originalErr = System.err;
        java.io.PrintStream nullStream = new java.io.PrintStream(new java.io.OutputStream() { public void write(int b) {} });
        System.setOut(nullStream);
        System.setErr(nullStream);
        try {
            parser.model(null);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
            if (parser.model == null) {
                System.err.println("ERROR: Parser returned null model.");
                return;
            }

            EPackage ePackage = (EPackage) parser.model.getOwnedPackage().getEObject();

            // ── Patch broken ECore schema (enum types not resolved by AlloyInEcore) ──
            patchEcoreSchema(ePackage);

            org.eclipse.emf.ecore.util.EcoreUtil.resolveAll(ePackage);
            EPackage.Registry.INSTANCE.put(ePackage.getNsURI(), ePackage);

            // Save .ecore to output/
            String baseName = recoreFile.getName().replace(".recore", "");
            String absOutputDir = outDir.getAbsolutePath() + File.separator;
            
            System.setOut(nullStream);
            System.setErr(nullStream);
            try {
                parser.saveResource(baseName, absOutputDir);
                org.eclipse.emf.ecore.util.EcoreUtil.resolveAll(ePackage);
            } finally {
                System.setOut(originalOut);
                System.setErr(originalErr);
            }

            String ecoreFile = absOutputDir + baseName + ".ecore";
            summaryEcore = ecoreFile;
            System.out.println("      → " + ecoreFile);

            // ─── Step 3: Verify instance (optional) ───
            if (instancePath != null) {
                // Auto-map JSON to AIE
                if (instancePath.endsWith(".json")) {
                    System.out.println("[2/3] Mapping JSON → AIE instance...");
                    String mappedAie = absOutputDir + "MappedInstance.aie";
                    com.verification.mapper.JsonToAieMapper.map(instancePath, mappedAie);
                    summaryMappedAie = mappedAie;
                    instancePath = mappedAie;
                    System.out.println("      → " + mappedAie);
                }

                System.out.println("[3/3] Verifying invariants...");
                String ecoreAbsPath = new File(ecoreFile).getAbsolutePath();
                if (details || reportPath != null) {
                    VerificationReport report = new VerificationReport();
                    VerificationReport finalReport = InvariantChecker.check(instancePath, ecoreAbsPath, absOutputDir, report);
                    summaryResult = finalReport.getResult();

                    enrichInvariantNames(finalReport, recorePath);

                    if (details) {
                        printBrokenRules(finalReport);
                    }
                    if (reportPath != null) {
                        writeReportJson(finalReport, reportPath);
                    }
                } else {
                    String result = InvariantChecker.check(instancePath, ecoreAbsPath, absOutputDir);
                    summaryResult = result;
                }
            } else {
                System.out.println("[2/3] Skipped (no instance provided)");
                System.out.println("[3/3] Skipped");
            }

        } catch (Throwable t) {
            summaryResult = "ERROR (" + t.getClass().getSimpleName() + ")";
            System.err.println("\nERROR: Verification failed with throwable:");
            t.printStackTrace(System.err);
        } finally {
            // ─── Summary ───
            System.out.println();
            System.out.println("══════════════════════════════════════════");
            System.out.println("  VERIFICATION SUMMARY");
            System.out.println("══════════════════════════════════════════");
            System.out.println("  Metamodel:    " + summaryMetamodel);
            System.out.println("  Instance:     " + summaryInstance);
            System.out.println("  .ecore:       " + summaryEcore);
            if (!summaryMappedAie.isEmpty())
                System.out.println("  Mapped .aie:  " + summaryMappedAie);
            System.out.println("  Result:       " + summaryResult);
            System.out.println("  Output dir:   " + new File(outputDir).getAbsolutePath());
            System.out.println("══════════════════════════════════════════");

            // Force exit to prevent EMF background threads from printing InterruptedException,
            // but propagate failure to the caller.
            int exitCode = (summaryResult != null && summaryResult.startsWith("ERROR")) ? 1 : 0;
            System.exit(exitCode);
        }
    }

    private static void printBrokenRules(VerificationReport report) {
        if (report == null || !"UNSAT".equals(report.getResult())) {
            return;
        }
        if (report.getViolations() == null || report.getViolations().isEmpty()) {
            System.out.println("\nBroken rules: (no UNSAT core details available)");
            return;
        }
        System.out.println("\nBroken rules (UNSAT core):");
        for (VerificationReport.Violation v : report.getViolations()) {
            String where = (v.getLine() != null) ? ("line " + v.getLine()) : "(unknown line)";
            String name = (v.getInvariantName() != null && !v.getInvariantName().trim().isEmpty())
                    ? v.getInvariantName().trim()
                    : null;
            String desc = (v.getDescription() != null && !v.getDescription().trim().isEmpty())
                    ? v.getDescription().trim()
                    : (v.getFormula() != null ? v.getFormula() : "(no description)");
            if (name != null) {
                System.out.println("  - " + where + ": " + name + " — " + desc);
            } else {
                System.out.println("  - " + where + ": " + desc);
            }
        }
    }

    private static void enrichInvariantNames(VerificationReport report, String recorePath) {
        if (report == null || report.getViolations() == null || report.getViolations().isEmpty()) {
            return;
        }
        if (recorePath == null || recorePath.trim().isEmpty()) {
            return;
        }

        File recoreFile = new File(recorePath);
        if (!recoreFile.exists()) {
            return;
        }

        final Pattern invPattern = Pattern.compile("^\\s*invariant\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*:");

        try {
            List<String> lines = Files.readAllLines(Paths.get(recoreFile.getAbsolutePath()), StandardCharsets.UTF_8);

            for (VerificationReport.Violation v : report.getViolations()) {
                if (v == null || v.getInvariantName() != null) {
                    continue;
                }
                if (v.getLine() == null || v.getLine() < 1 || v.getLine() > lines.size()) {
                    continue;
                }

                int idx = v.getLine() - 1;
                String found = null;
                // Search backwards a bit in case formatting spans lines.
                for (int i = idx; i >= 0 && i >= idx - 10; i--) {
                    String s = lines.get(i);
                    Matcher m = invPattern.matcher(s);
                    if (m.find()) {
                        found = m.group(1);
                        break;
                    }
                }
                v.setInvariantName(found);
            }
        } catch (IOException ignored) {
            // Best-effort enrichment; keep report usable even if file reading fails.
        }
    }

    private static void writeReportJson(VerificationReport report, String reportPath) throws IOException {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        File out = new File(reportPath);
        File parent = out.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create report directory: " + parent);
        }
        Files.write(Paths.get(out.getAbsolutePath()), gson.toJson(report).getBytes(StandardCharsets.UTF_8));
        System.out.println("\nWrote report: " + out.getAbsolutePath());
    }

    /**
     * Fix enum types that AlloyInEcore's parser leaves unresolved in the generated EPackage.
     */
    private static void patchEcoreSchema(EPackage ePackage) {
        org.eclipse.emf.ecore.EClass cMethod = (org.eclipse.emf.ecore.EClass) ePackage.getEClassifier("Method");
        org.eclipse.emf.ecore.EClass cAttribute = (org.eclipse.emf.ecore.EClass) ePackage.getEClassifier("Attribute");
        org.eclipse.emf.ecore.EClass cClassifier = (org.eclipse.emf.ecore.EClass) ePackage.getEClassifier("Classifier");
        org.eclipse.emf.ecore.EClass cBody = (org.eclipse.emf.ecore.EClass) ePackage.getEClassifier("MethodBody");
        org.eclipse.emf.ecore.EClass cBinding = (org.eclipse.emf.ecore.EClass) ePackage.getEClassifier("ImplementationBinding");
        org.eclipse.emf.ecore.EClass cRoot = (org.eclipse.emf.ecore.EClass) ePackage.getEClassifier("Root");
        org.eclipse.emf.ecore.EClass cType = (org.eclipse.emf.ecore.EClass) ePackage.getEClassifier("Type");
        org.eclipse.emf.ecore.EClass cClassifierType = (org.eclipse.emf.ecore.EClass) ePackage.getEClassifier("ClassifierType");
        org.eclipse.emf.ecore.EClass cObject = (org.eclipse.emf.ecore.EClass) ePackage.getEClassifier("Object");
        org.eclipse.emf.ecore.EClass cName = (org.eclipse.emf.ecore.EClass) ePackage.getEClassifier("Name");

        // Enum types
        org.eclipse.emf.ecore.EClassifier vis = ePackage.getEClassifier("Visibility");
        org.eclipse.emf.ecore.EClassifier scope = ePackage.getEClassifier("Scope");
        org.eclipse.emf.ecore.EClassifier yesno = ePackage.getEClassifier("YesNo");

        // Method
        if (cMethod != null) {
            setEType(cMethod, "visibility", vis);
            setEType(cMethod, "scope", scope);
            setEType(cMethod, "isAbstract", yesno);
        }
        // Attribute
        if (cAttribute != null) {
            setEType(cAttribute, "visibility", vis);
            setEType(cAttribute, "scope", scope);
        }
        // Classifier
        if (cClassifier != null) {
            setEType(cClassifier, "isAbstract", yesno);
            setEType(cClassifier, "classParent", cClassifier);
            setEType(cClassifier, "localMethods", cMethod);
            setEType(cClassifier, "localAttributes", cAttribute);
            setEType(cClassifier, "inheritedMethods", cMethod);
            setEType(cClassifier, "inheritedAttributes", cAttribute);
            setEType(cClassifier, "directInstances", cObject);
        }
        // ImplementationBinding
        if (cBinding != null) {
            setEType(cBinding, "implementer", cClassifier);
            setEType(cBinding, "target", cMethod);
            setEType(cBinding, "body", cBody);
        }
        // ClassifierType
        if (cClassifierType != null) {
            setEType(cClassifierType, "classifier", cClassifier);
        }
        // Root
        if (cRoot != null && cClassifier != null) {
            setEType(cRoot, "contents", cClassifier);
        }
    }

    private static void setEType(org.eclipse.emf.ecore.EClass cls, String featureName, org.eclipse.emf.ecore.EClassifier type) {
        if (cls == null || featureName == null || type == null) return;
        org.eclipse.emf.ecore.EStructuralFeature feature = cls.getEStructuralFeature(featureName);
        if (feature != null && feature.getEType() == null) {
            feature.setEType(type);
        }
    }
}
