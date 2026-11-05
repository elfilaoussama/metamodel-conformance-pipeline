package org.metamodel.nativeworld;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.util.*;
import javax.tools.*;

/** Candidate only: captures the character sequence returned to javac, not file snapshots. */
public class ConsumedSourceFileManager extends ForwardingJavaFileManager<JavaFileManager> {
    private final Map<URI,Captured> captured;
    private final ReturnedBufferStore buffers;
    public ConsumedSourceFileManager(JavaFileManager delegate) {
        super(delegate);captured=new LinkedHashMap<URI,Captured>();buffers=ReturnedBufferStore.configured();
        RouteTrace.event("MANAGER",null,null,"delegate="+delegate.getClass().getName());
    }
    ConsumedSourceFileManager(JavaFileManager delegate,ConsumedSourceFileManager supplied) {
        super(delegate);captured=supplied.captured;buffers=supplied.buffers;
        RouteTrace.event("MANAGER_SHARED_STORE",null,null,"delegate="+delegate.getClass().getName());
    }
    private JavaFileObject unwrap(JavaFileObject value) {
        return value instanceof Captured ? ((Captured)value).original : value;
    }
    public JavaFileObject wrap(JavaFileObject value) throws IOException {
        if(value==null)return value;
        RouteTrace.event("WRAP",null,value,"returned object candidate, not read");
        URI uri=value.toUri();
        if(value.getKind()==JavaFileObject.Kind.SOURCE && !"file".equals(uri.getScheme()))throw new IOException("unqualified source URI scheme");
        Captured previous=captured.get(uri);
        if(previous!=null)return previous;
        Captured result=new Captured(value);captured.put(uri,result);return result;
    }
    public Iterable<JavaFileObject> explicit(Iterable<? extends JavaFileObject> files) throws IOException {
        List<JavaFileObject> result=new ArrayList<JavaFileObject>();
        for(JavaFileObject file:files)result.add(wrap(file));return result;
    }
    @Override public Iterable<JavaFileObject> list(Location location,String name,Set<JavaFileObject.Kind> kinds,boolean recurse) throws IOException {
        List<JavaFileObject> result=new ArrayList<JavaFileObject>();
        RouteTrace.event("LIST",location,null,name+" kinds="+kinds+" recurse="+recurse);
        for(JavaFileObject file:super.list(location,name,kinds,recurse)){RouteTrace.event("LIST_RESULT",location,file,name);result.add(wrap(file));}return result;
    }
    @Override public JavaFileObject getJavaFileForInput(Location location,String name,JavaFileObject.Kind kind) throws IOException {
        RouteTrace.event("JAVA_INPUT",location,null,name+" kind="+kind);
        JavaFileObject result=super.getJavaFileForInput(location,name,kind);RouteTrace.event("JAVA_INPUT_RESULT",location,result,name);return wrap(result);
    }
    @Override public FileObject getFileForInput(Location location,String name,String relativeName) throws IOException {
        RouteTrace.event("FILE_INPUT",location,null,name+"/"+relativeName);
        FileObject result=super.getFileForInput(location,name,relativeName);
        RouteTrace.event("FILE_INPUT_RESULT",location,result,name+"/"+relativeName);
        return result instanceof JavaFileObject?wrap((JavaFileObject)result):RouteTrace.generic(result,location);
    }
    @Override public boolean hasLocation(Location location) {
        boolean result=super.hasLocation(location);RouteTrace.event("HAS_LOCATION",location,null,"result="+result);return result;
    }
    @Override public ClassLoader getClassLoader(Location location) {
        RouteTrace.event("CLASS_LOADER",location,null,"");return super.getClassLoader(location);
    }
    @Override public String inferBinaryName(Location location,JavaFileObject file) {
        String result=super.inferBinaryName(location,unwrap(file));RouteTrace.event("INFER_BINARY_NAME",location,unwrap(file),result);return result;
    }
    @Override public boolean isSameFile(FileObject first,FileObject second) {
        FileObject a=first instanceof JavaFileObject?unwrap((JavaFileObject)first):RouteTrace.unwrapGeneric(first);
        FileObject b=second instanceof JavaFileObject?unwrap((JavaFileObject)second):RouteTrace.unwrapGeneric(second);
        return super.isSameFile(a,b);
    }
    public Map<String,String> consumedCharacterHashes() throws Exception {
        Map<String,String> result=new TreeMap<String,String>();
        for(Captured file:captured.values())if(file.characters!=null) {
            // Preserve exact UTF-16 code units, including isolated surrogates: no lossy encoding.
            String value=file.characters;byte[] bytes=new byte[value.length()*2];
            for(int i=0;i<value.length();i++){char c=value.charAt(i);bytes[2*i]=(byte)(c>>>8);bytes[2*i+1]=(byte)c;}
            StringBuilder hex=new StringBuilder();
            for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))hex.append(String.format("%02x",b&255));
            result.put(file.toUri().toASCIIString(),hex.toString());
        }
        return result;
    }
    public List<Map<String,Object>> retainedBuffers()throws IOException{return buffers.manifest();}
    public void verifySourceSnapshots()throws IOException {
        for(Captured file:captured.values())if(file.getKind()==JavaFileObject.Kind.SOURCE&&file.bytes!=null) {
            byte[] current;
            try(InputStream input=file.original.openInputStream()){current=buffers.read(input);}
            if(!Arrays.equals(current,file.bytes))throw new IOException("source backing changed after capture");
        }
    }
    public Map<String,String> consumedByteHashes(JavaFileObject.Kind kind) throws Exception {
        Map<String,String> result=new TreeMap<String,String>();
        for(Captured file:captured.values())if(file.getKind()==kind && file.bytes!=null) {
            StringBuilder hex=new StringBuilder();
            for(byte b:MessageDigest.getInstance("SHA-256").digest(file.bytes))hex.append(String.format("%02x",b&255));
            result.put(file.toUri().toASCIIString(),hex.toString());
        }
        return result;
    }
    private final class Captured extends ForwardingJavaFileObject<JavaFileObject> {
        final JavaFileObject original;String characters;Boolean ignoreErrors;byte[] bytes;
        Captured(JavaFileObject original){super(original);this.original=original;}
        private synchronized byte[] bytes() throws IOException {
            if(bytes==null)try(InputStream input=original.openInputStream()) {
                RouteTrace.event("BACKING_STREAM_OPEN",null,original,"wrapper buffer fetch, not full compiler consumption");
                bytes=buffers.read(input);buffers.retain(toUri().toASCIIString(),getKind().name()+"_BYTES",bytes);
                RouteTrace.event("BUFFER_FETCH",null,original,"bytes="+bytes.length+" sha256="+RouteTrace.sha(bytes));
            }
            return bytes;
        }
        @Override public synchronized CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
            RouteTrace.event("CHAR_REQUEST",null,original,"ignoreEncodingErrors="+ignoreEncodingErrors);
            if(getKind()!=JavaFileObject.Kind.SOURCE || ignoreEncodingErrors)throw new IOException("only strict UTF8 source decoding qualified");
            if(characters==null){characters=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes())).toString();ignoreErrors=ignoreEncodingErrors;
                buffers.retainCharacters(toUri().toASCIIString(),characters);
            }
            else if(ignoreErrors.booleanValue()!=ignoreEncodingErrors)throw new IOException("source decoding policy changed");
            RouteTrace.event("CHAR_RETURN",null,original,"UTF16CodeUnits="+characters.length());return characters;
        }
        @Override public Reader openReader(boolean ignoreEncodingErrors) throws IOException {
            return new StringReader(getCharContent(ignoreEncodingErrors).toString());
        }
        @Override public InputStream openInputStream() throws IOException {
            RouteTrace.event("WRAPPER_OPEN",null,original,"compiler-facing stream");
            return RouteTrace.stream(bytes(),original);
        }
    }
}
