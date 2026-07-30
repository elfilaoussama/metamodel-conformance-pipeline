package com.verification;

import java.util.*;

public class InvariantChecker {

    
    /** Static entry point matching the old API. */
    public static String check(String instancePath, String ecoreAbsPath, String outputDir) throws Exception {
        String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(instancePath)));
        InvariantChecker checker = new InvariantChecker(false, false);
        VerificationReport report = checker.check(content, null);
        return report.getResult();
    }
    
    public static VerificationReport check(String instancePath, String ecoreAbsPath, String outputDir, VerificationReport report) throws Exception {
        String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(instancePath)));
        InvariantChecker checker = new InvariantChecker(false, false);
        VerificationReport r = checker.check(content, null);
        if (report != null) {
            report.setResult(r.getResult());
            for (VerificationReport.Violation v : r.getViolations()) report.addViolation(v);
            return report;
        }
        return r;
    }
    public InvariantChecker() { }

    public InvariantChecker(boolean strict, boolean details) {
        this();
    }

    public VerificationReport check(String aieContent, String metamodelContent) {
        VerificationReport report = new VerificationReport();
        try {
            AieModel model = parseAie(aieContent);
            populateBindingAttrs(model);
            populateParentAttrs(model);

            List<String> atoms = new ArrayList<>(model.atoms);
            if (atoms.isEmpty()) {
                report.setResult("SAT");
                return report;
            }

            List<ViolationInfo> violations = new ArrayList<>();

            checkNoDuplicateTypeNames(model, violations);
            checkIdUniqueness(model, violations);
            checkExclusiveDeclarationOwnership(model, violations);
            checkNoCyclicInheritance(model, violations);
            checkInheritedMemberDerivation(model, violations);
            checkLocalInheritedDisjointness(model, violations);
            checkImplementationBinding(model, violations);
            checkUnresolvedMethods(model, violations);
            checkAbstractMethodInAbstractClass(model, violations);
            checkNoStaticAbstractMethod(model, violations);
            checkLocalMethodNamespace(model, violations);
            checkInheritedConflictPolicy(model, violations);
            checkOverrideDiscipline(model, violations);

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

    private boolean isClassifierAtom(String atom) {
        return atom != null && (atom.startsWith("Classifier"));
    }

    

    

    private String isAbstract(String atom) {
        Map<String, String> attrs = model_cache.atomAttrs.get(atom);
        if (attrs == null) return null;
        return attrs.get("isAbstract");
    }

    private AieModel model_cache;

    private void checkNoDuplicateTypeNames(AieModel model, List<ViolationInfo> violations) {
        model_cache = model;
        Map<String, List<String>> nameToAtoms = new HashMap<>();
        for (Map.Entry<String, Map<String, String>> attr : model.atomAttrs.entrySet()) {
            String atomName = attr.getKey();
            if (!isClassifierAtom(atomName)) continue;
            String clsName = attr.getValue().get("name");
            if (clsName != null) {
                nameToAtoms.computeIfAbsent(clsName, k -> new ArrayList<>()).add(atomName);
            }
        }
        for (Map.Entry<String, List<String>> entry : nameToAtoms.entrySet()) {
            if (entry.getValue().size() > 1) {
                violations.add(new ViolationInfo("IdentifierIntegrity",
                        "Duplicate name '" + entry.getKey() + "' used by: " + String.join(", ", entry.getValue())));
                return;
            }
        }
    }

    private void checkIdUniqueness(AieModel model, List<ViolationInfo> violations) {
        Map<String, String> cidToAtom = new LinkedHashMap<>();
        Map<String, String> midToAtom = new LinkedHashMap<>();
        Map<String, String> aidToAtom = new LinkedHashMap<>();

        for (Map.Entry<String, Map<String, String>> attr : model.atomAttrs.entrySet()) {
            String atom = attr.getKey();
            Map<String, String> a = attr.getValue();
            if (isClassifierAtom(atom)) {
                String cid = a.get("cid");
                if (cid != null) {
                    String prev = cidToAtom.put(cid, atom);
                    if (prev != null) {
                        violations.add(new ViolationInfo("IdentifierIntegrity",
                                "Duplicate ClassifierID " + cid + " on " + prev + " and " + atom));
                        return;
                    }
                }
            } else if (atom.startsWith("Method")) {
                String mid = a.get("mid");
                if (mid != null) {
                    String prev = midToAtom.put(mid, atom);
                    if (prev != null) {
                        violations.add(new ViolationInfo("IdentifierIntegrity",
                                "Duplicate MethodID " + mid + " on " + prev + " and " + atom));
                        return;
                    }
                }
            } else if (atom.startsWith("Attribute")) {
                String aid = a.get("aid");
                if (aid != null) {
                    String prev = aidToAtom.put(aid, atom);
                    if (prev != null) {
                        violations.add(new ViolationInfo("IdentifierIntegrity",
                                "Duplicate AttributeID " + aid + " on " + prev + " and " + atom));
                        return;
                    }
                }
            }
        }
    }

    private void checkExclusiveDeclarationOwnership(AieModel model, List<ViolationInfo> violations) {
        Map<String, Set<String>> methodOwners = new HashMap<>();
        Map<String, Set<String>> attrOwners = new HashMap<>();
        for (TupleEntry t : model.getTuples("localMethods")) {
            if (t.to != null) {
                methodOwners.computeIfAbsent(t.to, k -> new LinkedHashSet<>()).add(t.from);
            }
        }
        for (TupleEntry t : model.getTuples("localAttributes")) {
            if (t.to != null) {
                attrOwners.computeIfAbsent(t.to, k -> new LinkedHashSet<>()).add(t.from);
            }
        }
        for (Map.Entry<String, Set<String>> e : methodOwners.entrySet()) {
            if (e.getValue().size() > 1) {
                violations.add(new ViolationInfo("ExclusiveDeclarationOwnership",
                        "Method " + e.getKey() + " declared in multiple classifiers: " + e.getValue()));
            }
        }
        for (Map.Entry<String, Set<String>> e : attrOwners.entrySet()) {
            if (e.getValue().size() > 1) {
                violations.add(new ViolationInfo("ExclusiveDeclarationOwnership",
                        "Attribute " + e.getKey() + " declared in multiple classifiers: " + e.getValue()));
            }
        }
    }

    private void checkNoCyclicInheritance(AieModel model, List<ViolationInfo> violations) {
        Map<String, List<String>> parentMap = new HashMap<>();
        for (TupleEntry t : model.getTuples("parents")) {
            if (t.to != null) {
                parentMap.computeIfAbsent(t.from, k -> new ArrayList<>()).add(t.to);
            }
        }
        for (TupleEntry t : model.getTuples("parents")) {
            if (t.to != null) {
                parentMap.computeIfAbsent(t.from, k -> new ArrayList<>()).add(t.to);
            }
        }

        for (String cls : parentMap.keySet()) {
            Set<String> visited = new LinkedHashSet<>();
            Deque<String> stack = new ArrayDeque<>();
            stack.push(cls);
            while (!stack.isEmpty()) {
                String current = stack.pop();
                if (!visited.add(current)) {
                    if (current.equals(cls)) {
                        violations.add(new ViolationInfo("AcyclicGeneralization",
                                "Cyclic inheritance involving " + cls));
                        return;
                    }
                    continue;
                }
                List<String> parents = parentMap.get(current);
                if (parents != null) {
                    for (String p : parents) stack.push(p);
                }
            }
        }
    }

    private void checkGeneralizationKindPolicy(AieModel model, List<ViolationInfo> violations) {
        for (TupleEntry t : model.getTuples("parents")) {
            if (t.to != null) {
                String child = t.from;
                if (isClassifierAtom(child)) {
                    violations.add(new ViolationInfo("GeneralizationKindPolicy",
                            "Interface " + child + " has a classParent " + t.to));
                }
            }
        }
    }

    private void checkInheritedMemberDerivation(AieModel model, List<ViolationInfo> violations) {
        for (TupleEntry t : model.getTuples("inheritedMethods")) {
            if (t.to != null) {
                Map<String, String> attrs = model.atomAttrs.get(t.to);
                if (attrs != null && "Priv".equals(attrs.get("isInheritable"))) {
                    violations.add(new ViolationInfo("InheritedMemberDerivation",
                            "Private method " + t.to + " appears in inheritedMethods of " + t.from));
                }
            }
        }
        for (TupleEntry t : model.getTuples("inheritedAttributes")) {
            if (t.to != null) {
                Map<String, String> attrs = model.atomAttrs.get(t.to);
                if (attrs != null && "Priv".equals(attrs.get("isInheritable"))) {
                    violations.add(new ViolationInfo("InheritedMemberDerivation",
                            "Private attribute " + t.to + " appears in inheritedAttributes of " + t.from));
                }
            }
        }
    }

    private void checkLocalInheritedDisjointness(AieModel model, List<ViolationInfo> violations) {
        for (TupleEntry lt : model.getTuples("localMethods")) {
            if (lt.to == null) continue;
            for (TupleEntry it : model.getTuples("inheritedMethods")) {
                if (it.to == null) continue;
                if (lt.from.equals(it.from) && lt.to.equals(it.to)) {
                    violations.add(new ViolationInfo("LocalInheritedSeparation",
                            "Method " + lt.to + " appears in both localMethods and inheritedMethods of " + lt.from));
                }
            }
        }
        for (TupleEntry lt : model.getTuples("localAttributes")) {
            if (lt.to == null) continue;
            for (TupleEntry it : model.getTuples("inheritedAttributes")) {
                if (it.to == null) continue;
                if (lt.from.equals(it.from) && lt.to.equals(it.to)) {
                    violations.add(new ViolationInfo("LocalInheritedSeparation",
                            "Attribute " + lt.to + " appears in both localAttributes and inheritedAttributes of " + lt.from));
                }
            }
        }
    }

    private void populateBindingAttrs(AieModel model) {
        for (TupleEntry t : model.getTuples("implementer")) {
            if (t.from.startsWith("ImplementationBinding")) {
                model.atomAttrs.computeIfAbsent(t.from, k -> new LinkedHashMap<>()).put("implementer", t.to);
            }
        }
        for (TupleEntry t : model.getTuples("target")) {
            if (t.from.startsWith("ImplementationBinding")) {
                model.atomAttrs.computeIfAbsent(t.from, k -> new LinkedHashMap<>()).put("target", t.to);
            }
        }
        for (TupleEntry t : model.getTuples("body")) {
            if (t.from.startsWith("ImplementationBinding")) {
                model.atomAttrs.computeIfAbsent(t.from, k -> new LinkedHashMap<>()).put("body", t.to);
            }
        }
    }

    private void populateParentAttrs(AieModel model) {
        for (TupleEntry t : model.getTuples("parents")) {
            String existing = model.atomAttrs.containsKey(t.from) ?
                    model.atomAttrs.get(t.from).get("parents") : null;
            if (existing == null) {
                model.atomAttrs.computeIfAbsent(t.from, k -> new LinkedHashMap<>())
                        .put("parents", t.to);
            }
        }
    }

    private void checkImplementationBinding(AieModel model, List<ViolationInfo> violations) {
        ImplementationBindingInfo bi = buildBindingInfo(model);
        if (!bi.hasBindings()) return;

        // Check for orphan MethodBody objects
        for (String atom : model.atoms) {
            if (!atom.startsWith("MethodBody")) continue;
            if (!bi.bodyToBinding.containsKey(atom)) {
                violations.add(new ViolationInfo("ImplementationBindingPolicy",
                        "Orphan MethodBody " + atom + " has no ImplementationBinding"));
            }
        }

        // Track bindings per (classifier, method) for duplicate detection
        Map<String, List<String>> bindingsPerClassMethod = new LinkedHashMap<>();

        for (String bindingId : bi.bindingToImplementer.keySet()) {
            String implementer = bi.bindingToImplementer.get(bindingId);
            String target = bi.bindingToTarget.get(bindingId);
            if (implementer == null || target == null) continue;

            String key = implementer + "::" + target;
            bindingsPerClassMethod.computeIfAbsent(key, k -> new ArrayList<>()).add(bindingId);

            boolean found = false;
            for (TupleEntry t : model.getTuples("localMethods")) {
                if (t.from.equals(implementer) && t.to.equals(target)) { found = true; break; }
            }
            if (!found) {
                for (TupleEntry t : model.getTuples("inheritedMethods")) {
                    if (t.from.equals(implementer) && t.to.equals(target)) { found = true; break; }
                }
            }
            if (!found) {
                violations.add(new ViolationInfo("ImplementationBindingPolicy",
                        "ImplementationBinding " + bindingId + " targets method " + target
                                + " not available in " + implementer));
            }

            if (isClassifierAtom(implementer)) {
                for (TupleEntry t : model.getTuples("localMethods")) {
                    if (t.from.equals(implementer) && t.to.equals(target)) {
                        Map<String, String> mattrs = model.atomAttrs.get(target);
                        if (mattrs != null && "Yes".equals(mattrs.get("isAbstract"))) {
                            violations.add(new ViolationInfo("ImplementationBindingPolicy",
                                    "Abstract method " + target + " has a body via " + bindingId
                                            + " in declaring class " + implementer));
                        }
                        break;
                    }
                }
            }
        }

        for (Map.Entry<String, List<String>> e : bindingsPerClassMethod.entrySet()) {
            if (e.getValue().size() > 1) {
                violations.add(new ViolationInfo("ImplementationBindingPolicy",
                        "Multiple bindings for " + e.getKey() + ": " + e.getValue()));
            }
        }

        for (TupleEntry t : model.getTuples("localMethods")) {
            if (t.to == null) continue;
            String cls = t.from;
            String mtd = t.to;
            if (!isClassifierAtom(cls)) continue;
            Map<String, String> mattrs = model.atomAttrs.get(mtd);
            if (mattrs != null && "No".equals(mattrs.get("isAbstract"))) {
                String key = cls + "::" + mtd;
                if (!bindingsPerClassMethod.containsKey(key)) {
                    violations.add(new ViolationInfo("ImplementationBindingPolicy",
                            "Non-abstract method " + mtd + " in " + cls
                                    + " has no ImplementationBinding"));
                }
            }
        }
    }

    private void checkUnresolvedMethods(AieModel model, List<ViolationInfo> violations) {
        // Build a map: method atom -> set of implementer Classifier atoms that bind it
        Map<String, Set<String>> methodToImplementers = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> attr : model.atomAttrs.entrySet()) {
            if (attr.getKey().startsWith("ImplementationBinding")) {
                String target = attr.getValue().get("target");
                String implementer = attr.getValue().get("implementer");
                if (target != null && implementer != null) {
                    methodToImplementers.computeIfAbsent(target, k -> new LinkedHashSet<>())
                            .add(implementer);
                }
            }
        }

        Set<String> abstractMethods = new HashSet<>();
        for (Map.Entry<String, Map<String, String>> attr : model.atomAttrs.entrySet()) {
            if (attr.getKey().startsWith("Method")
                    && "Yes".equals(attr.getValue().get("isAbstract"))) {
                abstractMethods.add(attr.getKey());
            }
        }

        for (Map.Entry<String, Map<String, String>> attr : model.atomAttrs.entrySet()) {
            String atom = attr.getKey();
            if (!isClassifierAtom(atom)) continue;
            if ("Yes".equals(attr.getValue().get("isAbstract"))) continue;

            // Collect all methods available to this classifier (local + inherited)
            Set<String> allMethods = new LinkedHashSet<>();
            for (TupleEntry t : model.getTuples("localMethods")) {
                if (atom.equals(t.from) && t.to != null) allMethods.add(t.to);
            }
            for (TupleEntry t : model.getTuples("inheritedMethods")) {
                if (atom.equals(t.from) && t.to != null) allMethods.add(t.to);
            }

            // Compute the set of Classifiers in this classifier's ancestor chain
            // (including itself) �?� only bindings from these implementers are visible.
            Set<String> visibleImplementers = new LinkedHashSet<>();
            visibleImplementers.add(atom);
            Map<String, String> cAttrs = model.atomAttrs.get(atom);
            if (cAttrs != null) {
                String parent = cAttrs.get("parents");
                while (parent != null) {
                    if (!visibleImplementers.add(parent)) break; // cycle guard
                    Map<String, String> pAttrs = model.atomAttrs.get(parent);
                    parent = pAttrs != null ? pAttrs.get("parents") : null;
                }
            }

            for (String m : allMethods) {
                if (abstractMethods.contains(m)) continue;
                Set<String> implementers = methodToImplementers.get(m);
                if (implementers == null || implementers.isEmpty()) {
                    violations.add(new ViolationInfo("AbstractionPolicy",
                            "Non-abstract classifier " + atom + " has non-abstract method "
                                    + m + " without any ImplementationBinding"));
                    continue;
                }
                // Check if at least one binding comes from a visible implementer
                boolean visible = false;
                for (String impl : implementers) {
                    if (visibleImplementers.contains(impl)) {
                        visible = true;
                        break;
                    }
                }
                if (!visible) {
                    violations.add(new ViolationInfo("AbstractionPolicy",
                            "Non-abstract classifier " + atom + " has non-abstract method "
                                    + m + " with no ImplementationBinding visible in ancestor chain"));
                }
            }
        }
    }

    private void checkAbstractMethodInAbstractClass(AieModel model, List<ViolationInfo> violations) {
        for (TupleEntry t : model.getTuples("localMethods")) {
            if (t.to != null) {
                String cls = t.from;
                String mtd = t.to;
                String clsAbstract = isAbstract(cls);
                Map<String, String> mtdAttrs = model.atomAttrs.get(mtd);
                boolean methodAbstract = mtdAttrs != null && "Yes".equals(mtdAttrs.get("isAbstract"));
                if (methodAbstract && clsAbstract != null && !"Yes".equals(clsAbstract)) {
                    violations.add(new ViolationInfo("AbstractionPolicy",
                            "Non-abstract classifier " + cls + " contains abstract method " + mtd));
                }
            }
        }
    }

    private void checkInterfaceMethodsAreAbstract(AieModel model, List<ViolationInfo> violations) {
        for (TupleEntry t : model.getTuples("localMethods")) {
            if (t.to != null) {
                String cls = t.from;
                if (!isClassifierAtom(cls)) continue;
                String mtd = t.to;
                Map<String, String> mtdAttrs = model.atomAttrs.get(mtd);
                boolean methodAbstract = mtdAttrs != null && "Yes".equals(mtdAttrs.get("isAbstract"));
                if (!methodAbstract) {
                    violations.add(new ViolationInfo("InterfacePolicy",
                            "Interface " + cls + " contains non-abstract method " + mtd));
                }
            }
        }
    }

    private void checkInterfaceHasNoInstanceFields(AieModel model, List<ViolationInfo> violations) {
        for (TupleEntry t : model.getTuples("localAttributes")) {
            if (t.to != null) {
                String cls = t.from;
                if (!isClassifierAtom(cls)) continue;
                String attr = t.to;
                Map<String, String> attrAttrs = model.atomAttrs.get(attr);
                if (attrAttrs != null && "Instance".equals(attrAttrs.get("scope"))) {
                    violations.add(new ViolationInfo("InterfacePolicy",
                            "Interface " + cls + " has instance-scoped attribute " + attr));
                }
            }
        }
    }

    private void checkNoStaticAbstractMethod(AieModel model, List<ViolationInfo> violations) {
        for (Map.Entry<String, Map<String, String>> attr : model.atomAttrs.entrySet()) {
            String atom = attr.getKey();
            if (!atom.startsWith("Method")) continue;
            Map<String, String> attrs = attr.getValue();
            if ("Static".equals(attrs.get("scope")) && "Yes".equals(attrs.get("isAbstract"))) {
                violations.add(new ViolationInfo("StaticMethodPolicy",
                        "Method " + atom + " is both static and abstract"));
            }
        }
    }

    private void checkLocalMethodNamespace(AieModel model, List<ViolationInfo> violations) {
        Map<String, Map<String, List<String>>> classLocalMethods = new LinkedHashMap<>();
        for (TupleEntry t : model.getTuples("localMethods")) {
            if (t.to == null) continue;
            Map<String, String> attrs = model.atomAttrs.get(t.to);
            if (attrs == null) continue;
            String key = methodKey(attrs);
            if (key == null) continue;
            classLocalMethods.computeIfAbsent(t.from, k -> new LinkedHashMap<>())
                    .computeIfAbsent(key, k -> new ArrayList<>()).add(t.to);
        }
        for (Map.Entry<String, Map<String, List<String>>> e : classLocalMethods.entrySet()) {
            for (Map.Entry<String, List<String>> ne : e.getValue().entrySet()) {
                if (ne.getValue().size() > 1) {
                    violations.add(new ViolationInfo("LocalMethodNamespace",
                            "Duplicate method key '" + ne.getKey() + "' in " + e.getKey()
                                    + ": " + ne.getValue()));
                }
            }
        }
    }

    private static String methodKey(Map<String, String> attrs) {
        String name = attrs.get("memberName");
        if (name == null) return null;
        String paramTypes = attrs.get("paramTypes");
        if (paramTypes != null && !paramTypes.isEmpty() && !"{}".equals(paramTypes)) {
            return name + "(" + sortParamTypes(paramTypes) + ")";
        }
        return name;
    }

    private static String sortParamTypes(String raw) {
        String inner = raw.replace("{", "").replace("}", "").trim();
        if (inner.isEmpty()) return "";
        Map<Integer, String> map = new TreeMap<>();
        for (String entry : inner.split(",")) {
            String trimmed = entry.trim();
            int eq = trimmed.indexOf("=");
            if (eq < 0) continue;
            try {
                int idx = Integer.parseInt(trimmed.substring(0, eq).trim());
                String type = trimmed.substring(eq + 1).trim().replace("\"", "");
                map.put(idx, type);
            } catch (NumberFormatException ignored) { }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, String> e : map.entrySet()) {
            if (sb.length() > 0) sb.append(",");
            sb.append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * Best-effort isSubtype check: returns true if {@code localType} is a nominal
     * subtype of {@code inheritedType} via the classParent chain. Both types must
     * be names of existing Classifier atoms. If no suitable hierarchy is found,
     * returns false (conservative). Full Type-atom-based subtyping is deferred
     * until the mapper emits Type/ClassifierType/PrimitiveType atoms.
     */
    private static boolean isSubtypeOf(String localType, String inheritedType, AieModel model) {
        Set<String> localAtoms = new LinkedHashSet<>();
        Set<String> inheritedAtoms = new LinkedHashSet<>();
        for (Map.Entry<String, Map<String, String>> e : model.atomAttrs.entrySet()) {
            String clsName = e.getValue().get("name");
            if (localType.equals(clsName)) localAtoms.add(e.getKey());
            if (inheritedType.equals(clsName)) inheritedAtoms.add(e.getKey());
        }
        if (localAtoms.isEmpty() || inheritedAtoms.isEmpty()) return false;

        // Walk classParent chain: local's classifier must be a descendant
        // of inherited's classifier
        for (String localAtom : localAtoms) {
            for (String inheritedAtom : inheritedAtoms) {
                    if (localAtom.equals(inheritedAtom)) return true;
                    String current = localAtom;
                    Set<String> visited = new LinkedHashSet<>();
                    while (current != null && visited.add(current)) {
                        Map<String, String> cattrs = model.atomAttrs.get(current);
                        String parent = cattrs != null ? cattrs.get("parents") : null;
                        if (inheritedAtom.equals(parent)) return true;
                        current = parent;
                    }
            }
        }
        return false;
    }

    private void checkInheritedConflictPolicy(AieModel model, List<ViolationInfo> violations) {
        Map<String, Map<String, List<String>>> classInheritedMethods = new LinkedHashMap<>();
        for (TupleEntry t : model.getTuples("inheritedMethods")) {
            if (t.to == null) continue;
            Map<String, String> attrs = model.atomAttrs.get(t.to);
            if (attrs == null) continue;
            String key = methodKey(attrs);
            if (key == null) continue;
            classInheritedMethods.computeIfAbsent(t.from, k -> new LinkedHashMap<>())
                    .computeIfAbsent(key, k -> new ArrayList<>()).add(t.to);
        }
        for (Map.Entry<String, Map<String, List<String>>> e : classInheritedMethods.entrySet()) {
            for (Map.Entry<String, List<String>> ne : e.getValue().entrySet()) {
                if (ne.getValue().size() > 1) {
                    violations.add(new ViolationInfo("InheritedConflictPolicy",
                            "Inherited method conflict key '" + ne.getKey() + "' in " + e.getKey()
                                    + ": " + ne.getValue()));
                }
            }
        }

        Map<String, Map<String, List<String>>> classInheritedAttrs = new LinkedHashMap<>();
        for (TupleEntry t : model.getTuples("inheritedAttributes")) {
            if (t.to == null) continue;
            Map<String, String> attrs = model.atomAttrs.get(t.to);
            if (attrs == null) continue;
            String memberName = attrs.get("memberName");
            if (memberName == null) continue;
            classInheritedAttrs.computeIfAbsent(t.from, k -> new LinkedHashMap<>())
                    .computeIfAbsent(memberName, k -> new ArrayList<>()).add(t.to);
        }
        for (Map.Entry<String, Map<String, List<String>>> e : classInheritedAttrs.entrySet()) {
            for (Map.Entry<String, List<String>> ne : e.getValue().entrySet()) {
                if (ne.getValue().size() > 1) {
                    violations.add(new ViolationInfo("InheritedConflictPolicy",
                            "Inherited attribute conflict '" + ne.getKey() + "' in " + e.getKey()
                                    + ": " + ne.getValue()));
                }
            }
        }
    }

    private void checkOverrideDiscipline(AieModel model, List<ViolationInfo> violations) {
        Map<String, Set<String>> localIds = new LinkedHashMap<>();
        for (TupleEntry t : model.getTuples("localMethods")) {
            if (t.to == null) continue;
            Map<String, String> attrs = model.atomAttrs.get(t.to);
            if (attrs == null) continue;
            localIds.computeIfAbsent(t.from, k -> new LinkedHashSet<>()).add(t.to);
        }

        Map<String, Set<String>> inheritedIds = new LinkedHashMap<>();
        for (TupleEntry t : model.getTuples("inheritedMethods")) {
            if (t.to == null) continue;
            Map<String, String> attrs = model.atomAttrs.get(t.to);
            if (attrs == null) continue;
            inheritedIds.computeIfAbsent(t.from, k -> new LinkedHashSet<>()).add(t.to);
        }

        for (String classifier : localIds.keySet()) {
            Set<String> inherited = inheritedIds.get(classifier);
            if (inherited == null || inherited.isEmpty()) continue;
            Set<String> local = localIds.get(classifier);
            if (local == null) continue;

            for (String lm : local) {
                Map<String, String> lAttrs = model.atomAttrs.get(lm);
                if (lAttrs == null) continue;
                String lKey = methodKey(lAttrs);
                String lScope = lAttrs.get("scope");

                for (String im : inherited) {
                    Map<String, String> iAttrs = model.atomAttrs.get(im);
                    if (iAttrs == null) continue;
                    String iKey = methodKey(iAttrs);
                    if (!lKey.equals(iKey)) continue;
                    // Scope match is a precondition of override in the Alloy
                    // model (overrides predicate, kernel line 348). Mismatched
                    // scope means no override occurs �?� not a violation.
                    if (lScope != null && !lScope.equals(iAttrs.get("scope"))) continue;

                    // O-09: return-type covariance �?� the overriding method's
                    // return type must be equal to or a proper subtype of the
                    // inherited method's return type.
                    String lRetType = lAttrs.get("returnType");
                    String iRetType = iAttrs.get("returnType");
                    if (lRetType != null && iRetType != null
                            && !lRetType.equals(iRetType)
                            && !isSubtypeOf(lRetType, iRetType, model)) {
                        violations.add(new ViolationInfo("OverridePolicy",
                                "Override " + lm + " (returnType " + lRetType
                                        + ") in " + classifier
                                        + " is not a subtype of inherited " + im
                                        + " (returnType " + iRetType + ")"));
                    }

                    boolean isAbstract = "Yes".equals(lAttrs.get("isAbstract"));
                    if (!isAbstract && !isClassifierAtom(classifier)) {
                        violations.add(new ViolationInfo("OverridePolicy",
                                "Override " + lm + " in " + classifier
                                        + " is non-abstract but classifier is not a Class"));
                    }
                }
            }
        }
    }

    private static class ImplementationBindingInfo {
        // Extracted from relation tuples (mapper emits relation lines)
        final Map<String, String> bindingToImplementer = new LinkedHashMap<>();
        final Map<String, String> bindingToTarget = new LinkedHashMap<>();
        final Map<String, String> bindingToBody = new LinkedHashMap<>();
        final Map<String, String> bodyToBinding = new LinkedHashMap<>();
        final Map<String, Set<String>> methodToImplementers = new LinkedHashMap<>();

        boolean hasBindings() { return !bindingToImplementer.isEmpty(); }
    }

    private ImplementationBindingInfo buildBindingInfo(AieModel model) {
        ImplementationBindingInfo info = new ImplementationBindingInfo();
        // Process relation tuples: implementer[BindingX], target[BindingX], body[BindingX]
        for (TupleEntry t : model.getTuples("implementer")) {
            String binding = t.from;
            String cls = t.to;
            if (binding != null && cls != null && binding.startsWith("ImplementationBinding")) {
                info.bindingToImplementer.put(binding, cls);
            }
        }
        for (TupleEntry t : model.getTuples("target")) {
            String binding = t.from;
            String mtd = t.to;
            if (binding != null && mtd != null && binding.startsWith("ImplementationBinding")) {
                info.bindingToTarget.put(binding, mtd);
                info.methodToImplementers.computeIfAbsent(mtd, k -> new LinkedHashSet<>())
                        .add(info.bindingToImplementer.get(binding));
            }
        }
        for (TupleEntry t : model.getTuples("body")) {
            String binding = t.from;
            String body = t.to;
            if (binding != null && body != null && binding.startsWith("ImplementationBinding")) {
                info.bindingToBody.put(binding, body);
                info.bodyToBinding.put(body, binding);
            }
        }
        return info;
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
                if (line.startsWith("classifiers = {")) {
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
            if (part.isEmpty()) continue;
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
