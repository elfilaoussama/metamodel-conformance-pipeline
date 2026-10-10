package metamodel.conformance.pipeline.invariant;

import metamodel.conformance.pipeline.model.EvidenceKind;

public enum ProjectionRelation {
    PARENTS(EvidenceKind.HIERARCHY),
    DECLARED_MEMBERS(EvidenceKind.DECLARATION_OWNERSHIP),
    OBSERVED_INHERITED_MEMBERS(EvidenceKind.INHERITED_MEMBERS),
    IMPLEMENTATION_BINDINGS(EvidenceKind.IMPLEMENTATION_BINDINGS),
    OVERRIDE_RELATIONS(EvidenceKind.OVERRIDE_RELATIONS);

    private final EvidenceKind requiredEvidence;

    ProjectionRelation(EvidenceKind requiredEvidence) {
        this.requiredEvidence = requiredEvidence;
    }

    public EvidenceKind requiredEvidence() {
        return requiredEvidence;
    }
}
