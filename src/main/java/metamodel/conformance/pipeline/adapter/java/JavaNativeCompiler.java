package metamodel.conformance.pipeline.adapter.java;

import com.fasterxml.jackson.databind.ObjectMapper;
import metamodel.conformance.pipeline.adapter.ObservationException;
import metamodel.conformance.pipeline.model.*;
import metamodel.conformance.pipeline.util.Hashing;
import metamodel.conformance.pipeline.util.ToolProcessLease;
import metamodel.conformance.pipeline.util.ToolProcessPolicy;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Owns the selected compiler process. Subject code and annotation processors are never executed. */
final class JavaNativeCompiler {
    record Capture(JavaNativeReceipt receipt, Map<String,SourceUnit> sourcesByUri,
                   String toolIdentity, List<String> semanticArguments,
                   List<NativeEvidence.ReportedItem> reportedInputs) {
        Capture { sourcesByUri=Map.copyOf(sourcesByUri);semanticArguments=List.copyOf(semanticArguments);reportedInputs=List.copyOf(reportedInputs); }
        SourceUnit unit(String uri){return sourcesByUri.get(JavaNativeReceipt.sourceUri(uri));}
    }
    static final class CompilerFailure extends IOException {
        CompilerFailure(String message){super(message);}
    }
    private static final List<String> SOURCES=List.of("NativeSemanticWorld.java","ConsumedSourceFileManager.java","ReturnedBufferStore.java","RouteTrace.java");
    private static final List<String> EXPORTS=List.of("--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED");
    private record Original(Path path,String sha256) { }

    static Capture capture(JavaNativeConfiguration config,Path originalRoot,Path capturedRoot,List<SourceUnit> units)
            throws IOException,ObservationException,InterruptedException {
        Path workspace=Files.createTempDirectory("native-javac-world-").toRealPath();
        try {
            for(String name:List.of("JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS","CLASSPATH"))
                if(System.getenv(name)!=null && !System.getenv(name).isEmpty())throw new CompilerFailure("unqualified native JVM environment: "+name);
            if(!config.javaHome().toRealPath().equals(config.javaHome()))throw new CompilerFailure("aliased native compiler home");
            List<String> toolNames=config.feature()==8?List.of("bin/java","bin/javac","release","lib/tools.jar","jre/lib/rt.jar")
                    :List.of("bin/java","bin/javac","release","lib/modules","lib/ct.sym");
            List<Original> originals=new ArrayList<>();Map<String,String> tools=new TreeMap<>();
            for(String name:toolNames){Path file=config.javaHome().resolve(name);String digest=hashRegular(file);tools.put(name,digest);originals.add(new Original(file,digest));}
            Path helper=Files.createDirectory(workspace.resolve("helper-source")),classes=Files.createDirectory(workspace.resolve("helper-classes"));
            Map<String,String> resources=new TreeMap<>();List<String> compile=new ArrayList<>(List.of(config.javaHome().resolve("bin/javac").toString(),"-proc:none","-encoding","UTF-8","-source","8","-target","8","-d",classes.toString()));
            if(config.feature()==8)compile.addAll(List.of("-cp",config.javaHome().resolve("lib/tools.jar").toString()));
            else compile.addAll(List.of("-cp",Files.createDirectory(workspace.resolve("empty-helper-classpath")).toString()));
            for(String name:SOURCES){Path file=resource(helper,name,resources);compile.add(file.toString());}
            run(compile,workspace,"compile-native-helper",config.timeoutMillis());
            if(config.feature()>8) {
                Path bridge=resource(helper,"EffectiveManagerBridge.java",resources);
                List<String> bridgeCommand=new ArrayList<>(List.of(config.javaHome().resolve("bin/javac").toString(),"-proc:none","-encoding","UTF-8","-source",Integer.toString(config.feature()),"-target",Integer.toString(config.feature())));
                bridgeCommand.addAll(EXPORTS);bridgeCommand.addAll(List.of("-cp",classes.toString(),"-d",classes.toString(),bridge.toString()));
                run(bridgeCommand,workspace,"compile-effective-manager",config.timeoutMillis());
            }
            Map<String,String> helperClasses=inventory(classes);
            List<String> stagedClasspath=new ArrayList<>();List<NativeEvidence.ReportedItem> reported=new ArrayList<>();
            List<Path> directoryInputs=new ArrayList<>();Map<Path,Set<Path>> directoryPopulations=new HashMap<>();
            for(int i=0;i<config.classpath().size();i++) {
                Path input=config.classpath().get(i),destination=workspace.resolve("classpath-"+i);
                List<NativeEvidence.Input> files=new ArrayList<>();
                if(Files.isDirectory(input,LinkOption.NOFOLLOW_LINKS)) {
                    if(!input.toRealPath().equals(input))throw new CompilerFailure("aliased native classpath directory");
                    Files.createDirectory(destination);Set<Path> paths=files(input);directoryInputs.add(input);directoryPopulations.put(input,paths);
                    long total=0;
                    for(Path file:paths.stream().sorted().toList()) {
                        if((total+=Files.size(file))>256L*1024*1024)throw new CompilerFailure("native classpath snapshot exceeds budget");
                        Path target=destination.resolve(input.relativize(file));Files.createDirectories(target.getParent());Files.copy(file,target);
                        String digest=Hashing.sha256(target);originals.add(new Original(file,digest));files.add(input(file,digest));
                    }
                } else {
                    String digest=hashRegular(input);if(Files.size(input)>256L*1024*1024)throw new CompilerFailure("native archive exceeds snapshot budget");
                    Files.createDirectory(destination);destination=destination.resolve(input.getFileName());
                    Files.copy(input,destination);if(!Hashing.sha256(destination).equals(digest))throw new CompilerFailure("native classpath copy changed");
                    originals.add(new Original(input,digest));files.add(input(input,digest));
                }
                stagedClasspath.add(destination.toString());
                reported.add(new NativeEvidence.ReportedItem("classpath-"+i,input.toString(),NativeEvidence.Evidence.known(files),"Snapshot of the declared path, not full consumed-input or unique binary-origin closure"));
            }
            List<NativeEvidence.Input> toolFiles=toolNames.stream().map(name -> input(config.javaHome().resolve(name),tools.get(name))).toList();
            reported.add(new NativeEvidence.ReportedItem("selected-compiler",config.javaHome().toString(),NativeEvidence.Evidence.known(toolFiles),
                    "Selected compiler/runtime backing files verified before/after execution; complete loaded-image closure is unproven"));
            String toolIdentity=Hashing.sha256(new ObjectMapper().writeValueAsString(new TreeMap<>(Map.of("format",JavaNativeReceipt.FORMAT,
                    "toolFiles",tools,"helperSources",resources,"helperClasses",helperClasses))));
            if(stagedClasspath.isEmpty())stagedClasspath.add(Files.createDirectory(workspace.resolve("empty-classpath")).toString());
            List<String> options=new ArrayList<>(List.of("-encoding","UTF-8","-proc:none"));
            if(config.releaseLevel()!=null)options.addAll(List.of("--release",config.releaseLevel().toString()));
            if(config.sourceLevel()!=null)options.addAll(List.of("-source",config.sourceLevel().toString()));
            if(config.targetLevel()!=null)options.addAll(List.of("-target",config.targetLevel().toString()));
            List<String> semantic=new ArrayList<>(options);
            semantic.addAll(List.of("-classpath",String.join(File.pathSeparator,config.classpath().stream().map(Path::toString).toList()),"-sourcepath",originalRoot.toString()));
            semantic.addAll(units.stream().map(SourceUnit::path).sorted().toList());
            Path generated=Files.createDirectory(workspace.resolve("generated"));
            options.addAll(List.of("-classpath",String.join(File.pathSeparator,stagedClasspath),"-sourcepath",capturedRoot.toString(),"-d",generated.toString()));
            List<String> sources=units.stream().map(unit -> capturedRoot.resolve(unit.path()).toString()).sorted().toList();
            Map<String,String> hashes=new TreeMap<>();Map<String,SourceUnit> byUri=new TreeMap<>();
            for(SourceUnit unit:units){String uri=capturedRoot.resolve(unit.path()).toUri().toASCIIString();hashes.put(uri,unit.sha256());byUri.put(uri,unit);}
            String attempt=UUID.randomUUID().toString().replace("-","");
            Path facts=workspace.resolve("facts.jsonl"),buffers=workspace.resolve("buffers");
            List<String> command=new ArrayList<>(List.of(config.javaHome().resolve("bin/java").toString(),"-Xmx512m"));
            if(config.feature()>8){command.addAll(EXPORTS);command.add("-Dconformance.route.manager=effective-diagnostic");}
            command.addAll(List.of("-Dconformance.capture.bufferRoot="+buffers,"-Dconformance.route.trace="+workspace.resolve("route-events.jsonl"),
                    "-cp",classes+(config.feature()==8?File.pathSeparator+config.javaHome().resolve("lib/tools.jar"):""),
                    "org.metamodel.nativeworld.NativeSemanticWorld",facts.toString(),attempt));
            command.addAll(sources);command.add("--");command.addAll(options);
            run(command,workspace,"native-source-world",config.timeoutMillis());
            for(Original input:originals)if(!hashRegular(input.path()).equals(input.sha256()))throw new ObservationException("native Java backing input changed during execution: "+input.path());
            for(Path directory:directoryInputs)if(!files(directory).equals(directoryPopulations.get(directory)))throw new ObservationException("native classpath population changed");
            if(!inventory(classes).equals(helperClasses))throw new ObservationException("native compiler helper changed during execution");
            for(var source:resources.entrySet())if(!hashRegular(helper.resolve(source.getKey())).equals(source.getValue()))
                throw new ObservationException("native compiler helper source changed during execution");
            String runtimeHome=(config.feature()==8?config.javaHome().resolve("jre"):config.javaHome()).toString();
            JavaNativeReceipt receipt=JavaNativeReceipt.read(facts,buffers,new JavaNativeReceipt.Request(attempt,sources,options,hashes,config.feature(),runtimeHome),0);
            return new Capture(receipt,byUri,toolIdentity,semantic,reported);
        } finally {
            try(var files=Files.walk(workspace)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}
        }
    }
    private static Path resource(Path folder,String name,Map<String,String> resources) throws IOException {
        byte[] bytes;try(var input=JavaNativeCompiler.class.getResourceAsStream("/java-native/"+name)) {
            if(input==null)throw new IOException("native compiler resource missing: "+name);bytes=input.readNBytes(256*1024+1);
        }
        if(bytes.length>256*1024)throw new IOException("native helper source exceeds bound");
        Path file=folder.resolve(name);Files.write(file,bytes,StandardOpenOption.CREATE_NEW);resources.put(name,Hashing.sha256(bytes));return file;
    }
    private static NativeEvidence.Input input(Path path,String sha256) {
        return new NativeEvidence.Input(path.toString(),sha256,NativeEvidence.Evidence.unavailable(
                NativeEvidence.Knowledge.UNKNOWN,"File timestamps are not part of this reported Java inventory"));
    }
    private static String hashRegular(Path path) throws IOException {
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().equals(path.toAbsolutePath()))throw new IOException("unavailable or aliased native input: "+path);
        return Hashing.sha256(path);
    }
    private static Map<String,String> inventory(Path root) throws IOException {
        Map<String,String> result=new TreeMap<>();for(Path file:files(root))result.put(root.relativize(file).toString().replace('\\','/'),hashRegular(file));return result;
    }
    private static Set<Path> files(Path root) throws IOException {
        Set<Path> result=new HashSet<>();try(var entries=Files.walk(root)) {
            for(Path file:entries.toList()) {
                if(Files.isSymbolicLink(file))throw new IOException("aliased native tree input");
                if(Files.isDirectory(file,LinkOption.NOFOLLOW_LINKS))continue;
                if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || result.size()>=16384)throw new IOException("native input population exceeds profile");
                result.add(file);
            }
        }return Set.copyOf(result);
    }
    private static void run(List<String> argv,Path workspace,String label,long timeout) throws IOException,InterruptedException {
        long started=System.nanoTime();
        Path stdout=workspace.resolve(label+".stdout"),stderr=workspace.resolve(label+".stderr");
        ProcessBuilder builder=new ProcessBuilder(argv).directory(workspace.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        builder.environment().put("JAVA_HOME",Path.of(argv.get(0)).getParent().getParent().toString());
        try(var lease=ToolProcessLease.start(builder,new ToolProcessPolicy(timeout))) {
            int exit=lease.awaitExit();
            if(exit!=0) {
                String error=new String(JavaNativeReceipt.bounded(stderr,1024*1024),java.nio.charset.StandardCharsets.UTF_8)
                        .replace(workspace.toString(),"<native-workspace>");
                throw new CompilerFailure(label+" rejected input (exit "+exit+"): "+error);
            }
        } finally {
            if(Boolean.getBoolean("metamodel.conformance.java.native.profile"))System.err.printf(
                    "JAVA_NATIVE_STAGE label=%s elapsedMillis=%d budgetMillis=%d%n",label,(System.nanoTime()-started)/1_000_000,timeout);
        }
    }
}
