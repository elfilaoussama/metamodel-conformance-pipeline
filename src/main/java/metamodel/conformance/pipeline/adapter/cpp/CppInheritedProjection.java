package metamodel.conformance.pipeline.adapter.cpp;

import metamodel.conformance.pipeline.adapter.ObservationException;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import java.util.*;

/** Projects compiler-selected ancestor declarations; contains no P0 policy. */
final class CppInheritedProjection {
    private CppInheritedProjection() { }

    static Optional<Map<String,List<String>>> project(List<ClassifierObservation> classifiers,
            CppNativeLookupReceipt.Result census) throws ObservationException {
        var byId=new HashMap<String,ClassifierObservation>();
        var ownerByMember=new HashMap<String,String>();
        for (var classifier:classifiers) {
            if (byId.put(classifier.id(),classifier)!=null)
                throw new ObservationException("duplicate C++ classifier in lookup projection");
            for (String member:classifier.declaredMemberKeys())
                if (ownerByMember.put(member,classifier.id())!=null)
                    throw new ObservationException("duplicate C++ declaration owner in lookup projection");
        }
        if (!census.populationObserved() || !census.classifierIds().equals(byId.keySet())
                || !census.completeClassifierIds().equals(byId.keySet())) return Optional.empty();
        var selected=new HashMap<String,Set<String>>();
        var inherited=new HashMap<String,Set<String>>();
        for (var row:census.lookups()) {
            var classifier=byId.get(row.ownerClassifierId());
            if (classifier==null) throw new ObservationException("C++ lookup outside contextual population");
            if (row.targets().knowledge()!=metamodel.conformance.pipeline.model.NativeEvidence.Knowledge.KNOWN)
                return Optional.empty();
            for (var target:row.targets().values()) {
                String owner=ownerByMember.get(target.declarationKey());
                var path=target.classifierPath();
                if (owner==null || path.isEmpty() || !path.get(0).equals(classifier.id())
                        || !path.get(path.size()-1).equals(owner))
                    throw new ObservationException("C++ lookup declaration/path owner mismatch");
                for (int i=1;i<path.size();i++) {
                    var previous=byId.get(path.get(i-1));
                    if (previous==null || !previous.parentIds().contains(path.get(i)))
                        throw new ObservationException("C++ lookup path disagrees with observed hierarchy");
                }
                selected.computeIfAbsent(classifier.id(),ignored->new HashSet<>()).add(target.declarationKey());
                if (!owner.equals(classifier.id()))
                    inherited.computeIfAbsent(classifier.id(),ignored->new HashSet<>()).add(target.declarationKey());
            }
        }
        var output=new TreeMap<String,List<String>>();
        for (var classifier:classifiers) {
            // A missing local target cannot masquerade as a complete empty lookup census.
            if (!selected.getOrDefault(classifier.id(),Set.of()).containsAll(classifier.declaredMemberKeys()))
                return Optional.empty();
            output.put(classifier.id(),inherited.getOrDefault(classifier.id(),Set.of()).stream().sorted().toList());
        }
        return Optional.of(Map.copyOf(output));
    }
}
