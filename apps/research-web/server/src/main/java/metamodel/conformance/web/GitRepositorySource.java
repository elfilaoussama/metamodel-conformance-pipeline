package metamodel.conformance.web;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Acquisition reads Git objects, never checkout filters, hooks, archives or author builds. */
@Service
public final class GitRepositorySource {
    private final OwnedProcesses processes;
    private final String executable;
    private final long maxAcquisitionBytes;
    private final JsonMapper json = JsonMapper.builder().build();
    private static final long MAX_TREE_BYTES = 64L * 1024 * 1024;
    record Entry(String mode, String object, long size, String path) { }
    public GitRepositorySource(OwnedProcesses processes, Environment env) {
        this.processes = processes;
        executable = env.getProperty("workbench.git", "git");
        long mib = env.getProperty("workbench.ingest-max-mib", Long.class, 256L);
        if (mib < 1 || mib > 4096) throw new IllegalArgumentException("ingest-max-mib must be 1..4096");
        maxAcquisitionBytes = mib * 1024 * 1024;
    }
    public void acquire(RepositoryRequest request, Path run, AtomicBoolean cancel) throws Exception {
        long started = System.nanoTime(), deadline = started + TimeUnit.SECONDS.toNanos(120);
        if (Files.getFileStore(run).getUsableSpace() < maxAcquisitionBytes + 128L * 1024 * 1024)
            throw new IOException("insufficient free disk for the configured acquisition budget plus source retention");
        Path acquisition = Files.createDirectory(run.resolve("acquisition"));
        Path config = Files.createFile(acquisition.resolve("empty-config"));
        Path template = Files.createDirectory(acquisition.resolve("empty-template"));
        Path repository = acquisition.resolve("repository.git");
        stage(run, acquisition, config, cancel, deadline, "git-version", null, "--version");
        stage(run, acquisition, config, cancel, deadline, "git-init", null, "init", "--bare", "--template=" + template, repository.toString());
        stage(run, acquisition, config, cancel, deadline, "git-fetch", null, "--git-dir=" + repository,
                "fetch", "--depth=1", "--no-tags", "--no-recurse-submodules", request.url() + ".git", request.revision());
        Path pin = stage(run, acquisition, config, cancel, deadline, "git-pin", null, "--git-dir=" + repository,
                "rev-parse", "--verify", "FETCH_HEAD^{commit}");
        String commit = Files.readString(pin).strip();
        if (!commit.matches("[0-9a-f]{40}")) throw new IOException("Git did not resolve exactly one supported commit ID");
        Map<String,Object> receipt = new LinkedHashMap<>();
        receipt.put("format", 1); receipt.put("state", "PINNED"); receipt.put("url", request.url());
        receipt.put("requestedRevision", request.revision()); receipt.put("resolvedCommit", commit);
        receipt.put("sourceSubdirectory", request.subdirectory()); receipt.put("acquiredAt", Instant.now().toString());
        receipt.put("gitVersion", Files.readString(run.resolve("git-version.log")).strip());
        receipt.put("sourceMode", "tracked Git blobs; no author build, dependency resolution, LFS or submodule expansion");
        if (Files.isRegularFile(run.resolve("discovery.json"))) receipt.put("discoveryReceiptSha256", Workspace.sha256(run.resolve("discovery.json")));
        writeReceipt(run, receipt);
        Path tree = stage(run, acquisition, config, cancel, deadline, "git-tree", null, "--git-dir=" + repository,
                "ls-tree", "-r", "-l", "-z", "--full-tree", commit);
        List<Entry> entries = entries(Files.readAllBytes(tree), request.subdirectory());
        Path input = acquisition.resolve("objects.txt");
        Files.writeString(input, entries.stream().map(e -> e.object() + "\n").collect(java.util.stream.Collectors.joining()),
                StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
        Path blobs = stage(run, acquisition, config, cancel, deadline, "git-blobs", input, "--git-dir=" + repository, "cat-file", "--batch");
        List<Map<String,Object>> files = materialize(entries, blobs, run.resolve("source"), cancel);
        receipt.put("state", "RETAINED"); receipt.put("files", files); receipt.put("fileCount", files.size());
        receipt.put("sourceBytes", entries.stream().mapToLong(Entry::size).sum());
        receipt.put("acquisitionSeconds", (System.nanoTime() - started) / 1e9);
        writeReceipt(run, receipt);
    }
    private void writeReceipt(Path run, Map<String,Object> receipt) throws IOException {
        Path temporary = run.resolve("acquisition-receipt.tmp");
        Files.writeString(temporary, json.writerWithDefaultPrettyPrinter().writeValueAsString(receipt));
        Files.move(temporary, run.resolve("acquisition.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    /** Reuse is permitted only for an exact, complete retained file inventory. */
    public void verifySnapshot(Path source, Path receipt) throws IOException {
        Workspace.requireRegular(receipt, Workspace.MAX_ARTIFACT);
        var inventory = json.readTree(Files.readString(receipt));
        if (!inventory.path("state").asText().equals("RETAINED")) throw new IOException("repository acquisition is not fully retained");
        Map<String,String> expected = new HashMap<>();
        for (var file : inventory.path("files")) {
            String path = file.path("path").asText(), hash = file.path("sha256").asText();
            RepositoryRequest.validatePath(path);
            if (!hash.matches("[0-9a-f]{64}") || expected.putIfAbsent(path, hash) != null) throw new IOException("invalid acquisition inventory");
        }
        if (expected.isEmpty() || expected.size() != inventory.path("fileCount").asInt()) throw new IOException("incomplete acquisition inventory");
        int count = 0;
        try (var paths = Files.walk(source)) {
            for (var iterator = paths.iterator(); iterator.hasNext();) {
                Path file = iterator.next(); Workspace.rejectLinks(file);
                if (Files.isDirectory(file)) continue;
                Workspace.requireRegular(file, Workspace.MAX_SOURCE); count++;
                String relative = source.relativize(file).toString().replace('\\', '/');
                if (!Workspace.sha256(file).equals(expected.get(relative))) throw new IOException("retained source differs from acquisition receipt: " + relative);
            }
        }
        if (count != expected.size()) throw new IOException("retained source is missing files from acquisition receipt");
    }
    private Path stage(Path run, Path acquisition, Path config, AtomicBoolean cancel, long deadline,
                       String name, Path input, String... arguments) throws Exception {
        long remaining = TimeUnit.NANOSECONDS.toSeconds(deadline - System.nanoTime());
        if (remaining < 1) throw new TimeoutException("repository acquisition exceeded 120 seconds");
        Path output = run.resolve(name + ".log");
        List<String> command = new ArrayList<>(List.of(executable, "-c", "credential.helper=", "-c", "http.followRedirects=false",
                "-c", "protocol.allow=never", "-c", "protocol.https.allow=always", "-c", "gc.auto=0"));
        if (System.getProperty("os.name").startsWith("Windows")) command.addAll(List.of("-c", "http.sslBackend=schannel"));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).redirectOutput(output.toFile())
                .redirectError(run.resolve(name + "-error.log").toFile());
        // Do not inherit Git configuration, credentials, object stores or command/trace injection variables.
        builder.environment().keySet().removeIf(key -> key.toUpperCase(Locale.ROOT).startsWith("GIT_"));
        builder.environment().putAll(Map.of("GIT_TERMINAL_PROMPT", "0", "GCM_INTERACTIVE", "Never",
                "GIT_CONFIG_NOSYSTEM", "1", "GIT_CONFIG_GLOBAL", config.toString()));
        if (input != null) builder.redirectInput(input.toFile());
        OwnedProcesses.Exit exit = processes.run(builder, remaining, cancel, () -> {
            long bytes;
            try (var paths = Files.walk(acquisition)) {
                bytes = paths.filter(Files::isRegularFile).mapToLong(p -> {
                    try { return Files.size(p); } catch (IOException ignored) { return 0; }
                }).sum();
            }
            if (bytes > maxAcquisitionBytes || (Files.exists(output) && Files.size(output) > MAX_TREE_BYTES + 1024 * 1024)
                    || Files.getFileStore(run).getUsableSpace() < 128L * 1024 * 1024)
                throw new IOException("repository acquisition exceeded an operational disk/output budget; partial attempt retained");
        });
        if (exit.cancelled()) throw new CancellationException("repository acquisition cancelled; owned Git process stopped");
        if (exit.timedOut()) throw new TimeoutException("repository acquisition exceeded 120 seconds");
        if (exit.code() != 0) throw new IOException(name + " failed (exit " + exit.code() + "); inspect its retained error log");
        return output;
    }
    static List<Entry> entries(byte[] tree, String subdirectory) throws IOException {
        List<Entry> entries = new ArrayList<>(); Set<String> names = new HashSet<>(); long total = 0;
        String prefix = subdirectory.isEmpty() ? "" : subdirectory + "/";
        String decoded = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(tree)).toString();
        for (String line : decoded.split("\u0000")) {
            int tab = line.indexOf('\t');
            if (tab < 0) throw new IOException("invalid Git tree row");
            String path = line.substring(tab + 1);
            if (!path.startsWith(prefix)) continue;
            String relative = path.substring(prefix.length()); RepositoryRequest.validatePath(relative);
            if (!names.add(relative.toLowerCase(Locale.ROOT))) throw new IOException("case-colliding Git paths cannot be retained portably");
            String[] fields = line.substring(0, tab).strip().split(" +");
            if (fields.length != 4 || !Set.of("100644", "100755").contains(fields[0]) || !fields[1].equals("blob") || !fields[2].matches("[0-9a-f]{40}"))
                throw new IOException("selected source contains a symlink, submodule or unsupported Git entry: " + path);
            long size = Long.parseLong(fields[3]);
            if (size < 0 || size > Workspace.MAX_SOURCE) throw new IOException("tracked file exceeds source retention budget: " + path);
            total += size;
            if (entries.size() >= 2000 || total > MAX_TREE_BYTES) throw new IOException("selected source exceeds 2000 files / 64 MiB operational budget");
            entries.add(new Entry(fields[0], fields[2], size, relative));
        }
        if (entries.isEmpty()) throw new IOException("no tracked files in the selected source subdirectory");
        return entries;
    }
    static List<Map<String,Object>> materialize(List<Entry> entries, Path blobs, Path destination, AtomicBoolean cancel) throws Exception {
        Files.createDirectory(destination); List<Map<String,Object>> retained = new ArrayList<>();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(blobs))) {
            for (Entry entry : entries) {
                if (cancel.get()) throw new CancellationException("repository retention cancelled");
                String header = line(in);
                if (!header.equals(entry.object() + " blob " + entry.size())) throw new IOException("Git blob batch does not match the pinned tree");
                byte[] bytes = in.readNBytes((int) entry.size());
                if (bytes.length != entry.size() || in.read() != '\n') throw new IOException("truncated Git blob batch");
                MessageDigest digest = MessageDigest.getInstance("SHA-1");
                digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII)); digest.update(bytes);
                if (!HexFormat.of().formatHex(digest.digest()).equals(entry.object())) throw new IOException("Git blob identity mismatch");
                if (new String(bytes, 0, Math.min(bytes.length, 200), StandardCharsets.US_ASCII).startsWith("version https://git-lfs.github.com/spec/v1"))
                    throw new IOException("selected source contains an unresolved Git LFS pointer: " + entry.path());
                Path target = destination.resolve(entry.path()).normalize();
                if (!target.startsWith(destination)) throw new IOException("invalid retained source path");
                Files.createDirectories(target.getParent()); Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
                retained.add(Map.of("path", entry.path(), "gitMode", entry.mode(), "blob", entry.object(), "size", entry.size(), "sha256", Workspace.sha256(target)));
            }
            if (in.read() != -1) throw new IOException("unexpected data after Git blob batch");
        }
        return retained;
    }
    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(); int value;
        while ((value = in.read()) != '\n') {
            if (value < 0 || buffer.size() > 150) throw new IOException("invalid Git blob header");
            buffer.write(value);
        }
        return buffer.toString(StandardCharsets.US_ASCII);
    }
}
