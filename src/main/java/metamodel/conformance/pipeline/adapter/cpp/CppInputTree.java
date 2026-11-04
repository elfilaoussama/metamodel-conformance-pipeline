package metamodel.conformance.pipeline.adapter.cpp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import metamodel.conformance.pipeline.util.Hashing;

/** Byte, directory and bounded relative-file-alias inventory of the configured tree.
 * This does not attest file timestamps or the compiler's entire filesystem. */
final class CppInputTree {
    private CppInputTree() { }
    static String digest(Path root) throws IOException {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("configured input root is not a regular directory");
        }
        Path physicalRoot = root.toRealPath();
        Path lexicalRoot = root.toAbsolutePath().normalize();
        StringBuilder inventory = new StringBuilder();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> !p.equals(root))
                    .sorted(Comparator.comparing(p -> java.util.HexFormat.of().formatHex(
                            root.relativize(p).toString().replace('\\','/').getBytes(java.nio.charset.StandardCharsets.UTF_8)))).toList()) {
                String relative=root.relativize(path).toString().replace('\\','/');
                if (relative.indexOf('\n')>=0 || relative.indexOf('\r')>=0 || relative.indexOf('\0')>=0) {
                    throw new IOException("unsupported configured input path: "+relative);
                }
                if (Files.isSymbolicLink(path)) {
                    Path literalPath = Files.readSymbolicLink(path);
                    String literal = literalPath.toString();
                    if (literalPath.isAbsolute() || literal.indexOf('\n')>=0
                            || literal.indexOf('\r')>=0 || literal.indexOf('\0')>=0
                            || !path.toAbsolutePath().getParent().resolve(literalPath).normalize().startsWith(lexicalRoot)) {
                        throw new IOException("unsupported configured input alias: "+relative);
                    }
                    Path target = path.toRealPath();
                    if (!target.startsWith(physicalRoot) || !Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("configured input alias is not an internal regular file: "+relative);
                    }
                    String digest = Hashing.sha256(target);
                    if (!Files.readSymbolicLink(path).equals(literalPath) || !path.toRealPath().equals(target)) {
                        throw new IOException("configured input alias changed: "+relative);
                    }
                    inventory.append("L\0").append(relative).append('\0').append(literal).append('\0')
                            .append(physicalRoot.relativize(target).toString().replace('\\','/'))
                            .append('\0').append(digest).append('\n');
                } else if (Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)) {
                    inventory.append("D\0").append(relative).append('\n');
                } else if (Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) {
                    inventory.append("F\0").append(relative).append('\0').append(Hashing.sha256(path)).append('\n');
                } else {
                    throw new IOException("configured input is not a regular file or directory: "+relative);
                }
            }
        }
        return Hashing.sha256(inventory.toString());
    }
}
