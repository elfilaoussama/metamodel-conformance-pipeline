import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import metamodel.conformance.pipeline.PipelineVersion;
import metamodel.conformance.pipeline.capsule.VerificationCapsule;
import metamodel.conformance.pipeline.emf.ObservationXmiReader;
import metamodel.conformance.pipeline.invariant.InvariantProfile;
import metamodel.conformance.pipeline.alloy.ExactAlloyEncoder;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.util.Hashing;
import java.nio.file.*;
import java.util.*;

/** Runs only with the pinned engine's classpath. No Alloy solve is performed. */
public final class ArtifactInspector {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("result directory and fresh view file required");
        Path root = Path.of(args[0]).toRealPath();
        var json = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        Path capsulePath = bounded(root, "verification-capsule.json");
        var capsule = json.readValue(capsulePath.toFile(), VerificationCapsule.class);
        if (!VerificationCapsule.FORMAT_VERSION.equals(capsule.formatVersion())
                || !PipelineVersion.TOOL_ID.equals(capsule.toolId()) || !PipelineVersion.VERSION.equals(capsule.toolVersion()))
            throw new IllegalArgumentException("unsupported capsule/engine identity");
        var profile = InvariantProfile.load();
        if (!profile.identity().equals(capsule.invariantProfile())) throw new IllegalArgumentException("profile identity mismatch");
        if (capsule.alloyExecution() == null || !capsule.alloyExecution().supported()) throw new IllegalArgumentException("unsupported execution profile");
        Path xmi = bounded(root, capsule.observationPath());
        Path alloy = bounded(root, capsule.alloyPath());
        if (!Hashing.sha256(xmi).equals(capsule.observationSha256()) || !Hashing.sha256(alloy).equals(capsule.alloySha256()))
            throw new IllegalArgumentException("artifact hash mismatch");
        Observation observation = new ObservationXmiReader().read(xmi);
        if (!observation.schemaVersion().equals(capsule.schemaVersion()) || !observation.adapterId().equals(capsule.adapterId())
                || !observation.adapterVersion().equals(capsule.adapterVersion())
                || !Hashing.sourceSetDigest(observation.units()).equals(capsule.sourceSetSha256())
                || !observation.diagnostics().equals(capsule.observationDiagnostics()))
            throw new IllegalArgumentException("observation metadata mismatch");
        if (!new ExactAlloyEncoder(profile).encode(observation).equals(Files.readString(alloy)))
            throw new IllegalArgumentException("exact Alloy encoding mismatch");
        Set<String> decisions = new HashSet<>(), keys = new HashSet<>();
        observation.classifiers().forEach(c -> keys.add(c.id()));
        observation.members().forEach(m -> keys.add(m.technicalKey()));
        observation.methodBodies().forEach(b -> keys.add(b.technicalKey()));
        observation.implementationBindings().forEach(b -> keys.add(b.technicalKey()));
        for (var decision : capsule.decisions()) {
            var rule = profile.registry().require(decision.invariantId());
            if (!decisions.add(rule.id())) throw new IllegalArgumentException("duplicate rule decision");
            for (var witness : decision.witnesses())
                if (witness.technicalKeys().size() != rule.witnessArity() || !keys.containsAll(witness.technicalKeys()))
                    throw new IllegalArgumentException("invalid witness references");
        }
        if (decisions.size() != profile.registry().all().size()) throw new IllegalArgumentException("incomplete rule decisions");
        var view = Map.of("inspection", "INTEGRITY_CHECKED_NOT_REPLAYED", "capsule", capsule,
                "observation", observation, "rules", profile.registry().all());
        byte[] bytes = json.writeValueAsBytes(view);
        if (bytes.length > 32 * 1024 * 1024) throw new IllegalArgumentException("inspection view exceeds slice budget");
        Files.write(Path.of(args[1]), bytes, StandardOpenOption.CREATE_NEW);
    }
    private static Path bounded(Path root, String relative) throws Exception {
        if (relative == null || Path.of(relative).isAbsolute() || Path.of(relative).normalize().startsWith(".."))
            throw new IllegalArgumentException("invalid artifact reference");
        Path path = root.resolve(relative).normalize();
        for (Path p = path; !p.equals(root); p = p.getParent())
            if (Files.isSymbolicLink(p)) throw new IllegalArgumentException("artifact symlinks forbidden");
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 32L * 1024 * 1024)
            throw new IllegalArgumentException("missing or oversized inspection artifact");
        return path;
    }
}
