package metamodel.conformance.web;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import java.nio.channels.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.stream.Stream;

/** All filesystem authority is established by the local operator at startup. */
@Component
public final class Workspace {
    public final Path root, inputRoot, engine, bridgeSource, java, javac, bridgeClasses;
    public final String engineSha256;
    public final int computeWorkers;
    public final FileChannel leaseChannel;
    public final FileLock lease;
    public static final long MAX_ARTIFACT = 32L * 1024 * 1024;
    public static final long MAX_SOURCE = 2L * 1024 * 1024;
    public Workspace(Environment env) throws IOException {
        computeWorkers = env.getProperty("workbench.compute-workers", Integer.class, 1);
        if (computeWorkers < 1) throw new IllegalArgumentException("workbench.compute-workers must be positive");
        if (Runtime.version().feature() != 17)
            throw new IllegalArgumentException("this execution slice requires a full JDK 17 host");
        if (!"127.0.0.1".equals(env.getProperty("server.address", "127.0.0.1")))
            throw new IllegalArgumentException("this local slice must bind to 127.0.0.1");
        root = Path.of(env.getRequiredProperty("workbench.workspace")).toAbsolutePath().normalize();
        Files.createDirectories(root);
        rejectLinks(root);
        inputRoot = Path.of(env.getRequiredProperty("workbench.input-root")).toRealPath();
        if (root.startsWith(inputRoot) || inputRoot.startsWith(root))
            throw new IllegalArgumentException("workspace and trusted input root must be disjoint");
        engine = Path.of(env.getRequiredProperty("workbench.engine")).toRealPath();
        requireRegular(engine, 128L * 1024 * 1024);
        engineSha256 = sha256(engine);
        bridgeSource = Path.of(env.getRequiredProperty("workbench.bridge-source")).toRealPath();
        requireRegular(bridgeSource, MAX_SOURCE);
        Path javaHome = Path.of(System.getProperty("java.home"));
        String suffix = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
        java = javaHome.resolve("bin/java" + suffix);
        javac = javaHome.resolve("bin/javac" + suffix);
        if (!Files.isRegularFile(javac)) throw new IllegalArgumentException("a full JDK is required");
        leaseChannel = FileChannel.open(root.resolve("server.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        lease = leaseChannel.tryLock();
        if (lease == null) throw new IllegalStateException("workspace is owned by another server");
        Files.createDirectories(root.resolve("runs"));
        bridgeClasses = root.resolve("bridge-" + sha256(bridgeSource).substring(0, 16) + "-" + engineSha256.substring(0, 16));
        Files.createDirectories(bridgeClasses);
    }
    public Path input(String raw) throws IOException {
        Path requested = Path.of(raw).toAbsolutePath().normalize();
        rejectLinks(requested);
        Path path = requested.toRealPath();
        if (!path.startsWith(inputRoot) || !Files.isDirectory(path))
            throw new IllegalArgumentException("directory must be inside the configured trusted input root");
        return path;
    }
    public Path run(String id) {
        if (id == null || !id.matches("[0-9a-f-]{36}")) throw new IllegalArgumentException("invalid run ID");
        return root.resolve("runs").resolve(id);
    }
    public void snapshot(Path source, Path destination) throws IOException {
        if (Files.getFileStore(root).getUsableSpace() < 128L * 1024 * 1024)
            throw new IllegalStateException("at least 128 MiB free space is required before source retention");
        Files.createDirectory(destination);
        long total = 0; int count = 0;
        try (Stream<Path> paths = Files.walk(source)) {
            for (Iterator<Path> iterator = paths.iterator(); iterator.hasNext();) {
                Path path = iterator.next();
                if (Files.isSymbolicLink(path)) throw new IllegalArgumentException("source symlinks are unsupported");
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else {
                    requireRegular(path, MAX_SOURCE);
                    total += Files.size(path);
                    if (++count > 2000 || total > 64L * 1024 * 1024)
                        throw new IllegalArgumentException("this slice supports at most 2000 source files / 64 MiB");
                    Files.copy(path, target);
                }
            }
        }
    }
    public static void requireRegular(Path path, long maximum) throws IOException {
        rejectLinks(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > maximum)
            throw new IllegalArgumentException("not a supported bounded regular file: " + path.getFileName());
    }
    public static void rejectLinks(Path path) throws IOException {
        for (Path p = path.toAbsolutePath(); p != null; p = p.getParent())
            if (Files.isSymbolicLink(p)) throw new IllegalArgumentException("symbolic-link coordinates are unsupported");
    }
    public static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536]; int n;
                while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
