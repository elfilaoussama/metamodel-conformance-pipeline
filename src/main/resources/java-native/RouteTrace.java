package org.metamodel.nativeworld;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.tools.*;

/** External diagnostic event stream; candidates and buffer fetches are not read closure. */
public final class RouteTrace {
    private static final Path OUTPUT;
    private static int index, bytes;
    private static final Map<FileObject,FileObject> GENERIC=new IdentityHashMap<FileObject,FileObject>();
    public static synchronized FileObject unwrapGeneric(FileObject value){FileObject original=GENERIC.get(value);return original==null?value:original;}
    static {
        try {
            String path=System.getProperty("conformance.route.trace");
            if(path==null)throw new IllegalArgumentException("fresh external trace path required");
            OUTPUT=Paths.get(path);if(!OUTPUT.isAbsolute())throw new IllegalArgumentException("absolute trace path");
            for(Path p=OUTPUT;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IllegalArgumentException("aliased trace output");
            Files.createFile(OUTPUT);
        }catch(Exception failure){throw new ExceptionInInitializerError(failure);}
    }
    static String quote(String value){if(value==null)return "null";StringBuilder out=new StringBuilder("\"");for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c=='"'||c=='\\')out.append('\\').append(c);else if(c<32||c>126)out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else out.append(c);}return out.append('"').toString();}
    public static synchronized void event(String operation,JavaFileManager.Location location,FileObject object,String detail) {
        try {
            if(index>=65536)throw new IOException("trace event limit");
            String row="{\"format\":\"javac-file-manager-route-diagnostic-v1\",\"index\":"+(++index)+",\"operation\":"+quote(operation)
                    +",\"location\":"+quote(location==null?null:location.getName())+",\"locationClass\":"+quote(location==null?null:location.getClass().getName())
                    +",\"uri\":"+quote(object==null?null:object.toUri().toASCIIString())+",\"objectClass\":"+quote(object==null?null:object.getClass().getName())
                    +",\"objectKind\":"+quote(object instanceof JavaFileObject?((JavaFileObject)object).getKind().name():null)+",\"detail\":"+quote(detail)+"}\n";
            byte[] data=row.getBytes(StandardCharsets.UTF_8);bytes+=data.length;if(data.length>65536 || bytes>16*1024*1024)throw new IOException("trace byte limit");
            Files.write(OUTPUT,data,StandardOpenOption.APPEND,StandardOpenOption.SYNC);
        }catch(Exception failure){throw new IllegalStateException("route trace failed",failure);}
    }
    public static String sha(byte[] value) {try {StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(value))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}catch(Exception failure){throw new IllegalStateException(failure);}}
    public static InputStream stream(byte[] bytes,final FileObject original) {
        // Use the generic InputStream path so newer bulk-read methods cannot
        // inherit ByteArrayInputStream's direct array-copy bypass of read().
        return new FilterInputStream(new ByteArrayInputStream(bytes)) {
            long readCount;
            public synchronized int read() throws IOException {int result=in.read();if(result>=0)readCount++;event("STREAM_READ",null,original,"returned="+(result<0?-1:1));return result;}
            public synchronized int read(byte[] buffer,int offset,int length) throws IOException {int result=in.read(buffer,offset,length);if(result>0)readCount+=result;event("STREAM_READ",null,original,"returned="+result);return result;}
            public void close() throws IOException {event("STREAM_CLOSE",null,original,"totalReturned="+readCount);super.close();}
        };
    }
    public static FileObject generic(final FileObject original,final JavaFileManager.Location location) {
        if(original==null)return null;
        FileObject wrapped=new ForwardingFileObject<FileObject>(original) {
            public InputStream openInputStream() throws IOException {
                event("GENERIC_OPEN",location,original,"compiler-facing generic FileObject");
                return new FilterInputStream(original.openInputStream()) {
                    long count;
                    public int read()throws IOException{int n=in.read();if(n>=0)count++;event("GENERIC_READ",location,original,"returned="+(n<0?-1:1));return n;}
                    public int read(byte[] b,int off,int len)throws IOException{int n=in.read(b,off,len);if(n>0)count+=n;event("GENERIC_READ",location,original,"returned="+n);return n;}
                    public void close()throws IOException{event("GENERIC_CLOSE",location,original,"totalReturned="+count);super.close();}
                };
            }
            public CharSequence getCharContent(boolean ignore)throws IOException{event("GENERIC_CHARS",location,original,"ignoreEncodingErrors="+ignore);return original.getCharContent(ignore);}
            public Reader openReader(boolean ignore)throws IOException{event("GENERIC_READER",location,original,"ignoreEncodingErrors="+ignore);return original.openReader(ignore);}
        };
        synchronized(RouteTrace.class){GENERIC.put(wrapped,original);}
        return wrapped;
    }
}
