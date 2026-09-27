package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.adapter.ObservationException;
import metamodel.conformance.pipeline.model.PlatformEvidence;
import metamodel.conformance.pipeline.util.Hashing;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/** Fingerprints the platform actually selected by one reconstructed javac context. */
final class JavaPlatformProvenance {
    private final PlatformEvidence evidence;
    private final String unitPath;

    private JavaPlatformProvenance(PlatformEvidence evidence, String unitPath) {
        this.evidence = evidence;
        this.unitPath = unitPath;
    }

    static JavaPlatformProvenance capture(JavaCompilationContext context) throws ObservationException {
        try {
            Path home = selectedPlatformHome(context);
            Path release = home.resolve("release");
            if (!Files.isRegularFile(release, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(release)) {
                throw new ObservationException("selected Java platform has no regular release metadata: " + home);
            }
            String releaseDigest = Hashing.sha256(release);
            Path ctSym = home.resolve("lib").resolve("ct.sym");
            String ctSymDigest = "";
            if (context.compilerSemantics().releaseLevel() != null) {
                if (!Files.isRegularFile(ctSym, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(ctSym)) {
                    throw new ObservationException("selected Java platform cannot fingerprint --release symbols: " + ctSym);
                }
                ctSymDigest = Hashing.sha256(ctSym);
            }
            JavaCompilerSemantics semantics = context.compilerSemantics();
            // The platform home and ct.sym file are shared across --release levels;
            // the effective platform API surface is selected by --release and
            // preview. Fingerprint them together so contexts that observe
            // different platform signatures never share one platform identity.
            String platformSemantics = "release=" + level(semantics.releaseLevel())
                    + ";preview=" + semantics.previewEnabled();
            String digest = Hashing.sha256(
                    releaseDigest + "\0" + ctSymDigest + "\0" + platformSemantics);
            String configuredIdentity = semantics.platformIdentity().isBlank()
                    ? "compiler-default" : semantics.platformIdentity();
            String compilerIdentity = System.getProperty("java.vendor", "unknown") + " "
                    + System.getProperty("java.version", "unknown");
            String semanticsText = "source=" + level(semantics.sourceLevel())
                    + ";target=" + level(semantics.targetLevel())
                    + ";release=" + level(semantics.releaseLevel())
                    + ";preview=" + semantics.previewEnabled();
            PlatformEvidence evidence = new PlatformEvidence(
                    context.id(), compilerIdentity, semanticsText, configuredIdentity, digest, List.of());
            return new JavaPlatformProvenance(evidence, "platform/" + digest + "/release");
        } catch (ObservationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ObservationException("failed to fingerprint selected Java platform: " + exception.getMessage(), exception);
        }
    }

    PlatformEvidence evidence() {
        return evidence;
    }

    String unitPath() {
        return unitPath;
    }

    String sha256() {
        return evidence.platformContentSha256();
    }

    PlatformEvidence withTerminalTypes(List<String> terminalTypes) {
        return evidence.withTerminalTypeNames(terminalTypes);
    }

    private static Path selectedPlatformHome(JavaCompilationContext context) throws ObservationException {
        try {
            List<Path> explicit = context.resolutionEntries(JavaResolutionPathRole.PLATFORM_PATH);
            if (explicit.isEmpty()) return Path.of(System.getProperty("java.home")).toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (explicit.size() != 1) throw new ObservationException("multiple platform paths are not representable by javac");
            Path selected = explicit.get(0).toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (Files.isRegularFile(selected.resolve("release"), LinkOption.NOFOLLOW_LINKS)) return selected;
            Path parent = selected.getParent();
            if (parent != null && Files.isRegularFile(parent.resolve("release"), LinkOption.NOFOLLOW_LINKS)) return parent;
            throw new ObservationException("explicit platform path has no fingerprintable Java home: " + selected);
        } catch (ObservationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ObservationException("failed to resolve selected Java platform", exception);
        }
    }

    private static String level(Integer value) {
        return value == null ? "" : Integer.toString(value);
    }
}
