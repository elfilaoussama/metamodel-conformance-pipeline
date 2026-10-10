package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.model.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.ArrayList;
import java.util.HashSet;

/** Qualifies a decoded frame before aggregation; physical paths remain physical origins. */
final class JavaContextualObservation {
    private JavaContextualObservation() { }

    record DomainIdentity(String sha256, String provenance,
                          List<String> classifierIds, List<String> memberKeys) { }

    static Observation aggregate(Map<String, Observation> frames) {
        return aggregate(frames, Map.of());
    }

    static Observation aggregate(Map<String, Observation> frames, Map<String, DomainIdentity> identities) {
        return aggregate(frames, identities, true);
    }

    static Observation mergeQualifiedFrames(Map<String, Observation> frames) {
        return aggregate(frames, Map.of(), false);
    }

    private static Observation aggregate(Map<String, Observation> frames,
            Map<String, DomainIdentity> identities, boolean qualifyFrames) {
        if (frames.isEmpty()) throw new IllegalArgumentException("no Java observation frames");
        if (!identities.isEmpty() && !identities.keySet().equals(frames.keySet())) {
            throw new IllegalArgumentException("Java domain identities must cover exactly the decoded frames");
        }
        var entries = new TreeMap<>(frames).entrySet().stream().toList();
        List<Observation> qualified = entries.stream()
                .map(entry -> qualifyFrames ? qualify(entry.getKey(), entry.getValue()) : entry.getValue()).toList();
        Observation first = qualified.get(0);
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
        var reportedInputs = new ArrayList<NativeEvidence.ReportedInputs>();
        for (int frameIndex = 0; frameIndex < qualified.size(); frameIndex++) {
            Observation frame = qualified.get(frameIndex);
            String contextId = entries.get(frameIndex).getKey();
            domains.addAll(frame.nativeEvidence().domains());
            roles.addAll(frame.nativeEvidence().roles());
            invocations.addAll(frame.nativeEvidence().invocations());
            bases.addAll(frame.nativeEvidence().bases());
            signatures.addAll(frame.nativeEvidence().signatures());
            lookups.addAll(frame.nativeEvidence().lookups());
            bodyBindings.addAll(frame.nativeEvidence().bodyBindings());
            reportedInputs.addAll(frame.nativeEvidence().reportedInputs());
            if (!identities.isEmpty()) {
                DomainIdentity identity = identities.get(contextId);
                domains.add(new NativeEvidence.Domain(contextId, Language.JAVA,
                        NativeEvidence.MeasurementMode.SELECTED_BUILD, NativeEvidence.View.EFFECTIVE_LOOKUP,
                        identity.sha256(), identity.provenance(),
                        keys(contextId, identity.classifierIds()), keys(contextId, identity.memberKeys())));
            }
            if (!first.schemaVersion().equals(frame.schemaVersion())
                    || !first.adapterId().equals(frame.adapterId())
                    || !first.adapterVersion().equals(frame.adapterVersion())
                    || !first.externalParents().equals(frame.externalParents())) {
                throw new IllegalArgumentException("incompatible Java observation frame metadata");
            }
            evidence.retainAll(frame.completeEvidence());
            for (SourceUnit unit : frame.units()) {
                SourceUnit previous = units.putIfAbsent(unit.path(), unit);
                if (previous != null && !previous.equals(unit)) {
                    throw new IllegalArgumentException("physical source changed across frames: " + unit.path());
                }
            }
            classifiers.addAll(frame.classifiers());
            members.addAll(frame.members());
            bodies.addAll(frame.methodBodies());
            bindings.addAll(frame.implementationBindings());
            unresolved.addAll(frame.unresolvedParents());
            diagnostics.addAll(frame.diagnostics());
            platform.addAll(frame.platformEvidence());
        }
        return new Observation(first.schemaVersion(), first.adapterId(), first.adapterVersion(),
                first.externalParents(), evidence, List.copyOf(units.values()), classifiers, members,
                bodies, bindings, unresolved, diagnostics.stream().distinct().toList(),
                platform.stream().distinct().toList(),
                new NativeEvidence.Bundle(domains, roles, invocations, bases, signatures, lookups, bodyBindings, reportedInputs));
    }

    static Observation qualify(String contextId, Observation frame) {
        if (contextId == null || contextId.isBlank()) {
            throw new IllegalArgumentException("context identity must not be blank");
        }
        // Native receipts already refer to contextual identities. Do not silently discard them.
        if (!frame.nativeEvidence().equals(NativeEvidence.Bundle.empty())) {
            throw new IllegalArgumentException("qualify Java frame before native receipt population");
        }
        return new Observation(frame.schemaVersion(), frame.adapterId(), frame.adapterVersion(),
                frame.externalParents(), frame.completeEvidence(), frame.units(),
                frame.classifiers().stream().map(c -> new ClassifierObservation(
                        key(contextId, c.id()), c.qualifiedName(), c.packageName(), c.kind(),
                        c.sourcePath(), c.startLine(), c.endLine(), keys(contextId, c.parentIds()),
                        keys(contextId, c.declaredMemberKeys()), keys(contextId, c.inheritedMemberKeys()),
                        c.abstraction())).toList(),
                frame.members().stream().map(m -> new MemberObservation(
                        key(contextId, m.technicalKey()), m.observedIdentifier(), m.kind(),
                        m.inheritability(), m.visibility(), m.memberName(), m.sourcePath(),
                        m.startLine(), m.endLine(), m.parameterTypes(), m.abstraction(), m.scope(),
                        m.returnType(), keys(contextId, m.overriddenMemberKeys()))).toList(),
                frame.methodBodies().stream().map(b -> new MethodBodyObservation(
                        key(contextId, b.technicalKey()), b.sourcePath(), b.startLine(), b.endLine())).toList(),
                frame.implementationBindings().stream().map(b -> new ImplementationBindingObservation(
                        key(contextId, b.technicalKey()), key(contextId, b.implementerClassifierId()),
                        key(contextId, b.targetMemberKey()), key(contextId, b.bodyKey()))).toList(),
                frame.unresolvedParents().stream().map(p -> new UnresolvedParent(
                        key(contextId, p.ownerId()), p.targetName(), p.sourcePath(), p.line())).toList(),
                frame.diagnostics(), frame.platformEvidence());
    }

    private static List<String> keys(String contextId, List<String> values) {
        return values.stream().map(value -> key(contextId, value)).toList();
    }

    static String key(String contextId, String physicalId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("java-contextual-declaration-v1\0".getBytes(StandardCharsets.UTF_8));
            digest.update(contextId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(physicalId.getBytes(StandardCharsets.UTF_8));
            return physicalId.substring(0, physicalId.indexOf('_') + 1)
                    + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
