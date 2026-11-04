package org.metamodel.mavenframes;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;

/** Opt-in native PARSE-unit population; character snapshots are not consumed buffers. */
public final class NativeSourceTrace implements Plugin {
    public String getName() { return "ConformanceSourceTrace"; }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') result.append('\\').append(c);
            else if (c < 32) result.append(String.format("\\u%04x", (int) c));
            else result.append(c);
        }
        return result.append('"').toString();
    }

    public void init(JavacTask task, String... args) {
        if ((args.length != 3 && args.length != 4) || !args[1].matches("[0-9a-f]{32}") || !args[2].matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("parser capture requires output-path token, attempt and invocation");
        }
        Path output = Path.of(new String(Base64.getUrlDecoder().decode(args[0]), StandardCharsets.UTF_8));
        String attempt = args[1];
        long invocation = Long.parseLong(args[2]);
        Path retained = args.length == 4 ? Path.of(new String(Base64.getUrlDecoder().decode(args[3]), StandardCharsets.UTF_8)) : null;
        String format = retained == null ? "native-javac-source-trace-v1" : "native-javac-source-trace-v2";
        try { Files.createFile(output); }
        catch (Exception problem) { throw new IllegalStateException("fresh parser receipt required", problem); }
        Trees trees = Trees.instance(task);
        task.addTaskListener(new TaskListener() {
            int units;
            long retainedBytes;
            private void emit(String row) {
                try {
                    var encoded = StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(row + "\n"));
                    if (encoded.remaining() > 8 * 1024 * 1024) throw new IllegalStateException("parser row exceeds8MiB policy");
                    byte[] bytes = new byte[encoded.remaining()]; encoded.get(bytes);
                    Files.write(output, bytes, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
                }
                catch (Exception problem) { throw new IllegalStateException("parser receipt failed", problem); }
            }
            public void started(TaskEvent event) { }
            public synchronized void finished(TaskEvent event) {
                if (event.getKind() == TaskEvent.Kind.PARSE) {
                    CompilationUnitTree unit = event.getCompilationUnit();
                    var declarations = new ArrayList<String>();
                    new TreeScanner<Void, Void>() {
                        public Void visitClass(ClassTree tree, Void unused) {
                            declarations.add("{\"name\":" + quote(tree.getSimpleName().toString())
                                    + ",\"start\":" + trees.getSourcePositions().getStartPosition(unit, tree)
                                    + ",\"end\":" + trees.getSourcePositions().getEndPosition(unit, tree) + "}");
                            return super.visitClass(tree, unused);
                        }
                    }.scan(unit, null);
                    String observed;
                    try {
                        String characters = unit.getSourceFile().getCharContent(true).toString();
                        if (characters.length() > 16 * 1024 * 1024) throw new IllegalStateException("source character snapshot exceeds policy");
                        observed = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(characters.getBytes(StandardCharsets.UTF_8)));
                    } catch (Exception problem) { throw new IllegalStateException("source character snapshot failed", problem); }
                    if (units >= 4094) throw new IllegalStateException("parsed-unit count exceeds policy");
                    String raw = "";
                    if (retained != null) {
                        try {
                            Path original = Path.of(unit.getSourceFile().toUri());
                            if (original.startsWith(retained)) throw new IllegalStateException("source overlaps retention destination");
                            Path ancestor = original.getRoot();
                            for (Path part : original) { ancestor = ancestor.resolve(part); if (Files.isSymbolicLink(ancestor)) throw new IllegalStateException("symlink source retention unqualified"); }
                            var before = Files.readAttributes(original, java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                            if (!before.isRegularFile() || before.size() > 64L * 1024 * 1024) throw new IllegalStateException("source retention kind/size unsupported");
                            String relative = "sources/source-" + (units + 1) + ".bin";
                            Path target = retained.resolve(relative);
                            MessageDigest digest = MessageDigest.getInstance("SHA-256"); long size = 0;
                            try (var input = Files.newInputStream(original, java.nio.file.LinkOption.NOFOLLOW_LINKS); var destination = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                                byte[] buffer = new byte[8192]; int n;
                                while ((n = input.read(buffer)) != -1) { size += n; retainedBytes += n; if (size > 64L * 1024 * 1024 || retainedBytes > 512L * 1024 * 1024) throw new IllegalStateException("source retention byte budget exceeded"); digest.update(buffer, 0, n); destination.write(buffer, 0, n); }
                            }
                            var after = Files.readAttributes(original, java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                            if (!after.isRegularFile() || size != before.size() || after.size() != before.size() || !before.lastModifiedTime().equals(after.lastModifiedTime()) || !java.util.Objects.equals(before.fileKey(), after.fileKey())) throw new IllegalStateException("source changed while retaining");
                            Files.setLastModifiedTime(target, before.lastModifiedTime());
                            var permissions = Files.getPosixFilePermissions(original, java.nio.file.LinkOption.NOFOLLOW_LINKS); Files.setPosixFilePermissions(target, permissions);
                            raw = ",\"rawSourceSnapshot\":{\"point\":\"POST_PARSE_FILE_SNAPSHOT\",\"path\":" + quote(relative) + ",\"sha256\":" + quote(HexFormat.of().formatHex(digest.digest())) + ",\"bytes\":" + size + ",\"lastModifiedNanos\":" + before.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS) + ",\"permissions\":" + quote(java.nio.file.attribute.PosixFilePermissions.toString(permissions)) + "}";
                        } catch (Exception problem) { throw new IllegalStateException("native parsed-source retention failed", problem); }
                    }
                    emit("{\"format\":" + quote(format) + ",\"phase\":\"PARSED_UNIT\",\"attempt\":" + quote(attempt)
                            + ",\"invocation\":" + invocation
                            + ",\"index\":" + (++units) + ",\"uri\":" + quote(unit.getSourceFile().toUri().toString())
                            + ",\"reportedSourceCharSha256\":" + quote(observed)
                            + ",\"declarations\":[" + String.join(",", declarations)
                            + "]" + raw + ",\"consumptionCertified\":false}");
                } else if (event.getKind() == TaskEvent.Kind.COMPILATION) {
                    emit("{\"format\":" + quote(format) + ",\"phase\":\"COMPILATION_END\",\"attempt\":" + quote(attempt)
                            + ",\"invocation\":" + invocation
                            + ",\"parsedUnits\":" + units + ",\"consumptionCertified\":false}");
                }
            }
        });
        try {
            Files.writeString(output, "{\"format\":" + quote(format) + ",\"phase\":\"TASK_INITIALIZED\",\"attempt\":"
                    + quote(attempt) + ",\"invocation\":" + invocation + ",\"consumptionCertified\":false}\n",
                    StandardCharsets.UTF_8, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
        } catch (Exception problem) { throw new IllegalStateException("parser initialization receipt failed", problem); }
    }
}
