package com.verification;

import eu.modelwriter.core.alloyinecore.interpreter.FormulaInfo;
import eu.modelwriter.core.alloyinecore.interpreter.KodKodUniverse;
import eu.modelwriter.core.alloyinecore.recognizer.AlloyInEcoreLexer;
import eu.modelwriter.core.alloyinecore.recognizer.AlloyInEcoreParser;
import eu.modelwriter.core.alloyinecore.translator.EcoreInstanceTranslator;
import kodkod.ast.BinaryFormula;
import kodkod.ast.Formula;
import kodkod.engine.Proof;
import kodkod.engine.Solution;
import kodkod.engine.Solver;
import kodkod.engine.satlab.SATFactory;
import kodkod.engine.ucore.RCEStrategy;
import kodkod.ast.NaryFormula;
import kodkod.ast.operator.FormulaOperator;
import kodkod.instance.Bounds;
import org.antlr.v4.runtime.*;
import org.eclipse.emf.common.util.URI;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Evaluates metamodel invariants against a concrete instance model.
 *
 * Accepts .aie (AlloyInEcore text) or .xmi (EMF XMI) instance files.
 * In .aie files, the literal ECORE_PATH is replaced with the actual .ecore path.
 *
 * Pipeline: text → ANTLR parse → KodKodUniverse → Kodkod SAT/UNSAT
 */
public class InvariantChecker {

    public static final String ECORE_PLACEHOLDER = "ECORE_PATH";

    /** Guards System.setOut to prevent concurrent calls from corrupting each other's stdout. */
    private static final Object STDOUT_LOCK = new Object();

    /**
     * @param instancePath  path to .aie or .xmi instance file
     * @param ecoreAbsPath  absolute path to generated .ecore
     * @param outputDir     directory for solver output files
     * @return "SAT" or "UNSAT" result string
     */
    public static String check(String instancePath, String ecoreAbsPath, String outputDir) throws Exception {
        return checkWithReport(instancePath, ecoreAbsPath, outputDir, null).result;
    }

    /**
     * Runs verification and (optionally) fills a report with UNSAT core details.
     */
    public static VerificationReport checkWithReport(String instancePath, String ecoreAbsPath, String outputDir, VerificationReport report) throws Exception {
        VerificationReport effectiveReport = (report != null) ? report : new VerificationReport();

        effectiveReport.result = null;
        if (effectiveReport.violations != null) {
            effectiveReport.violations.clear();
        }

        File instanceFile = new File(instancePath).getAbsoluteFile();
        if (!instanceFile.exists())
            throw new IllegalArgumentException("Instance file not found: " + instancePath);

        // ── Read / translate instance text ──
        String aieText;
        if (instancePath.endsWith(".aie")) {
            aieText = new String(Files.readAllBytes(Paths.get(instanceFile.getAbsolutePath())));
            String ecoreForwardSlash = ecoreAbsPath.replace("\\", "/");
            aieText = aieText.replace(ECORE_PLACEHOLDER, ecoreForwardSlash);
        } else {
            EcoreInstanceTranslator translator = new EcoreInstanceTranslator();
            aieText = translator.translate(instanceFile.getAbsolutePath());
        }

        if (aieText == null || aieText.trim().isEmpty())
            throw new RuntimeException("Instance text empty — check format/metamodel references.");

        // ── Parse ──
        CharStream input = CharStreams.fromString(aieText);
        AlloyInEcoreLexer lexer = new AlloyInEcoreLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        URI fileURI = URI.createFileURI(instanceFile.getAbsolutePath());
        AlloyInEcoreParser parser = new AlloyInEcoreParser(tokens, fileURI);

        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                    int line, int charPositionInLine, String msg, RecognitionException e) {
                System.err.println("  PARSE ERROR " + line + ":" + charPositionInLine + " " + msg);
            }
        });

        // Suppress verbose parser output but keep parse errors visible.
        // Thread-safe: synchronized to prevent concurrent calls from corrupting stdout.
        synchronized (STDOUT_LOCK) {
            java.io.PrintStream originalOut = System.out;
            java.io.PrintStream nullStream = new java.io.PrintStream(new java.io.OutputStream() { public void write(int b) {} });
            System.setOut(nullStream);
            try {
                parser.instance(null);
            } finally {
                System.setOut(originalOut);
                nullStream.close();
            }
        }

        if (parser.instance == null)
            throw new RuntimeException("Parser returned null instance — check syntax errors above.");

        // ── Build KodKod universe ──
        KodKodUniverse universe = new KodKodUniverse(parser.instance);
        Formula formula = universe.getFormula();
        Bounds bounds = universe.getBounds();

        // ── Solve ──
        Solver solver = new Solver();

        // Prefer proof-capable solver when available (enables UNSAT core extraction).
        // Fall back to pure-Java SAT4J for portability (e.g., Windows without WSL/native prover libs).
        SATFactory satFactory = SATFactory.MiniSatProver;
        if (!SATFactory.available(satFactory)) {
            satFactory = SATFactory.DefaultSAT4J;
        }

        solver.options().setSolver(satFactory);
        solver.options().setCoreGranularity(3);
        solver.options().setLogTranslation(2);
        solver.options().setSymmetryBreaking(20);
        solver.options().setBitwidth(universe.getBitwidth());

        Solution solution;
        try {
            solution = solver.solve(formula, bounds);
        } catch (RuntimeException | UnsatisfiedLinkError t) {
            // If the selected solver cannot be loaded at runtime, retry with SAT4J.
            if (satFactory != SATFactory.DefaultSAT4J) {
                solver = new Solver();
                solver.options().setSolver(SATFactory.DefaultSAT4J);
                solver.options().setCoreGranularity(0);
                solver.options().setLogTranslation(0);
                solver.options().setSymmetryBreaking(20);
                solver.options().setBitwidth(universe.getBitwidth());
                solution = solver.solve(formula, bounds);
            } else {
                throw t;
            }
        }

        // ── Report (captured BEFORE save so I/O errors don't lose the result) ──
        if (solution.sat()) {
            System.out.println("      ✓ SAT — all invariants hold");
            universe.updateWithInstance(solution.instance());
            effectiveReport.result = "SAT";
        } else {
            System.out.println("      ✗ UNSAT — invariant violation detected");
            effectiveReport.result = "UNSAT";
            Proof proof = solution.proof();
            if (proof != null) {
                proof.minimize(new RCEStrategy(proof.log()));
                effectiveReport.violations.addAll(extractViolationsFromCore(universe, proof.highLevelCore().keySet()));
            } else {
                // Portable fallback: compute a minimal unsat core by trying to drop candidate rule formulas.
                // This requires no proof logging and works with SAT4J.
                effectiveReport.violations.addAll(extractViolationsByDroppingRules(universe, bounds, formula, solver.options().solver()));
            }
        }

        // ── Save solver output (after result capture; I/O failure is logged, not fatal) ──
        try {
            File outDir = new File(outputDir);
            outDir.mkdirs();
            String solverFile = buildUniqueOutputName(instanceFile);
            universe.save(outDir.getAbsolutePath() + File.separator, solverFile, bounds, formula, solution);
            System.out.println("      → " + outDir.getAbsolutePath() + File.separator + solverFile);
        } catch (Exception saveEx) {
            System.err.println("WARNING: Failed to save solver output: " + saveEx.getMessage());
        }

        return effectiveReport;
    }

    // ── FIX #4: Unmapped formulas now produce actionable violation entries ──
    private static List<VerificationReport.Violation> extractViolationsFromCore(KodKodUniverse universe, Set<Formula> core) {
        List<VerificationReport.Violation> violations = new ArrayList<>();

        for (Formula f : core) {
            Set<FormulaInfo> fis = universe.getFormulaInfos(f);
            if (fis == null || fis.isEmpty()) {
                violations.add(new VerificationReport.Violation(
                        null,
                        "[unmapped-formula]",
                        "Core formula with no invariant mapping (structural constraint)",
                        String.valueOf(f)));
            } else {
                for (FormulaInfo fi : fis) {
                    Integer line = fi.getLine() > 0 ? fi.getLine() : null;
                    String desc = fi.getDescription();
                    String name = extractInvariantName(desc);
                    violations.add(new VerificationReport.Violation(line, name, desc, String.valueOf(f)));
                }
            }
        }

        return violations;
    }

    // ── FIX #1 (compound violations via MUS fallback), #2 (truncation warning), #3 (solver reuse) ──
    private static List<VerificationReport.Violation> extractViolationsByDroppingRules(
            KodKodUniverse universe,
            Bounds bounds,
            Formula fullFormula,
            SATFactory satFactory
    ) {
        List<Formula> conjuncts = flattenAnd(fullFormula);

        // Phase 1: Prefer user-facing "rules" (invariants + qualifier constraints) to keep fallback fast.
        List<Formula> ruleCandidates = new ArrayList<>();
        for (Formula c : conjuncts) {
            if (looksLikeRule(universe.getFormulaInfos(c))) {
                ruleCandidates.add(c);
            }
        }

        List<Formula> candidates = !ruleCandidates.isEmpty() ? ruleCandidates : conjuncts;

        // FIX #2: Warn when the candidate list is truncated.
        final int maxCandidates = 250;
        boolean truncated = false;
        int totalCandidates = candidates.size();
        if (candidates.size() > maxCandidates) {
            truncated = true;
            System.err.println("WARNING: " + totalCandidates + " candidate formulas found, "
                    + "truncating to " + maxCandidates + ". "
                    + (totalCandidates - maxCandidates) + " formulas will NOT be checked.");
            candidates = new ArrayList<>(candidates.subList(0, maxCandidates));
        }

        // FIX #3 + FIX H: Create primary and fallback solvers once, reuse across all calls.
        // Note: Kodkod's Solver is an options container; internal SAT state is rebuilt per solve() call.
        // Reuse avoids redundant option-setting, not internal SAT solver construction.
        Solver primarySolver = buildConfiguredSolver(satFactory, universe.getBitwidth());
        Solver fallbackSolver = (satFactory != SATFactory.DefaultSAT4J)
                ? buildConfiguredSolver(SATFactory.DefaultSAT4J, universe.getBitwidth())
                : null;

        List<VerificationReport.Violation> violations = new ArrayList<>();

        // ── Single-drop pass (fast path) ──
        for (Formula candidate : candidates) {
            Formula reduced = andWithout(conjuncts, candidate);
            boolean sat = solveSatSafe(primarySolver, fallbackSolver, reduced, bounds);

            if (sat) {
                addViolationForFormula(violations, universe, candidate);
            }
        }

        // ── FIX #1 + FIX C: If the single-drop pass found nothing, perform MUS extraction
        //    to detect compound violations (groups of rules that jointly cause UNSAT).
        //    We must verify that structural+candidates is actually UNSAT before running MUS.
        //    If the conflict involves non-rule conjuncts, widen candidates to all conjuncts. ──
        if (violations.isEmpty()) {
            List<VerificationReport.Violation> compoundViolations =
                    extractCompoundViolations(universe, bounds, conjuncts, candidates,
                            primarySolver, fallbackSolver);
            violations.addAll(compoundViolations);
        }

        // FIX #2: Add a truncation marker violation so the report is never silently incomplete.
        if (truncated) {
            violations.add(new VerificationReport.Violation(
                    null,
                    "[TRUNCATED]",
                    "Only " + maxCandidates + " of " + totalCandidates
                            + " candidate formulas were checked. Results may be incomplete.",
                    null));
        }

        return violations;
    }

    /**
     * FIX #1 + FIX C — Deletion-based Minimal Unsatisfiable Subset (MUS) extraction.
     *
     * Before running the MUS loop, verifies that structural + candidates is
     * actually UNSAT. If it is SAT (the conflict involves non-rule conjuncts),
     * widens candidates to ALL conjuncts so nothing is missed.
     *
     * Then removes each candidate one at a time:
     *   - If removing candidate C makes the formula SAT → C is essential.
     *   - If removing candidate C still leaves UNSAT → C is redundant (drop it).
     *
     * The remaining set is a minimal unsatisfiable subset.
     */
    private static List<VerificationReport.Violation> extractCompoundViolations(
            KodKodUniverse universe,
            Bounds bounds,
            List<Formula> allConjuncts,
            List<Formula> candidates,
            Solver primarySolver,
            Solver fallbackSolver
    ) {
        List<VerificationReport.Violation> violations = new ArrayList<>();

        // Structural formulas = allConjuncts minus candidates.
        List<Formula> structural = new ArrayList<>();
        for (Formula c : allConjuncts) {
            if (!candidates.contains(c)) {
                structural.add(c);
            }
        }

        // FIX C: Verify that structural + candidates is actually UNSAT.
        // If the rule-only subset + structural is SAT, the conflict involves
        // non-rule conjuncts that were excluded. Widen to ALL conjuncts.
        List<Formula> allForCheck = new ArrayList<>(structural);
        allForCheck.addAll(candidates);
        Formula checkFormula = allForCheck.isEmpty() ? Formula.TRUE : Formula.and(allForCheck);
        boolean subsetIsUnsat = !solveSatSafe(primarySolver, fallbackSolver, checkFormula, bounds);

        List<Formula> effectiveCandidates;
        List<Formula> effectiveStructural;

        if (subsetIsUnsat) {
            // The conflict is within candidates — proceed with the narrow set.
            effectiveCandidates = new ArrayList<>(candidates);
            effectiveStructural = structural;
        } else {
            // The conflict involves non-rule conjuncts. Widen to all conjuncts.
            System.err.println("NOTE: Rule-only candidates are SAT. Widening to all conjuncts for MUS extraction.");
            effectiveCandidates = new ArrayList<>(allConjuncts);
            effectiveStructural = new ArrayList<>(); // everything is a candidate now
            // Apply the same truncation limit to avoid unbounded MUS on large metamodels.
            final int maxMusCandidates = 250;
            if (effectiveCandidates.size() > maxMusCandidates) {
                System.err.println("WARNING: Widened candidate set (" + effectiveCandidates.size()
                        + ") exceeds limit. Truncating MUS to first " + maxMusCandidates + " conjuncts.");
                effectiveCandidates = new ArrayList<>(effectiveCandidates.subList(0, maxMusCandidates));
            }
        }

        // FIX G: Use index-based formula construction to avoid O(n²) list copies.
        // We track which indices are still active with a boolean array.
        boolean[] active = new boolean[effectiveCandidates.size()];
        for (int i = 0; i < active.length; i++) {
            active[i] = true;
        }


        for (int i = 0; i < effectiveCandidates.size(); i++) {
            if (!active[i]) {
                continue; // already dropped
            }

            // Build the formula WITHOUT candidate i: structural + all active except i.
            active[i] = false; // tentatively remove
            Formula reduced = buildFormulaFromActive(effectiveStructural, effectiveCandidates, active);
            boolean sat = solveSatSafe(primarySolver, fallbackSolver, reduced, bounds);

            if (sat) {
                // Removing this candidate made the formula SAT → it is essential. Put it back.
                active[i] = true;
            }
            // else: removing it still leaves UNSAT → it is redundant. Leave it dropped.
        }

        // Everything still active is essential to the conflict.
        for (int i = 0; i < effectiveCandidates.size(); i++) {
            if (active[i]) {
                addViolationForFormula(violations, universe, effectiveCandidates.get(i));
            }
        }

        return violations;
    }

    /**
     * FIX G: Build a conjunction from structural formulas + active candidates
     * without allocating a full intermediate list.
     */
    private static Formula buildFormulaFromActive(
            List<Formula> structural,
            List<Formula> candidates,
            boolean[] active
    ) {
        List<Formula> parts = new ArrayList<>(structural);
        for (int i = 0; i < candidates.size(); i++) {
            if (active[i]) {
                parts.add(candidates.get(i));
            }
        }
        return parts.isEmpty() ? Formula.TRUE : Formula.and(parts);
    }

    /**
     * Creates a violation entry for a given formula, using FormulaInfo when available.
     */
    private static void addViolationForFormula(
            List<VerificationReport.Violation> violations,
            KodKodUniverse universe,
            Formula candidate
    ) {
        Set<FormulaInfo> fis = universe.getFormulaInfos(candidate);
        if (fis == null || fis.isEmpty()) {
            violations.add(new VerificationReport.Violation(
                    null,
                    "[unmapped-formula]",
                    "Core formula with no invariant mapping (structural constraint)",
                    String.valueOf(candidate)));
        } else {
            for (FormulaInfo fi : fis) {
                Integer line = fi.getLine() > 0 ? fi.getLine() : null;
                String desc = fi.getDescription();
                String name = extractInvariantName(desc);
                violations.add(new VerificationReport.Violation(line, name, desc, String.valueOf(candidate)));
            }
        }
    }

    /**
     * Build a configured Solver instance with the given options.
     */
    private static Solver buildConfiguredSolver(SATFactory satFactory, int bitwidth) {
        Solver solver = new Solver();
        solver.options().setSolver(satFactory);
        solver.options().setCoreGranularity(0);
        solver.options().setLogTranslation(0);
        solver.options().setSymmetryBreaking(20);
        solver.options().setBitwidth(bitwidth);
        return solver;
    }

    /**
     * FIX H: Solve using a primary solver, falling back to a pre-built fallback
     * solver on error (avoids rebuilding the fallback on every call).
     */
    private static boolean solveSatSafe(Solver primary, Solver fallback,
                                        Formula formula, Bounds bounds) {
        try {
            Solution sol = primary.solve(formula, bounds);
            return sol.sat();
        } catch (RuntimeException | UnsatisfiedLinkError t) {
            if (fallback != null) {
                Solution sol = fallback.solve(formula, bounds);
                return sol.sat();
            }
            throw (t instanceof RuntimeException) ? (RuntimeException) t : new RuntimeException(t);
        }
    }

    private static boolean looksLikeRule(Set<FormulaInfo> infos) {
        if (infos == null || infos.isEmpty()) {
            return false;
        }
        for (FormulaInfo fi : infos) {
            String desc = fi.getDescription();
            if (desc == null) {
                continue;
            }
            String trimmed = desc.trim();
            if (trimmed.startsWith("Invariant ")) {
                return true;
            }
            if (trimmed.startsWith("Qualifier {id} (unique) on ")) {
                return true;
            }
        }
        return false;
    }

    private static String extractInvariantName(String desc) {
        if (desc == null) {
            return null;
        }
        String trimmed = desc.trim();
        if (trimmed.startsWith("Invariant ")) {
            String rest = trimmed.substring("Invariant ".length());
            int colon = rest.indexOf(':');
            return (colon >= 0 ? rest.substring(0, colon) : rest).trim();
        }
        if (trimmed.startsWith("Qualifier {id} (unique) on ")) {
            String target = trimmed.substring("Qualifier {id} (unique) on ".length()).trim();
            return !target.isEmpty() ? ("id_unique(" + target + ")") : "id_unique";
        }
        return null;
    }

    /**
     * FIX #5 + FIX E: Build a unique output filename using parent segments AND
     * a hash of the full absolute path to guarantee uniqueness even when segments
     * collide (e.g., dir-1 vs dir_1 after sanitization).
     */
    private static String buildUniqueOutputName(File instanceFile) {
        String absPath = instanceFile.getAbsolutePath();
        // Use up to 3 parent directory segments for human readability.
        List<String> segments = new ArrayList<>();
        segments.add(instanceFile.getName());
        File parent = instanceFile.getParentFile();
        for (int i = 0; i < 3 && parent != null; i++) {
            String name = parent.getName();
            if (name.isEmpty()) {
                break;
            }
            segments.add(0, name);
            parent = parent.getParentFile();
        }
        String combined = String.join("_", segments);
        // Sanitize: replace any path-unsafe characters.
        combined = combined.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        // Append a hash of the full path to guarantee uniqueness.
        String hashSuffix = String.format("%08x", absPath.hashCode());
        return combined + "_" + hashSuffix + ".kodkod";
    }

    /**
     * Iterative (stack-based) flattening of AND-formula trees.
     * Avoids StackOverflowError on deeply nested formulas (e.g., 5,000+ chained .and() calls
     * producing right-skewed BinaryFormula trees).
     */
    private static List<Formula> flattenAnd(Formula f) {
        List<Formula> out = new ArrayList<>();
        if (f == null) {
            return out;
        }
        java.util.ArrayDeque<Formula> stack = new java.util.ArrayDeque<>();
        stack.push(f);
        while (!stack.isEmpty()) {
            Formula current = stack.pop();
            if (current instanceof NaryFormula) {
                NaryFormula nf = (NaryFormula) current;
                if (nf.op() == FormulaOperator.AND) {
                    // Push children in reverse order so they are processed left-to-right.
                    List<Formula> children = new ArrayList<>();
                    for (Formula c : nf) {
                        children.add(c);
                    }
                    for (int i = children.size() - 1; i >= 0; i--) {
                        stack.push(children.get(i));
                    }
                    continue;
                }
            }
            if (current instanceof BinaryFormula) {
                BinaryFormula bf = (BinaryFormula) current;
                if (bf.op() == FormulaOperator.AND) {
                    // Push right first so left is processed first.
                    stack.push(bf.right());
                    stack.push(bf.left());
                    continue;
                }
            }
            out.add(current);
        }
        return out;
    }

    /**
     * Build the conjunction of all formulas except the first occurrence of {@code excluded}.
     * Uses reference equality (==) intentionally: Kodkod formula objects are structurally shared,
     * and flattenAnd preserves the original object references without copying.
     */
    private static Formula andWithout(List<Formula> conjuncts, Formula excluded) {
        if (conjuncts == null || conjuncts.isEmpty()) {
            return Formula.TRUE;
        }
        List<Formula> kept = new ArrayList<>(Math.max(0, conjuncts.size() - 1));
        boolean removedOnce = false;
        for (Formula c : conjuncts) {
            if (!removedOnce && c == excluded) { // identity comparison — see Javadoc
                removedOnce = true;
                continue;
            }
            kept.add(c);
        }
        if (kept.isEmpty()) {
            return Formula.TRUE;
        }
        return Formula.and(kept);
    }
}
