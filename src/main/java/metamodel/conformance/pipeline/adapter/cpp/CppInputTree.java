package metamodel.conformance.pipeline.adapter.cpp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import metamodel.conformance.pipeline.util.Hashing;

/** Byte and directory inventory of the materialized configured source tree.
 * This does not attest file timestamps or the compiler's entire filesystem. */
final class CppInputTree {
    private CppInputTree() { }
    static String digest(Path root) throws IOException {
        StringBuilder inventory = new StringBuilder();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> !p.equals(root))
                    .sorted(Comparator.comparing(p -> java.util.HexFormat.of().formatHex(
                            root.relativize(p).toString().replace('\\','/').getBytes(java.nio.charset.StandardCharsets.UTF_8)))).toList()) {
                String relative=root.relativize(path).toString().replace('\\','/');
                if (relative.indexOf('\n')>=0 || relative.indexOf('\r')>=0 || relative.indexOf('\0')>=0
                        || Files.isSymbolicLink(path)) {
                    throw new IOException("unsupported configured input path: "+relative);
                }
                if (Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)) {
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
