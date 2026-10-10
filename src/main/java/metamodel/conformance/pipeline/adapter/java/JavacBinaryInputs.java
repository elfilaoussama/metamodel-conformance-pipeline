package metamodel.conformance.pipeline.adapter.java;

import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.ForwardingJavaFileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Records the class-file bytes actually read by this javac task, not lookup candidates. */
final class JavacBinaryInputs extends ForwardingJavaFileManager<StandardJavaFileManager> {
    record Read(String binaryName, String moduleName, URI uri, String sha256) { }
    private record Loaded(Read receipt, JavaFileObject original) { }
    private final Map<Read, Loaded> loaded = new LinkedHashMap<>();
    private final Set<String> sourceReads = new java.util.HashSet<>();
    private final Map<Location, Location> moduleLocations = new java.util.IdentityHashMap<>();

    /** Keep client module locations owned by this manager when a release manager is installed. */
    private record ClientModuleLocation(Location original) implements Location {
        @Override public String getName() { return original.getName(); }
        @Override public boolean isOutputLocation() { return original.isOutputLocation(); }
        @Override public boolean isModuleOrientedLocation() { return original.isModuleOrientedLocation(); }
    }
    private Location clientLocation(Location location) {
        return location == null ? null : moduleLocations.computeIfAbsent(location, ClientModuleLocation::new);
    }
    private static Location unwrap(Location location) {
        return location instanceof ClientModuleLocation client ? client.original() : location;
    }

    @Override public boolean hasLocation(Location location) { return super.hasLocation(unwrap(location)); }
    @Override public ClassLoader getClassLoader(Location location) { return super.getClassLoader(unwrap(location)); }
    @Override public <S> java.util.ServiceLoader<S> getServiceLoader(Location location, Class<S> service) throws IOException {
        return super.getServiceLoader(unwrap(location), service);
    }
    @Override public FileObject getFileForInput(Location location, String packageName, String relativeName) throws IOException {
        return super.getFileForInput(unwrap(location), packageName, relativeName);
    }
    @Override public FileObject getFileForOutput(Location location, String packageName, String relativeName, FileObject sibling) throws IOException {
        return super.getFileForOutput(unwrap(location), packageName, relativeName, unwrap(sibling));
    }
    @Override public JavaFileObject getJavaFileForOutput(Location location, String name, JavaFileObject.Kind kind, FileObject sibling) throws IOException {
        return super.getJavaFileForOutput(unwrap(location), name, kind, unwrap(sibling));
    }
    @Override public String inferModuleName(Location location) throws IOException {
        return super.inferModuleName(unwrap(location));
    }
    @Override public Location getLocationForModule(Location location, String module) throws IOException {
        return clientLocation(super.getLocationForModule(unwrap(location), module));
    }
    @Override public Iterable<Set<Location>> listLocationsForModules(Location location) throws IOException {
        List<Set<Location>> result = new ArrayList<>();
        for (Set<Location> locations : super.listLocationsForModules(unwrap(location))) {
            Set<Location> clients = new java.util.LinkedHashSet<>();
            for (Location module : locations) clients.add(clientLocation(module));
            result.add(Set.copyOf(clients));
        }
        return result;
    }

    JavacBinaryInputs(StandardJavaFileManager delegate) { super(delegate); }

    @Override
    public Iterable<JavaFileObject> list(Location location, String packageName,
                                         Set<JavaFileObject.Kind> kinds, boolean recurse) throws IOException {
        List<JavaFileObject> result = new ArrayList<>();
        for (JavaFileObject file : super.list(unwrap(location), packageName, kinds, recurse)) {
            result.add(wrap(unwrap(location), file));
        }
        return result;
    }

    @Override
    public JavaFileObject getJavaFileForInput(Location location, String name,
                                               JavaFileObject.Kind kind) throws IOException {
        return wrap(unwrap(location), super.getJavaFileForInput(unwrap(location), name, kind));
    }

    @Override
    public String inferBinaryName(Location location, JavaFileObject file) {
        return super.inferBinaryName(unwrap(location), unwrap(file));
    }

    @Override
    public boolean isSameFile(FileObject left, FileObject right) {
        return super.isSameFile(unwrap(left), unwrap(right));
    }

    @Override
    public boolean contains(Location location, FileObject file) throws IOException {
        return super.contains(unwrap(location), unwrap(file));
    }

    @Override
    public Location getLocationForModule(Location location, JavaFileObject file) throws IOException {
        return clientLocation(super.getLocationForModule(unwrap(location), unwrap(file)));
    }

    private JavaFileObject wrap(Location location, JavaFileObject file) throws IOException {
        if (file == null || (file.getKind() != JavaFileObject.Kind.CLASS
                && file.getKind() != JavaFileObject.Kind.SOURCE)) return file;
        String binaryName = fileManager.inferBinaryName(location, file);
        if (binaryName == null) return file;
        if (file.getKind() == JavaFileObject.Kind.SOURCE) return new SourceAccess(file, binaryName);
        String moduleName;
        try { moduleName = fileManager.inferModuleName(location); }
        catch (IllegalArgumentException | UnsupportedOperationException failure) { moduleName = ""; }
        return new Captured(file, binaryName, moduleName == null ? "" : moduleName);
    }

    Read origin(TypeElement type, Elements elements) {
        String name = elements.getBinaryName(type).toString();
        var module = elements.getModuleOf(type);
        String moduleName = module == null || module.isUnnamed() ? "" : module.getQualifiedName().toString();
        List<Read> matches = loaded.keySet().stream()
                .filter(read -> read.binaryName().equals(name) && read.moduleName().equals(moduleName))
                .distinct().toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    List<Read> reads() { return List.copyOf(loaded.keySet()); }
    boolean hasReadsFor(String binaryName) {
        return loaded.keySet().stream().anyMatch(read -> read.binaryName().equals(binaryName));
    }
    boolean hasSourceReadsFor(String binaryName) {
        return sourceReads.stream().anyMatch(name -> binaryName.equals(name) || binaryName.startsWith(name + "$"));
    }
    boolean hasSourceReads() { return !sourceReads.isEmpty(); }

    void verifyUnchanged() throws IOException {
        for (Loaded input : loaded.values()) {
            byte[] current;
            try (InputStream stream = input.original().openInputStream()) { current = stream.readAllBytes(); }
            if (!digest(current).equals(input.receipt().sha256())) {
                throw new IOException("compiler class input changed during observation: " + input.receipt().uri());
            }
        }
    }

    private final class Captured extends ForwardingJavaFileObject<JavaFileObject> {
        private final String binaryName;
        private final String moduleName;
        Captured(JavaFileObject file, String binaryName, String moduleName) {
            super(file); this.binaryName = binaryName; this.moduleName = moduleName;
        }
        @Override
        public InputStream openInputStream() throws IOException {
            byte[] bytes;
            try (InputStream stream = fileObject.openInputStream()) { bytes = stream.readAllBytes(); }
            Read read = new Read(binaryName, moduleName, fileObject.toUri(), digest(bytes));
            loaded.putIfAbsent(read, new Loaded(read, fileObject));
            // The decoder consumes exactly the captured bytes. Retain only the receipt afterward.
            return new ByteArrayInputStream(bytes);
        }
        JavaFileObject original() { return fileObject; }
    }

    private final class SourceAccess extends ForwardingJavaFileObject<JavaFileObject> {
        private final String binaryName;
        SourceAccess(JavaFileObject file, String binaryName) { super(file); this.binaryName = binaryName; }
        @Override public CharSequence getCharContent(boolean ignoreErrors) throws IOException {
            sourceReads.add(binaryName); return fileObject.getCharContent(ignoreErrors);
        }
        @Override public InputStream openInputStream() throws IOException {
            sourceReads.add(binaryName); return fileObject.openInputStream();
        }
        @Override public java.io.Reader openReader(boolean ignoreErrors) throws IOException {
            sourceReads.add(binaryName); return fileObject.openReader(ignoreErrors);
        }
        JavaFileObject original() { return fileObject; }
    }

    private static JavaFileObject unwrap(JavaFileObject file) {
        if (file instanceof JavacBinaryInputs.Captured captured) return captured.original();
        return file instanceof JavacBinaryInputs.SourceAccess source ? source.original() : file;
    }
    private static FileObject unwrap(FileObject file) {
        return file instanceof JavaFileObject java ? unwrap(java) : file;
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
