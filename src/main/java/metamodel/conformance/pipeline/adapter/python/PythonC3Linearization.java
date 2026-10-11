package metamodel.conformance.pipeline.adapter.python;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** C3 over an independently observed ordered base graph; never evaluates P0. */
final class PythonC3Linearization {
    private PythonC3Linearization() { }

    static List<String> linearize(String root, Map<String, List<String>> bases) {
        return visit(root, bases, new HashMap<>(), new HashSet<>());
    }

    static Map<String, List<String>> linearizeAll(Map<String, List<String>> bases) {
        Map<String, List<String>> completed = new HashMap<>();
        Set<String> active = new HashSet<>();
        for (String root : bases.keySet()) visit(root, bases, completed, active);
        return Map.copyOf(completed);
    }
    private static List<String> visit(String root, Map<String, List<String>> bases,
            Map<String, List<String>> completed, Set<String> active) {
        if (completed.containsKey(root)) return completed.get(root);
        List<String> parents = bases.get(root);
        if (parents == null) throw new IllegalArgumentException("unobserved C3 base: " + root);
        if (!active.add(root)) throw new IllegalArgumentException("cyclic C3 bases: " + root);
        if (new HashSet<>(parents).size() != parents.size()) {
            throw new IllegalArgumentException("duplicate C3 direct base: " + root);
        }
        List<List<String>> sequences = new ArrayList<>();
        for (String parent : parents) sequences.add(new ArrayList<>(visit(parent, bases, completed, active)));
        sequences.add(new ArrayList<>(parents));
        List<String> order = new ArrayList<>();
        order.add(root);
        while (sequences.stream().anyMatch(sequence -> !sequence.isEmpty())) {
            String selected = null;
            for (List<String> sequence : sequences) {
                if (sequence.isEmpty()) continue;
                String head = sequence.get(0);
                if (sequences.stream().noneMatch(other -> other.subList(Math.min(1, other.size()), other.size()).contains(head))) {
                    selected = head;
                    break;
                }
            }
            if (selected == null) throw new IllegalArgumentException("inconsistent C3 ordering: " + root);
            order.add(selected);
            for (List<String> sequence : sequences) {
                if (!sequence.isEmpty() && sequence.get(0).equals(selected)) sequence.remove(0);
            }
        }
        active.remove(root);
        List<String> result = List.copyOf(order);
        completed.put(root, result);
        return result;
    }
}
