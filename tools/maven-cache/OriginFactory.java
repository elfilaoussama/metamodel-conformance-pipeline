package org.metamodel.cache;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.metadata.Metadata;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.internal.impl.LocalPathPrefixComposer;
import org.eclipse.aether.internal.impl.LocalPathPrefixComposerFactorySupport;

/** Pinned Resolver SPI only: retains the enhanced manager, its tracking and update policy. */
@javax.inject.Named
@javax.inject.Singleton
@org.eclipse.sisu.Priority(100)
public final class OriginFactory extends LocalPathPrefixComposerFactorySupport {
    public static final String PROFILE = "maven-3.9.16-resolver-1.9.27-origin-v1";

    private static void requireVersion(String resource, String expected) {
        Properties properties = new Properties();
        try (InputStream stream = OriginFactory.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("Missing runtime identity: " + resource);
            properties.load(stream);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot establish runtime identity", error);
        }
        if (!expected.equals(properties.getProperty("version")))
            throw new IllegalStateException("Unsupported runtime for " + PROFILE);
    }

    public static void checkRuntime() {
        if (Runtime.version().feature() != 17)
            throw new IllegalStateException("This cache profile requires JDK17");
        requireVersion("/META-INF/maven/org.apache.maven/maven-core/pom.properties", "3.9.16");
        requireVersion("/META-INF/maven/org.apache.maven.resolver/maven-resolver-impl/pom.properties", "1.9.27");
    }

    public static void main(String[] args) {
        checkRuntime();
        System.out.println(PROFILE);
    }

    @Override
    public LocalPathPrefixComposer createComposer(RepositorySystemSession session) {
        checkRuntime();
        String attempt = Objects.toString(session.getConfigProperties().get("conformance.cache.attempt"), "");
        if (!attempt.matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("A valid conformance.cache.attempt is required");
        String receipt = Objects.toString(session.getConfigProperties().get("conformance.cache.receipt"), "");
        if (receipt.isEmpty()) throw new IllegalArgumentException("Cache selection receipt is required");
        try {
            Files.writeString(Path.of(receipt), "{\"profile\":\"" + PROFILE + "\",\"attempt\":\"" + attempt + "\"}\n");
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot record cache selection", error);
        }
        return new LocalPathPrefixComposerSupport(
                true, "installed/" + attempt, true, "cached", true, true, false, "releases", "snapshots") {
            private String originKey(RemoteRepository repository) {
                try {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    // A file URL inside a container can address different mounted checkouts.
                    // Such artifacts and metadata are deliberately attempt-scoped.
                    String fileScope = repository.getUrl().regionMatches(true, 0, "file:", 0, 5) ? attempt : "";
                    for (String value : List.of(repository.getId(), repository.getUrl(), repository.getContentType(), fileScope)) {
                        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                        digest.update(bytes);
                    }
                    return HexFormat.of().formatHex(digest.digest());
                } catch (NoSuchAlgorithmException error) {
                    throw new IllegalStateException(error);
                }
            }

            @Override
            public String getPathPrefixForRemoteArtifact(Artifact artifact, RemoteRepository repository) {
                return "cached/" + originKey(repository) + "/" + (artifact.isSnapshot() ? "snapshots" : "releases");
            }

            @Override
            public String getPathPrefixForRemoteMetadata(Metadata metadata, RemoteRepository repository) {
                return "cached/" + originKey(repository) + "/" + (isSnapshot(metadata) ? "snapshots" : "releases");
            }
        };
    }
}
