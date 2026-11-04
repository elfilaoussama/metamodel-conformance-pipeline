package org.metamodel.mavenframes;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.*;
import org.apache.maven.execution.MojoExecutionEvent;
import static org.metamodel.mavenframes.CompilerFrames.field;
import static org.metamodel.mavenframes.CompilerFrames.invoke;
import static org.metamodel.mavenframes.CompilerFrames.json;

/** Opt-in pinned compiler API interception. Selected inputs are not consumed-buffer proof. */
final class CompilerBoundary {
 private final Map<Object,Object> originalManagers=new IdentityHashMap<>();private long invocationSequence;
 synchronized long invocations(){return invocationSequence;}
 static Field member(Object target,String name)throws Exception {
  for(Class<?> c=target.getClass();c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);if(!f.trySetAccessible())throw new IllegalStateException(name);return f;}catch(NoSuchFieldException absent){}
  throw new IllegalStateException("field unavailable: "+name);
 }
 static Object call(Method method,Object target,Object[] args)throws Throwable {
  try{return method.invoke(target,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
 }
 synchronized void boundary(MojoExecutionEvent event,long frame,long invocation,String phase,Object compiler,Object configuration,Object result,Throwable failure,SourceMonitor monitor)throws Exception {
  var row=new LinkedHashMap<String,Object>();row.put("format","native-compiler-api-boundary-v1");row.put("frame",frame);row.put("invocation",invocation);row.put("phase",phase);row.put("consumptionCertified",false);row.put("compilerClass",compiler.getClass().getName());
  row.put("attempt",CompilerFrames.attempt(event.getSession()));
  if(monitor!=null)row.put("sourceMonitor",monitor.receipt(!phase.equals("START")));
  String resource="/"+compiler.getClass().getName().replace('.','/')+".class";
  try(InputStream in=compiler.getClass().getResourceAsStream(resource)){if(in==null)throw new IllegalStateException("compiler bytes unavailable");row.put("compilerClassSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())));}
  var config=new LinkedHashMap<String,Object>();
  for(String name:List.of("getSourceFiles","getSourceLocations","getClasspathEntries","getModulepathEntries","getOutputLocation","getSourceVersion","getTargetVersion","getReleaseVersion","getSourceEncoding","getProc","getProcessorPathEntries","isFork","getExecutable","getWorkingDirectory"))config.put(name,invoke(configuration,name));
  var arguments=new ArrayList<Object>();for(Object entry:(Collection<?>)invoke(configuration,"getCustomCompilerArgumentsEntries")){var e=(Map.Entry<?,?>)entry;var pair=new LinkedHashMap<String,Object>();pair.put("key",e.getKey());pair.put("value",e.getValue());arguments.add(pair);}config.put("orderedCustomArguments",arguments);row.put("configuration",config);
  var sources=new ArrayList<Object>();Object selected=invoke(configuration,"getSourceFiles");
  if(selected instanceof Collection<?> files){if(files.size()>65536)throw new IllegalStateException("selected-source count exceeds capture policy");long total=0;
   for(Object source:files){Path p=((File)source).toPath();if(!Files.isRegularFile(p,java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("nonregular selected source");long size=Files.size(p);total+=size;if(size>64*1024*1024 || total>256L*1024*1024)throw new IllegalStateException("selected-source bytes exceed capture policy");
    var digest=MessageDigest.getInstance("SHA-256");long read=0;try(InputStream in=Files.newInputStream(p)){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){read+=n;if(read>64*1024*1024)throw new IllegalStateException("growing source exceeds capture policy");digest.update(buffer,0,n);}}if(read!=size)throw new IllegalStateException("selected-source size changed during capture");
    var item=new LinkedHashMap<String,Object>();item.put("path",p.toString());item.put("sha256",HexFormat.of().formatHex(digest.digest()));sources.add(item);
   }
  }
  row.put("selectedSourceHashes",sources);row.put("compilerSuccess",result==null?null:invoke(result,"isSuccess"));row.put("throwableClass",failure==null?null:failure.getClass().getName());
  String file=event.getSession().getUserProperties().getProperty("conformance.compiler.receipt");if(file==null)throw new IllegalStateException("compiler receipt required");var encoded=StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(json(row)+"\n"));byte[] bytes=new byte[encoded.remaining()];encoded.get(bytes);if(bytes.length>8*1024*1024)throw new IllegalStateException("compiler row exceeds8MiB capture policy");Files.write(Path.of(file),bytes,StandardOpenOption.CREATE,StandardOpenOption.APPEND,StandardOpenOption.SYNC);
 }
 synchronized void install(MojoExecutionEvent event,long frame)throws Exception {
  Object mojo=event.getMojo();Field f=member(mojo,"compilerManager");Object manager=f.get(mojo);Class<?> managerApi=f.getType();
  Object proxy=java.lang.reflect.Proxy.newProxyInstance(managerApi.getClassLoader(),new Class<?>[]{managerApi},(ignored,method,args)->{
   Object value=call(method,manager,args);if(!method.getName().equals("getCompiler"))return value;
   Class<?> compilerApi=method.getReturnType();Object delegate=value;
   return java.lang.reflect.Proxy.newProxyInstance(compilerApi.getClassLoader(),new Class<?>[]{compilerApi},(unused,operation,values)->{
    if(!operation.getName().equals("performCompile"))return call(operation,delegate,values);
    long invocation; synchronized(this){invocation=++invocationSequence;}
    Object configuration=values[0];
    if(event.getSession().getUserProperties().containsKey("conformance.compiler.sourcePlugin") && !delegate.getClass().getName().equals("org.codehaus.plexus.compiler.javac.JavacCompiler"))throw new IllegalStateException("source monitor compiler implementation unqualified");
    SourceMonitor monitor=SourceMonitor.prepare(event,invocation,configuration);
    boundary(event,frame,invocation,"START",delegate,configuration,null,null,monitor);
    if(monitor!=null)monitor.activate();
    Object result;
    try{result=call(operation,delegate,values);}catch(Throwable failure){if(monitor!=null)monitor.restore();boundary(event,frame,invocation,"THROW",delegate,configuration,null,failure,monitor);throw failure;}
    if(monitor!=null)monitor.restore();
    boundary(event,frame,invocation,"RETURN",delegate,configuration,result,null,monitor);return result;
   });
  });originalManagers.put(mojo,manager);f.set(mojo,proxy);
 }
 synchronized void restore(MojoExecutionEvent event)throws Exception {Object original=originalManagers.remove(event.getMojo());if(original!=null)member(event.getMojo(),"compilerManager").set(event.getMojo(),original);}
}
