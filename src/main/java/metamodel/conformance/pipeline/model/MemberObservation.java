package metamodel.conformance.pipeline.model;

import java.util.List;
import java.util.Objects;

public record MemberObservation(
        String technicalKey,
        String observedIdentifier,
        MemberKind kind,
        Inheritability inheritability,
        MemberVisibility visibility,
        String memberName,
        String sourcePath,
        int startLine,
        int endLine,
        List<String> parameterTypes,
        MethodAbstraction abstraction,
        MemberScope scope,
        String returnType,
        List<String> overriddenMemberKeys) {

    public MemberObservation {
        technicalKey = CanonicalObservationValue.technicalId(
                technicalKey, "mem_", "technicalKey");
        observedIdentifier = observedIdentifier == null || observedIdentifier.isBlank()
                ? null : observedIdentifier;
        kind = Objects.requireNonNull(kind, "kind");
        inheritability = Objects.requireNonNull(inheritability, "inheritability");
        visibility = Objects.requireNonNull(visibility, "visibility");
        memberName = requireText(memberName, "memberName");
        sourcePath = CanonicalObservationValue.relativePath(sourcePath, "sourcePath");
        if (startLine < 1 || endLine < startLine) {
            throw new IllegalArgumentException("invalid member source line range");
        }
        parameterTypes = parameterTypes == null ? List.of() : List.copyOf(parameterTypes);
        if (kind == MemberKind.ATTRIBUTE && !parameterTypes.isEmpty()) {
            throw new IllegalArgumentException("attributes cannot have parameter types");
        }
        if (parameterTypes.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("parameter types must not be blank");
        }
        abstraction = abstraction == null ? MethodAbstraction.UNKNOWN : abstraction;
        scope = scope == null ? MemberScope.UNKNOWN : scope;
        returnType = returnType == null || returnType.isBlank() ? null : returnType;
        overriddenMemberKeys = overriddenMemberKeys == null ? List.of() : overriddenMemberKeys.stream()
                .map(value -> CanonicalObservationValue.technicalId(
                        value, "mem_", "overriddenMemberKey"))
                .sorted().distinct().toList();
        if (kind == MemberKind.ATTRIBUTE && abstraction != MethodAbstraction.UNKNOWN) {
            throw new IllegalArgumentException("attributes cannot carry method abstraction evidence");
        }
        if (kind == MemberKind.ATTRIBUTE && scope != MemberScope.UNKNOWN) {
            throw new IllegalArgumentException("attributes cannot carry method scope evidence");
        }
        if (kind == MemberKind.ATTRIBUTE && returnType != null) {
            throw new IllegalArgumentException("attributes cannot carry method return-type evidence");
        }
        if (kind == MemberKind.ATTRIBUTE && !overriddenMemberKeys.isEmpty()) {
            throw new IllegalArgumentException("attributes cannot carry override evidence");
        }
    }

    public MemberObservation(
            String technicalKey,
            String observedIdentifier,
            MemberKind kind,
            Inheritability inheritability,
            MemberVisibility visibility,
            String memberName,
            String sourcePath,
            int startLine,
            int endLine,
            List<String> parameterTypes,
            MethodAbstraction abstraction,
            MemberScope scope) {
        this(technicalKey, observedIdentifier, kind, inheritability, visibility,
                memberName, sourcePath, startLine, endLine, parameterTypes,
                abstraction, scope, null, List.of());
    }

    public MemberObservation(
            String technicalKey,
            String observedIdentifier,
            MemberKind kind,
            Inheritability inheritability,
            MemberVisibility visibility,
            String memberName,
            String sourcePath,
            int startLine,
            int endLine,
            List<String> parameterTypes,
            MethodAbstraction abstraction) {
        this(technicalKey, observedIdentifier, kind, inheritability, visibility,
                memberName, sourcePath, startLine, endLine, parameterTypes,
                abstraction, MemberScope.UNKNOWN);
    }

    public MemberObservation(
            String technicalKey,
            String observedIdentifier,
            MemberKind kind,
            Inheritability inheritability,
            MemberVisibility visibility,
            String memberName,
            String sourcePath,
            int startLine,
            int endLine,
            List<String> parameterTypes) {
        this(technicalKey, observedIdentifier, kind, inheritability, visibility,
                memberName, sourcePath, startLine, endLine, parameterTypes,
                MethodAbstraction.UNKNOWN, MemberScope.UNKNOWN);
    }

    public MemberObservation(
            String technicalKey,
            String observedIdentifier,
            MemberKind kind,
            Inheritability inheritability,
            String memberName,
            String sourcePath,
            int startLine,
            int endLine,
            List<String> parameterTypes) {
        this(technicalKey, observedIdentifier, kind, inheritability, MemberVisibility.PUBLIC,
                memberName, sourcePath, startLine, endLine, parameterTypes);
    }

    public MemberObservation(
            String technicalKey,
            String observedIdentifier,
            MemberKind kind,
            String memberName,
            String sourcePath,
            int startLine,
            int endLine,
            List<String> parameterTypes) {
        this(technicalKey, observedIdentifier, kind, Inheritability.UNKNOWN, MemberVisibility.PUBLIC,
                memberName, sourcePath, startLine, endLine, parameterTypes);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
