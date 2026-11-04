package metamodel.conformance.pipeline.invariant;

import metamodel.conformance.pipeline.util.Hashing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Immutable rule inputs shared by encoding, evaluation, worker JVMs and replay. */
public final class InvariantProfile {
    public static final int MAX_REGISTRY_BYTES = 1024 * 1024;
    public static final int MAX_RULE_BYTES = 16 * 1024 * 1024;
    public static final String EXPECTED_IDENTITY_PROPERTY = "metamodel.conformance.expectedInvariantProfile";
    private static final String REGISTRY = "invariants/registry.json";
    private static final String RULES = "alloy/invariants.als";

    private final byte[] registryBytes;
    private final byte[] ruleBytes;
    private final InvariantRegistry registry;
    private final String alloyRules;
    private final Identity identity;

    public record Identity(String registrySha256, String rulesSha256) {
        public Identity {
            if (registrySha256 == null || !registrySha256.matches("[0-9a-f]{64}")
                    || rulesSha256 == null || !rulesSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invariant profile requires SHA-256 resource identities");
            }
        }

        public String token() { return registrySha256 + ":" + rulesSha256; }
    }

    private InvariantProfile(byte[] registryBytes, byte[] ruleBytes) {
        requireSize(registryBytes, MAX_REGISTRY_BYTES, "invariant registry");
        requireSize(ruleBytes, MAX_RULE_BYTES, "Alloy rules");
        this.registryBytes = registryBytes.clone();
        this.ruleBytes = ruleBytes.clone();
        this.registry = InvariantRegistry.parse(this.registryBytes);
        try {
            this.alloyRules = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(this.ruleBytes)).toString();
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException("Alloy rules must be UTF-8", malformed);
        }
        this.identity = new Identity(Hashing.sha256(this.registryBytes), Hashing.sha256(this.ruleBytes));
    }

    public static InvariantProfile capture(byte[] registryBytes, byte[] ruleBytes) {
        return new InvariantProfile(registryBytes, ruleBytes);
    }

    public static InvariantProfile load() {
        var profile = capture(readResource(REGISTRY, MAX_REGISTRY_BYTES),
                readResource(RULES, MAX_RULE_BYTES));
        String expected = System.getProperty(EXPECTED_IDENTITY_PROPERTY);
        if (expected != null && !profile.identity.token().equals(expected)) {
            throw new IllegalStateException("invariant profile identity mismatch in worker");
        }
        return profile;
    }

    public InvariantRegistry registry() { return registry; }
    public String alloyRules() { return alloyRules; }
    public Identity identity() { return identity; }
    public byte[] registryBytes() { return registryBytes.clone(); }
    public byte[] ruleBytes() { return ruleBytes.clone(); }

    /** Stage only captured bytes in a fresh owned directory for child classloaders. */
    public void writeResources(Path directory) throws IOException {
        Files.createDirectory(directory);
        Files.createDirectory(directory.resolve("invariants"));
        Files.createDirectory(directory.resolve("alloy"));
        Files.write(directory.resolve(REGISTRY), registryBytes, StandardOpenOption.CREATE_NEW);
        Files.write(directory.resolve(RULES), ruleBytes, StandardOpenOption.CREATE_NEW);
    }

    static byte[] readResource(String name, int limit) {
        try (InputStream input = InvariantProfile.class.getResourceAsStream("/" + name)) {
            if (input == null) throw new IllegalStateException("missing invariant profile resource: " + name);
            byte[] bytes = input.readNBytes(limit + 1);
            requireSize(bytes, limit, name);
            return bytes;
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read invariant profile resource: " + name, failure);
        }
    }

    private static void requireSize(byte[] bytes, int limit, String name) {
        if (bytes == null || bytes.length == 0 || bytes.length > limit) {
            throw new IllegalArgumentException(name + " is empty or exceeds the resource size limit");
        }
    }
}
