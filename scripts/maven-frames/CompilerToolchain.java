package org.metamodel.mavenframes;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import static org.metamodel.mavenframes.CompilerFrames.invoke;

/** Observed selected JDK backing files, not proof of complete loaded-image closure. */
final class CompilerToolchain {
    private static final long MAX_FILE_BYTES=512L*1024*1024;
    private final Path home;
    private final Map<String,Object> descriptor;

    private CompilerToolchain(Path home,Map<String,Object> descriptor) {
        this.home=home;this.descriptor=Collections.unmodifiableMap(descriptor);
    }

    static CompilerToolchain capture(Object configuration)throws Exception {
        Object forkValue=invoke(configuration,"isFork");
        if(!(forkValue instanceof Boolean))throw new IllegalStateException("observed compiler execution mode required");
        boolean fork=(Boolean)forkValue;
        Object executableValue=invoke(configuration,"getExecutable");
        Path executable,home;
        if(fork) {
            if(!(executableValue instanceof String text) || text.isBlank())throw new IllegalStateException("selected fork compiler executable required");
            executable=regular(Path.of(text));
            if(!executable.getFileName().toString().equals("javac") || executable.getParent()==null || executable.getParent().getParent()==null)
                throw new IllegalStateException("qualified Linux JDK javac layout required");
            home=executable.getParent().getParent();
        } else {
            if(executableValue!=null && !executableValue.toString().isBlank())throw new IllegalStateException("in-process compiler cannot declare another executable");
            home=Path.of(System.getProperty("java.home")).toAbsolutePath().normalize();
            executable=regular(home.resolve("bin/java"));
        }
        if(!home.toRealPath().equals(home))throw new IllegalStateException("aliased compiler home unqualified");
        Path release=regular(home.resolve("release"));
        if(Files.size(release)>1024*1024)throw new IllegalStateException("JDK release metadata bound");
        byte[] bytes=Files.readAllBytes(release);Properties properties=new Properties();
        properties.load(new ByteArrayInputStream(bytes));
        String version=properties.getProperty("JAVA_VERSION");
        if(version==null)throw new IllegalStateException("selected JDK version unavailable");
        if(version.startsWith("\"") && version.endsWith("\""))version=version.substring(1,version.length()-1);
        int feature;
        if(version.startsWith("1.8."))feature=8;
        else if(version.matches("(?:11|17|25)(?:[.+_-][0-9A-Za-z.+_-]+)?"))feature=Integer.parseInt(version.split("[.+_-]",2)[0]);
        else throw new IllegalStateException("selected native compiler family is unqualified: "+version);
        List<String> names=feature==8?List.of("bin/java","bin/javac","release","lib/tools.jar","jre/lib/rt.jar")
                :List.of("bin/java","bin/javac","release","lib/modules","lib/ct.sym");
        Map<String,Object> files=new TreeMap<>();for(String name:names)files.put(name,artifact(home.resolve(name)));
        var result=new LinkedHashMap<String,Object>();
        result.put("format","observed-javac-toolchain-v1");result.put("mode",fork?"FORKED_JAVAC":"IN_PROCESS_JAVAC");
        result.put("feature",feature);result.put("javaVersion",version);result.put("javaHome",home.toString());
        result.put("runtimeHome",(feature==8?home.resolve("jre"):home).toString());
        result.put("executable",executable.toString());result.put("files",Collections.unmodifiableMap(files));
        result.put("completeLoadedImageCertified",false);
        var captured=new CompilerToolchain(home,result);captured.verifyUnchanged();return captured;
    }

    Map<String,Object> descriptor(){return descriptor;}

    void verifyUnchanged()throws Exception {
        @SuppressWarnings("unchecked") Map<String,Object> files=(Map<String,Object>)descriptor.get("files");
        for(var entry:files.entrySet())if(!artifact(home.resolve(entry.getKey())).equals(entry.getValue()))
            throw new IllegalStateException("selected compiler backing input changed: "+entry.getKey());
    }

    private static Path regular(Path path)throws Exception {
        if(!path.isAbsolute() || !path.normalize().equals(path) || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)
                || !path.toRealPath().equals(path))throw new IllegalStateException("regular unaliased absolute compiler input required: "+path);
        return path;
    }

    private static Map<String,Object> artifact(Path file)throws Exception {
        regular(file);var before=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(before.size()>MAX_FILE_BYTES)throw new IllegalStateException("compiler backing-file bound");
        var digest=MessageDigest.getInstance("SHA-256");long size=0;
        try(InputStream stream=Files.newInputStream(file)) {
            byte[] block=new byte[8192];int count;
            while((count=stream.read(block))!=-1) {
                size+=count;if(size>MAX_FILE_BYTES)throw new IllegalStateException("growing compiler backing input");
                digest.update(block,0,count);
            }
        }
        var after=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(size!=before.size() || before.size()!=after.size() || !Objects.equals(before.fileKey(),after.fileKey())
                || !before.lastModifiedTime().equals(after.lastModifiedTime()) || !before.creationTime().equals(after.creationTime()))
            throw new IllegalStateException("compiler backing input changed while hashing");
        return Map.of("sha256",HexFormat.of().formatHex(digest.digest()),"bytes",size);
    }
}
