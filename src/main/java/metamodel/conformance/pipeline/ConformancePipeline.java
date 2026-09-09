package metamodel.conformance.pipeline;

import metamodel.conformance.pipeline.adapter.ObservationException;
import metamodel.conformance.pipeline.adapter.SourceObserver;
import metamodel.conformance.pipeline.alloy.ExactAlloyEncoder;
import metamodel.conformance.pipeline.alloy.AlloyInvariantEvaluator;
import metamodel.conformance.pipeline.capsule.CapsuleWriter;
import metamodel.conformance.pipeline.capsule.VerificationCapsule;
import metamodel.conformance.pipeline.decision.Decision;
import metamodel.conformance.pipeline.emf.ObservationXmiReader;
import metamodel.conformance.pipeline.emf.ObservationXmiWriter;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.util.ArtifactLimits;
import metamodel.conformance.pipeline.util.AtomicFiles;
import metamodel.conformance.pipeline.util.Hashing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;
import java.util.List;

public final class ConformancePipeline {
    private static final String OBSERVATION_FILE = "observation.xmi";
    private static final String ALLOY_FILE = "repository-instance.als";
    private static final String CAPSULE_FILE = "verification-capsule.json";

    private final SourceObserver observer;
    private final ExactAlloyEncoder encoder;
    private final AlloyInvariantEvaluator runner;

    public ConformancePipeline(SourceObserver observer) {
        this(observer, new ExactAlloyEncoder(), new AlloyInvariantEvaluator());
    }

    ConformancePipeline(SourceObserver observer, ExactAlloyEncoder encoder, AlloyInvariantEvaluator runner) {
        this.observer = observer;
        this.encoder = encoder;
        this.runner = runner;
    }

    public PipelineResult analyze(Path sourceRoot, Path outputDirectory, Set<String> externalParents)
            throws ObservationException, IOException {
        Path output = prepareOutputDirectory(outputDirectory);
        Observation observed = observer.observe(sourceRoot, externalParents);
        Path capsulePath = output.resolve(CAPSULE_FILE);
        Files.deleteIfExists(capsulePath);

        Path observationPath = output.resolve(OBSERVATION_FILE);
        new ObservationXmiWriter().write(observed, observationPath);
        Observation observation = new ObservationXmiReader().read(observationPath);

        String alloy = encoder.encode(observation);
        Path alloyPath = output.resolve(ALLOY_FILE);
        AtomicFiles.writeUtf8(
                alloyPath, alloy, ArtifactLimits.MAX_ALLOY_BYTES, "Alloy artifact");

        List<Decision> decisions = runner.evaluateAll(observation, alloy);
        VerificationCapsule capsule = new VerificationCapsule(
                "6",
                PipelineVersion.TOOL_ID,
                PipelineVersion.VERSION,
                observation.schemaVersion(),
                observation.adapterId(),
                observation.adapterVersion(),
                Hashing.sourceSetDigest(observation.units()),
                OBSERVATION_FILE,
                Hashing.sha256(observationPath),
                ALLOY_FILE,
                Hashing.sha256(alloyPath),
                runner.executionConfig(),
                observation.diagnostics(),
                decisions);
        new CapsuleWriter().write(capsule, capsulePath);
        return new PipelineResult(observation, decisions, observationPath, alloyPath, capsulePath);
    }

    private static Path prepareOutputDirectory(Path outputDirectory) throws IOException {
        if (outputDirectory == null) {
            throw new IOException("output directory is required");
        }
        Path output = outputDirectory.toAbsolutePath().normalize();
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(output)) {
            throw new IOException("symbolic-link output directories are forbidden");
        }
        Files.createDirectories(output);
        if (!Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("output path is not a directory: " + output);
        }
        return output.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }
}
