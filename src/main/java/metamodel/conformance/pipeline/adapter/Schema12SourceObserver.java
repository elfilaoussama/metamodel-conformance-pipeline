package metamodel.conformance.pipeline.adapter;

import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.Observation;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Upgrades frontends that do not yet emit schema-12 override/return-type evidence. */
final class Schema12SourceObserver implements SourceObserver {
    private final SourceObserver delegate;

    Schema12SourceObserver(SourceObserver delegate) {
        this.delegate = delegate;
    }

    @Override
    public Observation observe(Path sourceRoot, Set<String> externalParents) throws ObservationException {
        Observation base = delegate.observe(sourceRoot, externalParents);
        List<MemberObservation> members = base.members().stream()
                .map(member -> new MemberObservation(
                        member.technicalKey(), member.observedIdentifier(), member.kind(),
                        member.inheritability(), member.visibility(), member.memberName(),
                        member.sourcePath(), member.startLine(), member.endLine(), member.parameterTypes(),
                        member.abstraction(), member.scope(), null, List.of()))
                .toList();
        return new Observation(
                "12",
                base.adapterId(),
                base.adapterVersion(),
                base.externalParents(),
                base.completeEvidence(),
                base.units(),
                base.classifiers(),
                members,
                List.of(),
                List.of(),
                base.unresolvedParents(),
                base.diagnostics());
    }
}
