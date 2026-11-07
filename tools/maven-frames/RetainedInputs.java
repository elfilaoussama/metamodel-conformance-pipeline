package org.metamodel.mavenframes;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.metamodel.mavenframes.CompilerFrames.invoke;

/** Before-call resolution snapshots. They do not certify every compiler read. */
final class RetainedInputs {
    private long bytes;
    private int nodes;
    private final Path root;
    private final Map<String,Map<String,Object>> indexed=new LinkedHashMap<>();

    RetainedInputs(Path root,Object configuration)throws Exception {
        this.root=root;Files.createDirectory(root);Files.createDirectory(root.resolve("resolution"));Files.createDirectory(root.resolve("sources"));
        var manifest=new LinkedHashMap<String,Object>();manifest.put("format","native-compiler-retained-inputs-v1");manifest.put("snapshotPoint","BEFORE_COMPILER_CALL");manifest.put("consumptionCertified",false);manifest.put("nativeDefaultCharset",java.nio.charset.Charset.defaultCharset().name());manifest.put("sourceEncoding",invoke(configuration,"getSourceEncoding"));
        manifest.put("classpath",capture(invoke(configuration,"getClasspathEntries")));
        Object processors=invoke(configuration,"getProcessorPathEntries");manifest.put("processorPath",processors==null?null:capture(processors));
        byte[] data=(CompilerFrames.json(manifest)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if(data.length>8*1024*1024)throw new IllegalStateException("retained manifest exceeds8MiB policy");
        Files.write(root.resolve("manifest.jsonl"),data,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,StandardOpenOption.SYNC);
    }
    private List<Object> capture(Object values)throws Exception {
        if(!(values instanceof Collection<?> entries))throw new IllegalStateException("resolution path inventory unavailable");
        var result=new ArrayList<Object>();
        for(Object value:entries){
            if(!(value instanceof String text))throw new IllegalStateException("nontextual resolution path");
            Map<String,Object> item=indexed.get(text);
            if(item==null){item=snapshot(text,indexed.size());indexed.put(text,item);}result.add(item);
        }
        return result;
    }
    private Map<String,Object> snapshot(String original,int index)throws Exception {
        Path source=Path.of(original);
        if(!source.isAbsolute() || !source.equals(source.normalize()) || source.getFileName()==null)throw new IllegalStateException("resolution snapshot path must be absolute/canonical");
        if(root.startsWith(source) || source.startsWith(root))throw new IllegalStateException("retention destination overlaps resolution input");
        Path ancestor=source.getRoot();
        for(Path part:source){ancestor=ancestor.resolve(part);if(Files.isSymbolicLink(ancestor))throw new IllegalStateException("symlink resolution inputs require a separate profile");}
        String relative="resolution/entry-"+index+"/"+source.getFileName();Path target=root.resolve(relative);Files.createDirectory(target.getParent());
        var item=new LinkedHashMap<String,Object>();item.put("original",original);item.put("snapshot",relative);var inventory=new ArrayList<Object>();
        if(!Files.exists(source,LinkOption.NOFOLLOW_LINKS)){Files.createDirectory(target);item.put("kind","ABSENT");}
        else if(Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS)){if(++nodes>32768)throw new IllegalStateException("resolution snapshot exceeds32768-node policy");item.put("kind","FILE");inventory.add(copy(source,target,"."));}
        else if(Files.isDirectory(source,LinkOption.NOFOLLOW_LINKS)){
            item.put("kind","DIRECTORY");
            List<Path> paths;try(var walk=Files.walk(source)){paths=walk.limit(32769).sorted().toList();}
            if(paths.size()>32768)throw new IllegalStateException("resolution tree exceeds32768-node policy");
            for(Path path:paths){
                if(++nodes>32768)throw new IllegalStateException("resolution snapshot exceeds32768-node policy");
                String name=path.equals(source)?".":source.relativize(path).toString();Path destination=name.equals(".")?target:target.resolve(name);
                if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)){
                    Files.createDirectory(destination);var directory=new LinkedHashMap<String,Object>();directory.put("path",name);directory.put("kind","DIRECTORY");directory.put("lastModifiedNanos",Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).to(TimeUnit.NANOSECONDS));directory.put("permissions",java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS)));inventory.add(directory);
                }
                else if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))inventory.add(copy(path,destination,name));
                else throw new IllegalStateException("nonregular resolution input");
            }
            for(int i=inventory.size()-1;i>=0;i--){
                @SuppressWarnings("unchecked") var node=(Map<String,Object>)inventory.get(i);
                if(!node.get("kind").equals("DIRECTORY"))continue;String name=(String)node.get("path");Path originalPath=name.equals(".")?source:source.resolve(name),destination=name.equals(".")?target:target.resolve(name);
                if(Files.getLastModifiedTime(originalPath,LinkOption.NOFOLLOW_LINKS).to(TimeUnit.NANOSECONDS)!=(long)node.get("lastModifiedNanos"))throw new IllegalStateException("resolution directory changed while copying");
                Files.setLastModifiedTime(destination,java.nio.file.attribute.FileTime.from((long)node.get("lastModifiedNanos"),TimeUnit.NANOSECONDS));Files.setPosixFilePermissions(destination,java.nio.file.attribute.PosixFilePermissions.fromString((String)node.get("permissions")));
            }
        }else throw new IllegalStateException("unsupported resolution input kind");
        item.put("inventory",inventory);return item;
    }
    private Map<String,Object> copy(Path source,Path target,String relative)throws Exception {
        BasicFileAttributes before=Files.readAttributes(source,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!before.isRegularFile())throw new IllegalStateException("resolution input is no longer regular");
        var digest=MessageDigest.getInstance("SHA-256");long size=0;
        try(InputStream in=Files.newInputStream(source,LinkOption.NOFOLLOW_LINKS);OutputStream out=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)){
            byte[] buffer=new byte[8192];int count;
            while((count=in.read(buffer))!=-1){size+=count;bytes+=count;if(size>64L*1024*1024 || bytes>512L*1024*1024)throw new IllegalStateException("resolution snapshot byte budget exceeded");digest.update(buffer,0,count);out.write(buffer,0,count);}
        }
        BasicFileAttributes after=Files.readAttributes(source,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!after.isRegularFile() || size!=before.size() || after.size()!=before.size() || !before.lastModifiedTime().equals(after.lastModifiedTime()) || !Objects.equals(before.fileKey(),after.fileKey()))throw new IllegalStateException("resolution input changed while copying");
        Files.setLastModifiedTime(target,before.lastModifiedTime());
        var permissions=Files.getPosixFilePermissions(source,LinkOption.NOFOLLOW_LINKS);Files.setPosixFilePermissions(target,permissions);
        var item=new LinkedHashMap<String,Object>();item.put("path",relative);item.put("kind","FILE");item.put("sha256",HexFormat.of().formatHex(digest.digest()));item.put("bytes",size);item.put("lastModifiedNanos",before.lastModifiedTime().to(TimeUnit.NANOSECONDS));item.put("permissions",java.nio.file.attribute.PosixFilePermissions.toString(permissions));return item;
    }
    Map<String,Object> receipt()throws Exception {
        return Map.of("format","native-compiler-retained-inputs-v1","root",root.toString(),"manifest",CompilerFrames.artifact(root.resolve("manifest.jsonl")),"snapshotPoint","BEFORE_COMPILER_CALL","consumptionCertified",false);
    }
}
