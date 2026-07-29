package com.verification;

import java.util.*;

public class InvariantChecker {
    private final boolean strict;
    private final boolean details;

    public InvariantChecker(boolean strict, boolean details) {
        this.strict = strict;
        this.details = details;
    }

    public VerificationReport check(String aieContent, String metamodelContent) {
        VerificationReport report = new VerificationReport();
        try {
            AieModel model = parseAie(aieContent);

            List<String> atoms = new ArrayList<>(model.atoms);
            if (atoms.isEmpty()) {
                report.setResult("SAT");
                return report;
            }

            List<ViolationInfo> violations = new ArrayList<>();

            checkNoCyclicInheritance(model, violations);
            checkNoDuplicateTypeNames(model, violations);
            checkAbstractMethodInAbstractClass(model, violations);
            checkInterfaceMethodsAreAbstract(model, violations);
            checkInterfaceHasNoFields(model, violations);
            checkNoStaticAbstractMethod(model, violations);
            checkLocalMethodNamespace(model, violations);
            checkGeneralizationKindPolicy(model, violations);

            if (violations.isEmpty()) {
                report.setResult("SAT");
            } else {
                report.setResult("UNSAT");
                for (ViolationInfo v : violations) {
                    VerificationReport.Violation violation = new VerificationReport.Violation();
                    violation.setDescription(v.message);
                    violation.setInvariantName(v.invariant);
                    report.addViolation(violation);
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
                    violations.add(new ViolationInfo("AcyclicGeneralization",
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
                violations.add(new ViolationInfo("IdentifierIntegrity",
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
                    violations.add(new ViolationInfo("AbstractionPolicy",
                            "Non-abstract class " + cls + " contains abstract method " + mtd));
                }
            }
        }
    }

    private void checkInterfaceMethodsAreAbstract(AieModel model, List<ViolationInfo> violations) {
        for (TupleEntry t : model.getTuples("classMethods")) {
            if (t.to != null) {
                String cls = t.from;
                Map<String, String> clsAttrs = model.atomAttrs.get(cls);
                String kind = clsAttrs != null ? clsAttrs.get("kind") : null;
                if (!"interface".equals(kind)) continue;

                String mtd = t.to;
                Map<String, String> mtdAttrs = model.atomAttrs.get(mtd);
                boolean methodAbstract = mtdAttrs != null && "true".equals(mtdAttrs.get("abstract"));
                if (!methodAbstract) {
                    violations.add(new ViolationInfo("InterfacePolicy",
                            "Interface " + cls + " contains non-abstract method " + mtd));
                }
            }
        }
    }

    private void checkInterfaceHasNoFields(AieModel model, List<ViolationInfo> violations) {
        for (TupleEntry t : model.getTuples("classAttributes")) {
            if (t.to != null) {
                String cls = t.from;
                Map<String, String> clsAttrs = model.atomAttrs.get(cls);
                String kind = clsAttrs != null ? clsAttrs.get("kind") : null;
                if ("interface".equals(kind)) {
                    violations.add(new ViolationInfo("InterfacePolicy",
                            "Interface " + cls + " has field " + t.to));
                }
            }
        }
    }

    private void checkNoStaticAbstractMethod(AieModel model, List<ViolationInfo> violations) {
        for (Map.Entry<String, Map<String, String>> attr : model.atomAttrs.entrySet()) {
            String atom = attr.getKey();
            if (!atom.startsWith("Method")) continue;
            Map<String, String> attrs = attr.getValue();
            if ("true".equals(attrs.get("static")) && "true".equals(attrs.get("abstract"))) {
                violations.add(new ViolationInfo("StaticMethodPolicy",
                        "Method " + atom + " is both static and abstract"));
            }
        }
    }

    private void checkLocalMethodNamespace(AieModel model, List<ViolationInfo> violations) {
        for (TupleEntry t : model.getTuples("classMethods")) {
            if (t.to != null) {
                String cls = t.from;
                List<TupleEntry> allMethodsForClass = model.getTuples("classMethods");
                Map<String, List<String>> methodNamesInClass = new HashMap<>();
                for (TupleEntry mt : allMethodsForClass) {
                    if (cls.equals(mt.from) && mt.to != null) {
                        Map<String, String> mtdAttrs = model.atomAttrs.get(mt.to);
                        String mname = mtdAttrs != null ? mtdAttrs.get("name") : null;
                        if (mname != null) {
                            methodNamesInClass.computeIfAbsent(mname, k -> new ArrayList<>()).add(mt.to);
                        }
                    }
                }
                for (Map.Entry<String, List<String>> entry : methodNamesInClass.entrySet()) {
                    if (entry.getValue().size() > 1) {
                        violations.add(new ViolationInfo("LocalMethodNamespace",
                                "Duplicate method name '" + entry.getKey() + "' in class " + cls
                                        + ": " + String.join(", ", entry.getValue())));
                        return;
                    }
                }
            }
        }
    }

    private void checkGeneralizationKindPolicy(AieModel model, List<ViolationInfo> violations) {
        for (TupleEntry t : model.getTuples("classParent")) {
            if (t.to != null) {
                String child = t.from;
                String parent = t.to;
                Map<String, String> childAttrs = model.atomAttrs.get(child);
                Map<String, String> parentAttrs = model.atomAttrs.get(parent);
                String childKind = childAttrs != null ? childAttrs.get("kind") : null;
                String parentKind = parentAttrs != null ? parentAttrs.get("kind") : null;
                if (childKind == null || parentKind == null) continue;

                if ("interface".equals(childKind) && !"interface".equals(parentKind)) {
                    violations.add(new ViolationInfo("GeneralizationKindPolicy",
                            "Interface " + child + " extends non-interface " + parent));
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
}
