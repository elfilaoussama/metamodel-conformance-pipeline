package metamodel.conformance.pipeline.adapter.python;

import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.NativeEvidence;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Source-derived vanilla-class lookup; distinct from fixed-P0 inheritance. */
final class PythonStaticLookup {
    private PythonStaticLookup() { }

    record Result(boolean complete, String reason, List<NativeEvidence.Lookup> rows) { }

    static Result observe(String context,
            List<ClassifierObservation> classifiers, List<MemberObservation> members,
            List<NativeEvidence.Bases> bases, String guardReason) {
        Map<String, List<String>> graph = new HashMap<>();
        String reason = guardReason == null ? "static lookup admission receipt missing" : guardReason;
        for (NativeEvidence.Bases row : bases) {
            List<String> parents = new ArrayList<>();
            if (row.directBases().knowledge() != NativeEvidence.Knowledge.KNOWN) {
                reason = "direct base sequence unavailable";
            }
            for (NativeEvidence.DirectBase base : row.directBases().values()) {
                if (base.targetClassifierId().knowledge() != NativeEvidence.Knowledge.KNOWN
                        || base.targetClassifierId().values().size() != 1) {
                    reason = "direct base has no unique canonical source target";
                } else parents.add(base.targetClassifierId().values().get(0));
            }
            graph.put(row.classifierId(), List.copyOf(parents));
        }
        if (!graph.keySet().equals(classifiers.stream().map(ClassifierObservation::id)
                .collect(java.util.stream.Collectors.toSet()))) reason = "base receipts do not cover the classifier population";
        Map<String, MemberObservation> byKey = new HashMap<>();
        members.forEach(member -> byKey.put(member.technicalKey(), member));
        Map<String, Map<String, String>> declarations = new HashMap<>();

        for (ClassifierObservation classifier : classifiers) {
            Map<String, String> local = new HashMap<>();
            for (String key : classifier.declaredMemberKeys()) {
                MemberObservation member = byKey.get(key);
                if (member == null) { reason = "declaration target unavailable"; continue; }

                if (local.put(member.memberName(), key) != null) reason = "redefined source member";
            }
            declarations.put(classifier.id(), local);
        }
        Map<String, List<String>> orders = new HashMap<>();
        if (reason.isEmpty()) {
            try {
                orders.putAll(PythonC3Linearization.linearizeAll(graph));
            } catch (IllegalArgumentException invalid) { reason = invalid.getMessage(); }
        }
        List<NativeEvidence.Lookup> rows = new ArrayList<>();
        for (ClassifierObservation classifier : classifiers) {
            TreeSet<String> names = new TreeSet<>();
            Set<String> reachable = new HashSet<>();
            ArrayDeque<String> pending = new ArrayDeque<>();
            pending.add(classifier.id());
            while (!pending.isEmpty()) {
                String owner = pending.removeFirst();
                if (!reachable.add(owner)) continue;
                names.addAll(declarations.getOrDefault(owner, Map.of()).keySet());
                graph.getOrDefault(owner, List.of()).forEach(pending::addLast);
            }
            for (String name : names) {
                NativeEvidence.Evidence<NativeEvidence.LookupTarget> targets;
                if (!reason.isEmpty()) {
                    targets = NativeEvidence.Evidence.unavailable(NativeEvidence.Knowledge.UNKNOWN, reason);
                } else {
                    List<String> order = orders.get(classifier.id());
                    List<NativeEvidence.LookupTarget> selected = new ArrayList<>();
                    for (int i = 0; i < order.size(); i++) {
                        String target = declarations.get(order.get(i)).get(name);
                        if (target != null) {
                            selected.add(new NativeEvidence.LookupTarget(target,
                                    NativeEvidence.LookupMechanism.PYTHON_C3, order.subList(0, i + 1),
                                    NativeEvidence.Access.NOT_APPLICABLE, "",
                                    NativeEvidence.Evidence.unavailable(NativeEvidence.Knowledge.UNKNOWN,
                                            "native override evidence is not admitted by lookup")));
                            break;
                        }
                    }
                    targets = NativeEvidence.Evidence.known(selected);
                }
                rows.add(new NativeEvidence.Lookup(context, classifier.id(), name, targets));
            }
        }
        return new Result(reason.isEmpty(), reason, List.copyOf(rows));
    }
}
