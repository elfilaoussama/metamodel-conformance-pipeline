package com.verification;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import kodkod.ast.*;
import kodkod.ast.operator.ExprOperator;
import kodkod.ast.operator.FormulaOperator;
import kodkod.engine.*;
import kodkod.engine.satlab.SAT4J;
import kodkod.engine.config.ConsoleReporter;
import kodkod.engine.config.Options;
import kodkod.instance.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class InvariantChecker {
    private final Gson gson = new Gson();
    private final boolean strict;
    private final boolean details;

    public InvariantChecker(boolean strict, boolean details) {
        this.strict = strict;
        this.details = details;
    }

    public VerificationReport check(String aieInstance, String recoreContent) {
        VerificationReport report = new VerificationReport();

        try {
            JsonObject instance = JsonParser.parseString(aieInstance).getAsJsonObject();

            Universe universe = buildUniverse(instance);
            Bounds bounds = buildBounds(instance, universe);

            Options options = new Options();
            options.setSolver(new SAT4J());
            options.setReporter(new ConsoleReporter());
            options.setBitwidth(4);

            if (strict) {
                options.setSkolemDepth(0);
                options.setSymmetryBreaking(0);
            }

            Solver solver = new Solver(options);
            Formula constraints = buildConstraints(instance);

            Solution solution = solver.solve(constraints, bounds);

            if (solution.outcome() == Solution.Outcome.SATISFIABLE
                    || solution.outcome() == Solution.Outcome.TRIVIALLY_SATISFIABLE) {
                report.setResult("SAT");
            } else if (solution.outcome() == Solution.Outcome.UNSATISFIABLE) {
                report.setResult("UNSAT");
                extractViolations(solution, report, instance);
            } else {
                report.setResult("ERROR");
                VerificationReport.Violation v = new VerificationReport.Violation();
                v.setDescription("Solver returned: " + solution.outcome());
                report.addViolation(v);
            }
        } catch (Exception e) {
            report.setResult("ERROR");
            VerificationReport.Violation v = new VerificationReport.Violation();
            v.setDescription(e.getMessage());
            report.addViolation(v);
        }

        return report;
    }

    private Universe buildUniverse(JsonObject instance) {
        Set<String> atoms = new LinkedHashSet<>();

        atoms.add("null");

        JsonObject root = instance.getAsJsonObject("Root");
        if (root != null) {
            addAtomsFromSet(root, "classes", atoms);

            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                if (entry.getKey().startsWith("Method") || entry.getKey().startsWith("Field")) {
                    JsonObject obj = entry.getValue().getAsJsonObject();
                    if (obj.has("name")) {
                        atoms.add(obj.get("name").getAsString());
                    }
                }
            }
        }

        return new Universe(new ArrayList<>(atoms));
    }

    private void addAtomsFromSet(JsonObject obj, String key, Set<String> atoms) {
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonObject()) return;
        JsonObject setObj = el.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : setObj.entrySet()) {
            atoms.add(entry.getKey());
        }
    }

    private Bounds buildBounds(JsonObject instance, Universe universe) {
        Bounds bounds = new Bounds(universe);

        for (int i = 0; i < universe.size(); i++) {
            String atom = universe.iterator().next().toString();
        }

        return bounds;
    }

    private Formula buildConstraints(JsonObject instance) {
        return Formula.TRUE;
    }

    private void extractViolations(Solution solution, VerificationReport report, JsonObject instance) {
        if (solution.proof() != null) {
            ResolutionBasedProof proof = (ResolutionBasedProof) solution.proof();
            Set<Formula> core = proof.highLevelCore();

            for (Formula f : core) {
                VerificationReport.Violation v = new VerificationReport.Violation();
                v.setDescription("Constraint violation: " + f.toString());
                v.setFormula(f.toString());
                report.addViolation(v);
            }

            if (core.isEmpty()) {
                extractViaDeletion(report, instance);
            }
        } else {
            extractViaDeletion(report, instance);
        }
    }

    private void extractViaDeletion(VerificationReport report, JsonObject instance) {
        VerificationReport.Violation v = new VerificationReport.Violation();
        v.setDescription("Unsatisfiable constraints detected");
        v.setFormula("UNSAT core extraction skipped (no proof available)");
        report.addViolation(v);
    }
}
