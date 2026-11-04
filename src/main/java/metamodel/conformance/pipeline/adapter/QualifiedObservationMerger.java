package metamodel.conformance.pipeline.adapter;

import metamodel.conformance.pipeline.model.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Combines independently qualified frames without changing identities or admitting evidence. */
public final class QualifiedObservationMerger {
    private QualifiedObservationMerger() { }

    public static Observation merge(Map<String, Observation> frames) {
        if (frames == null || frames.isEmpty())
            throw new IllegalArgumentException("no qualified observation frames");
        for (var entry : frames.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null)
                throw new IllegalArgumentException("qualified frames require nonblank labels and observations");
        }
        List<Observation> ordered = List.copyOf(new TreeMap<>(frames).values());
        Observation first = ordered.get(0);
        var evidence = new HashSet<>(first.completeEvidence());
        Map<String, SourceUnit> units = new TreeMap<>();
        var classifiers = new ArrayList<ClassifierObservation>();
        var members = new ArrayList<MemberObservation>();
        var bodies = new ArrayList<MethodBodyObservation>();
        var bindings = new ArrayList<ImplementationBindingObservation>();
        var unresolved = new ArrayList<UnresolvedParent>();
        var diagnostics = new ArrayList<ObservationDiagnostic>();
        var platform = new ArrayList<PlatformEvidence>();
        var domains = new ArrayList<NativeEvidence.Domain>();
        var roles = new ArrayList<NativeEvidence.RoleReceipt>();
        var invocations = new ArrayList<NativeEvidence.Invocation>();
        var bases = new ArrayList<NativeEvidence.Bases>();
        var signatures = new ArrayList<NativeEvidence.Signature>();
        var lookups = new ArrayList<NativeEvidence.Lookup>();
        var bodyBindings = new ArrayList<NativeEvidence.BodyBinding>();
        var reported = new ArrayList<NativeEvidence.ReportedInputs>();
        var selections = new ArrayList<NativeEvidence.BuildSelection>();
        var correspondences = new ArrayList<NativeEvidence.DefinitionCorrespondence>();
        var languageModes = new ArrayList<NativeEvidence.LanguageMode>();
        for (Observation frame : ordered) {
            if (!first.schemaVersion().equals(frame.schemaVersion())
                    || !first.adapterId().equals(frame.adapterId())
                    || !first.adapterVersion().equals(frame.adapterVersion())
                    || !first.externalParents().equals(frame.externalParents()))
                throw new IllegalArgumentException("incompatible qualified observation frame metadata");
            evidence.retainAll(frame.completeEvidence());
            for (SourceUnit unit : frame.units()) {
                SourceUnit previous = units.putIfAbsent(unit.path(), unit);
                if (previous != null && !previous.equals(unit))
                    throw new IllegalArgumentException("physical source changed across frames: " + unit.path());
            }
            classifiers.addAll(frame.classifiers());
            members.addAll(frame.members());
            bodies.addAll(frame.methodBodies());
            bindings.addAll(frame.implementationBindings());
            unresolved.addAll(frame.unresolvedParents());
            diagnostics.addAll(frame.diagnostics());
            platform.addAll(frame.platformEvidence());
            NativeEvidence.Bundle nativeEvidence = frame.nativeEvidence();
            domains.addAll(nativeEvidence.domains());
            roles.addAll(nativeEvidence.roles());
            invocations.addAll(nativeEvidence.invocations());
            bases.addAll(nativeEvidence.bases());
            signatures.addAll(nativeEvidence.signatures());
            lookups.addAll(nativeEvidence.lookups());
            bodyBindings.addAll(nativeEvidence.bodyBindings());
            reported.addAll(nativeEvidence.reportedInputs());
            selections.addAll(nativeEvidence.buildSelections());
            correspondences.addAll(nativeEvidence.definitionCorrespondences());
            languageModes.addAll(nativeEvidence.languageModes());
        }
        return new Observation(first.schemaVersion(), first.adapterId(), first.adapterVersion(),
                first.externalParents(), evidence, List.copyOf(units.values()), classifiers, members,
                bodies, bindings, unresolved, diagnostics.stream().distinct().toList(),
                platform.stream().distinct().toList(),
                new NativeEvidence.Bundle(domains, roles, invocations, bases, signatures, lookups,
                        bodyBindings, reported, selections, correspondences,languageModes));
    }
}
