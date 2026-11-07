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
import org.apache.maven.execution.MojoExecutionListener;
import org.apache.maven.plugin.MojoExecutionException;

/** Pinned experimental native-Mojo metadata capture, not a javac-consumption certificate. */
@javax.inject.Named
@javax.inject.Singleton
public final class CompilerFrames extends org.apache.maven.eventspy.AbstractEventSpy implements MojoExecutionListener {
 private final Map<Object,Long> active=new IdentityHashMap<>();private long sequence;
 private final CompilerBoundary boundary=new CompilerBoundary();
 private String captureAttempt;
 static String attempt(org.apache.maven.execution.MavenSession session) {
  String value=session.getUserProperties().getProperty("conformance.compiler.attempt");
  if(value==null || !value.matches("[0-9a-f]{32}"))throw new IllegalStateException("fresh32-hex compiler attempt required");return value;
 }
 static Path receipt(org.apache.maven.execution.MavenSession session,String property) {
  String value=session.getUserProperties().getProperty(property);if(value==null || value.isBlank())throw new IllegalStateException("receipt property required: "+property);return Path.of(value).toAbsolutePath().normalize();
 }
 static Map<String,Object> artifact(Path path)throws Exception {
  if(!Files.isRegularFile(path,java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("nonregular capture artifact");
  var digest=MessageDigest.getInstance("SHA-256");long size=0;try(InputStream in=Files.newInputStream(path)){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){size+=n;if(size>64L*1024*1024)throw new IllegalStateException("capture artifact exceeds64MiB policy");digest.update(buffer,0,n);}}
  return Map.of("sha256",HexFormat.of().formatHex(digest.digest()),"bytes",size);
 }
 @Override public synchronized void onEvent(Object event)throws Exception {
  if(!(event instanceof org.apache.maven.execution.ExecutionEvent e) || !e.getSession().getUserProperties().containsKey("conformance.compiler.receipt"))return;
  if(e.getType()==org.apache.maven.execution.ExecutionEvent.Type.SessionStarted){
   if(captureAttempt!=null)throw new IllegalStateException("duplicate capture session");
   String id=attempt(e.getSession());Path metadata=receipt(e.getSession(),"conformance.frames.receipt"),compiler=receipt(e.getSession(),"conformance.compiler.receipt"),seal=receipt(e.getSession(),"conformance.compiler.session");
   if(Set.of(metadata,compiler,seal).size()!=3 || Files.exists(seal,java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("capture paths must be distinct and fresh");
   Files.createFile(metadata);Files.createFile(compiler);SourceMonitor.initialize(e.getSession());captureAttempt=id;
  }else if(e.getType()==org.apache.maven.execution.ExecutionEvent.Type.SessionEnded){
   if(captureAttempt==null || !captureAttempt.equals(attempt(e.getSession())))throw new IllegalStateException("capture session was not initialized");
   var seal=new LinkedHashMap<String,Object>();seal.put("format","native-maven-compiler-session-v1");seal.put("attempt",captureAttempt);seal.put("nativeSuccess",!e.getSession().getResult().hasExceptions());seal.put("frameCount",sequence);seal.put("activeFrameCount",active.size());seal.put("invocationCount",boundary.invocations());seal.put("metadata",artifact(receipt(e.getSession(),"conformance.frames.receipt")));seal.put("compiler",artifact(receipt(e.getSession(),"conformance.compiler.receipt")));
   Files.writeString(receipt(e.getSession(),"conformance.compiler.session"),json(seal)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,StandardOpenOption.SYNC);
  }
 }
 static void checkRuntime() throws Exception {
  var p=new Properties();try(InputStream in=CompilerFrames.class.getResourceAsStream("/META-INF/maven/org.apache.maven/maven-core/pom.properties")){if(in==null)throw new IllegalStateException("Maven identity unavailable");p.load(in);}
  if(!"3.9.16".equals(p.getProperty("version")) || Runtime.version().feature()!=17)throw new IllegalStateException("probe requires Maven3.9.16/JDK17");
 }
 public static void main(String[] args)throws Exception{checkRuntime();System.out.println("maven-3.9.16-compiler-3.16.0-native-mojo-metadata-v1");}
 static Object invoke(Object mojo,String name)throws Exception {
  for(Class<?> c=mojo.getClass();c!=null;c=c.getSuperclass())try {Method m=c.getDeclaredMethod(name);if(!m.trySetAccessible())throw new IllegalStateException("method inaccessible: "+name);return m.invoke(mojo);}catch(NoSuchMethodException absent) { }
  throw new IllegalStateException("required method missing: "+name);
 }
 static Object field(Object mojo,String name)throws Exception {
  for(Class<?> c=mojo.getClass();c!=null;c=c.getSuperclass())try {Field f=c.getDeclaredField(name);if(!f.trySetAccessible())throw new IllegalStateException("field inaccessible: "+name);return f.get(mojo);}catch(NoSuchFieldException absent) { }
  throw new IllegalStateException("required field missing: "+name);
 }
 static String quote(String s){StringBuilder b=new StringBuilder("\"");for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c=='"'||c=='\\')b.append('\\').append(c);else if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}return b.append('"').toString();}
 static String json(Object value) {
  if(value==null)return "null";if(value instanceof String s)return quote(s);if(value instanceof File f)return quote(f.getPath());if(value instanceof Boolean || value instanceof Number)return value.toString();
  if(value instanceof Collection<?> values){if(values.size()>65536)throw new IllegalArgumentException("collection exceeds capture policy");return "["+String.join(",",values.stream().map(CompilerFrames::json).toList())+"]";}
  if(value instanceof Map<?,?> values){var parts=new ArrayList<String>();for(var item:values.entrySet()){if(!(item.getKey() instanceof String))throw new IllegalArgumentException("nontextual capture key");parts.add(quote((String)item.getKey())+":"+json(item.getValue()));}return "{"+String.join(",",parts)+"}";}
  throw new IllegalArgumentException("unsupported capture value "+value.getClass().getName());
 }
 boolean compiler(MojoExecutionEvent e){return "org.apache.maven.plugins".equals(e.getExecution().getGroupId()) && "maven-compiler-plugin".equals(e.getExecution().getArtifactId());}
 synchronized void record(MojoExecutionEvent e,String phase)throws Exception {
  if(!compiler(e))return;
  checkRuntime();var execution=e.getExecution();Object mojo=e.getMojo();String goal=execution.getGoal();
  if(!"3.16.0".equals(execution.getVersion()) || !Set.of("compile","testCompile").contains(goal))throw new IllegalStateException("unsupported compiler plugin/goal");
  if(mojo==null)throw new IllegalStateException("configured compiler Mojo unavailable");
  long id;
  if(phase.equals("BEFORE")){if(active.containsKey(mojo))throw new IllegalStateException("duplicate active Mojo");id=++sequence;active.put(mojo,id);}
  else{Long previous=active.remove(mojo);if(previous==null)throw new IllegalStateException("terminal event without start");id=previous;}
  var row=new LinkedHashMap<String,Object>();row.put("format","native-maven-compiler-mojo-metadata-v1");row.put("consumptionCertified",false);row.put("frame",id);row.put("phase",phase);row.put("module",e.getProject().getBasedir().getPath());row.put("executionId",execution.getExecutionId());row.put("goal",goal);row.put("pluginVersion",execution.getVersion());row.put("mojoClass",mojo.getClass().getName());
  if(e.getSession().getUserProperties().containsKey("conformance.compiler.receipt")){if(captureAttempt==null || !captureAttempt.equals(attempt(e.getSession())))throw new IllegalStateException("native capture session unavailable");row.put("attempt",captureAttempt);}
  var fields=new LinkedHashMap<String,Object>();
  for(String name:List.of("getCompileSourceRoots","getOutputDirectory","getSource","getTarget","getRelease"))fields.put(name,invoke(mojo,name));
  fields.put("configuredClasspath",field(mojo,goal.equals("compile")?"compilePath":"testPath"));
  for(String name:List.of("compilerId","fork","executable","jdkToolchain","compilerArgs","compilerArgument","compilerArguments","encoding","enablePreview"))fields.put(name,field(mojo,name));
  // A successful Mojo may suppress compiler errors or bypass compilation entirely.
  // Preserve these controls; callback SUCCESS is deliberately not renamed compile success.
  for(String name:List.of("failOnError","proc","useIncrementalCompilation"))fields.put(name,field(mojo,name));
  fields.put("skipCompilation",field(mojo,goal.equals("compile")?"skipMain":"skip"));
  fields.put("effectiveCompilerArgument",invoke(mojo,"getCompilerArgument"));
  fields.put("effectiveCompilerArguments",invoke(mojo,"getCompilerArguments"));
  fields.put("includes",field(mojo,goal.equals("compile")?"includes":"testIncludes"));fields.put("excludes",field(mojo,goal.equals("compile")?"excludes":"testExcludes"));
  // TestCompilerMojo getters copy nullable fields and throw before preparePaths ran
  // (for example on skip/no-source exits). Read the pinned fields without invoking
  // that copy; null means unprepared, distinct from a prepared empty path.
  if(!phase.equals("BEFORE")){fields.put("preparedClasspath",field(mojo,"classpathElements"));fields.put("preparedModulepath",field(mojo,"modulepathElements"));}
  row.put("fields",fields);row.put("effectiveConfigurationXml",execution.getConfiguration().toString());row.put("causeType",e.getCause()==null?null:e.getCause().getClass().getName());
  String resource="/"+mojo.getClass().getName().replace('.','/')+".class";
  try(InputStream bytes=mojo.getClass().getResourceAsStream(resource)){if(bytes==null)throw new IllegalStateException("Mojo class bytes unavailable");row.put("mojoClassSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.readAllBytes())));}
  String file=e.getSession().getUserProperties().getProperty("conformance.frames.receipt");if(file==null)throw new IllegalStateException("receipt path required");var encoded=StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(json(row)+"\n"));byte[] bytes=new byte[encoded.remaining()];encoded.get(bytes);if(bytes.length>8*1024*1024)throw new IllegalStateException("capture exceeds8MiB row policy");
  Files.write(Path.of(file),bytes,StandardOpenOption.CREATE,StandardOpenOption.APPEND,StandardOpenOption.SYNC);
 }
 @Override public synchronized void beforeMojoExecution(MojoExecutionEvent e)throws MojoExecutionException{try{record(e,"BEFORE");if(compiler(e)&&e.getSession().getUserProperties().containsKey("conformance.compiler.receipt"))boundary.install(e,active.get(e.getMojo()));}catch(Exception problem){throw new MojoExecutionException("native frame capture failed",problem);}}
 @Override public synchronized void afterMojoExecutionSuccess(MojoExecutionEvent e)throws MojoExecutionException{try{boundary.restore(e);record(e,"SUCCESS");}catch(Exception problem){throw new MojoExecutionException("native frame capture failed",problem);}}
 @Override public synchronized void afterExecutionFailure(MojoExecutionEvent e){try{boundary.restore(e);record(e,"FAILURE");}catch(Exception problem){throw new IllegalStateException("native failure frame capture failed",problem);}}
}
