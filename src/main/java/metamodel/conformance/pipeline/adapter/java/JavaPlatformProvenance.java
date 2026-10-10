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
    private final Path ctSymPath;
    private final Integer releaseLevel;
    private java.util.Set<String> signatureNames;

    private JavaPlatformProvenance(PlatformEvidence evidence, String unitPath, Path ctSymPath, Integer releaseLevel) {
        this.evidence = evidence;
        this.unitPath = unitPath;
        this.ctSymPath = ctSymPath;
        this.releaseLevel = releaseLevel;
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
            Path platformImage = home.resolve("lib/modules");
            String platformImageDigest = imageDigest(platformImage);
            Path compilerImage = Path.of(System.getProperty("java.home")).resolve("lib/modules");
            String compilerImageDigest = Files.isSameFile(platformImage, compilerImage)
                    ? platformImageDigest : imageDigest(compilerImage);
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
                    "platform-content-v2\0" + releaseDigest + "\0" + ctSymDigest + "\0"
                            + platformImageDigest + "\0" + compilerImageDigest + "\0" + platformSemantics);
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
            return new JavaPlatformProvenance(evidence, "platform/" + digest + "/content-v2",
                    ctSymDigest.isEmpty() ? null : ctSym.toRealPath(LinkOption.NOFOLLOW_LINKS), semantics.releaseLevel());
        } catch (ObservationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ObservationException("failed to fingerprint selected Java platform: " + exception.getMessage(), exception);
        }
    }

    PlatformEvidence evidence() {
        return evidence;
    }

    private static String imageDigest(Path image) throws Exception {
        if (!Files.isRegularFile(image, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(image)) {
            throw new ObservationException("Java platform/compiler has no regular module image: " + image);
        }
        return Hashing.sha256(image);
    }

    String unitPath() {
        return unitPath;
    }

    String sha256() {
        return evidence.platformContentSha256();
    }

    boolean ownsBinary(java.net.URI uri) {
        if ("jrt".equals(uri.getScheme())) return true;
        Path archive = JavaDependencySymbols.archivePath(uri);
        return ctSymPath != null && archive != null && archive.toAbsolutePath().normalize().equals(ctSymPath);
    }

    /** Release library symbols bypass the supplied manager; positive admitted-image membership is required. */
    boolean suppliesReleaseSymbol(String binaryName, String moduleName, List<String> options) throws java.io.IOException {
        if (releaseLevel == null || ctSymPath == null || options.stream().anyMatch(arg -> arg.startsWith("-Xplugin:"))) return false;
        int releaseOption = options.indexOf("--release");
        if (releaseOption < 0 || releaseOption + 1 >= options.size()
                || !options.get(releaseOption + 1).equals(releaseLevel.toString())) return false;
        if (signatureNames == null) {
            java.util.Set<String> names = new java.util.HashSet<>();
            try (java.util.zip.ZipFile symbols = new java.util.zip.ZipFile(ctSymPath.toFile())) {
                var entries = symbols.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (!name.endsWith(".sig")) continue;
                    int releaseEnd = name.indexOf('/');
                    int moduleEnd = name.indexOf('/', releaseEnd + 1);
                    if (releaseEnd >= 0 && moduleEnd >= 0) {
                        names.add(name.substring(moduleEnd + 1, name.length() - 4).replace('/', '.'));
                    }
                }
            }
            signatureNames = java.util.Set.copyOf(names);
        }
        if (signatureNames.contains(binaryName)) return true;
        // The current release may use the runtime image rather than a .sig entry.
        return releaseLevel == Runtime.version().feature() && !moduleName.isEmpty()
                && java.nio.file.Files.isRegularFile(java.nio.file.FileSystems.getFileSystem(java.net.URI.create("jrt:/"))
                    .getPath("/modules", moduleName, binaryName.replace('.', '/') + ".class"));
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
