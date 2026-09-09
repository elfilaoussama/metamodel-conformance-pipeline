package metamodel.conformance.pipeline.alloy;

import metamodel.conformance.pipeline.invariant.InvariantDefinition;
import metamodel.conformance.pipeline.invariant.ProjectionRelation;
import metamodel.conformance.pipeline.invariant.ProjectionRoot;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.ImplementationBindingObservation;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.MethodBodyObservation;
import metamodel.conformance.pipeline.model.Observation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

final class AlloyWorkUnitPlanner {
    static final int WORK_UNIT_ATOM_TARGET = 256;
    private static final String CLASSIFIER_PREFIX = "C:";
    private static final String MEMBER_PREFIX = "M:";
    private static final String BODY_PREFIX = "B:";
    private static final String BINDING_PREFIX = "I:";

    List<Observation> plan(Observation observation, InvariantDefinition definition) {
        Map<String, Set<String>> graph = graph(observation, definition.partitionRelations());
        Set<String> visited = new HashSet<>();
        List<Set<String>> components = new ArrayList<>();
        for (String node : graph.keySet()) {
            if (visited.contains(node)) continue;
            Set<String> component = component(node, graph, visited);
            if (containsRoot(component, definition.partitionRoots())) components.add(component);
        }
        List<Observation> result = new ArrayList<>();
        Set<String> packed = new TreeSet<>();
        for (Set<String> component : components) {
            if (!packed.isEmpty() && packed.size() + component.size() > WORK_UNIT_ATOM_TARGET) {
                result.add(project(observation, packed, definition.partitionRelations()));
                packed = new TreeSet<>();
            }
            packed.addAll(component);
            if (packed.size() >= WORK_UNIT_ATOM_TARGET) {
                result.add(project(observation, packed, definition.partitionRelations()));
                packed = new TreeSet<>();
            }
        }
        if (!packed.isEmpty()) result.add(project(observation, packed, definition.partitionRelations()));
        return List.copyOf(result);
    }

    private static Map<String, Set<String>> graph(Observation observation, Set<ProjectionRelation> relations) {
        Map<String, Set<String>> graph = new TreeMap<>();
        observation.classifiers().forEach(item -> graph.put(classifierNode(item.id()), new TreeSet<>()));
        observation.members().forEach(item -> graph.put(memberNode(item.technicalKey()), new TreeSet<>()));
        observation.methodBodies().forEach(item -> graph.put(bodyNode(item.technicalKey()), new TreeSet<>()));
        observation.implementationBindings().forEach(item -> graph.put(bindingNode(item.technicalKey()), new TreeSet<>()));
        for (ClassifierObservation classifier : observation.classifiers()) {
            if (relations.contains(ProjectionRelation.PARENTS)) {
                classifier.parentIds().forEach(parent -> connect(graph, classifierNode(classifier.id()), classifierNode(parent)));
            }
            if (relations.contains(ProjectionRelation.DECLARED_MEMBERS)) {
                classifier.declaredMemberKeys().forEach(member -> connect(graph, classifierNode(classifier.id()), memberNode(member)));
            }
            if (relations.contains(ProjectionRelation.OBSERVED_INHERITED_MEMBERS)) {
                classifier.inheritedMemberKeys().forEach(member -> connect(graph, classifierNode(classifier.id()), memberNode(member)));
            }
        }
        if (relations.contains(ProjectionRelation.OVERRIDE_RELATIONS)) {
            for (MemberObservation member : observation.members()) {
                member.overriddenMemberKeys().forEach(overridden ->
                        connect(graph, memberNode(member.technicalKey()), memberNode(overridden)));
            }
        }
        if (relations.contains(ProjectionRelation.IMPLEMENTATION_BINDINGS)) {
            for (ImplementationBindingObservation binding : observation.implementationBindings()) {
                String node = bindingNode(binding.technicalKey());
                connect(graph, node, classifierNode(binding.implementerClassifierId()));
                connect(graph, node, memberNode(binding.targetMemberKey()));
                connect(graph, node, bodyNode(binding.bodyKey()));
            }
        }
        return graph;
    }

    private static void connect(Map<String, Set<String>> graph, String left, String right) {
        graph.get(left).add(right);
        graph.get(right).add(left);
    }

    private static Set<String> component(String start, Map<String, Set<String>> graph, Set<String> visited) {
        Set<String> result = new TreeSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(start);
        visited.add(start);
        while (!pending.isEmpty()) {
            String node = pending.removeFirst();
            result.add(node);
            for (String adjacent : graph.get(node)) if (visited.add(adjacent)) pending.addLast(adjacent);
        }
        return result;
    }

    private static boolean containsRoot(Set<String> component, Set<ProjectionRoot> roots) {
        return component.stream().anyMatch(node ->
                roots.contains(ProjectionRoot.CLASSIFIER) && node.startsWith(CLASSIFIER_PREFIX)
                        || roots.contains(ProjectionRoot.MEMBER) && node.startsWith(MEMBER_PREFIX)
                        || roots.contains(ProjectionRoot.BODY) && node.startsWith(BODY_PREFIX));
    }

    private static Observation project(Observation source, Set<String> component, Set<ProjectionRelation> relations) {
        Set<String> classifierIds = new HashSet<>();
        Set<String> memberKeys = new HashSet<>();
        Set<String> bodyKeys = new HashSet<>();
        Set<String> bindingKeys = new HashSet<>();
        component.forEach(node -> {
            if (node.startsWith(CLASSIFIER_PREFIX)) classifierIds.add(node.substring(CLASSIFIER_PREFIX.length()));
            else if (node.startsWith(MEMBER_PREFIX)) memberKeys.add(node.substring(MEMBER_PREFIX.length()));
            else if (node.startsWith(BODY_PREFIX)) bodyKeys.add(node.substring(BODY_PREFIX.length()));
            else if (node.startsWith(BINDING_PREFIX)) bindingKeys.add(node.substring(BINDING_PREFIX.length()));
        });

        List<ClassifierObservation> classifiers = source.classifiers().stream()
                .filter(item -> classifierIds.contains(item.id()))
                .map(item -> new ClassifierObservation(
                        item.id(), item.qualifiedName(), item.packageName(), item.kind(), item.sourcePath(),
                        item.startLine(), item.endLine(),
                        relations.contains(ProjectionRelation.PARENTS) ? retained(item.parentIds(), classifierIds) : List.of(),
                        relations.contains(ProjectionRelation.DECLARED_MEMBERS) ? retained(item.declaredMemberKeys(), memberKeys) : List.of(),
                        relations.contains(ProjectionRelation.OBSERVED_INHERITED_MEMBERS) ? retained(item.inheritedMemberKeys(), memberKeys) : List.of(),
                        item.abstraction()))
                .toList();
        List<MemberObservation> members = source.members().stream()
                .filter(item -> memberKeys.contains(item.technicalKey()))
                .map(item -> new MemberObservation(
                        item.technicalKey(), item.observedIdentifier(), item.kind(), item.inheritability(),
                        item.visibility(), item.memberName(), item.sourcePath(), item.startLine(), item.endLine(),
                        item.parameterTypes(), item.abstraction(), item.scope(), item.returnType(),
                        relations.contains(ProjectionRelation.OVERRIDE_RELATIONS)
                                ? retained(item.overriddenMemberKeys(), memberKeys) : List.of()))
                .toList();
        List<MethodBodyObservation> bodies = source.methodBodies().stream()
                .filter(item -> bodyKeys.contains(item.technicalKey())).toList();
        List<ImplementationBindingObservation> bindings = relations.contains(ProjectionRelation.IMPLEMENTATION_BINDINGS)
                ? source.implementationBindings().stream()
                        .filter(item -> bindingKeys.contains(item.technicalKey()))
                        .filter(item -> classifierIds.contains(item.implementerClassifierId())
                                && memberKeys.contains(item.targetMemberKey())
                                && bodyKeys.contains(item.bodyKey()))
                        .toList()
                : List.of();
        return new Observation(
                source.schemaVersion(), source.adapterId(), source.adapterVersion(),
                source.externalParents(), source.completeEvidence(), source.units(),
                classifiers, members, bodies, bindings, List.of(), List.of());
    }

    private static List<String> retained(List<String> values, Set<String> retained) {
        return values.stream().filter(retained::contains).toList();
    }

    private static String classifierNode(String id) { return CLASSIFIER_PREFIX + id; }
    private static String memberNode(String key) { return MEMBER_PREFIX + key; }
    private static String bodyNode(String key) { return BODY_PREFIX + key; }
    private static String bindingNode(String key) { return BINDING_PREFIX + key; }
}
