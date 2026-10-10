package metamodel.conformance.pipeline.model;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Typed native measurements, distinct from the fixed P0 projection.
 * These records carry observations, never P0 decisions or completeness claims.
 * Schema 14 preserves these records; adapter population is incremental and
 * absent receipts do not assert known-empty facts or grant completeness.
 */
public final class NativeEvidence {
    private NativeEvidence() { }

    public enum Knowledge { KNOWN, UNKNOWN, UNSUPPORTED, AMBIGUOUS }
    public enum MeasurementMode { SOURCE_ONLY, SELECTED_BUILD }
    public enum View { SOURCE_DECLARATIONS, EFFECTIVE_LOOKUP }
    public enum SourceRole { TARGET, SUPPORT, DECLARED_NONCOMPILED, UNKNOWN }
    public enum Generation { GENERATED, NOT_GENERATED, UNKNOWN }
    public enum Access { PUBLIC, PROTECTED, PRIVATE, NOT_APPLICABLE, UNKNOWN }
    public enum Virtuality { VIRTUAL, NON_VIRTUAL, NOT_APPLICABLE, UNKNOWN }
    public enum TypeMeaning { COMPILER_TYPE, SOURCE_ANNOTATION, PARAMETER_NAME_CONVENTION, UNRESOLVED_SPELLING }
    public enum LookupMechanism { DECLARED, JAVA_INHERITANCE, PYTHON_C3, CPP_SUBOBJECT }

    /** Ordered evidence: a KNOWN empty sequence differs from unavailable evidence. */
    public record Evidence<T>(Knowledge knowledge, List<T> values, String reason) {
        public Evidence {
            knowledge = Objects.requireNonNull(knowledge, "knowledge");
            values = List.copyOf(Objects.requireNonNull(values, "values"));
            if (knowledge == Knowledge.KNOWN) {
                if (reason != null && !reason.isEmpty()) {
                    throw new IllegalArgumentException("known evidence cannot carry an unavailability reason");
                }
                reason = "";
            } else {
                if (!values.isEmpty()) throw new IllegalArgumentException("unavailable evidence cannot carry values");
                reason = text(reason, "unavailability reason");
            }
        }

        public static <T> Evidence<T> known(List<T> values) {
            return new Evidence<>(Knowledge.KNOWN, values, "");
        }

        public static <T> Evidence<T> unavailable(Knowledge knowledge, String reason) {
            if (knowledge == Knowledge.KNOWN) throw new IllegalArgumentException("use known for known evidence");
            return new Evidence<>(knowledge, List.of(), reason);
        }
    }

    /** Declaration IDs are contextual identities; physical origins live in source spans. */
    public record Domain(String contextId, Language language, MeasurementMode mode, View view,
                         String inputIdentitySha256, String provenance,
                         List<String> classifierIds, List<String> memberKeys) {
        public Domain {
            contextId = text(contextId, "contextId");
            language = Objects.requireNonNull(language, "language");
            if (language != Language.JAVA && language != Language.PYTHON && language != Language.CPP) {
                throw new IllegalArgumentException("native domain requires a source language");
            }
            mode = Objects.requireNonNull(mode, "mode");
            view = Objects.requireNonNull(view, "view");
            inputIdentitySha256 = CanonicalObservationValue.sha256(inputIdentitySha256, "input identity");
            provenance = text(provenance, "domain provenance");
            classifierIds = ids(classifierIds, "cls_", "classifierIds");
            memberKeys = ids(memberKeys, "mem_", "memberKeys");
        }
    }

    /** Physical compiler/source spelling, including absolute dependency paths when applicable. */
    public record SourceSpan(String path, int startLine, int endLine) {
        public SourceSpan {
            path = text(path, "source path");
            if (path.indexOf('\0') >= 0) throw new IllegalArgumentException("NUL in source path");
            if (startLine < 1 || endLine < startLine) throw new IllegalArgumentException("invalid source span");
        }
    }

    public record RoleReceipt(String contextId, String path, String sha256, SourceRole role,
                              Generation generation, String provenance) {
        public RoleReceipt {
            contextId = text(contextId, "contextId");
            path = text(path, "input path");
            sha256 = CanonicalObservationValue.sha256(sha256, "input digest");
            role = Objects.requireNonNull(role, "role");
            generation = Objects.requireNonNull(generation, "generation");
            provenance = text(provenance, "role provenance or unknown reason");
        }
    }

    /** Source metadata is separate from the value actually expanded by the compiler. */
    public record Input(String path, String sha256, Evidence<Long> modificationEpochSeconds) {
        public Input {
            path = text(path, "input path");
            sha256 = CanonicalObservationValue.sha256(sha256, "input digest");
            modificationEpochSeconds = scalar(modificationEpochSeconds, "source modification epoch");
        }
    }

    public record MacroInput(String builtinName, String expandedLiteral, SourceSpan origin,
                             Evidence<Long> sourceModificationEpochSeconds) {
        public MacroInput {
            if (!Set.of("__DATE__", "__TIME__", "__TIMESTAMP__").contains(builtinName)) {
                throw new IllegalArgumentException("unsupported clock/file-time builtin name");
            }
            expandedLiteral = text(expandedLiteral, "actual expanded literal");
            origin = Objects.requireNonNull(origin, "origin");
            sourceModificationEpochSeconds = scalar(sourceModificationEpochSeconds, "source modification epoch");
        }
    }

    /** Semantic compiler argv retains order, duplicates and original spelling.
     * Adapter-owned output transport routes are excluded, not compiler inputs.
     * The adapter separately verifies the full executed transport argv.
     * Inventory completeness is explicit. */
    public record Invocation(String contextId, String executable, String workingDirectory,
                             List<String> arguments, String toolIdentitySha256,
                             Evidence<Input> consumedInputs, Evidence<MacroInput> macroInputs) {
        public Invocation {
            contextId = text(contextId, "contextId");
            executable = text(executable, "executable");
            workingDirectory = text(workingDirectory, "workingDirectory");
            arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
            for (String argument : arguments) {
                if (argument.indexOf('\0') >= 0) throw new IllegalArgumentException("NUL in argument");
            }
            toolIdentitySha256 = CanonicalObservationValue.sha256(toolIdentitySha256, "tool identity");
            consumedInputs = Objects.requireNonNull(consumedInputs, "consumedInputs");
            macroInputs = Objects.requireNonNull(macroInputs, "macroInputs");
            Set<String> paths = new HashSet<>();
            for (Input input : consumedInputs.values()) {
                if (!paths.add(input.path())) throw new IllegalArgumentException("duplicate consumed input path");
            }
        }
    }

    public record DirectBase(String sourceSpelling, Evidence<String> targetClassifierId,
                             Access access, Virtuality virtuality) {
        public DirectBase {
            sourceSpelling = text(sourceSpelling, "base source spelling");
            targetClassifierId = scalar(targetClassifierId, "base target");
            targetClassifierId.values().forEach(id -> CanonicalObservationValue.technicalId(id, "cls_", "base target"));
            access = Objects.requireNonNull(access, "access");
            virtuality = Objects.requireNonNull(virtuality, "virtuality");
        }
    }

    public record Bases(String contextId, String classifierId, Evidence<DirectBase> directBases) {
        public Bases {
            contextId = text(contextId, "contextId");
            classifierId = CanonicalObservationValue.technicalId(classifierId, "cls_", "classifierId");
            directBases = Objects.requireNonNull(directBases, "directBases");
        }
    }

    public record TypeToken(String spelling, TypeMeaning meaning) {
        public TypeToken {
            spelling = text(spelling, "type spelling");
            meaning = Objects.requireNonNull(meaning, "meaning");
        }
    }

    public record Parameter(String spelling, TypeMeaning meaning) {
        public Parameter {
            spelling = text(spelling, "parameter spelling");
            meaning = Objects.requireNonNull(meaning, "meaning");
        }
    }

    public record Signature(String contextId, String memberKey, Evidence<Parameter> parameters,
                            Evidence<String> receiverQualifiers, Evidence<TypeToken> returnType) {
        public Signature {
            contextId = text(contextId, "contextId");
            memberKey = CanonicalObservationValue.technicalId(memberKey, "mem_", "memberKey");
            parameters = Objects.requireNonNull(parameters, "parameters");
            receiverQualifiers = Objects.requireNonNull(receiverQualifiers, "receiverQualifiers");
            receiverQualifiers.values().forEach(value -> text(value, "receiver qualifier"));
            returnType = scalar(returnType, "return type");
        }
    }

    /** Native lookup and overrides retain their mechanism and ordered path, separate from P0 derivation. */
    public record LookupTarget(String declarationKey, LookupMechanism mechanism, List<String> classifierPath,
                               Access access, String subobjectIdentity, Evidence<String> overriddenMemberKeys) {
        public LookupTarget {
            declarationKey = CanonicalObservationValue.technicalId(declarationKey, "mem_", "declarationKey");
            mechanism = Objects.requireNonNull(mechanism, "mechanism");
            classifierPath = List.copyOf(Objects.requireNonNull(classifierPath, "classifierPath"));
            classifierPath.forEach(id -> CanonicalObservationValue.technicalId(id, "cls_", "lookup classifier"));
            access = Objects.requireNonNull(access, "access");
            // Only C++ subobject lookup admits a subobject identity, and requires it.
            if (mechanism == LookupMechanism.CPP_SUBOBJECT) {
                subobjectIdentity = text(subobjectIdentity, "subobjectIdentity");
            } else if (subobjectIdentity != null && !subobjectIdentity.isEmpty()) {
                throw new IllegalArgumentException("subobject identity requires C++ subobject lookup");
            } else subobjectIdentity = "";
            overriddenMemberKeys = Objects.requireNonNull(overriddenMemberKeys, "overriddenMemberKeys");
            overriddenMemberKeys.values().forEach(id -> CanonicalObservationValue.technicalId(id, "mem_", "override target"));
        }
    }

    public record Lookup(String contextId, String ownerClassifierId, String lookupName, Evidence<LookupTarget> targets) {
        public Lookup {
            contextId = text(contextId, "contextId");
            ownerClassifierId = CanonicalObservationValue.technicalId(ownerClassifierId, "cls_", "lookup owner");
            lookupName = text(lookupName, "lookup name");
            targets = Objects.requireNonNull(targets, "targets");
        }
    }

    public record BodyBinding(String contextId, String ownerClassifierId, String memberKey,
                              Evidence<SourceSpan> bodySpan, Evidence<String> implementationTargetKeys) {
        public BodyBinding {
            contextId = text(contextId, "contextId");
            ownerClassifierId = CanonicalObservationValue.technicalId(ownerClassifierId, "cls_", "body owner");
            memberKey = CanonicalObservationValue.technicalId(memberKey, "mem_", "body member");
            bodySpan = scalar(bodySpan, "body span");
            implementationTargetKeys = Objects.requireNonNull(implementationTargetKeys, "implementationTargetKeys");
            implementationTargetKeys.values().forEach(id -> CanonicalObservationValue.technicalId(id, "mem_", "implementation target"));
        }
    }

    /** A validated native bundle; no ability to certify a projected EvidenceKind. */
    public record Bundle(List<Domain> domains, List<RoleReceipt> roles, List<Invocation> invocations,
                         List<Bases> bases, List<Signature> signatures, List<Lookup> lookups,
                         List<BodyBinding> bodyBindings) {
        public Bundle {
            domains = List.copyOf(domains);
            roles = List.copyOf(roles);
            invocations = List.copyOf(invocations);
            bases = List.copyOf(bases);
            signatures = List.copyOf(signatures);
            lookups = List.copyOf(lookups);
            bodyBindings = List.copyOf(bodyBindings);
            Map<String, DomainIndex> byContext = new HashMap<>();
            Set<String> contextualIds = new HashSet<>();
            for (Domain domain : domains) {
                if (byContext.put(domain.contextId(), new DomainIndex(
                        Set.copyOf(domain.classifierIds()), Set.copyOf(domain.memberKeys()))) != null) {
                    throw new IllegalArgumentException("duplicate domain");
                }
                for (String id : domain.classifierIds()) unique(contextualIds, id, "classifier identity reused across contexts");
                for (String id : domain.memberKeys()) unique(contextualIds, id, "member identity reused across contexts");
            }
            Set<List<String>> occurrences = new HashSet<>();
            for (RoleReceipt role : roles) {
                domain(byContext, role.contextId());
                unique(occurrences, List.of("role", role.contextId(), role.path()), "duplicate role receipt");
            }
            for (Invocation invocation : invocations) {
                domain(byContext, invocation.contextId());
                unique(occurrences, List.of("invocation", invocation.contextId()), "duplicate invocation receipt");
            }
            for (Bases entry : bases) {
                DomainIndex domain = domain(byContext, entry.contextId());
                require(domain.classifierIds(), entry.classifierId(), "base owner");
                unique(occurrences, List.of("bases", entry.contextId(), entry.classifierId()), "duplicate bases receipt");
                entry.directBases().values().forEach(base -> base.targetClassifierId().values()
                        .forEach(id -> require(domain.classifierIds(), id, "base target")));
            }
            for (Signature signature : signatures) {
                DomainIndex domain = domain(byContext, signature.contextId());
                require(domain.memberKeys(), signature.memberKey(), "signature member");
                unique(occurrences, List.of("signature", signature.contextId(), signature.memberKey()), "duplicate signature receipt");
            }
            for (Lookup lookup : lookups) {
                DomainIndex domain = domain(byContext, lookup.contextId());
                require(domain.classifierIds(), lookup.ownerClassifierId(), "lookup owner");
                unique(occurrences, List.of("lookup", lookup.contextId(), lookup.ownerClassifierId(), lookup.lookupName()), "duplicate lookup receipt");
                for (LookupTarget target : lookup.targets().values()) {
                    require(domain.memberKeys(), target.declarationKey(), "lookup declaration");
                    target.classifierPath().forEach(id -> require(domain.classifierIds(), id, "lookup path"));
                    target.overriddenMemberKeys().values().forEach(id -> require(domain.memberKeys(), id, "override target"));
                }
            }
            for (BodyBinding binding : bodyBindings) {
                DomainIndex domain = domain(byContext, binding.contextId());
                require(domain.classifierIds(), binding.ownerClassifierId(), "body owner");
                require(domain.memberKeys(), binding.memberKey(), "body member");
                unique(occurrences, List.of("body", binding.contextId(), binding.memberKey()), "duplicate body receipt");
                binding.implementationTargetKeys().values().forEach(id -> require(domain.memberKeys(), id, "implementation target"));
            }
        }

        public static Bundle empty() {
            return new Bundle(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    private static String text(String value, String name) {
        return CanonicalObservationValue.text(value, name);
    }

    private static <T> Evidence<T> scalar(Evidence<T> evidence, String name) {
        Objects.requireNonNull(evidence, name);
        if (evidence.knowledge() == Knowledge.KNOWN && evidence.values().size() != 1) {
            throw new IllegalArgumentException(name + " requires exactly one value when known");
        }
        return evidence;
    }

    private static List<String> ids(List<String> values, String prefix, String name) {
        List<String> result = List.copyOf(Objects.requireNonNull(values, name));
        Set<String> unique = new HashSet<>();
        for (String value : result) {
            CanonicalObservationValue.technicalId(value, prefix, name);
            unique(unique, value, "duplicate " + name);
        }
        return result.stream().sorted().toList();
    }

    private record DomainIndex(Set<String> classifierIds, Set<String> memberKeys) { }

    private static DomainIndex domain(Map<String, DomainIndex> domains, String id) {
        DomainIndex domain = domains.get(id);
        if (domain == null) throw new IllegalArgumentException("unknown native context: " + id);
        return domain;
    }

    private static void require(Set<String> ids, String id, String role) {
        if (!ids.contains(id)) throw new IllegalArgumentException(role + " is outside its native domain: " + id);
    }

    private static <T> void unique(Set<T> keys, T key, String message) {
        if (!keys.add(key)) throw new IllegalArgumentException(message);
    }
}
