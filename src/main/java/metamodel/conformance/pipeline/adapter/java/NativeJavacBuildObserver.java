package metamodel.conformance.pipeline.adapter.java;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import metamodel.conformance.pipeline.adapter.*;
import metamodel.conformance.pipeline.model.*;
import metamodel.conformance.pipeline.util.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import static metamodel.conformance.pipeline.adapter.java.JavaNativeReceipt.*;

/** Imports guarded actual-task source frames. No host attribution or binary-origin inference. */
public final class NativeJavacBuildObserver implements SourceObserver {
    private static final List<String> GUARDS=List.of("frame_receipt.py","compiler_receipt.py","parser_receipt.py","retained_inputs.py",
            "semantic_receipt.py","javac_response.py","native_context_bundle.py","native_source_bundle.py");
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Path bundle;
    public NativeJavacBuildObserver(Path bundle){this.bundle=Objects.requireNonNull(bundle).toAbsolutePath().normalize();}

    @Override public Observation observe(Path sourceRoot,Set<String> externalParents)throws ObservationException {
        if(externalParents!=null && !externalParents.isEmpty())throw new ObservationException("Native author-task facts cannot be extended by an external-parent allowlist");
        try {
            Path root=SpoonJavaObserver.validateRoot(sourceRoot);
            try(var snapshot=SourceInputSnapshot.capture(root,SpoonJavaObserver.discoverJavaFiles(root))) {
                JsonNode descriptor=verify();List<SourceUnit> units=snapshot.units(Language.JAVA);Map<String,SourceUnit> byPath=new TreeMap<>();
                units.forEach(unit->byPath.put(unit.path(),unit));JsonNode inventory=descriptor.get("inventory");
                require(inventory!=null && inventory.isObject() && inventory.size()==units.size(),"native bundle/source census differs");
                for(SourceUnit unit:units)require(unit.sha256().equals(text(inventory,unit.path())),"native bundle/source bytes differ: "+unit.path());
                Map<String,Observation> frames=new TreeMap<>();
                for(JsonNode world:array(descriptor,"worlds")) {
                    String contextId=text(world,"contextId");long invocation=integer(world,"invocation",1);
                    Map<String,SourceUnit> byUri=new TreeMap<>();Map<String,String> characters=new TreeMap<>();Set<ParsedTypeSpan> spans=new HashSet<>();
                    for(JsonNode source:array(world,"sources")) {
                        String path=text(source,"path"),uri=text(source,"uri");SourceUnit unit=byPath.get(path);
                        require(unit!=null && unit.sha256().equals(text(source,"sha256")),"native source does not bind to selected source snapshot");
                        require(byUri.put(sourceUri(uri),unit)==null,"duplicate native source URI");
                        String content=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bounded(snapshot.root().resolve(path),32*1024*1024))).toString();
                        characters.put(uri,content);
                        for(JsonNode declared:array(source,"declarations"))require(spans.add(new ParsedTypeSpan(uri,integer(declared,"start",0),integer(declared,"end",1))),"duplicate parsed native class span");
                    }
                    JsonNode toolchain=world.get("toolchain");
                    var request=new BuildRequest(text(descriptor,"attempt"),invocation,Math.toIntExact(integer(world,"feature",1)),text(toolchain,"runtimeHome"),text(toolchain,"javaVersion"),characters,spans);
                    Path facts=artifact("parsed/invocation-"+invocation+".semantic.jsonl");
                    JavaNativeReceipt receipt=readBuild(facts,request,0);require(receipt.sha256.equals(text(world,"factsSha256")),"native semantic artifact changed after validation");
                    JsonNode recorded=world.get("invocationReceipt");List<String> arguments=strings(recorded.get("response").get("arguments"));
                    String toolIdentity=Hashing.sha256(JSON.writeValueAsString(List.of(toolchain,text(world,"compilerClassSha256"),
                            descriptor.get("files").get("collector.jar"),descriptor.get("files").get("source-plugin.jar"))));
                    List<NativeEvidence.ReportedItem> reported=reportedInputs(descriptor,world,invocation);
                    var capture=new JavaNativeCompiler.Capture(receipt,byUri,toolIdentity,arguments,reported);
                    List<String> roots=strings(world.get("sourceRoots"));
                    var context=new JavaCompilationContext(contextId,text(world,"module"),roots,List.of(),List.of(),List.of(),Set.of(),JavaCompilerSemantics.unknown(),
                            byUri.values().stream().map(SourceUnit::path).collect(java.util.stream.Collectors.toSet()));
                    var nativeInvocation=new NativeEvidence.Invocation(contextId,text(recorded,"actualExecutable"),text(recorded,"workingDirectory"),arguments,toolIdentity,
                            NativeEvidence.Evidence.unavailable(NativeEvidence.Knowledge.UNKNOWN,"Captured invocation and resolution snapshots are not complete consumed-buffer/unique binary-origin evidence"),NativeEvidence.Evidence.known(List.of()));
                    String identity=Hashing.sha256(JSON.writeValueAsString(List.of(text(descriptor,"bundleSha256"),contextId,receipt.sha256,toolIdentity,Hashing.sourceSetDigest(byUri.values().stream().toList()))));
                    var scope=new JavaNativeProjection.World(context,NativeEvidence.MeasurementMode.SELECTED_BUILD,identity,
                            "Source declarations observed in the actual successful author javac task. Native PARSE/census and source snapshots are bound; full consumed-input and unique binary-origin closure remain unproven.",nativeInvocation);
                    require(frames.put(contextId,JavaNativeProjection.project(capture,scope))==null,"duplicate native build world");
                }
                Observation result=QualifiedObservationMerger.merge(frames);
                Set<String> unowned=new TreeSet<>(strings(array(descriptor,"unownedSources")));
                if(!unowned.isEmpty()) {
                    var evidence=new HashSet<>(result.completeEvidence());evidence.remove(EvidenceKind.DECLARATION_OWNERSHIP);
                    var diagnostics=new ArrayList<>(result.diagnostics());
                    for(String path:unowned)diagnostics.add(new ObservationDiagnostic(DiagnosticKind.EVIDENCE_INCOMPLETE,path,0,"Source has no native compiler ownership; totality remains incomplete"));
                    result=new Observation(result.schemaVersion(),result.adapterId(),result.adapterVersion(),result.externalParents(),evidence,units,result.classifiers(),result.members(),result.methodBodies(),result.implementationBindings(),result.unresolvedParents(),diagnostics,result.platformEvidence(),result.nativeEvidence());
                }
                snapshot.verifyOriginalsUnchanged(SpoonJavaObserver.discoverJavaFiles(root));
                require(text(verify(),"bundleSha256").equals(text(descriptor,"bundleSha256")),"native bundle changed during import");
                return result;
            }
        } catch(Exception failure) {
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            throw new ObservationException("Native Java build-source admission failed: "+failure.getMessage(),failure);
        }
    }

    private Path artifact(String relative)throws java.io.IOException {
        Path path=bundle.resolve(relative).normalize();require(path.startsWith(bundle) && path.toRealPath().equals(path),"native bundle artifact alias/outside root");return path;
    }
    private List<NativeEvidence.ReportedItem> reportedInputs(JsonNode descriptor,JsonNode world,long invocation) {
        List<NativeEvidence.ReportedItem> result=new ArrayList<>();List<NativeEvidence.Input> capture=new ArrayList<>();
        for(String name:List.of("collector.jar","source-plugin.jar","capture/metadata.jsonl","capture/compiler.jsonl","capture/session.jsonl","parsed/invocation-"+invocation+".semantic.jsonl"))
            capture.add(input(bundle.resolve(name).toString(),text(descriptor.get("files").get(name),"sha256")));
        capture.add(input(bundle.resolve("bundle.json").toString(),text(descriptor,"bundleSha256")));
        result.add(new NativeEvidence.ReportedItem("native-capture",bundle.toString(),NativeEvidence.Evidence.known(capture),"Guarded recorded capture; identities bind artifacts, not adversarial attestation or read closure"));
        JsonNode toolchain=world.get("toolchain");List<NativeEvidence.Input> tools=new ArrayList<>();
        toolchain.get("files").fields().forEachRemaining(entry->tools.add(input(text(toolchain,"javaHome")+"/"+entry.getKey(),text(entry.getValue(),"sha256"))));
        result.add(new NativeEvidence.ReportedItem("selected-compiler",text(toolchain,"javaHome"),NativeEvidence.Evidence.known(tools),"Selected compiler backing identities observed before/after the author compilation"));
        JsonNode resolution=world.get("retainedInputs").get("resolution");
        for(String role:List.of("classpath","processorPath")) {
            JsonNode entries=resolution.get(role);if(entries==null || entries.isNull())continue;int index=0;
            for(JsonNode entry:entries) {
                List<NativeEvidence.Input> files=new ArrayList<>();String original=text(entry,"original");
                for(JsonNode node:array(entry,"inventory"))if(text(node,"kind").equals("FILE")) {
                    String name=text(node,"path");files.add(input(name.equals(".")?original:original+"/"+name,text(node,"sha256")));
                }
                result.add(new NativeEvidence.ReportedItem(role+"-"+(index++),original,NativeEvidence.Evidence.known(files),"Ordered before-call resolution snapshot; no unique consumed binary correspondence is inferred"));
            }
        }
        return List.copyOf(result);
    }
    private static NativeEvidence.Input input(String path,String sha){return new NativeEvidence.Input(path,sha,NativeEvidence.Evidence.unavailable(NativeEvidence.Knowledge.UNKNOWN,"Timestamp is not part of this reported identity"));}

    private JsonNode verify()throws Exception {
        Path workspace=Files.createTempDirectory("native-java-build-guard-").toRealPath();
        try {
            for(String name:GUARDS)try(var stream=NativeJavacBuildObserver.class.getResourceAsStream("/java-native-build/"+name)) {
                require(stream!=null,"native build guard resource missing: "+name);Files.copy(stream,workspace.resolve(name));
            }
            Path stdout=workspace.resolve("validated.json"),stderr=workspace.resolve("stderr");
            String python=System.getProperty("metamodel.conformance.python","python3");require(!python.isBlank(),"native build validation interpreter required");
            ProcessBuilder process=new ProcessBuilder(python,"-I","-S",workspace.resolve("native_source_bundle.py").toString(),"verify","--bundle",bundle.toString())
                    .directory(workspace.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
            try(var lease=ToolProcessLease.start(process,ToolProcessPolicy.configured())) {
                int exit=lease.awaitExit();if(exit!=0)throw new java.io.IOException("native source bundle guard rejected input: "+new String(bounded(stderr,1024*1024),StandardCharsets.UTF_8));
            }
            return JSON.readTree(bounded(stdout,8*1024*1024));
        } finally {
            try(var files=Files.walk(workspace)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
    }
}
