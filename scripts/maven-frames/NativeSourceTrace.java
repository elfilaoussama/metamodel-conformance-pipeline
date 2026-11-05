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
import java.util.List;
import java.util.Set;

/** Opt-in native PARSE-unit population; character snapshots are not consumed buffers. */
public final class NativeSourceTrace implements Plugin {
    public String getName() { return "ConformanceSourceTrace"; }

    private static String hex(byte[] bytes) {
        char[] alphabet="0123456789abcdef".toCharArray();
        char[] output=new char[bytes.length*2];
        for(int i=0;i<bytes.length;i++) {
            int value=bytes[i]&255;output[2*i]=alphabet[value>>>4];output[2*i+1]=alphabet[value&15];
        }
        return new String(output);
    }

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

    /** JDK8 has no module encapsulation; retain its effective option values
     * rather than guessing how a Maven release request was translated. */
    private static String legacyOptions(JavacTask task) throws Exception {
        Class<?> contextType=Class.forName("com.sun.tools.javac.util.Context");
        Object context=task.getClass().getMethod("getContext").invoke(task);
        Class<?> optionsType=Class.forName("com.sun.tools.javac.util.Options");
        Object options=optionsType.getMethod("instance",contextType).invoke(null,context);
        java.lang.reflect.Method get=optionsType.getMethod("get",String.class);
        List<String> entries=new ArrayList<String>();
        for(String name:new String[]{"-source","-target","-encoding","-classpath","-sourcepath","-proc:none","-proc:only","-processor","-processorpath"}) {
            String value=(String)get.invoke(options,name);
            entries.add(quote(name)+":"+(value==null?"null":quote(value)));
        }
        return "{"+String.join(",",entries)+"}";
    }

    public void init(JavacTask task, String... args) {
        if ((args.length != 3 && args.length != 4 && args.length != 5 && args.length != 6) || !args[1].matches("[0-9a-f]{32}") || !args[2].matches("[1-9][0-9]*")
                || (args.length>=5 && !args[4].equals("runtime-lifecycle-v1"))) {
            throw new IllegalArgumentException("parser capture requires output-path token, attempt and invocation");
        }
        Path output = java.nio.file.Paths.get(new String(Base64.getUrlDecoder().decode(args[0]), StandardCharsets.UTF_8));
        String attempt = args[1];
        long invocation = Long.parseLong(args[2]);
        Path retained = args.length >= 4 ? java.nio.file.Paths.get(new String(Base64.getUrlDecoder().decode(args[3]), StandardCharsets.UTF_8)) : null;
        boolean runtimeLifecycle=args.length>=5;
        boolean legacy8="1.8".equals(System.getProperty("java.specification.version"));
        if(legacy8 && !runtimeLifecycle)throw new IllegalArgumentException("JDK8 parser capture requires explicit process lifecycle and observed successful fork exit");
        String format = runtimeLifecycle ? "native-javac-source-trace-v3" : retained == null ? "native-javac-source-trace-v1" : "native-javac-source-trace-v2";
        // One javac plugin owns the observer composition. JDK8's plugin loop
        // mutates its pending set during iteration when two plugins are selected.
        if(args.length==6)new org.metamodel.nativeworld.NativeTaskSemanticCapture().init(task,args[5],attempt,args[2]);
        try { Files.createFile(output); }
        catch (Exception problem) { throw new IllegalStateException("fresh parser receipt required", problem); }
        Trees trees = Trees.instance(task);
        class Events implements TaskListener {
            int units;
            long retainedBytes;
            boolean completed;
            private void emit(String row) {
                try {
                    java.nio.ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(row + "\n"));
                    if (encoded.remaining() > 8 * 1024 * 1024) throw new IllegalStateException("parser row exceeds8MiB policy");
                    byte[] bytes = new byte[encoded.remaining()]; encoded.get(bytes);
                    Files.write(output, bytes, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
                }
                catch (Exception problem) { throw new IllegalStateException("parser receipt failed", problem); }
            }
            public void started(TaskEvent event) { }
            synchronized void complete(String phase) {
                if(completed)return;
                emit("{\"format\":" + quote(format) + ",\"phase\":" + quote(phase) + ",\"attempt\":" + quote(attempt)
                        + ",\"invocation\":" + invocation
                        + ",\"parsedUnits\":" + units + ",\"consumptionCertified\":false}");
                completed=true;
            }
            public synchronized void finished(TaskEvent event) {
                if (event.getKind() == TaskEvent.Kind.PARSE) {
                    CompilationUnitTree unit = event.getCompilationUnit();
                    List<String> declarations = new ArrayList<String>();
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
                        observed = hex(MessageDigest.getInstance("SHA-256").digest(characters.getBytes(StandardCharsets.UTF_8)));
                    } catch (Exception problem) { throw new IllegalStateException("source character snapshot failed", problem); }
                    if (units >= 4094) throw new IllegalStateException("parsed-unit count exceeds policy");
                    String raw = "";
                    if (retained != null) {
                        try {
                            Path original = java.nio.file.Paths.get(unit.getSourceFile().toUri());
                            if (original.startsWith(retained)) throw new IllegalStateException("source overlaps retention destination");
                            Path ancestor = original.getRoot();
                            for (Path part : original) { ancestor = ancestor.resolve(part); if (Files.isSymbolicLink(ancestor)) throw new IllegalStateException("symlink source retention unqualified"); }
                            java.nio.file.attribute.BasicFileAttributes before = Files.readAttributes(original, java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                            if (!before.isRegularFile() || before.size() > 64L * 1024 * 1024) throw new IllegalStateException("source retention kind/size unsupported");
                            String relative = "sources/source-" + (units + 1) + ".bin";
                            Path target = retained.resolve(relative);
                            MessageDigest digest = MessageDigest.getInstance("SHA-256"); long size = 0;
                            try (java.io.InputStream input = Files.newInputStream(original, java.nio.file.LinkOption.NOFOLLOW_LINKS); java.io.OutputStream destination = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                                byte[] buffer = new byte[8192]; int n;
                                while ((n = input.read(buffer)) != -1) { size += n; retainedBytes += n; if (size > 64L * 1024 * 1024 || retainedBytes > 512L * 1024 * 1024) throw new IllegalStateException("source retention byte budget exceeded"); digest.update(buffer, 0, n); destination.write(buffer, 0, n); }
                            }
                            java.nio.file.attribute.BasicFileAttributes after = Files.readAttributes(original, java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                            if (!after.isRegularFile() || size != before.size() || after.size() != before.size() || !before.lastModifiedTime().equals(after.lastModifiedTime()) || !java.util.Objects.equals(before.fileKey(), after.fileKey())) throw new IllegalStateException("source changed while retaining");
                            Files.setLastModifiedTime(target, before.lastModifiedTime());
                            Set<java.nio.file.attribute.PosixFilePermission> permissions = Files.getPosixFilePermissions(original, java.nio.file.LinkOption.NOFOLLOW_LINKS); Files.setPosixFilePermissions(target, permissions);
                            raw = ",\"rawSourceSnapshot\":{\"point\":\"POST_PARSE_FILE_SNAPSHOT\",\"path\":" + quote(relative) + ",\"sha256\":" + quote(hex(digest.digest())) + ",\"bytes\":" + size + ",\"lastModifiedNanos\":" + before.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS) + ",\"permissions\":" + quote(java.nio.file.attribute.PosixFilePermissions.toString(permissions)) + "}";
                        } catch (Exception problem) { throw new IllegalStateException("native parsed-source retention failed", problem); }
                    }
                    emit("{\"format\":" + quote(format) + ",\"phase\":\"PARSED_UNIT\",\"attempt\":" + quote(attempt)
                            + ",\"invocation\":" + invocation
                            + ",\"index\":" + (++units) + ",\"uri\":" + quote(unit.getSourceFile().toUri().toString())
                            + ",\"reportedSourceCharSha256\":" + quote(observed)
                            + ",\"declarations\":[" + String.join(",", declarations)
                            + "]" + raw + ",\"consumptionCertified\":false}");
                } else if (event.getKind().name().equals("COMPILATION")) {
                    complete("COMPILATION_END");
                }
            }
        }
        final Events events=new Events();
        task.addTaskListener(events);
        if(legacy8)Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run(){events.complete("PROCESS_SHUTDOWN");}
        },"conformance-parser-process-completion"));
        try {
            String runtime=runtimeLifecycle?",\"javaVersion\":"+quote(System.getProperty("java.version"))
                    +",\"javaHome\":"+quote(System.getProperty("java.home"))
                    +",\"compilerTaskClass\":"+quote(task.getClass().getName())
                    +",\"completionStrategy\":"+quote(legacy8?"PROCESS_SHUTDOWN_WITH_OBSERVED_EXIT_REQUIRED":"TASK_COMPILATION_EVENT")
                    +",\"effectiveOptions\":"+(legacy8?legacyOptions(task):"null"):"";
            String initialized="{\"format\":" + quote(format) + ",\"phase\":\"TASK_INITIALIZED\",\"attempt\":"
                    + quote(attempt) + ",\"invocation\":" + invocation + runtime + ",\"consumptionCertified\":false}\n";
            Files.write(output, initialized.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND, StandardOpenOption.SYNC);
        } catch (Exception problem) { throw new IllegalStateException("parser initialization receipt failed", problem); }
    }
}
