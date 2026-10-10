package metamodel.conformance.pipeline.model;

import java.util.List;

/** Versioned provenance for platform symbols observed through one Java compilation context. */
public record PlatformEvidence(
        String contextId,
        String compilerIdentity,
        String compilerSemantics,
        String platformIdentity,
        String platformContentSha256,
        List<String> terminalTypeNames) {
    public PlatformEvidence {
        contextId = text(contextId, "platform context id");
        compilerIdentity = text(compilerIdentity, "platform compiler identity");
        compilerSemantics = text(compilerSemantics, "platform compiler semantics");
        platformIdentity = text(platformIdentity, "platform identity");
        platformContentSha256 = text(platformContentSha256, "platform content digest");
        terminalTypeNames = terminalTypeNames == null ? List.of() : terminalTypeNames.stream()
                .map(value -> text(value, "platform terminal type"))
                .distinct().sorted().toList();
    }

    public PlatformEvidence withTerminalTypeNames(List<String> terminals) {
        return new PlatformEvidence(contextId, compilerIdentity, compilerSemantics, platformIdentity,
                platformContentSha256, terminals);
    }

    private static String text(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " must not be blank");
        return value.trim();
    }
}
