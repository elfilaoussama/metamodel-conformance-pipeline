package metamodel.conformance.pipeline.alloy;

import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.ImplementationBindingObservation;
import metamodel.conformance.pipeline.model.Language;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.MemberScope;
import metamodel.conformance.pipeline.model.MemberVisibility;
import metamodel.conformance.pipeline.model.MethodAbstraction;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.model.SourceUnit;
import metamodel.conformance.pipeline.util.Hashing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ExactAlloyEncoder {
    private static final int RELATION_CHUNK_SIZE = 64;

    public String encode(Observation observation) {
        Map<String, String> nameAtoms = tokens(observation.members().stream()
                .map(MemberObservation::memberName).toList(), "N_");
        Map<String, String> typeAtoms = tokens(observation.members().stream()
                .flatMap(member -> Stream.concat(
                        member.parameterTypes().stream(), Stream.ofNullable(member.returnType())))
                .toList(), "T_");
        Map<String, String> packageAtoms = tokens(observation.classifiers().stream()
                .map(ClassifierObservation::packageName).toList(), "PKG_");
        int positionCount = observation.members().stream()
                .mapToInt(member -> member.parameterTypes().size()).max().orElse(0);
        Set<String> repositorySourcePaths = observation.units().stream()
                .filter(unit -> unit.language() != Language.JAVA_ARCHIVE)
                .map(SourceUnit::path)
                .collect(Collectors.toUnmodifiableSet());
        List<String> sourceClassifierAtoms = observation.classifiers().stream()
                .filter(classifier -> repositorySourcePaths.contains(classifier.sourcePath()))
                .map(classifier -> classifierAtom(classifier.id()))
                .sorted()
                .toList();

        StringBuilder alloy = new StringBuilder();
        alloy.append("module repository_instance\n\n");
        alloy.append("abstract sig ClassifierAbstraction {}\n")
                .append("one sig CLASSIFIER_ABSTRACT, CLASSIFIER_CONCRETE, CLASSIFIER_ABSTRACTION_UNKNOWN extends ClassifierAbstraction {}\n")
                .append("abstract sig Classifier {\n")
                .append("  parents: set Classifier,\n")
                .append("  declaredMembers: set Member,\n")
                .append("  observedInheritedMembers: set Member,\n")
                .append("  packageName: one PackageToken,\n")
                .append("  classifierAbstraction: one ClassifierAbstraction\n")
                .append("}\n")
                .append("abstract sig MemberKind {}\n")
                .append("one sig METHOD, ATTRIBUTE extends MemberKind {}\n")
                .append("abstract sig Inheritability {}\n")
                .append("one sig INHERITABLE, NOT_INHERITABLE, UNKNOWN extends Inheritability {}\n")
                .append("abstract sig MemberVisibility {}\n")
                .append("one sig PUBLIC, PROTECTED, PACKAGE, PRIVATE, VISIBILITY_UNKNOWN extends MemberVisibility {}\n")
                .append("abstract sig MethodAbstraction {}\n")
                .append("one sig ABSTRACT, CONCRETE, ABSTRACTION_UNKNOWN extends MethodAbstraction {}\n")
                .append("abstract sig MemberScope {}\n")
                .append("one sig INSTANCE_SCOPE, STATIC_SCOPE, SCOPE_UNKNOWN extends MemberScope {}\n")
                .append("abstract sig PackageToken {}\n")
                .append("abstract sig NameToken {}\n")
                .append("abstract sig TypeToken {}\n")
                .append("abstract sig PositionToken {}\n")
                .append("abstract sig MethodBody {}\n")
                .append("abstract sig Member {\n")
                .append("  kind: one MemberKind,\n")
                .append("  inheritability: one Inheritability,\n")
                .append("  visibility: one MemberVisibility,\n")
                .append("  memberName: one NameToken,\n")
                .append("  parameterTypeAt: PositionToken -> lone TypeToken,\n")
                .append("  abstraction: one MethodAbstraction,\n")
                .append("  memberScope: one MemberScope,\n")
                .append("  returnType: lone TypeToken,\n")
                .append("  observedOverrides: set Member\n")
                .append("}\n")
                .append("abstract sig ImplementationBinding {\n")
                .append("  implementer: one Classifier,\n")
                .append("  target: one Member,\n")
                .append("  body: one MethodBody\n")
                .append("}\n\n");

        observation.classifiers().forEach(item -> alloy.append("one sig ")
                .append(classifierAtom(item.id())).append(" extends Classifier {}\n"));
        observation.members().forEach(item -> alloy.append("one sig ")
                .append(memberAtom(item.technicalKey())).append(" extends Member {}\n"));
        observation.methodBodies().forEach(item -> alloy.append("one sig ")
                .append(bodyAtom(item.technicalKey())).append(" extends MethodBody {}\n"));
        observation.implementationBindings().forEach(item -> alloy.append("one sig ")
                .append(bindingAtom(item.technicalKey())).append(" extends ImplementationBinding {}\n"));
        nameAtoms.values().forEach(atom -> alloy.append("one sig ").append(atom).append(" extends NameToken {}\n"));
        typeAtoms.values().forEach(atom -> alloy.append("one sig ").append(atom).append(" extends TypeToken {}\n"));
        packageAtoms.values().forEach(atom -> alloy.append("one sig ").append(atom).append(" extends PackageToken {}\n"));
        for (int position = 0; position < positionCount; position++) {
            alloy.append("one sig P_").append(position).append(" extends PositionToken {}\n");
        }
        alloy.append("\nfun SourceClassifiers : set Classifier {\n  ")
                .append(sourceClassifierAtoms.isEmpty() ? "none" : String.join(" + ", sourceClassifierAtoms))
                .append("\n}\n");

        alloy.append("\nfact ExactObservation {\n");
        relation(alloy, "parents", parentEdges(observation));
        relation(alloy, "declaredMembers", declarationEdges(observation));
        relation(alloy, "observedInheritedMembers", inheritedMembershipEdges(observation));
        relation(alloy, "packageName", packageEdges(observation, packageAtoms));
        relation(alloy, "classifierAbstraction", classifierAbstractionEdges(observation));
        relation(alloy, "kind", kindEdges(observation));
        relation(alloy, "inheritability", inheritabilityEdges(observation));
        relation(alloy, "visibility", visibilityEdges(observation));
        relation(alloy, "memberName", nameEdges(observation, nameAtoms));
        relation(alloy, "parameterTypeAt", parameterTypeEdges(observation, typeAtoms));
        relation(alloy, "abstraction", abstractionEdges(observation));
        relation(alloy, "memberScope", memberScopeEdges(observation));
        relation(alloy, "returnType", returnTypeEdges(observation, typeAtoms));
        relation(alloy, "observedOverrides", overrideEdges(observation));
        relation(alloy, "implementer", implementerEdges(observation));
        relation(alloy, "target", targetEdges(observation));
        relation(alloy, "body", bodyEdges(observation));
        alloy.append("}\n\n");
        alloy.append(loadRules()).append('\n');
        alloy.append("run ObservationConsistency ")
                .append(scope(observation, nameAtoms.size(), typeAtoms.size(), packageAtoms.size(), positionCount))
                .append('\n');
        return alloy.toString();
    }

    public Map<String, String> atomTechnicalKeys(Observation observation) {
        Map<String, String> result = new LinkedHashMap<>();
        observation.classifiers().forEach(item -> result.put(classifierAtom(item.id()), item.id()));
        observation.members().forEach(item -> result.put(memberAtom(item.technicalKey()), item.technicalKey()));
        observation.methodBodies().forEach(item -> result.put(bodyAtom(item.technicalKey()), item.technicalKey()));
        observation.implementationBindings().forEach(item ->
                result.put(bindingAtom(item.technicalKey()), item.technicalKey()));
        return Map.copyOf(result);
    }

    static String classifierAtom(String id) {
        if (!id.matches("cls_[0-9a-f]{64}")) throw new IllegalArgumentException("unsafe or invalid classifier id: " + id);
        return "C_" + id.substring(4);
    }

    static String memberAtom(String key) {
        if (!key.matches("mem_[0-9a-f]{64}")) throw new IllegalArgumentException("unsafe or invalid member key: " + key);
        return "M_" + key.substring(4);
    }

    static String bodyAtom(String key) {
        if (!key.matches("body_[0-9a-f]{64}")) throw new IllegalArgumentException("unsafe or invalid method-body key: " + key);
        return "B_" + key.substring(5);
    }

    static String bindingAtom(String key) {
        if (!key.matches("bind_[0-9a-f]{64}")) throw new IllegalArgumentException("unsafe or invalid binding key: " + key);
        return "I_" + key.substring(5);
    }

    private static Map<String, String> tokens(List<String> values, String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        values.stream().distinct().sorted().forEach(value -> result.put(value, prefix + Hashing.sha256(value)));
        return Collections.unmodifiableMap(result);
    }

    private static List<String> parentEdges(Observation o) {
        List<String> edges = new ArrayList<>();
        for (ClassifierObservation c : o.classifiers()) c.parentIds().forEach(p -> edges.add(classifierAtom(c.id()) + "->" + classifierAtom(p)));
        return edges;
    }

    private static List<String> declarationEdges(Observation o) {
        List<String> edges = new ArrayList<>();
        for (ClassifierObservation c : o.classifiers()) c.declaredMemberKeys().forEach(m -> edges.add(classifierAtom(c.id()) + "->" + memberAtom(m)));
        return edges;
    }

    private static List<String> inheritedMembershipEdges(Observation o) {
        List<String> edges = new ArrayList<>();
        for (ClassifierObservation c : o.classifiers()) c.inheritedMemberKeys().forEach(m -> edges.add(classifierAtom(c.id()) + "->" + memberAtom(m)));
        return edges;
    }

    private static List<String> classifierAbstractionEdges(Observation o) {
        return o.classifiers().stream().map(c -> classifierAtom(c.id()) + "->" + switch (c.abstraction()) {
            case ABSTRACT -> "CLASSIFIER_ABSTRACT";
            case CONCRETE -> "CLASSIFIER_CONCRETE";
            case UNKNOWN -> "CLASSIFIER_ABSTRACTION_UNKNOWN";
        }).toList();
    }

    private static List<String> kindEdges(Observation o) {
        return o.members().stream().map(m -> memberAtom(m.technicalKey()) + "->" + (m.kind() == MemberKind.METHOD ? "METHOD" : "ATTRIBUTE")).toList();
    }

    private static List<String> inheritabilityEdges(Observation o) {
        return o.members().stream().map(m -> memberAtom(m.technicalKey()) + "->" + m.inheritability().name()).toList();
    }

    private static List<String> visibilityEdges(Observation o) {
        return o.members().stream().map(m -> memberAtom(m.technicalKey()) + "->" + (m.visibility() == MemberVisibility.UNKNOWN ? "VISIBILITY_UNKNOWN" : m.visibility().name())).toList();
    }

    private static List<String> abstractionEdges(Observation o) {
        return o.members().stream().map(m -> memberAtom(m.technicalKey()) + "->" + (m.abstraction() == MethodAbstraction.UNKNOWN ? "ABSTRACTION_UNKNOWN" : m.abstraction().name())).toList();
    }

    private static List<String> memberScopeEdges(Observation o) {
        return o.members().stream().map(m -> memberAtom(m.technicalKey()) + "->" + switch (m.scope()) {
            case INSTANCE -> "INSTANCE_SCOPE";
            case STATIC -> "STATIC_SCOPE";
            case UNKNOWN -> "SCOPE_UNKNOWN";
        }).toList();
    }

    private static List<String> returnTypeEdges(Observation o, Map<String, String> atoms) {
        return o.members().stream()
                .filter(member -> member.returnType() != null)
                .map(member -> memberAtom(member.technicalKey()) + "->" + atoms.get(member.returnType()))
                .toList();
    }

    private static List<String> overrideEdges(Observation o) {
        List<String> edges = new ArrayList<>();
        for (MemberObservation member : o.members()) {
            member.overriddenMemberKeys().forEach(overridden ->
                    edges.add(memberAtom(member.technicalKey()) + "->" + memberAtom(overridden)));
        }
        return edges;
    }

    private static List<String> implementerEdges(Observation o) {
        return o.implementationBindings().stream().map(b -> bindingAtom(b.technicalKey()) + "->" + classifierAtom(b.implementerClassifierId())).toList();
    }

    private static List<String> targetEdges(Observation o) {
        return o.implementationBindings().stream().map(b -> bindingAtom(b.technicalKey()) + "->" + memberAtom(b.targetMemberKey())).toList();
    }

    private static List<String> bodyEdges(Observation o) {
        return o.implementationBindings().stream().map(b -> bindingAtom(b.technicalKey()) + "->" + bodyAtom(b.bodyKey())).toList();
    }

    private static List<String> packageEdges(Observation o, Map<String, String> atoms) {
        return o.classifiers().stream().map(c -> classifierAtom(c.id()) + "->" + atoms.get(c.packageName())).toList();
    }

    private static List<String> nameEdges(Observation o, Map<String, String> atoms) {
        return o.members().stream().map(m -> memberAtom(m.technicalKey()) + "->" + atoms.get(m.memberName())).toList();
    }

    private static List<String> parameterTypeEdges(Observation o, Map<String, String> atoms) {
        List<String> edges = new ArrayList<>();
        for (MemberObservation m : o.members()) {
            for (int p = 0; p < m.parameterTypes().size(); p++) {
                edges.add(memberAtom(m.technicalKey()) + "->P_" + p + "->" + atoms.get(m.parameterTypes().get(p)));
            }
        }
        return edges;
    }

    private static void relation(StringBuilder alloy, String name, List<String> edges) {
        List<String> sorted = edges.stream().sorted(Comparator.naturalOrder()).toList();
        if (sorted.isEmpty()) {
            alloy.append("  no ").append(name).append('\n');
            return;
        }
        alloy.append("  ").append(name).append(" = ");
        for (int start = 0; start < sorted.size(); start += RELATION_CHUNK_SIZE) {
            if (start > 0) alloy.append(" +\n    ");
            int end = Math.min(start + RELATION_CHUNK_SIZE, sorted.size());
            alloy.append('(').append(String.join(" + ", sorted.subList(start, end))).append(')');
        }
        alloy.append('\n');
    }

    private static String scope(Observation o, int names, int types, int packages, int positions) {
        return "for exactly " + o.classifiers().size() + " Classifier, exactly "
                + o.members().size() + " Member, exactly " + o.methodBodies().size()
                + " MethodBody, exactly " + o.implementationBindings().size()
                + " ImplementationBinding, exactly " + names + " NameToken, exactly " + types
                + " TypeToken, exactly " + packages + " PackageToken, exactly " + positions + " PositionToken";
    }

    private static String loadRules() {
        try (InputStream input = ExactAlloyEncoder.class.getResourceAsStream("/alloy/invariants.als")) {
            if (input == null) throw new IllegalStateException("bundled Alloy invariants are missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot load Alloy invariants", failure);
        }
    }
}
