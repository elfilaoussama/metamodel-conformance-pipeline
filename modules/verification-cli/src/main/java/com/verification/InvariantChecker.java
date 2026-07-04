package com.verification;

import kodkod.ast.*;
import kodkod.engine.*;
import kodkod.engine.satlab.SATFactory;
import kodkod.engine.config.Options;
import kodkod.instance.*;

import java.util.*;

public class InvariantChecker {
    private final boolean strict;
    private final boolean details;

    public InvariantChecker(boolean strict, boolean details) {
        this.strict = strict;
        this.details = details;
    }

    public VerificationReport check(String aieContent, String recoreContent) {
        VerificationReport report = new VerificationReport();
        try {
            AieModel model = parseAie(aieContent);
            RecoreMeta metamodel = parseRecore(recoreContent);

            List<String> atoms = new ArrayList<>(model.atoms);
            if (atoms.isEmpty()) {
                report.setResult("SAT");
                return report;
            }
            Universe universe = new Universe(atoms);
            Bounds bounds = buildBounds(universe, model);

            Formula constraints = evaluateInvariants(model, metamodel, report);

            Options options = new Options();
            options.setSolver(SATFactory.DefaultSAT4J);
            options.setBitwidth(computeBitwidth(universe.size()));
            if (strict) {
                options.setSkolemDepth(0);
                options.setSymmetryBreaking(0);
            }

            Solver solver = new Solver(options);
            Solution solution = solver.solve(constraints, bounds);

            if (solution.outcome() == Solution.Outcome.SATISFIABLE
                    || solution.outcome() == Solution.Outcome.TRIVIALLY_SATISFIABLE) {
                if (report.getResult() == null) report.setResult("SAT");
            } else if (solution.outcome() == Solution.Outcome.UNSATISFIABLE) {
                if (report.getResult() == null) report.setResult("UNSAT");
                if (report.getViolations().isEmpty()) {
                    extractViolations(solution, report);
                }
            } else {
                if (report.getResult() == null) report.setResult("ERROR");
                if (report.getViolations().isEmpty()) {
                    VerificationReport.Violation v = new VerificationReport.Violation();
                    v.setDescription("Solver returned: " + solution.outcome());
                    report.addViolation(v);
                }
            }
        } catch (Exception e) {
            report.setResult("ERROR");
            VerificationReport.Violation v = new VerificationReport.Violation();
            v.setDescription(e.getMessage() != null ? e.getMessage() : e.getClass().getName());
            report.addViolation(v);
        }
        return report;
    }

    private int computeBitwidth(int universeSize) {
        int bits = 4;
        while ((1 << bits) < universeSize + 10) bits++;
        return Math.min(bits, 16);
    }

    private Bounds buildBounds(Universe universe, AieModel model) {
        Bounds bounds = new Bounds(universe);
        TupleFactory tf = universe.factory();

        for (Map.Entry<String, List<TupleEntry>> entry : model.relationTuples.entrySet()) {
            String relName = entry.getKey();
            List<TupleEntry> tuples = entry.getValue();

            if (tuples.isEmpty()) continue;
            Relation rel = Relation.binary(relName);
            TupleSet lower = tf.noneOf(2);

            for (TupleEntry t : tuples) {
                if (t.to == null) continue;
                if (universe.index(t.from) < 0 || universe.index(t.to) < 0) continue;
                lower.add(tf.tuple(t.from, t.to));
            }

            if (!lower.isEmpty()) {
                bounds.boundExactly(rel, lower);
            }
        }

        return bounds;
    }

    private Formula evaluateInvariants(AieModel model, RecoreMeta meta, VerificationReport report) {
        List<ViolationInfo> violations = new ArrayList<>();

        checkNoCyclicInheritance(model, violations);
        checkNoDuplicateTypeNames(model, violations);
        checkAbstractMethodInAbstractClass(model, violations);

        if (violations.isEmpty()) {
            report.setResult("SAT");
            return Formula.TRUE;
        } else {
            report.setResult("UNSAT");
            for (ViolationInfo v : violations) {
                VerificationReport.Violation violation = new VerificationReport.Violation();
                violation.setDescription(v.message);
                violation.setInvariantName(v.invariant);
                report.addViolation(violation);
            }
            return Formula.FALSE;
        }
    }

    static class ViolationInfo {
        final String invariant;
        final String message;
        ViolationInfo(String invariant, String message) {
            this.invariant = invariant;
            this.message = message;
        }
    }

    private void checkNoCyclicInheritance(AieModel model, List<ViolationInfo> violations) {
        Map<String, String> parentMap = new HashMap<>();
        for (TupleEntry t : model.getTuples("classParent")) {
            if (t.to != null) parentMap.put(t.from, t.to);
        }
        for (String cls : parentMap.keySet()) {
            Set<String> visited = new HashSet<>();
            String current = cls;
            while (current != null) {
                if (!visited.add(current)) {
                    violations.add(new ViolationInfo("NoCyclicInheritance",
                            "Cyclic inheritance detected involving class " + current));
                    return;
                }
                current = parentMap.get(current);
            }
        }
    }

    private void checkNoDuplicateTypeNames(AieModel model, List<ViolationInfo> violations) {
        Map<String, List<String>> nameToAtoms = new HashMap<>();
        for (Map.Entry<String, Map<String, String>> attr : model.atomAttrs.entrySet()) {
            String atomName = attr.getKey();
            if (!atomName.startsWith("Class")) continue;
            String clsName = attr.getValue().get("name");
            if (clsName != null) {
                nameToAtoms.computeIfAbsent(clsName, k -> new ArrayList<>()).add(atomName);
            }
        }
        for (Map.Entry<String, List<String>> entry : nameToAtoms.entrySet()) {
            if (entry.getValue().size() > 1) {
                violations.add(new ViolationInfo("NoDuplicateTypeNames",
                        "Duplicate name '" + entry.getKey() + "' used by: " + String.join(", ", entry.getValue())));
            }
        }
    }

    private void checkAbstractMethodInAbstractClass(AieModel model, List<ViolationInfo> violations) {
        Set<String> abstractAtoms = new HashSet<>();
        for (Map.Entry<String, Map<String, String>> attr : model.atomAttrs.entrySet()) {
            if ("true".equals(attr.getValue().get("abstract"))) {
                abstractAtoms.add(attr.getKey());
            }
        }

        for (TupleEntry t : model.getTuples("classMethods")) {
            if (t.to != null) {
                String cls = t.from;
                String mtd = t.to;
                Map<String, String> mtdAttrs = model.atomAttrs.get(mtd);
                boolean methodAbstract = mtdAttrs != null && "true".equals(mtdAttrs.get("abstract"));
                if (methodAbstract && !abstractAtoms.contains(cls)) {
                    violations.add(new ViolationInfo("AbstractMethodInAbstractClass",
                            "Non-abstract class " + cls + " contains abstract method " + mtd));
                }
            }
        }
    }

    static class AieModel {
        Set<String> atoms = new LinkedHashSet<>();
        Map<String, Map<String, String>> atomAttrs = new LinkedHashMap<>();
        Map<String, List<TupleEntry>> relationTuples = new LinkedHashMap<>();

        void addAtom(String name) {
            if (name != null && !name.isEmpty() && !name.equals("null")) {
                atoms.add(name);
            }
        }

        void addTuple(String relation, String from, String to) {
            atoms.add("null");
            atoms.add(from);
            if (to != null && !to.equals("null")) {
                atoms.add(to);
            }
            relationTuples.computeIfAbsent(relation, k -> new ArrayList<>())
                    .add(new TupleEntry(from, to != null && !to.equals("null") ? to : null));
        }

        void addMultiTuple(String relation, String from, Set<String> targets) {
            for (String t : targets) {
                addTuple(relation, from, t);
            }
        }

        List<TupleEntry> getTuples(String relation) {
            return relationTuples.getOrDefault(relation, Collections.emptyList());
        }
    }

    static class TupleEntry {
        final String from;
        final String to;
        TupleEntry(String from, String to) {
            this.from = from;
            this.to = to;
        }
    }

    static class RecoreMeta {
        List<String> classNames = new ArrayList<>();
        List<String> invariantExpressions = new ArrayList<>();
    }

    private RecoreMeta parseRecore(String content) {
        RecoreMeta meta = new RecoreMeta();
        String[] lines = content.split("\\n");
        StringBuilder invariantBuffer = null;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("--")) continue;

            if (line.startsWith("class ") || line.startsWith("abstract class ")) {
                String cls = extractClassName(line);
                if (cls != null) meta.classNames.add(cls);
                continue;
            }

            if (line.startsWith("invariant ")) {
                invariantBuffer = new StringBuilder();
                String rest = line.substring("invariant ".length()).trim();
                if (rest.endsWith("{")) {
                    invariantBuffer.append(rest, 0, rest.length() - 1).append(": ");
                } else {
                    int brace = rest.indexOf("{");
                    if (brace >= 0) {
                        invariantBuffer.append(rest, 0, brace).append(": ");
                    } else {
                        invariantBuffer.append(rest).append(": ");
                    }
                }
                continue;
            }

            if (invariantBuffer != null) {
                if (line.equals("}") || line.startsWith("}")) {
                    meta.invariantExpressions.add(invariantBuffer.toString().trim());
                    invariantBuffer = null;
                } else {
                    invariantBuffer.append(line).append(" ");
                }
            }
        }
        if (invariantBuffer != null) {
            meta.invariantExpressions.add(invariantBuffer.toString().trim());
        }
        return meta;
    }

    private String extractClassName(String line) {
        String s = line.replace("abstract", "").replace("class", "").trim();
        int idx = s.indexOf(" ");
        if (idx > 0) s = s.substring(0, idx);
        idx = s.indexOf("extends");
        if (idx > 0) s = s.substring(0, idx).trim();
        return s.isEmpty() ? null : s;
    }

    private AieModel parseAie(String content) {
        AieModel model = new AieModel();
        String[] lines = content.split("\\n");
        String currentAtom = null;
        boolean inRoot = false;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("--")) continue;

            if (line.equals("Root = {")) {
                inRoot = true;
                continue;
            }
            if (line.equals("}")) {
                inRoot = false;
                currentAtom = null;
                continue;
            }

            if (inRoot) {
                if (line.startsWith("classes = {")) {
                    String inner = extractBraces(line);
                    if (inner != null) {
                        for (String s : inner.split(",")) {
                            model.addAtom(s.trim());
                        }
                    }
                } else if (line.contains("[") && line.contains("]")) {
                    parseRelationLine(model, line);
                } else if (line.contains(" = {")) {
                    int eq = line.indexOf("=");
                    String name = line.substring(0, eq).trim();
                    currentAtom = name;
                    model.addAtom(name);
                    String rest = line.substring(eq + 1).trim();
                    parseAtomBody(model, name, rest);
                } else if (line.contains(" = ") && currentAtom != null) {
                    int eq = line.indexOf("=");
                    if (eq > 0) {
                        String key = line.substring(0, eq).trim();
                        String val = line.substring(eq + 1).trim().replace("\"", "");
                        model.atomAttrs.computeIfAbsent(currentAtom, k -> new LinkedHashMap<>()).put(key, val);
                    }
                }
            }
        }
        return model;
    }

    private String extractBraces(String line) {
        int start = line.indexOf("{");
        int end = line.lastIndexOf("}");
        if (start >= 0 && end > start) {
            return line.substring(start + 1, end);
        }
        return null;
    }

    private void parseAtomBody(AieModel model, String atomName, String body) {
        String inner = body;
        if (inner.startsWith("{")) inner = inner.substring(1);
        if (inner.endsWith("}")) inner = inner.substring(0, inner.length() - 1);
        for (String part : inner.split(",")) {
            part = part.trim();
            if (part.contains("=")) {
                String[] kv = part.split("=", 2);
                String key = kv[0].trim();
                String val = kv[1].trim().replace("\"", "");
                model.atomAttrs.computeIfAbsent(atomName, k -> new LinkedHashMap<>()).put(key, val);
            }
        }
    }

    private void parseRelationLine(AieModel model, String line) {
        int bracket = line.indexOf("[");
        if (bracket < 0) return;
        String relName = line.substring(0, bracket).trim();
        int cb = line.indexOf("]");
        if (cb < 0) return;
        String from = line.substring(bracket + 1, cb).trim();
        model.addAtom(from);

        String rest = line.substring(cb + 1).trim();
        if (rest.startsWith("=")) rest = rest.substring(1).trim();

        if (rest.equals("null") || rest.equals("null,")) return;

        if (rest.startsWith("{")) {
            String inner = rest.substring(1);
            if (inner.endsWith("}")) inner = inner.substring(0, inner.length() - 1);
            if (inner.endsWith("},")) inner = inner.substring(0, inner.length() - 2);
            Set<String> targets = new LinkedHashSet<>();
            for (String s : inner.split(",")) {
                String t = s.trim();
                if (!t.isEmpty() && !t.equals("null")) targets.add(t);
            }
            model.addMultiTuple(relName, from, targets);
        } else {
            String to = rest.replace(",", "").trim();
            model.addTuple(relName, from, to);
        }
    }

    private void extractViolations(Solution solution, VerificationReport report) {
        if (solution.proof() != null) {
            Proof proof = solution.proof();
            for (Formula f : proof.highLevelCore().keySet()) {
                VerificationReport.Violation v = new VerificationReport.Violation();
                v.setDescription("Constraint violation: " + f);
                v.setFormula(f.toString());
                report.addViolation(v);
            }
        } else {
            VerificationReport.Violation v = new VerificationReport.Violation();
            v.setDescription("Unsatisfiable constraints detected");
            report.addViolation(v);
        }
    }
}
