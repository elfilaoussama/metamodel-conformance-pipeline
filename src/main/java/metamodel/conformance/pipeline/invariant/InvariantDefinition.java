package metamodel.conformance.pipeline.invariant;

import metamodel.conformance.pipeline.model.EvidenceKind;

import java.util.Set;
import java.util.regex.Pattern;

public record InvariantDefinition(
        String id,
        String title,
        String specificationTrace,
        String witnessFunction,
        int witnessArity,
        Set<EvidenceKind> requiredEvidence,
        Set<ProjectionRelation> partitionRelations,
        Set<ProjectionRoot> partitionRoots,
        PartitionPolicy partitionPolicy,
        Set<EvidenceRequirement> evidenceRequirements,
        String conformanceMessage,
        String violationMessage) {

    private static final Pattern SEMANTIC_ID = Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)*");
    private static final Pattern ALLOY_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    public InvariantDefinition {
        if (id == null || id.isBlank() || title == null || title.isBlank()
                || witnessFunction == null || witnessFunction.isBlank()) {
            throw new IllegalArgumentException("invariant identity and witness function are required");
        }
        if (!SEMANTIC_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("invariant id must be a semantic kebab-case identifier");
        }
        if (!ALLOY_NAME.matcher(witnessFunction).matches()) {
            throw new IllegalArgumentException("witnessFunction must be an Alloy identifier");
        }
        if (witnessArity < 1) {
            throw new IllegalArgumentException("witnessArity must be positive");
        }
        requiredEvidence = requiredEvidence == null ? Set.of() : Set.copyOf(requiredEvidence);
        partitionRelations = partitionRelations == null ? Set.of() : Set.copyOf(partitionRelations);
        partitionRoots = partitionRoots == null ? Set.of() : Set.copyOf(partitionRoots);
        partitionPolicy = partitionPolicy == null ? PartitionPolicy.AUTO : partitionPolicy;
        evidenceRequirements = evidenceRequirements == null ? Set.of() : Set.copyOf(evidenceRequirements);
        if (partitionPolicy == PartitionPolicy.ALL_CLASSIFIER_UPWARD
                && (!partitionRoots.equals(Set.of(ProjectionRoot.CLASSIFIER))
                    || !partitionRelations.equals(Set.of(ProjectionRelation.PARENTS)))) {
            throw new IllegalArgumentException("all-classifier upward planning requires classifier roots and parents only");
        }
        if (evidenceRequirements.contains(EvidenceRequirement.SOURCE_ANCESTOR_IMPLEMENTATION_COVERAGE)
                && !requiredEvidence.containsAll(Set.of(EvidenceKind.HIERARCHY,
                    EvidenceKind.DECLARATION_OWNERSHIP, EvidenceKind.IMPLEMENTATION_BINDINGS))) {
            throw new IllegalArgumentException("ancestor implementation coverage requires hierarchy, ownership and bindings");
        }
        if (partitionRoots.isEmpty()) {
            throw new IllegalArgumentException("at least one partition root is required");
        }
        for (ProjectionRelation relation : partitionRelations) {
            if (!requiredEvidence.contains(relation.requiredEvidence())) {
                throw new IllegalArgumentException(
                        "partition relation " + relation + " requires evidence "
                                + relation.requiredEvidence());
            }
        }
        if (conformanceMessage == null || conformanceMessage.isBlank()
                || violationMessage == null || violationMessage.isBlank()) {
            throw new IllegalArgumentException("invariant decision messages are required");
        }
    }
}
