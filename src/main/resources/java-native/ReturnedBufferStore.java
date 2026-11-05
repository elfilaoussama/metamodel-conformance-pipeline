package org.metamodel.nativeworld;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Retains compiler-facing snapshots; does not infer binary origins or full consumption. */
public final class ReturnedBufferStore {
    private final Path root;
    private final int maximumObjectBytes;
    private final long maximumTotalBytes;
    private final int maximumReferences;
    private long totalBytes;
    private final Map<String,Long> objects=new TreeMap<String,Long>();
    private final Map<String,Map<String,Object>> references=new TreeMap<String,Map<String,Object>>();

    public ReturnedBufferStore(Path root,int maximumObjectBytes,long maximumTotalBytes,int maximumReferences) throws IOException {
        if(maximumObjectBytes<=0||maximumTotalBytes<=0||maximumReferences<=0)
            throw new IllegalArgumentException("positive capture bounds required");
        Path absolute=root.toAbsolutePath().normalize();
        if(!absolute.equals(root)||absolute.getParent()==null
                ||!absolute.getParent().toRealPath().equals(absolute.getParent()))
            throw new IOException("unaliased absolute buffer root required");
        Files.createDirectory(absolute);
        this.root=absolute;this.maximumObjectBytes=maximumObjectBytes;
        this.maximumTotalBytes=maximumTotalBytes;this.maximumReferences=maximumReferences;
    }
    static ReturnedBufferStore configured() {
        String root=System.getProperty("conformance.capture.bufferRoot");
        if(root==null)throw new IllegalArgumentException("explicit fresh returned-buffer root required");
        try {
            return new ReturnedBufferStore(Paths.get(root),
                Integer.parseInt(System.getProperty("conformance.capture.maxObjectBytes","33554432")),
                Long.parseLong(System.getProperty("conformance.capture.maxTotalBytes","67108864")),
                Integer.parseInt(System.getProperty("conformance.capture.maxReferences","8192")));
        } catch(IOException failure){throw new java.io.UncheckedIOException(failure);}
    }
    public byte[] read(InputStream input) throws IOException {
        ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] block=new byte[8192];int count;
        while((count=input.read(block))!=-1) {
            if(count>maximumObjectBytes-output.size())throw new IOException("returned buffer exceeds object budget");
            output.write(block,0,count);
        }
        return output.toByteArray();
    }
    public synchronized void retain(String uri,String representation,byte[] bytes) throws IOException {
        if(uri==null||uri.isEmpty()||representation==null||representation.isEmpty())
            throw new IOException("buffer provenance required");
        if(bytes.length>maximumObjectBytes)throw new IOException("returned buffer exceeds object budget");
        String digest=digest(bytes),key=uri+"\0"+representation;
        Map<String,Object> old=references.get(key);
        if(old!=null) {
            if(!digest.equals(old.get("sha256")))throw new IOException("buffer reference changed");
            verifyObject(digest,bytes.length);return;
        }
        if(references.size()>=maximumReferences)throw new IOException("returned-buffer reference budget exceeded");
        if(!objects.containsKey(digest)) {
            if(bytes.length>maximumTotalBytes-totalBytes)throw new IOException("returned-buffer total budget exceeded");
            Files.write(root.resolve(digest),bytes,StandardOpenOption.CREATE_NEW);
            objects.put(digest,(long)bytes.length);totalBytes+=bytes.length;
        } else verifyObject(digest,bytes.length);
        Map<String,Object> row=new LinkedHashMap<String,Object>();
        row.put("uri",uri);row.put("representation",representation);row.put("sha256",digest);
        row.put("bytes",bytes.length);row.put("object",digest);
        row.put("point","WRAPPER_SNAPSHOT_AVAILABLE_TO_COMPILER_NOT_FULL_CONSUMPTION");
        references.put(key,row);
    }
    public void retainCharacters(String uri,CharSequence characters)throws IOException {
        if(characters.length()>maximumObjectBytes/2)throw new IOException("returned characters exceed object budget");
        byte[] units=new byte[characters.length()*2];
        for(int i=0;i<characters.length();i++){char c=characters.charAt(i);units[2*i]=(byte)(c>>>8);units[2*i+1]=(byte)c;}
        retain(uri,"SOURCE_UTF16BE_CODE_UNITS",units);
    }
    private void verifyObject(String digest,long size)throws IOException {
        Path path=root.resolve(digest);
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)!=size)
            throw new IOException("retained buffer missing or wrong size");
        byte[] value;
        try(InputStream input=Files.newInputStream(path)){value=read(input);}
        if(!digest.equals(digest(value)))throw new IOException("retained buffer changed");
    }
    public synchronized List<Map<String,Object>> manifest()throws IOException {
        for(Map.Entry<String,Long> object:objects.entrySet())verifyObject(object.getKey(),object.getValue());
        List<Map<String,Object>> result=new ArrayList<Map<String,Object>>();
        for(Map<String,Object> row:references.values())result.add(new LinkedHashMap<String,Object>(row));
        return result;
    }
    static String digest(byte[] value)throws IOException {
        try {
            StringBuilder hex=new StringBuilder();
            for(byte item:MessageDigest.getInstance("SHA-256").digest(value))hex.append(String.format("%02x",item&255));
            return hex.toString();
        } catch(java.security.NoSuchAlgorithmException impossible){throw new IOException(impossible);}
    }
}
